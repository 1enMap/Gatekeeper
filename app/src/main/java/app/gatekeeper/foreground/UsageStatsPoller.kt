package app.gatekeeper.foreground

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import app.gatekeeper.gatekeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fallback foreground detection for users who decline the accessibility service.
 * Polls UsageStatsManager every [INTERVAL_MS]; reacts more slowly than accessibility events.
 */
class UsageStatsPoller(private val context: Context) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var lastQueryEnd = System.currentTimeMillis() - 10_000
    private var lastReported: String? = null

    /** Runs until [scope] is cancelled. [shouldPoll] is checked on every tick. */
    fun start(scope: CoroutineScope, shouldPoll: () -> Boolean): Job = scope.launch {
        while (isActive) {
            if (power.isInteractive && shouldPoll() && hasUsageAccess(context)) {
                val pkg = withContext(Dispatchers.IO) { latestResumedPackage() }
                if (pkg != null && pkg != lastReported && !context.gatekeeper.foregroundFilter.isIgnored(pkg)) {
                    lastReported = pkg
                    context.gatekeeper.engine.onForegroundChanged(pkg)
                }
            } else {
                lastQueryEnd = System.currentTimeMillis()
                lastReported = null
            }
            delay(INTERVAL_MS)
        }
    }

    private fun latestResumedPackage(): String? {
        val end = System.currentTimeMillis()
        val events = usageStats.queryEvents(lastQueryEnd, end)
        lastQueryEnd = end
        var latest: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
        }
        return latest
    }

    companion object {
        const val INTERVAL_MS = 1_500L

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION") // Replacement needs API 36; this is fine on all supported versions.
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
