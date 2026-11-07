package app.sloplight.ui.theme

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.platform.LocalContext

/** Light or dark, as the theme was given (for the colors the scheme doesn't name) */
val LocalIsDarkTheme = compositionLocalOf { false }

/**
 * Sloplight's look: Material 3 Expressive with restraint. A calm periwinkle
 * on neutral greys (the wallpaper's colors only when asked for: they made the
 * app muddy), and motion that is quick and barely springy (the stock
 * Expressive springs are slow and bouncy), light or dark as the system is.
 */
@Composable
fun SloplightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context).widenLightSurfaceRamp().widenLightSecondary()
        darkTheme -> DarkColors
        else -> LightColors.widenLightSecondary()
    }
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = SnappyMotion,
            content = content,
        )
    }
}

/**
 * The light dynamic scheme's surface containers a step further apart: the
 * system's ramp is so flat that cards barely show on the page (the static
 * light scheme has the wider ramp in its values already). Dark is fine as is.
 */
private fun ColorScheme.widenLightSurfaceRamp(): ColorScheme = copy(
    surfaceContainerLow = surfaceContainerLow.shiftLightness(-0.0115f),
    surfaceContainer = surfaceContainer.shiftLightness(-0.02f),
    surfaceContainerHigh = surfaceContainerHigh.shiftLightness(-0.03f),
    surfaceContainerHighest = surfaceContainerHighest.shiftLightness(-0.04f),
    surfaceDim = surfaceDim.shiftLightness(-0.05f),
)

/** Light schemes' secondary a little darker: selected pills (secondaryContainer) stand off the cards */
private fun ColorScheme.widenLightSecondary(): ColorScheme = copy(
    secondary = secondary.shiftLightness(-0.02f),
    secondaryContainer = secondaryContainer.shiftLightness(-0.03f),
)

private fun Color.shiftLightness(delta: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = (hsl[2] + delta).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * Every component's springs, stiffer: movement settles in about a third of
 * the stock time with a hint of overshoot, effects (color, fade) without any.
 */
val SnappyMotion: MotionScheme = object : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 1200f)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 1600f)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 600f)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF405AA9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE1FF),
    onPrimaryContainer = Color(0xFF00164F),
    secondary = Color(0xFF595E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE1F9),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF745470),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F8),
    onTertiaryContainer = Color(0xFF2C122B),
    error = Color(0xFFBA1B1B),
    errorContainer = Color(0xFFFFDAD4),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF410001),
    background = Color(0xFFFBF8FD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBF8FD),
    onSurface = Color(0xFF1B1B1F),
    surfaceDim = Color(0xFFC2C0C4),
    surfaceBright = Color(0xFFFBF8FD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFEDF2),
    surfaceContainer = Color(0xFFE3E1E6),
    surfaceContainerHigh = Color(0xFFD8D6DB),
    surfaceContainerHighest = Color(0xFFCDCBD0),
    surfaceVariant = Color(0xFFE1E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF75767F),
    inverseOnSurface = Color(0xFFF3F0F5),
    inverseSurface = Color(0xFF303033),
    inversePrimary = Color(0xFFB4C5FF),
    surfaceTint = Color(0xFF405AA9),
    outlineVariant = Color(0xFFC6C6D0),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF032978),
    primaryContainer = Color(0xFF25418F),
    onPrimaryContainer = Color(0xFFDAE1FF),
    secondary = Color(0xFFC1C5DC),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF414659),
    onSecondaryContainer = Color(0xFFDDE1F9),
    tertiary = Color(0xFFE3BBDB),
    onTertiary = Color(0xFF422740),
    tertiaryContainer = Color(0xFF5A3D57),
    onTertiaryContainer = Color(0xFFFFD7F8),
    error = Color(0xFFFFB4A9),
    errorContainer = Color(0xFF930006),
    onError = Color(0xFF680003),
    onErrorContainer = Color(0xFFFFDAD4),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE3E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE3E1E6),
    surfaceDim = Color(0xFF131316),
    surfaceBright = Color(0xFF39393C),
    surfaceContainerLowest = Color(0xFF0D0E11),
    surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF2A2A2D),
    surfaceContainerHighest = Color(0xFF343438),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF8F909A),
    inverseOnSurface = Color(0xFF303033),
    inverseSurface = Color(0xFFE3E1E6),
    inversePrimary = Color(0xFF405AA9),
    surfaceTint = Color(0xFFB4C5FF),
    outlineVariant = Color(0xFF45464F),
    scrim = Color(0xFF000000),
)
