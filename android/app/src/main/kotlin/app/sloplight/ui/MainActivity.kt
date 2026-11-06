package app.sloplight.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.preference.PreferenceManager
import app.sloplight.data.HostsRepository
import app.sloplight.data.PendingPairing
import app.sloplight.ui.addhost.AddHostScreen
import app.sloplight.ui.apps.AppsScreen
import app.sloplight.ui.hosts.HostsScreen
import app.sloplight.ui.nav.AddHost
import app.sloplight.ui.nav.Apps
import app.sloplight.ui.nav.EditProfile
import app.sloplight.ui.nav.Hosts
import app.sloplight.ui.nav.Profiles
import app.sloplight.ui.nav.Settings
import app.sloplight.ui.theme.SloplightTheme
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.ShortcutTrampoline
import com.limelight.AppExtras
import com.limelight.Game
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.preferences.GlPreferences
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.profiles.ProfilesManager
import com.limelight.utils.ShortcutHelper
import com.limelight.utils.UiHelper
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The app outside the stream, in Compose: the hosts, a host's apps, adding
 * a host, the settings and profiles, one screen at a time (Navigation 3).
 * Streams run in [Game], an activity of their own.
 */
class MainActivity : ComponentActivity() {
    private var linkRoute by mutableStateOf<NavKey?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)
        setFirstRunDefaults()
        handleIntent(intent)
        // The service up early, and this device's key pair, so discovery has it when it needs it
        HostsRepository.get(this)
        Thread { AndroidCryptoProvider(this).clientCertificate }.start()

        setContent {
            SloplightTheme {
                GlRendererProbe()
                App(linkRoute = linkRoute, onLinkHandled = { linkRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // After a decoder crash: what happened and what to try
        UiHelper.showDecoderCrashDialog(this)
    }

    @Deprecated("The launcher file export still goes through startActivityForResult in ShortcutHelper")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            val uri = data?.data
            if (resultCode == Activity.RESULT_OK && uri != null) {
                ShortcutHelper.writeArtFileToUri(this, uri)
            } else {
                ShortcutHelper.artFileContentToExport = null
                if (resultCode == Activity.RESULT_CANCELED) Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** art:// links (adding a host, launching an app), and a pairing asked for by the host list's caller */
    private fun handleIntent(intent: Intent) {
        val hostname = intent.getStringExtra("hostname")
        val pin = intent.getStringExtra("pin")
        val passphrase = intent.getStringExtra("passphrase")
        if (hostname != null && pin != null && passphrase != null) {
            val port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT)
            PendingPairing.value = PendingPairing.Request(ComputerDetails.AddressTuple(hostname, if (port == -1) NvHTTP.DEFAULT_HTTP_PORT else port), pin, passphrase)
        }
        // A host's launcher shortcut (through ShortcutTrampoline): its apps, over the hosts
        val shortcutHost = intent.getStringExtra(AppExtras.UUID_EXTRA)
        if (shortcutHost != null && intent.data == null) {
            linkRoute = Apps(
                hostUuid = shortcutHost,
                hostName = intent.getStringExtra(AppExtras.NAME_EXTRA) ?: "",
                showHidden = intent.getBooleanExtra(AppExtras.SHOW_HIDDEN_APPS_EXTRA, false),
                newlyPaired = intent.getBooleanExtra(AppExtras.NEW_PAIR_EXTRA, false),
            )
            return
        }
        val data: Uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme != "art") return
        if (data.port == -1 && data.host == "launch") {
            startActivity(Intent(this, ShortcutTrampoline::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AppExtras.UUID_EXTRA, data.getQueryParameter("host_uuid"))
                putExtra(AppExtras.NAME_EXTRA, data.getQueryParameter("host_name"))
                putExtra(Game.EXTRA_APP_UUID, data.getQueryParameter("app_uuid"))
                putExtra(Game.EXTRA_APP_NAME, data.getQueryParameter("app_name"))
                putExtra(Game.EXTRA_APP_ID, data.getQueryParameter("app_id"))
            })
            finish()
            return
        }
        val server = data.authority ?: return
        val query = data.query
        linkRoute = AddHost(address = if (query.isNullOrEmpty()) server else "$server?$query", name = data.getQueryParameter("name"))
    }

    /**
     * The first run's settings: the frame rate the display can show (a virtual
     * display on the host matches it), the tallest 16:9 stream the screen shows
     * pixel for pixel, the bitrate for those; then the rest of the defaults.
     */
    private fun setFirstRunDefaults() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.contains("list_fps")) {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val maxHz = display.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f
            val fps = if (maxHz >= 118) "120" else if (maxHz >= 88) "90" else "60"
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val short = minOf(metrics.widthPixels, metrics.heightPixels)
            val resolution = when {
                short >= 2160 -> "3840x2160"
                short >= 1440 -> "2560x1440"
                short >= 1080 -> "1920x1080"
                else -> "1280x720"
            }
            prefs.edit()
                .putString("list_resolution", resolution)
                .putString("list_fps", fps)
                .putInt("seekbar_bitrate_kbps", PreferenceConfiguration.getDefaultBitrate(resolution, fps))
                .apply()
        }
        // The settings' defaults not stored yet (the switches and lists, as the old settings screen wrote them)
        app.sloplight.ui.settings.SettingsValues(
            app.sloplight.data.prefs.PrefsStore.default(this),
            PreferenceConfiguration.getDefaultSmallMode(this),
        ).persistDefaults(app.sloplight.ui.settings.SettingsCatalog.groups)
    }
}

