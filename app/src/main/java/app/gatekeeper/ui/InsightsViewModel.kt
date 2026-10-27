package app.gatekeeper.ui

import android.app.Application
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gatekeeper.data.ConnectionAttempt
import app.gatekeeper.data.SensorEvent
import android.location.LocationManager
import app.gatekeeper.data.InstalledApps
import app.gatekeeper.foreground.UsageStatsPoller
import app.gatekeeper.gatekeeper
import app.gatekeeper.insights.AppAudit
import app.gatekeeper.insights.DataUsage
import app.gatekeeper.insights.SecurityAudit
import app.gatekeeper.insights.TrackerDomains
import app.gatekeeper.insights.UsageRow
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UsagePeriod(val label: String, val millis: Long) {
    DAY("24 hours", 24L * 60 * 60 * 1000),
    WEEK("7 days", 7L * 24 * 60 * 60 * 1000),
    MONTH("30 days", 30L * 24 * 60 * 60 * 1000),
}

data class UsageItem(val row: UsageRow, val packageName: String?, val label: String)

sealed interface UsageState {
    data object NeedsPermission : UsageState
    data object Loading : UsageState
    data class Ready(val items: List<UsageItem>) : UsageState
}

data class Destination(
    val name: String,
    val protocol: String,
    val count: Int,
    val lastMillis: Long,
    val tracker: String?,
    /** An encrypted DNS (DoT, port 853) look-up: the name it wanted is hidden. */
    val encryptedDns: Boolean,
    /** How many of [count] were let through by the live monitor (the rest were blocked). */
    val allowed: Int = 0,
    val bytes: Long = 0,
)

data class AppConnections(
    val packageName: String?,
    val label: String,
    val total: Int,
    val trackerAttempts: Int,
    val lastMillis: Long,
    val destinations: List<Destination>,
)

data class NightWatch(val fromMillis: Long, val toMillis: Long, val apps: List<AppConnections>)

