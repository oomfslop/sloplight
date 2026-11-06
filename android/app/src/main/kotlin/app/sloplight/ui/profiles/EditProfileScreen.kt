package app.sloplight.ui.profiles

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.sloplight.data.prefs.PrefsStore
import app.sloplight.ui.settings.SettingsContent
import com.limelight.R
import com.limelight.profiles.ProfilesManager
import com.limelight.profiles.SettingsProfile
import java.util.UUID

/**
 * A profile's settings, edited in memory and kept only on save. No
 * [profileUuid]: a new profile, starting from the app's current settings.
 */
@Composable
fun EditProfileScreen(profileUuid: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val profile: SettingsProfile? = remember(profileUuid) {
        profileUuid?.let { id -> ProfilesManager.getInstance().profiles.firstOrNull { it.uuid.toString() == id } }
    }
    if (profileUuid != null && profile == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, R.string.profile_manager_profile_not_found, Toast.LENGTH_SHORT).show()
            onBack()
        }
        return
    }

    val global = remember { PrefsStore.default(context) }
    val store = remember(profileUuid) { PrefsStore.inMemory(if (profile != null) profile.options else global.all()) }
    // The name of an existing profile is saved right away; a new one's waits for the save
    var name by remember(profileUuid) { mutableStateOf(profile?.name) }
    var renaming by remember { mutableStateOf(false) }

    val title = when {
        profile != null -> stringResource(R.string.profile_manager_edit_profile_with, name ?: "")
        name != null -> stringResource(R.string.profile_manager_new_profile_with, name ?: "")
        else -> stringResource(R.string.profile_manager_new_profile)
    }

    val save = {
        val manager = ProfilesManager.getInstance()
        val options: MutableMap<String, Any?> = store.snapshot()
        @Suppress("UNCHECKED_CAST")
        val typed = options as MutableMap<String, Any>
        val displayName: String
        if (profile != null) {
            profile.options = typed
            profile.modifiedUtc = System.currentTimeMillis()
            displayName = profile.name
            manager.update(profile)
        } else {
            var newName = name?.trim()
            if (newName.isNullOrEmpty()) {
                newName = context.getString(R.string.profile_manager_profile) + (manager.profiles.size + 1)
            }
            val now = System.currentTimeMillis()
            manager.add(SettingsProfile(UUID.randomUUID(), newName, now, now, typed))
            displayName = newName
        }
        if (manager.save(context)) {
            Toast.makeText(context, context.getString(R.string.profile_manager_profile_saved, displayName), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.profile_manager_failed_to_save, Toast.LENGTH_LONG).show()
        }
        onBack()
    }

    SettingsContent(
        store = store,
        title = title,
        onBack = onBack,
        profileBase = global,
        actions = {
            IconButton(onClick = { renaming = true }) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.rename))
            }
            IconButton(onClick = save) {
                Icon(painterResource(R.drawable.ic_check), stringResource(R.string.save))
            }
        },
    )

    if (renaming) {
        RenameDialog(
            initial = name ?: "",
            onDismiss = { renaming = false },
            onRename = { newName ->
                renaming = false
                if (newName.isEmpty()) {
                    Toast.makeText(context, R.string.profile_manager_name_cannot_be_blank, Toast.LENGTH_SHORT).show()
                } else {
                    name = newName
                    if (profile != null) {
                        profile.name = newName
                        profile.modifiedUtc = System.currentTimeMillis()
                        ProfilesManager.getInstance().update(profile)
                    }
                }
            },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_manager_edit_profile_name)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(field.text.trim()) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
