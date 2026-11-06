package app.sloplight.ui.stream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The stream's quick menu (GameMenu's dialogs): a card of list items in a
 * dialog window of its own, so it has the keys and the window focus while
 * it shows (D-pad / gamepad / Tab move between the items, Enter or A picks
 * one, Back / Escape / B cancels), and Game gets them back when it goes.
 * Hosted in whichever activity shows it (the stream, or the controls on
 * the phone when the stream is on an external display); the host view is
 * in that activity only while a menu shows.
 */
class StreamMenu(context: Context) {
    /** A menu entry; action null: it only closes the menu (Cancel) */
    class Item(@JvmField val label: String, @JvmField val action: Runnable?)

    private class Menu(val title: String, val items: List<Item>, val onCancel: Runnable?)
    private class Message(val title: String, val message: String)

    private val activity: Activity = context.findActivity()
    private val main = Handler(Looper.getMainLooper())
    private var menu by mutableStateOf<Menu?>(null)
    private var message by mutableStateOf<Message?>(null)
    private var host: ComposeView? = null

    val isShowing: Boolean get() = menu != null

    /** Shows a menu (in place of the one showing, if any) */
    fun show(title: String, items: List<Item>, onCancel: Runnable?) {
        if (activity.isFinishing) return
        menu = Menu(title, items.toList(), onCancel)
        update()
    }

    /** A message on its own (not "the menu"), dismissed by Back or OK */
    fun showMessage(title: String, message: String) {
        if (activity.isFinishing) return
        this.message = Message(title, message)
        update()
    }

    fun dismiss() {
        menu = null
        update()
    }

    private fun update() {
        val needed = menu != null || message != null
        if (needed && host == null) {
            val v = overlayView(activity) { Content() }
            activity.contentRoot().addView(v, dialogHostParams())
            host = v
        } else if (!needed && host != null) {
            // Taking the host away dismisses the dialog window now
            activity.contentRoot().removeView(host)
            host = null
        }
    }

    private fun pick(m: Menu, item: Item) {
        // After the click is done with: the menu goes, then its action runs
        // (it may wait for the stream to have the focus again, or open another menu)
        main.post {
            if (menu !== m) return@post
            dismiss()
            item.action?.run()
        }
    }

    private fun cancel(m: Menu) {
        main.post {
            if (menu !== m) return@post
            dismiss()
            m.onCancel?.run()
        }
    }

    @Composable
    private fun Content() {
        menu?.let { m -> key(m) { MenuDialog(m) } }
        message?.let { msg ->
            key(msg) {
                AlertDialog(
                    onDismissRequest = { closeMessage(msg) },
                    title = { Text(msg.title) },
                    text = { Text(msg.message) },
                    confirmButton = {
                        TextButton(onClick = { closeMessage(msg) }) { Text(stringResource(android.R.string.ok)) }
                    },
                )
            }
        }
    }

    private fun closeMessage(msg: Message) {
        main.post {
            if (message !== msg) return@post
            message = null
            update()
        }
    }

    @Composable
    private fun MenuDialog(m: Menu) {
        // The entries that do something are the list; the ones that only
        // close the menu (Cancel) are its button
        val actions = remember(m) { m.items.filter { it.action != null } }
        val closers = remember(m) { m.items.filter { it.action == null } }
        val first = remember { FocusRequester() }
        Dialog(
            onDismissRequest = { cancel(m) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 280.dp, max = 440.dp),
            ) {
                Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                    Text(
                        m.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(top = 16.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                    ) {
                        actions.forEachIndexed { i, item ->
                            ListItem(
                                onClick = { pick(m, item) },
                                shapes = ListItemDefaults.segmentedShapes(index = i, count = actions.size),
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                            ) {
                                Text(item.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (closers.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, start = 16.dp, end = 16.dp),
                        ) {
                            closers.forEach { item ->
                                TextButton(onClick = { pick(m, item) }) { Text(item.label) }
                            }
                        }
                    }
                }
            }
            // With a keyboard or a gamepad the first entry has the focus (in
            // touch mode nothing does, and nothing is highlighted)
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("StreamMenu needs an activity")
}
