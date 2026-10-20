package app.gatekeeper

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.gatekeeper.data.Mode
import app.gatekeeper.receivers.NewAppActionReceiver
import app.gatekeeper.ui.MainActivity

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_NEW_APPS = "new_apps"

    const val ID_STATUS = 1
    const val ID_STOPPED = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, "Firewall status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Persistent notification while the firewall is running"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ALERTS, "Firewall stopped", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown when the firewall is turned off by the system or another VPN"
                },
                NotificationChannel(CHANNEL_NEW_APPS, "New apps", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Choose internet access for newly installed apps"
                },
            ),
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun status(context: Context, blockedCount: Int): Notification {
        val text = when (blockedCount) {
            0 -> "Gatekeeper active · no apps blocked right now"
            1 -> "Gatekeeper active · 1 app blocked"
            else -> "Gatekeeper active · $blockedCount apps blocked"
        }
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(text)
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showStopped(context: Context, reason: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Gatekeeper firewall stopped")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        notify(context, ID_STOPPED, n)
    }

    fun cancelStopped(context: Context) = NotificationManagerCompat.from(context).cancel(ID_STOPPED)

    fun newAppNotificationId(packageName: String) = 1000 + (packageName.hashCode() and 0xFFFFF)

    fun showNewApp(context: Context, packageName: String, label: String, appliedMode: Mode) {
        fun action(title: String, mode: Mode, requestCode: Int) = NotificationCompat.Action(
            0,
            title,
            PendingIntent.getBroadcast(
                context,
                newAppNotificationId(packageName) * 4 + requestCode,
                Intent(context, NewAppActionReceiver::class.java)
                    .putExtra(NewAppActionReceiver.EXTRA_PACKAGE, packageName)
                    .putExtra(NewAppActionReceiver.EXTRA_MODE, mode.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val current = when (appliedMode) {
            Mode.ALLOWED -> "allowed"
            Mode.BLOCKED -> "blocked"
            Mode.WHILE_OPEN -> "allowed only while open"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_NEW_APPS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("New app: $label")
            .setContentText("Internet is $current. Choose:")
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .addAction(action("Allow", Mode.ALLOWED, 0))
            .addAction(action("Block", Mode.BLOCKED, 1))
            .addAction(action("While open", Mode.WHILE_OPEN, 2))
            .build()
        notify(context, newAppNotificationId(packageName), n)
    }

    fun notify(context: Context, id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