/**
 * The GPU's renderer name, read once per system build (the decoder's choices
 * use it): a GL surface, 1 dp, only until it has been read.
 */
@Composable
private fun GlRendererProbe() {
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    val glPrefs = remember { GlPreferences.readPreferences(activity) }
    var needed by remember { mutableStateOf(glPrefs.savedFingerprint != Build.FINGERPRINT || glPrefs.glRenderer.isEmpty()) }
    if (!needed) return
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { context ->
            GLSurfaceView(context).apply {
                setRenderer(object : GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
                        glPrefs.glRenderer = gl.glGetString(GL10.GL_RENDERER)
                        glPrefs.savedFingerprint = Build.FINGERPRINT
                        glPrefs.writePreferences()
                        LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer)
                        post { needed = false }
                    }

                    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {}
                    override fun onDrawFrame(gl: GL10) {}
                })
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        },
    )
}

@Composable
private fun App(linkRoute: NavKey?, onLinkHandled: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current!!
    val backStack = rememberNavBackStack(Hosts)
    if (linkRoute != null) {
        backStack.add(linkRoute)
        onLinkHandled()
    }
    // The active profile's name on the profiles button (empty: none)
    var activeProfile by remember { mutableStateOf(ProfilesManager.getInstance().activeName) }
    DisposableEffect(Unit) {
        val listener = ProfilesManager.ProfileChangeListener {
            activity.runOnUiThread { activeProfile = ProfilesManager.getInstance().activeName }
        }
        ProfilesManager.getInstance().addListener(listener)
        onDispose { ProfilesManager.getInstance().removeListener(listener) }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else activity.finish() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Hosts> {
                HostsScreen(
                    onOpenApps = { host, newlyPaired, showHidden -> backStack.add(Apps(host.uuid, host.name, showHidden, newlyPaired)) },
                    onSettings = { backStack.add(Settings) },
                    onProfiles = { backStack.add(Profiles) },
                    onAddHost = { backStack.add(AddHost()) },
                    activeProfile = activeProfile,
                )
            }
            entry<Apps> { key ->
                AppsScreen(
                    hostUuid = key.hostUuid,
                    hostName = key.hostName,
                    showHidden = key.showHidden,
                    newlyPaired = key.newlyPaired,
                    onBack = { backStack.removeLastOrNull() },
                    onProfiles = { backStack.add(Profiles) },
                    activeProfile = activeProfile,
                )
            }
            entry<AddHost> { key ->
                AddHostScreen(onBack = { backStack.removeLastOrNull() }, linkAddress = key.address, linkName = key.name)
            }
            entry<Settings> {
                app.sloplight.ui.settings.SettingsScreen(onBack = { backStack.removeLastOrNull() })
            }
            entry<Profiles> {
                app.sloplight.ui.profiles.ProfilesScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onEdit = { uuid -> backStack.add(EditProfile(uuid)) },
                )
            }
            entry<EditProfile> { key ->
                app.sloplight.ui.profiles.EditProfileScreen(profileUuid = key.profileUuid, onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}
