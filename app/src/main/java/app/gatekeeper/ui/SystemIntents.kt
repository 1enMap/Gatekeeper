package app.gatekeeper.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/** Shortcuts into system settings screens used by onboarding. */
object SystemIntents {

    fun openAccessibilitySettings(context: Context) =
        launchFirst(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openUsageAccessSettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    @SuppressLint("BatteryLife") // A firewall must survive in the background; this is its core job.
    fun requestIgnoreBatteryOptimizations(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun openVpnSettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_VPN_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    fun openAppDetails(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
    )

    /** Android 12+: Settings → Privacy, where the camera and microphone access switches are. */
    fun openPrivacySettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_PRIVACY_SETTINGS),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    /** Android's own Privacy dashboard (which app used camera, mic or location, and when). */
    fun openPrivacyDashboard(context: Context) = launchFirst(
        context,
        Intent("android.intent.action.REVIEW_PERMISSION_USAGE"),
        Intent(Settings.ACTION_PRIVACY_SETTINGS),
    )

    fun openLocationSettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    fun openAppDetailsFor(context: Context, packageName: String) = launchFirst(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
    )

    fun openNotificationSettings(context: Context) = launchFirst(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
    )

    /**
     * Vivo / iQOO (Funtouch OS, OriginOS) hide autostart and background power settings in
     * iManager. Component names vary by OS version, so try the known ones in order and fall
     * back to the app's own settings page.
     */
    fun openVivoAutostart(context: Context) = launchFirst(
        context,
        component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
    )

    fun openVivoBackgroundPower(context: Context) = launchFirst(
        context,
        component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
        component("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
    )

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    /** Starts the first intent that resolves. Returns false if none did. */
    private fun launchFirst(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
