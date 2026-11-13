package com.fem.linexa.ui.screens.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fem.linexa.R
import com.fem.linexa.domain.commands.CustomCommand
import com.fem.linexa.domain.commands.CustomCommandInput

/**
 * The Add / Edit form for a saved custom command — the feature's whole
 * creation surface.
 *
 * ## Save means STORE, never run
 *
 * The confirm button persists the record ([onSave]); nothing is executed. A
 * saved command runs when its card is tapped later, through the same
 * terminal/session path every other leaf uses. The one deliberate exception
 * is the secondary [onRunOnce] action ("Run once, don't save"), which keeps
 * the pre-existing one-off affordance reachable from the same `+` that now
 * primarily saves — it runs the typed command immediately WITHOUT storing it,
 * exactly like the old custom-run dialog did.
 *
 * ## Validation is the domain's, not this dialog's
 *
 * The confirm button is enabled iff [CustomCommandInput.validate] accepts the
 * current fields — the same validator the store re-runs before persisting, so
 * the dialog can never produce a record the persistence layer would refuse,
 * and the rules (non-blank name, single-line non-blank command, length caps)
 * live in exactly one place. The keyboard's Done action is wired to the same
 * confirm path.
 */
@Composable
fun CustomCommandFormDialog(
    title: String,
    confirmLabel: String,
    onSave: (name: String, command: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Present when editing an existing record — enables the delete action. */
    onDelete: (() -> Unit)? = null,
    /** Present when the one-off run is reachable (needs a live runtime). */
    onRunOnce: ((command: String) -> Unit)? = null,
    initialName: String = "",
    initialCommand: String = ""
) {
    val focusRequester = remember { FocusRequester() }
    var name by remember { mutableStateOf(initialName) }
    var command by remember { mutableStateOf(initialCommand) }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val input = CustomCommandInput.validate(name, command)
    val canConfirm = input != null
    val overLimit = command.isNotBlank() && command.trim().length > CustomCommand.MAX_COMMAND_CHARS

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.custom_command_name_label)) },
                    placeholder = { Text(stringResource(R.string.custom_command_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.custom_command_command_label)) },
                    placeholder = { Text(stringResource(R.string.custom_command_command_hint)) },
                    singleLine = true,
                    isError = overLimit,
                    supportingText = if (overLimit) {
                        { Text(stringResource(R.string.custom_command_too_long)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canConfirm) {
                                onSave(input!!.name, input.command)
                                onDismiss()
                            }
                        }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                if (onDelete != null || onRunOnce != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        if (onDelete != null) {
                            TextButton(onClick = onDelete) {
                                Text(
                                    stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (onRunOnce != null) {
                            TextButton(
                                onClick = {
                                    input?.let {
                                        onRunOnce(it.command)
                                        onDismiss()
                                    }
                                },
                                enabled = input != null
                            ) {
                                Text(stringResource(R.string.custom_command_run_once))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canConfirm) {
                        onSave(input!!.name, input.command)
                        onDismiss()
                    }
                },
                enabled = canConfirm
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
