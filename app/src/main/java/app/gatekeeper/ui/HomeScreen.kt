package app.gatekeeper.ui

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
import app.gatekeeper.insights.Risk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    insights: InsightsViewModel,
    navigate: (Screen) -> Unit,
    onToggleFirewall: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val status by viewModel.vpnStatus.collectAsStateWithLifecycle()
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accessibilityRunning by viewModel.accessibilityRunning.collectAsStateWithLifecycle()
    val rows by viewModel.allRows.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()

    val blockedRules = rows?.count { it.mode == Mode.BLOCKED } ?: 0
    val whileOpenRules = rows?.count { it.mode == Mode.WHILE_OPEN } ?: 0
    val accessibilityOn = accessibilityRunning || checks.accessibilityEnabled
    val fallbackOn = settings?.usageStatsFallback == true && checks.usageAccess

    Scaffold(
        topBar = {
            GatekeeperTopBar("Gatekeeper", actions = {
                IconAction(R.drawable.ic_settings, "Settings") { navigate(Screen.Settings) }
                IconAction(R.drawable.ic_info, "About") { navigate(Screen.About) }
            })
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(
                container = if (status.running) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Firewall", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (status.running) "On" else "Off",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = status.running, onCheckedChange = onToggleFirewall)
                }
            }

            SectionCard {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                StatusLine(
                    ok = status.running,
                    text = when {
                        !status.running -> "VPN off"
                        status.tunnelUp -> "VPN on"
                        else -> "VPN on (idle, nothing to block right now)"
                    },
                )
                StatusLine(
                    ok = true,
                    text = "${status.blockedCount} apps blocked right now " +
                        "($blockedRules always, $whileOpenRules while open)",
                )
                StatusLine(
                    ok = accessibilityOn,
                    warn = !accessibilityOn && fallbackOn,
                    text = when {
                        accessibilityOn -> "Accessibility on (instant foreground detection)"
                        fallbackOn -> "Accessibility off, using Usage Access (slower)"
                        else -> "Foreground detection off: \"While open\" apps never get internet"
                    },
                )
                if (status.running) {
                    StatusLine(
                        ok = status.alwaysOn,
                        warn = !status.alwaysOn,
                        text = if (status.alwaysOn) "Always-on VPN enabled" else "Always-on VPN not enabled (recommended)",
                    )
                }
                if (status.lockdown) {
                    StatusLine(
                        ok = false,
                        text = "\"Block connections without VPN\" is on. This cuts off every app Gatekeeper " +
                            "is not blocking. Turn it off in VPN settings.",
                    )
                    OutlinedButton(onClick = { SystemIntents.openVpnSettings(context) }) { Text("Open VPN settings") }
                }
                if (!checks.batteryUnrestricted) {
                    StatusLine(ok = false, warn = true, text = "Battery optimisation is on; the system may stop the firewall")
                }
            }

            Button(onClick = { navigate(Screen.Apps) }, modifier = Modifier.fillMaxWidth()) {
                Text("Manage apps")
            }

            OutlinedButton(onClick = { navigate(Screen.Live(null)) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (status.monitoring) "● Live monitor is on: view" else "Live monitor: see where apps connect")
            }

            PrivacyCheckupCard(insights, navigate)
            OutlinedButton(onClick = { navigate(Screen.Onboarding) }, modifier = Modifier.fillMaxWidth()) {
                Text("Setup guide")
            }

            SectionCard {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                if (activity.isEmpty()) {
                    Text(
                        "Nothing yet. Opening and leaving \"While open\" apps will show up here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val labels = rows?.associate { it.app.packageName to it.app.label }.orEmpty()
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                activity.forEach { entry ->
                    val who = entry.packageName?.let { labels[it] ?: it }
                    Row {
                        Text(
                            timeFormat.format(Date(entry.timeMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(
                            if (who != null) "$who · ${entry.message}" else entry.message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyCheckupCard(insights: InsightsViewModel, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val audit by insights.audit.collectAsStateWithLifecycle()
    val top by insights.topBackground.collectAsStateWithLifecycle()
    val usage by insights.usage.collectAsStateWithLifecycle()
    val log by insights.connections.collectAsStateWithLifecycle()

    SectionCard {
        Text("Privacy checkup", style = MaterialTheme.typography.titleMedium)
        val apps = audit?.filter { !it.isSystem }
        if (apps == null) {
            Text("Checking your apps…", style = MaterialTheme.typography.bodyMedium)
        } else {
            val high = apps.count { it.risk == Risk.HIGH }
            val medium = apps.count { it.risk == Risk.MEDIUM }
            StatusLine(
                ok = high == 0,
                text = if (high == 0) "No apps need urgent attention" else "$high ${if (high == 1) "app needs" else "apps need"} a look",
            )
            if (medium > 0) StatusLine(ok = false, warn = true, text = "$medium worth checking")
        }
        when {
            usage == UsageState.NeedsPermission ->
                StatusLine(ok = false, warn = true, text = "Grant Usage Access to see which apps use data in the background")
            top != null -> top?.let {
                StatusLine(
                    ok = true,
                    text = "Most background data this week: ${it.label} (${formatBytes(context, it.row.background)})",
                )
            }
        }
        log?.let {
            val trackers = if (it.trackersLast24h > 0) ", ${it.trackersLast24h} to trackers" else ""
            StatusLine(ok = true, text = "Blocked apps tried ${it.totalLast24h} connections in 24 h$trackers")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { navigate(Screen.Audit) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                Text("Audit", maxLines = 1)
            }
            OutlinedButton(onClick = { navigate(Screen.Usage) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                Text("Data use", maxLines = 1)
            }
            OutlinedButton(onClick = { navigate(Screen.Connections) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                Text("Log", maxLines = 1)
            }
        }
    }
}
