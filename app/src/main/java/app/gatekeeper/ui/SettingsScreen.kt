package app.gatekeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.R
import app.gatekeeper.data.AppRule

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    insights: InsightsViewModel,
    onBack: (() -> Unit)?,
    onRunSetup: () -> Unit,
    onAbout: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = settings ?: return

    Scaffold(topBar = { GatekeeperTopBar("Settings", onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard {
                Text("Newly installed apps", style = MaterialTheme.typography.titleMedium)
                ModeSelector(s.newAppMode, { viewModel.setNewAppMode(it) }, Modifier.fillMaxWidth())
                Text(
                    "Applied automatically when an app is installed. A notification lets you change it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard {
                var grace by remember(s.defaultGraceSeconds) { mutableFloatStateOf(s.defaultGraceSeconds.toFloat()) }
                Text("Default grace period: ${grace.toInt()} s", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = grace,
                    onValueChange = { grace = it },
                    onValueChangeFinished = { viewModel.setDefaultGrace(grace.toInt()) },
                    valueRange = 0f..AppRule.MAX_GRACE_SECONDS.toFloat(),
                    steps = AppRule.MAX_GRACE_SECONDS / 5 - 1,
                    // 5-second steps, but without a row of tick dots.
                    colors = SliderDefaults.colors(
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                )
                Text("Used for apps you set to \"While open\" from now on.", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Usage Access fallback", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Detect the open app by polling usage stats when the accessibility service " +
                                "is off. Slower to react.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = s.usageStatsFallback, onCheckedChange = { viewModel.setUsageFallback(it) })
                }
                if (s.usageStatsFallback && !checks.usageAccess) {
                    StatusLine(ok = false, text = "Usage Access permission not granted")
                    OutlinedButton(onClick = { SystemIntents.openUsageAccessSettings(context) }) { Text("Grant Usage Access") }
                }
            }

            TrackerBlockingCard(viewModel)

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Record blocked connection attempts", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Keeps a list of the servers blocked apps try to reach, on this phone only, " +
                                "for 7 days.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = s.recordConnections, onCheckedChange = { insights.setRecording(it) })
                }
                OutlinedButton(onClick = { insights.clearLog() }) { Text("Clear connection log") }
            }

            SectionCard {
                NavRow(R.drawable.ic_check, "Setup guide", "VPN, open-app detection, battery and Vivo steps") { onRunSetup() }
                NavRow(R.drawable.ic_info, "About & help", "How Gatekeeper works, privacy and limits") { onAbout() }
            }
        }
    }
}

@Composable
private fun TrackerBlockingCard(viewModel: MainViewModel) {
    val rows by viewModel.allRows.collectAsStateWithLifecycle()
    val mine = rows.orEmpty().filter { !it.app.isSystem }
    val blocking = mine.count { it.blockTrackers }

    SectionCard {
        Text("Tracker blocking", style = MaterialTheme.typography.titleMedium)
        Text(
            "Stops apps reaching known ad, analytics and crash-reporting servers; the apps keep working. " +
                "Uses a little more battery. If an app misbehaves, turn it off for that app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("On in $blocking of ${mine.size} apps", style = MaterialTheme.typography.titleSmall)
        Button(
            onClick = { viewModel.setBlockTrackersForMyApps(true) },
            enabled = blocking < mine.size,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Block trackers in all my apps") }
        if (blocking > 0) {
            TextButton(onClick = { viewModel.setBlockTrackersForMyApps(false) }, modifier = Modifier.fillMaxWidth()) {
                Text("Turn off everywhere")
            }
        }
    }
}
