package app.sloplight.ui.settings

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.sloplight.data.prefs.PrefsStore
import com.limelight.preferences.PreferenceConfiguration
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The settings' values as Compose state, one state per key so a change only
 * recomposes the rows that read it. Reads the prefs once up front; after
 * that only the keys that change are read again.
 */
@Stable
class SettingsValues(val store: PrefsStore, private val smallModeDefault: Boolean) {
    private val snapshot = HashMap<String, Any?>(store.all())
    private val states = HashMap<String, MutableState<Any?>>()

    init {
        // Every setting's state made up front, so none is first made inside a snapshot read
        for (key in SettingsCatalog.byKey.keys) state(key)
    }

    private fun state(key: String): MutableState<Any?> =
        states.getOrPut(key) { mutableStateOf(snapshot[key]) }

    /** A key changed in the store (null: anything may have) */
    fun onStoreChanged(key: String?) {
        if (key == null) {
            snapshot.clear()
            snapshot.putAll(store.all())
            for ((k, s) in states) s.value = snapshot[k]
        } else {
            val v = store.all()[key]
            snapshot[key] = v
            states[key]?.value = v
        }
    }

    private fun set(key: String, value: Any?) {
        snapshot[key] = value
        states[key]?.value = value
    }

    fun boolean(key: String, default: Boolean): Boolean = state(key).value as? Boolean ?: default
    fun string(key: String, default: String?): String? = state(key).value as? String ?: default
    fun int(key: String, default: Int): Int = (state(key).value as? Number)?.toInt() ?: default

    fun putBoolean(key: String, value: Boolean) {
        set(key, value)
        store.edit { putBoolean(key, value) }
    }

    fun putString(key: String, value: String) {
        set(key, value)
        store.edit { putString(key, value) }
    }

    fun putInt(key: String, value: Int) {
        set(key, value)
        store.edit { putInt(key, value) }
    }

    fun remove(key: String) {
        set(key, null)
        store.edit { remove(key) }
    }

    fun toggleDefault(s: Setting.Toggle) = if (s.smallModeDefault) smallModeDefault else s.default

    fun toggle(s: Setting.Toggle): Boolean = boolean(s.key, toggleDefault(s))

    fun choice(s: Setting.Choice): String = string(s.key, s.default) ?: s.default

    /** The slider's stored value (its default when there is none) */
    fun slider(s: Setting.Slider): Int = int(s.key, s.default ?: defaultBitrate())

    /** The default bitrate for the chosen resolution and frame rate */
    fun defaultBitrate(
        resolution: String = string(Keys.RESOLUTION, Keys.DEFAULT_RESOLUTION) ?: Keys.DEFAULT_RESOLUTION,
        fps: String = string(Keys.FPS, Keys.DEFAULT_FPS) ?: Keys.DEFAULT_FPS,
    ): Int = try {
        PreferenceConfiguration.getDefaultBitrate(resolution, fps)
    } catch (e: RuntimeException) {
        PreferenceConfiguration.getDefaultBitrate(Keys.DEFAULT_RESOLUTION, Keys.DEFAULT_FPS)
    }

    /** The bitrate back to the default of the resolution and frame rate (the chosen ones when null) */
    fun resetBitrate(resolution: String? = null, fps: String? = null) {
        putInt(
            Keys.BITRATE,
            defaultBitrate(
                resolution ?: string(Keys.RESOLUTION, Keys.DEFAULT_RESOLUTION) ?: Keys.DEFAULT_RESOLUTION,
                fps ?: string(Keys.FPS, Keys.DEFAULT_FPS) ?: Keys.DEFAULT_FPS,
            ),
        )
    }

    /** Enabled: its dependency (and that one's, and so on) is on */
    fun isEnabled(setting: Setting, features: DeviceFeatures): Boolean {
        if (setting.key == Keys.ENABLE_HDR && features.brokenHdr) return false
        var dep = setting.enabledWhen
        var depth = 0
        while (dep != null && depth++ < 8) {
            val d = SettingsCatalog.byKey[dep] as? Setting.Toggle ?: return true
            if (!toggle(d)) return false
            dep = d.enabledWhen
        }
        return true
    }

    /** Writes the defaults of the toggles and choices not stored yet, as the old preference screen did on opening */
    fun persistDefaults(groups: List<SettingGroup>) {
        val missing = ArrayList<Setting>()
        for (g in groups) for (s in g.settings) {
            if ((s is Setting.Toggle || s is Setting.Choice) && !snapshot.containsKey(s.key)) missing += s
        }
        if (missing.isEmpty()) return
        store.edit {
            for (s in missing) when (s) {
                is Setting.Toggle -> putBoolean(s.key, toggleDefault(s))
                is Setting.Choice -> putString(s.key, s.default)
                else -> Unit
            }
        }
        for (s in missing) when (s) {
            is Setting.Toggle -> set(s.key, toggleDefault(s))
            is Setting.Choice -> set(s.key, s.default)
            else -> Unit
        }
    }
}

/** The slider's value on a whole step, inside its range */
internal fun Setting.Slider.snap(raw: Float): Int {
    val v = Math.round(raw)
    val rounded = Math.round(v / step.toFloat()) * step
    return min(max, max(min, rounded))
}

/** The slider's value as shown: divided, with its unit */
internal fun Setting.Slider.format(context: Context, value: Int): String {
    val t = if (divisor != 1) String.format(null as Locale?, "%.1f", value / divisor.toFloat()) else value.toString()
    val unit = unit(context)
    return when {
        unit.isNullOrEmpty() -> t
        unit.length > 1 -> "$t $unit"
        else -> t + unit
    }
}

/** Huge ranges (the bitrates) move on a curve, finer at the low end */
private val Setting.Slider.curved: Boolean get() = max - min >= 10000

/** The range of the slider's positions */
internal fun Setting.Slider.positions(): ClosedFloatingPointRange<Float> =
    if (curved) 0f..1f else min.toFloat()..max(min + 1, max).toFloat()

/** The slider's position for [value] */
internal fun Setting.Slider.positionOf(value: Int): Float =
    if (curved) sqrt(((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)) else value.toFloat()

/** The value at slider position [p], on a whole step */
internal fun Setting.Slider.valueAt(p: Float): Int = if (curved) snap(min + (max - min) * p * p) else snap(p)

/** The value as typed: divided, without its unit */
internal fun Setting.Slider.plain(value: Int): String =
    if (divisor == 1) value.toString()
    else BigDecimal(value).divide(BigDecimal(divisor)).stripTrailingZeros().toPlainString()

/** A typed value back: multiplied, inside the range (null: not a number) */
internal fun Setting.Slider.parse(text: String): Int? {
    val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (v.isNaN() || v.isInfinite()) return null
    return Math.round(v * divisor).coerceIn(min.toLong(), max.toLong()).toInt()
}

/** The slider's unit (null: none) */
internal fun Setting.Slider.unit(context: Context): String? =
    if (suffixRes != 0) context.getString(suffixRes) else suffix
