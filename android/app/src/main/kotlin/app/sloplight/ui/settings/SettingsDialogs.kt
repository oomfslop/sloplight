package app.sloplight.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.limelight.R

/** Shows the controller's current dialog */
@Composable
internal fun SettingsDialogHost(controller: SettingsController) {
    val dismiss = { controller.dialog = null }
    when (val d = controller.dialog) {
        null -> Unit
        is SettingsDialog.Choose -> ChoiceDialog(
            setting = d.setting,
            list = controller.choiceList(d.setting),
            selected = controller.values.choice(d.setting),
            onSelect = { controller.onChoice(d.setting, it) },
            onDismiss = dismiss,
        )
        is SettingsDialog.Slide -> SliderDialog(
            setting = d.setting,
            initial = controller.values.slider(d.setting),
            onConfirm = { controller.onSlider(d.setting, it) },
            onDismiss = dismiss,
        )
        is SettingsDialog.Type -> TextDialog(
            setting = d.setting,
            initial = controller.values.string(d.setting.key, d.setting.default) ?: d.setting.default,
            onConfirm = { controller.onText(d.setting, it) },
            onDismiss = dismiss,
        )
        is SettingsDialog.Confirm -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.dialog_title_reset_osc)) },
            text = { Text(stringResource(R.string.dialog_text_reset_osc)) },
            confirmButton = { TextButton(onClick = { controller.onConfirm(d.setting) }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.no)) } },
        )
        is SettingsDialog.Message -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(d.title)) },
            text = {
                Text(stringResource(d.text), modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/** The entries as radio rows; picking one saves it and closes */
@Composable
private fun ChoiceDialog(
    setting: Setting.Choice,
    list: ChoiceList,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedIndex = list.entries.indexOfFirst { it.value == selected }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.title)) },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 420.dp).selectableGroup(),
            ) {
                itemsIndexed(list.entries) { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(
                                selected = index == selectedIndex,
                                onClick = { onSelect(entry.value) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = null)
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The setting's message, its exact value typed (with its unit), and a slider on whole steps */
@Composable
private fun SliderDialog(
    setting: Setting.Slider,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val start = initial.coerceIn(setting.min, maxOf(setting.min, setting.max))
    var value by remember { mutableIntStateOf(start) }
    var field by remember {
        val text = setting.plain(start)
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    val typed = setting.parse(field.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val unit = setting.unit(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (setting.dialogMessage != 0) {
                    Text(
                        stringResource(setting.dialogMessage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        setting.parse(it.text)?.let { v -> value = v }
                    },
                    singleLine = true,
                    isError = typed == null,
                    supportingText = if (typed == null) {
                        { Text(stringResource(R.string.settings_value_invalid)) }
                    } else {
                        null
                    },
                    suffix = unit?.let { { Text(it) } },
                    label = { Text(stringResource(R.string.settings_value_edit)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (setting.min < 0) KeyboardType.Text else KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { typed?.let(onConfirm) }),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                @Suppress("DEPRECATION")
                Slider(
                    value = setting.positionOf(value),
                    onValueChange = {
                        value = setting.valueAt(it)
                        val text = setting.plain(value)
                        field = TextFieldValue(text, TextRange(text.length))
                    },
                    valueRange = setting.positions(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { typed?.let(onConfirm) }, enabled = typed != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A text field with the old preference's input type and length */
@Composable
private fun TextDialog(
    setting: Setting.Text,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (maxLength, keyboard) = when (setting.kind) {
        TextKind.RESOLUTION -> 11 to KeyboardType.Text
        TextKind.BITRATE -> 5 to KeyboardType.Decimal
        TextKind.REFRESH_RATE -> 7 to KeyboardType.Decimal
    }
    var field by remember {
        val text = initial.take(maxLength)
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.dialogTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (setting.dialogMessage != 0) {
                    Text(
                        stringResource(setting.dialogMessage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { if (it.text.length <= maxLength) field = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(field.text) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
