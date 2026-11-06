package app.sloplight.ui.settings

import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/** Something the device must have for a setting to be shown */
enum class Feature {
    TOUCHSCREEN,
    /** Pointer capture (Oreo+), and not a SHIELD (it has raw mouse input there) */
    ABSOLUTE_MOUSE,
    /** Gamepad motion, LED, battery extensions (Android 12+) */
    CONTROLLER_SENSORS,
    /** An accelerometer or a gyroscope */
    DEVICE_MOTION,
    USB_HOST,
    PIP,
    VIBRATOR,
    /** A vibrator with amplitude control (Oreo+) */
    VIBRATOR_AMPLITUDE,
    /** A display with HDR10 (Nougat+) */
    HDR10,
}

/** Choices whose entries come from the device and the other settings */
enum class ChoiceSource { RESOLUTION, FPS }

/** The custom text entries, each with its own validation */
enum class TextKind { RESOLUTION, BITRATE, REFRESH_RATE }

/** What an action row does */
enum class SettingAction {
    RESET_OSC,
    RESET_KEYBOARD,
    IMPORT_KEYBOARD,
    EXPORT_KEYBOARD,
    SHARE_PERF_LOGS,
    DEBUG_INFO,
    IMPORT_SPECIAL_BUTTONS,
}

/** What else happens when a setting changes */
enum class ChangeEffect { NONE, RESOLUTION, FPS, PERF_LOGGING }

/**
 * One setting. [title] and [summary] are string resources (0: none).
 * [enabledWhen]: the key of a toggle that has to be on (and itself enabled).
 * [visibleWhen]: what the device needs for it to be shown at all.
 */
@Immutable
sealed interface Setting {
    val key: String
    @get:StringRes val title: Int
    @get:StringRes val summary: Int
    val enabledWhen: String?
    val visibleWhen: Feature?
    val hiddenInProfile: Boolean

    /** A switch; a Boolean in the prefs */
    @Immutable
    data class Toggle(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val default: Boolean,
        /** The default is "small icons" on phone-sized screens */
        val smallModeDefault: Boolean = false,
        val effect: ChangeEffect = ChangeEffect.NONE,
        override val enabledWhen: String? = null,
        override val visibleWhen: Feature? = null,
        override val hiddenInProfile: Boolean = false,
    ) : Setting

    /** One of a list; a String in the prefs */
    @Immutable
    data class Choice(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val default: String,
        @ArrayRes val entries: Int,
        @ArrayRes val values: Int,
        /** Entries computed from the display and other settings */
        val source: ChoiceSource? = null,
        val effect: ChangeEffect = ChangeEffect.NONE,
        @DrawableRes val icon: Int = 0,
        override val enabledWhen: String? = null,
        override val visibleWhen: Feature? = null,
        override val hiddenInProfile: Boolean = false,
    ) : Setting

    /** A number on a slider; an Int in the prefs, shown divided by [divisor] */
    @Immutable
    data class Slider(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        /** null: the default bitrate of the chosen resolution and frame rate */
        val default: Int?,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        val divisor: Int = 1,
        @StringRes val suffixRes: Int = 0,
        val suffix: String? = null,
        @StringRes val dialogMessage: Int = 0,
        override val enabledWhen: String? = null,
        override val visibleWhen: Feature? = null,
        override val hiddenInProfile: Boolean = false,
    ) : Setting

    /** Text typed in a dialog; a String in the prefs */
    @Immutable
    data class Text(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val default: String,
        val kind: TextKind,
        @StringRes val dialogTitle: Int,
        @StringRes val dialogMessage: Int = 0,
        override val enabledWhen: String? = null,
        override val visibleWhen: Feature? = null,
        override val hiddenInProfile: Boolean = false,
    ) : Setting

    /** A row that does something; stores nothing */
    @Immutable
    data class Action(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val action: SettingAction,
        override val enabledWhen: String? = null,
        override val visibleWhen: Feature? = null,
        override val hiddenInProfile: Boolean = false,
    ) : Setting

    /** A row that opens a web page ([url], or the string [urlRes]) */
    @Immutable
    data class Link(
        override val key: String,
        @StringRes override val title: Int,
        @StringRes override val summary: Int,
        val url: String? = null,
        @StringRes val urlRes: Int = 0,
        override val enabledWhen: String? = null,
        override val visibleWhen: Feature? = null,
        override val hiddenInProfile: Boolean = false,
    ) : Setting
}

/**
 * A category of settings. Beyond the first [initiallyShown] ones the rest
 * sit behind a "show more" row (null: all shown).
 */
@Immutable
data class SettingGroup(
    val key: String,
    @StringRes val title: Int,
    val settings: List<Setting>,
    val initiallyShown: Int? = null,
    val visibleWhen: Feature? = null,
)
