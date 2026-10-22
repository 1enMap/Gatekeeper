package app.gatekeeper.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.ui.theme.GatekeeperTheme
import app.gatekeeper.vpn.GatekeeperVpnService

sealed interface Screen {
    data object Home : Screen
    data object Apps : Screen
    data class Detail(val packageName: String) : Screen
    data object Onboarding : Screen
    data object Settings : Screen
    data object About : Screen
    data object Audit : Screen
    data object Usage : Screen
    data object Connections : Screen
    data class Live(val packageName: String?) : Screen
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val insights: InsightsViewModel by viewModels()

    /** Turn the firewall on after the user accepts the VPN consent dialog. */
    private var enableAfterConsent = false

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshChecks()
        val granted = VpnService.prepare(this) == null
        if (enableAfterConsent && granted) viewModel.setFirewall(true)
        if (!granted) GatekeeperVpnService.setMonitoring(false)
        enableAfterConsent = false
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshChecks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(EXTRA_REQUEST_ENABLE, false) == true) requestEnable()

        setContent {
            GatekeeperTheme {
                Surface {
                    val settings by viewModel.settings.collectAsStateWithLifecycle()
                    val loaded = settings ?: return@Surface
                    val backStack = remember {
                        mutableStateListOf<Screen>(if (loaded.onboardingDone) Screen.Home else Screen.Onboarding)
                    }
                    val navigate: (Screen) -> Unit = { backStack.add(it) }
                    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else finish() }
                    BackHandler(enabled = backStack.size > 1) { back() }

                    // Keeps each screen's saved state (e.g. the app list's scroll position)
                    // while another screen is on top of it.
                    val stateHolder = rememberSaveableStateHolder()
                    val screen = backStack.last()
                    stateHolder.SaveableStateProvider(screen.toString()) {
                        when (screen) {
                            Screen.Home -> HomeScreen(viewModel, insights, navigate, onToggleFirewall = ::toggleFirewall, onToggleTor = ::toggleTor)
                            Screen.Apps -> AppListScreen(
                                viewModel,
                                onBack = back,
                                onOpen = { navigate(Screen.Detail(it)) },
                                onLive = { navigate(Screen.Live(it)) },
                            )
                            is Screen.Detail -> AppDetailScreen(
                                viewModel, insights, screen.packageName,
                                onBack = back,
                                onLive = { navigate(Screen.Live(screen.packageName)) },
                            )
                            Screen.Onboarding -> OnboardingScreen(
                                viewModel,
                                onRequestVpnConsent = ::requestVpnConsent,
                                onRequestNotifications = ::requestNotifications,
                                onFinish = {
                                    viewModel.setOnboardingDone(true)
                                    backStack.clear()
                                    backStack.add(Screen.Home)
                                },
                            )
                            Screen.Settings -> SettingsScreen(viewModel, insights, onBack = back, onRunSetup = { navigate(Screen.Onboarding) })
                            Screen.About -> AboutScreen(onBack = back)
                            Screen.Audit -> AuditScreen(insights, onBack = back, onOpen = { navigate(Screen.Detail(it)) })
                            Screen.Usage -> UsageScreen(insights, onBack = back, onOpen = { navigate(Screen.Detail(it)) })
                            Screen.Connections -> ConnectionsScreen(insights, onBack = back, onOpen = { navigate(Screen.Detail(it)) })
                            is Screen.Live -> LiveScreen(
                                viewModel, insights, screen.packageName,
                                onBack = back,
                                onStart = ::startMonitor,
                                onStop = { GatekeeperVpnService.setMonitoring(false) },
                                onOpenApp = { navigate(Screen.Detail(it)) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_ENABLE, false)) requestEnable()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshChecks()
        viewModel.refreshApps()
        insights.refresh()
    }

    private fun toggleFirewall(on: Boolean) {
        if (!on) viewModel.setFirewall(false) else requestEnable()
    }

    private fun toggleTor(on: Boolean) {
        viewModel.setTorMode(on)
        // Tor mode lives inside the firewall's VPN, so turning it on starts the firewall too.
        if (on && !GatekeeperVpnService.status.value.running) requestEnable()
    }

    private fun startMonitor() {
        GatekeeperVpnService.setMonitoring(true)
        if (!GatekeeperVpnService.status.value.running) requestEnable()
    }

    private fun requestEnable() {
        if (!viewModel.setFirewall(true)) {
            enableAfterConsent = true
            requestVpnConsent()
        }
    }

    private fun requestVpnConsent() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            // Consent was already granted.
            viewModel.refreshChecks()
            if (enableAfterConsent) viewModel.setFirewall(true)
            enableAfterConsent = false
        } else {
            vpnConsent.launch(intent)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_REQUEST_ENABLE = "request_enable"
    }
}
