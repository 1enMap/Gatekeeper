package app.gatekeeper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Gatekeeper's own look ("Night watch"): deep ink surfaces, a mint "protected" accent, amber for
 * things worth a look, coral for problems and violet for Tor. Fixed rather than wallpaper-based so
 * the status colours always mean the same thing.
 */

private val Dark = darkColorScheme(
    primary = Color(0xFF4BE3A6),
    onPrimary = Color(0xFF00381F),
    primaryContainer = Color(0xFF0E4A34),
    onPrimaryContainer = Color(0xFFB6F5D6),
    secondary = Color(0xFF8ECFD9),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF16404A),
    onSecondaryContainer = Color(0xFFC9EEF4),
    tertiary = Color(0xFFB79CFF),
    onTertiary = Color(0xFF2A1A5E),
    tertiaryContainer = Color(0xFF3A2C66),
    onTertiaryContainer = Color(0xFFE6DDFF),
    error = Color(0xFFFF7A70),
    onError = Color(0xFF5A0B05),
    errorContainer = Color(0xFF5A1A16),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF0A0F14),
    onBackground = Color(0xFFE3EAF0),
    surface = Color(0xFF0A0F14),
    onSurface = Color(0xFFE3EAF0),
    surfaceVariant = Color(0xFF1C2834),
    onSurfaceVariant = Color(0xFF9AAAB8),
    surfaceContainerLowest = Color(0xFF070B0F),
    surfaceContainerLow = Color(0xFF0F171E),
    surfaceContainer = Color(0xFF131D26),
    surfaceContainerHigh = Color(0xFF19252F),
    surfaceContainerHighest = Color(0xFF203039),
    outline = Color(0xFF3A4A58),
    outlineVariant = Color(0xFF26333F),
    inverseSurface = Color(0xFFE3EAF0),
    inverseOnSurface = Color(0xFF131D26),
    inversePrimary = Color(0xFF007A50),
)

private val Light = lightColorScheme(
    primary = Color(0xFF007A50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4F1D2),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF2D6A74),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEBF0),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF6A4FC2),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8DEFF),
    onTertiaryContainer = Color(0xFF22005D),
    error = Color(0xFFC4372D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF121A20),
    surface = Color(0xFFF4F7F6),
    onSurface = Color(0xFF121A20),
    surfaceVariant = Color(0xFFDDE5E2),
    onSurfaceVariant = Color(0xFF4D5B66),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9FBFA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF3F1),
    surfaceContainerHighest = Color(0xFFE5ECE9),
    outline = Color(0xFF73828E),
    outlineVariant = Color(0xFFD3DCD9),
    inverseSurface = Color(0xFF26313A),
    inverseOnSurface = Color(0xFFEDF2F0),
    inversePrimary = Color(0xFF4BE3A6),
)

/** Meaning-carrying colours used across the app. */
@Immutable
data class StatusColors(
    val good: Color,
    val warn: Color,
    val danger: Color,
    val tor: Color,
    val tracker: Color,
    /** Hero card gradient. */
    val heroStart: Color,
    val heroEnd: Color,
)

private val DarkStatus = StatusColors(
    good = Color(0xFF4BE3A6),
    warn = Color(0xFFFFB547),
    danger = Color(0xFFFF7A70),
    tor = Color(0xFFB79CFF),
    tracker = Color(0xFFE48BD6),
    heroStart = Color(0xFF0F3A2C),
    heroEnd = Color(0xFF102A3A),
)

private val LightStatus = StatusColors(
    good = Color(0xFF007A50),
    warn = Color(0xFFB26A00),
    danger = Color(0xFFC4372D),
    tor = Color(0xFF6A4FC2),
    tracker = Color(0xFF9C2F8E),
    heroStart = Color(0xFFCFF5E3),
    heroEnd = Color(0xFFD5ECF2),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val GatekeeperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun GatekeeperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            shapes = GatekeeperShapes,
            typography = typography(),
            content = content,
        )
    }
}

/** Shorthand: `Gk.status.good` etc. */
object Gk {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}
