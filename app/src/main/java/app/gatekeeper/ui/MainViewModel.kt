package app.gatekeeper.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gatekeeper.FirewallController
import app.gatekeeper.data.AppRule
import app.gatekeeper.data.InstalledApp
import app.gatekeeper.data.InstalledApps
import app.gatekeeper.data.Mode
import app.gatekeeper.data.SettingsState
import app.gatekeeper.engine.ActivityEntry
import app.gatekeeper.foreground.ForegroundAccessibilityService
import app.gatekeeper.foreground.UsageStatsPoller
import app.gatekeeper.gatekeeper
import app.gatekeeper.tor.TorState
import app.gatekeeper.vpn.GatekeeperVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortOrder { NAME, MODE }

data class AppRow(
    val app: InstalledApp,
    val mode: Mode,
    val graceSeconds: Int,
    /** Currently has internet (Allowed, or While open and on screen / in grace period). */
    val hasInternetNow: Boolean,
)

data class ListFilters(
    val query: String = "",
    val showSystem: Boolean = false,
    val sort: SortOrder = SortOrder.NAME,
)

/** Permission / system state that can only be checked by asking, refreshed on every resume. */
data class SystemChecks(
    val vpnConsent: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val usageAccess: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val notificationsAllowed: Boolean = true,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.gatekeeper

    val settings: StateFlow<SettingsState?> = app.settings.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val vpnStatus: StateFlow<GatekeeperVpnService.Status> = GatekeeperVpnService.status
    val torState: StateFlow<TorState> = app.tor.state
    val accessibilityRunning: StateFlow<Boolean> = ForegroundAccessibilityService.running
    val activity: StateFlow<List<ActivityEntry>> = app.engine.activity

    private val _checks = MutableStateFlow(SystemChecks())
    val checks: StateFlow<SystemChecks> = _checks.asStateFlow()

    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val _filters = MutableStateFlow(ListFilters())
    val filters: StateFlow<ListFilters> = _filters.asStateFlow()

    private val rules = app.rules.rules.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** All installed apps with their rules, unfiltered (used for detail screen and counts). */
    val allRows: StateFlow<List<AppRow>?> =
        combine(installed, rules, app.engine.allowedNow, settings) { apps, rules, allowedNow, settings ->
            apps?.map { a ->
                val rule = rules[a.packageName]
                val mode = rule?.mode ?: Mode.ALLOWED
                AppRow(
                    app = a,
                    mode = mode,
                    graceSeconds = rule?.graceSeconds ?: settings?.defaultGraceSeconds ?: AppRule.DEFAULT_GRACE_SECONDS,
                    hasInternetNow = when (mode) {
                        Mode.ALLOWED -> true
                        Mode.BLOCKED -> false
                        Mode.WHILE_OPEN -> a.packageName in allowedNow
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val rows: StateFlow<List<AppRow>?> = combine(allRows, _filters) { rows, f ->
        rows?.asSequence()
            ?.filter { f.showSystem || !it.app.isSystem }
            ?.filter {
                f.query.isBlank() ||
                    it.app.label.contains(f.query, ignoreCase = true) ||
                    it.app.packageName.contains(f.query, ignoreCase = true)
            }
            ?.sortedWith(
                when (f.sort) {
                    SortOrder.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.app.label }
                    SortOrder.MODE -> compareBy<AppRow> { modeOrder(it.mode) }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.app.label }
                },
            )
            ?.toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        refreshApps()
    }

    /** Other installed apps that share [uid] (and therefore its network rule). */
    fun sharedUidApps(packageName: String, uid: Int): List<InstalledApp> =
        installed.value.orEmpty().filter { it.uid == uid && it.packageName != packageName }

    fun refreshApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { InstalledApps.load(getApplication()) }
            installed.value = apps
            // Clean up rules of apps that were uninstalled while we weren't listening.
            val present = apps.mapTo(HashSet()) { it.packageName }
            val stale = rules.first().keys.filter { it !in present && !InstalledApps.isInstalled(getApplication(), it) }
            if (stale.isNotEmpty()) withContext(Dispatchers.IO) { app.rules.removeAll(stale) }
        }
    }

    fun refreshChecks() {
        val ctx = getApplication<Application>()
        _checks.value = SystemChecks(
            vpnConsent = FirewallController.hasVpnConsent(ctx),
            accessibilityEnabled = ForegroundAccessibilityService.isEnabledInSettings(ctx),
            usageAccess = UsageStatsPoller.hasUsageAccess(ctx),
            batteryUnrestricted = ctx.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(ctx.packageName),
            notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    fun setQuery(q: String) = _filters.value.let { _filters.value = it.copy(query = q) }
    fun setShowSystem(show: Boolean) = _filters.value.let { _filters.value = it.copy(showSystem = show) }
    fun setSort(sort: SortOrder) = _filters.value.let { _filters.value = it.copy(sort = sort) }

    fun setMode(pkg: String, mode: Mode) = app.setMode(pkg, mode)
    fun setGrace(pkg: String, seconds: Int) = app.setGrace(pkg, seconds)

    fun setFirewall(enabled: Boolean): Boolean {
        val ctx = getApplication<Application>()
        return if (enabled) FirewallController.enable(ctx) else {
            FirewallController.disable(ctx)
            true
        }
    }

    fun setNewAppMode(mode: Mode) = viewModelScope.launch { app.settings.setNewAppMode(mode) }
    fun setDefaultGrace(seconds: Int) = viewModelScope.launch { app.settings.setDefaultGrace(seconds) }
    fun setUsageFallback(enabled: Boolean) = viewModelScope.launch { app.settings.setUsageStatsFallback(enabled) }
    fun setOnboardingDone(done: Boolean) = viewModelScope.launch { app.settings.setOnboardingDone(done) }
    fun setTorMode(enabled: Boolean) = viewModelScope.launch { app.settings.setTorMode(enabled) }
    fun newTorIdentity() = app.tor.newIdentity()

    /** After a failure: start Tor again (only while the firewall runs it). */
    fun retryTor() {
        if (!GatekeeperVpnService.status.value.running) return
        app.tor.stop()
        app.tor.start()
    }

    private fun modeOrder(mode: Mode) = when (mode) {
        Mode.BLOCKED -> 0
        Mode.WHILE_OPEN -> 1
        Mode.ALLOWED -> 2
    }
}
