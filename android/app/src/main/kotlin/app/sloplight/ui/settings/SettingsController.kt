package app.sloplight.ui.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.limelight.R
import java.util.Locale

/** The dialog the settings screen shows, if any */
@Immutable
internal sealed interface SettingsDialog {
    data class Choose(val setting: Setting.Choice) : SettingsDialog
    data class Slide(val setting: Setting.Slider) : SettingsDialog
    data class Type(val setting: Setting.Text) : SettingsDialog
    data class Confirm(val setting: Setting.Action) : SettingsDialog
    data class Message(val title: Int, val text: Int) : SettingsDialog
}

/**
 * What happens when a setting is tapped or changed: the same rules the old
 * preference screen had (bitrate reset, native warnings, validation, ...).
 */
@Stable
internal class SettingsController(
    private val context: Context,
    val values: SettingsValues,
    val features: DeviceFeatures,
    private val resolutions: State<ChoiceList>,
    private val frameRates: State<ChoiceList>,
) {
    var dialog: SettingsDialog? by mutableStateOf(null)

    /** Set by the screen: start the file pickers */
    var pickKeyboardLayout: () -> Unit = {}
    var pickSpecialButtons: () -> Unit = {}

    private val staticLists = HashMap<String, ChoiceList>()

    /** The entries of a choice (reading the computed ones subscribes to them) */
    fun choiceList(s: Setting.Choice): ChoiceList = when (s.source) {
        ChoiceSource.RESOLUTION -> resolutions.value
        ChoiceSource.FPS -> frameRates.value
        null -> staticLists.getOrPut(s.key) { staticChoices(context, s.entries, s.values) }
    }

    fun enabled(s: Setting): Boolean = values.isEnabled(s, features)

    fun open(s: Setting) {
        when (s) {
            is Setting.Toggle -> onToggle(s, !values.toggle(s))
            is Setting.Choice -> dialog = SettingsDialog.Choose(s)
            is Setting.Slider -> dialog = SettingsDialog.Slide(s)
            is Setting.Text -> dialog = SettingsDialog.Type(s)
            is Setting.Link -> SettingsActions.openLink(context, s)
            is Setting.Action -> when (s.action) {
                SettingAction.RESET_OSC, SettingAction.RESET_KEYBOARD -> dialog = SettingsDialog.Confirm(s)
                SettingAction.IMPORT_KEYBOARD -> pickKeyboardLayout()
                SettingAction.IMPORT_SPECIAL_BUTTONS -> pickSpecialButtons()
                SettingAction.EXPORT_KEYBOARD -> SettingsActions.exportKeyboard(context, values.store)
                SettingAction.SHARE_PERF_LOGS -> SettingsActions.sharePerfLogs(context)
                SettingAction.DEBUG_INFO -> SettingsActions.debugInfo(context)
            }
        }
    }

    fun onToggle(s: Setting.Toggle, on: Boolean) {
        if (s.effect == ChangeEffect.PERF_LOGGING && !on) SettingsActions.clearPerfLogs(context)
        values.putBoolean(s.key, on)
    }

    fun onChoice(s: Setting.Choice, value: String) {
        dialog = null
        when (s.effect) {
            ChangeEffect.RESOLUTION -> {
                // A native (not one of the usual) resolution: warn
                if (value !in resolutions.value.staticValues) {
                    dialog = SettingsDialog.Message(R.string.title_native_res_dialog, R.string.text_native_res_dialog)
                }
                values.resetBitrate(resolution = value)
            }
            ChangeEffect.FPS -> {
                val native = frameRates.value.nativeFps
                if (native != null && native == value) {
                    dialog = SettingsDialog.Message(R.string.title_native_fps_dialog, R.string.text_native_res_dialog)
                }
                values.resetBitrate(fps = value)
            }
            else -> Unit
        }
        values.putString(s.key, value)
    }

    fun onSlider(s: Setting.Slider, value: Int) {
        dialog = null
        values.putInt(s.key, value)
    }

    fun onConfirm(s: Setting.Action) {
        dialog = null
        when (s.action) {
            SettingAction.RESET_OSC -> SettingsActions.resetOsc(context)
            SettingAction.RESET_KEYBOARD -> SettingsActions.resetKeyboard(context)
            else -> Unit
        }
    }

    /** The typed text, checked as the old preference did; saved only when it is valid */
    fun onText(s: Setting.Text, text: String) {
        dialog = null
        if (text.isEmpty()) {
            SettingsActions.toast(context, R.string.pref_enter_value_0_9999)
            return
        }
        when (s.kind) {
            TextKind.BITRATE -> {
                val mbps = text.toFloatOrNull()
                if (mbps == null) {
                    SettingsActions.toast(context, R.string.pref_error_occurred)
                    return
                }
                values.putInt(Keys.BITRATE, (mbps * 1000).toInt())
                values.putString(s.key, text)
                SettingsActions.toast(context, R.string.pref_set_success)
            }
            TextKind.RESOLUTION -> {
                // [width]x[height]
                val parts = text.split("x")
                val w = parts.getOrNull(0)?.toIntOrNull()
                val h = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || w == null || h == null || w <= 0 || h <= 0) {
                    SettingsActions.toast(context, R.string.pref_error_occurred)
                    return
                }
                values.putString(s.key, text)
            }
            TextKind.REFRESH_RATE -> {
                val rate = text.toFloatOrNull()
                if (rate == null) {
                    SettingsActions.toast(context, R.string.pref_error_occurred)
                    return
                }
                if (rate <= 0) {
                    SettingsActions.toast(context, R.string.pref_enter_value_0_9999)
                    return
                }
                // At most 3 decimals, no trailing zeros
                val formatted = String.format(Locale.ROOT, "%.3f", rate)
                    .replace(Regex("0+$"), "")
                    .replace(Regex("\\.$"), "")
                values.putString(s.key, formatted)
            }
        }
    }
}
