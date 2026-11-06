package app.sloplight.ui.apps

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(hostName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onProfiles,
                expanded = activeProfile.isNotEmpty(),
                icon = { Icon(painterResource(R.drawable.ic_profiles), stringResource(R.string.profile_manager_choose_profile)) },
                text = { Text(activeProfile) },
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
                LoadingIndicator(Modifier.size(96.dp))
                Text(stringResource(R.string.applist_refresh_msg), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
        } else {
            val small = prefs.smallIconMode
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (small) 100.dp else 150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        AppCard(
                            item = item,
                            boxArt = { viewModel.boxArt },
                            small = small,
                            onClick = {
                                val running = viewModel.runningAppId
                                when {
                                    item.isDesk -> start(item, false)
                                    running != 0 && prefs.resumeWithoutConfirm && running == item.id -> start(item, prefs.useVirtualDisplay)
                                    running != 0 -> menu = true
                                    else -> startChecked(item, prefs.useVirtualDisplay)
                                }
                            },
                            onLongClick = { menu = true },
                        )
                        AppMenu(
                            item = item,
                            runningId = viewModel.runningAppId,
                            useVirtualDisplay = prefs.useVirtualDisplay,
                            expanded = menu,
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
                                val c = computer() ?: return@AppMenu
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

/**
 * An app's card: its poster filling it (its name when it has none), outlined
 * in the primary color with a play badge while it runs, faded when hidden.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(item: AppItem, boxArt: () -> app.sloplight.data.BoxArt?, small: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = if (small) MaterialTheme.shapes.large else MaterialTheme.shapes.extraLarge
    val art by produceState<Bitmap?>(initialValue = boxArt()?.cached(item.app), item.id, boxArt()) {
        if (value == null && !item.isDesk) value = boxArt()?.load(item.app)
    }
    Surface(
        shape = shape,
        color = colors.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .alpha(if (item.hidden) 0.4f else 1f)
            .then(if (item.running) Modifier.border(3.dp, colors.primary, shape) else Modifier)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button, onClickLabel = item.name),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val bitmap = art
            if (bitmap != null) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Image(image, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    item.name,
                    style = if (small) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.titleMediumEmphasized,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp),
                )
            }
            if (item.running) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                Box(
                    Modifier.size(if (small) 40.dp else 56.dp).background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_play_plain), null, tint = colors.onPrimary, modifier = Modifier.size(if (small) 20.dp else 28.dp))
                }
            }
        }
    }
}

/** An app's actions, as what runs allows (what AppView's context menu offered) */
@Composable
private fun AppMenu(
    item: AppItem,
    runningId: Int,
    useVirtualDisplay: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onStart: (withVDisplay: Boolean) -> Unit,
    onQuitAndStart: (withVDisplay: Boolean) -> Unit,
    onQuit: () -> Unit,
    onHide: () -> Unit,
    onDetails: () -> Unit,
    onShortcut: () -> Unit,
    onExport: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = MaterialTheme.shapes.large) {
        @Composable
        fun item(text: Int, action: () -> Unit) =
            DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { onDismiss(); action() })

        Text(
            item.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (item.isDesk) {
            item(R.string.applist_menu_start_primarydisplay) { onStart(false) }
            return@DropdownMenu
        }
        when {
            runningId == 0 ->
                if (useVirtualDisplay) item(R.string.applist_menu_start_primarydisplay) { onStart(false) }
                else item(R.string.applist_menu_start_vdisplay) { onStart(true) }
            runningId == item.id -> {
                item(R.string.applist_menu_resume) { onStart(false) }
                item(R.string.applist_menu_quit, onQuit)
            }
            useVirtualDisplay -> {
                item(R.string.applist_menu_quit_and_start) { onQuitAndStart(true) }
                item(R.string.applist_menu_quit_and_start_primarydisplay) { onQuitAndStart(false) }
            }
            else -> {
                item(R.string.applist_menu_quit_and_start) { onQuitAndStart(false) }
                item(R.string.applist_menu_quit_and_start_vdisplay) { onQuitAndStart(true) }
            }
        }
        if (runningId != item.id || item.hidden) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.applist_menu_hide_app)) },
                trailingIcon = { Checkbox(checked = item.hidden, onCheckedChange = null) },
                onClick = { onDismiss(); onHide() },
            )
        }
        item(R.string.applist_menu_details, onDetails)
        item(R.string.applist_menu_scut, onShortcut)
        item(R.string.applist_menu_export_launcher, onExport)
    }
}
