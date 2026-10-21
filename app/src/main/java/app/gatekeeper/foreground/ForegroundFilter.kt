package app.gatekeeper.foreground

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager

/**
 * Decides which packages never count as "the app on screen": system UI and keyboards.
 * Without this, opening the keyboard inside WhatsApp would look like leaving WhatsApp.
 *
 * Gatekeeper itself is deliberately NOT ignored: switching from a "While open" app to
 * Gatekeeper must count as leaving that app. Gatekeeper never blocks itself, so treating it
 * as a normal foreground app is harmless.
 */
class ForegroundFilter(private val context: Context) {
    private val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
    private var keyboards: Set<String> = emptySet()
    private var keyboardsLoadedAt = 0L

    fun isIgnored(pkg: String): Boolean {
        if (pkg in ALWAYS_IGNORED) return true
        return pkg in currentKeyboards()
    }

    fun refreshKeyboards() {
        keyboards = runCatching {
            inputMethodManager.enabledInputMethodList.mapTo(HashSet()) { it.packageName }
        }.getOrDefault(keyboards)
        keyboardsLoadedAt = SystemClock.elapsedRealtime()
    }

    private fun currentKeyboards(): Set<String> {
        if (SystemClock.elapsedRealtime() - keyboardsLoadedAt > KEYBOARD_CACHE_MS) refreshKeyboards()
        return keyboards
    }

    private companion object {
        const val KEYBOARD_CACHE_MS = 60_000L
        val ALWAYS_IGNORED = setOf(
            "com.android.systemui",
            // Framework dialogs (app chooser, "app not responding", etc.).
            "android",
            // Runtime permission prompts shown on top of the app that asked.
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            // Vivo/iQOO system overlays (floating windows, game sidebars).
            "com.vivo.upslide",
            "com.vivo.smartmultiwindow",
        )
    }
}
