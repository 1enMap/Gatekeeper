package app.gatekeeper.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gatekeeper.R
import app.gatekeeper.data.Mode
import app.gatekeeper.ui.theme.Gk

val Mode.label: String
    get() = when (this) {
        Mode.ALLOWED -> "Allowed"
        Mode.BLOCKED -> "Blocked"
        Mode.WHILE_OPEN -> "While open"
    }

@Composable
fun ModeSelector(selected: Mode, onSelect: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val modes = Mode.entries
    SingleChoiceSegmentedButtonRow(modifier) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                icon = {},
                label = { Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatekeeperTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                }
            }
        },
        actions = { actions() },
    )
}

@Composable
fun IconAction(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painterResource(icon), contentDescription = description) }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** One line of the status card: a coloured dot plus text. */
@Composable
fun StatusLine(ok: Boolean, text: String, warn: Boolean = false) {
    val color = when {
        ok -> Gk.status.good
        warn -> Gk.status.warn
        else -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Bullet(text: String) {
    Row {
        Text("•", modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
