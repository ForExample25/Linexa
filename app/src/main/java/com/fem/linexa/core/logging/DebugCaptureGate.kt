package com.fem.linexa.core.logging

import com.fem.linexa.domain.settings.SettingsRepository
import com.fem.linexa.service.runtime.RuntimeTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI/UX Phase 7 — the single path from the "Enable debug info" switch to the
 * capture mechanisms.
 *
 * [DebugCapture] is an `object` with a `@Volatile` flag because [Logger] has
 * nowhere to receive an injected dependency (see its class doc). This class is the
 * other half of that arrangement: the one place that *writes* the flag, so the
 * persisted setting and what the app actually does can never disagree. It observes
 * the setting for the life of the process rather than reading it once, because the
 * toggle is reachable from two screens (Global Settings and the Workspace Settings
 * tab) and a one-shot read at startup would leave capture in whatever state it was
 * in when the process started — the user would have to kill the app for their own
 * setting to take effect.
 *
 * Started from `LinexaApplication.onCreate` and never stopped. That placement is
 * load-bearing in both directions:
 *
 *  * **Early enough to matter.** Capture is what a cold-start bug needs, so the
 *    mirror has to be live before the runtime does anything worth recording. It
 *    is deliberately not tied to an Activity or a ViewModel: `RuntimeService`
 *    outlives every screen (§1.100), so a gate scoped to the UI would stop
 *    mirroring exactly when a long-lived workspace is doing the most logging.
 *  * **Fail-closed if it is ever missing.** [DebugCapture] defaults to `false`, so
 *    a build where this gate is never started captures *nothing* rather than
 *    everything. That is the safe direction — an empty debug panel is visible and
 *    gets reported, whereas silent background logging with the switch off is
 *    precisely the condition Phase 7 exists to remove and the one a user cannot
 *    see. `DebugInfoWiringSourceTest` asserts the application actually starts it,
 *    because an unstarted gate compiles perfectly and leaves debug info
 *    permanently unavailable.
 *
 * Turning the toggle **off** also clears [RuntimeTrace]'s in-memory buffers. That
 * is not tidiness: stopping new appends still leaves up to 400 lines per workspace
 * retained for the life of the process, for a panel that is now hidden, which is
 * the "not continuously buffer logs in the background" requirement read honestly.
 * Only memory is dropped — the persisted `{workspace}/logs/` evidence (§1.53) and
 * the app log survive, because a toggle is not a delete and the app log has its
 * own explicit Clear action.
 */
@Singleton
class DebugCaptureGate @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val runtimeTrace: RuntimeTrace
) {

    /**
     * Begins mirroring the persisted setting into [DebugCapture].
     *
     * Idempotent: a second call is ignored rather than starting a second
     * collector. Both would write the same value, so the harm is not incorrectness
     * but an invisible duplicate subscription for the life of the process.
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            settingsRepository.observeDebugEnabled().collectLatest { enabled ->
                apply(enabled)
            }
        }
    }

    /**
     * Applies one setting value. Separate from [start] so the ordering rule that
     * matters — capture is disabled *before* the buffers are dropped, never after
     * — is stated in one place and can be asserted without a coroutine.
     *
     * The order is deliberate: clearing first would leave a window in which a
     * concurrent `record` from the runtime re-populates the buffer that was just
     * emptied, and the user would turn debug off and still be holding lines.
     */
    private fun apply(enabled: Boolean) {
        DebugCapture.setEnabled(enabled)
        if (!enabled) runtimeTrace.clear()
    }

    /** Applies a value synchronously. For tests — see [apply]'s ordering note. */
    internal fun applyForTesting(enabled: Boolean) = apply(enabled)

    @Volatile
    private var started: Boolean = false
}
