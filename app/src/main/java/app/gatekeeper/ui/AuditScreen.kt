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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gatekeeper.insights.AppAudit
import app.gatekeeper.insights.Risk

@Composable
fun AuditScreen(insights: InsightsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val audit by insights.audit.collectAsStateWithLifecycle()
    var onlyConcerns by rememberSaveable { mutableStateOf(true) }
    var showSystem by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { GatekeeperTopBar("Security audit", onBack = onBack) }) { padding ->
        val list = audit
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Checking your apps…", Modifier.padding(top = 12.dp))
                }
            }
            return@Scaffold
        }
        val visible = list
            .filter { showSystem || !it.isSystem }
            .filter { !onlyConcerns || it.risk != Risk.LOW }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = list.filter { showSystem || !it.isSystem }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("${shown.count { it.risk == Risk.HIGH }} need a look", Risk.HIGH.color)
                        Pill("${shown.count { it.risk == Risk.MEDIUM }} worth checking", Risk.MEDIUM.color)
                        Pill("${shown.count { it.risk == Risk.LOW }} fine", Risk.LOW.color)
                    }
                    Text(
                        "Checked on this phone, offline: special powers (accessibility, notification " +
                            "access, device admin), sensitive permissions combined with internet access, " +
                            "where each app came from, look-alikes of popular apps and background data use. " +
                            "A flag means \"have a look\", not \"this is malware\".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = onlyConcerns, onClick = { onlyConcerns = !onlyConcerns }, label = { Text("Only concerns") })
                        FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("System apps") })
                    }
                }
            }
            if (visible.isEmpty()) {
                item { Text("Nothing to worry about here. 🎉", Modifier.padding(16.dp)) }
            }
            items(visible, key = { it.packageName }) { a -> AuditRow(a) { onOpen(a.packageName) } }
        }
    }
}

@Composable
private fun AuditRow(a: AppAudit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Pill(a.risk.label, a.risk.color)
            }
            val top = a.findings.firstOrNull { !it.good }
            if (top != null) {
                Text(top.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val access = buildList {
                addAll(a.sensitive)
                add(if (a.hasInternet) "Online" else "Offline")
            }
            Text(
                access.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
