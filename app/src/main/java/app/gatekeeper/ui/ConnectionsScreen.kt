package app.gatekeeper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.data.ConnectionAttempt
import app.gatekeeper.insights.TrackerDomains
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionsScreen(
    insights: InsightsViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    /** Open with the Trackers filter already on. */
    trackersFirst: Boolean = false,
) {
    val log by insights.connections.collectAsStateWithLifecycle()
    val strictDns by insights.privateDnsStrict.collectAsStateWithLifecycle()
    val recording by insights.recording.collectAsStateWithLifecycle()
    var timeline by rememberSaveable { mutableStateOf(false) }
    var trackersOnly by rememberSaveable { mutableStateOf(trackersFirst) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            GatekeeperTopBar("Connection log", onBack = onBack, actions = {
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            })
        },
    ) { padding ->
        val data = log
        if (data == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard {
                        Text(
                            "${data.totalLast24h} blocked attempts in the last 24 h",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (data.trackersLast24h > 0) {
                            Text("${data.trackersLast24h} of them went to known ad or tracking servers.")
                        }
                        if (data.allowedLast24h > 0) {
                            Text("Plus ${connectionsLabel(data.allowedLast24h)} let through while the live monitor was on.")
                        }
                        Text(
                            "Every connection a blocked app tries (including \"While open\" apps in the " +
                                "background) is dropped inside Gatekeeper's tunnel and listed here, along with " +
                                "what the live monitor saw. Kept on this phone for 7 days.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!recording) {
                            StatusLine(ok = false, warn = true, text = "Recording is off (Settings)")
                        }
                        if (strictDns) {
                            StatusLine(
                                ok = false,
                                warn = true,
                                text = "Private DNS is on, so look-ups are encrypted and most entries show " +
                                    "addresses instead of names. Set Private DNS to Automatic to see names.",
                            )
                        }
                    }
                    data.nightWatch?.let { NightWatchCard(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !timeline, onClick = { timeline = false }, label = { Text("By app") })
                        FilterChip(selected = timeline, onClick = { timeline = true }, label = { Text("Timeline") })
                        FilterChip(selected = trackersOnly, onClick = { trackersOnly = !trackersOnly }, label = { Text("Trackers") })
                    }
                }
            }

            if (data.apps.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Block an app (or set it to While open) and its attempts will appear here.",
                        Modifier.padding(16.dp),
                    )
                }
            } else if (timeline) {
                val rows = if (trackersOnly) data.timeline.filter { TrackerDomains.categoryOf(it.domain) != null } else data.timeline
                items(rows, key = { it.id }) { TimelineRow(it, insights) }
            } else {
                val apps = if (trackersOnly) data.apps.filter { it.trackerAttempts > 0 } else data.apps
                items(apps, key = { it.packageName ?: "?" }) { app ->
                    val key = app.packageName ?: "?"
                    AppConnectionsItem(
                        app = app,
                        expanded = expanded == key,
                        trackersOnly = trackersOnly,
                        onToggle = { expanded = if (expanded == key) null else key },
                        onOpen = { app.packageName?.let(onOpen) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the connection log?") },
            text = { Text("This deletes every recorded attempt. Your rules are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    insights.clearLog()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NightWatchCard(night: NightWatch) {
    val fmt = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text("🌙 Night watch", style = MaterialTheme.typography.titleMedium)
        Text(
            "While you slept (${fmt.format(Date(night.fromMillis))} – ${fmt.format(Date(night.toMillis))}), " +
                "blocked apps tried to connect ${night.apps.sumOf { it.total }} times:",
            style = MaterialTheme.typography.bodyMedium,
        )
        night.apps.take(5).forEach { app ->
            val trackers = if (app.trackerAttempts > 0) " (${app.trackerAttempts} to trackers)" else ""
            Bullet("${app.label}: ${app.total}× to ${app.destinations.size} destinations$trackers")
        }
    }
}

@Composable
private fun AppConnectionsItem(
    app: AppConnections,
    expanded: Boolean,
    trackersOnly: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (app.packageName != null) AppIcon(app.packageName) else Spacer(Modifier.width(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${app.total} attempts · ${app.destinations.size} destinations · last ${relativeTime(app.lastMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (app.trackerAttempts > 0) Pill("${app.trackerAttempts} tracker", TrackerColor)
        }
        if (expanded) {
            val destinations = if (trackersOnly) app.destinations.filter { it.tracker != null } else app.destinations
            destinations.take(50).forEach { d -> DestinationLine(d) }
            if (destinations.size > 50) Text("…and ${destinations.size - 50} more", style = MaterialTheme.typography.bodySmall)
            if (app.packageName != null) TextButton(onClick = onOpen) { Text("App details & rules") }
        }
    }
}

@Composable
private fun DestinationLine(d: Destination) {
    Row(Modifier.padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (d.encryptedDns) "Encrypted DNS look-up (${d.name})" else d.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val outcome = when {
                d.allowed == 0 -> "blocked"
                d.allowed == d.count -> "let through"
                else -> "${d.allowed} let through, ${d.count - d.allowed} blocked"
            }
            val size = if (d.bytes > 0) " · ${formatBytes(LocalContext.current, d.bytes)}" else ""
            Text(
                "${d.protocol} · ${d.count}× $outcome$size · ${relativeTime(d.lastMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        d.tracker?.let { Pill(it, TrackerColor) }
    }
}

@Composable
private fun TimelineRow(a: ConnectionAttempt, insights: InsightsViewModel) {
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()) }
    val tracker = TrackerDomains.categoryOf(a.domain)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${a.packageName?.let { insights.labelOf(it) } ?: "Unknown app"} → ${a.domain ?: "${a.address}:${a.port}"}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${fmt.format(Date(a.timeMillis))} · ${a.protocol} · ${if (a.allowed) "let through" else "blocked"}" +
                    if (a.count > 1) " · ${a.count}×" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        tracker?.let { Pill(it, TrackerColor) }
    }
}
