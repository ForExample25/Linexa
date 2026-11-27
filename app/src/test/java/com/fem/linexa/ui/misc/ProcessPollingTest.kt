package com.fem.linexa.ui.misc

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 10D — the behavioural half of the shared `/proc` poll cadence (Phase 08,
 * audit OPT-PERF-004). The value used to be pinned by re-parsing `INTERVAL_MS`
 * out of ProcessPolling.kt's source text (WorkspacePerformancePanelSourceTest);
 * the constant is directly readable on the JVM, so the bound is asserted
 * behaviourally. The wiring half — that every poller declares its interval as
 * a named constant delegating to this one cadence — stays in the source tests.
 */
class ProcessPollingTest {

    @Test
    fun theSharedPollIntervalIsAtLeastOneSecond() {
        assertTrue(
            "a /proc poll faster than 1s is not the 'low, controlled rate' the " +
                "pollers require (was ${ProcessPolling.INTERVAL_MS}ms)",
            ProcessPolling.INTERVAL_MS >= 1_000L
        )
    }
}
