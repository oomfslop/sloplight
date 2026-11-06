package app.sloplight.ui.settings

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.Immutable
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the device has, for the settings that need it */
@Immutable
data class DeviceFeatures(
    val features: Set<Feature>,
    /** Old SHIELD TV firmware that crashes with HDR: the toggle is off and disabled */
    val brokenHdr: Boolean,
) {
    operator fun contains(feature: Feature?) = feature == null || feature in features

    companion object {
        @Suppress("DEPRECATION")
        fun detect(context: Context): DeviceFeatures {
            val pm = context.packageManager
            val f = HashSet<Feature>()
            if (pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) f += Feature.TOUCHSCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.hasSystemFeature("com.nvidia.feature.shield")) {
                f += Feature.ABSOLUTE_MOUSE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) f += Feature.CONTROLLER_SENSORS
            if (pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER) ||
                pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
            ) f += Feature.DEVICE_MOTION
            if (pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) f += Feature.USB_HOST
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                pm.hasSystemFeature("android.software.picture_in_picture") &&
                !pm.hasSystemFeature("com.amazon.software.fireos")
            ) f += Feature.PIP

            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                f += Feature.VIBRATOR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()) {
                    f += Feature.VIBRATOR_AMPLITUDE
                }
            }

            var brokenHdr = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val display = defaultDisplay(context)
                val hdr10 = display?.hdrCapabilities?.supportedHdrTypes
                    ?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
                if (hdr10) {
                    f += Feature.HDR10
                    if (PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()) {
                        LimeLog.info("Disabling HDR toggle on old broken SHIELD TV firmware")
                        brokenHdr = true
                    }
                } else {
                    LimeLog.info("Excluding HDR toggle based on display capabilities")
                }
            } else {
                LimeLog.info("Excluding HDR toggle based on OS")
            }
            return DeviceFeatures(f, brokenHdr)
        }
    }
}

@Suppress("DEPRECATION")
internal fun defaultDisplay(context: Context): Display? =
    (context as? Activity)?.windowManager?.defaultDisplay
        ?: (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)

/** A native resolution of the display; [fullScreen]: with the cutout insets included */
@Immutable
data class NativeResolution(val width: Int, val height: Int, val fullScreen: Boolean)

/** The display's resolutions and refresh rate (cheap; read on the main thread) */
@Immutable
data class DisplayInfo(
    val natives: List<NativeResolution>,
    /** The largest resolution slot the display's modes reach (0: unknown) */
    val modesMaxWidth: Int,
    val maxFps: Float,
    /** Below Marshmallow there are no display modes and no decoder checks */
    val legacy: Boolean,
) {
    companion object {
        @Suppress("DEPRECATION")
        fun read(activity: Activity): DisplayInfo {
            val display = activity.windowManager.defaultDisplay
            var maxFps = display.refreshRate
            val natives = ArrayList<NativeResolution>()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                // The true metrics (getWidth()/getHeight() leave out the system bars)
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                natives += NativeResolution(
                    max(metrics.widthPixels, metrics.heightPixels),
                    min(metrics.widthPixels, metrics.heightPixels),
                    false,
                )
                return DisplayInfo(natives, 0, maxFps, legacy = true)
            }

            var maxWidth = 0
            // A native resolution without the cutout, for those who don't want
            // the picture behind the notch
            var hasInsets = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    display.cutout
                } else {
                    // Android 9 has no Display.getCutout()
                    activity.window?.decorView?.rootWindowInsets?.displayCutout
                }
                if (cutout != null) {
                    val widthInsets = cutout.safeInsetLeft + cutout.safeInsetRight
                    val heightInsets = cutout.safeInsetBottom + cutout.safeInsetTop
                    if (widthInsets != 0 || heightInsets != 0) {
                        val metrics = DisplayMetrics()
                        display.getRealMetrics(metrics)
                        val w = max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets)
                        val h = min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets)
                        natives += NativeResolution(w, h, false)
                        hasInsets = true
                    }
                }
            }

            val tv = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
            for (mode in display.supportedModes) {
                // Some devices report portrait modes: normalize to width > height
                val w = max(mode.physicalWidth, mode.physicalHeight)
                val h = min(mode.physicalWidth, mode.physicalHeight)
                // TVs report odd values here: only above-4K native modes there
                if (!tv || w > 3840 || h > 2160) natives += NativeResolution(w, h, hasInsets)

                if ((w >= 3840 || h >= 2160) && maxWidth < 3840) maxWidth = 3840
                else if ((w >= 2560 || h >= 1440) && maxWidth < 2560) maxWidth = 2560
                else if ((w >= 1920 || h >= 1080) && maxWidth < 1920) maxWidth = 1920

                if (mode.refreshRate > maxFps) maxFps = mode.refreshRate
            }
            return DisplayInfo(natives, maxWidth, maxFps, legacy = false)
        }

        /** The physical pixel count of the display, to tell when it is another one (foldables) */
        @Suppress("DEPRECATION")
        fun pixelCount(activity: Activity): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0
            val mode = activity.windowManager.defaultDisplay.mode
            return mode.physicalWidth * mode.physicalHeight
        }
    }
}

