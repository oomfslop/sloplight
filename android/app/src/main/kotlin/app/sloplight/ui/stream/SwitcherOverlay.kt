package app.sloplight.ui.stream

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.limelight.KeyboardAccessibilityService
import com.limelight.R
import com.limelight.desk.Workspaces
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * The launcher / window switcher over the stream: workspaces, the host's
 * other windows and its apps, filtered as you type. Keys come from the
 * stream activity (it sees every key first and hands them to [onKey] while
 * this shows), touches land here directly. Also shows the little workspace
 * badge (the HUD) after a switch.
 *
 * Two ComposeViews, each in the activity only while it shows: the panel
 * (the whole screen, the stream dimmed behind a card) and the HUD (its own
 * size, top center). They never take the focus.
 */
class SwitcherOverlay(private val activity: Activity, private val ws: Workspaces, private val onHidden: Runnable) {
    private class Entry(
        val kind: Int,
        val slot: Int,
        val title: String,
        val detail: String?,
        val window: Workspaces.Window?,
        val app: Workspaces.App?,
        val icon: Bitmap? = null,
        /** The workspace in front (a filled badge) */
        val front: Boolean = false,
        /** Long press offers "own workspace / beside" */
        val canBeside: Boolean = false,
    ) {
        val selectable: Boolean get() = kind != HEADER && kind != NOTE

        companion object {
            const val WORKSPACE = 0
            const val EMPTY = 1
            const val WINDOW = 2
            const val APP = 3
            const val HEADER = 4
            const val NOTE = 5
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var panel: ComposeView? = null
    private var hud: ComposeView? = null

    private var query by mutableStateOf("")
    /** Opened for an empty workspace: what is picked goes there */
    private var targetSlot by mutableIntStateOf(0)
    private var selected by mutableIntStateOf(-1)
    private var entries by mutableStateOf<List<Entry>>(emptyList())
    private var metaMode by mutableStateOf(false)
    /** The row whose long-press menu is open (-1: none) */
    private var menuFor by mutableIntStateOf(-1)

    private var hudText by mutableStateOf("")
    private var hudGen by mutableIntStateOf(0)

    fun isShowing(): Boolean = panel != null

    /** Opens the switcher; slot > 0: for that empty workspace */
    fun show(slot: Int) {
        targetSlot = slot
        query = ""
        selected = -1
        menuFor = -1
        metaMode = KeyboardAccessibilityService.running
        ws.refreshHostWindows()
        if (ws.apps().isEmpty()) ws.refreshApps()
        rebuild()
        if (panel == null) {
            val v = overlayView(activity) { Panel() }
            activity.contentRoot().addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            panel = v
        }
    }

    fun hide() {
        val p = panel ?: return
        panel = null
        menuFor = -1
        // Not showing from now on (keys go back to the stream); the view goes
        // once the event that closed it (often a tap on one of its rows) is
        // done; gone at once, so it neither draws nor takes another touch
        p.visibility = android.view.View.GONE
        main.post { activity.contentRoot().removeView(p) }
        onHidden.run()
    }

    /** Workspaces changed: the list follows */
    fun refresh() {
        if (isShowing()) rebuild()
    }

    fun showHud(text: String) {
        hudText = text
        hudGen++
        if (hud == null) {
            val v = overlayView(activity) { Hud() }
            activity.contentRoot().addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ))
            hud = v
        }
    }

    private fun hudDone(gen: Int) {
        main.post {
            if (gen != hudGen) return@post
            hud?.let { activity.contentRoot().removeView(it) }
            hud = null
        }
    }

