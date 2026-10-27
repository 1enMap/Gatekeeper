package app.gatekeeper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.R
import app.gatekeeper.data.SensorEvent
import app.gatekeeper.ui.theme.Gk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Permission groups shown in "Who can access", mapped to the audit's names. */
private enum class AccessGroup(val label: String, val icon: Int, val names: Set<String>) {
    CAMERA("Camera", R.drawable.ic_camera, setOf("Camera")),
    MIC("Microphone", R.drawable.ic_mic, setOf("Microphone")),
    LOCATION("Location", R.drawable.ic_location, setOf("Location", "Background location")),
    CONTACTS("Contacts", R.drawable.ic_apps, setOf("Contacts")),
    SMS("SMS", R.drawable.ic_privacy, setOf("SMS")),
    CALLS("Call logs", R.drawable.ic_activity, setOf("Call logs")),
}

@Composable
fun PrivacyScreen(insights: InsightsViewModel, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val events by insights.sensorEvents.collectAsStateWithLifecycle()
    val cameraNow by insights.cameraInUse.collectAsStateWithLifecycle()
    val micNow by insights.micInUse.collectAsStateWithLifecycle()
    val locationOn by insights.locationOn.collectAsStateWithLifecycle()
    val audit by insights.audit.collectAsStateWithLifecycle()
    val alerts by insights.sensorAlerts.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf(AccessGroup.CAMERA) }
    var showAll by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { GatekeeperTopBar("Privacy") }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Right now + quick switches ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SensorSwitchTile(
                        Modifier.weight(1f), R.drawable.ic_camera, "Camera",
                        if (cameraNow) "In use now" else "Not in use",
                        if (cameraNow) Gk.status.warn else Gk.status.good,
                    ) { SystemIntents.openPrivacySettings(context) }
                    SensorSwitchTile(
                        Modifier.weight(1f), R.drawable.ic_mic, "Microphone",
                        if (micNow) "In use now" else "Not in use",
                        if (micNow) Gk.status.warn else Gk.status.good,
                    ) { SystemIntents.openPrivacySettings(context) }
                    SensorSwitchTile(
                        Modifier.weight(1f), R.drawable.ic_location, "Location",
                        if (locationOn) "On" else "Off",
                        if (locationOn) MaterialTheme.colorScheme.secondary else Gk.status.good,
                    ) { SystemIntents.openLocationSettings(context) }
                }
            }
            item {
                Text(
                    "Tap a tile to open its switch. Android lets only the system turn camera, microphone " +
                        "and location off for every app, so Gatekeeper takes you straight there. Quickest of " +
                        "all: add the \"Camera access\" and \"Microphone access\" tiles to Quick Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // ---- Timeline ----
            item { SectionLabel("Camera & microphone, last 7 days") }
            val suspicious = events.count { it.suspicious }
            if (suspicious > 0) {
                item {
                    AlertRow(
                        "$suspicious ${if (suspicious == 1) "time" else "times"} with the screen off or no app open. " +
                            "Check which apps have camera or microphone access below.",
                        Gk.status.danger,
                    )
                }
            }
            if (events.isEmpty()) {
                item {
                    SectionCard {
                        Text("Nothing recorded yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Gatekeeper notes every time the camera or microphone switches on, and which app " +
                                "was on screen. Android doesn't tell apps exactly which app used it, so this is " +
                                "the app you were in at the time.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                item {
                    SectionCard {
                        val shown = if (showAll) events else events.take(8)
                        shown.forEach { SensorEventRow(it, insights) }
                        Row {
                            if (events.size > 8) {
                                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all ${events.size}") }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { insights.clearSensorHistory() }) { Text("Clear") }
                        }
                    }
                }
            }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Alert me", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Notify when the camera or microphone switches on while the screen is off or no app is open.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = alerts, onCheckedChange = { insights.setSensorAlerts(it) })
                    }
                }
            }

            // ---- Who can access ----
            item { SectionLabel("Who can access") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AccessGroup.entries) { g ->
                        FilterChip(selected = g == group, onClick = { group = g }, label = { Text(g.label) })
                    }
                }
            }
            val holders = audit.orEmpty()
                .filter { !it.isSystem && it.sensitive.any { name -> name in group.names } }
                .sortedBy { it.label.lowercase() }
            item {
                SectionCard {
                    if (audit == null) {
                        Text("Checking your apps…", style = MaterialTheme.typography.bodyMedium)
                    } else if (holders.isEmpty()) {
                        Text("None of your apps has ${group.label.lowercase()} access.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            "${holders.size} ${if (holders.size == 1) "app has" else "apps have"} ${group.label.lowercase()} access. Tap one to change it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        holders.forEach { a ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { SystemIntents.openAppDetailsFor(context, a.packageName) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(a.packageName, size = 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (a.hasInternet) "Can go online" else "Can't go online",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if ("Background location" in a.sensitive && group == AccessGroup.LOCATION) {
                                    Pill("Always", Gk.status.warn)
                                    Spacer(Modifier.width(6.dp))
                                }
                                TextButton(onClick = { onOpenApp(a.packageName) }) { Text("Details") }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { SystemIntents.openPrivacyDashboard(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Android's Privacy dashboard")
                }
            }
        }
    }
}

@Composable
private fun SensorSwitchTile(modifier: Modifier, icon: Int, title: String, state: String, tint: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(icon, tint, 40.dp)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1)
        }
    }
}

@Composable
private fun SensorEventRow(e: SensorEvent, insights: InsightsViewModel) {
    val time = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    val camera = e.sensor == SensorEvent.SENSOR_CAMERA
    val who = when {
        e.foregroundPackage != null -> "while ${insights.labelOf(e.foregroundPackage)} was open"
        !e.screenOn -> "with the screen off"
        e.locked -> "on the lock screen"
        else -> "with no app open"
    }
    val duration = e.endMillis?.let { formatDuration(it - e.startMillis) } ?: "still on"
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (e.foregroundPackage != null) AppIcon(e.foregroundPackage, size = 36.dp)
        else IconBadge(if (camera) R.drawable.ic_camera else R.drawable.ic_mic, if (e.suspicious) Gk.status.danger else Gk.status.warn, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${if (camera) "Camera" else "Microphone"} $who",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${time.format(Date(e.startMillis))} · $duration",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (e.suspicious) Pill("check", Gk.status.danger)
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} min ${s % 60}s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}
