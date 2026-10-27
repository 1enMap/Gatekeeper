package app.gatekeeper.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.gatekeeper.data.Mode
import app.gatekeeper.data.Route
import app.gatekeeper.ui.theme.GatekeeperTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens to PNGs (build/screenshots) for design review. Images are only
 * written with -Pscreenshots; otherwise these just check the screens compose without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        if (dark) org.robolectric.RuntimeEnvironment.setQualifiers("+night") else org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
        compose.setContent { GatekeeperTheme { Surface(color = MaterialTheme.colorScheme.background) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** Fake installed apps so lists and details have something to show. */
    private fun installSampleApps() {
        val pm = org.robolectric.Shadows.shadowOf(app.packageManager)
        listOf("com.whatsapp" to "WhatsApp", "com.phonepe.app" to "PhonePe", "com.adobe.scan.android" to "Adobe Scan")
            .forEach { (pkg, label) ->
                val info = android.content.pm.PackageInfo().apply {
                    packageName = pkg
                    applicationInfo = android.content.pm.ApplicationInfo().apply {
                        packageName = pkg
                        nonLocalizedLabel = label
                        uid = 10000 + pkg.length
                    }
                }
                pm.installPackage(info)
            }
    }

    /** Like [shoot], but with sample apps and their rules loaded first. */
    private fun shootWithApps(name: String, dark: Boolean, content: @androidx.compose.runtime.Composable (MainViewModel) -> Unit) {
        installSampleApps()
        val vm = MainViewModel(app)
        vm.refreshApps()
        vm.setRoute("com.whatsapp", Route.TOR)
        vm.setBlockTrackers("com.adobe.scan.android", true)
        vm.setMode("com.phonepe.app", Mode.WHILE_OPEN)
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && vm.allRows.value?.size != 3) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(50)
        }
        Thread.sleep(300)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        shoot(name, dark) { content(vm) }
    }

    // View models are built outside the composables, as an activity would.
    private val main by lazy { MainViewModel(app) }
    private val insights by lazy { InsightsViewModel(app) }

    @Test fun homeDark() {
        val (m, i) = main to insights
        shoot("home_dark", dark = true) { HomeScreen(m, i, {}, {}, {}, {}) }
    }

    @Test fun homeLight() {
        val (m, i) = main to insights
        shoot("home_light", dark = false) { HomeScreen(m, i, {}, {}, {}, {}) }
    }

    @Test fun privacyDark() {
        val i = insights
        shoot("privacy_dark", dark = true) { PrivacyScreen(i) {} }
    }

    @Test fun activityDark() {
        val (m, i) = main to insights
        shoot("activity_dark", dark = true) { ActivityScreen(m, i) {} }
    }

    @Test fun settingsDark() {
        val (m, i) = main to insights
        shoot("settings_dark", dark = true) { SettingsScreen(m, i, onBack = null, onRunSetup = {}) }
    }

    @Test fun appsDark() = shootWithApps("apps_dark", dark = true) { vm ->
        AppListScreen(vm, onBack = null, onOpen = {}, onLive = {})
    }

    @Test fun appDetailDark() {
        val i = insights
        shootWithApps("app_detail_dark", dark = true) { vm -> AppDetailScreen(vm, i, "com.phonepe.app", onBack = {}, onLive = {}) }
    }

    @Test fun privacyLight() {
        val i = insights
        shoot("privacy_light", dark = false) { PrivacyScreen(i) {} }
    }
}
