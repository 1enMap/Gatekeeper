package app.gatekeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { GatekeeperTopBar("About & help", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard {
                Text("How it works", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Gatekeeper starts a local VPN that only blocked apps are routed into. Their packets " +
                        "are read and thrown away; every other app uses the network normally. Apps set to " +
                        "\"While open\" are let out of the tunnel while they are on screen and for a short " +
                        "grace period after you leave them.",
                )
            }
            SectionCard {
                Text("Privacy", style = MaterialTheme.typography.titleMedium)
                Bullet(
                    "Gatekeeper never contacts any server of its own. It uses the Internet permission only " +
                        "while you run the Live monitor, to relay other apps' traffic so you can see it.",
                )
                Bullet("The Live monitor reads server names and byte counts, never message or page content.")
                Bullet("No analytics, no crash reporting, no ads.")
                Bullet("Your rules stay on this device and are excluded from backups.")
                Bullet("The accessibility service only sees the package name of the app on screen, never its content.")
            }
            SectionCard {
                Text("Known limitations", style = MaterialTheme.typography.titleMedium)
                Bullet("Android allows only one VPN at a time, so Gatekeeper can't run alongside a real VPN.")
                Bullet(
                    "Apps in \"While open\" mode get no notifications and no incoming calls in the " +
                        "background. That is the point, but be aware of it.",
                )
                Bullet(
                    "Linked devices, such as WhatsApp Web or Desktop, still receive messages, so senders " +
                        "may see double ticks even though your phone is blocked.",
                )
                Bullet("Apps that share a user ID with other apps are blocked or allowed together.")
                Bullet(
                    "Never enable \"Block connections without VPN\" for Gatekeeper: it would cut off " +
                        "every app that isn't blocked.",
                )
            }
        }
    }
}
