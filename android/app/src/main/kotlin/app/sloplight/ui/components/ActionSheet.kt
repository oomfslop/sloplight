package app.sloplight.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** An action of a sheet; [checked] makes it a toggle (with a switch), [destructive] colors it as such */
@Immutable
data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val checked: Boolean? = null,
)

/**
 * The actions of one thing (a host, an app) as a bottom sheet: what it is at
 * the top, then its actions in blocks (each list in [groups] one block).
 * Picking one closes the sheet first, then acts.
 */
@Composable
fun ActionSheet(
    onDismiss: () -> Unit,
    header: @Composable () -> Unit,
    groups: List<List<SheetAction>>,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { header() }
            for (group in groups.filter { it.isNotEmpty() }) {
                Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                    group.forEachIndexed { i, action ->
                        val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        SegmentRow(
                            shapes = segmentShape(i, group.size),
                            onClick = {
                                if (action.checked != null) {
                                    action.onClick()
                                } else {
                                    scope.launch { state.hide() }.invokeOnCompletion {
                                        onDismiss()
                                        action.onClick()
                                    }
                                }
                            },
                            leadingContent = { Icon(action.icon, null, tint = tint, modifier = Modifier.size(24.dp)) },
                            trailingContent = action.checked?.let { c -> { Switch(checked = c, onCheckedChange = null) } },
                        ) {
                            Text(action.label, color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}
