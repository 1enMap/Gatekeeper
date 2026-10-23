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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.R
import app.gatekeeper.data.Mode
import app.gatekeeper.data.Route

@Composable
fun AppListScreen(viewModel: MainViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onLive: (String) -> Unit) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val setMode = remember(viewModel) { { pkg: String, mode: Mode -> viewModel.setMode(pkg, mode) } }

    // Items are keyed, so without this the list would stay anchored on the same app after a
    // re-sort and look like nothing happened.
    // Saved so coming back from an app's detail screen doesn't count as a change.
    val filterKey = "${filters.sort}|${filters.showSystem}|${filters.query}"
    var appliedFilterKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(filterKey) {
        if (appliedFilterKey != null && appliedFilterKey != filterKey) listState.scrollToItem(0)
        appliedFilterKey = filterKey
    }

    Scaffold(topBar = { GatekeeperTopBar("Apps", onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = filters.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = filters.showSystem,
                    onClick = { viewModel.setShowSystem(!filters.showSystem) },
                    label = { Text("System apps") },
                )
                Spacer(Modifier.weight(1f))
                Text("Sort", style = MaterialTheme.typography.labelMedium)
                FilterChip(
                    selected = filters.sort == SortOrder.NAME,
                    onClick = { viewModel.setSort(SortOrder.NAME) },
                    label = { Text("Name") },
                )
                FilterChip(
                    selected = filters.sort == SortOrder.MODE,
                    onClick = { viewModel.setSort(SortOrder.MODE) },
                    label = { Text("Mode") },
                )
            }
            val list = rows
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(list, key = { it.app.packageName }, contentType = { 0 }) { row ->
                        AppRowItem(row = row, onOpen = onOpen, onMode = setMode, onLive = onLive)
                    }
                }
            }
        }
    }
}

/** One compact line per app; the mode is changed through a small dropdown. */
@Composable
private fun AppRowItem(row: AppRow, onOpen: (String) -> Unit, onMode: (String, Mode) -> Unit, onLive: (String) -> Unit) {
    val pkg = row.app.packageName
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(pkg) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.route == Route.TOR || row.blockTrackers) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    if (row.route == Route.TOR) Pill("Tor", TorColor)
                    if (row.blockTrackers) Pill("No trackers", TrackerColor)
                }
            }
        }
        IconButton(onClick = { onLive(pkg) }) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = "Live activity")
        }
        ModeDropdown(row.mode) { onMode(pkg, it) }
    }
}

@Composable
private fun ModeDropdown(mode: Mode, onSelect: (Mode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }) {
            Text("● ", color = mode.color)
            Text(mode.label, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Mode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Text("●", color = option.color) },
                    onClick = {
                        open = false
                        if (option != mode) onSelect(option)
                    },
                )
            }
        }
    }
}

private val Mode.color: Color
    get() = when (this) {
        Mode.ALLOWED -> Color(0xFF2E7D32)
        Mode.BLOCKED -> Color(0xFFC62828)
        Mode.WHILE_OPEN -> Color(0xFFEF6C00)
    }
