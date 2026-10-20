package app.gatekeeper.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.gatekeeper.FirewallController
import app.gatekeeper.gatekeeper
import kotlinx.coroutines.launch

/** Restarts the firewall after a reboot or app update, but only if the master switch was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.gatekeeper
        val pending = goAsync()
        app.appScope.launch {
            try {
                if (app.settings.current().firewallEnabled && FirewallController.hasVpnConsent(context)) {
                    FirewallController.startService(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
