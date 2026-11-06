package app.sloplight.ui.stream

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The transient notice over the stream ("slow connection"...), top end.
 * Same calls as the TextView it replaces: setText, setVisibility. Its
 * ComposeView is in the container only while it is visible.
 */
class StreamNotice(private val container: ViewGroup) {
    private var text by mutableStateOf("")
    private var view: ComposeView? = null

    fun setText(text: CharSequence) {
        this.text = text.toString()
    }

    fun setVisibility(visibility: Int) {
        if (visibility == View.VISIBLE) {
            if (view == null) {
                val v = overlayView(container.context, keepClear = true) {
                    // (a Box, not a Surface: touches go through to the stream, as before)
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                                MaterialTheme.shapes.large,
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                container.addView(v, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                view = v
            }
            container.visibility = View.VISIBLE
        } else {
            view?.let { container.removeView(it) }
            view = null
            container.visibility = View.GONE
        }
    }
}

/**
 * A round floating button over the stream (the menu button, the zoom
 * toggle). Only its look is Compose: it takes no touches itself, so the
 * view's own touch listener (Game's drag-or-tap) gets them all.
 */
class FloatingStreamButton private constructor(@DrawableRes private val icon: Int, private val description: String?) {
    private var on by mutableStateOf(false)

    /** On: in the primary colors (zoom mode on) */
    fun setActive(active: Boolean) {
        on = active
    }

    companion object {
        @JvmStatic
        fun attach(view: ComposeView, @DrawableRes icon: Int, description: String?): FloatingStreamButton {
            val button = FloatingStreamButton(icon, description)
            view.contentDescription = description
            view.asOverlay { button.Content() }
            return button
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        // No Surface or clickable here: nothing in Compose may take the touches
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color(0xCC000000), CircleShape)
                .border(1.5.dp, if (on) MaterialTheme.colorScheme.primary else Color(0xAAFFFFFF), CircleShape)
                .semantics {
                    role = Role.Button
                    description?.let { contentDescription = it }
                },
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
