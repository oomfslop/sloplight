package app.sloplight.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The app's screens (outside the stream, which is an activity of its own) */
sealed interface Route : NavKey

/** The hosts: discovered and added PCs */
@Serializable
data object Hosts : Route

/** A host's apps; [showHidden] lists the hidden ones too */
@Serializable
data class Apps(
    val hostUuid: String,
    val hostName: String,
    val showHidden: Boolean = false,
    val newlyPaired: Boolean = false,
) : Route

/** A host added by its address (from a link: [address] filled in, with the link's query, and the host's [name]) */
@Serializable
data class AddHost(val address: String? = null, val name: String? = null) : Route

/** The settings: appearance, profiles and the streaming settings' categories */
@Serializable
data object Settings : Route

/** One category of the streaming settings, all of it */
@Serializable
data class SettingsCategory(val key: String) : Route

/** The settings profiles */
@Serializable
data object Profiles : Route

/** A profile's settings; no [profileUuid]: a new one */
@Serializable
data class EditProfile(val profileUuid: String? = null) : Route
