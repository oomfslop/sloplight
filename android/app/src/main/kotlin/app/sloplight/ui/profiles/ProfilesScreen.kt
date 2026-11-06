package app.sloplight.ui.profiles

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.profiles.ProfilesManager
import java.util.UUID

/** A profile as listed */
@Immutable
data class ProfileItem(val uuid: String, val name: String, val modifiedUtc: Long, val active: Boolean)

/** The profiles, kept up to date with ProfilesManager */
@Composable
fun rememberProfiles(): State<List<ProfileItem>> {
    val state = remember { mutableStateOf(profilesSnapshot()) }
    DisposableEffect(Unit) {
        val listener = ProfilesManager.ProfileChangeListener { state.value = profilesSnapshot() }
        ProfilesManager.getInstance().addListener(listener)
        state.value = profilesSnapshot()
        onDispose { ProfilesManager.getInstance().removeListener(listener) }
    }
    return state
}

private fun profilesSnapshot(): List<ProfileItem> {
    val manager = ProfilesManager.getInstance()
    val active = manager.active?.uuid
    return manager.profiles.map { ProfileItem(it.uuid.toString(), it.name ?: "", it.modifiedUtc, it.uuid == active) }
}

/** The settings profiles: pick the one in use, edit, delete, make a new one */
@Composable
fun ProfilesScreen(onBack: () -> Unit, onEdit: (profileUuid: String?) -> Unit) {
    val context = LocalContext.current
    val profiles by rememberProfiles()
    var deleting by remember { mutableStateOf<ProfileItem?>(null) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val container = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = container,
        topBar = {
            LargeFlexibleTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container),
                title = { Text(stringResource(R.string.profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.profile_manager_new_profile)) },
                icon = { Icon(painterResource(R.drawable.ic_add_plain), contentDescription = null) },
                onClick = { onEdit(null) },
                expanded = fabExpanded,
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + 16.dp,
                end = padding.calculateEndPadding(direction) + 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 112.dp,
            ),
        ) {
            item(key = "description", contentType = "description") {
                Text(
                    stringResource(R.string.profile_manager_profiles_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
                )
            }
            if (profiles.isEmpty()) {
                item(key = "empty", contentType = "empty") { EmptyProfiles() }
            }
            itemsIndexed(profiles, key = { _, p -> p.uuid }, contentType = { _, _ -> "profile" }) { index, profile ->
                ProfileRow(
                    profile = profile,
                    index = index,
                    count = profiles.size,
                    onToggleActive = { toggleActive(context, profile) },
                    onEdit = { onEdit(profile.uuid) },
                    onDelete = { deleting = profile },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.profile_manager_delete_profile)) },
            text = { Text(stringResource(R.string.profile_manager_confirm_profile_deleteion, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val manager = ProfilesManager.getInstance()
                    manager.delete(UUID.fromString(profile.uuid))
                    manager.save(context)
                    Toast.makeText(
                        context,
                        context.getString(R.string.profile_manager_profile_deleted, profile.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.profile_manager_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The one in use off, or this one in use */
private fun toggleActive(context: Context, profile: ProfileItem) {
    val manager = ProfilesManager.getInstance()
    if (profile.active) {
        manager.setActive(null)
        Toast.makeText(context, R.string.profile_manager_deactivated_profile, Toast.LENGTH_SHORT).show()
    } else {
        manager.setActive(UUID.fromString(profile.uuid))
        Toast.makeText(
            context,
            context.getString(R.string.profile_manager_activated_profile, profile.name),
            Toast.LENGTH_SHORT,
        ).show()
    }
    manager.save(context)
}

@Composable
private fun ProfileRow(
    profile: ProfileItem,
    index: Int,
    count: Int,
    onToggleActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val modified = remember(profile.modifiedUtc) {
        DateUtils.getRelativeTimeSpanString(profile.modifiedUtc, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString()
    }
    SegmentedListItem(
        selected = profile.active,
        onClick = onEdit,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = modifier.padding(bottom = if (index < count - 1) ListItemDefaults.SegmentedGap else 0.dp),
        leadingContent = { RadioButton(selected = profile.active, onClick = onToggleActive) },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit_profile_description))
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete_profile_description))
                }
            }
        },
        supportingContent = { Text(modified) },
    ) {
        Text(
            profile.name,
            style = MaterialTheme.typography.titleMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** No profiles yet: the profiles icon in a soft burst, and how to make one */
@Composable
private fun EmptyProfiles() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val shape = MaterialShapes.SoftBurst.toShape()
        Box(
            modifier = Modifier.size(120.dp).background(MaterialTheme.colorScheme.secondaryContainer, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_profiles),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            stringResource(R.string.profile_manager_no_profiles_yet),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.profile_manager_tap_create_profile),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
