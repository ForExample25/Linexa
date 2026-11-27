package com.fem.linexa.ui.screens.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fem.linexa.R

/**
 * Localized /proc state letter for the process rows shared by Activity and the
 * Task Manager. The domain keeps the raw letter (and an English label for
 * diagnostics); user-visible text belongs to the composition layer.
 */
@Composable
internal fun processStateLabel(stateChar: Char): String = when (stateChar) {
    'R' -> stringResource(R.string.process_state_running)
    'S' -> stringResource(R.string.process_state_sleeping)
    'D' -> stringResource(R.string.process_state_disk_sleep)
    'Z' -> stringResource(R.string.process_state_zombie)
    'T', 't' -> stringResource(R.string.process_state_stopped)
    'I' -> stringResource(R.string.process_state_idle)
    'X', 'x' -> stringResource(R.string.process_state_dead)
    'K' -> stringResource(R.string.process_state_wakekill)
    'W' -> stringResource(R.string.process_state_waking)
    'P' -> stringResource(R.string.process_state_parked)
    else -> stateChar.toString()
}
