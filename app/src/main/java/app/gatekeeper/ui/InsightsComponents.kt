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

val Risk.color: Color
    get() = when (this) {
        Risk.HIGH -> Color(0xFFC62828)
        Risk.MEDIUM -> Color(0xFFEF6C00)
        Risk.LOW -> Color(0xFF2E7D32)
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
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

val TrackerColor = Color(0xFF6A1B9A)
val TorColor = Color(0xFF7E4798)
