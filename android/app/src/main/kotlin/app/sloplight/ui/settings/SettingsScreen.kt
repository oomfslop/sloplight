package app.sloplight.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.sloplight.data.prefs.PrefsStore
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import kotlin.system.exitProcess

/** The streaming settings, over the app's default SharedPreferences */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
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
