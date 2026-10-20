package app.gatekeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.data.AppRule
import app.gatekeeper.data.Mode

@Composable
fun AppDetailScreen(viewModel: MainViewModel, packageName: String, onBack: () -> Unit) {
    val rows by viewModel.allRows.collectAsStateWithLifecycle()
    val row = rows?.firstOrNull { it.app.packageName == packageName }

    Scaffold(topBar = { GatekeeperTopBar(row?.app?.label ?: packageName, onBack = onBack) }) { padding ->
        if (row == null) {
            Text(
                if (rows == null) "Loading…" else "This app is no longer installed.",
                Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        val shared = remember(row.app.uid) { viewModel.sharedUidApps(packageName, row.app.uid) }
        var grace by remember(row.graceSeconds) { mutableFloatStateOf(row.graceSeconds.toFloat()) }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(row.app.label, style = MaterialTheme.typography.titleLarge)
                    Text(packageName, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (row.hasInternetNow) "Has internet right now" else "Blocked right now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.hasInternetNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard {
                Text("Internet access", style = MaterialTheme.typography.titleMedium)
                ModeSelector(row.mode, { viewModel.setMode(packageName, it) }, Modifier.fillMaxWidth())
                Text(
                    when (row.mode) {
                        Mode.ALLOWED -> "Normal internet."
                        Mode.BLOCKED -> "No internet, ever."
                        Mode.WHILE_OPEN -> "Internet only while the app is on screen, plus the grace period below. " +
                            "No notifications, messages or calls arrive in the background."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard {
                Text("Grace period: ${grace.toInt()} s", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = grace,
                    onValueChange = { grace = it },
                    onValueChangeFinished = { viewModel.setGrace(packageName, grace.toInt()) },
                    valueRange = 0f..AppRule.MAX_GRACE_SECONDS.toFloat(),
                    steps = AppRule.MAX_GRACE_SECONDS / 5 - 1,
                    enabled = row.mode == Mode.WHILE_OPEN,
                )
                Text(
                    "How long the app keeps internet after you leave it or turn the screen off, " +
                        "so messages you just sent still go out.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (shared.isNotEmpty()) {
                SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("Shares network access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android gives these apps the same network identity, so they are always " +
                            "blocked or allowed together: " + shared.joinToString { it.label },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
