package app.gatekeeper

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import app.gatekeeper.vpn.GatekeeperVpnService
import kotlinx.coroutines.launch

/** Starts and stops the firewall and keeps the master switch setting in sync. */
object FirewallController {

    /** True when the user has already granted VPN consent to Gatekeeper. */
    fun hasVpnConsent(context: Context): Boolean = VpnService.prepare(context) == null

    /**
     * Turns the firewall on. Returns false if VPN consent is missing; the caller must then
     * launch [VpnService.prepare]'s intent from an activity.
     */
    fun enable(context: Context): Boolean {
        if (!hasVpnConsent(context)) return false
        if (!startService(context)) return false
        val app = context.gatekeeper
        app.appScope.launch { app.settings.setFirewallEnabled(true) }
        return true
    }

    fun disable(context: Context) {
        val app = context.gatekeeper
        app.appScope.launch { app.settings.setFirewallEnabled(false) }
        if (!GatekeeperVpnService.status.value.running) return
        // The service is in the foreground, so this start is not subject to background limits.
        runCatching {
            context.startService(
                Intent(context, GatekeeperVpnService::class.java).setAction(GatekeeperVpnService.ACTION_STOP),
            )
        }.onFailure { Log.w(TAG, "Could not stop the VPN service", it) }
    }

    /**
     * Starts the service without touching the setting (boot, app update). Returns false if
     * Android refused to start a foreground service from the current context.
     */
    fun startService(context: Context): Boolean = try {
        ContextCompat.startForegroundService(
            context,
            Intent(context, GatekeeperVpnService::class.java).setAction(GatekeeperVpnService.ACTION_START),
        )
        Notifications.cancelStopped(context)
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException on Android 12+.
        Log.w(TAG, "Foreground service start not allowed", e)
        false
    }

    private const val TAG = "FirewallController"
}
