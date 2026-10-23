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
import app.gatekeeper.data.Route
import app.gatekeeper.insights.KnownApps
import androidx.compose.material3.Switch
import app.gatekeeper.insights.Risk
import app.gatekeeper.insights.TrackerDomains
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppDetailScreen(
    viewModel: MainViewModel,
    insights: InsightsViewModel,
    packageName: String,
    onBack: () -> Unit,
    onLive: () -> Unit,
) {
    val rows by viewModel.allRows.collectAsStateWithLifecycle()
    val audits by insights.audit.collectAsStateWithLifecycle()
    val weekUsage by insights.weekUsage.collectAsStateWithLifecycle()
    val attempts by remember(packageName) { insights.connectionsFor(packageName) }.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
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
                        // This is what Gatekeeper permits, not whether the app is currently connecting.
                        if (row.hasInternetNow) "Allowed to use the internet" else "Blocked right now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.hasInternetNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }

            Button(onClick = onLive, modifier = Modifier.fillMaxWidth()) {
                Text("Live activity: see where it connects")
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
                if (row.mode != Mode.BLOCKED) {
                    OutlinedButton(onClick = { viewModel.setMode(packageName, Mode.BLOCKED) }) {
                        Text("Quarantine: block it and watch what it tries")
                    }
                }
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

            PrivacyRoutingCard(viewModel, row)

            audits?.firstOrNull { it.packageName == packageName }?.let { audit ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Security check", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Pill(audit.risk.label, audit.risk.color)
                    }
                    audit.findings.forEach { f ->
                        Row {
                            Text(if (f.good) "✓" else "●", color = if (f.good) Risk.LOW.color else f.severity.color)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(f.title, style = MaterialTheme.typography.bodyMedium)
                                Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (audit.findings.isEmpty()) Text("Nothing notable.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { SystemIntents.openAppDetailsFor(context, packageName) }) {
                        Text("Review permissions in Android settings")
                    }
                }
            }

            weekUsage[row.app.uid]?.let { usage ->
                SectionCard {
                    Text("Data used in the last 7 days", style = MaterialTheme.typography.titleMedium)
                    Text("In the background: ${formatBytes(context, usage.background)}")
                    Text("While on screen: ${formatBytes(context, usage.foreground)}")
                    Text(
                        "Wi-Fi ${formatBytes(context, usage.wifi)} · Mobile ${formatBytes(context, usage.mobile)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (attempts.isNotEmpty()) {
                SectionCard {
                    Text(
                        "Recorded connections (${attempts.sumOf { it.count }})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    attempts.take(15).forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.domain ?: "${a.address}:${a.port}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${a.protocol} · ${if (a.allowed) "let through" else "blocked"} · ${relativeTime(a.timeMillis)}" +
                                        if (a.count > 1) " · ${a.count}×" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TrackerDomains.categoryOf(a.domain)?.let { Pill(it, TrackerColor) }
                        }
                    }
                }
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

@Composable
private fun PrivacyRoutingCard(viewModel: MainViewModel, row: AppRow) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pkg = row.app.packageName
    val torOn = settings?.torMode == true
    val payment = KnownApps.isPaymentApp(pkg, row.app.label)
    val whatsapp = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

    SectionCard {
        Text("Privacy", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Through Tor", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Hides your IP address from the servers this app talks to. If Tor isn't " +
                        "connected, this app stays offline: it never falls back to a direct connection.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = row.route == Route.TOR, onCheckedChange = { viewModel.setRoute(pkg, if (it) Route.TOR else Route.DIRECT) })
        }
        if (row.route == Route.TOR && !torOn) {
            StatusLine(ok = false, warn = true, text = "Tor mode is off on the home screen, so this app goes direct for now")
        }
        if (payment) {
            StatusLine(
                ok = row.route != Route.TOR,
                warn = true,
                text = "Looks like a payment or banking app. These expect an Indian connection and usually " +
                    "fail or lock you out over Tor. Keep it direct.",
            )
        }
        if (whatsapp && row.route == Route.TOR) {
            StatusLine(ok = false, warn = true, text = "Messages work over Tor, but voice and video calls won't (Tor carries no UDP)")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Block trackers", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Stops this app reaching known ad, analytics and crash-reporting servers; the app " +
                        "itself keeps working. If something in the app breaks (e.g. \"Log in with " +
                        "Facebook\"), turn this off.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = row.blockTrackers, onCheckedChange = { viewModel.setBlockTrackers(pkg, it) })
        }
    }
}
