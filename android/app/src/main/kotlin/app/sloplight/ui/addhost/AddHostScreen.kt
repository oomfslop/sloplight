package app.sloplight.ui.addhost

import android.app.Application
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sloplight.data.HostsRepository
import app.sloplight.data.PendingPairing
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Wifi
import app.sloplight.ui.components.SectionHeading
import app.sloplight.ui.components.SegmentRow
import app.sloplight.ui.components.SegmentedColumn
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Adding a host: idle, in progress, failed with a message, done */
sealed interface AddState {
    data object Idle : AddState
    data object Adding : AddState
    data class Failed(val message: String) : AddState
    data object Added : AddState
}

class AddHostViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = HostsRepository.get(application)
    private val _state = MutableStateFlow<AddState>(AddState.Idle)
    val state = _state.asStateFlow()

    fun dismissError() { _state.value = AddState.Idle }

    /** Adds the host at [raw] (an address, address:port, an IPv6 literal, or address?pin=…&passphrase=… from a link) */
    fun add(raw: String) {
        if (_state.value == AddState.Adding) return
        val app = getApplication<Application>()
        _state.value = AddState.Adding
        viewModelScope.launch {
            val uri = parse(raw)
            val result = withContext(Dispatchers.IO) {
                if (uri?.host.isNullOrEmpty()) return@withContext AddState.Failed(app.getString(R.string.addpc_unknown_host))
                val host = uri!!.host!!
                val port = if (uri.port == -1) NvHTTP.DEFAULT_HTTP_PORT else uri.port
                val added = try {
                    val details = ComputerDetails().apply { manualAddress = ComputerDetails.AddressTuple(host, port) }
                    repo.awaitBinder().addComputerBlocking(details)
                } catch (e: IllegalArgumentException) {
                    // (OkHttp: a host name that doesn't canonicalize)
                    return@withContext AddState.Failed(app.getString(R.string.addpc_unknown_host))
                }
                when {
                    added -> {
                        val pin = uri.getQueryParameter("pin")
                        val passphrase = uri.getQueryParameter("passphrase")
                        if (pin != null && passphrase != null) {
                            PendingPairing.value = PendingPairing.Request(ComputerDetails.AddressTuple(host, port), pin, passphrase)
                        }
                        AddState.Added
                    }
                    wrongSubnet(host) -> AddState.Failed(app.getString(R.string.addpc_wrong_sitelocal))
                    else -> {
                        // (a few seconds: whether the ports are blocked on this network)
                        val test = MoonBridge.testClientConnectivity(
                            ServerHelper.CONNECTION_TEST_SERVER, 443,
                            MoonBridge.ML_PORT_FLAG_TCP_47984 or MoonBridge.ML_PORT_FLAG_TCP_47989,
                        )
                        val blocked = test != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && test != 0
                        AddState.Failed(app.getString(if (blocked) R.string.nettest_text_blocked else R.string.addpc_fail))
                    }
                }
            }
            _state.value = result
        }
    }

    private fun parse(raw: String): Uri? {
        // 127.0.0.1:47989, [::1], [::1]:47989, 127.0.0.1; then ::1 as an IPv6 literal
        Uri.parse("art://$raw").takeIf { !it.host.isNullOrEmpty() }?.let { return it }
        return Uri.parse("art://[$raw]").takeIf { !it.host.isNullOrEmpty() }
    }

    /** A private IPv4 address on no subnet of this device's */
    private fun wrongSubnet(address: String): Boolean = try {
        val target = InetAddress.getByName(address)
        if (target !is Inet4Address || !target.isSiteLocalAddress) false else {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.interfaceAddresses }.none { a ->
                val local = a.address
                local is Inet4Address && local.isSiteLocalAddress && (0 until a.networkPrefixLength.toInt()).all { i ->
                    (local.address[i / 8].toInt() and (1 shl (i % 8))) == (target.address[i / 8].toInt() and (1 shl (i % 8)))
                }
            }
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * A host added by its address, as the journal's forms are: the address typed
 * large in a hero card (what is happening shows in the card too: looking for
 * the PC, or why it couldn't be added), how hosts are found under it, and
 * "Add PC" as the floating button. From a link ([linkAddress] =
 * address?pin=…), it is offered with the link's name and added once confirmed.
 */
@Composable
fun AddHostScreen(
    onBack: () -> Unit,
    linkAddress: String? = null,
    linkName: String? = null,
    viewModel: AddHostViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(linkAddress?.substringBefore('?') ?: "") }
    var confirmLink by rememberSaveable { mutableStateOf(linkAddress != null && linkAddress.contains('?')) }
    val focus = remember { FocusRequester() }
    var emptyError by remember { mutableStateOf(false) }
    val adding = state == AddState.Adding

    LaunchedEffect(state) { if (state == AddState.Added) onBack() }
    LaunchedEffect(Unit) { if (!confirmLink) focus.requestFocus() }

    fun submit() {
        if (address.isBlank()) emptyError = true else viewModel.add(address.trim())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_pc_button)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!adding) submit() },
                expanded = true,
                icon = { Icon(Icons.Outlined.Check, null) },
                text = { Text(stringResource(R.string.add_pc_button)) },
                modifier = Modifier.imePadding(),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
        ) {
            AddressHero(
                address = address,
                onAddress = { address = it; emptyError = false; if (state is AddState.Failed) viewModel.dismissError() },
                onDone = ::submit,
                enabled = !adding,
                adding = adding,
                error = when {
                    emptyError -> stringResource(R.string.addpc_enter_ip)
                    state is AddState.Failed -> (state as AddState.Failed).message
                    else -> null
                },
                focus = focus,
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                SectionHeading(stringResource(R.string.addpc_how_title))
                SegmentedColumn(
                    listOf<@Composable () -> Unit>(
                        { Tip(Icons.Outlined.Wifi, R.string.addpc_tip_lan_title, R.string.addpc_tip_lan) },
                        { Tip(Icons.Outlined.Language, R.string.addpc_tip_remote_title, R.string.addpc_tip_remote) },
                        { Tip(Icons.Outlined.Link, R.string.addpc_tip_link_title, R.string.addpc_tip_link) },
                    )
                )
            }
        }
    }

    if (confirmLink && linkAddress != null) {
        val server = linkAddress.substringBefore('?')
        val shown = if (!linkName.isNullOrEmpty()) "$linkName ($server)" else server
        AlertDialog(
            onDismissRequest = { confirmLink = false },
            title = { Text(stringResource(R.string.pair_pc_confirm_title)) },
            text = { Text(stringResource(R.string.pair_pc_confirm_message, shown)) },
            confirmButton = { TextButton(onClick = { confirmLink = false; viewModel.add(linkAddress) }) { Text(stringResource(R.string.proceed)) } },
            dismissButton = { TextButton(onClick = { confirmLink = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The hero: a tinted card with the address typed large; while the PC is
 * looked for, a progress bar; when it couldn't be added, why, in the card.
 */
@Composable
private fun AddressHero(
    address: String,
    onAddress: (String) -> Unit,
    onDone: () -> Unit,
    enabled: Boolean,
    adding: Boolean,
    error: String?,
    focus: FocusRequester,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (error != null) colors.error else colors.primary
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = tint.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Box {
            // A quiet shape in the corner, as the journal's hero cards have
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 48.dp, y = (-48).dp)
                    .requiredSize(140.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(tint.copy(alpha = 0.12f))
            )
            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                Text(stringResource(R.string.addpc_address_label), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val style = MaterialTheme.typography.headlineLarge.copy(color = colors.onSurface)
                BasicTextField(
                    value = address,
                    onValueChange = onAddress,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(tint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { field ->
                        Box {
                            if (address.isEmpty()) Text("192.168.1.20", style = style.copy(color = colors.onSurfaceVariant.copy(alpha = 0.5f)))
                            field()
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.addpc_address_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                AnimatedVisibility(visible = adding, enter = fadeIn() + expandVertically(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(top = 16.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.msg_add_pc), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                AnimatedVisibility(visible = error != null && !adding, enter = fadeIn() + expandVertically(), exit = shrinkVertically() + fadeOut()) {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = colors.error, modifier = Modifier.size(20.dp))
                        Text(error ?: "", style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** How hosts are found: an icon, what, how */
@Composable
private fun Tip(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, text: Int) {
    SegmentRow(
        shapes = RectangleShape,
        leadingContent = { Icon(icon, null) },
        supportingContent = { Text(stringResource(text)) },
    ) { Text(stringResource(title)) }
}
