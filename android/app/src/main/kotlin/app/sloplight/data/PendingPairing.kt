package app.sloplight.data

import com.limelight.nvstream.http.ComputerDetails
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A pairing a link asked for (art://host?pin=…&passphrase=…): done by the
 * host list once that host is online at this address.
 */
object PendingPairing {
    data class Request(val address: ComputerDetails.AddressTuple, val pin: String, val passphrase: String)

    private val state = MutableStateFlow<Request?>(null)
    val flow = state

    var value: Request?
        get() = state.value
        set(v) { state.value = v }
}
