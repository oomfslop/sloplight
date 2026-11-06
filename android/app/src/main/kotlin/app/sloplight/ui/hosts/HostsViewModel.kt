package app.sloplight.ui.hosts

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sloplight.data.Host
import app.sloplight.data.HostsRepository
import com.limelight.AppExtras
import com.limelight.R
import com.limelight.binding.PlatformBinding
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.utils.ServerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException

/** Something for the host list to tell or do once */
sealed interface HostsEvent {
    data class Message(val text: String) : HostsEvent
    /** Paired (or was already): its apps */
    data class OpenApps(val host: Host, val newlyPaired: Boolean) : HostsEvent
}

/** The PIN pairing is waiting for the user to enter on the host */
data class PairingPrompt(val hostName: String, val pin: String?, val otp: Boolean)

class HostsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = HostsRepository.get(application)
    val hosts: StateFlow<List<Host>?> = repo.hosts

    private val _pairing = MutableStateFlow<PairingPrompt?>(null)
    /** Shown while a pairing waits on the host */
    val pairing: StateFlow<PairingPrompt?> = _pairing.asStateFlow()

    private val _events = Channel<HostsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun str(@StringRes id: Int) = getApplication<Application>().getString(id)
    private fun say(text: String?) {
        if (text != null) _events.trySend(HostsEvent.Message(text))
    }

    fun details(uuid: String): ComputerDetails? = repo.details(uuid)

    /** Pairs with the host: a PIN shown here to type on the host, or [otp] + [passphrase] from the host */
    fun pair(host: Host, otp: String? = null, passphrase: String? = null) {
        val computer = repo.details(host.uuid) ?: return
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            say(str(R.string.pair_pc_offline)); return
        }
        say(str(R.string.pairing))
        viewModelScope.launch {
            var message: String? = null
            var success = false
            try {
                repo.withPollingPaused {
                    withContext(Dispatchers.IO) {
                        val binder = repo.awaitBinder()
                        val http = NvHTTP(
                            ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                            binder.uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(getApplication()),
                        )
                        if (http.pairState == PairState.PAIRED) {
                            success = true
                        } else {
                            val pin = otp ?: PairingManager.generatePinString()
                            _pairing.value = PairingPrompt(computer.name, if (passphrase == null) pin else null, passphrase != null)
                            val pm = http.pairingManager
                            when (pm.pair(http.getServerInfo(true), pin, passphrase)) {
                                PairState.PIN_WRONG -> message = str(R.string.pair_incorrect_pin)
                                PairState.FAILED -> message = str(if (computer.runningGameId != 0) R.string.pair_pc_ingame else R.string.pair_fail)
                                PairState.ALREADY_IN_PROGRESS -> message = str(R.string.pair_already_in_progress)
                                PairState.PAIRED -> {
                                    success = true
                                    // This certificate for HTTPS from now on; the pair state read again
                                    binder.getComputer(computer.uuid)?.serverCert = pm.pairedCert
                                    binder.invalidateStateForComputer(computer.uuid)
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            } catch (e: UnknownHostException) {
                message = str(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                message = str(R.string.error_404)
            } catch (e: XmlPullParserException) {
                message = e.message
            } catch (e: IOException) {
                message = e.message
            } finally {
                _pairing.value = null
            }
            say(message)
            if (success) _events.trySend(HostsEvent.OpenApps(host, newlyPaired = true))
        }
    }

    fun unpair(host: Host) {
        val computer = repo.details(host.uuid) ?: return
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            say(str(R.string.error_pc_offline)); return
        }
        say(str(R.string.unpairing))
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val http = NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        repo.awaitBinder().uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(getApplication()),
                    )
                    if (http.pairState == PairState.PAIRED) {
                        http.unpair()
                        str(if (http.pairState == PairState.NOT_PAIRED) R.string.unpair_success else R.string.unpair_fail)
                    } else {
                        str(R.string.unpair_error)
                    }
                }
            } catch (e: UnknownHostException) {
                str(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) {
                str(R.string.error_404)
            } catch (e: Exception) {
                e.message
            }
            say(message)
        }
    }

    fun wake(host: Host) {
        val computer = repo.details(host.uuid) ?: return
        if (computer.state == ComputerDetails.State.ONLINE) { say(str(R.string.wol_pc_online)); return }
        if (computer.macAddress == null) { say(str(R.string.wol_no_mac)); return }
        viewModelScope.launch(Dispatchers.IO) {
            say(try {
                WakeOnLanSender.sendWolPacket(computer)
                str(R.string.wol_waking_msg)
            } catch (e: IOException) {
                str(R.string.wol_fail)
            })
        }
    }

    /** Forgets the host, its box art and its hidden apps (shortcuts are the screen's, it has the activity) */
    fun forget(host: Host) {
        val computer = repo.details(host.uuid) ?: return
        repo.forget(computer)
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            DiskAssetLoader(app).deleteAssetsForComputer(computer.uuid)
            app.getSharedPreferences(AppExtras.HIDDEN_APPS_PREF_FILENAME, android.content.Context.MODE_PRIVATE)
                .edit().remove(computer.uuid).apply()
        }
    }
}
