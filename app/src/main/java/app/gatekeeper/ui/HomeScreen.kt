package app.gatekeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.R
import app.gatekeeper.data.Mode
import app.gatekeeper.data.Route
import app.gatekeeper.insights.Risk
import app.gatekeeper.tor.TorState
import app.gatekeeper.ui.theme.Gk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the protection score: what counts, whether it's done, and how to fix it. */
private class ScoreItem(val points: Int, val earned: Int, val label: String, val fix: String?, val onFix: () -> Unit)

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    insights: InsightsViewModel,
    navigate: (Screen) -> Unit,
    onTab: (Tab) -> Unit,
    onToggleFirewall: (Boolean) -> Unit,
    onToggleTor: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val status by viewModel.vpnStatus.collectAsStateWithLifecycle()
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accessibilityRunning by viewModel.accessibilityRunning.collectAsStateWithLifecycle()
    val rows by viewModel.allRows.collectAsStateWithLifecycle()
    val audit by insights.audit.collectAsStateWithLifecycle()
    val log by insights.connections.collectAsStateWithLifecycle()
    val sensorEvents by insights.sensorEvents.collectAsStateWithLifecycle()
    val cameraNow by insights.cameraInUse.collectAsStateWithLifecycle()
    val micNow by insights.micInUse.collectAsStateWithLifecycle()
    val tor by viewModel.torState.collectAsStateWithLifecycle()
    var showScore by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val torCard = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val myApps = rows.orEmpty().filter { !it.app.isSystem }
    val blockedRules = rows?.count { it.mode == Mode.BLOCKED } ?: 0
    val whileOpenRules = rows?.count { it.mode == Mode.WHILE_OPEN } ?: 0
    val trackerApps = myApps.count { it.blockTrackers }
    val torApps = rows?.count { it.route == Route.TOR } ?: 0
    val highRisk = audit?.count { !it.isSystem && it.risk == Risk.HIGH } ?: 0
    val mediumRisk = audit?.count { !it.isSystem && it.risk == Risk.MEDIUM } ?: 0
    val suspiciousSensors = sensorEvents.count { it.suspicious }
    val detectionOn = accessibilityRunning || checks.accessibilityEnabled ||
        (settings?.usageStatsFallback == true && checks.usageAccess)

    val items = listOf(
        ScoreItem(30, if (status.running) 30 else 0, "Firewall on", "Turn on") { onToggleFirewall(true) },
        ScoreItem(20, (20 - 10 * highRisk).coerceAtLeast(0), "No apps that need a look", "Review") { navigate(Screen.Audit) },
        ScoreItem(15, if (suspiciousSensors == 0) 15 else 0, "No camera or mic use with the screen off", "See when") { onTab(Tab.PRIVACY) },
        ScoreItem(10, if (detectionOn) 10 else 0, "Detects the open app (for While open)", "Set up") { navigate(Screen.Onboarding) },
        ScoreItem(10, if (checks.batteryUnrestricted) 10 else 0, "Not restricted by battery saver", "Fix") {
            SystemIntents.requestIgnoreBatteryOptimizations(context)
        },
        ScoreItem(
            10,
            when {
                myApps.isNotEmpty() && trackerApps * 2 >= myApps.size -> 10
                trackerApps > 0 -> 5
                else -> 0
            },
            "Trackers blocked in your apps", "Turn on",
        ) { navigate(Screen.Settings) },
        ScoreItem(5, if (checks.notificationsAllowed) 5 else 0, "Notifications allowed (for alerts)", "Allow") {
            SystemIntents.openNotificationSettings(context)
        },
    )
    val score = items.sumOf { it.earned }

    Scaffold(topBar = { GatekeeperTopBar("Gatekeeper") }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Hero: score + master switch ----
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier
                        .background(Brush.linearGradient(listOf(Gk.status.heroStart, Gk.status.heroEnd)))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ScoreRing(if (status.running) score else 0, label = "protection")
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                when {
                                    !status.running -> "Protection is off"
                                    score >= 85 -> "Well protected"
                                    score >= 60 -> "Protected, with gaps"
                                    else -> "Needs attention"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                if (status.running) {
                                    "${status.blockedCount} blocked now · $blockedRules always · $whileOpenRules while open"
                                } else {
                                    "Apps use the internet freely until you turn the firewall on."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { showScore = !showScore }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Text(if (showScore) "Hide details" else "How is this scored?")
                            }
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(R.drawable.ic_shield, if (status.running) Gk.status.good else MaterialTheme.colorScheme.onSurfaceVariant, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Firewall", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (status.running) "On" else "Off",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (status.running) Gk.status.good else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = status.running, onCheckedChange = onToggleFirewall)
                        }
                    }
                    if (showScore) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items.forEach { item ->
                                val done = item.earned == item.points
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (done) "✓" else "○",
                                        color = if (done) Gk.status.good else Gk.status.warn,
                                        modifier = Modifier.width(22.dp),
                                    )
                                    Text(item.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Text(
                                        "${item.earned}/${item.points}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (!done && item.fix != null) {
                                        TextButton(onClick = item.onFix) { Text(item.fix) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Things that need attention ----
            if (status.lockdown) {
                AlertRow(
                    "\"Block connections without VPN\" is on: it cuts off every app Gatekeeper isn't relaying.",
                    Gk.status.danger, action = "Fix",
                ) { SystemIntents.openVpnSettings(context) }
            }
            if (status.running && !detectionOn) {
                AlertRow("Open-app detection is off, so \"While open\" apps never get internet.", Gk.status.warn, action = "Set up") {
                    navigate(Screen.Onboarding)
                }
            }
            if (suspiciousSensors > 0) {
                AlertRow(
                    "Camera or microphone used with the screen off or no app open ($suspiciousSensors× this week).",
                    Gk.status.danger, icon = R.drawable.ic_privacy, action = "See",
                ) { onTab(Tab.PRIVACY) }
            }

            // ---- Tiles ----
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    R.drawable.ic_block, "Blocked", "${log?.totalLast24h ?: 0}",
                    "connection attempts stopped in 24 h",
                    Gk.status.danger, Modifier.weight(1f),
                ) { navigate(Screen.Connections) }
                val trackerHits = log?.trackersLast24h ?: 0
                StatTile(
                    R.drawable.ic_tracker, "Trackers",
                    if (trackerApps == 0) "Blocking off" else "On in $trackerApps",
                    when {
                        trackerHits > 0 -> "$trackerHits tracker connections stopped in 24 h"
                        trackerApps == 0 -> "tap to see which apps use trackers"
                        else -> "none tried in the last 24 h"
                    },
                    Gk.status.tracker, Modifier.weight(1f),
                ) { navigate(Screen.TrackerLog) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    R.drawable.ic_bug, "Threats",
                    when {
                        audit == null -> "…"
                        highRisk > 0 -> "$highRisk to check"
                        else -> "None"
                    },
                    if (mediumRisk > 0) "$mediumRisk more worth a look" else "apps with risky powers",
                    if (highRisk > 0) Gk.status.danger else Gk.status.good, Modifier.weight(1f),
                ) { navigate(Screen.Audit) }
                StatTile(
                    R.drawable.ic_camera, "Camera & mic",
                    when {
                        cameraNow && micNow -> "Both in use"
                        cameraNow -> "Camera on"
                        micNow -> "Mic on"
                        else -> "Idle"
                    },
                    sensorEvents.firstOrNull()?.let {
                        val t = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it.startMillis))
                        "last used $t"
                    } ?: "nothing recorded this week",
                    if (cameraNow || micNow) Gk.status.warn else MaterialTheme.colorScheme.secondary, Modifier.weight(1f),
                ) { onTab(Tab.PRIVACY) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    R.drawable.ic_globe, "Tor",
                    when {
                        settings?.torMode != true -> "Off"
                        torApps == 0 -> "No apps"
                        tor is TorState.Ready -> "Connected"
                        tor is TorState.Failed -> "Failed"
                        else -> "Connecting"
                    },
                    if (torApps > 0) "$torApps ${if (torApps == 1) "app" else "apps"} through Tor" else "hide your IP from chosen apps' servers",
                    Gk.status.tor, Modifier.weight(1f),
                ) { scope.launch { torCard.bringIntoView() } }
                StatTile(
                    R.drawable.ic_activity, "Live",
                    if (status.monitoring) "Watching" else "Off",
                    "see where apps connect",
                    MaterialTheme.colorScheme.primary, Modifier.weight(1f),
                ) { navigate(Screen.Live(null)) }
            }

            TorCard(
                viewModel, status,
                torModeOn = settings?.torMode == true,
                torAppCount = torApps,
                onToggleTor = onToggleTor,
                onChooseApps = { onTab(Tab.APPS) },
                torAppNames = rows.orEmpty().filter { it.route == Route.TOR }.map { it.app.label }.sorted(),
                modifier = Modifier.bringIntoViewRequester(torCard),
            )
            VerticalGap(8.dp)
        }
    }
}