    /** A key while the switcher is open; true when it was used */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                hide()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { move(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(1); return true }
            KeyEvent.KEYCODE_TAB -> { move(if (event.isShiftPressed) -1 else 1); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { move(-8); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { move(8); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                activate(selected, event.isShiftPressed)
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (query.isNotEmpty()) {
                    query = if (event.isCtrlPressed || event.isAltPressed) "" else dropLastCodePoint(query)
                    selected = -1
                    rebuild()
                }
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                val e = entries.getOrNull(selected)
                if (e != null && e.kind == Entry.WORKSPACE) {
                    if (event.isShiftPressed) ws.close(e.slot) else ws.release(e.slot)
                }
                return true
            }
        }
        // Digits with nothing typed: that workspace
        if (query.isEmpty() && !event.isShiftPressed && event.keyCode >= KeyEvent.KEYCODE_1 && event.keyCode <= KeyEvent.KEYCODE_9) {
            val n = event.keyCode - KeyEvent.KEYCODE_0
            if (!ws.isEmpty(n)) {
                ws.switchTo(n)
                hide()
            } else {
                targetSlot = n
                rebuild()
            }
            return true
        }
        val ch = event.unicodeChar
        if (ch >= 0x20 && !event.isCtrlPressed && !event.isMetaPressed) {
            query += String(Character.toChars(ch))
            selected = -1
            rebuild()
        }
        return true
    }

    private fun dropLastCodePoint(s: String): String {
        val end = s.offsetByCodePoints(s.length, -1)
        return s.substring(0, end)
    }

    private fun move(by: Int) {
        val list = entries
        if (list.isEmpty()) return
        var i = selected
        val step = if (by > 0) 1 else -1
        var left = Math.abs(by)
        while (left > 0) {
            var next = i + step
            while (next >= 0 && next < list.size && !list[next].selectable) next += step
            if (next < 0 || next >= list.size) break
            i = next
            left--
        }
        selected = i
    }

    /** beside: a window or app opens next to the windows in front (or in the
     *  workspace picked) instead of in a workspace of its own */
    private fun activate(i: Int, beside: Boolean = false) {
        val e = entries.getOrNull(i) ?: return
        when (e.kind) {
            Entry.WORKSPACE -> {
                ws.switchTo(e.slot)
                hide()
            }
            Entry.EMPTY -> {
                targetSlot = e.slot
                query = ""
                selected = -1
                rebuild()
            }
            Entry.WINDOW -> {
                ws.bring(e.window!!.hwnd, targetSlot, beside)
                hide()
            }
            Entry.APP -> {
                ws.launch(e.app!!, targetSlot, beside)
                hide()
            }
        }
    }

    private fun rebuild() {
        val q = query.lowercase(Locale.ROOT).trim()
        val keep = entries.getOrNull(selected)
        val out = ArrayList<Entry>()

        // Workspaces
        val group = ArrayList<Entry>()
        val current = ws.current()
        for (n in 1..Workspaces.COUNT) {
            val w = ws.windowIn(n)
            if (w != null) {
                // Side by side ones: all their names, left to right; the focused one's icon
                val tiles = ws.windowsIn(n)
                val title = StringBuilder()
                var match = false
                for (t in tiles) {
                    if (title.isNotEmpty()) title.append("  │  ")
                    title.append(t.label())
                    match = match || matches(q, t.label(), t.process)
                }
                val detail = if (tiles.size > 1) activity.getString(R.string.switcher_side_by_side, tiles.size) else processName(w.process)
                if (match) {
                    group.add(Entry(Entry.WORKSPACE, n, title.toString(), detail, w, null,
                        icon = ws.icon(w.icon, w.hwnd), front = n == current))
                }
            } else if (q.isEmpty() && n == firstEmptyToOffer()) {
                group.add(Entry(Entry.EMPTY, n, activity.getString(R.string.switcher_empty),
                    activity.getString(R.string.switcher_empty_detail), null, null))
            }
        }
        if (group.isNotEmpty() && targetSlot == 0) {
            out.add(Entry(Entry.HEADER, 0, activity.getString(R.string.switcher_workspaces), null, null, null))
            out.addAll(group)
        }

        val beside = ws.canTileBeside()

        // The host's other windows
        group.clear()
        for (w in ws.otherWindows()) {
            if (matches(q, w.label(), w.process)) {
                group.add(Entry(Entry.WINDOW, 0, w.label(), processName(w.process), w, null,
                    icon = ws.icon(w.icon, w.hwnd), canBeside = beside))
            }
        }
        if (group.isNotEmpty()) {
            out.add(Entry(Entry.HEADER, 0, activity.getString(R.string.switcher_on_pc), null, null, null))
            out.addAll(group)
        }

        // Apps
        group.clear()
        val apps = ws.apps()
        val limit = if (q.isEmpty()) APPS_UNFILTERED else MATCHES
        for (a in apps) {
            if (group.size >= limit) break
            if (matches(q, a.name, null)) {
                group.add(Entry(Entry.APP, 0, a.name, "", null, a,
                    icon = if (a.icon.isNotEmpty()) ws.icon(a.icon, null) else null, canBeside = beside))
            }
        }
        if (group.isNotEmpty()) {
            out.add(Entry(Entry.HEADER, 0, activity.getString(R.string.switcher_apps), null, null, null))
            out.addAll(group)
            if (q.isEmpty() && apps.size > limit) {
                out.add(Entry(Entry.NOTE, 0, activity.getString(R.string.switcher_more_apps, apps.size), null, null, null))
            }
        } else if (ws.appsLoading()) {
            out.add(Entry(Entry.NOTE, 0, activity.getString(R.string.switcher_loading_apps), null, null, null))
        } else if (q.isNotEmpty() && out.isEmpty()) {
            out.add(Entry(Entry.NOTE, 0, activity.getString(R.string.switcher_no_match, query), null, null, null))
        }

        // The selection stays on the same thing, or goes to the first one
        var sel = -1
        if (keep != null) {
            sel = out.indexOfFirst { it.kind == keep.kind && it.slot == keep.slot && it.title == keep.title }
        }
        if (sel < 0) {
            val other = hasOtherWorkspace()
            // Nothing typed: the workspace that isn't in front is the likely pick
            sel = out.indexOfFirst { it.selectable && !(q.isEmpty() && it.kind == Entry.WORKSPACE && it.slot == current && other) }
        }
        if (menuFor >= out.size) menuFor = -1
        entries = out
        selected = sel
    }

    private fun hasOtherWorkspace(): Boolean {
        val current = ws.current()
        for (n in 1..Workspaces.COUNT) {
            if (n != current && !ws.isEmpty(n)) return true
        }
        return false
    }

    private fun firstEmptyToOffer(): Int = if (targetSlot > 0) targetSlot else ws.firstFree()

    private fun openAccessibilitySettings() {
        if (!KeyboardAccessibilityService.running) {
            try {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    // --- The UI ---

    @Composable
    private fun Panel() {
        val scheme = MaterialTheme.colorScheme
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000))
                // A tap on the dimmed stream closes it
                .pointerInput(Unit) { detectTapGestures { hide() } },
        ) {
            val cardWidth = min(max(maxWidth - 48.dp, 280.dp), 720.dp)
            // The card stays inside the screen: at most 85% of its height
            val cardHeight = maxHeight * 0.85f
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = scheme.surfaceContainerHigh,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .width(cardWidth)
                    // (always that tall, so it doesn't jump as the list is filtered)
                    .height(cardHeight)
                    // Taps on the card don't close it
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    SearchField()
                    Spacer(Modifier.size(8.dp))
                    EntryList(Modifier.weight(1f))
                    Footer()
                }
            }
        }
    }

    @Composable
    private fun SearchField() {
        val scheme = MaterialTheme.colorScheme
        val empty = query.trim().isEmpty()
        val text = when {
            !empty -> query
            targetSlot > 0 -> stringResource(R.string.switcher_search_hint_slot, targetSlot)
            else -> stringResource(R.string.switcher_search_hint)
        }
        Text(
            text = text,
            color = if (empty) scheme.onSurfaceVariant else scheme.onSurface,
            fontSize = 18.sp,
            maxLines = 1,
            overflow = if (empty) TextOverflow.Ellipsis else TextOverflow.StartEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHighest, RoundedCornerShape(28.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }

    @Composable
    private fun EntryList(modifier: Modifier) {
        val list = entries
        val sel = selected
        val state = rememberLazyListState()
        val pad = with(LocalDensity.current) { 8.dp.roundToPx() }
        // The selected row stays in view
        LaunchedEffect(sel, list) {
            if (sel < 0 || sel >= list.size) return@LaunchedEffect
            // (once the list is laid out with these entries)
            snapshotFlow { state.layoutInfo.totalItemsCount }.first { it == list.size }
            val info = state.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@LaunchedEffect
            val item = info.visibleItemsInfo.firstOrNull { it.index == sel }
            if (item == null) {
                state.animateScrollToItem(sel)
            } else {
                val top = item.offset - pad
                val bottom = item.offset + item.size + pad
                if (top < info.viewportStartOffset) {
                    state.animateScrollBy((top - info.viewportStartOffset).toFloat())
                } else if (bottom > info.viewportEndOffset) {
                    state.animateScrollBy((bottom - info.viewportEndOffset).toFloat())
                }
            }
        }
        LazyColumn(state = state, modifier = modifier.fillMaxWidth()) {
            itemsIndexed(list) { i, e -> EntryRow(e, i, i == sel) }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun EntryRow(e: Entry, index: Int, isSelected: Boolean) {
        val scheme = MaterialTheme.colorScheme
        if (e.kind == Entry.HEADER || e.kind == Entry.NOTE) {
            // Headings in the primary color, notes dim
            Text(
                text = e.title,
                fontSize = 13.sp,
                color = if (e.kind == Entry.HEADER) scheme.primary else scheme.onSurfaceVariant,
                fontWeight = if (e.kind == Entry.HEADER) FontWeight.Bold else null,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (e.kind == Entry.HEADER) 12.dp else 6.dp, bottom = 6.dp),
            )
            return
        }
        val longPress = e.kind == Entry.WORKSPACE || ((e.kind == Entry.WINDOW || e.kind == Entry.APP) && e.canBeside)
        val shape = RoundedCornerShape(28.dp)
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(if (isSelected) scheme.secondaryContainer else Color.Transparent, shape)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(),
                        onLongClick = if (longPress) ({ menuFor = index }) else null,
                        onClick = { activate(index) },
                    )
                    .padding(start = 12.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            ) {
                Badge(e)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(28.dp)) {
                    val bmp = e.icon
                    if (bmp != null) {
                        val image = remember(bmp) { bmp.asImageBitmap() }
                        Image(image, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = e.title,
                    fontSize = 15.sp,
                    color = if (e.kind == Entry.EMPTY) scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!e.detail.isNullOrEmpty()) {
                    Text(
                        text = e.detail,
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            if (longPress) {
                DropdownMenu(expanded = menuFor == index, onDismissRequest = { menuFor = -1 }) {
                    if (e.kind == Entry.WORKSPACE) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.switcher_send_back)) },
                            onClick = { menuFor = -1; ws.release(e.slot) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.switcher_close_window)) },
                            onClick = { menuFor = -1; ws.close(e.slot) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.switcher_open_own)) },
                            onClick = { menuFor = -1; activate(index, false) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.switcher_open_beside)) },
                            onClick = { menuFor = -1; activate(index, true) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Badge(e: Entry) {
        val scheme = MaterialTheme.colorScheme
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp).let {
            when {
                e.kind != Entry.WORKSPACE && e.kind != Entry.EMPTY -> it
                // The workspace in front: a filled primary circle; the others outlined
                e.front -> it.background(scheme.primary, CircleShape)
                else -> it.border(1.5.dp, scheme.outlineVariant, CircleShape)
            }
        }) {
            if (e.kind == Entry.WORKSPACE || e.kind == Entry.EMPTY) {
                Text(
                    text = e.slot.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (e.front) scheme.onPrimary else scheme.onSurface,
                )
            }
        }
    }

    @Composable
    private fun Footer() {
        val m = if (metaMode) "Win" else "Alt"
        var text = stringResource(R.string.switcher_footer, m)
        if (!metaMode) {
            text += "\n" + stringResource(R.string.switcher_footer_service, stringResource(R.string.keyboard_service_label))
        }
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = null, indication = null) { openAccessibilitySettings() }
                .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 2.dp),
        )
    }

    @Composable
    private fun Hud() {
        val scheme = MaterialTheme.colorScheme
        val alpha = remember { Animatable(1f) }
        val gen = hudGen
        // Shown for a moment, then it fades and goes
        LaunchedEffect(gen) {
            alpha.snapTo(1f)
            delay(1100)
            alpha.animateTo(0f, tween(250))
            hudDone(gen)
        }
        // (no Surface: touches go through to the stream)
        Box(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp).graphicsLayer { this.alpha = alpha.value }) {
            Text(
                text = hudText,
                color = scheme.inverseOnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .graphicsLayer {
                        shadowElevation = 6.dp.toPx()
                        shape = CircleShape
                        clip = false
                    }
                    .background(scheme.inverseSurface, CircleShape)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }

    private companion object {
        const val APPS_UNFILTERED = 12
        const val MATCHES = 60

        fun matches(q: String, a: String?, b: String?): Boolean {
            if (q.isEmpty()) return true
            return (a != null && a.lowercase(Locale.ROOT).contains(q)) || (b != null && b.lowercase(Locale.ROOT).contains(q))
        }

        fun processName(p: String?): String {
            if (p == null) return ""
            return if (p.lowercase(Locale.ROOT).endsWith(".exe")) p.substring(0, p.length - 4) else p
        }
    }
}

