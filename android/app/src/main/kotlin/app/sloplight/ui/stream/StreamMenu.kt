package app.sloplight.ui.stream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.KeyboardCommandKey
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import app.sloplight.ui.components.cardColor
import com.limelight.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import app.sloplight.ui.components.SegmentGap
import app.sloplight.ui.components.SegmentRow
import app.sloplight.ui.components.segmentShape
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

    /** What an entry is, by its label: its icon, and its place in the quick menu */
    private enum class Role { TILE, LEAVE, QUIT, ROW }
    private class Look(val icon: ImageVector, val role: Role)

    private val looks: Map<String, Look> by lazy {
        fun s(id: Int) = activity.getString(id)
        mapOf(
            s(R.string.game_menu_apps_windows) to Look(Icons.Outlined.Dashboard, Role.TILE),
            s(R.string.game_menu_toggle_keyboard) to Look(Icons.Outlined.Keyboard, Role.TILE),
            s(R.string.game_menu_upload_clipboard) to Look(Icons.Outlined.Upload, Role.TILE),
            s(R.string.game_menu_fetch_clipboard) to Look(Icons.Outlined.ContentPaste, Role.TILE),
            s(R.string.game_menu_enable_zoom_mode) to Look(Icons.Outlined.ZoomIn, Role.TILE),
            s(R.string.game_menu_disable_zoom_mode) to Look(Icons.Outlined.ZoomOut, Role.TILE),
            s(R.string.game_menu_rotate_screen) to Look(Icons.Outlined.ScreenRotation, Role.TILE),
            s(R.string.game_menu_server_cmd) to Look(Icons.Outlined.Terminal, Role.TILE),
            s(R.string.game_menu_advanced) to Look(Icons.Outlined.Tune, Role.TILE),
            s(R.string.game_menu_disconnect) to Look(Icons.Outlined.LinkOff, Role.LEAVE),
            s(R.string.game_menu_quit_session) to Look(Icons.Outlined.PowerSettingsNew, Role.QUIT),
            s(R.string.game_menu_select_mouse_mode) to Look(Icons.Outlined.Mouse, Role.ROW),
            s(R.string.game_menu_toggle_hud) to Look(Icons.Outlined.Speed, Role.ROW),
            s(R.string.game_menu_toggle_floating_button) to Look(Icons.Outlined.RadioButtonChecked, Role.ROW),
            s(R.string.game_menu_toggle_keyboard_model) to Look(Icons.Outlined.KeyboardAlt, Role.ROW),
            s(R.string.game_menu_toggle_virtual_model) to Look(Icons.Outlined.Gamepad, Role.ROW),
            s(R.string.game_menu_toggle_virtual_keyboard_model) to Look(Icons.Outlined.Keyboard, Role.ROW),
            s(R.string.game_menu_task_manager) to Look(Icons.Outlined.Memory, Role.ROW),
            s(R.string.game_menu_send_keys) to Look(Icons.Outlined.KeyboardCommandKey, Role.ROW),
            s(R.string.game_menu_switch_touch_sensitivity_model) to Look(Icons.Outlined.TouchApp, Role.ROW),
        )
    }

    private fun lookOf(item: Item): Look? = looks[item.label]

    @Composable
    private fun MenuDialog(m: Menu) {
        // The entries that do something; the ones that only close the menu (Cancel) are its close button
        val actions = remember(m) { m.items.filter { it.action != null } }
        val closer = remember(m) { m.items.firstOrNull { it.action == null } }
        // The quick menu (its entries have tiles): tiles, then leaving and quitting as buttons
        val tiles = remember(m) { actions.filter { lookOf(it)?.role == Role.TILE } }
        val quick = tiles.size >= 3
        val leave = remember(m) { actions.firstOrNull { lookOf(it)?.role == Role.LEAVE } }
        val quit = remember(m) { actions.firstOrNull { lookOf(it)?.role == Role.QUIT } }
        val rows = if (quick) actions.filter { lookOf(it)?.role == null || lookOf(it)?.role == Role.ROW } else actions
        val first = remember { FocusRequester() }
        val colors = MaterialTheme.colorScheme
        Dialog(
            onDismissRequest = { cancel(m) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = colors.surfaceContainer,
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 300.dp, max = 520.dp),
            ) {
                Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
                    ) {
                        Text(m.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (closer != null) {
                            IconButton(onClick = { pick(m, closer) }) { Icon(Icons.Outlined.Close, closer.label) }
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(top = 8.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        if (quick) {
                            // Three a row, as many rows as it takes
                            tiles.chunked(3).forEachIndexed { r, chunk ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    chunk.forEachIndexed { c, item ->
                                        Tile(
                                            item, lookOf(item)!!.icon,
                                            onClick = { pick(m, item) },
                                            modifier = Modifier.weight(1f).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                                        )
                                    }
                                    repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                        if (rows.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                                rows.forEachIndexed { i, item ->
                                    SegmentRow(
                                        onClick = { pick(m, item) },
                                        shapes = segmentShape(i, rows.size),
                                        leadingContent = { Icon(lookOf(item)?.icon ?: Icons.Outlined.ChevronRight, null) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (!quick && i == 0) Modifier.focusRequester(first) else Modifier),
                                    ) {
                                        Text(item.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        if (quick && (leave != null || quit != null)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                if (leave != null) {
                                    FilledTonalButton(onClick = { pick(m, leave) }, modifier = Modifier.weight(1f)) {
                                        Icon(lookOf(leave)!!.icon, null, Modifier.size(18.dp))
                                        Text(leave.label, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (quit != null) {
                                    Button(
                                        onClick = { pick(m, quit) },
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(lookOf(quit)!!.icon, null, Modifier.size(18.dp))
                                        Text(quit.label, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
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

    /** A quick-menu action: its icon over its name, on a card */
    @Composable
    private fun Tile(item: Item, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = cardColor(), modifier = modifier.heightIn(min = 84.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("StreamMenu needs an activity")
}
