package app.gatekeeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.R
import app.gatekeeper.ui.theme.Gk
import app.gatekeeper.vpn.GatekeeperVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything Gatekeeper has seen: live connections, the log, data use and recent events. */
@Composable
fun ActivityScreen(viewModel: MainViewModel, insights: InsightsViewModel, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val status by GatekeeperVpnService.status.collectAsStateWithLifecycle()
    val log by insights.connections.collectAsStateWithLifecycle()
    val top by insights.topBackground.collectAsStateWithLifecycle()
    val usage by insights.usage.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val rows by viewModel.allRows.collectAsStateWithLifecycle()
    val labels = rows?.associate { it.app.packageName to it.app.label }.orEmpty()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Scaffold(topBar = { GatekeeperTopBar("Activity") }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    NavRow(
                        R.drawable.ic_activity,
                        "Live monitor",
                        when {
                            status.monitoring -> "On: watching every app"
                            status.relaying -> "Showing Tor and tracker-blocking apps"
                            else -> "See where apps connect, as it happens"
                        },
                        tint = if (status.monitoring) Gk.status.good else MaterialTheme.colorScheme.primary,
                    ) { navigate(Screen.Live(null)) }
                    NavRow(
                        R.drawable.ic_block,
                        "Connection log",
                        log?.let {
                            val trackers = if (it.trackersLast24h > 0) " · ${it.trackersLast24h} to trackers" else ""
                            "${it.totalLast24h} blocked attempts in 24 h$trackers"
                        } ?: "What blocked apps tried to reach",
                        tint = Gk.status.danger,
                    ) { navigate(Screen.Connections) }
                    NavRow(
                        R.drawable.ic_data,
                        "Data usage",
                        when {
                            usage == UsageState.NeedsPermission -> "Needs Usage Access"
                            top != null -> "Most in the background: ${top!!.label} (${formatBytes(context, top!!.row.background)})"
                            else -> "Per app, background vs on screen"
                        },
                        tint = MaterialTheme.colorScheme.secondary,
                    ) { navigate(Screen.Usage) }
                    NavRow(R.drawable.ic_bug, "Security audit", "Apps with risky powers or permissions", tint = Gk.status.warn) {
                        navigate(Screen.Audit)
                    }
                }
            }

            item { SectionLabel("Recent events") }
            if (activity.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Opening and leaving \"While open\" apps, firewall changes and camera or " +
                            "microphone use show up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    SectionCard {
                        activity.forEach { entry ->
                            val who = entry.packageName?.let { labels[it] ?: it }
                            Row {
                                Text(
                                    timeFormat.format(Date(entry.timeMillis)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(52.dp),
                                )
                                Column {
                                    if (who != null) Text(who, style = MaterialTheme.typography.labelLarge)
                                    Text(entry.message, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
