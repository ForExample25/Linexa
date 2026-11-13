package com.fem.linexa.ui.screens.terminal

import com.fem.linexa.data.resource.ProcessSignals
import com.fem.linexa.domain.resource.ResourceMonitor
import com.fem.linexa.domain.resource.WorkspaceProcessSample
import com.fem.linexa.domain.resource.WorkspaceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

/**
 * Phase 08 (audit OPT-PERF-004): the `stop()`-named poller contract, driven
 * through the test dispatcher ([DeviceInfoPollerLifecycleTest] carries the
 * `pause()`-named variant; the two cover the Part D criteria together).
 *
 * [SessionTaskManagerViewModel] is the cheapest `stop()` poller to drive: its
 * only sampling dependency is [ResourceMonitor], stubbed here as an unreadable
 * `/proc` (a real honest reading — nothing is fabricated, the state ends
 * `unavailable`) so the loop's side effects are exactly countable.
 *
 * Asserted:
 *  - `start` samples immediately, then every [ProcessPolling.INTERVAL_MS];
 *  - `stop()` cancels the polling job — advancing the dispatcher afterwards
 *    produces NO new samples;
 *  - a double `start()` does not create two jobs;
 *  - restarting after `stop()` resumes one series.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTaskManagerPollerLifecycleTest {

    private class RecordingProcessSignals : ProcessSignals {
        var terminated = mutableListOf<Int>()
        override fun terminate(pid: Int): Boolean {
            terminated += pid
            return true
        }
    }

    private lateinit var dispatcher: TestDispatcher
    private lateinit var monitor: ResourceMonitor
    private lateinit var processSignals: RecordingProcessSignals
    private lateinit var viewModel: SessionTaskManagerViewModel

    /** Counts real sampling passes on the stubbed monitor. */
    private var snapshotCalls = 0

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        snapshotCalls = 0
        monitor = Mockito.mock(ResourceMonitor::class.java)
        processSignals = RecordingProcessSignals()
        viewModel = SessionTaskManagerViewModel(monitor, processSignals)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Stub [ResourceMonitor.snapshot] as unreadable `/proc`, counting real passes. */
    private fun givenAnUnreadableProcSnapshot() {
        runBlocking {
            Mockito.`when`(monitor.snapshot(ArgumentMatchers.anyCollection()))
                .thenAnswer {
                    snapshotCalls++
                    WorkspaceSnapshot.unavailable()
                }
        }
    }

    private fun sample(pid: Int) = WorkspaceProcessSample(
        pid = pid,
        name = "sh",
        stateChar = 'S',
        cpuPercentOfDevice = null,
        memKb = null
    )

    @Test
    fun startSamplesImmediatelyThenEveryInterval() {
        givenAnUnreadableProcSnapshot()
        viewModel.start(rootPid = 42)
        dispatcher.scheduler.advanceTimeBy(4_100)
        assertEquals("one series ticks at 0, 2000, 4000", 3, snapshotCalls)
        assertTrue(viewModel.state.value.unavailable)
    }

    @Test
    fun stopCancelsThePollingJobAndLaterTimeProducesNoNewSamples() {
        givenAnUnreadableProcSnapshot()
        viewModel.start(rootPid = 42)
        dispatcher.scheduler.advanceTimeBy(2_100)
        assertEquals(2, snapshotCalls)

        viewModel.stop()
        dispatcher.scheduler.advanceTimeBy(60_000)
        assertEquals("stop() must cancel the polling job", 2, snapshotCalls)
    }

    @Test
    fun aPopulatedSnapshotReachesTheState() {
        runBlocking {
            Mockito.`when`(monitor.snapshot(ArgumentMatchers.anyCollection())).thenReturn(
                WorkspaceSnapshot(
                    processes = listOf(sample(7), sample(9)),
                    topology = WorkspaceSnapshot.unavailable().topology,
                    readable = true
                )
            )
        }
        viewModel.start(rootPid = 42)
        dispatcher.scheduler.advanceTimeBy(1_100)
        assertEquals(
            listOf(7, 9),
            viewModel.state.value.processes.map { it.pid }
        )
        assertEquals(false, viewModel.state.value.unavailable)
        assertEquals(false, viewModel.state.value.loading)
    }

    @Test
    fun doubleStartDoesNotCreateTwoPollingJobs() {
        givenAnUnreadableProcSnapshot()
        viewModel.start(rootPid = 42)
        viewModel.start(rootPid = 42)
        // Two series would tick at 0/0, 2000/2000, 4000/4000 → 6 samples.
        // One idempotent series ticks at 0, 2000, 4000 → 3.
        dispatcher.scheduler.advanceTimeBy(4_100)
        assertEquals(3, snapshotCalls)
    }

    @Test
    fun restartingAfterStopResumesOneSeries() {
        givenAnUnreadableProcSnapshot()
        viewModel.start(rootPid = 42)
        dispatcher.scheduler.advanceTimeBy(2_100)
        assertEquals(2, snapshotCalls)

        viewModel.stop()
        viewModel.start(rootPid = 42)
        dispatcher.scheduler.advanceTimeBy(2_100)
        // The resumed series runs alone: +2 samples, not +4 (a leftover first
        // job would keep ticking alongside the new one).
        assertEquals(4, snapshotCalls)
    }
}
