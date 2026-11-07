package app.sloplight.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import app.sloplight.ui.components.SegmentRow
import app.sloplight.ui.components.SegmentGap
import app.sloplight.ui.components.segmentShape
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

/** A setting as listed, its texts resolved once; [options]: a choice shown inline as buttons */
@Immutable
internal class SettingItem(val setting: Setting, val title: String, val summary: String?, val options: List<ChoiceEntry>?)

/** A visible section of a category ([title] null: no heading) */
@Immutable
internal class SectionItem(val key: String, val title: String?, val items: List<SettingItem>)

/** A visible category and its visible sections */
@Immutable
internal class GroupItem(val key: String, val title: String, val summary: String, val sections: List<SectionItem>)

/** A line of the list */
@Immutable
internal sealed interface Entry {
    val key: String
    val type: Any get() = this::class

    class Header(override val key: String, val title: String, val first: Boolean) : Entry

    class Row(val item: SettingItem, val index: Int, val count: Int) : Entry {
        override val key get() = item.setting.key
        override val type: Any
            get() = when (item.setting) {
                is Setting.Toggle -> 1
                is Setting.Slider -> 2
                is Setting.Choice -> if (item.options != null) 3 else 4
                else -> 4
            }
    }

    /** (the hub) the appearance and profiles, above the categories */
    /** (the main hub) the theme picker, wallpaper colors, the profiles row */
    class Theme(val index: Int, val count: Int) : Entry {
        override val key get() = "theme"
    }

    class Wallpaper(val index: Int, val count: Int) : Entry {
        override val key get() = "wallpaper"
    }

    data object ProfilesLink : Entry {
        override val key get() = "profiles"
    }

    data object Version : Entry {
        override val key get() = "version"
    }

    data object DebugInfo : Entry {
        override val key get() = "debug-info"
    }

    data object HubTop : Entry {
        override val key get() = "hub"
    }

    data object StreamingHeader : Entry {
        override val key get() = "streaming"
    }

    /** (the hub) a category, opening its page */
    class GroupLink(val group: GroupItem, val index: Int, val count: Int) : Entry {
        override val key get() = "g:" + group.key
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
    groupKey: String? = null,
    onOpenGroup: ((String) -> Unit)? = null,
    hubInfo: HubInfo? = null,
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
    // Without pages of its own (the profile editor) a category opens inside this screen
    var innerGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val hub = groupKey == null
    val entries by remember(groups) {
        derivedStateOf {
            val q = if (searching) query.trim() else ""
            val open = groupKey ?: innerGroup
            when {
                hub && open == null && q.isEmpty() && hubInfo != null -> hubEntries(groups, hubInfo)
                hub && open == null && q.isEmpty() -> buildList {
                    groups.forEachIndexed { i, g -> add(Entry.GroupLink(g, i, groups.size)) }
                }
                open != null && q.isEmpty() -> buildEntries(groups.filter { it.key == open }, "")
                else -> buildEntries(groups, q)
            }
        }
    }
    val closeSearch = {
        searching = false
        query = ""
    }
    val back = {
        if (innerGroup != null) innerGroup = null else onBack()
    }
    BackHandler(enabled = innerGroup != null) { innerGroup = null }
    BackHandler(enabled = searching, onBack = closeSearch)

