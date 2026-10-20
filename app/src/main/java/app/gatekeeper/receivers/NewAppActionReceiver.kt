package app.gatekeeper.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.gatekeeper.Notifications
import app.gatekeeper.data.Mode
import app.gatekeeper.gatekeeper

/** Handles the Allow / Block / While open buttons on the "new app installed" notification. */
class NewAppActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: return
        context.gatekeeper.setMode(pkg, mode)
        NotificationManagerCompat.from(context).cancel(Notifications.newAppNotificationId(pkg))
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_MODE = "mode"
    }
}
