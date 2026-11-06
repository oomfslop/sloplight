package app.sloplight.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sloplight.data.prefs.PrefsStore
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A setting as listed, its texts resolved once */
@Immutable
internal class SettingItem(val setting: Setting, val title: String, val summary: String?)

/** A visible category and its visible settings */
@Immutable
internal class GroupItem(val key: String, val title: String, val items: List<SettingItem>, val initiallyShown: Int?)

/** A line of the list */
@Immutable
internal sealed interface Entry {
    val key: String

    class Header(val group: GroupItem, val first: Boolean) : Entry {
        override val key get() = "h:" + group.key
    }

    class Row(val item: SettingItem, val index: Int, val count: Int) : Entry {
        override val key get() = item.setting.key
    }

    class More(val group: GroupItem, val index: Int, val count: Int, val hidden: Int, val summary: String) : Entry {
        override val key get() = "m:" + group.key
    }

    data object NoResults : Entry {
        override val key get() = "none"
    }
}

/**
 * The settings list over [store], shared by the settings screen and the
 * profile editor. With [profileBase] (the app's own prefs) it is a profile
 * being edited: settings that differ from the app's get a "*", and the rows
 * that make no sense in a profile are left out.
 */
@Composable
internal fun SettingsContent(
    store: PrefsStore,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    profileBase: PrefsStore? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val profileMode = profileBase != null

    val features = remember { DeviceFeatures.detect(activity ?: context) }
    val values = remember(store) {
        SettingsValues(store, PreferenceConfiguration.getDefaultSmallMode(context))
            .also { it.persistDefaults(SettingsCatalog.groups) }
    }
    LaunchedEffect(values) { store.changes.collect { values.onStoreChanged(it) } }

    // The display (read again when it changes: foldables) and the decoders (once, off the main thread)
    val configuration = LocalConfiguration.current
    val pixelCount = remember(configuration, activity) { activity?.let { DisplayInfo.pixelCount(it) } ?: 0 }
    val displayInfo = remember { mutableStateOf(activity?.let { DisplayInfo.read(it) }) }
    LaunchedEffect(pixelCount) { activity?.let { displayInfo.value = DisplayInfo.read(it) } }
    val decoderMax = remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val app = context.applicationContext
        decoderMax.value = withContext(Dispatchers.Default) { runCatching { decoderMaxWidth(app) }.getOrDefault(0) }
    }
    val resolutions = remember(values) {
        derivedStateOf {
            resolutionChoices(context, displayInfo.value, decoderMax.value, values.string(Keys.CUSTOM_RESOLUTION, null))
        }
    }
    val frameRates = remember(values) {
        derivedStateOf {
            fpsChoices(
                context, displayInfo.value,
                values.boolean(Keys.UNLOCK_FPS, false),
                values.string(Keys.CUSTOM_REFRESH_RATE, null),
            )
        }
    }
    val controller = remember(values) { SettingsController(context, values, features, resolutions, frameRates) }

    // What the device can't do is replaced by the next best (and the bitrate follows)
    LaunchedEffect(values) {
        snapshotFlow { resolutions.value.removed }.collect { removed ->
            for ((gone, next) in removed) {
                if (values.string(Keys.RESOLUTION, Keys.DEFAULT_RESOLUTION).equals(gone, ignoreCase = true)) {
                    values.putString(Keys.RESOLUTION, next)
                    values.resetBitrate()
                }
            }
        }
    }
    LaunchedEffect(values) {
        snapshotFlow { frameRates.value.removed }.collect { removed ->
            for ((gone, next) in removed) {
                if (values.string(Keys.FPS, Keys.DEFAULT_FPS).equals(gone, ignoreCase = true)) {
                    values.putString(Keys.FPS, next)
                    values.resetBitrate()
                }
            }
        }
    }
    // A custom refresh rate that isn't a number is dropped
    LaunchedEffect(values) {
        snapshotFlow { values.string(Keys.CUSTOM_REFRESH_RATE, null) }.collect {
            if (!it.isNullOrEmpty() && it.toFloatOrNull() == null) values.remove(Keys.CUSTOM_REFRESH_RATE)
        }
    }
    LaunchedEffect(features) {
        if (features.brokenHdr) values.putBoolean(Keys.ENABLE_HDR, false)
    }

    // The settings a profile changes against the app's own
    val changed = produceState(emptySet<String>(), values, profileBase) {
        if (profileBase == null) return@produceState
        val base = profileBase.all()
        value = changedKeys(base, store.all())
        store.changes.collect { value = changedKeys(base, store.all()) }
    }

    // The file pickers of the imports
    val keyboardPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null) SettingsActions.importKeyboard(context, store, uri)
    }
    val specialPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null) SettingsActions.importSpecialButtons(context, uri)
    }
    SideEffect {
        controller.pickKeyboardLayout = { launchPicker(context) { keyboardPicker.launch(SettingsActions.openJsonIntent()) } }
        controller.pickSpecialButtons = { launchPicker(context) { specialPicker.launch(SettingsActions.openJsonIntent()) } }
    }

    val groups = remember(features, profileMode) { buildGroups(context, features, profileMode) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val entries by remember(groups) {
        derivedStateOf { buildEntries(groups, if (searching) query.trim() else "", expanded) }
    }
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(enabled = searching, onBack = closeSearch)

    val listState = rememberLazyListState()
    LaunchedEffect(query) { if (searching) listState.scrollToItem(0) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // (rows on a darker container: each group reads as one block)
    val container = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = container,
        topBar = {
            if (searching) {
                SearchTopBar(query = query, onQueryChange = { query = it }, onClose = closeSearch)
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(SettingsIcons.Search, stringResource(R.string.settings_search))
                        }
                        actions()
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 16.dp,
                end = padding.calculateEndPadding(direction) + 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            items(entries, key = { it.key }, contentType = { it::class }) { entry ->
                when (entry) {
                    is Entry.Header -> GroupHeader(entry.group.title, entry.first)
                    is Entry.Row -> SettingRow(entry.item, entry.index, entry.count, controller, changed)
                    is Entry.More -> MoreRow(entry) { expanded[entry.group.key] = true }
                    Entry.NoResults -> Text(
                        stringResource(R.string.settings_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }
        }
    }

    SettingsDialogHost(controller)
}

private inline fun launchPicker(context: Context, launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        SettingsActions.toast(context, R.string.pref_error_occurred)
    }
}

@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            val style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_search),
                                style = style,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.settings_search_clear))
                }
            }
        },
    )
}