    val listState = rememberLazyListState()
    LaunchedEffect(query) { if (searching) listState.scrollToItem(0) }
    LaunchedEffect(innerGroup) { listState.scrollToItem(0) }
    val shownTitle = innerGroup?.let { k -> groups.firstOrNull { it.key == k }?.title } ?: title

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (searching) {
                SearchTopBar(query = query, onQueryChange = { query = it }, onClose = closeSearch)
            } else {
                TopAppBar(
                    title = { Text(shownTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = back) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(SettingsIcons.Search, stringResource(R.string.settings_search))
                        }
                        actions()
                    },
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
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            items(entries, key = { it.key }, contentType = { it.type }) { entry ->
                when (entry) {
                    is Entry.Header -> GroupHeader(entry.title, entry.first)
                    is Entry.Row -> SettingRow(entry.item, entry.index, entry.count, controller, changed)
                    Entry.HubTop -> Unit
                    Entry.StreamingHeader -> GroupHeader(stringResource(R.string.settings_streaming), first = hubInfo == null)
                    is Entry.Theme -> ThemeRow(entry.index, entry.count)
                    is Entry.Wallpaper -> WallpaperRow(entry.index, entry.count)
                    Entry.ProfilesLink -> hubInfo?.let { ProfilesRow(it) }
                    Entry.Version -> VersionRow()
                    Entry.DebugInfo -> DebugInfoRow()
                    is Entry.GroupLink -> GroupLinkRow(entry, changed) {
                        if (onOpenGroup != null) onOpenGroup(entry.group.key) else innerGroup = entry.group.key
                    }
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
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (first) 0.dp else 24.dp, bottom = 8.dp),
    )
}

/** Rows of disabled settings: their texts faded (the controls fade on their own) */
private const val DisabledAlpha = 0.38f

@Composable
private fun SettingRow(
    item: SettingItem,
    index: Int,
    count: Int,
    controller: SettingsController,
    changed: State<Set<String>>,
) {
    val s = item.setting
    val shapes = segmentShape(index, count)
    val enabled = controller.enabled(s)
    val title = if (s.key in changed.value) "*" + item.title else item.title
    // Not dimmed as a whole: a disabled switch or slider would fade into the card
    val modifier = Modifier
        .padding(bottom = if (index < count - 1) SegmentGap else 0.dp)
        .then(if (enabled) Modifier else Modifier.semantics(mergeDescendants = s !is Setting.Slider) { disabled() })
    when {
        s is Setting.Toggle -> ToggleRow(s, item, title, enabled, shapes, modifier, controller)
        s is Setting.Slider -> SliderRow(s, item, title, enabled, shapes, modifier, controller)
        s is Setting.Choice && item.options != null -> InlineChoiceRow(s, item, item.options, title, enabled, shapes, modifier, controller)
        else -> ValueRow(s, item, title, enabled, shapes, modifier, controller)
    }
}

/** A text's color: as is, or faded when disabled */
@Composable
private fun dimmed(enabled: Boolean): Color = if (enabled) Color.Unspecified else LocalContentColor.current.copy(alpha = DisabledAlpha)

@Composable
private fun Summary(text: String?, enabled: Boolean) {
    if (text != null) Text(text, color = dimmed(enabled))
}

@Composable
private fun LeadingIcon(s: Setting, enabled: Boolean) {
    if (s is Setting.Choice && s.icon != 0) {
        Icon(painterResource(s.icon), contentDescription = null, tint = dimmed(enabled).takeOrElse { LocalContentColor.current })
    }
}

private fun leadingFor(s: Setting, enabled: Boolean): (@Composable () -> Unit)? =
    if (s is Setting.Choice && s.icon != 0) {
        { LeadingIcon(s, enabled) }
    } else {
        null
    }

/** A switch at the end; the whole row toggles it */
@Composable
private fun ToggleRow(
    s: Setting.Toggle,
    item: SettingItem,
    title: String,
    enabled: Boolean,
    shapes: Shape,
    modifier: Modifier,
    controller: SettingsController,
) {
    val checked = controller.values.toggle(s)
    // (a plain row with a switch: "checked" would tint every row that is on)
    SegmentRow(
        onClick = if (enabled) {
            { controller.onToggle(s, !checked) }
        } else {
            null
        },
        shapes = shapes,
        modifier = modifier,
        trailingContent = {
            // A disabled switch keeps its normal colors, only lighter: its own disabled ones vanish on the card
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = if (enabled) Modifier else Modifier.alpha(0.5f),
            )
        },
        supportingContent = item.summary?.let { { Summary(it, enabled) } },
    ) { Text(title, color = dimmed(enabled)) }
}

/**
 * The title and the value, a slider below. The value while dragging stays
 * in the row; it is saved when the drag ends. Tapping the value types it.
 */
@Composable
private fun SliderRow(
    s: Setting.Slider,
    item: SettingItem,
    title: String,
    enabled: Boolean,
    shapes: Shape,
    modifier: Modifier,
    controller: SettingsController,
) {
    val context = LocalContext.current
    val stored = controller.values.slider(s)
    var dragged by remember { mutableStateOf<Int?>(null) }
    val shown = dragged ?: stored
    val range = remember(s) { s.positions() }
    SegmentRow(
        shapes = shapes,
        modifier = modifier,
        supportingContent = {
            Column {
                Summary(item.summary, enabled)
                @Suppress("DEPRECATION")
                Slider(
                    value = s.positionOf(shown),
                    onValueChange = { dragged = s.valueAt(it) },
                    onValueChangeFinished = {
                        dragged?.let { if (it != controller.values.slider(s)) controller.onSlider(s, it) }
                        dragged = null
                    },
                    enabled = enabled,
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = dimmed(enabled), modifier = Modifier.weight(1f).padding(end = 12.dp))
            ValueChip(s.format(context, shown), enabled) { controller.open(s) }
        }
    }
}

/** A setting's value as a small tonal button (opens its dialog) */
@Composable
private fun ValueChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) colors.secondaryContainer else colors.onSurface.copy(alpha = 0.08f),
        contentColor = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = DisabledAlpha),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A few short options as connected buttons under the title */
