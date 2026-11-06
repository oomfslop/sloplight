package app.sloplight.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Immutable
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.computers.ComputerManagerService.ComputerManagerBinder
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** What a screen shows of a host (immutable: the service's ComputerDetails changes in place) */
@Immutable
data class Host(
    val uuid: String,
    val name: String,
    val state: ComputerDetails.State,
    val paired: Boolean,
    val runningGameId: Int,
    val nvidiaServer: Boolean,
    val hasMac: Boolean,
    val vDisplaySupported: Boolean,
    val managementUrl: String?,
) {
    val online: Boolean get() = state == ComputerDetails.State.ONLINE

    companion object {
        fun of(d: ComputerDetails) = Host(
            uuid = d.uuid,
            name = d.name ?: "",
            state = d.state ?: ComputerDetails.State.UNKNOWN,
            paired = d.pairState == PairingManager.PairState.PAIRED,
            runningGameId = d.runningGameId,
            nvidiaServer = d.nvidiaServer,
            hasMac = d.macAddress != null,
            vDisplaySupported = d.vDisplaySupported,
            managementUrl = d.activeAddress?.let { "https://" + it.address + ":" + (d.guessExternalPort() + 1) },
        )
    }
}

/**
 * The hosts, from the computer manager service (discovery, polling, the
 * saved hosts), for the whole app: bound once, polled while a screen
 * collects [hosts] (the service polls nothing when nobody looks).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostsRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _binder = MutableStateFlow<ComputerManagerBinder?>(null)
    /** The service, once it is ready (null until then) */
    val binder: StateFlow<ComputerManagerBinder?> = _binder.asStateFlow()

    private val details = MutableStateFlow<Map<String, ComputerDetails>>(emptyMap())
    private val paused = MutableStateFlow(0)

    init {
        app.bindService(Intent(app, ComputerManagerService::class.java), object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as ComputerManagerBinder
                // (waitForReady blocks until the saved hosts are loaded)
                Thread {
                    binder.waitForReady()
                    _binder.value = binder
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                _binder.value = null
            }
        }, Context.BIND_AUTO_CREATE)
    }

    /** The service's updates, while someone collects them (and pairing doesn't pause them) */
    private val updates: Flow<ComputerDetails> = combine(_binder.filterNotNull(), paused) { b, p -> b to p }
        .flatMapLatest { (binder, pausedCount) ->
            if (pausedCount > 0) emptyFlow() else callbackFlow {
                binder.startPolling(ComputerManagerListener { trySend(it) })
                awaitClose { binder.stopPolling() }
            }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000))

    /** The hosts by name, kept up to date while collected (null: none heard of yet) */
    val hosts: StateFlow<List<Host>?> = channelFlow {
        launch { updates.collect { d -> details.update { it + (d.uuid to d) } } }
        details.collect { all -> send(all.values.map(Host::of).sortedBy { it.name.lowercase() }) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000), null)

    /** A host's updates (its running app, its state) while collected */
    fun host(uuid: String): Flow<ComputerDetails> = updates.filter { it.uuid == uuid }

    /** The service's own record of a host (what actions use) */
    fun details(uuid: String): ComputerDetails? = details.value[uuid] ?: _binder.value?.getComputer(uuid)

    /** The service, waiting for it */
    suspend fun awaitBinder(): ComputerManagerBinder = _binder.filterNotNull().first()

    /** Polling stopped (and waited out) while [block] talks to a host, as pairing must */
    suspend fun <T> withPollingPaused(block: suspend () -> T): T {
        paused.update { it + 1 }
        try {
            val binder = awaitBinder()
            withContext(Dispatchers.IO) { binder.waitForPollingStopped() }
            return block()
        } finally {
            paused.update { it - 1 }
        }
    }

    /** Forgets a host */
    fun forget(computer: ComputerDetails) {
        _binder.value?.removeComputer(computer)
        details.update { it - computer.uuid }
    }

    companion object {
        @Volatile private var instance: HostsRepository? = null

        fun get(context: Context): HostsRepository =
            instance ?: synchronized(this) { instance ?: HostsRepository(context).also { instance = it } }
    }
}