@Composable
private fun GroupHeader(title: String, first: Boolean) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (first) 8.dp else 28.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(
    item: SettingItem,
    index: Int,
    count: Int,
    controller: SettingsController,
    changed: State<Set<String>>,
) {
    val s = item.setting
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val enabled = controller.enabled(s)
    val title = if (s.key in changed.value) "*" + item.title else item.title
    val modifier = Modifier.padding(bottom = if (index < count - 1) ListItemDefaults.SegmentedGap else 0.dp)
    val supporting: (@Composable () -> Unit)? = item.summary?.let { { Text(it) } }
    val leading: (@Composable () -> Unit)? =
        if (s is Setting.Choice && s.icon != 0) {
            { Icon(painterResource(s.icon), contentDescription = null) }
        } else {
            null
        }

    if (s is Setting.Toggle) {
        val checked = controller.values.toggle(s)
        // (a plain row with a switch: "checked" would tint every row that is on)
        SegmentedListItem(
            onClick = { controller.onToggle(s, !checked) },
            shapes = shapes,
            modifier = modifier,
            enabled = enabled,
            leadingContent = leading,
            trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
            supportingContent = supporting,
        ) { Text(title) }
        return
    }

    val context = LocalContext.current
    val value: String? = when (s) {
        is Setting.Choice -> {
            val v = controller.values.choice(s)
            controller.choiceList(s).nameOf(v) ?: v
        }
        is Setting.Slider -> s.format(context, controller.values.slider(s))
        is Setting.Text -> controller.values.string(s.key, null)?.takeIf { it.isNotEmpty() }
        else -> null
    }
    SegmentedListItem(
        onClick = { controller.open(s) },
        shapes = shapes,
        modifier = modifier,
        enabled = enabled,
        leadingContent = leading,
        trailingContent = when {
            value != null -> {
                {
                    Text(
                        value,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 128.dp),
                    )
                }
            }
            s is Setting.Link -> {
                { Icon(SettingsIcons.OpenInNew, contentDescription = null) }
            }
            else -> null
        },
        supportingContent = supporting,
    ) { Text(title) }
}

@Composable
private fun MoreRow(entry: Entry.More, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(entry.index, entry.count),
        leadingContent = {
            Icon(SettingsIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        supportingContent = { Text(entry.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(stringResource(R.string.settings_show_more, entry.hidden), color = MaterialTheme.colorScheme.primary)
    }
}

/** The categories and settings this device (and a profile) shows */
private fun buildGroups(context: Context, features: DeviceFeatures, profileMode: Boolean): List<GroupItem> =
    SettingsCatalog.groups.filter { it.visibleWhen in features }.mapNotNull { g ->
        val items = g.settings
            .filter { it.visibleWhen in features && !(profileMode && it.hiddenInProfile) }
            .map { s ->
                val summary = when {
                    s.key == Keys.ENABLE_HDR && features.brokenHdr -> context.getString(R.string.settings_hdr_broken_shield)
                    s.summary != 0 -> context.getString(s.summary)
                    else -> null
                }
                SettingItem(s, context.getString(s.title), summary)
            }
        if (items.isEmpty()) null else GroupItem(g.key, context.getString(g.title), items, g.initiallyShown)
    }

/** The list's lines: each category's heading and rows (or those matching [query]) */
private fun buildEntries(groups: List<GroupItem>, query: String, expanded: Map<String, Boolean>): List<Entry> {
    val out = ArrayList<Entry>()
    for (g in groups) {
        if (query.isNotEmpty()) {
            val matches = g.items.filter {
                it.title.contains(query, ignoreCase = true) || it.summary?.contains(query, ignoreCase = true) == true
            }
            if (matches.isEmpty()) continue
            out += Entry.Header(g, out.isEmpty())
            matches.forEachIndexed { i, item -> out += Entry.Row(item, i, matches.size) }
            continue
        }
        val limit = g.initiallyShown
        val collapsed = limit != null && g.items.size > limit && expanded[g.key] != true
        val shown = if (collapsed) g.items.take(limit!!) else g.items
        val count = shown.size + if (collapsed) 1 else 0
        out += Entry.Header(g, out.isEmpty())
        shown.forEachIndexed { i, item -> out += Entry.Row(item, i, count) }
        if (collapsed) {
            val hidden = g.items.drop(limit!!)
            out += Entry.More(g, shown.size, count, hidden.size, hidden.joinToString(", ") { it.title })
        }
    }
    if (out.isEmpty() && query.isNotEmpty()) out += Entry.NoResults
    return out
}

/** The keys whose value in [profile] differs from [base] (or that the profile lacks) */
private fun changedKeys(base: Map<String, *>, profile: Map<String, *>): Set<String> {
    val out = HashSet<String>()
    for ((k, v) in base) {
        if (!profile.containsKey(k)) {
            out += k
            continue
        }
        val p = profile[k]
        val same = if (v is Number && p is Number) v.toDouble() == p.toDouble() else v == p
        if (v == null || !same) out += k
    }
    return out
}
