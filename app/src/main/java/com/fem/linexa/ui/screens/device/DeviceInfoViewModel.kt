package com.fem.linexa.ui.screens.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fem.linexa.domain.hostinfo.HostSystemInfoProvider
import com.fem.linexa.ui.misc.ProcessPolling
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Producer for the Device/System Information screen.
 *
 * Single-source-of-truth discipline: this ViewModel's ONLY data dependency is
 * [HostSystemInfoProvider] — the same authoritative provider the RootFS bridge
 * consumes. There is no command execution, no /proc parsing, no parallel
 * collection, and no duplicated caching policy: refresh behavior rides the
 * provider's per-category TTLs entirely, so a 2-second UI tick costs one TTL-
 * governed collection burst shared with any other consumer that asked recently
 * (that is what the provider's burst-dedup mutex buys).
 *
 * Polling discipline follows the app's shared visible-lifetime law (written
 * as law after a real bug; the standalone Performance dashboard that first
 * codified it was removed in UI/UX Phase 3, the law stays):
 * - ONE timer runs ONLY while the screen is visible ([start]/[pause] are
 *   wired from composition effects at the destination);
 * - the loop is cancelled on [onCleared];
 * - a manual [refresh] force-pulls through `refreshSnapshot()` and is rate-
 *   limited by the provider's own MIN_FORCED_REFRESH_MS guard — this class
 *   adds none of its own bookkeeping;
 * - while a forced refresh is in flight [refreshing]=true and the PREVIOUS
 *   content stays rendered with its original collectedAt stamp — stale values
 *   never silently pose as fresh ones (Phase 7).
 */
@HiltViewModel
class DeviceInfoViewModel @Inject constructor(
    private val hostSystemInfoProvider: HostSystemInfoProvider,
    private val labels: DeviceInfoLabels = DeviceInfoLabels.English
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceInfoState.LOADING)
    val state: StateFlow<DeviceInfoState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Begin visible-lifetime sampling; idempotent while already running. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            // First cycle renders immediately; the provider does the waiting
            // when another consumer already refreshed a category within TTL.
            while (true) {
                // 2026-08-27 crash-hardening: publish() (mapper) previously ran
                // OUTSIDE the runCatching guard, so any mapper exception would
                // escape through the coroutine's uncaught handler and kill the
                // whole app. Now the entire tick is guarded: a failing tick
                // keeps the last-known-good state on screen and can never crash
                // the process. CancellationException is rethrown so pause()
                // and onCleared() keep their semantics.
                try {
                    hostSystemInfoProvider.snapshot().let(::publish)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    // Previous content stays rendered; nothing is fabricated.
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stop visible-lifetime sampling (composition disposal path). Idempotent. */
    fun pause() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Manual pull-to-refresh action. Force-routes every category through its
     * collector now; state keeps rendering the previous snapshot meanwhile,
     * with [DeviceInfoState.refreshing] flagging the in-flight pass.
     */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                hostSystemInfoProvider.refreshSnapshot().let(::publish)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(refreshing = false) }
                throw cancellation
            } catch (failure: Throwable) {
                // The failed pull keeps the previous content rendered; only the
                // in-flight flag is cleared. Never crashes, never fabricates.
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun publish(fresh: com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot) {
        _state.update { current ->
            DeviceInfoMapper.from(snapshot = fresh, refreshing = false, labels = labels)
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        /**
         * 2 s — identical cadence to the Performance dashboard: fast enough to
         * feel live for RAM/battery/thermal/uptime drift, slow enough that each
         * tick lands inside the provider's CPU delta floor (600 ms) + margin.
         * Phase 08 (OPT-PERF-004): the one shared [ProcessPolling] constant.
         */
        const val POLL_INTERVAL_MS = ProcessPolling.INTERVAL_MS
    }
}
