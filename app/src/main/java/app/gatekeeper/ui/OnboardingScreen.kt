package app.gatekeeper.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val STEP_COUNT = 4

@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    onRequestVpnConsent: () -> Unit,
    onRequestNotifications: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(topBar = { GatekeeperTopBar("Setup · step ${step + 1} of $STEP_COUNT") }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(progress = { (step + 1f) / STEP_COUNT }, modifier = Modifier.fillMaxWidth())

            when (step) {
                0 -> SectionCard {
                    Text("1. Allow the local VPN", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Gatekeeper uses Android's VPN feature to drop the traffic of apps you block. " +
                            "Nothing is sent anywhere. Gatekeeper only relays other apps' traffic for features " +
                            "you turn on (Live monitor, Tor, tracker blocking) and never contacts any server of its own.",
                    )
                    StatusLine(checks.vpnConsent, if (checks.vpnConsent) "VPN permission granted" else "VPN permission not granted yet")
                    Button(onClick = onRequestVpnConsent, enabled = !checks.vpnConsent) { Text("Allow VPN") }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        StatusLine(
                            checks.notificationsAllowed,
                            if (checks.notificationsAllowed) "Notifications allowed" else "Notifications are off",
                            warn = !checks.notificationsAllowed,
                        )
                        Text(
                            "Notifications show the firewall status, warn you if it stops, and let you " +
                                "choose a mode for newly installed apps.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = onRequestNotifications, enabled = !checks.notificationsAllowed) {
                            Text("Allow notifications")
                        }
                    }
                }

                1 -> SectionCard {
                    Text("2. Detect the open app", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "\"Only while open\" needs to know which app is on screen. The accessibility " +
                            "service only receives the package name of the app in front. It never reads " +
                            "what's on the screen.",
                    )
                    StatusLine(checks.accessibilityEnabled, if (checks.accessibilityEnabled) "Accessibility service on" else "Accessibility service off")
                    Button(onClick = { SystemIntents.openAccessibilitySettings(context) }, enabled = !checks.accessibilityEnabled) {
                        Text("Open accessibility settings")
                    }
                    Text(
                        "Find \"Gatekeeper\" under Installed apps / Downloaded apps and switch it on. " +
                            "If Android says the setting is restricted, open Gatekeeper's App info, tap ⋮ " +
                            "and choose \"Allow restricted settings\", then try again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { SystemIntents.openAppDetails(context) }) { Text("Open App info") }

                    Text("Prefer not to?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use Usage Access instead. It checks the open app every 1.5 seconds, so it reacts " +
                            "more slowly: an app may stay blocked for a moment after you open it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    StatusLine(
                        checks.usageAccess && settings?.usageStatsFallback == true,
                        when {
                            settings?.usageStatsFallback != true -> "Usage Access fallback off"
                            checks.usageAccess -> "Usage Access fallback on"
                            else -> "Usage Access fallback chosen, permission missing"
                        },
                        warn = !checks.accessibilityEnabled,
                    )
                    OutlinedButton(onClick = {
                        viewModel.setUsageFallback(true)
                        SystemIntents.openUsageAccessSettings(context)
                    }) { Text("Use Usage Access instead") }
                }

                2 -> SectionCard {
                    Text("3. Ignore battery optimisation", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Otherwise Android may stop Gatekeeper in the background, and blocked apps " +
                            "would get internet again until you reopen it.",
                    )
                    StatusLine(checks.batteryUnrestricted, if (checks.batteryUnrestricted) "Battery optimisation ignored" else "Battery optimisation still on")
                    Button(onClick = { SystemIntents.requestIgnoreBatteryOptimizations(context) }, enabled = !checks.batteryUnrestricted) {
                        Text("Allow background running")
                    }
                }

                else -> SectionCard {
                    Text("4. Vivo / iQOO extra steps", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Vivo phones stop background apps aggressively. Do all of these, even on other " +
                            "brands the last one is recommended:",
                    )
                    Bullet("Allow autostart: iManager → App manager → Autostart → Gatekeeper on.")
                    OutlinedButton(onClick = { SystemIntents.openVivoAutostart(context) }) { Text("Open autostart settings") }
                    Bullet("Background power: Settings → Battery → Background power consumption management → Gatekeeper → Allow high background power use.")
                    OutlinedButton(onClick = { SystemIntents.openVivoBackgroundPower(context) }) { Text("Open battery settings") }
                    Bullet("Lock Gatekeeper in Recents: open Recents, pull down on Gatekeeper's card (or tap its lock icon) so it shows a lock.")
                    Bullet("Always-on VPN: Settings → Network (Other networks & connections) → VPN → ⚙ next to Gatekeeper → Always-on VPN on.")
                    Text(
                        "Do NOT turn on \"Block connections without VPN\". Gatekeeper only tunnels blocked " +
                            "apps, so that option would cut off every other app.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { SystemIntents.openVpnSettings(context) }) { Text("Open VPN settings") }
                }
            }

            Row(Modifier.fillMaxWidth()) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (step < STEP_COUNT - 1) {
                    TextButton(onClick = { step++ }) { Text("Skip") }
                    Button(onClick = { step++ }) { Text("Next") }
                } else {
                    Button(onClick = onFinish) { Text("Done") }
                }
            }
        }
    }
}