/**
 * The largest resolution slot the decoders take (0: they don't say). Slow:
 * it walks the codec list, so call it off the main thread.
 */
internal fun decoderMaxWidth(context: Context): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return 0
    // Must run before the decoder lists are read
    MediaCodecHelper.initialize(context, GlPreferences.readPreferences(context).glRenderer)
    var maxWidth = 0
    for (mime in arrayOf("video/avc", "video/hevc")) {
        val decoder = MediaCodecHelper.findProbableSafeDecoder(mime, -1) ?: continue
        val range = try {
            decoder.getCapabilitiesForType(mime).videoCapabilities?.supportedWidths ?: continue
        } catch (e: Exception) {
            continue
        }
        LimeLog.info("${if (mime == "video/avc") "AVC" else "HEVC"} supported width range: ${range.lower} - ${range.upper}")
        // If 720p isn't reported as supported, the API is not to be trusted
        if (range.contains(1280)) {
            maxWidth = max(
                maxWidth,
                when {
                    range.contains(3840) -> 3840
                    range.contains(1920) -> 1920
                    else -> 1280
                },
            )
        }
    }
    return maxWidth
}

/** One entry of a choice list */
@Immutable
data class ChoiceEntry(val value: String, val name: String)

/**
 * A choice list built for this device: the entries, the ones that can't be
 * picked here (and what replaces them when chosen), and the entries added
 * from the display.
 */
@Immutable
data class ChoiceList(
    val entries: List<ChoiceEntry>,
    /** (removed value, its replacement), in order */
    val removed: List<Pair<String, String>> = emptyList(),
    /** Values of the entries in the XML array */
    val staticValues: Set<String> = emptySet(),
    /** The display's own frame rate when it was added to the list */
    val nativeFps: String? = null,
) {
    fun nameOf(value: String): String? = entries.firstOrNull { it.value == value }?.name
}

internal fun staticChoices(context: Context, namesRes: Int, valuesRes: Int): ChoiceList {
    val names = context.resources.getStringArray(namesRes)
    val values = context.resources.getStringArray(valuesRes)
    val entries = values.indices.map { ChoiceEntry(values[it], names.getOrElse(it) { values[it] }) }
    return ChoiceList(entries, staticValues = values.toSet())
}

