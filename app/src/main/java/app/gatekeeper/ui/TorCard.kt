package app.gatekeeper.ui

import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.R
import app.gatekeeper.data.Mode
import app.gatekeeper.data.Route
import app.gatekeeper.insights.Risk
import app.gatekeeper.tor.TorState
import app.gatekeeper.vpn.GatekeeperVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TorCard(
    viewModel: MainViewModel,
    status: GatekeeperVpnService.Status,
    torModeOn: Boolean,
    torAppCount: Int,
    onToggleTor: (Boolean) -> Unit,
    onChooseApps: () -> Unit,
    modifier: Modifier = Modifier,
    /** Names of the apps routed through Tor. */
    torAppNames: List<String> = emptyList(),
) {
    val context = LocalContext.current
    val tor by viewModel.torState.collectAsStateWithLifecycle()

    SectionCard(
        modifier = modifier,
        container = if (torModeOn && tor is TorState.Ready) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(app.gatekeeper.R.drawable.ic_globe, app.gatekeeper.ui.theme.Gk.status.tor, 40.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tor mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Experimental",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Switch(checked = torModeOn, onCheckedChange = onToggleTor)
        }

        when {
            !torModeOn -> Text(
                "Sends the apps you choose through the Tor network, each app on its own circuit, so the " +
                    "servers they talk to don't see your IP address. Other apps stay direct.",
                style = MaterialTheme.typography.bodySmall,
            )
            torAppCount == 0 -> {
                StatusLine(ok = false, warn = true, text = "No apps chosen yet. Open an app and turn on \"Through Tor\".")
                OutlinedButton(onClick = onChooseApps) { Text("Choose apps") }
            }
            !status.running -> StatusLine(ok = false, warn = true, text = "Starts when the firewall is on")
            else -> when (val t = tor) {
                is TorState.Ready -> {
                    StatusLine(
                        ok = true,
                        text = "Connected. ${if (torAppCount == 1) "1 app goes" else "$torAppCount apps go"} through Tor; " +
                            "all others are direct.",
                    )
                    OutlinedButton(onClick = { viewModel.newTorIdentity() }, modifier = Modifier.fillMaxWidth()) {
                        Text("New identity (fresh circuits)")
                    }
                }
                is TorState.Starting -> {
                    StatusLine(
                        ok = false, warn = true,
                        text = "Connecting to Tor… ${t.progress}%" + if (t.summary.isNotBlank()) " · ${t.summary}" else "",
                    )
                    LinearProgressIndicator(progress = { t.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Tor apps are offline until Tor connects; none of their traffic goes out without Tor.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is TorState.Failed -> {
                    StatusLine(ok = false, text = t.message)
                    Text(
                        "Tor apps stay offline rather than leak. Retry, or turn Tor mode off to send them direct.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { viewModel.retryTor() }) { Text("Retry") }
                }
                TorState.Off -> StatusLine(ok = false, warn = true, text = "Starting Tor…")
            }
        }

        if (torModeOn && torAppCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Through Tor: " + torAppNames.joinToString(", ").ifEmpty { "$torAppCount apps" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onChooseApps) { Text("Change apps") }
            }
        }

        if (torModeOn) {
            Text(
                "Know the limits: Tor hides your IP, not your identity. If you log in, the site knows " +
                    "it's you. It's slower, some sites block Tor, and voice/video calls, games and other " +
                    "UDP apps won't work. Devices on your Wi-Fi (printers, casting) can't be reached.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "While the phone boots, before Gatekeeper starts, Tor apps can go online directly for a " +
                    "few seconds. Payment and banking apps break over Tor: keep them direct.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
