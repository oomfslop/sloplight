package app.sloplight.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sloplight.data.AppearanceSettings
import app.sloplight.data.ThemeMode
import app.sloplight.data.rememberAppearance
import app.sloplight.ui.components.SectionHeading
import app.sloplight.ui.components.SegmentRow
import app.sloplight.ui.components.SegmentedColumn
import app.sloplight.ui.components.segmentShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.sloplight.data.prefs.PrefsStore
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import kotlin.system.exitProcess

/**
 * The settings' hub: how the app looks, the profiles, then each category of
 * the streaming settings (search goes through all of them).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onProfiles: () -> Unit,
    activeProfile: String,
) {
    val context = LocalContext.current
    // Read up front as the old screen did (it also migrates legacy prefs)
    val startLanguage = remember { PreferenceConfiguration.readPreferences(context).language }
    val store = remember { PrefsStore.default(context) }
    val leave = {
        onBack()
        applyLanguageChange(context, startLanguage)
    }
    BackHandler(onBack = leave)
    SettingsContent(
        store = store,
        title = stringResource(R.string.action_settings),
        onBack = leave,
        onOpenGroup = onOpenCategory,
        hubInfo = HubInfo(
            onProfiles = onProfiles,
            activeProfile = activeProfile,
            appearanceTitle = stringResource(R.string.settings_appearance),
            profilesTitle = stringResource(R.string.profiles_title),
            aboutTitle = stringResource(R.string.settings_section_about),
        ),
    )
}

/** One category of the streaming settings, all of its settings */
@Composable
fun SettingsCategoryScreen(groupKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PrefsStore.default(context) }
    SettingsContent(
        store = store,
        title = stringResource(groupOf(groupKey)?.title ?: R.string.action_settings),
        onBack = onBack,
        groupKey = groupKey,
    )
}

/**
 * A new language takes a restart. Before Android 13 the app's task is
 * started again; from 13 on, going back to the system language needs the
 * app to be restarted by hand.
 */
private fun applyLanguageChange(context: Context, startLanguage: String?) {
    val language = PreferenceConfiguration.readPreferences(context).language
    if (language == startLanguage) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } else if (language == PreferenceConfiguration.DEFAULT_LANGUAGE) {
        SettingsActions.toast(context, context.getString(R.string.settings_language_reset_restart), long = true)
        exitProcess(0)
    }
}
