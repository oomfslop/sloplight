package app.sloplight.ui.apps

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.Shape
import app.sloplight.data.BoxArt
import app.sloplight.ui.components.ActionSheet
import app.sloplight.ui.components.SectionHeading
import app.sloplight.ui.components.SheetAction
import app.sloplight.ui.components.cardColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sloplight.data.HostsRepository
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper

/** What starting an app needs confirmed first */
private sealed interface Confirm {
    /** A virtual display that the host can't make (yet): start anyway? */
    data class VirtualDisplay(val then: () -> Unit) : Confirm
    /** Quit the running app (then maybe start another) */
    data class Quit(val then: () -> Unit) : Confirm
    /** Export a launcher file for an app without a UUID */
    data class Export(val then: () -> Unit) : Confirm
}

/**
 * A host's apps as posters (the app's name on its card when it has none); a
 * tap starts one (or resumes it, or asks first when another runs), a long
 * press its menu.
 */
@Composable
fun AppsScreen(
    hostUuid: String,
    hostName: String,
    showHidden: Boolean,
    newlyPaired: Boolean,
    onBack: () -> Unit,
    onProfiles: () -> Unit,
    activeProfile: String,
) {
    val application = LocalActivity.current?.application ?: return
    val viewModel: AppsViewModel = viewModel { AppsViewModel(application, hostUuid, showHidden) }
    val activity = LocalActivity.current ?: return
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val prefs = remember { PreferenceConfiguration.readPreferences(activity) }
    // The posters' size: the grid's own choice, switched from its top bar
    var small by remember { mutableStateOf(prefs.smallIconMode) }
    fun setSmall(on: Boolean) {
        small = on
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity).edit().putBoolean("checkbox_small_icon_mode", on).apply()
    }
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var details by remember { mutableStateOf<AppItem?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Polled while shown; leaves when the host goes away or forgets this device
    LaunchedEffect(viewModel) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.poll() } }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is AppsEvent.Leave -> {
                    if (e.notPaired) viewModel.computer?.let {
                        ShortcutHelper(activity).disableComputerShortcut(it, activity.getString(R.string.scut_not_paired))
                    }
                    Toast.makeText(activity, e.message, Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
        }
    }
    // This host's launcher shortcut (forced: the user opened it); the apps' shortcuts follow the list
    LaunchedEffect(viewModel.computer) {
        viewModel.computer?.let {
            val helper = ShortcutHelper(activity)
            helper.createAppViewShortcut(it, true, newlyPaired)
            helper.reportComputerShortcutUsed(it)
        }
    }
    var shownIds by remember { mutableStateOf<Set<Int>?>(null) }
    LaunchedEffect(apps) {
        val now = apps?.filterNot { it.isDesk } ?: return@LaunchedEffect
        val computer = viewModel.computer ?: return@LaunchedEffect
        val before = shownIds
        val helper = ShortcutHelper(activity)
        if (before != null) {
            now.filter { it.id !in before }.forEach { helper.enableAppShortcut(computer, it.app) }
        }
        shownIds = now.map { it.id }.toSet()
    }

    fun binder() = HostsRepository.get(activity).binder.value
    fun computer(): ComputerDetails? = viewModel.computer
    fun vDisplayReady() = computer()?.let { it.vDisplaySupported && it.vDisplayDriverReady } ?: false
    fun start(item: AppItem, withVDisplay: Boolean) {
        val c = computer() ?: return
        val b = binder() ?: return
        if (item.isDesk) ServerHelper.doStartDesk(activity, c, b)
        else ServerHelper.doStart(activity, item.app, c, b, withVDisplay)
    }
    /** Starts it, asking first when a virtual display is wanted but not ready */
    fun startChecked(item: AppItem, withVDisplay: Boolean) {
        if (withVDisplay && !vDisplayReady()) confirm = Confirm.VirtualDisplay { start(item, true) }
        else start(item, withVDisplay)
    }
    fun quit(item: AppItem) {
        val c = computer() ?: return
        val b = binder() ?: return
        confirm = Confirm.Quit { ServerHelper.doQuit(activity, c, item.app, b) { viewModel.pollNow() } }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(hostName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = {
                    if (activeProfile.isNotEmpty()) {
                        Text(stringResource(R.string.settings_profiles_active, activeProfile), modifier = Modifier.clickable(onClick = onProfiles))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { setSmall(!small) }) {
                        Icon(
                            if (small) Icons.Outlined.ViewModule else Icons.Outlined.GridView,
                            stringResource(if (small) R.string.apps_posters_large else R.string.apps_posters_small),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = apps
        if (list == null) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(40.dp))
                Text(stringResource(R.string.applist_refresh_msg), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
        } else {
            val desk = list.firstOrNull { it.isDesk }
            val running = list.firstOrNull { it.running && !it.isDesk }
            val grid = list.filterNot { it.isDesk }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (small) 96.dp else 132.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (running != null) {
                    item(key = "now", span = { GridItemSpan(maxLineSpan) }, contentType = "now") {
                        NowPlaying(
                            item = running,
                            boxArt = { viewModel.boxArt },
                            onResume = { start(running, false) },
                            onQuit = { quit(running) },
                        )
                    }
                }
                if (desk != null) {
                    item(key = "desk", span = { GridItemSpan(maxLineSpan) }, contentType = "desk") {
                        DeskCard(onClick = { start(desk, false) })
                    }
                }
                if (running != null || desk != null) {
                    item(key = "heading", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                        SectionHeading(stringResource(R.string.apps_heading), Modifier.padding(top = 8.dp))
                    }
                }
                items(grid, key = { it.id }, contentType = { "app" }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    AppPoster(
                        item = item,
                        boxArt = { viewModel.boxArt },
                        small = small,
                        onClick = {
                            val runningId = viewModel.runningAppId
                            when {
                                runningId != 0 && prefs.resumeWithoutConfirm && runningId == item.id -> start(item, prefs.useVirtualDisplay)
                                runningId != 0 -> menu = true
                                else -> startChecked(item, prefs.useVirtualDisplay)
                            }
                        },
                        onLongClick = { menu = true },
                    )
                    if (menu) {
                        AppSheet(
                            item = item,
                            boxArt = { viewModel.boxArt },
                            runningId = viewModel.runningAppId,
                            useVirtualDisplay = prefs.useVirtualDisplay,
                            onDismiss = { menu = false },
                            onStart = { v -> startChecked(item, v) },
                            onQuitAndStart = { v -> confirm = Confirm.Quit { startChecked(item, v) } },
                            onQuit = { quit(item) },
                            onHide = { viewModel.setHidden(item, !item.hidden) },
                            onDetails = { details = item },
                            onShortcut = {
                                val art = viewModel.boxArt?.cached(item.app)
                                val c = computer()
                                if (art == null || c == null || !ShortcutHelper(activity).createPinnedGameShortcut(c, item.app, art)) {
                                    Toast.makeText(activity, R.string.unable_to_pin_shortcut, Toast.LENGTH_LONG).show()
                                }
                            },
                            onExport = {
                                val c = computer() ?: return@AppSheet
                                val export = { ShortcutHelper(activity).exportLauncherFile(c, item.app) }
                                if (item.app.appUUID.isNullOrEmpty()) confirm = Confirm.Export(export) else export()
                            },
                        )
                    }
                }
            }
        }
    }

    when (val c = confirm) {
        null -> Unit
        else -> {
            val text = when (c) {
                is Confirm.VirtualDisplay -> stringResource(if (computer()?.vDisplaySupported == true) R.string.vdisplay_not_ready else R.string.vdisplay_not_supported)
                is Confirm.Quit -> stringResource(R.string.applist_quit_confirmation)
                is Confirm.Export -> stringResource(R.string.message_export_sunshine_launcher_file)
            }
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = if (c is Confirm.Export) {{ Text(stringResource(R.string.title_export_sunshine_launcher_file)) }} else null,
                text = { Text(text) },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        when (c) {
                            is Confirm.VirtualDisplay -> c.then()
                            is Confirm.Quit -> c.then()
                            is Confirm.Export -> c.then()
                        }
                    }) { Text(stringResource(if (c is Confirm.Quit) R.string.yes else R.string.proceed)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = null }) { Text(stringResource(if (c is Confirm.Quit) R.string.no else R.string.cancel)) }
                },
            )
        }
    }
    details?.let { item ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(stringResource(R.string.title_details)) },
            text = { Text(item.app.toString()) },
            confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/** An app's box art (the memory cache at once, else loaded), null when it has none */
