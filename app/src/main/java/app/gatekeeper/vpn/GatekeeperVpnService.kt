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
import app.gatekeeper.data.ConnectionAttempt
import app.gatekeeper.insights.OwnerLookup
import app.gatekeeper.insights.TrafficObserver
import app.gatekeeper.data.Mode
import app.gatekeeper.data.Route
import app.gatekeeper.monitor.ForwardingEngine
import app.gatekeeper.monitor.RelayPolicy
import app.gatekeeper.monitor.TunnelAddresses
import java.net.InetSocketAddress
import app.gatekeeper.monitor.LiveMonitor
import app.gatekeeper.tor.TorState
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.combine
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
 * Local "VPN" with two modes.
 *
 * Normal mode: only blocked apps are routed into the tunnel (addAllowedApplication); every
 * other app keeps using the normal network. Whatever enters the tunnel is read and discarded.
 *
 * Live monitor (on request, never persisted): every app except Gatekeeper is routed into the
 * tunnel. Blocked apps' packets are still dropped; everything else is relayed to the internet by
 * [ForwardingEngine], which reports each connection live.
 *
 * Tor mode (experimental, a setting): same tunnel as the live monitor, but the engine relays
 * through Tor, which runs inside Gatekeeper ([app.gatekeeper.tor.TorController]). It fails
 * closed: until Tor is ready nothing is relayed, and nothing is ever relayed directly.
 */
class GatekeeperVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tunnelDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Written from tunnelDispatcher; onDestroy reads them to close synchronously.
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var drainer: TunnelDrainer? = null
    @Volatile private var forwarder: ForwardingEngine? = null
    /** Apps in the running forwarder's tunnel; null means every app (live monitor). */
    private var forwarderMembers: Set<String>? = null
    /** The plan last applied, for the status card and notification. */
    @Volatile private var lastPlan: TunnelPlan? = null
    private val finishedConnections = ConcurrentLinkedQueue<ConnectionAttempt>()
    private val underlyingDns = MutableStateFlow<List<InetAddress>>(emptyList())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var rulesJob: Job? = null
    private var pollerJob: Job? = null
    private var observerJob: Job? = null
    private var torJob: Job? = null

    /** Packages in the current tunnel; read by the observer on the drainer thread. */
    @Volatile private var appliedPackages: Set<String> = emptySet()
    @Volatile private var recordConnections = true
    private val owners by lazy { OwnerLookup(this) }
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

        watchUnderlyingDns()

        val torMode = gatekeeper.settings.state.map { it.torMode }.distinctUntilChanged()

        @OptIn(FlowPreview::class)
        rulesJob = combine(
            gatekeeper.engine.blockedPackages.filterNotNull(),
            _monitoring,
            torMode,
            gatekeeper.rules.rules,
            underlyingDns,
        ) { blocked, monitor, torOn, rules, dns ->
            val torApps = if (torOn) rules.values.filter { it.route == Route.TOR }.mapTo(HashSet()) { it.packageName } else emptySet()
            TunnelPlan(
                blocked = blocked - packageName,
                monitor = monitor,
                torOn = torOn,
                torApps = torApps - packageName,
                trackerApps = rules.values.filter { it.blockTrackers }.mapTo(HashSet()) { it.packageName } - packageName,
                whileOpen = rules.values.filter { it.mode == Mode.WHILE_OPEN }.mapTo(HashSet()) { it.packageName } - packageName,
                dns = dns,
            )
        }
            .debounce(REBUILD_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { plan ->
                withContext(tunnelDispatcher) {
                    lastPlan = plan
                    if (plan.monitor || plan.relayed.isNotEmpty()) applyForwarding(plan) else applyBlockedSet(plan.blocked)
                }
            }
            .launchIn(scope)

        torJob = scope.launch {
            // Tor runs only while the firewall runs with Tor mode on and at least one app uses it.
            launch {
                combine(torMode, gatekeeper.rules.rules) { on, rules -> on && rules.values.any { it.route == Route.TOR } }
                    .distinctUntilChanged()
                    .collect { needed -> if (needed) gatekeeper.tor.start() else gatekeeper.tor.stop() }
            }
            gatekeeper.tor.state.collect { state ->
                forwarder?.updateTorRoute((state as? TorState.Ready)?.route)
                refreshNotification()
            }
        }

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
                withContext(Dispatchers.IO) {
                    observer.flush()
                    flushFinishedConnections()
                }
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
            .addAddress(TunnelAddresses.ADDRESS_V4, 32)
            .addAddress(TunnelAddresses.ADDRESS_V6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // DNS servers inside the tunnel, so blocked apps' lookups are dropped as well.
            .addDnsServer(TunnelAddresses.DNS_V4)
            .addDnsServer(TunnelAddresses.DNS_V6)
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
        val oldForwarder = forwarder
        val wasMonitor = forwarderMembers == null
        tunnel = newTunnel
        appliedPackages = applied
        drainer = TunnelDrainer(newTunnel) { buf, len ->
            if (recordConnections) observer.onPacket(buf, len)
        }.also { it.start() }
        forwarder = null
        forwarderMembers = null
        oldDrainer?.shutdown()
        oldForwarder?.let {
            it.shutdown()
            gatekeeper.engine.log(null, if (wasMonitor) "Live monitor off" else "Tor and tracker blocking off")
        }
        oldTunnel?.closeQuietly()

        gatekeeper.engine.log(null, "Firewall updated: ${applied.size} apps blocked")
        publish(applied.size)
    }

    /**
     * Relay through the forwarding engine. Live monitor: every app except Gatekeeper is in the
     * tunnel. Otherwise only apps that need the engine are: blocked and While open apps (so their
     * switching never rebuilds the tunnel), Tor apps and tracker-blocking apps; everyone else
     * stays out of Gatekeeper entirely. Rule changes that keep the same members are applied inside
     * the running engine without a rebuild.
     */
    private suspend fun applyForwarding(plan: TunnelPlan) {
        if (stopping) return
        val members: Set<String>? = if (plan.monitor) null else plan.blocked + plan.whileOpen + plan.relayed
        val policy = RelayPolicy(
            blocked = plan.blocked,
            tor = plan.torApps,
            trackers = plan.trackerApps,
            directDns = plan.dns.map { InetSocketAddress(it, 53) },
        )
        val running = forwarder
        if (running != null && members == forwarderMembers) {
            running.updatePolicy(policy)
            appliedPackages = plan.blocked
            publish(plan.blocked.size)
            return
        }

        val builder = Builder()
            .setSession(if (plan.monitor) "Gatekeeper live monitor" else "Gatekeeper")
            .addAddress(TunnelAddresses.ADDRESS_V4, 32)
            .addAddress(TunnelAddresses.ADDRESS_V6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // DNS servers inside the tunnel: the engine answers every lookup, through Tor for Tor
            // apps (with tracker filtering where asked) and via the network's DNS for the rest.
            .addDnsServer(TunnelAddresses.DNS_V4)
            .addDnsServer(TunnelAddresses.DNS_V6)
            .setMtu(MONITOR_MTU)
            .setBlocking(false)
            .setMetered(false)
        if (members == null) {
            try {
                // Our relay sockets (and Tor itself) must not loop back into the tunnel.
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        } else {
            val uninstalled = ArrayList<String>()
            var added = 0
            for (pkg in members) {
                try {
                    builder.addAllowedApplication(pkg)
                    added++
                } catch (_: PackageManager.NameNotFoundException) {
                    uninstalled += pkg
                }
            }
            if (uninstalled.isNotEmpty()) gatekeeper.rules.removeAll(uninstalled)
            if (added == 0) {
                // Never establish with zero allowed apps: that would capture EVERY app.
                closeTunnel()
                publish(0)
                return
            }
        }

        val newTunnel = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed (relay)", e)
            null
        }
        if (newTunnel == null) {
            withContext(Dispatchers.Main) { handleRevoked("VPN permission was removed. Open Gatekeeper to turn it back on.") }
            return
        }

        val newForwarder = ForwardingEngine(
            protector = object : ForwardingEngine.Protector {
                override fun protect(socket: java.net.Socket) = this@GatekeeperVpnService.protect(socket)
                override fun protect(socket: java.net.DatagramSocket) = this@GatekeeperVpnService.protect(socket)
            },
            toTunnel = java.io.FileOutputStream(newTunnel.fileDescriptor),
            owners = owners,
            initialPolicy = policy,
            onBlockedPacket = { buf, len -> if (recordConnections) observer.onPacket(buf, len) },
            finished = finishedConnections,
            initialTorRoute = currentTorRoute(),
        ).also { it.start() }

        val oldTunnel = tunnel
        val oldDrainer = drainer
        val oldForwarder = forwarder
        val oldMembers = forwarderMembers
        tunnel = newTunnel
        appliedPackages = plan.blocked
        forwarder = newForwarder
        forwarderMembers = members
        drainer = TunnelDrainer(newTunnel) { buf, len -> newForwarder.enqueue(buf, len) }.also { it.start() }
        // Tor may have become ready while the engine was being built.
        newForwarder.updateTorRoute(currentTorRoute())
        oldDrainer?.shutdown()
        oldForwarder?.shutdown()
        oldTunnel?.closeQuietly()

        if (oldForwarder == null || (oldMembers == null) != (members == null)) {
            gatekeeper.engine.log(
                null,
                if (members == null) "Live monitor on"
                else "Relaying ${plan.torApps.size} apps through Tor, blocking trackers in ${plan.trackerApps.size}",
            )
        }
        publish(plan.blocked.size)
    }

    private fun currentTorRoute() = (gatekeeper.tor.state.value as? TorState.Ready)?.route

    /** Folds finished relayed connections (one per DNS query, etc.) into log rows. */
    private suspend fun flushFinishedConnections() {
        val batch = ArrayList<ConnectionAttempt>()
        while (true) batch += finishedConnections.poll() ?: break
        if (batch.isEmpty()) return
        val rows = batch
            .groupBy { listOf(it.packageName, it.protocol, it.address, it.port, it.domain) }
            .map { (_, same) ->
                same.first().copy(
                    timeMillis = same.minOf { it.timeMillis },
                    count = same.size,
                    bytes = same.sumOf { it.bytes },
                )
            }
        try {
            gatekeeper.connections.insertAll(rows)
        } catch (e: Exception) {
            Log.w(TAG, "Could not store relayed connections", e)
        }
    }

    private fun watchUnderlyingDns() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                // Gatekeeper itself is never inside its own VPN, so this is the real network.
                underlyingDns.value = linkProperties.dnsServers.filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun unwatchUnderlyingDns() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
    }

    private fun closeTunnel() {
        appliedPackages = emptySet()
        drainer?.shutdown()
        drainer = null
        forwarder?.shutdown()
        forwarder = null
        forwarderMembers = null
        tunnel?.closeQuietly()
        tunnel = null
    }

    private suspend fun publish(blockedCount: Int) = withContext(Dispatchers.Main) {
        if (stopping) return@withContext
        val relaying = forwarder != null
        val plan = lastPlan
        _status.value = Status(
            running = true,
            tunnelUp = blockedCount > 0 || relaying,
            blockedCount = blockedCount,
            alwaysOn = isAlwaysOn,
            lockdown = isLockdownEnabled,
            monitoring = relaying && forwarderMembers == null,
            relaying = relaying,
            torApps = if (relaying) plan?.torApps?.size ?: 0 else 0,
            trackerApps = if (relaying) plan?.trackerApps?.size ?: 0 else 0,
        )
        refreshNotification()
    }

    private fun refreshNotification() {
        if (stopping || !_status.value.running) return
        val s = _status.value
        val apps = if (s.torApps == 1) "1 app" else "${s.torApps} apps"
        val torText = if (s.torApps == 0) null else when (val t = gatekeeper.tor.state.value) {
            is TorState.Ready -> "Tor · $apps connected"
            is TorState.Starting -> "Tor · connecting ${t.progress}%"
            is TorState.Failed -> "Tor · not connected, $apps offline"
            TorState.Off -> "Tor · starting"
        }
        Notifications.notify(
            this, Notifications.ID_STATUS,
            Notifications.status(this, s.blockedCount, s.monitoring, torText),
        )
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
        torJob?.cancel()
        torJob = null
        gatekeeper.tor.stop()
        _monitoring.value = false
        unwatchUnderlyingDns()
        gatekeeper.appScope.launch(Dispatchers.IO) {
            observer.flush()
            flushFinishedConnections()
        }
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
        torJob?.cancel()
        gatekeeper.tor.stop()
        _monitoring.value = false
        unwatchUnderlyingDns()
        unregisterReceivers()
        // Close synchronously: the process may die right after onDestroy.
        drainer?.shutdown()
        forwarder?.shutdown()
        tunnel?.closeQuietly()
        drainer = null
        forwarder = null
        tunnel = null
        LiveMonitor.clear()
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
        /** Live monitor: every app's traffic is relayed and shown. */
        val monitoring: Boolean = false,
        /** The forwarding engine runs (live monitor, Tor apps or tracker blocking). */
        val relaying: Boolean = false,
        /** Apps currently routed through Tor (Tor mode on). */
        val torApps: Int = 0,
        /** Apps with tracker blocking on. */
        val trackerApps: Int = 0,
    ) {
        /** Tor is in use by at least one app. */
        val torMode: Boolean get() = torApps > 0
    }

    private data class TunnelPlan(
        val blocked: Set<String>,
        val monitor: Boolean,
        val torOn: Boolean,
        /** Apps routed through Tor; empty while Tor mode is off. */
        val torApps: Set<String>,
        val trackerApps: Set<String>,
        val whileOpen: Set<String>,
        val dns: List<InetAddress>,
    ) {
        /** Apps that need the forwarding engine. */
        val relayed: Set<String> get() = torApps + trackerApps
    }

    companion object {
        private const val TAG = "GatekeeperVpn"
        const val ACTION_START = "app.gatekeeper.START"
        const val ACTION_STOP = "app.gatekeeper.STOP"

        private const val REBUILD_DEBOUNCE_MS = 300L
        private const val LOG_FLUSH_MS = 10_000L
        private const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val MONITOR_MTU = 1500

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status.asStateFlow()

        /** Requested live-monitor state. Kept in memory only: every restart begins in normal mode. */
        private val _monitoring = MutableStateFlow(false)
        val monitoringRequested: StateFlow<Boolean> = _monitoring.asStateFlow()

        fun setMonitoring(on: Boolean) {
            _monitoring.value = on
        }

        private fun ParcelFileDescriptor.closeQuietly() {
            try {
                close()
            } catch (_: Exception) {
            }
        }
    }
}
