package app.sloplight.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sloplight.data.AppearanceSettings
import app.sloplight.data.ThemeMode
import app.sloplight.data.rememberAppearance
import app.sloplight.ui.components.SegmentGap
import app.sloplight.ui.components.SegmentRow
import app.sloplight.ui.components.cardColor
import app.sloplight.ui.components.segmentShape
import com.limelight.BuildConfig
import com.limelight.R

/** What the main settings screen shows above and below the categories (titles already read) */
@Immutable
internal class HubInfo(
    val onProfiles: () -> Unit,
    val activeProfile: String,
    val appearanceTitle: String,
    val profilesTitle: String,
    val aboutTitle: String,
)

/** The app's own settings (not the stream's): language goes to Appearance; About is the version and debug info (box art size is the app grid's, in its top bar) */
private const val APP_GROUP = "category_settings_misc"
private val APPEARANCE_KEYS = listOf(Keys.LANGUAGE)

/**
 * The main settings screen: Appearance (theme, wallpaper colors, language,
 * box art size), Profiles, the streaming categories, About at the bottom.
 */
internal fun hubEntries(groups: List<GroupItem>, info: HubInfo): List<Entry> = buildList {
    val appItems = groups.firstOrNull { it.key == APP_GROUP }?.sections?.flatMap { it.items }.orEmpty()
    val appearanceItems = APPEARANCE_KEYS.mapNotNull { k -> appItems.firstOrNull { it.setting.key == k } }
    val wallpaper = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val appearanceCount = 1 + (if (wallpaper) 1 else 0) + appearanceItems.size

    add(Entry.Header("h:appearance", info.appearanceTitle, first = true))
    add(Entry.Theme(0, appearanceCount))
    if (wallpaper) add(Entry.Wallpaper(1, appearanceCount))
    val offset = appearanceCount - appearanceItems.size
    appearanceItems.forEachIndexed { i, item -> add(Entry.Row(item, offset + i, appearanceCount)) }

    add(Entry.Header("h:profiles", info.profilesTitle, first = false))
    add(Entry.ProfilesLink)

    val streaming = groups.filter { it.key != APP_GROUP }
    add(Entry.StreamingHeader)
    streaming.forEachIndexed { i, g -> add(Entry.GroupLink(g, i, streaming.size)) }

    add(Entry.Header("h:about", info.aboutTitle, first = false))
    add(Entry.Version)
    add(Entry.DebugInfo)
}

private fun Modifier.gapAfter(index: Int, count: Int) = padding(bottom = if (index < count - 1) SegmentGap else 0.dp)

/** Light, dark or as the system is: three connected buttons under the title */
@Composable
internal fun ThemeRow(index: Int, count: Int) {
    val context = LocalContext.current
    val appearance = rememberAppearance()
    Surface(shape = segmentShape(index, count), color = cardColor(), modifier = Modifier.gapAfter(index, count)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DarkMode, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
            }
            val modes = ThemeMode.entries
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 12.dp),
            ) {
                modes.forEachIndexed { i, mode ->
                    ToggleButton(
                        checked = appearance.mode == mode,
                        onCheckedChange = { AppearanceSettings.setMode(context, mode) },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) {
                        Text(
                            stringResource(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.settings_theme_system
                                    ThemeMode.LIGHT -> R.string.settings_theme_light
                                    ThemeMode.DARK -> R.string.settings_theme_dark
                                }
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The wallpaper's colors instead of Sloplight's own (Android 12+) */
@Composable
internal fun WallpaperRow(index: Int, count: Int) {
    val context = LocalContext.current
    val appearance = rememberAppearance()
    SegmentRow(
        shapes = segmentShape(index, count),
        modifier = Modifier.gapAfter(index, count),
        onClick = { AppearanceSettings.setWallpaperColors(context, !appearance.wallpaperColors) },
        leadingContent = { Icon(Icons.Outlined.Palette, null) },
        trailingContent = { Switch(checked = appearance.wallpaperColors, onCheckedChange = null) },
        supportingContent = { Text(stringResource(R.string.settings_wallpaper_colors_summary)) },
    ) { Text(stringResource(R.string.settings_wallpaper_colors)) }
}

/** The profiles, with the one in use */
@Composable
internal fun ProfilesRow(info: HubInfo) {
    SegmentRow(
        shapes = segmentShape(0, 1),
        onClick = info.onProfiles,
        leadingContent = { Icon(Icons.Outlined.AccountCircle, null) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingContent = {
            Text(if (info.activeProfile.isEmpty()) stringResource(R.string.settings_profiles_none) else stringResource(R.string.settings_profiles_active, info.activeProfile))
        },
    ) { Text(info.profilesTitle) }
}

/** The app's version (and build) */
@Composable
internal fun VersionRow() {
    SegmentRow(
        shapes = segmentShape(0, 2),
        modifier = Modifier.gapAfter(0, 2),
        leadingContent = { Icon(Icons.Outlined.Info, null) },
        supportingContent = { Text("${stringResource(R.string.app_label)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
    ) { Text(stringResource(R.string.settings_version)) }
}

/** The debug screen: controllers, vibration, decoders */
@Composable
internal fun DebugInfoRow() {
    val context = LocalContext.current
    SegmentRow(
        shapes = segmentShape(1, 2),
        onClick = { context.startActivity(android.content.Intent(context, com.limelight.DebugInfoActivity::class.java)) },
        leadingContent = { Icon(Icons.Outlined.BugReport, null) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingContent = { Text(stringResource(R.string.settings_debug_info_summary)) },
    ) { Text(stringResource(R.string.title_debug_info)) }
}
