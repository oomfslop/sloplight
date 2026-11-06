package app.sloplight.ui.apps

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sloplight.data.BoxArt
import app.sloplight.data.HostsRepository
import com.limelight.AppExtras
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.utils.CacheHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.StringReader

/** An app on the grid (the desk tile, "Apps & windows", is one with [AppExtras.DESK_APP_ID]) */
@Immutable
data class AppItem(val app: NvApp, val name: String, val running: Boolean, val hidden: Boolean) {
    val id: Int get() = app.appId
    val isDesk: Boolean get() = app.appId == AppExtras.DESK_APP_ID
}

sealed interface AppsEvent {
    /** The host went away or isn't paired any more: back to the hosts */
    data class Leave(val message: Int, val notPaired: Boolean) : AppsEvent
}

/**
 * A host's apps: from the cached list first, then the host's (polled while
 * the screen collects [apps]), with the running one and the hidden ones.
 */
class AppsViewModel(
    application: Application,
    val hostUuid: String,
    val showHidden: Boolean,
) : AndroidViewModel(application) {
    private val repo = HostsRepository.get(application)

    private val prefs = application.getSharedPreferences(AppExtras.HIDDEN_APPS_PREF_FILENAME, Context.MODE_PRIVATE)
    private val hiddenIds = MutableStateFlow(
        prefs.getStringSet(hostUuid, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
    )
    private val list = MutableStateFlow<List<NvApp>?>(null)
    private val runningId = MutableStateFlow(0)
    private var lastRaw: String? = null
    private var deskAllowed = false

    private val _apps = MutableStateFlow<List<AppItem>?>(null)
    /** null until a list is known (from the cache or the host) */
    val apps: StateFlow<List<AppItem>?> = _apps.asStateFlow()

    private val _events = Channel<AppsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The host's record (what starting and quitting need) */
    val computer: ComputerDetails? get() = repo.details(hostUuid)
    val runningAppId: Int get() = runningId.value

    var boxArt: BoxArt? = null
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val binder = repo.awaitBinder()
            val computer = binder.getComputer(hostUuid) ?: run {
                _events.trySend(AppsEvent.Leave(R.string.lost_connection, false)); return@launch
            }
            boxArt = BoxArt(application, computer, binder.uniqueId)
            deskAllowed = allowsDesk(computer)
            // The cached list at once, the host's when polling brings it
            try {
                val raw = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(application.cacheDir, "applist", hostUuid))
                lastRaw = raw
                list.value = NvHTTP.getAppListByReader(StringReader(raw))
                LimeLog.info("Loaded applist from cache")
            } catch (e: Exception) {
                LimeLog.info("Loading applist from the network")
            }
            rebuild()
        }
    }

    private fun allowsDesk(c: ComputerDetails) = c.permission != -1 && (c.permission and (1 shl 30)) != 0

    private fun rebuild() {
        val apps = list.value ?: return
        val hidden = hiddenIds.value
        val running = runningId.value
        val items = buildList {
            if (deskAllowed) {
                val desk = NvApp(getApplication<Application>().getString(R.string.applist_desk_tile), null, AppExtras.DESK_APP_ID, false)
                desk.setAppIndex(-1)
                add(AppItem(desk, desk.appName, false, false))
            }
            apps.sortedWith(compareBy<NvApp> { it.appIndex }.thenBy { it.appName.lowercase() }).forEach { app ->
                val isHidden = app.appId in hidden
                if (showHidden || !isHidden) add(AppItem(app, app.appName, app.appId == running, isHidden))
            }
        }
        _apps.value = items
    }

    /** Polls the host's apps and state while collected (the screen, while it is shown) */
    suspend fun poll() {
        val binder = repo.awaitBinder()
        val computer = binder.getComputer(hostUuid) ?: return
        val poller = binder.createAppListPoller(computer)
        poller.start()
        try {
            repo.host(hostUuid).collect { d -> onUpdate(d) }
        } finally {
            poller.stop()
        }
    }

    /** Asks the host for its apps now (after quitting one) */
    fun pollNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val binder = repo.awaitBinder()
            binder.getComputer(hostUuid)?.let { binder.createAppListPoller(it).pollNow() }
        }
    }

    private suspend fun onUpdate(d: ComputerDetails) {
        when {
            d.state == ComputerDetails.State.OFFLINE -> {
                _events.send(AppsEvent.Leave(R.string.lost_connection, false)); return
            }
            d.state == ComputerDetails.State.ONLINE && d.pairState != PairingManager.PairState.PAIRED -> {
                _events.send(AppsEvent.Leave(R.string.scut_not_paired, true)); return
            }
        }
        var changed = false
        if (allowsDesk(d) != deskAllowed) { deskAllowed = allowsDesk(d); changed = true }
        if (d.runningGameId != runningId.value) { runningId.value = d.runningGameId; changed = true }
        val raw = d.rawAppList
        if (raw != null && raw != lastRaw) {
            try {
                list.value = withContext(Dispatchers.Default) { NvHTTP.getAppListByReader(StringReader(raw)) }
                lastRaw = raw
                changed = true
            } catch (e: Exception) {
                LimeLog.warning("Bad applist from the host: $e")
            }
        }
        if (changed) rebuild()
    }

    fun setHidden(app: AppItem, hidden: Boolean) {
        hiddenIds.update { if (hidden) it + app.id else it - app.id }
        prefs.edit().putStringSet(hostUuid, hiddenIds.value.map(Int::toString).toSet()).apply()
        rebuild()
    }
}
