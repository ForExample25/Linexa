package com.fem.linexa.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.fem.linexa.ui.icons.LinexaIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fem.linexa.R
import com.fem.linexa.ui.components.isIgnoringBatteryOptimizations
import com.fem.linexa.ui.components.requestIgnoreBatteryOptimizations
import com.fem.linexa.ui.theme.LinexaColors

/**
 * §1.191 — the actionable explanation for a terminal that died on SIGKILL.
 *
 * A SIGKILLed process cannot say anything about its own death: the guest gets
 * no signal handler, writes nothing to the pty, and the transcript is silent.
 * The exit status is the only evidence there is, so without this the user sees
 * "Terminal T1 exited unexpectedly" for an event that was neither their fault
 * nor the guest's, and has no idea that a platform setting is the lever.
 *
 * **Deliberately inline, and deliberately not a screen.** This is a block
 * inside the existing per-terminal placeholder — the same
 * [WorkspaceTerminalPanel] surface that renders every other terminal state. It
 * is rendered for ONE terminal, alongside siblings that are still Running, and
 * it takes over nothing: no Activity, no route, no dialog, no back-stack entry.
 * That is the point. Linexa's error model is per-terminal, not per-workspace
 * (§P0 — a dead terminal never drives the workspace or its siblings anywhere),
 * and a full-screen takeover would contradict that model by making one
 * terminal's death look like the workspace's. tiny_container's dedicated
 * `Signal9Activity` is the pattern being rejected here, not followed: it can
 * only be correct in an app with a single global session, and it turns a
 * recoverable per-terminal event into a modal interruption. Do not "promote"
 * this to a screen or a dialog.
 *
 * The claim is hedged on purpose. SIGKILL from lowmemorykiller, from Android's
 * background/freezer restrictions, from a guest-side `kill -9`, and from `adb`
 * are indistinguishable in the exit status — so the copy says Android *may*
 * have reclaimed it rather than asserting a cause that cannot be proven from
 * a `-9`. The battery-optimization exemption is offered as the one thing the
 * user can actually change, honestly framed as "helps" rather than a fix.
 *
 * Diagnosis only: this composable reads state and launches a system settings
 * screen. It never restarts, closes, or otherwise touches the terminal, so the
 * §P0 per-terminal error handling stays exactly as it was.
 */
@Composable
fun TerminalKilledNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // The exemption has no change notification, and tapping the action leaves
    // for a system screen — so re-query on resume, which is when the user comes
    // back having possibly granted it. Same approach as the Global Settings
    // access rows, for the same reason.
    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // §1.105 crash guard, inherited with the pattern: this binds compose-ui's
    // LocalLifecycleOwner (androidx.compose.ui.platform), NOT the
    // identically-named CompositionLocal from androidx.lifecycle.compose, which
    // compose-ui 1.6.x never provides. Do not "simplify" to LifecycleResumeEffect.

    val exempt = remember(refreshKey) { isIgnoringBatteryOptimizations(context) }

    Surface(
        modifier = modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = LinexaIcons.BatterySaver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 2.dp)
                )
                Text(
                    text = stringResource(R.string.terminal_killed_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Text(
                text = stringResource(R.string.terminal_killed_body),
                style = MaterialTheme.typography.bodySmall,
                color = LinexaColors.TextMuted,
                textAlign = TextAlign.Start
            )
            // Already-exempt users still get the row, relabelled: the exemption
            // is not the only cause of a SIGKILL, so hiding the affordance would
            // leave them with an explanation and nothing to look at. The
            // underlying launcher opens the full battery list in that case.
            TextButton(
                onClick = { requestIgnoreBatteryOptimizations(context) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = stringResource(
                        if (exempt) {
                            R.string.terminal_killed_action_granted
                        } else {
                            R.string.terminal_killed_action
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/** Kept off the notice itself so the composable stays free of layout constants. */
internal val TerminalKilledNoticeSpacing = 14.dp
