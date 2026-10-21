package app.gatekeeper.vpn

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.gatekeeper.Notifications
import app.gatekeeper.data.InstalledApps
import app.gatekeeper.foreground.ForegroundAccessibilityService
import app.gatekeeper.foreground.UsageStatsPoller
import app.gatekeeper.gatekeeper
import app.gatekeeper.insights.TrafficObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local "VPN" that captures the traffic of blocked apps only and drops it.
 *
 * Only apps passed to [VpnService.Builder.addAllowedApplication] are routed into the tunnel;
 * every other app keeps using the normal network. The tunnel is never connected to anything,
 * so whatever enters it is read and discarded.
 */
class GatekeeperVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tunnelDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Written from tunnelDispatcher; onDestroy reads them to close synchronously.
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var drainer: TunnelDrainer? = null

    private var rulesJob: Job? = null
    private var pollerJob: Job? = null
    private var observerJob: Job? = null

    /** Packages in the current tunnel; read by the observer on the drainer thread. */
    @Volatile private var appliedPackages: Set<String> = emptySet()
    @Volatile private var recordConnections = true
    private val observer by lazy {
        TrafficObserver(this, gatekeeper.connections) { appliedPackages }
    }
    private var receiversRegistered = false
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            // ACTION_START, the always-on VPN intent (android.net.VpnService), or a sticky restart.
            else -> start(fromAlwaysOn = intent?.action == SERVICE_INTERFACE)
        }
        return START_STICKY
    }

    private fun start(fromAlwaysOn: Boolean) {
        stopping = false
        goForeground(_status.value.blockedCount)
        if (fromAlwaysOn) {
            // The system started us as the always-on VPN: reflect that in the master switch.
            scope.launch { gatekeeper.settings.setFirewallEnabled(true) }
        }
        if (rulesJob != null) return // Already running.

        _status.value = _status.value.copy(running = true)
        registerReceivers()

        @OptIn(FlowPreview::class)
        rulesJob = gatekeeper.engine.blockedPackages
            .filterNotNull()
            .debounce(REBUILD_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { blocked -> withContext(tunnelDispatcher) { applyBlockedSet(blocked) } }
            .launchIn(scope)

        val fallbackEnabled = gatekeeper.settings.state
            .map { it.usageStatsFallback }
            .stateIn(scope, SharingStarted.Eagerly, false)
        pollerJob = UsageStatsPoller(this).start(scope) {
            fallbackEnabled.value && !ForegroundAccessibilityService.running.value
        }

        observerJob = scope.launch {
            launch { gatekeeper.settings.state.collect { recordConnections = it.recordConnections } }
            withContext(Dispatchers.IO) {
                gatekeeper.connections.deleteOlderThan(System.currentTimeMillis() - LOG_RETENTION_MS)
            }
            while (true) {
                delay(LOG_FLUSH_MS)
                withContext(Dispatchers.IO) { observer.flush() }
            }
        }
    }

    /**
     * Rebuilds the tunnel for a new blocked set. The new tunnel is established before the old
     * file descriptor is closed so the switch is seamless.
     */
    private suspend fun applyBlockedSet(requested: Set<String>) {
        if (stopping) return
        val targets = requested - packageName // Never block ourselves.

        if (targets.isEmpty()) {
            // A Builder with zero allowed apps would route EVERY app into the tunnel and kill all
            // internet. With nothing to block we simply keep no tunnel at all.
            if (tunnel != null) gatekeeper.engine.log(null, "Firewall idle: nothing to block")
            closeTunnel()
            publish(0)
            return
        }

        val builder = Builder()
            .setSession("Gatekeeper")
            .addAddress(TUN_ADDRESS_V4, 32)
            .addAddress(TUN_ADDRESS_V6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // DNS servers inside the tunnel, so blocked apps' lookups are dropped as well.
            .addDnsServer(TUN_DNS_V4)
            .addDnsServer(TUN_DNS_V6)
            .setBlocking(false)
            .setMetered(false)

        val applied = HashSet<String>()
        val uninstalled = ArrayList<String>()
        for (pkg in targets) {
            try {
                builder.addAllowedApplication(pkg)
                applied += pkg
            } catch (_: PackageManager.NameNotFoundException) {
                uninstalled += pkg
            }
        }
        if (uninstalled.isNotEmpty()) {
            Log.i(TAG, "Removing rules of uninstalled apps: $uninstalled")
            gatekeeper.rules.removeAll(uninstalled)
        }
        if (applied.isEmpty()) {
            // Same edge case as above: never establish without at least one allowed app.
            closeTunnel()
            publish(0)
            return
        }

        val newTunnel = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }
        if (newTunnel == null) {
            // Consent revoked or another VPN is in control.
            Log.w(TAG, "establish() returned null; VPN permission is gone")
            withContext(Dispatchers.Main) { handleRevoked("VPN permission was removed. Open Gatekeeper to turn it back on.") }
            return
        }

        val oldTunnel = tunnel
        val oldDrainer = drainer
        tunnel = newTunnel
        appliedPackages = applied
        drainer = TunnelDrainer(newTunnel) { buf, len ->
            if (recordConnections) observer.onPacket(buf, len)
        }.also { it.start() }
        oldDrainer?.shutdown()
        oldTunnel?.closeQuietly()

        gatekeeper.engine.log(null, "Firewall updated: ${applied.size} apps blocked")
        publish(applied.size)
    }

    private fun closeTunnel() {
        appliedPackages = emptySet()
        drainer?.shutdown()
        drainer = null
        tunnel?.closeQuietly()
        tunnel = null
    }

    private suspend fun publish(blockedCount: Int) = withContext(Dispatchers.Main) {
        if (stopping) return@withContext
        _status.value = Status(
            running = true,
            tunnelUp = blockedCount > 0,
            blockedCount = blockedCount,
            alwaysOn = isAlwaysOn,
            lockdown = isLockdownEnabled,
        )
        Notifications.notify(this@GatekeeperVpnService, Notifications.ID_STATUS, Notifications.status(this@GatekeeperVpnService, blockedCount))
    }

    private fun goForeground(blockedCount: Int) {
        val notification = Notifications.status(this, blockedCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // Android 14+: VPN apps are explicitly allowed to use systemExempted.
                ServiceCompat.startForeground(
                    this, Notifications.ID_STATUS, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } catch (e: Exception) {
                Log.w(TAG, "systemExempted refused, falling back to specialUse", e)
                ServiceCompat.startForeground(
                    this, Notifications.ID_STATUS, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }
        } else {
            startForeground(Notifications.ID_STATUS, notification)
        }
    }

    /** The user switched the VPN off in settings, or another VPN app took over. */
    override fun onRevoke() {
        handleRevoked("Another VPN started or the VPN was turned off in Settings. Tap to turn the firewall back on.")
    }

    private fun handleRevoked(reason: String) {
        if (stopping) return
        Log.i(TAG, "VPN revoked: $reason")
        gatekeeper.engine.log(null, "Firewall stopped: $reason")
        scope.launch { gatekeeper.settings.setFirewallEnabled(false) }
        Notifications.showStopped(this, reason)
        shutdown()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        rulesJob?.cancel()
        rulesJob = null
        pollerJob?.cancel()
        pollerJob = null
        observerJob?.cancel()
        observerJob = null
        gatekeeper.appScope.launch(Dispatchers.IO) { observer.flush() }
        unregisterReceivers()
        scope.launch(tunnelDispatcher) { closeTunnel() }
        _status.value = Status()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        rulesJob?.cancel()
        pollerJob?.cancel()
        observerJob?.cancel()
        unregisterReceivers()
        // Close synchronously: the process may die right after onDestroy.
        drainer?.shutdown()
        tunnel?.closeQuietly()
        drainer = null
        tunnel = null
        _status.value = Status()
        scope.cancel()
        super.onDestroy()
    }

    // --- Screen and package events -------------------------------------------------------------

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val engine = gatekeeper.engine
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> engine.onScreenOff()
                Intent.ACTION_USER_PRESENT -> engine.onScreenUnlocked()
                Intent.ACTION_SCREEN_ON -> {
                    val keyguard = getSystemService(KeyguardManager::class.java)
                    if (!keyguard.isKeyguardLocked) engine.onScreenUnlocked()
                }
            }
        }
    }

    private val packageAddedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // Just an update.
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg == packageName) return
            val app = gatekeeper
            app.appScope.launch {
                val mode = app.settings.current().newAppMode
                app.setMode(pkg, mode)
                Notifications.showNewApp(context, pkg, InstalledApps.label(context, pkg), mode)
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, packageAddedReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") },
            ContextCompat.RECEIVER_EXPORTED, // Sent by the system package manager.
        )
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(packageAddedReceiver) }
        receiversRegistered = false
    }

    data class Status(
        val running: Boolean = false,
        val tunnelUp: Boolean = false,
        val blockedCount: Int = 0,
        /** Gatekeeper is the always-on VPN (recommended). */
        val alwaysOn: Boolean = false,
        /** "Block connections without VPN" is on, which breaks every app we don't tunnel. */
        val lockdown: Boolean = false,
    )

    companion object {
        private const val TAG = "GatekeeperVpn"
        const val ACTION_START = "app.gatekeeper.START"
        const val ACTION_STOP = "app.gatekeeper.STOP"

        private const val REBUILD_DEBOUNCE_MS = 300L
        private const val LOG_FLUSH_MS = 10_000L
        private const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val TUN_ADDRESS_V4 = "10.111.222.1"
        private const val TUN_ADDRESS_V6 = "fd00:1:2:3::1"
        private const val TUN_DNS_V4 = "10.111.222.2"
        private const val TUN_DNS_V6 = "fd00:1:2:3::2"

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status.asStateFlow()

        private fun ParcelFileDescriptor.closeQuietly() {
            try {
                close()
            } catch (_: Exception) {
            }
        }
    }
}
