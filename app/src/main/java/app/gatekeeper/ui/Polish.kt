package app.gatekeeper.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gatekeeper.R
import app.gatekeeper.ui.theme.Gk

/** The protection score as an animated ring, coloured by how good it is. */
@Composable
fun ScoreRing(score: Int, modifier: Modifier = Modifier, size: Dp = 124.dp, label: String = "score") {
    val target = score.coerceIn(0, 100) / 100f
    val sweep by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "score")
    val color = scoreColor(score)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color.copy(alpha = 0.55f))),
                -90f, 360f * sweep, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(sweep * 100).toInt()}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun scoreColor(score: Int): Color = when {
    score >= 85 -> Gk.status.good
    score >= 60 -> Gk.status.warn
    else -> Gk.status.danger
}

/** A round, softly tinted icon. */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Color, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A dashboard tile: icon, a big value and a caption. */
@Composable
fun StatTile(
    @DrawableRes icon: Int,
    title: String,
    value: String,
    caption: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, tint, 34.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A highlighted issue with an optional action, e.g. "Battery optimisation is on · Fix". */
@Composable
fun AlertRow(
    text: String,
    tint: Color,
    @DrawableRes icon: Int = R.drawable.ic_warning,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = tint.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    action,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onAction)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Small uppercase heading between sections. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** A tappable list row with icon, title, subtitle and a chevron. */
@Composable
fun NavRow(@DrawableRes icon: Int, title: String, subtitle: String? = null, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun VerticalGap(height: Dp = 8.dp) = Spacer(Modifier.height(height))
