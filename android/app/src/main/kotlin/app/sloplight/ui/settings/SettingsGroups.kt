package app.sloplight.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.vector.ImageVector

/** A category's icon in the hub */
internal fun groupIcon(key: String): ImageVector = when (key) {
    "category_video_settings" -> Icons.Outlined.Videocam
    "category_audio_settings" -> Icons.AutoMirrored.Outlined.VolumeUp
    "category_gamepad_settings" -> Icons.Outlined.SportsEsports
    "category_input_settings" -> Icons.Outlined.TouchApp
    "category_keyboard" -> Icons.Outlined.Keyboard
    "category_onscreen_controls" -> Icons.Outlined.Gamepad
    "category_ui_settings" -> Icons.Outlined.Tv
    "category_host_settings" -> Icons.Outlined.Computer
    "category_settings_misc" -> Icons.Outlined.Info
    else -> Icons.Outlined.Settings
}

/** The category of [key], if there is one */
internal fun groupOf(key: String): SettingGroup? = SettingsCatalog.groups.firstOrNull { it.key == key }
