package app.sloplight.ui.stream

import android.content.res.Resources
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.video.PerfOverlayListener

/**
 * The stream's numbers for the last second, as the decoder measured them;
 * formatted for the screen by the overlay, on the main thread.
 */
data class PerfStats(
    val width: Int,
    val height: Int,
    val totalFps: Float,
    val receivedFps: Float,
    val renderedFps: Float,
    val decoder: String,
    /** Frames lost, in percent */
    val lossPercent: Float,
    /** The app's network traffic in KiB/s; negative: unknown (yet) */
    val bandwidthKBps: Float,
    val rttMs: Int,
    val rttVarianceMs: Int,
    /** Host processing latency (ms); NaN: none reported */
    val hostLatencyMin: Float,
    val hostLatencyMax: Float,
    val hostLatencyAvg: Float,
    val decodeMs: Float,
    val stereoActive: Boolean,
    val stereoFps: Float,
    val stereoRenderer: String?,
    val stereoDrawDelay: Float,
)

/** A perf listener that takes the numbers rather than the decoder's text */
interface PerfStatsListener : PerfOverlayListener {
    fun onPerfStats(stats: PerfStats)
}

/**
 * The performance overlay: full (several lines, top left) or lite (one line,
 * top center), at the top or the bottom (the container's gravity). Its
 * ComposeView is in the container only while the overlay is shown; a new
 * snapshot once a second recomposes the one Text.
 */
class PerfOverlay(
    private val container: ViewGroup,
    private val lite: Boolean,
    /** Lite mode's "tap for the quick menu"; null: not clickable */
    private val onClick: Runnable?,
) {
    private var stats by mutableStateOf<PerfStats?>(null)
    private var view: ComposeView? = null

    /** Main thread */
    fun update(stats: PerfStats) {
        this.stats = stats
    }

    fun setShown(shown: Boolean) {
        if (shown) {
            if (view == null) {
                val v = overlayView(container.context, keepClear = true) {
                    PerfOverlayContent(stats, lite, onClick)
                }
                container.addView(v, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (lite) Gravity.CENTER_HORIZONTAL else Gravity.START,
                ))
                view = v
            }
            container.visibility = android.view.View.VISIBLE
        } else {
            view?.let { container.removeView(it) }
            view = null
            container.visibility = android.view.View.GONE
        }
    }

    val isShown: Boolean get() = view != null
}

private val NumbersStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun PerfOverlayContent(stats: PerfStats?, lite: Boolean, onClick: Runnable?) {
    // Nothing until the first second's numbers
    if (stats == null) return
    val res = LocalResources.current
    val text = remember(stats, lite) { if (lite) liteText(res, stats) else fullText(res, stats) }
    if (lite) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            style = NumbersStyle,
            modifier = Modifier
                .padding(top = 4.dp)
                .background(Color(0x59000000), RoundedCornerShape(8.dp))
                .then(if (onClick != null) Modifier.clickable { onClick.run() } else Modifier)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    } else {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            style = NumbersStyle,
            modifier = Modifier
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp)
                .background(Color(0x80000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun bandwidth(kBps: Float): String =
    if (kBps >= 1000) String.format("%.2fM/s", kBps / 1024f) else String.format("%.2fK/s", kBps)

private fun stereo(res: Resources, s: PerfStats): String =
    " " + res.getString(R.string.perf_overlay_ai_fps) + " " + s.stereoFps +
        " " + res.getString(R.string.perf_overlay_ai_delegate) + " " + s.stereoRenderer +
        " " + res.getString(R.string.perf_overlay_drawdelay, s.stereoDrawDelay)

private fun liteText(res: Resources, s: PerfStats): String = buildString {
    if (s.bandwidthKBps >= 0) {
        append(res.getString(R.string.perf_overlay_lite_bandwidth)).append(": ")
        append(bandwidth(s.bandwidthKBps)).append("   ")
    }
    append(res.getString(R.string.perf_overlay_lite_network_decoding_delay)).append(": ")
    append(res.getString(R.string.perf_overlay_lite_net, s.rttMs))
    append(" / ")
    append(res.getString(R.string.perf_overlay_lite_dectime, s.decodeMs))
    append("   ")
    append(res.getString(R.string.perf_overlay_lite_packet_loss)).append(": ")
    append(res.getString(R.string.perf_overlay_lite_netdrops, s.lossPercent))
    append("   FPS: ")
    append(res.getString(R.string.perf_overlay_lite_fps, s.totalFps))
    if (s.stereoActive) append(stereo(res, s))
}

private fun fullText(res: Resources, s: PerfStats): String = buildString {
    append(res.getString(R.string.perf_overlay_streamdetails, "${s.width}x${s.height}", s.totalFps))
    if (s.stereoActive) append('\n').append(stereo(res, s))
    append('\n')
    append(res.getString(R.string.perf_overlay_decoder, s.decoder)).append('\n')
    append(res.getString(R.string.perf_overlay_incomingfps, s.receivedFps)).append('\n')
    append(res.getString(R.string.perf_overlay_renderingfps, s.renderedFps)).append('\n')
    append(res.getString(R.string.perf_overlay_netdrops, s.lossPercent)).append('\n')
    if (s.bandwidthKBps >= 0) {
        append(res.getString(R.string.perf_overlay_lite_bandwidth)).append(": ")
        append(bandwidth(s.bandwidthKBps)).append('\n')
    }
    append(res.getString(R.string.perf_overlay_netlatency, s.rttMs, s.rttVarianceMs)).append('\n')
    if (!s.hostLatencyAvg.isNaN()) {
        append(res.getString(R.string.perf_overlay_hostprocessinglatency, s.hostLatencyMin, s.hostLatencyMax, s.hostLatencyAvg)).append('\n')
    }
    append(res.getString(R.string.perf_overlay_dectime, s.decodeMs))
}
