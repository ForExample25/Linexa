package com.fem.linexa.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fem.linexa.R

/**
 * The shared confirmation dialog for Linexa: a filled primary confirm button
 * (error-filled when [destructive]) and a text dismiss button ([dismissLabel],
 * defaulting to the standard Cancel). Used by Home (delete), SettingsTab
 * (delete), WorkspaceNavGraph (exit), the diagnostic viewer (delete) and the
 * file manager (delete) so every destructive/confirm prompt looks and behaves
 * identically.
 *
 * Transformation spec §9/§10: the workspace-exit caller passes an explicit
 * [dismissLabel] ("Keep running") because its dismiss button is not a cancel
 * — it is a second, distinct outcome. Every other caller keeps the default
 * Cancel text and the original behaviour.
 *
 * Text-input dialogs (rename, create-folder, custom command) intentionally
 * keep their own [OutlinedTextField] layout and are not routed through this.
 */
@Composable
fun LinexaAlertDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { ScrollableDialogMessage(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel ?: stringResource(R.string.common_cancel))
            }
        }
    )
}

/**
 * An **acknowledgement** dialog: title, message, one OK button.
 *
 * §1.117: deliberately not [LinexaAlertDialog]. That one always renders a
 * Cancel dismiss button, which is meaningless when there is nothing to cancel
 * — a refused delete already deleted nothing, so offering "OK / Cancel" would
 * imply the user is choosing between two outcomes when both just close the
 * dialog. Use this for reporting a result, and [LinexaAlertDialog] for asking
 * a question.
 */
@Composable
fun LinexaMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { ScrollableDialogMessage(message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.common_ok))
            }
        }
    )
}

/**
 * UI/UX consistency pass — the shared single-line text-input dialog.
 *
 * The three previous callers (FileManager new-folder, FileManager rename,
 * Home rename-workspace, ShortcutsTab custom-command run) each opened a raw
 * `AlertDialog` and hand-wrote the same `OutlinedTextField` boilerplate with
 * the same focus/IME/capitalization conventions. Wrapping them here removes
 * ~30 lines of duplication per caller and ensures the styling stays consistent
 * with the rest of the dialog family (the [LinexaAlertDialog] titleMedium
 * title, the [common_cancel] dismiss button).
 *
 * The caller owns the *commit semantics* via [isValid]; the dialog owns the
 * text state itself. The confirm button is enabled iff `isValid(text)` is
 * `true` for the current text, so the predicate re-evaluates on every
 * keystroke rather than being frozen at composition. The dialog calls
 * [onConfirm] only with the trimmed text and only when valid, and always
 * calls [onDismiss] afterwards. The keyboard's `Done` action is wired to
 * the same confirm path.
 *
 * The text field auto-focuses on first composition (via a `FocusRequester`
 * + `LaunchedEffect`), so the user can start typing immediately rather
 * than tapping the field first.
 *
 * @param initialValue seeded into the text field (e.g. the file's current
 *   name on rename). The dialog's own `mutableStateOf(initialValue)` keeps
 *   it stable across recompositions.
 * @param isValid predicate evaluated against the live text on every
 *   keystroke. Defaults to "non-blank" — the most common predicate. Pass
 *   a custom predicate to enforce rules like "not equal to the initial
 *   value" (rename) or "matches a port-number pattern".
 */
@Composable
fun LinexaInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    isValid: (String) -> Boolean = { it.isNotBlank() },
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None
) {
    val focusRequester = remember { FocusRequester() }
    var text by remember { mutableStateOf(initialValue) }
    val trimmed = remember(text) { text.trim() }
    val canConfirm = remember(text) { isValid(trimmed) }

    LaunchedEffect(Unit) {
        // Auto-focus the field so the keyboard appears with the dialog.
        // A `LaunchedEffect(Unit)` rather than `LaunchedEffect(focusRequester)`
        // because the requester is stable; this fires exactly once.
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = placeholder?.let { { Text(it) } },
                singleLine = true,
                isError = isError,
                supportingText = errorMessage?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = capitalization,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canConfirm) {
                            onConfirm(trimmed)
                            onDismiss()
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canConfirm) {
                        onConfirm(trimmed)
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

/**
 * A dialog message that scrolls when the localized copy is taller than the
 * space the dialog can give it. The cap is a fraction of the slot's own
 * measured height (never a hard dp), so it adapts to the screen, the font
 * scale and the dialog's title: the action row always keeps room.
 */
@Composable
private fun ScrollableDialogMessage(message: String) {
    BoxWithConstraints {
        // A dialog host always gives a bounded height, but guard the unbounded
        // case (a test harness or a future popup host) so verticalScroll never
        // receives an infinite constraint.
        // Exactly the original `maxHeight.value.isFinite()`: finite → scale the
        // cap; ±Inf or NaN (both non-finite) → fall back to 240.dp.
        val cap = if (!maxHeight.value.isInfinite() && !maxHeight.value.isNaN()) maxHeight * 0.6f else 240.dp
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .heightIn(max = cap)
                .verticalScroll(rememberScrollState())
        )
    }
}
