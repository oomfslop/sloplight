package app.sloplight.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import app.sloplight.data.prefs.PrefsStore
import com.google.gson.Gson
import com.limelight.DebugInfoActivity
import com.limelight.GameMenu
import com.limelight.R
import com.limelight.binding.input.virtual_controller.VirtualControllerConfigurationLoader
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardControllerConfigurationLoader
import com.limelight.utils.FileUriUtils
import com.limelight.utils.HelpLauncher
import com.limelight.utils.PerformanceDataTracker
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The settings' actions: resets, imports and exports, logs, links */
internal object SettingsActions {
    fun toast(context: Context, text: String, long: Boolean = false) {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun toast(context: Context, text: Int) = toast(context, context.getString(text))

    /** A JSON file to pick */
    fun openJsonIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")

    /** The prefs file of the chosen keyboard layout */
    private fun keyboardLayoutName(store: PrefsStore): String =
        store.getString(
            KeyBoardControllerConfigurationLoader.OSC_PREFERENCE,
            KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE,
        ) ?: KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE

    /** Clears the saved on-screen controls layout */
    fun resetOsc(context: Context) {
        context.getSharedPreferences(VirtualControllerConfigurationLoader.OSC_PREFERENCE, Context.MODE_PRIVATE)
            .edit().clear().apply()
        toast(context, R.string.toast_reset_osc_success)
    }

    /** Clears the chosen virtual keyboard layout (named in the app's own settings) */
    fun resetKeyboard(context: Context) {
        val name = PreferenceManager.getDefaultSharedPreferences(context).getString(
            KeyBoardControllerConfigurationLoader.OSC_PREFERENCE,
            KeyBoardControllerConfigurationLoader.OSC_PREFERENCE_VALUE,
        )
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        toast(context, R.string.toast_reset_osc_success)
    }

    /** Replaces the chosen keyboard layout with the one in the JSON file */
    fun importKeyboard(context: Context, store: PrefsStore, uri: Uri) {
        try {
            val json = FileUriUtils.openUriForRead(context, uri)
            if (json.isNullOrEmpty()) {
                toast(context, R.string.pref_empty_file)
                return
            }
            val obj = JSONObject(json)
            val editor = context.getSharedPreferences(keyboardLayoutName(store), Context.MODE_PRIVATE).edit()
            editor.clear()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                editor.putString(key, obj.getString(key))
            }
            editor.apply()
            toast(context, R.string.pref_import_success)
        } catch (e: Exception) {
            e.printStackTrace()
            toast(context, context.getString(R.string.pref_error_occurred) + e.message)
        }
    }

    /** Takes the special buttons from the JSON file */
    fun importSpecialButtons(context: Context, uri: Uri) {
        try {
            val json = FileUriUtils.openUriForRead(context, uri)
            if (json.isNullOrEmpty()) {
                toast(context, R.string.pref_empty_file)
                return
            }
            context.getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(GameMenu.KEY_NAME, json).apply()
            toast(context, R.string.pref_import_success)
        } catch (e: Exception) {
            e.printStackTrace()
            toast(context, context.getString(R.string.pref_error_occurred) + e.message)
        }
    }

    /** Shares the chosen keyboard layout as a JSON file */
    fun exportKeyboard(context: Context, store: PrefsStore) {
        try {
            val dir = File(context.externalCacheDir ?: context.cacheDir, "export_settings")
            if (!dir.exists()) dir.mkdir()
            val name = keyboardLayoutName(store)
            val map = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val file = File(dir, "$name.json")
            if (!FileUriUtils.writerFileString(file, Gson().toJson(map))) {
                toast(context, R.string.pref_error_occurred)
                return
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setType("application/json")
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.pref_save_keyboard_profile)))
        } catch (e: Exception) {
            e.printStackTrace()
            toast(context, R.string.pref_error_occurred)
        }
    }

    /** Mails the performance log */
    fun sharePerfLogs(context: Context) {
        val logs = PerformanceDataTracker().getLog(context)
        if (logs == null || logs.isBlank()) {
            toast(context, R.string.toast_no_logs)
            return
        }
        try {
            val file = File(context.cacheDir, "artemistics_logs.txt")
            file.outputStream().use { it.write(logs.toByteArray(Charsets.UTF_8)) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.email_recipient)))
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.email_subject))
                .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.email_prefix_message))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.email_chooser_title)))
        } catch (e: IOException) {
            Log.d("PerformanceDataTracker", "Error creating log file")
        } catch (e: ActivityNotFoundException) {
            toast(context, R.string.toast_no_email_clients)
        }
    }

    fun clearPerfLogs(context: Context) = PerformanceDataTracker().clearLogs(context)

    fun debugInfo(context: Context) {
        context.startActivity(Intent(context, DebugInfoActivity::class.java))
    }

    fun openLink(context: Context, link: Setting.Link) {
        val url = link.url ?: context.getString(link.urlRes)
        HelpLauncher.launchUrl(context, url)
    }
}
