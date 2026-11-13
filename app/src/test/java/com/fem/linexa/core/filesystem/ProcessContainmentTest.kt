package com.fem.linexa.core.filesystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [ProcessContainment]: the TERM→KILL escalation, the
 * process-group target, PID-reuse rejection, the UID guard and the timeout
 * path — all against a fake `/proc` root and a recording signal sender, so
 * the real decision logic is exercised without `android.system.Os`.
 */
class ProcessContainmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var procRoot: File
    private val signals = mutableListOf<Pair<Int, ProcessSignal>>()
    private var nowMs = 0L
    private val selfUid = 10123

    @Before
    fun setUp() {
        procRoot = tmp.newFolder("proc")
        signals.clear()
        nowMs = 0L
    }

    private fun statLine(pid: Int, state: Char = 'S', pgrp: Int = pid, start: Long = 100L): String =
        // pid (comm) then fields 3..22 after the last ')':
        // state, ppid, pgrp, session, tpgid, flags, minflt, cminflt, majflt,
        // cmajflt, utime, stime, cutime, cstime, priority, nice, num_threads,
        // itrealvalue, starttime — starttime is field 22 (index 19).
        listOf(pid, "(proot)", state, 1, pgrp, pid, 0, 4194304, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20, 0, 1, 0, start, 0)
            .joinToString(" ")

    private fun add(pid: Int, state: Char = 'S', pgrp: Int = pid, start: Long = 100L, uid: Int = selfUid) {
        val dir = File(procRoot, pid.toString())
        dir.mkdirs()
        File(dir, "stat").writeText(statLine(pid, state, pgrp, start))
        File(dir, "status").writeText("Name:\tproot\nUid:\t$uid\t$uid\t$uid\t$uid\nThreads:\t1\n")
    }

    private fun remove(pid: Int) {
        File(procRoot, pid.toString()).deleteRecursively()
    }

    private fun containment() = ProcessContainment(
        expectedUid = selfUid,
        sender = ProcessSignalSender { target, signal ->
            signals.add(target to signal)
            true
        },
        procRoot = procRoot,
        clockMs = { nowMs },
        sleep = { nowMs += 100 }
    )

    private fun identity(pid: Int = 500, start: Long = 100L, pgid: Int? = pid) =
        ProcessIdentity(pid = pid, startTimeTicks = start, processGroupId = pgid)

    @Test
    fun `term is sent to the negative group and success needs no kill`() {
        val c = ProcessContainment(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                signals.add(target to signal)
                if (signal == ProcessSignal.TERM) remove(500)
                true
            },
            procRoot = procRoot,
            clockMs = { nowMs },
            sleep = { nowMs += 100 }
        )
        add(500)
        assertTrue(c.killTree(identity()))
        assertEquals(listOf(-500 to ProcessSignal.TERM), signals)
    }

    @Test
    fun `ignored term escalates to kill then confirms gone`() {
        val c = ProcessContainment(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                signals.add(target to signal)
                // SIGKILL cannot be deferred: the fake dies on it.
                if (signal == ProcessSignal.KILL) remove(500)
                true
            },
            procRoot = procRoot,
            clockMs = { nowMs },
            sleep = { nowMs += 100 }
        )
        add(500, start = 100L)
        assertTrue(c.killTree(identity(), graceMs = 500, hardLimitMs = 500))
        assertEquals(
            listOf(-500 to ProcessSignal.TERM, -500 to ProcessSignal.KILL),
            signals
        )
    }

    @Test
    fun `a tree that survives even the kill reports failure`() {
        val c = ProcessContainment(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal -> signals.add(target to signal); true },
            procRoot = procRoot,
            clockMs = { nowMs },
            sleep = { nowMs += 100 }
        )
        add(500)
        assertFalse(c.killTree(identity(), graceMs = 200, hardLimitMs = 200))
        assertEquals(
            listOf(-500 to ProcessSignal.TERM, -500 to ProcessSignal.KILL),
            signals
        )
    }

    @Test
    fun `a reused pid is reported gone and never signalled`() {
        val c = containment()
        add(500, start = 999L)
        assertTrue(c.killTree(identity(pid = 500, start = 100L)))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `a zombie is reported gone and never signalled`() {
        val c = containment()
        add(500, state = 'Z')
        assertTrue(c.killTree(identity()))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `a missing pid is reported gone`() {
        val c = containment()
        assertTrue(c.killTree(identity()))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `a foreign-uid process is refused without any signal`() {
        val c = containment()
        add(500, uid = 9999)
        assertFalse(c.killTree(identity()))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `an incomplete identity is refused without any signal`() {
        val c = containment()
        add(500)
        assertFalse(c.killTree(identity(start = 0L)))
        assertFalse(c.killTree(identity(pid = 0)))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `without a group only the single pid is signalled`() {
        val c = ProcessContainment(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                signals.add(target to signal)
                if (signal == ProcessSignal.KILL) remove(500)
                true
            },
            procRoot = procRoot,
            clockMs = { nowMs },
            sleep = { nowMs += 100 }
        )
        add(500, pgrp = 1) // not a group leader
        assertTrue(c.killTree(identity(pgid = 1), graceMs = 300, hardLimitMs = 300))
        assertEquals(
            listOf(500 to ProcessSignal.TERM, 500 to ProcessSignal.KILL),
            signals
        )
    }

    @Test
    fun `group membership is confirmed across every member`() {
        // Host + two tracees in the same group; TERM removes one, KILL the rest.
        val c = ProcessContainment(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                signals.add(target to signal)
                if (signal == ProcessSignal.TERM) remove(501)
                if (signal == ProcessSignal.KILL) {
                    remove(500)
                    remove(502)
                }
                true
            },
            procRoot = procRoot,
            clockMs = { nowMs },
            sleep = { nowMs += 100 }
        )
        add(500)
        add(501)
        add(502)
        assertTrue(c.killTree(identity(pid = 500, pgid = 500), graceMs = 200, hardLimitMs = 200))
        assertEquals(
            listOf(-500 to ProcessSignal.TERM, -500 to ProcessSignal.KILL),
            signals
        )
        // Every member is gone from the fake /proc.
        assertEquals(0, procRoot.listFiles { f -> f.name.toIntOrNull() != null }!!.size)
    }

    @Test
    fun `a setsid escapee outside the group is not counted as contained`() {
        // 500 (group) exits on TERM; 501 escaped via setsid (own group) and
        // stays alive. Group containment correctly reports the GROUP gone —
        // the escapee is invisible to it, which is exactly why the runtime's
        // cmdline sweep exists as the fallback layer.
        val c = ProcessContainment(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                signals.add(target to signal)
                if (signal == ProcessSignal.TERM) remove(500)
                true
            },
            procRoot = procRoot,
            clockMs = { nowMs },
            sleep = { nowMs += 100 }
        )
        add(500)
        add(501, pgrp = 501)
        assertTrue(c.killTree(identity(pid = 500, pgid = 500), graceMs = 200, hardLimitMs = 200))
        assertEquals(listOf(-500 to ProcessSignal.TERM), signals)
        assertTrue("the escapee is not the containment target", File(procRoot, "501").exists())
    }

    @Test
    fun `identityOf parses pgid and start time and tolerates comm spaces`() {
        val dir = File(procRoot, "700")
        dir.mkdirs()
        File(dir, "stat").writeText("700 (my proot (v1)) S 1 700 700 0 -1 0 0 0 0 0 0 0 0 0 20 0 3 0 12345 0")
        File(dir, "status").writeText("Uid:\t10123\t10123\t10123\t10123\n")
        val id = containment().identityOf(700)
        assertEquals(ProcessIdentity(pid = 700, startTimeTicks = 12345L, processGroupId = 700), id)
    }

    @Test
    fun `parseStat rejects malformed lines`() {
        assertEquals(null, ProcessContainment.parseStat("700 S 1 700 700"))
        assertEquals(null, ProcessContainment.parseStat(""))
        assertEquals(null, ProcessContainment.parseStat("700 (proot) S 1 700 700 0 -1 0 0 0 0 0 0 0 0 0 0 20 0 1"))
    }
}
