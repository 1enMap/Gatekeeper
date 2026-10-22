package app.gatekeeper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.data.Mode
import app.gatekeeper.insights.TrackerDomains
import app.gatekeeper.monitor.LiveEntry
import app.gatekeeper.monitor.LiveMonitor
import app.gatekeeper.vpn.GatekeeperVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AppSummary(val packageName: String?, val connections: Int, val open: Int, val bytes: Long, val hosts: List<String>)

@Composable
fun LiveScreen(
    viewModel: MainViewModel,
    insights: InsightsViewModel,
    filterPackage: String?,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val context = LocalContext.current
    val status by GatekeeperVpnService.status.collectAsStateWithLifecycle()
    val requested by GatekeeperVpnService.monitoringRequested.collectAsStateWithLifecycle()
    val all by LiveMonitor.entries.collectAsStateWithLifecycle()
    var appFilter by rememberSaveable { mutableStateOf(filterPackage) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    var trackersOnly by rememberSaveable { mutableStateOf(false) }
    var byApp by rememberSaveable { mutableStateOf(filterPackage == null) }
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }

    val entries = all
        .filter { appFilter == null || it.packageName == appFilter }
        .filter { !openOnly || it.open }
        .filter { !trackersOnly || TrackerDomains.categoryOf(it.host) != null }
    val title = appFilter?.let { "Live: ${insights.labelOf(it)}" } ?: "Live monitor"

    Scaffold(topBar = { GatekeeperTopBar(title, onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard(
                        container = if (status.monitoring) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        status.torMode -> "● Watching traffic going through Tor"
                                        status.monitoring -> "● Watching all traffic"
                                        requested -> "Starting…"
                                        else -> "Monitor is off"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (status.monitoring) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (status.torMode) {
                                // Tor mode already relays everything; it is turned off on the home screen.
                            } else if (status.monitoring || requested) {
                                Button(
                                    onClick = onStop,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                ) { Text("Stop") }
                            } else {
                                Button(onClick = onStart) { Text("Start") }
                            }
                        }
                        Text(
                            "While on, every app's traffic passes through Gatekeeper so you can see where it " +
                                "goes, live. Blocked apps stay blocked. Gatekeeper reads only server names " +
                                "and byte counts, never content, and keeps everything on this phone. It uses " +
                                "more battery: stop it when you're done (it also stops on restart).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (status.torMode) {
                            Text(
                                "Tor mode is on, so this view is live while it runs. Addresses are the ones apps " +
                                    "asked for; sites see a Tor exit instead of your IP. Dropped UDP shows as blocked.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = byApp, onClick = { byApp = true }, label = { Text("By app") })
                        FilterChip(selected = !byApp, onClick = { byApp = false }, label = { Text("Connections") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = openOnly, onClick = { openOnly = !openOnly }, label = { Text("Open now") })
                        FilterChip(selected = trackersOnly, onClick = { trackersOnly = !trackersOnly }, label = { Text("Trackers") })
                        appFilter?.let {
                            FilterChip(selected = true, onClick = { appFilter = null }, label = { Text("${insights.labelOf(it)} ✕") })
                        }
                    }
                    Text(
                        "${entries.size} connections · ${entries.count { it.open }} open · " +
                            "${formatBytes(context, entries.sumOf { it.bytesIn + it.bytesOut })}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        if (status.monitoring) "Waiting for connections… Use an app and they'll appear here."
                        else "Start the monitor to see connections live.",
                        Modifier.padding(16.dp),
                    )
                }
            } else if (byApp && appFilter == null) {
                val summaries = entries.groupBy { it.packageName }.map { (pkg, list) ->
                    AppSummary(
                        packageName = pkg,
                        connections = list.size,
                        open = list.count { it.open },
                        bytes = list.sumOf { it.bytesIn + it.bytesOut },
                        hosts = list.mapNotNull { it.host }.distinct(),
                    )
                }.sortedWith(compareByDescending<AppSummary> { it.open }.thenByDescending { it.bytes })
                items(summaries, key = { it.packageName ?: "?" }) { s ->
                    AppSummaryRow(s, insights) { appFilter = s.packageName; byApp = false }
                    HorizontalDivider()
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    LiveRow(
                        entry = entry,
                        insights = insights,
                        expanded = expanded == entry.id,
                        onToggle = { expanded = if (expanded == entry.id) null else entry.id },
                        onBlockApp = { pkg -> viewModel.setMode(pkg, Mode.BLOCKED) },
                        onOpenApp = onOpenApp,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AppSummaryRow(s: AppSummary, insights: InsightsViewModel, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.packageName != null && s.packageName != "android") AppIcon(s.packageName) else Spacer(Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(appName(s.packageName, insights), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${s.connections} connections · ${s.open} open · ${formatBytes(context, s.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.hosts.isNotEmpty()) {
                Text(
                    s.hosts.take(3).joinToString(", ") + if (s.hosts.size > 3) " +${s.hosts.size - 3}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (s.open > 0) Pill("live", Color(0xFF2E7D32))
    }
}

@Composable
private fun LiveRow(
    entry: LiveEntry,
    insights: InsightsViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    onBlockApp: (String) -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val context = LocalContext.current
    val tracker = TrackerDomains.categoryOf(entry.host)
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.packageName != null && entry.packageName != "android") AppIcon(entry.packageName, size = 32.dp)
            else Spacer(Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.host ?: entry.address,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${appName(entry.packageName, insights)} · ${entry.protocol} ${entry.port} · " +
                        "↑${formatBytes(context, entry.bytesOut)} ↓${formatBytes(context, entry.bytesIn)} · ${relativeTime(entry.lastMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            when {
                entry.blocked -> Pill("blocked", Color(0xFFC62828))
                entry.protocol == "QUIC" -> Pill("QUIC→TCP", Color.Gray)
                entry.open -> Pill("open", Color(0xFF2E7D32))
            }
            tracker?.let {
                Spacer(Modifier.width(4.dp))
                Pill("tracker", TrackerColor)
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 42.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Server: ${entry.address} port ${entry.port}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Started ${time.format(Date(entry.startMillis))}, last activity ${time.format(Date(entry.lastMillis))}",
                    style = MaterialTheme.typography.bodySmall,
                )
                tracker?.let { Text("Known as: $it", style = MaterialTheme.typography.bodySmall) }
                if (entry.protocol == "QUIC") {
                    Text(
                        "QUIC hides server names, so the monitor stops it; the app falls back to a normal connection that can be named.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                entry.packageName?.takeIf { it != "android" }?.let { pkg ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!entry.blocked) OutlinedButton(onClick = { onBlockApp(pkg) }) { Text("Block this app") }
                        OutlinedButton(onClick = { onOpenApp(pkg) }) { Text("App details") }
                    }
                }
            }
        }
    }
}

private fun appName(pkg: String?, insights: InsightsViewModel) = when (pkg) {
    null -> "Unknown / system"
    "android" -> "Android system"
    else -> insights.labelOf(pkg)
}
