package com.fem.linexa.ui.screens.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fem.linexa.R
import com.fem.linexa.service.runtime.pty.PtySession
import com.fem.linexa.ui.components.haven.HavenExtraKeys
import com.fem.linexa.ui.components.haven.HavenTerminalBar
import com.fem.linexa.ui.components.haven.HavenTerminalModifiers
import com.fem.linexa.ui.components.haven.HavenTerminalSheet
import com.fem.linexa.ui.components.haven.HavenSessionDot
import com.fem.linexa.ui.components.haven.HavenTerminalSurface
import com.fem.linexa.ui.components.haven.HSpinner
import com.fem.linexa.ui.theme.haven.HavenTheme

/**
 * The management (host) terminal — Haven chrome.
 *
 * Same terminal mechanism as before ([ManagementTerminalViewModel] + the real
 * `TerminalView`); the presentation is the Haven near-chromeless model: one
 * 46dp bar (name + ⋯), the floating extra-keys pill, and the ⋯ sheet with
 * Copy / Paste / Font size. There are no session dots — the management shell
 * is a single session (the bars' dot list shows only it, as a subtle live
 * indicator).
 */
@Composable
fun HavenManagementTerminalPanel(
    session: PtySession?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    settingsViewModel: TerminalUiSettingsViewModel = hiltViewModel()
) {
    val h = HavenTheme.colors
    val clipboard = LocalClipboardManager.current
    var showSheet by remember { mutableStateOf(false) }
    val terminalModifiers = remember { HavenTerminalModifiers() }
    val terminalHandleRef = remember { mutableStateOf<TerminalHandle?>(null) }
    val toolbar by settingsViewModel.toolbarSettings.collectAsState()

    HavenTerminalSurface(modifier = modifier) {
        Box(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                HavenTerminalBar(
                    workspaceName = stringResource(R.string.management_terminal_host_shell),
                    sessions = listOf(
                        HavenSessionDot(
                            terminalId = "sh",
                            active = true,
                            alive = session?.isAlive == true
                        )
                    ),
                    // Single fixed host session — no overview to open; the
                    // Keyboard control is still mandatory (Issue 8).
                    onKeyboard = { terminalHandleRef.value?.focusAndShowKeyboard() },
                    onSessionClick = {},
                    onMore = { showSheet = true }
                )
                val live = session
                if (live != null) {
                    RealTerminalView(
                        ptySession = live,
                        modifiers = terminalModifiers,
                        onViewReady = { terminalHandleRef.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        HSpinner()
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.management_terminal_connecting),
                            style = com.fem.linexa.ui.theme.haven.HavenType.mono12,
                            color = h.termDim,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }

                // 2026-08-29 (UI optimization, Issue A): shortcut bar as a
                // real layout row between the terminal surface and the soft
                // keyboard — never an overlay over terminal content.
                HavenExtraKeys(
                    modifiers = terminalModifiers,
                    config = toolbar.config,
                    onModifierTap = { key ->
                        key.modifier?.let { terminalModifiers.toggle(it) }
                    },
                    onKey = { key ->
                        val code = key.keyCode ?: return@HavenExtraKeys
                        // Keys dispatch only — the Keyboard control owns the IME.
                        terminalHandleRef.value?.dispatchTerminalKeyCode(
                            code,
                            key.dispatchMetaState()
                        )
                    }
                )
            }
        }
    }

    if (showSheet) {
        HavenTerminalSheet(
            sessionLabel = stringResource(R.string.management_terminal_host_shell),
            onDismiss = { showSheet = false },
            fontDp = terminalHandleRef.value?.fontSizeDp
                ?: com.fem.linexa.domain.settings.TerminalFontPreference.DEFAULT_DP,
            fontRange = com.fem.linexa.domain.settings.TerminalFontPreference.MIN_DP..
                com.fem.linexa.domain.settings.TerminalFontPreference.MAX_DP,
            onFontChange = { terminalHandleRef.value?.setFontSizeDp(it) },
            onCopy = {
                val live = session
                if (live != null) {
                    clipboard.setText(AnnotatedString(live.recentOutput(8_000)))
                }
                showSheet = false
            },
            onPaste = {
                val live = session
                val text = clipboard.getText()?.text
                if (live != null && !text.isNullOrEmpty()) {
                    live.write(text)
                }
                showSheet = false
            },
            onNewTerminal = null,
            onCloseSession = null
        )
    }
}