data class ConnectionLog(
    val apps: List<AppConnections>,
    val timeline: List<ConnectionAttempt>,
    /** Blocked attempts in the last 24 hours. */
    val totalLast24h: Int,
    val trackersLast24h: Int,
    /** Connections the live monitor let through in the last 24 hours. */
    val allowedLast24h: Int = 0,
    val nightWatch: NightWatch?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.gatekeeper
    private val labelCache = ConcurrentHashMap<String, String>()

    private val _period = MutableStateFlow(UsagePeriod.WEEK)
    val period: StateFlow<UsagePeriod> = _period.asStateFlow()

    private val _usage = MutableStateFlow<UsageState>(UsageState.Loading)
    val usage: StateFlow<UsageState> = _usage.asStateFlow()

    private val _audit = MutableStateFlow<List<AppAudit>?>(null)
    val audit: StateFlow<List<AppAudit>?> = _audit.asStateFlow()

    /** 7-day usage for the audit and the app detail screen, keyed by UID. */
    private val _weekUsage = MutableStateFlow<Map<Int, UsageRow>>(emptyMap())
    val weekUsage: StateFlow<Map<Int, UsageRow>> = _weekUsage.asStateFlow()

    /** The app that used the most data in the background over the last 7 days. */
    private val _topBackground = MutableStateFlow<UsageItem?>(null)
    val topBackground: StateFlow<UsageItem?> = _topBackground.asStateFlow()

    private val _privateDnsStrict = MutableStateFlow(false)
    val privateDnsStrict: StateFlow<Boolean> = _privateDnsStrict.asStateFlow()

    val recording: StateFlow<Boolean> = app.settings.state.map { it.recordConnections }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val since = MutableStateFlow(System.currentTimeMillis() - LOG_WINDOW_MS)

    val connections: StateFlow<ConnectionLog?> = since
        .flatMapLatest { app.connections.observeSince(it) }
        .map { summarize(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Camera and microphone use over the last 7 days, newest first. */
    val sensorEvents: StateFlow<List<SensorEvent>> = since
        .flatMapLatest { app.sensorEvents.observeSince(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val cameraInUse: StateFlow<Boolean> = app.sensors.cameraInUse
    val micInUse: StateFlow<Boolean> = app.sensors.micInUse

    private val _locationOn = MutableStateFlow(true)
    /** The phone's main location switch (readable by any app). */
    val locationOn: StateFlow<Boolean> = _locationOn.asStateFlow()

    val sensorAlerts: StateFlow<Boolean> = app.settings.state.map { it.sensorAlerts }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSensorAlerts(enabled: Boolean) = viewModelScope.launch { app.settings.setSensorAlerts(enabled) }
    fun clearSensorHistory() = viewModelScope.launch(Dispatchers.IO) { app.sensorEvents.clear() }

    private var refreshJob: Job? = null
    private var lastRefresh = 0L

    /** Re-reads data usage and re-runs the audit. Cheap enough to call on every resume. */
    fun refresh() {
        since.value = System.currentTimeMillis() - LOG_WINDOW_MS
        _locationOn.value = runCatching {
            getApplication<Application>().getSystemService(LocationManager::class.java).isLocationEnabled
        }.getOrDefault(true)
        _privateDnsStrict.value = runCatching {
            val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp != null && lp.isPrivateDnsActive && lp.privateDnsServerName != null
        }.getOrDefault(false)

        // Re-running the audit on every screen change would be wasteful; a few seconds is fresh enough.
        val now = System.currentTimeMillis()
        if (refreshJob?.isActive == true || now - lastRefresh < MIN_REFRESH_MS) return
        lastRefresh = now
        refreshJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val hasAccess = UsageStatsPoller.hasUsageAccess(ctx)
            val now = System.currentTimeMillis()
            val week = if (hasAccess) {
                withContext(Dispatchers.IO) { DataUsage.query(ctx, now - UsagePeriod.WEEK.millis, now) }
            } else {
                emptyList()
            }
            _weekUsage.value = week.associateBy { it.uid }
            _topBackground.value = week
                .firstOrNull { it.background > 0 && it.packages.isNotEmpty() && DataUsage.specialName(it.uid) == null }
                ?.let { row -> withContext(Dispatchers.Default) { UsageItem(row, row.packages.first(), labelOf(row.packages.first())) } }
            loadUsage(hasAccess, week)
            _audit.value = withContext(Dispatchers.Default) {
                SecurityAudit.run(ctx, week.associate { it.uid to it.background })
            }.also { audits -> audits.forEach { labelCache[it.packageName] = it.label } }
        }
    }

    fun setPeriod(period: UsagePeriod) {
        if (_period.value == period) return
        _period.value = period
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val hasAccess = UsageStatsPoller.hasUsageAccess(ctx)
            loadUsage(hasAccess, if (period == UsagePeriod.WEEK) _weekUsage.value.values.toList() else null)
        }
    }

    private suspend fun loadUsage(hasAccess: Boolean, weekRows: List<UsageRow>?) {
        if (!hasAccess) {
            _usage.value = UsageState.NeedsPermission
            return
        }
        val ctx = getApplication<Application>()
        val period = _period.value
        val rows = if (period == UsagePeriod.WEEK && weekRows != null) {
            weekRows
        } else {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { DataUsage.query(ctx, now - period.millis, now) }
        }
        val items = withContext(Dispatchers.Default) {
            rows.map { row ->
                val pkg = row.packages.firstOrNull()
                val label = DataUsage.specialName(row.uid)
                    ?: pkg?.let { labelOf(it) + if (row.packages.size > 1) " +${row.packages.size - 1}" else "" }
                    ?: "UID ${row.uid}"
                UsageItem(row, if (DataUsage.specialName(row.uid) == null) pkg else null, label)
            }
        }
        _usage.value = UsageState.Ready(items)
    }

    fun connectionsFor(packageName: String): Flow<List<ConnectionAttempt>> =
        app.connections.observeForApp(packageName)

    fun auditFor(packageName: String): AppAudit? = _audit.value?.firstOrNull { it.packageName == packageName }

    fun setRecording(enabled: Boolean) = viewModelScope.launch { app.settings.setRecordConnections(enabled) }

    fun clearLog() = viewModelScope.launch(Dispatchers.IO) { app.connections.clear() }

    fun labelOf(pkg: String): String =
        labelCache.getOrPut(pkg) { InstalledApps.label(getApplication(), pkg) }

    private fun summarize(rows: List<ConnectionAttempt>): ConnectionLog {
        val now = System.currentTimeMillis()
        val dayAgo = now - 24L * 60 * 60 * 1000
        val last24 = rows.filter { it.timeMillis >= dayAgo }
        val blocked24 = last24.filter { !it.allowed }
        return ConnectionLog(
            apps = group(rows),
            timeline = rows.take(TIMELINE_LIMIT),
            totalLast24h = blocked24.sumOf { it.count },
            trackersLast24h = blocked24.filter { TrackerDomains.categoryOf(it.domain) != null }.sumOf { it.count },
            allowedLast24h = last24.filter { it.allowed }.sumOf { it.count },
            nightWatch = nightWatch(rows.filter { !it.allowed }, now),
        )
    }

    private fun group(rows: List<ConnectionAttempt>): List<AppConnections> =
        rows.groupBy { it.packageName }.map { (pkg, attempts) ->
            val destinations = attempts
                .groupBy { it.domain ?: "${it.address}:${it.port}" }
                .map { (name, same) ->
                    val first = same.first()
                    Destination(
                        name = name,
                        protocol = first.protocol,
                        count = same.sumOf { it.count },
                        lastMillis = same.maxOf { it.timeMillis },
                        tracker = TrackerDomains.categoryOf(first.domain),
                        encryptedDns = first.domain == null && first.port == 853,
                        allowed = same.filter { it.allowed }.sumOf { it.count },
                        bytes = same.sumOf { it.bytes },
                    )
                }
                .sortedByDescending { it.count }
            AppConnections(
                packageName = pkg,
                label = pkg?.let { labelOf(it) } ?: "Unknown app",
                total = attempts.sumOf { it.count },
                trackerAttempts = destinations.filter { it.tracker != null }.sumOf { it.count },
                lastMillis = attempts.maxOf { it.timeMillis },
                destinations = destinations,
            )
        }.sortedByDescending { it.total }

    /** What blocked apps tried between midnight and 7 am, i.e. while you were (probably) asleep. */
    private fun nightWatch(rows: List<ConnectionAttempt>, now: Long): NightWatch? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var from = cal.timeInMillis
        if (now < from + NIGHT_MS) from -= 24L * 60 * 60 * 1000 // It's still night: show the previous one.
        val to = from + NIGHT_MS
        val night = rows.filter { it.timeMillis in from until to }
        if (night.isEmpty()) return null
        return NightWatch(from, to, group(night))
    }

    private companion object {
        const val LOG_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val NIGHT_MS = 7L * 60 * 60 * 1000
        const val TIMELINE_LIMIT = 300
        const val MIN_REFRESH_MS = 10_000L
    }
}