@Composable
private fun rememberBoxArt(item: AppItem, boxArt: () -> BoxArt?): Bitmap? {
    val art by produceState<Bitmap?>(initialValue = boxArt()?.cached(item.app), item.id, boxArt()) {
        if (value == null && !item.isDesk) value = boxArt()?.load(item.app)
    }
    return art
}

/** A poster: the box art, or the app's initial on a tinted card when it has none */
@Composable
private fun Poster(item: AppItem, boxArt: () -> BoxArt?, shape: Shape, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val art = rememberBoxArt(item, boxArt)
    Box(modifier.clip(shape).background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
        if (art != null) {
            val image = remember(art) { art.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSecondaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/**
 * An app: its poster (3:4) and its name under it; hidden ones faded with a
 * mark. A tap starts it, a long press opens its actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppPoster(item: AppItem, boxArt: () -> BoxArt?, small: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .alpha(if (item.hidden) 0.45f else 1f)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button, onClickLabel = item.name)
            .padding(4.dp),
    ) {
        Box {
            Poster(item, boxArt, shape, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            if (item.hidden) {
                Icon(
                    Icons.Outlined.VisibilityOff, null,
                    tint = colors.onSurface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(colors.surfaceContainerHighest, CircleShape).padding(4.dp).size(16.dp),
                )
            }
        }
        Text(
            item.name,
            style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** What runs on the host now: its poster, its name, resume and quit */
@Composable
private fun NowPlaying(item: AppItem, boxArt: () -> BoxArt?, onResume: () -> Unit, onQuit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(item, boxArt, MaterialTheme.shapes.small, Modifier.width(60.dp).aspectRatio(3f / 4f))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.apps_now_running), style = MaterialTheme.typography.labelMedium)
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onResume) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.applist_menu_resume), modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = onQuit) { Text(stringResource(R.string.applist_menu_quit)) }
            }
        }
    }
}

