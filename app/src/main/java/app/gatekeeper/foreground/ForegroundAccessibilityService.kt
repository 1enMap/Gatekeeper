package app.gatekeeper.foreground

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import app.gatekeeper.gatekeeper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports which app is on screen. Only TYPE_WINDOW_STATE_CHANGED events are requested and
 * window content is never read (canRetrieveWindowContent="false").
 */
class ForegroundAccessibilityService : AccessibilityService() {

    private val launchableCache = HashMap<String, Boolean>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        gatekeeper.foregroundFilter.refreshKeyboards()
        _running.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (gatekeeper.foregroundFilter.isIgnored(pkg)) return
        if (isTransientOverlay(event, pkg)) return
        gatekeeper.engine.onForegroundChanged(pkg)
    }

    /**
     * Toasts and small floating windows from packages the user can't launch (system overlays,
     * bubbles, assistant popups) must not count as leaving the current app.
     */
    private fun isTransientOverlay(event: AccessibilityEvent, pkg: String): Boolean {
        val className = event.className?.toString().orEmpty()
        if (className.contains("Toast")) return true
        if (event.isFullScreen) return false
        return !isLaunchable(pkg)
    }

    private fun isLaunchable(pkg: String): Boolean = launchableCache.getOrPut(pkg) {
        val pm = packageManager
        pm.getLaunchIntentForPackage(pkg) != null || isHomeApp(pm, pkg)
    }

    private fun isHomeApp(pm: PackageManager, pkg: String): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private val _running = MutableStateFlow(false)

        /** True while the service is connected in this process. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Whether the user has switched the service on in system settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, ForegroundAccessibilityService::class.java)
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            return splitter.any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
