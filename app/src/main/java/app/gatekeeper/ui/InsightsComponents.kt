package app.gatekeeper.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gatekeeper.insights.Risk
import app.gatekeeper.ui.theme.Gk
import androidx.compose.ui.text.font.FontWeight

val Risk.color: Color
    @Composable get() = when (this) {
        Risk.HIGH -> Gk.status.danger
        Risk.MEDIUM -> Gk.status.warn
        Risk.LOW -> Gk.status.good
    }

val Risk.label: String
    get() = when (this) {
        Risk.HIGH -> "Needs a look"
        Risk.MEDIUM -> "Worth checking"
        Risk.LOW -> "Looks fine"
    }

/** Small coloured pill, e.g. a risk level or "Tracker". */
@Composable
fun Pill(text: String, color: Color) {
    // Tinted pill: a soft fill of the colour with the colour itself as text, readable in both themes.
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

val TrackerColor: Color
    @Composable get() = Gk.status.tracker
val TorColor: Color
    @Composable get() = Gk.status.tor
