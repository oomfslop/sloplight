package app.sloplight.ui.addhost

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * A host added by its address. From a link ([linkAddress] = address?pin=…),
 * it is offered with the link's name and added once confirmed.
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
    val emptyMessage = stringResource(R.string.addpc_enter_ip)
    var emptyError by remember { mutableStateOf(false) }

    LaunchedEffect(state) { if (state == AddState.Added) onBack() }
    LaunchedEffect(Unit) { if (!confirmLink) focus.requestFocus() }

    fun submit() {
        if (address.isBlank()) emptyError = true else viewModel.add(address.trim())
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.title_add_pc)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; emptyError = false },
                label = { Text(stringResource(R.string.ip_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_computer_small), null) },
                singleLine = true,
                isError = emptyError,
                supportingText = if (emptyError) {{ Text(emptyMessage) }} else null,
                enabled = state != AddState.Adding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Button(onClick = ::submit, enabled = state != AddState.Adding, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.add_pc_button))
            }
        }
    }

    if (state == AddState.Adding) {
        AlertDialog(
            onDismissRequest = {},
            icon = { LoadingIndicator(Modifier.size(48.dp)) },
            title = { Text(stringResource(R.string.title_add_pc)) },
            text = { Text(stringResource(R.string.msg_add_pc)) },
            confirmButton = {},
        )
    }
    (state as? AddState.Failed)?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(stringResource(R.string.conn_error_title)) },
            text = { Text(it.message) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(android.R.string.ok)) } },
        )
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