/** The host's windows as workspaces (a Sloplight host that allows this device) */
@Composable
private fun DeskCard(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = cardColor()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(colors.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Dashboard, null, tint = colors.onTertiaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(stringResource(R.string.applist_desk_tile), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.apps_desk_summary), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
        }
    }
}

/** An app's actions, as what runs allows (what AppView's context menu offered), as a sheet */
@Composable
private fun AppSheet(
    item: AppItem,
    boxArt: () -> BoxArt?,
    runningId: Int,
    useVirtualDisplay: Boolean,
    onDismiss: () -> Unit,
    onStart: (withVDisplay: Boolean) -> Unit,
    onQuitAndStart: (withVDisplay: Boolean) -> Unit,
    onQuit: () -> Unit,
    onHide: () -> Unit,
    onDetails: () -> Unit,
    onShortcut: () -> Unit,
    onExport: () -> Unit,
) {
    val start = buildList {
        when {
            runningId == 0 -> {
                add(SheetAction(stringResource(R.string.applist_menu_start), Icons.Filled.PlayArrow, { onStart(useVirtualDisplay) }))
                add(
                    if (useVirtualDisplay) SheetAction(stringResource(R.string.applist_menu_start_primarydisplay), Icons.Outlined.Monitor, { onStart(false) })
                    else SheetAction(stringResource(R.string.applist_menu_start_vdisplay), Icons.Outlined.DesktopWindows, { onStart(true) })
                )
            }
            runningId == item.id -> {
                add(SheetAction(stringResource(R.string.applist_menu_resume), Icons.Filled.PlayArrow, { onStart(false) }))
                add(SheetAction(stringResource(R.string.applist_menu_quit), Icons.Outlined.StopCircle, onQuit, destructive = true))
            }
            useVirtualDisplay -> {
                add(SheetAction(stringResource(R.string.applist_menu_quit_and_start), Icons.Outlined.SwapHoriz, { onQuitAndStart(true) }))
                add(SheetAction(stringResource(R.string.applist_menu_quit_and_start_primarydisplay), Icons.Outlined.Monitor, { onQuitAndStart(false) }))
            }
            else -> {
                add(SheetAction(stringResource(R.string.applist_menu_quit_and_start), Icons.Outlined.SwapHoriz, { onQuitAndStart(false) }))
                add(SheetAction(stringResource(R.string.applist_menu_quit_and_start_vdisplay), Icons.Outlined.DesktopWindows, { onQuitAndStart(true) }))
            }
        }
    }
    val more = buildList {
        if (runningId != item.id || item.hidden) {
            add(SheetAction(stringResource(R.string.applist_menu_hide_app), Icons.Outlined.VisibilityOff, onHide, checked = item.hidden))
        }
        add(SheetAction(stringResource(R.string.applist_menu_scut), Icons.Outlined.AddToHomeScreen, onShortcut))
        add(SheetAction(stringResource(R.string.applist_menu_export_launcher), Icons.Outlined.FileDownload, onExport))
        add(SheetAction(stringResource(R.string.applist_menu_details), Icons.Outlined.Description, onDetails))
    }
    ActionSheet(
        onDismiss = onDismiss,
        header = {
            Poster(item, boxArt, MaterialTheme.shapes.small, Modifier.width(48.dp).aspectRatio(3f / 4f))
            Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
        },
        groups = listOf(start, more),
    )
}
