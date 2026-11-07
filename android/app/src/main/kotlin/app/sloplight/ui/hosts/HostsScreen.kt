package app.sloplight.ui.hosts

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import app.sloplight.ui.components.SheetAction
import app.sloplight.ui.components.ActionSheet
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import app.sloplight.ui.components.segmentShape
import app.sloplight.ui.components.cardColor
import app.sloplight.ui.components.SegmentGap
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sloplight.data.Host
import app.sloplight.data.HostsRepository
import app.sloplight.data.PendingPairing
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.utils.HelpLauncher
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.UiHelper
import kotlinx.coroutines.launch

/**
 * The hosts: discovered and added PCs as cards, a tap opens one's apps (or
 * pairs it, or offers to wake it), a long press its menu. Polls the hosts
 * while it is shown.
 */
@Composable
fun HostsScreen(
    onOpenApps: (host: Host, newlyPaired: Boolean, showHidden: Boolean) -> Unit,
    onSettings: () -> Unit,
    onProfiles: () -> Unit,
    onAddHost: () -> Unit,
    activeProfile: String,
    viewModel: HostsViewModel = viewModel(),
) {
    val activity = LocalActivity.current
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var otpFor by remember { mutableStateOf<Host?>(null) }
    var detailsFor by remember { mutableStateOf<Host?>(null) }
    var confirmDelete by remember { mutableStateOf<Host?>(null) }
    var confirmQuit by remember { mutableStateOf<Host?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HostsEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                is HostsEvent.OpenApps -> onOpenApps(event.host, event.newlyPaired, false)
            }
        }
    }
    // A link's pairing (art://host?pin=…&passphrase=…), once that host is online there
    val pending by PendingPairing.flow.collectAsStateWithLifecycle()
    LaunchedEffect(hosts, pending) {
        val request = pending ?: return@LaunchedEffect
        val host = hosts?.firstOrNull { h ->
            h.online && viewModel.details(h.uuid)?.activeAddress == request.address
        } ?: return@LaunchedEffect
        PendingPairing.value = null
        viewModel.pair(host, request.pin, request.passphrase)
    }
    // Launcher shortcuts for the paired hosts that are online
    LaunchedEffect(hosts, activity) {
        if (activity == null) return@LaunchedEffect
        val helper = ShortcutHelper(activity)
        hosts?.filter { it.paired && it.online }?.forEach { host ->
            viewModel.details(host.uuid)?.let(helper::createAppViewShortcutForOnlineHost)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                // The profile in use, when one is: it changes how every stream starts
                subtitle = {
                    if (activeProfile.isNotEmpty()) {
                        Text(stringResource(R.string.settings_profiles_active, activeProfile), modifier = Modifier.clickable(onClick = onProfiles))
                    }
                },
                actions = {
                    if (activity?.packageManager?.hasSystemFeature("amazon.hardware.fire_tv") != true) {
                        IconButton(onClick = { activity?.let(HelpLauncher::launchSetupGuide) }) {
                            Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.help))
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.action_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddHost,
                icon = { Icon(painterResource(R.drawable.ic_add_plain), null) },
                text = { Text(stringResource(R.string.add_pc_button)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = hosts
        if (list.isNullOrEmpty()) {
            Searching(Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(SegmentGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(list, key = { _, host -> host.uuid }) { index, host ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        HostRow(
                            host = host,
                            shape = segmentShape(index, list.size),
                            onClick = {
                                when {
                                    !host.online -> menu = true
                                    !host.paired -> viewModel.pair(host)
                                    else -> onOpenApps(host, false, false)
                                }
                            },
                            onLongClick = { menu = true },
                            onMenu = { menu = true },
                        )
                        HostMenu(
                            host = host,
                            expanded = menu,
                            onDismiss = { menu = false },
                            onPair = { viewModel.pair(host) },
                            onPairOtp = { otpFor = host },
                            onWake = { viewModel.wake(host) },
                            onResume = {
                                val d = viewModel.details(host.uuid)
                                val binder = HostsRepository.get(activity!!).binder.value
                                if (d != null && binder != null) {
                                    ServerHelper.doStart(activity, NvApp("app", null, d.runningGameId, false), d, binder, false)
                                }
                            },
                            onQuit = { confirmQuit = host },
                            onAllApps = { onOpenApps(host, false, true) },
                            onManagement = {
                                val url = host.managementUrl
                                if (url == null) Toast.makeText(activity, R.string.pcview_error_no_management_url, Toast.LENGTH_LONG).show()
                                else HelpLauncher.launchUrl(activity, url)
                            },
                            onEol = { activity?.let(HelpLauncher::launchGameStreamEolFaq) },
                            onTestNetwork = { activity?.let(ServerHelper::doNetworkTest) },
                            onDelete = { confirmDelete = host },
                            onDetails = { detailsFor = host },
                        )
                    }
                }
            }
        }
    }

    pairing?.let { PairingDialog(it) }
    otpFor?.let { host ->
        OtpPairDialog(onDismiss = { otpFor = null }, onPair = { pin, phrase -> otpFor = null; viewModel.pair(host, pin, phrase) })
    }
    detailsFor?.let { host ->
        AlertDialog(
            onDismissRequest = { detailsFor = null },
            title = { Text(stringResource(R.string.title_details)) },
            text = { Text(viewModel.details(host.uuid)?.toString() ?: "") },
            confirmButton = { TextButton(onClick = { detailsFor = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    confirmDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.pcview_menu_delete_pc)) },
            text = { Text(stringResource(R.string.delete_pc_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    viewModel.details(host.uuid)?.let { d ->
                        activity?.let { ShortcutHelper(it).disableComputerShortcut(d, it.getString(R.string.scut_deleted_pc)) }
                    }
                    viewModel.forget(host)
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.no)) } },
        )
    }
    confirmQuit?.let { host ->
        AlertDialog(
            onDismissRequest = { confirmQuit = null },
            title = { Text(stringResource(R.string.applist_quit_app)) },
            text = { Text(stringResource(R.string.applist_quit_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmQuit = null
                    val d = viewModel.details(host.uuid)
                    val binder = activity?.let { HostsRepository.get(it).binder.value }
                    if (activity != null && d != null && binder != null) {
                        ServerHelper.doQuit(activity, d, NvApp("app", null, 0, false), binder, null)
                    }
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmQuit = null }) { Text(stringResource(R.string.no)) } },
        )
    }
}

/** Nothing found yet: a progress ring and what is going on */
@Composable
private fun Searching(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(40.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.searching_pc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

/**
 * A host: its monitor in a circle colored by its state (primary when it can
 * be streamed, tertiary while it waits to be paired, muted when it can't be
 * reached), its name, what state it is in; a ring while it is being reached.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(host: Host, shape: Shape, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val locked = host.online && !host.paired
    val (fill, ink) = when {
        locked -> colors.tertiaryContainer to colors.onTertiaryContainer
        host.online -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    val status = stringResource(
        when {
            host.state == ComputerDetails.State.UNKNOWN -> R.string.host_status_checking
            !host.online -> R.string.host_status_offline
            locked -> R.string.host_status_not_paired
            host.runningGameId != 0 -> R.string.host_status_running
            else -> R.string.host_status_online
        }
    )
    Surface(
        shape = shape,
        color = cardColor(),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button, onClickLabel = host.name),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Box(Modifier.size(44.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_computer_small), null, tint = ink, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    host.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface.copy(alpha = if (host.online) 1f else 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (host.state == ComputerDetails.State.UNKNOWN) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            // The host's actions (the same as a long press)
            IconButton(onClick = onMenu) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.host_actions), tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** A host's actions, as its state allows, as a sheet (what PcView's context menu offered) */
@Composable
private fun HostMenu(
    host: Host,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPair: () -> Unit,
    onPairOtp: () -> Unit,
    onWake: () -> Unit,
    onResume: () -> Unit,
    onQuit: () -> Unit,
    onAllApps: () -> Unit,
    onManagement: () -> Unit,
    onEol: () -> Unit,
    onTestNetwork: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
    if (!expanded) return
    @Composable
    fun a(text: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, action: () -> Unit) =
        SheetAction(stringResource(text), icon, action, destructive)

    val main = buildList {
        when {
            !host.online -> add(a(R.string.pcview_menu_send_wol, Icons.Outlined.PowerSettingsNew, action = onWake))
            !host.paired -> {
                add(a(R.string.pcview_menu_pair_pc, Icons.Outlined.Link, action = onPair))
                add(a(R.string.pcview_menu_pair_pc_otp, Icons.Outlined.Password, action = onPairOtp))
            }
            else -> {
                if (host.runningGameId != 0) {
                    add(a(R.string.applist_menu_resume, Icons.Outlined.PlayArrow, action = onResume))
                    add(a(R.string.applist_menu_quit, Icons.Outlined.StopCircle, action = onQuit))
                }
                add(a(R.string.pcview_menu_app_list, Icons.Outlined.VisibilityOff, action = onAllApps))
            }
        }
    }
    val tools = buildList {
        if (host.online && !host.nvidiaServer) add(a(R.string.pcview_menu_open_management_page, Icons.Outlined.OpenInBrowser, action = onManagement))
        else add(a(R.string.pcview_menu_eol, Icons.Outlined.Info, action = onEol))
        add(a(R.string.pcview_menu_test_network, Icons.Outlined.NetworkCheck, action = onTestNetwork))
        add(a(R.string.pcview_menu_details, Icons.Outlined.Description, action = onDetails))
    }
    val danger = listOf(a(R.string.pcview_menu_delete_pc, Icons.Outlined.Delete, destructive = true, action = onDelete))
    ActionSheet(
        onDismiss = onDismiss,
        header = { HostSheetHeader(host) },
        groups = listOf(main, tools, danger),
    )
}

@Composable
private fun HostSheetHeader(host: Host) {
    val colors = MaterialTheme.colorScheme
    val locked = host.online && !host.paired
    val (fill, ink) = when {
        locked -> colors.tertiaryContainer to colors.onTertiaryContainer
        host.online -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Box(Modifier.size(48.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_computer_small), null, tint = ink, modifier = Modifier.size(24.dp))
    }
    Column(Modifier.padding(start = 16.dp)) {
        Text(host.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(
                when {
                    host.state == ComputerDetails.State.UNKNOWN -> R.string.host_status_checking
                    !host.online -> R.string.host_status_offline
                    locked -> R.string.host_status_not_paired
                    host.runningGameId != 0 -> R.string.host_status_running
                    else -> R.string.host_status_online
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

/** While pairing waits on the host: the PIN to type there (or what to do for a one-time PIN) */
@Composable
private fun PairingDialog(prompt: PairingPrompt) {
    AlertDialog(
        onDismissRequest = {},
        icon = { CircularProgressIndicator(Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.pair_pairing_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (prompt.pin != null) {
                    Text(stringResource(R.string.pair_pairing_msg), textAlign = TextAlign.Center)
                    Text(
                        prompt.pin,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Text(stringResource(R.string.pair_pairing_help), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.pair_otp_pairing_msg) + "\n\n" + stringResource(R.string.pair_otp_pairing_help), textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {},
    )
}

/** Pairing with a one-time PIN and passphrase set on the host */
@Composable
private fun OtpPairDialog(onDismiss: () -> Unit, onPair: (pin: String, passphrase: String) -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    val pinOk = pin.length == 4
    val phraseOk = passphrase.length >= 4
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pcview_menu_pair_pc_otp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = pin.isNotEmpty() && !pinOk,
                    supportingText = if (pin.isNotEmpty() && !pinOk) {{ Text(stringResource(R.string.pair_pin_length_msg)) }} else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.pair_passphrase_hint)) },
                    singleLine = true,
                    isError = passphrase.isNotEmpty() && !phraseOk,
                    supportingText = if (passphrase.isNotEmpty() && !phraseOk) {{ Text(stringResource(R.string.pair_passphrase_length_msg)) }} else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPair(pin, passphrase) }, enabled = pinOk && phraseOk) { Text(stringResource(R.string.proceed)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
