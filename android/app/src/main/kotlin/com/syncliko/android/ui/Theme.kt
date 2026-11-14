package com.syncliko.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Syncliko palette: an indigo → violet brand (tonal values from a Material 3 palette built on
// seed #5C6BFF), so light and dark themes keep proper contrast without relying on the wallpaper.

private val LightScheme = lightColorScheme(
    primary = Color(0xFF4A56D1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF000D61),
    inversePrimary = Color(0xFFBCC2FF),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F1),
    onTertiaryContainer = Color(0xFF2D1228),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF4A56D1),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF1A2591),
    primaryContainer = Color(0xFF323DB8),
    onPrimaryContainer = Color(0xFFDFE0FF),
    inversePrimary = Color(0xFF4A56D1),
    secondary = Color(0xFFC4C4DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFFE6BAD7),
    onTertiary = Color(0xFF44263E),
    tertiaryContainer = Color(0xFF5D3C55),
    onTertiaryContainer = Color(0xFFFFD7F1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFBCC2FF),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

/** Colors outside the Material scheme: the hero gradient and status colors, per theme. */
@Immutable
data class SynclikoColors(
    val heroGradient: Brush,
    val onHero: Color,
    val online: Color,
    val warning: Color,
)

private val LightExtras = SynclikoColors(
    heroGradient = Brush.linearGradient(listOf(Color(0xFF5865F2), Color(0xFF7C3AED))),
    onHero = Color.White,
    online = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
)

private val DarkExtras = SynclikoColors(
    // Deeper and less saturated than the light variant so it doesn't glare on OLED.
    heroGradient = Brush.linearGradient(listOf(Color(0xFF3A43B8), Color(0xFF5B2BB5))),
    onHero = Color(0xFFF3F1FF),
    online = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
)

val LocalSynclikoColors = staticCompositionLocalOf { LightExtras }

private val SynclikoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun SynclikoTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor -> runCatching { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
            .getOrElse { if (dark) DarkScheme else LightScheme }
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalSynclikoColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = scheme, shapes = SynclikoShapes, content = content)
    }
}

object Syncliko {
    val colors: SynclikoColors
        @Composable get() = LocalSynclikoColors.current
}