@Composable
private fun InlineChoiceRow(
    s: Setting.Choice,
    item: SettingItem,
    options: List<ChoiceEntry>,
    title: String,
    enabled: Boolean,
    shapes: Shape,
    modifier: Modifier,
    controller: SettingsController,
) {
    val current = controller.values.choice(s)
    SegmentRow(
        shapes = shapes,
        modifier = modifier,
        leadingContent = leadingFor(s, enabled),
        supportingContent = {
            Column {
                Summary(item.summary, enabled)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    options.forEachIndexed { i, option ->
                        ToggleButton(
                            checked = option.value == current,
                            onCheckedChange = { if (option.value != current) controller.onChoice(s, option.value) },
                            enabled = enabled,
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) {
                            Text(option.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
    ) { Text(title, color = dimmed(enabled)) }
}

/** A row that opens a dialog, a link or an action; the current value under its title */
@Composable
private fun ValueRow(
    s: Setting,
    item: SettingItem,
    title: String,
    enabled: Boolean,
    shapes: Shape,
    modifier: Modifier,
    controller: SettingsController,
) {
    val value: String? = when (s) {
        is Setting.Choice -> {
            val v = controller.values.choice(s)
            controller.choiceList(s).nameOf(v) ?: v
        }
        is Setting.Text -> controller.values.string(s.key, null)?.takeIf { it.isNotEmpty() }
        else -> null
    }
    SegmentRow(
        onClick = if (enabled) {
            { controller.open(s) }
        } else {
            null
        },
        shapes = shapes,
        modifier = modifier,
        leadingContent = leadingFor(s, enabled),
        trailingContent = if (s is Setting.Link) {
            { Icon(SettingsIcons.OpenInNew, contentDescription = null, tint = dimmed(enabled).takeOrElse { LocalContentColor.current }) }
        } else {
            null
        },
        supportingContent = if (value != null || item.summary != null) {
            {
                Column {
                    if (value != null) {
                        Text(
                            value,
                            color = if (enabled) MaterialTheme.colorScheme.primary else dimmed(false),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp, bottom = if (item.summary != null) 2.dp else 0.dp),
                        )
                    }
                    Summary(item.summary, enabled)
                }
            }
        } else {
            null
        },
    ) { Text(title, color = dimmed(enabled)) }
}

@Composable
private fun GroupLinkRow(entry: Entry.GroupLink, changed: State<Set<String>>, onClick: () -> Unit) {
    // (a profile: "*" on the categories it changes)
    val set = changed.value
    val marked = set.isNotEmpty() && entry.group.sections.any { section -> section.items.any { it.setting.key in set } }
    SegmentRow(
        onClick = onClick,
        shapes = segmentShape(entry.index, entry.count),
        modifier = Modifier.padding(bottom = if (entry.index < entry.count - 1) SegmentGap else 0.dp),
        leadingContent = { Icon(groupIcon(entry.group.key), contentDescription = null) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        supportingContent = { Text(entry.group.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(if (marked) "*" + entry.group.title else entry.group.title)
    }
}

/** The categories, sections and settings this device (and a profile) shows */
private fun buildGroups(context: Context, features: DeviceFeatures, profileMode: Boolean): List<GroupItem> =
    SettingsCatalog.groups.filter { it.visibleWhen in features }.mapNotNull { g ->
        val sections = g.sections.withIndex().filter { it.value.visibleWhen in features }.mapNotNull { (i, section) ->
            val items = section.settings
                .filter { it.visibleWhen in features && !(profileMode && it.hiddenInProfile) }
                .map { s -> settingItem(context, s, features) }
            if (items.isEmpty()) null
            else SectionItem("${g.key}:$i", section.title.takeIf { it != 0 }?.let(context::getString), items)
        }
        if (sections.isEmpty()) null else GroupItem(g.key, context.getString(g.title), context.getString(g.summary), sections)
    }

private fun settingItem(context: Context, s: Setting, features: DeviceFeatures): SettingItem {
    val summary = when {
        s.key == Keys.ENABLE_HDR && features.brokenHdr -> context.getString(R.string.settings_hdr_broken_shield)
        s.summary != 0 -> context.getString(s.summary)
        else -> null
    }
    val options = (s as? Setting.Choice)?.takeIf { it.shortEntries != 0 }?.let { c ->
        val names = context.resources.getStringArray(c.shortEntries)
        val values = context.resources.getStringArray(c.values)
        values.mapIndexed { i, v -> ChoiceEntry(v, names.getOrElse(i) { v }) }
    }
    return SettingItem(s, context.getString(s.title), summary, options)
}

/**
 * The list's lines: each section's heading and its rows. With a [query],
 * only the matching rows, under "category › section" headings.
 */
private fun buildEntries(groups: List<GroupItem>, query: String): List<Entry> {
    val out = ArrayList<Entry>()
    for (g in groups) for (section in g.sections) {
        val rows = if (query.isEmpty()) section.items else section.items.filter {
            it.title.contains(query, ignoreCase = true) || it.summary?.contains(query, ignoreCase = true) == true
        }
        if (rows.isEmpty()) continue
        val title = when {
            query.isEmpty() -> section.title
            section.title == null -> g.title
            else -> g.title + " › " + section.title
        }
        if (title != null) out += Entry.Header("h:" + section.key, title, out.isEmpty())
        rows.forEachIndexed { i, item -> out += Entry.Row(item, i, rows.size) }
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
