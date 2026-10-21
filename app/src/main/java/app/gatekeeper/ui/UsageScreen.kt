package app.gatekeeper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun UsageScreen(insights: InsightsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val usage by insights.usage.collectAsStateWithLifecycle()
    val period by insights.period.collectAsStateWithLifecycle()
    var sortBackground by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current

    Scaffold(topBar = { GatekeeperTopBar("Data usage", onBack = onBack) }) { padding ->
        when (val state = usage) {
            UsageState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            UsageState.NeedsPermission -> Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard {
                    Text("Usage Access needed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android counts how much data every app sends and receives. Usage Access lets " +
                            "Gatekeeper read those counters, on this phone only. Gatekeeper itself still " +
                            "has no internet access.",
                    )
                    Button(onClick = { SystemIntents.openUsageAccessSettings(context) }) { Text("Grant Usage Access") }
                }
            }
            is UsageState.Ready -> {
                val items = if (sortBackground) state.items.sortedByDescending { it.row.background }
                else state.items.sortedByDescending { it.row.total }
                val max = items.maxOfOrNull { if (sortBackground) it.row.background else it.row.total }?.coerceAtLeast(1) ?: 1
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                UsagePeriod.entries.forEach { p ->
                                    FilterChip(selected = p == period, onClick = { insights.setPeriod(p) }, label = { Text(p.label) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = sortBackground, onClick = { sortBackground = true }, label = { Text("Background first") })
                                FilterChip(selected = !sortBackground, onClick = { sortBackground = false }, label = { Text("Total") })
                            }
                            Text(
                                "\"Background\" is data used while the app was not on screen. Apps you have " +
                                    "blocked may still show small amounts: that's what they tried to send " +
                                    "into Gatekeeper's tunnel, where it was dropped.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(items, key = { it.row.uid }) { item ->
                        UsageRowItem(item, if (sortBackground) item.row.background else item.row.total, max) {
                            item.packageName?.let(onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRowItem(item: UsageItem, value: Long, max: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.packageName != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.packageName != null) AppIcon(item.packageName) else Spacer(Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatBytes(context, value), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (value.toFloat() / max).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Background ${formatBytes(context, item.row.background)} · On screen ${formatBytes(context, item.row.foreground)}" +
                    " · Wi-Fi ${formatBytes(context, item.row.wifi)} · Mobile ${formatBytes(context, item.row.mobile)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
