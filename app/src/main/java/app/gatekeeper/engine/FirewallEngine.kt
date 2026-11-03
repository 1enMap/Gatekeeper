package app.gatekeeper.engine

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.annotation.MainThread
import app.gatekeeper.data.AppRule
import app.gatekeeper.data.Mode
import app.gatekeeper.data.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Turns rules + "what is on screen right now" into the set of packages that must be blocked.
 *
 * blocked = every BLOCKED app + every WHILE_OPEN app that is not temporarily allowed.
 *
 * A WHILE_OPEN app becomes temporarily allowed when it comes to the foreground and stays allowed
 * until its grace timer fires after it leaves the foreground (or the screen turns off).
 *
 * All foreground callbacks must be made on the main thread; [scope] must use the main dispatcher.
 */
class FirewallEngine(
    context: Context,
    rules: RuleRepository,
    private val scope: CoroutineScope,
) {
    private val ownPackage = context.packageName
    private val powerManager = context.getSystemService(PowerManager::class.java)

    /** null until Room has delivered the rules for the first time. */
    private val rulesState: StateFlow<Map<String, AppRule>?> =
        rules.rules.stateIn(scope, SharingStarted.Eagerly, null)

    /** WHILE_OPEN apps that currently have internet (on screen, or inside their grace period). */
    private val temporarilyAllowed = MutableStateFlow<Set<String>>(emptySet())
    val allowedNow: StateFlow<Set<String>> = temporarilyAllowed.asStateFlow()

    /**
     * Packages that should be routed into the tunnel and dropped.
     * null means "rules not loaded yet" and must not be acted on.
     */
    val blockedPackages: StateFlow<Set<String>?> =
        combine(rulesState, temporarilyAllowed) { rules, allowed ->
            rules?.values
                ?.filter { rule ->
                    rule.packageName != ownPackage && when (rule.mode) {
                        Mode.ALLOWED -> false
                        Mode.BLOCKED -> true
                        Mode.WHILE_OPEN -> rule.packageName !in allowed
                    }
                }
                ?.mapTo(HashSet()) { it.packageName }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _foreground = MutableStateFlow<String?>(null)
    val foreground: StateFlow<String?> = _foreground.asStateFlow()

    /** Last foreground app before the screen went off; restored on unlock. */
    private var foregroundBeforeScreenOff: String? = null

    /**
     * The app most recently on screen, kept across screen off. Unlike [foreground] it is not
     * cleared when the screen turns off, so it still names the app the user unlocks back into
     * before any new window event arrives.
     */
    val lastForeground: String? get() = _foreground.value ?: foregroundBeforeScreenOff

    private val reblockTimers = HashMap<String, Job>()

    private val _activity = MutableStateFlow<List<ActivityEntry>>(emptyList())

    /** Recent allow/block decisions, newest first, kept in memory only. */
    val activity: StateFlow<List<ActivityEntry>> = _activity.asStateFlow()

    /** Adds a line to the in-app activity log (also safe to call from other threads). */
    fun log(packageName: String?, message: String) {
        Log.d(TAG, "${packageName ?: "-"}: $message")
        val entry = ActivityEntry(System.currentTimeMillis(), packageName, message)
        _activity.update { (listOf(entry) + it).take(MAX_ACTIVITY) }
    }

    init {
        // A WHILE_OPEN app may already be on screen before Room has delivered the rules.
        scope.launch {
            rulesState.filterNotNull().first()
            _foreground.value?.let { pkg ->
                if (modeOf(pkg) == Mode.WHILE_OPEN) temporarilyAllowed.update { it + pkg }
            }
        }
    }

    @MainThread
    fun onForegroundChanged(pkg: String) {
        foregroundBeforeScreenOff = null
        val previous = _foreground.value
        _foreground.value = pkg
        if (previous != null && previous != pkg) startGraceTimer(previous, screenOff = false)

        if (modeOf(pkg) == Mode.WHILE_OPEN) {
            val wasPending = reblockTimers.remove(pkg)?.cancel() != null
            if (pkg !in temporarilyAllowed.value || wasPending) log(pkg, "On screen: internet allowed")
            temporarilyAllowed.update { it + pkg }
        }
    }

    @MainThread
    fun onScreenOff() {
        val current = _foreground.value ?: return
        foregroundBeforeScreenOff = current
        _foreground.value = null
        startGraceTimer(current, screenOff = true)
    }

    /** Screen unlocked: the user is usually back in the app that was open before. */
    @MainThread
    fun onScreenUnlocked() {
        val pkg = foregroundBeforeScreenOff ?: return
        onForegroundChanged(pkg)
    }

    /** Called when a rule changes so stale temporary grants don't linger. */
    @MainThread
    fun onRuleChanged(pkg: String, mode: Mode) {
        if (mode != Mode.WHILE_OPEN) {
            reblockTimers.remove(pkg)?.cancel()
            temporarilyAllowed.update { it - pkg }
        } else if (_foreground.value == pkg) {
            temporarilyAllowed.update { it + pkg }
        }
    }

    private fun modeOf(pkg: String): Mode = rulesState.value?.get(pkg)?.mode ?: Mode.ALLOWED

    private fun startGraceTimer(pkg: String, screenOff: Boolean) {
        if (pkg !in temporarilyAllowed.value) return
        reblockTimers.remove(pkg)?.cancel()
        val graceSeconds = rulesState.value?.get(pkg)?.graceSeconds ?: AppRule.DEFAULT_GRACE_SECONDS
        val why = if (screenOff) "Screen off" else "Left the app"
        if (graceSeconds <= 0) {
            log(pkg, "$why: blocked")
            temporarilyAllowed.update { it - pkg }
            return
        }
        log(pkg, "$why: blocked in $graceSeconds s")
        // With the screen off the CPU may sleep and delay the timer; hold a bounded wake lock
        // so the app really loses internet when its grace period ends.
        val wakeLock = if (screenOff) {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Gatekeeper:grace").apply {
                setReferenceCounted(false)
                acquire((graceSeconds + 5) * 1000L)
            }
        } else {
            null
        }
        reblockTimers[pkg] = scope.launch {
            try {
                delay(graceSeconds * 1000L)
                log(pkg, "Grace period over: blocked")
                temporarilyAllowed.update { it - pkg }
                reblockTimers.remove(pkg)
            } finally {
                // Keep the lock until the rebuild has had time to run (debounce is 300 ms).
                if (wakeLock?.isHeld == true) scope.launch {
                    delay(2_000)
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }

    private companion object {
        const val TAG = "FirewallEngine"
        const val MAX_ACTIVITY = 50
    }
}

data class ActivityEntry(val timeMillis: Long, val packageName: String?, val message: String)
