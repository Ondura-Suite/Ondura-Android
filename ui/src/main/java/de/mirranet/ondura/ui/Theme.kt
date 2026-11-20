package de.mirranet.ondura.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Colors of the Ondura design (same tint and surfaces as the desktop apps,
 * see apps/ondura/src/styles.css in Ondura-Core).
 */
object OnduraColors {
    val Tint = Color(0xFF3B5BDB)
    val TintLight = Color(0xFF5A75E1)
    val TintDark = Color(0xFF304BB4)
    val TintOnDark = Color(0xFF9CB0FF)

    val LightBackground = Color(0xFFF6F6FA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFECEEF2)
    val DarkBackground = Color(0xFF111216)
    val DarkSurface = Color(0xFF1C1D25)
    val DarkSurfaceVariant = Color(0xFF262833)

    val Flag = Color(0xFFC4314B)
    val Danger = Color(0xFFC42B1C)
    val Success = Color(0xFF107C41)
}

private val LightColors = lightColorScheme(
    primary = OnduraColors.Tint,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FB),
    onPrimaryContainer = Color(0xFF15245E),
    secondary = Color(0xFF4F5B7C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E7F4),
    onSecondaryContainer = Color(0xFF1B2440),
    tertiary = Color(0xFF6B4FBB),
    background = OnduraColors.LightBackground,
    onBackground = Color(0xFF1B1C22),
    surface = OnduraColors.LightSurface,
    onSurface = Color(0xFF1B1C22),
    surfaceVariant = OnduraColors.LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF5B5E6B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9F9FC),
    surfaceContainer = OnduraColors.LightBackground,
    surfaceContainerHigh = Color(0xFFEFF0F5),
    surfaceContainerHighest = OnduraColors.LightSurfaceVariant,
    outline = Color(0xFFC5C8D3),
    outlineVariant = Color(0xFFE2E4EB),
    error = OnduraColors.Danger,
)

private val DarkColors = darkColorScheme(
    primary = OnduraColors.TintOnDark,
    onPrimary = Color(0xFF0E1B4D),
    primaryContainer = Color(0xFF2A3C88),
    onPrimaryContainer = Color(0xFFDDE3FB),
    secondary = Color(0xFFB7C0DC),
    onSecondary = Color(0xFF212A43),
    secondaryContainer = Color(0xFF2E3550),
    onSecondaryContainer = Color(0xFFDCE1F3),
    tertiary = Color(0xFFC9B6FF),
    background = OnduraColors.DarkBackground,
    onBackground = Color(0xFFE4E4EA),
    surface = OnduraColors.DarkSurface,
    onSurface = Color(0xFFE4E4EA),
    surfaceVariant = OnduraColors.DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFA9ACB8),
    surfaceContainerLowest = Color(0xFF0C0D10),
    surfaceContainerLow = Color(0xFF17181E),
    surfaceContainer = OnduraColors.DarkSurface,
    surfaceContainerHigh = Color(0xFF22232C),
    surfaceContainerHighest = OnduraColors.DarkSurfaceVariant,
    outline = Color(0xFF4A4D5A),
    outlineVariant = Color(0xFF30323D),
    error = Color(0xFFFF8A80),
)

private val OnduraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** `theme`: "system", "light" or "dark". */
@Composable
fun OnduraTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = OnduraShapes,
        content = content,
    )
}
