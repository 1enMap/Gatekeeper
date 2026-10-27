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
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.gatekeeper.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.ui.theme.GatekeeperTheme
import app.gatekeeper.vpn.GatekeeperVpnService

/** The bottom-bar destinations. */
enum class Tab(val label: String, @DrawableRes val icon: Int) {
    HOME("Home", R.drawable.ic_home),
    APPS("Apps", R.drawable.ic_apps),
    PRIVACY("Privacy", R.drawable.ic_privacy),
    ACTIVITY("Activity", R.drawable.ic_activity),
    SETTINGS("Settings", R.drawable.ic_settings),
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = t == current,
                onClick = { onSelect(t) },
                icon = { Icon(painterResource(t.icon), contentDescription = t.label) },
                label = { Text(t.label) },
            )
        }
    }
}

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
    /** The connection log, filtered to tracker servers. */
    data object TrackerLog : Screen
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
                Surface(color = MaterialTheme.colorScheme.background) {
                    val settings by viewModel.settings.collectAsStateWithLifecycle()
                    val loaded = settings ?: return@Surface
                    var tab by rememberSaveable { mutableStateOf(initialTab(intent)) }
                    // Screens pushed on top of the current tab (details, logs, setup).
                    val stack = remember {
                        mutableStateListOf<Screen>().apply { if (!loaded.onboardingDone) add(Screen.Onboarding) }
                    }
                    openTab = { tab = it; stack.clear() }
                    val navigate: (Screen) -> Unit = { screen ->
                        when (screen) {
                            Screen.Home -> { stack.clear(); tab = Tab.HOME }
                            Screen.Apps -> { stack.clear(); tab = Tab.APPS }
                            Screen.Settings -> { stack.clear(); tab = Tab.SETTINGS }
                            else -> stack.add(screen)
                        }
                    }
                    val back: () -> Unit = {
                        when {
                            stack.isNotEmpty() -> stack.removeAt(stack.lastIndex)
                            tab != Tab.HOME -> tab = Tab.HOME
                            else -> finish()
                        }
                    }
                    BackHandler(enabled = stack.isNotEmpty() || tab != Tab.HOME) { back() }

                    // Keeps each screen's saved state (e.g. the app list's scroll position)
                    // while another screen is on top of it.
                    val stateHolder = rememberSaveableStateHolder()
                    val top = stack.lastOrNull()
                    Scaffold(
                        bottomBar = { if (top == null) BottomBar(tab) { tab = it } },
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) { outer ->
                        Box(Modifier.padding(outer).consumeWindowInsets(outer)) {
                            stateHolder.SaveableStateProvider(top?.toString() ?: "tab-${tab.name}") {
                                when (top) {
                                    null -> when (tab) {
                                        Tab.HOME -> HomeScreen(
                                            viewModel, insights, navigate,
                                            onTab = { tab = it },
                                            onToggleFirewall = ::toggleFirewall,
                                            onToggleTor = ::toggleTor,
                                        )
                                        Tab.APPS -> AppListScreen(
                                            viewModel,
                                            onBack = null,
                                            onOpen = { navigate(Screen.Detail(it)) },
                                            onLive = { navigate(Screen.Live(it)) },
                                        )
                                        Tab.PRIVACY -> PrivacyScreen(insights, onOpenApp = { navigate(Screen.Detail(it)) })
                                        Tab.ACTIVITY -> ActivityScreen(viewModel, insights, navigate)
                                        Tab.SETTINGS -> SettingsScreen(
                                            viewModel, insights,
                                            onBack = null,
                                            onRunSetup = { navigate(Screen.Onboarding) },
                                            onAbout = { navigate(Screen.About) },
                                        )
                                    }
                                    Screen.Home, Screen.Apps, Screen.Settings -> Unit // Handled as tabs.
                                    is Screen.Detail -> AppDetailScreen(
                                        viewModel, insights, top.packageName,
                                        onBack = back,
                                        onLive = { navigate(Screen.Live(top.packageName)) },
                                    )
                                    Screen.Onboarding -> OnboardingScreen(
                                        viewModel,
                                        onRequestVpnConsent = ::requestVpnConsent,
                                        onRequestNotifications = ::requestNotifications,
                                        onFinish = {
                                            viewModel.setOnboardingDone(true)
                                            stack.clear()
                                            tab = Tab.HOME
                                        },
                                    )
                                    Screen.About -> AboutScreen(onBack = back)
                                    Screen.Audit -> AuditScreen(insights, onBack = back, onOpen = { navigate(Screen.Detail(it)) })
                                    Screen.Usage -> UsageScreen(insights, onBack = back, onOpen = { navigate(Screen.Detail(it)) })
                                    Screen.Connections -> ConnectionsScreen(insights, onBack = back, onOpen = { navigate(Screen.Detail(it)) })
                                    Screen.TrackerLog -> ConnectionsScreen(
                                        insights, onBack = back, onOpen = { navigate(Screen.Detail(it)) }, trackersFirst = true,
                                    )
                                    is Screen.Live -> LiveScreen(
                                        viewModel, insights, top.packageName,
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
        }
    }

    /** Set while the UI is composed: switches tab (used for notification taps). */
    private var openTab: (Tab) -> Unit = {}

    private fun initialTab(intent: Intent?): Tab =
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == "privacy") Tab.PRIVACY else Tab.HOME

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_ENABLE, false)) requestEnable()
        if (intent.getStringExtra(EXTRA_OPEN_TAB) == "privacy") openTab(Tab.PRIVACY)
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
        const val EXTRA_OPEN_TAB = "open_tab"
    }
}
