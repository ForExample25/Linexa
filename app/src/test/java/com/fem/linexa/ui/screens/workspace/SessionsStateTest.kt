package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.domain.resource.WorkspaceProcessSample
import com.fem.linexa.domain.runtime.TerminalState
import com.fem.linexa.ui.screens.workspace.SessionsViewModel.SessionFilter
import com.fem.linexa.ui.screens.workspace.SessionsViewModel.SessionKind
import com.fem.linexa.ui.screens.workspace.SessionsViewModel.SessionSort
import com.fem.linexa.ui.screens.workspace.SessionsViewModel.SessionUi
import com.fem.linexa.ui.screens.workspace.SessionsViewModel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Search / filter / sort of the Sessions list — pure, no Android. */
class SessionsStateTest {

    private fun session(
        id: String,
        label: String,
        state: TerminalState = TerminalState.Running,
        createdSec: Long,
        cpu: Float? = null,
        mem: Long? = null,
        pid: Int? = null,
        endedAtMillis: Long? = null,
        procs: List<WorkspaceProcessSample> = emptyList()
    ) = SessionUi(
        terminalId = id, label = label, kind = SessionKind.SHELL, state = state,
        createdAt = Instant.ofEpochSecond(createdSec), endedAtMillis = endedAtMillis, pid = pid,
        cpuPercentOfDevice = cpu, memKb = mem, memoryIsProportional = true,
        processCount = procs.size, processes = procs
    )

    private val running1 = session("T0", "T1", createdSec = 100, cpu = 5f, mem = 300, pid = 4242)
    private val running2 = session("T1", "T2", createdSec = 200, cpu = 20f, mem = 100, pid = 5151)
    private val ended = session(
        "T2", "T3", state = TerminalState.Error("boom", -9), createdSec = 300, endedAtMillis = 400_000
    )
    private val all = State(sessions = listOf(running1, running2, ended))

    @Test
    fun `filters split running and ended and counts agree`() {
        assertEquals(2, all.runningCount)
        assertEquals(1, all.endedCount)
        assertEquals(listOf("T0", "T1"), all.copy(filter = SessionFilter.RUNNING, sort = SessionSort.OLDEST)
            .visibleSessions.map { it.terminalId })
        assertEquals(listOf("T2"), all.copy(filter = SessionFilter.ENDED).visibleSessions.map { it.terminalId })
    }

    @Test
    fun `search matches visible label internal id and pid`() {
        // "t3" is the ended session's VISIBLE label; its internal id is T2.
        assertEquals(listOf("T2"), all.copy(query = "t3").visibleSessions.map { it.terminalId })
        assertEquals(listOf("T0"), all.copy(query = "4242").visibleSessions.map { it.terminalId })
        assertEquals(3, all.copy(query = "shell").visibleSessions.size)
    }

    @Test
    fun `search matches process names`() {
        val proc = WorkspaceProcessSample(pid = 9, name = "vim", stateChar = 'S', cpuPercentOfDevice = null, memKb = null)
        val s = State(sessions = listOf(running1.copy(processes = listOf(proc)), running2))
        assertEquals(listOf("T0"), s.copy(query = "vim").visibleSessions.map { it.terminalId })
    }

    @Test
    fun `sort by cpu and ram puts highest first and unknown last`() {
        assertEquals(listOf("T1", "T0", "T2"), all.copy(sort = SessionSort.CPU).visibleSessions.map { it.terminalId })
        assertEquals(listOf("T0", "T1", "T2"), all.copy(sort = SessionSort.RAM).visibleSessions.map { it.terminalId })
    }

    @Test
    fun `newest and oldest are inverse`() {
        assertEquals(listOf("T2", "T1", "T0"), all.copy(sort = SessionSort.NEWEST).visibleSessions.map { it.terminalId })
        assertEquals(listOf("T0", "T1", "T2"), all.copy(sort = SessionSort.OLDEST).visibleSessions.map { it.terminalId })
    }

    @Test
    fun `cpu and ram sorts are only offered when a reading exists`() {
        assertTrue(all.canSortByCpu)
        assertFalse(State(sessions = listOf(ended)).canSortByCpu)
        assertFalse(State(sessions = listOf(ended)).canSortByRam)
    }

    @Test
    fun `ended session duration is frozen at its end time`() {
        // created at 300s, ended observed at 400s -> 100s, however late "now" is.
        assertEquals(100_000L, ended.elapsedMillis(nowMillis = 9_999_999_000L))
        // A running session keeps counting: created at 100s, now 500s -> 400s.
        assertEquals(400_000L, running1.elapsedMillis(nowMillis = 500_000L))
    }
}
