package app.gatekeeper.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.torproject.jni.TorService

sealed interface TorState {
    data object Off : TorState
    data class Starting(val progress: Int, val summary: String) : TorState
    data class Ready(val route: TorRoute) : TorState
    data class Failed(val message: String) : TorState
}

/**
 * Runs Tor inside Gatekeeper's own process (Guardian Project's tor-android), as a client only.
 * Gatekeeper is excluded from its own VPN, so Tor's connections to the Tor network go out on the
 * real network while every other app's traffic is relayed into it.
 *
 * Main thread only.
 */
class TorController(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<TorState>(TorState.Off)
    val state: StateFlow<TorState> = _state.asStateFlow()

    private var connection: ServiceConnection? = null
    @Volatile private var service: TorService? = null
    private var pollJob: Job? = null
    private var epoch = 0

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (connection == null) return
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "unknown error"
            Log.w(TAG, "Tor error: $text")
            fail("Tor could not start: $text")
        }
    }
    private var receiverRegistered = false

    fun start() {
        if (connection != null) return
        try {
            TorService.getTorrc(app).writeText(TorInfo.torrc())
        } catch (e: Exception) {
            Log.e(TAG, "Could not write torrc", e)
            _state.value = TorState.Failed("Could not write Tor's settings")
            return
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(app, errorReceiver, IntentFilter(TorService.ACTION_ERROR), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        _state.value = TorState.Starting(0, "Starting Tor")
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? TorService.LocalBinder)?.service
                startPolling()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                fail("Tor stopped unexpectedly")
            }
        }
        connection = conn
        val bound = runCatching { app.bindService(Intent(app, TorService::class.java), conn, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) {
            connection = null
            _state.value = TorState.Failed("Android refused to start Tor")
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        service = null
        if (receiverRegistered) {
            runCatching { app.unregisterReceiver(errorReceiver) }
            receiverRegistered = false
        }
        _state.value = TorState.Off
    }

    /** Fresh circuits for every app; open connections are reset by the forwarder. */
    fun newIdentity() {
        val current = _state.value as? TorState.Ready ?: return
        epoch++
        _state.value = TorState.Ready(current.route.copy(epoch = epoch))
        val svc = service ?: return
        scope.launch(Dispatchers.IO) {
            // Also tells Tor to stop reusing old circuits for new streams.
            runCatching { svc.torControlConnection?.signal("NEWNYM") }
        }
    }

    private fun fail(message: String) {
        pollJob?.cancel()
        pollJob = null
        if (connection != null) _state.value = TorState.Failed(message)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val svc = service ?: break
                // getInfo returns null until Tor's control connection is up.
                val phase = withContext(Dispatchers.IO) { svc.getInfo("status/bootstrap-phase") }
                val parsed = TorInfo.bootstrap(phase)
                if (parsed != null) {
                    val (progress, summary) = parsed
                    if (progress >= 100) {
                        if (_state.value !is TorState.Ready) {
                            val socks = withContext(Dispatchers.IO) { TorInfo.firstPort(svc.getInfo("net/listeners/socks")) }
                            val dns = withContext(Dispatchers.IO) { TorInfo.firstPort(svc.getInfo("net/listeners/dns")) }
                            if (socks > 0 && dns > 0) {
                                _state.value = TorState.Ready(TorRoute(socks, dns, epoch))
                            } else {
                                fail("Tor started without its SOCKS or DNS port")
                                break
                            }
                        }
                    } else {
                        _state.value = TorState.Starting(progress, summary)
                    }
                }
                delay(if (_state.value is TorState.Ready) READY_POLL_MS else STARTING_POLL_MS)
            }
        }
    }

    private companion object {
        const val TAG = "TorController"
        const val STARTING_POLL_MS = 700L
        const val READY_POLL_MS = 5_000L
    }
}
