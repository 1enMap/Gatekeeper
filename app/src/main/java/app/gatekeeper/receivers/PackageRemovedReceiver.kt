package app.gatekeeper.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.gatekeeper.Notifications
import app.gatekeeper.gatekeeper

/** Deletes the rule of an app once it is uninstalled. */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        context.gatekeeper.removeRule(pkg)
        NotificationManagerCompat.from(context).cancel(Notifications.newAppNotificationId(pkg))
    }
}
