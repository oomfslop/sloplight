package app.sloplight.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager

/** Light, dark, or as the system is */
enum class ThemeMode(val key: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

/** How the app looks: its theme mode and whether it takes the wallpaper's colors */
@Immutable
data class Appearance(val mode: ThemeMode, val wallpaperColors: Boolean)

/** The appearance settings (in the app's default SharedPreferences, next to the streaming ones) */
object AppearanceSettings {
    const val THEME_MODE = "ui_theme_mode"
    const val WALLPAPER_COLORS = "ui_wallpaper_colors"

    fun read(prefs: SharedPreferences) = Appearance(
        mode = ThemeMode.entries.firstOrNull { it.key == prefs.getString(THEME_MODE, null) } ?: ThemeMode.SYSTEM,
        wallpaperColors = prefs.getBoolean(WALLPAPER_COLORS, false),
    )

    fun read(context: Context) = read(PreferenceManager.getDefaultSharedPreferences(context))

    fun setMode(context: Context, mode: ThemeMode) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(THEME_MODE, mode.key).apply()

    fun setWallpaperColors(context: Context, on: Boolean) =
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(WALLPAPER_COLORS, on).apply()
}

/** The appearance, kept current as it is changed */
@Composable
fun rememberAppearance(): Appearance {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var appearance by remember { mutableStateOf(AppearanceSettings.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == AppearanceSettings.THEME_MODE || key == AppearanceSettings.WALLPAPER_COLORS) appearance = AppearanceSettings.read(p)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return appearance
}

/** Dark, by the mode (the system's when it follows the system) */
@Composable
fun Appearance.isDark(): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
