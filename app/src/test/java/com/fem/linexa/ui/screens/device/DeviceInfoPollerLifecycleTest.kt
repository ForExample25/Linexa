package com.fem.linexa.ui.screens.device

import com.fem.linexa.domain.hostinfo.HostInfoCategory
import com.fem.linexa.domain.hostinfo.HostSystemInfoProvider
import com.fem.linexa.domain.hostinfo.HostSystemInfoSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Phase 08 (audit OPT-PERF-004): the `/proc` poller contract, driven through
 * the test dispatcher.
 *
 * [DeviceInfoViewModel] is the poller with the fewest dependencies (one
 * interface), so it carries the behavioural half of the phase's Part D here:
 *
 *  - sampling starts immediately and then every [ProcessPolling.INTERVAL_MS],
 *  - `pause()` cancels the polling job — advancing the dispatcher after it
 *    produces NO new samples,
 *  - a double `start()` does not create two jobs (one series, not two),
 *  - restarting after `pause()` resumes one series.
 *
 * The remaining five pollers share this exact loop shape (verified structurally
 * by [com.fem.linexa.ui.ProcessPollerConsistencyTest]); the `stop()`-named API
 * variant is covered in [com.fem.linexa.ui.screens.terminal.SessionTaskManagerPollerLifecycleTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceInfoPollerLifecycleTest {

    /** Counts every sampling pass; publishes all-unknown snapshots (never fabricated values). */
    private class RecordingProvider : HostSystemInfoProvider {
        var snapshots = 0
            private set

        override suspend fun snapshot(): HostSystemInfoSnapshot {
            snapshots++
            return HostSystemInfoSnapshot.empty(atEpochMs = 1_700_000_000_000L + snapshots)
        }

        override suspend fun refreshSnapshot(): HostSystemInfoSnapshot = snapshot()

        override suspend fun snapshotOf(categories: Set<HostInfoCategory>): HostSystemInfoSnapshot = snapshot()

        override fun lastSnapshot(): HostSystemInfoSnapshot? = null

        override fun invalidateAll() = Unit
    }

    private lateinit var dispatcher: TestDispatcher
    private lateinit var provider: RecordingProvider
    private lateinit var viewModel: DeviceInfoViewModel

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        provider = RecordingProvider()
        viewModel = DeviceInfoViewModel(provider)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startSamplesImmediatelyThenEveryInterval() {
        viewModel.start()
        // Ticks land at t=0, 2000, 4000, 6000 — one series at the shared cadence.
        dispatcher.scheduler.advanceTimeBy(6_100)
        assertEquals(4, provider.snapshots)
    }

    @Test
    fun pauseCancelsThePollingJobAndLaterTimeProducesNoNewSamples() {
        viewModel.start()
        dispatcher.scheduler.advanceTimeBy(2_100)
        assertEquals(2, provider.snapshots)

        viewModel.pause()
        // Far past the interval: a live poller would have ticked ~30 more times.
        dispatcher.scheduler.advanceTimeBy(60_000)
        assertEquals("pause() must cancel the polling job", 2, provider.snapshots)
    }

    @Test
    fun doubleStartDoesNotCreateTwoPollingJobs() {
        viewModel.start()
        viewModel.start()
        // Two series would tick at 0/0, 2000/2000, 4000/4000 → 6 samples.
        // One idempotent series ticks at 0, 2000, 4000 → 3.
        dispatcher.scheduler.advanceTimeBy(4_100)
        assertEquals(3, provider.snapshots)
    }

    @Test
    fun restartingAfterPauseResumesOneSeries() {
        viewModel.start()
        dispatcher.scheduler.advanceTimeBy(2_100)
        assertEquals(2, provider.snapshots)

        viewModel.pause()
        viewModel.start()
        dispatcher.scheduler.advanceTimeBy(2_100)
        // The resumed series runs alone: +2 samples, not +4 (a leftover first
        // job would keep ticking alongside the new one).
        assertEquals(4, provider.snapshots)
    }
}
