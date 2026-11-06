package app.sloplight.ui.stream

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import app.sloplight.ui.theme.SloplightTheme

/**
 * A ComposeView for an overlay over the stream: dark theme, and never takes
 * the focus (keys keep going to the stream view and Game; the switcher gets
 * its keys from Game, the dialogs are windows of their own).
 *
 * The overlays are added only while they show and removed when hidden, so
 * nothing of Compose lays out or draws over the video when nothing is shown.
 */
internal fun overlayView(context: Context, keepClear: Boolean = false, content: @Composable () -> Unit): ComposeView =
    ComposeView(context).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (keepClear && Build.VERSION.SDK_INT >= 33) setPreferKeepClear(true)
        setContent { SloplightTheme(darkTheme = true) { content() } }
    }

/** Sets up a ComposeView declared in a layout the same way as [overlayView] */
internal fun ComposeView.asOverlay(content: @Composable () -> Unit) {
    isFocusable = false
    isFocusableInTouchMode = false
    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    setContent { SloplightTheme(darkTheme = true) { content() } }
}

internal fun Activity.contentRoot(): FrameLayout = findViewById(android.R.id.content)

/** A host for windows of their own (dialogs): a view of no size in the activity */
internal fun dialogHostParams() = FrameLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
)