/** The resolution list: the XML's, then custom and native ones, minus what the device can't decode */
internal fun resolutionChoices(
    context: Context,
    display: DisplayInfo?,
    decoderMaxWidth: Int?,
    customResolution: String?,
): ChoiceList {
    val base = staticChoices(context, R.array.resolution_names, R.array.resolution_values)
    val entries = base.entries.toMutableList()
    val res = context.resources

    fun add(width: Int, height: Int, fullScreen: Boolean, portrait: Boolean, custom: Boolean) {
        var name = when {
            fullScreen -> res.getString(R.string.resolution_prefix_native_fullscreen)
            custom -> res.getString(R.string.resolution_prefix_custom)
            else -> res.getString(R.string.resolution_prefix_native)
        }
        if (PreferenceConfiguration.isSquarishScreen(width, height)) {
            name += " " + res.getString(
                if (portrait) R.string.resolution_prefix_native_portrait else R.string.resolution_prefix_native_landscape,
            )
        }
        name += " (${width}x$height)"
        val value = "${width}x$height"
        if (entries.none { it.value == value }) entries += ChoiceEntry(value, name)
    }

    fun addBoth(width: Int, height: Int, fullScreen: Boolean, custom: Boolean) {
        if (PreferenceConfiguration.isSquarishScreen(width, height)) add(height, width, fullScreen, true, custom)
        add(width, height, fullScreen, false, custom)
    }

    if (!customResolution.isNullOrEmpty()) {
        val parts = customResolution.split("x")
        if (parts.size == 2) {
            val w = parts[0].toIntOrNull()
            val h = parts[1].toIntOrNull()
            if (w != null && h != null) addBoth(w, h, fullScreen = false, custom = true)
        }
    }

    val removed = ArrayList<Pair<String, String>>()
    if (display != null) {
        for (n in display.natives) addBoth(n.width, n.height, n.fullScreen, custom = false)
        if (!display.legacy && decoderMaxWidth != null) {
            val maxWidth = max(display.modesMaxWidth, decoderMaxWidth)
            LimeLog.info("Maximum resolution slot: $maxWidth")
            if (maxWidth != 0) {
                if (maxWidth < 3840) removed += PreferenceConfiguration.RES_4K to PreferenceConfiguration.RES_1440P
                if (maxWidth < 2560) removed += PreferenceConfiguration.RES_1440P to PreferenceConfiguration.RES_1080P
                if (maxWidth < 1920) removed += PreferenceConfiguration.RES_1080P to PreferenceConfiguration.RES_720P
                // 720p is never removed
            }
        }
    }
    entries.removeAll { e -> removed.any { it.first.equals(e.value, ignoreCase = true) } }
    return ChoiceList(entries, removed, base.staticValues)
}

/** The frame rate list: the XML's, the custom one, minus what the display can't show, then the display's own */
internal fun fpsChoices(
    context: Context,
    display: DisplayInfo?,
    unlockFps: Boolean,
    customRefreshRate: String?,
): ChoiceList {
    val base = staticChoices(context, R.array.fps_names, R.array.fps_values)
    val entries = base.entries.toMutableList()
    val res = context.resources
    val fpsSuffix = res.getString(R.string.fps_suffix_fps)

    if (!customRefreshRate.isNullOrEmpty()) {
        val rate = customRefreshRate.toFloatOrNull()
        if (rate != null && rate > 0) {
            val value = rate.toString()
            if (entries.none { it.value == value }) {
                entries += ChoiceEntry(value, res.getString(R.string.resolution_prefix_custom) + " ($value $fpsSuffix)")
            }
        }
    }

    val removed = ArrayList<Pair<String, String>>()
    var nativeFps: String? = null
    if (display != null) {
        if (!unlockFps) {
            // Some room in case the rate is rounded down; 30 and 60 always stay
            if (display.maxFps < 118) removed += "120" to "90"
            if (display.maxFps < 88) removed += "90" to "60"
            entries.removeAll { e -> removed.any { it.first.equals(e.value, ignoreCase = true) } }
        }
        val rounded = display.maxFps.roundToInt()
        if (rounded != 0) {
            val value = rounded.toString()
            if (entries.none { it.value == value }) {
                entries += ChoiceEntry(value, res.getString(R.string.resolution_prefix_native) + " ($value $fpsSuffix)")
                nativeFps = value
            }
        }
    }
    return ChoiceList(entries, removed, base.staticValues, nativeFps)
}
