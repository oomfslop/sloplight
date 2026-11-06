package app.sloplight.ui.hosts

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                actions = {
                    if (activity?.packageManager?.hasSystemFeature("amazon.hardware.fire_tv") != true) {
                        IconButton(onClick = { activity?.let(HelpLauncher::launchSetupGuide) }) {
                            Icon(painterResource(R.drawable.ic_help), stringResource(R.string.help))
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.action_settings))
                    }
                    FilledTonalIconButton(onClick = onAddHost) {
                        Icon(painterResource(R.drawable.ic_add_plain), stringResource(R.string.title_add_pc))
                    }
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
        val list = hosts
        if (list.isNullOrEmpty()) {
            Searching(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.uuid }) { host ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        HostCard(
                            host = host,
                            onClick = {
                                when {
                                    !host.online -> menu = true
                                    !host.paired -> viewModel.pair(host)
                                    else -> onOpenApps(host, false, false)
                                }
                            },
                            onLongClick = { menu = true },
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

/** Nothing found yet: the Expressive loading indicator and what is going on */
@Composable
private fun Searching(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingIndicator(Modifier.size(96.dp))
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
 * A host: its monitor in a cookie shape, colored by its state (primary when it
 * can be streamed, tertiary while it waits to be paired, muted when it can't
 * be reached), a badge for the lock or the offline state, a wavy ring while
 * it is being reached, its name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(host: Host, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val locked = host.online && !host.paired
    val (fill, ink) = when {
        locked -> colors.tertiaryContainer to colors.onTertiaryContainer
        host.online -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button, onClickLabel = host.name),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 18.dp),
        ) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(88.dp).clip(cookie).background(fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_computer_small), null, tint = ink,
                        modifier = Modifier.size(44.dp).alpha(if (host.state == ComputerDetails.State.OFFLINE) 0.6f else 1f),
                    )
                }
                if (host.state == ComputerDetails.State.UNKNOWN) {
                    CircularWavyProgressIndicator(Modifier.size(104.dp))
                }
                val badge = when {
                    host.state == ComputerDetails.State.OFFLINE -> Triple(R.drawable.ic_pc_offline, colors.surfaceContainerHighest, colors.onSurfaceVariant)
                    locked -> Triple(R.drawable.ic_lock, colors.tertiary, colors.onTertiary)
                    else -> null
                }
                if (badge != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(34.dp)
                            .background(colors.surfaceContainer, CircleShape)
                            .padding(3.dp)
                            .background(badge.second, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(badge.first), null, tint = badge.third, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                host.name,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onSurface.copy(alpha = if (host.online) 1f else 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A host's actions, as its state allows (what PcView's context menu offered) */
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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = MaterialTheme.shapes.large) {
        @Composable
        fun item(text: Int, action: () -> Unit) =
            DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { onDismiss(); action() })

        val state = stringResource(
            when (host.state) {
                ComputerDetails.State.ONLINE -> R.string.pcview_menu_header_online
                ComputerDetails.State.OFFLINE -> R.string.pcview_menu_header_offline
                else -> R.string.pcview_menu_header_unknown
            }
        )
        Text(
            "${host.name} · $state",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            !host.online -> {
                item(R.string.pcview_menu_send_wol, onWake)
                item(R.string.pcview_menu_eol, onEol)
            }
            !host.paired -> {
                item(R.string.pcview_menu_pair_pc_otp, onPairOtp)
                item(R.string.pcview_menu_pair_pc, onPair)
                if (host.nvidiaServer) item(R.string.pcview_menu_eol, onEol) else item(R.string.pcview_menu_open_management_page, onManagement)
            }
            else -> {
                if (host.runningGameId != 0) {
                    item(R.string.applist_menu_resume, onResume)
                    item(R.string.applist_menu_quit, onQuit)
                }
                if (host.nvidiaServer) item(R.string.pcview_menu_eol, onEol) else item(R.string.pcview_menu_open_management_page, onManagement)
                item(R.string.pcview_menu_app_list, onAllApps)
            }
        }
        item(R.string.pcview_menu_test_network, onTestNetwork)
        item(R.string.pcview_menu_delete_pc, onDelete)
        item(R.string.pcview_menu_details, onDetails)
    }
}

/** While pairing waits on the host: the PIN to type there (or what to do for a one-time PIN) */
@Composable
private fun PairingDialog(prompt: PairingPrompt) {
    AlertDialog(
        onDismissRequest = {},
        icon = { LoadingIndicator(Modifier.size(48.dp)) },
        title = { Text(stringResource(R.string.pair_pairing_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (prompt.pin != null) {
                    Text(stringResource(R.string.pair_pairing_msg), textAlign = TextAlign.Center)
                    Text(
                        prompt.pin,
                        style = MaterialTheme.typography.displayMediumEmphasized,
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
