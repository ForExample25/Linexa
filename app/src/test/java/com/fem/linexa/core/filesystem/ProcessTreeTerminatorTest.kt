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
 * Phase 0 regression tests for [ProcessTreeTerminator] — the component-level
 * process ownership model.
 *
 * Each case builds a fake `/proc` with several independent process trees and
 * asserts the **exact** set of signals sent. The scenarios mirror the audit's
 * process matrix (`WORKSPACE_PROCESS_AUDIT.md` §11):
 *
 *  - daemon stop must not touch a terminal tree (`bash` + `apt` + `sleep`);
 *  - one of two daemons stops, the other survives;
 *  - a dead daemon host sends zero signals;
 *  - an exec timeout kills only the exec tree;
 *  - PID reuse is rejected by start-time identity;
 *  - a reparented group member is still found through the process group;
 *  - SIGTERM escalation and verified termination.
 *
 * The fake rows deliberately carry no cmdline: the old defect was a
 * workspace-wide cmdline sweep, so these tests prove the new model does not
 * depend on cmdline matching at all.
 */
class ProcessTreeTerminatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var procRoot: File
    private val signals = mutableListOf<Pair<Int, ProcessSignal>>()
    private var nowMs = 0L
    private val selfUid = 10125

    @Before
    fun setUp() {
        procRoot = tmp.newFolder("proc")
        signals.clear()
        nowMs = 0L
    }

    private fun statLine(pid: Int, ppid: Int, pgrp: Int, start: Long, state: Char = 'S'): String =
        listOf(pid, "(proot)", state, ppid, pgrp, pid, 0, 4194304, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20, 0, 1, 0, start, 0)
            .joinToString(" ")

    private fun add(
        pid: Int,
        ppid: Int = 1,
        pgrp: Int = pid,
        start: Long = 100L,
        state: Char = 'S',
        uid: Int = selfUid
    ) {
        val dir = File(procRoot, pid.toString())
        dir.mkdirs()
        File(dir, "stat").writeText(statLine(pid, ppid, pgrp, start, state))
        File(dir, "status").writeText("Name:\tproot\nUid:\t$uid\t$uid\t$uid\t$uid\nThreads:\t1\n")
    }

    private fun remove(pid: Int) {
        File(procRoot, pid.toString()).deleteRecursively()
    }

    private fun identity(pid: Int, start: Long = 100L, pgid: Int? = pid) =
        ProcessIdentity(pid = pid, startTimeTicks = start, processGroupId = pgid)

    /**
     * A sender that records every signal and (optionally) makes the target
     * disappear when it receives [removeOn]. Defaults to "TERM is graceful".
     */
    private fun terminator(
        removeOn: ProcessSignal? = ProcessSignal.TERM
    ) = ProcessTreeTerminator(
        expectedUid = selfUid,
        sender = ProcessSignalSender { target, signal ->
            signals += target to signal
            if (signal == removeOn) remove(target)
            true
        },
        procRoot = procRoot,
        clockMs = { nowMs },
        sleep = { nowMs += 100 },
        selfPid = 999_999
    )

    private fun signalledPids(): Set<Int> = signals.map { it.first }.toSet()

    @Test
    fun `daemon stop kills only the daemon tree and never a live terminal`() {
        // Daemon tree.
        add(100, ppid = 1, pgrp = 100)
        add(101, ppid = 100, pgrp = 100)
        add(102, ppid = 101, pgrp = 100)
        // Terminal T1: pty host + guest bash + guest apt.
        add(200, ppid = 1, pgrp = 200)
        add(201, ppid = 200, pgrp = 200)
        add(202, ppid = 201, pgrp = 200)

        val report = terminator().terminate(listOf(identity(100)))

        assertEquals(setOf(100, 101, 102), signalledPids())
        assertTrue(report.confirmed)
        assertTrue("terminal host must survive", File(procRoot, "200").exists())
        assertTrue("terminal bash must survive", File(procRoot, "201").exists())
        assertTrue("terminal apt/sleep must survive", File(procRoot, "202").exists())
    }

    @Test
    fun `two daemons stopping one leaves the other untouched`() {
        add(100, ppid = 1, pgrp = 100)
        add(101, ppid = 100, pgrp = 100)
        add(300, ppid = 1, pgrp = 300)
        add(301, ppid = 300, pgrp = 300)

        terminator().terminate(listOf(identity(100)))

        assertEquals(setOf(100, 101), signalledPids())
        assertTrue("second daemon host must survive", File(procRoot, "300").exists())
        assertTrue("second daemon child must survive", File(procRoot, "301").exists())
    }

    @Test
    fun `dead daemon host sends no signals at all`() {
        // The daemon host (100) already died; only a live PTY remains.
        add(200, ppid = 1, pgrp = 200)
        add(201, ppid = 200, pgrp = 200)

        val report = terminator().terminate(listOf(identity(100)))

        assertEquals("a dead host must never sweep the workspace", emptySet<Int>(), signalledPids())
        assertTrue(report.confirmed)
        assertTrue(File(procRoot, "201").exists())
    }

    @Test
    fun `exec timeout kills only the exec tree and never a live terminal`() {
        add(400, ppid = 1, pgrp = 400) // exec host (e.g. hung dpkg preflight)
        add(401, ppid = 400, pgrp = 400)
        add(200, ppid = 1, pgrp = 200) // T1 pty host
        add(201, ppid = 200, pgrp = 200) // T1 bash
        add(202, ppid = 201, pgrp = 200) // T1 apt

        terminator().terminate(listOf(identity(400)))

        assertEquals(setOf(400, 401), signalledPids())
        assertTrue("terminal bash must survive an exec timeout", File(procRoot, "201").exists())
        assertTrue("terminal apt must survive an exec timeout", File(procRoot, "202").exists())
    }

    @Test
    fun `pid reuse is rejected by start time and nothing is signalled`() {
        // PID 100 was recorded with start=100, but the live PID 100 is a
        // different process (start=9000) — e.g. a recycled Android PID.
        add(100, ppid = 1, pgrp = 100, start = 9000)

        val report = terminator().terminate(listOf(identity(100, start = 100)))

        assertEquals(emptySet<Int>(), signalledPids())
        assertTrue(report.confirmed)
        assertTrue("the reused PID must not be killed", File(procRoot, "100").exists())
    }

    @Test
    fun `reparented member is still found through the process group`() {
        // The daemon double-forked: its child (101) reparented to init, but it
        // is still in the daemon's session group (pgid == 100).
        add(100, ppid = 1, pgrp = 100)
        add(101, ppid = 1, pgrp = 100)
        add(200, ppid = 1, pgrp = 200) // unrelated terminal

        terminator().terminate(listOf(identity(100)))

        assertTrue("reparented group member must be contained", 101 in signalledPids())
        assertFalse("unrelated terminal must not be contained", 200 in signalledPids())
        assertTrue(File(procRoot, "200").exists())
    }

    @Test
    fun `sigterm is escalated to sigkill and termination is verified`() {
        add(100, ppid = 1, pgrp = 100)
        add(101, ppid = 100, pgrp = 100)

        // Nothing dies on TERM; everything dies on KILL.
        val report = terminator(removeOn = ProcessSignal.KILL).terminate(listOf(identity(100)))

        assertTrue("TERM must be attempted", signals.any { it.second == ProcessSignal.TERM })
        assertTrue("SIGKILL must be escalated", signals.any { it.second == ProcessSignal.KILL })
        assertTrue("termination must be verified", report.confirmed)
        assertEquals(2, report.escalated)
        assertFalse(File(procRoot, "101").exists())
    }

    @Test
    fun `an unkillable survivor is reported instead of claiming success`() {
        add(100, ppid = 1, pgrp = 100)

        // Signals are recorded but the process never disappears.
        val report = terminator(removeOn = null).terminate(
            listOf(identity(100)),
            graceMs = 200,
            hardLimitMs = 200
        )

        assertFalse("unconfirmed termination must not be reported as success", report.confirmed)
        assertEquals(listOf(100), report.survivors)
    }

    @Test
    fun `another uid is never signalled`() {
        add(100, ppid = 1, pgrp = 100, uid = 99999)

        val report = terminator().terminate(listOf(identity(100)))

        assertEquals(emptySet<Int>(), signalledPids())
        assertTrue("foreign-uid process is not ours to kill", File(procRoot, "100").exists())
        assertFalse(report.confirmed)
    }

    @Test
    fun `a zombie root counts as already gone`() {
        add(100, ppid = 1, pgrp = 100, state = 'Z')

        val report = terminator().terminate(listOf(identity(100)))

        assertEquals(emptySet<Int>(), signalledPids())
        assertTrue(report.confirmed)
    }

    @Test
    fun `a pty close reaps the tracer and every guest job without touching a sibling terminal`() {
        // SS-001 / MV-PT-10 reproduction. A Linexa terminal is a PRoot host
        // (pid 500, session/group leader) with the guest bash (501) and its
        // jobs (502 background, 503 nohup) below it. A second terminal (600…)
        // must not be touched. PRoot and interactive bash ignore SIGTERM, so
        // only the foreground sleep disappears on TERM; the rest must be
        // escalated to SIGKILL by the terminator.
        add(500, ppid = 1, pgrp = 500)
        add(501, ppid = 500, pgrp = 501)
        add(502, ppid = 501, pgrp = 502)
        add(503, ppid = 501, pgrp = 503)
        // Sibling terminal, same app and rootfs.
        add(600, ppid = 1, pgrp = 600)
        add(601, ppid = 600, pgrp = 601)

        val killingTerm = ProcessTreeTerminator(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                signals += target to signal
                // sleep 502 dies on TERM; proot/bash/nohup ignore it, and
                // SIGKILL always kills (the fake mirrors the kernel).
                if (signal == ProcessSignal.KILL || (signal == ProcessSignal.TERM && target == 502)) {
                    remove(target)
                }
                true
            },
            procRoot = procRoot,
            clockMs = { nowMs },
            sleep = { nowMs += 100 },
            selfPid = 999_999
        )
        val report = killingTerm.terminate(listOf(identity(500)), graceMs = 200, hardLimitMs = 200)

        val firstTerm = signals.filter { it.second == ProcessSignal.TERM }.map { it.first }.toSet()
        val killed = signals.filter { it.second == ProcessSignal.KILL }.map { it.first }.toSet()
        assertEquals("only this terminal's tree gets TERM", setOf(500, 501, 502, 503), firstTerm)
        assertEquals("survivors after the grace are SIGKILLed", setOf(500, 501, 503), killed)
        assertTrue("sibling terminal is never signalled", signalledPids().none { it >= 600 })
        assertTrue("the whole pty tree is confirmed gone", report.confirmed)
    }

    @Test
    fun `an unreadable proc root is best effort and sends nothing`() {
        // Android can deny /proc reads. A missing table must degrade to
        // "nothing addressable" — never to a guessed signal or a crash.
        val missing = File(tmp.root, "proc-not-readable")
        val report = ProcessTreeTerminator(
            expectedUid = selfUid,
            sender = ProcessSignalSender { target, signal ->
                signals += target to signal
                true
            },
            procRoot = missing,
            clockMs = { nowMs },
            sleep = { nowMs += 100 },
            selfPid = 999_999
        ).terminate(listOf(identity(100)))

        assertEquals(emptySet<Int>(), signalledPids())
        assertTrue(report.confirmed)
    }

    @Test
    fun `a large unrelated process table is one snapshot and signals only the target`() {
        // Finding 020: the old workspace sweep matched every own-UID PID by
        // cmdline on every stop. The replacement reads the table once, then
        // touches only target rows; 1,000 unrelated entries must not add
        // signals and must stay bounded.
        add(100, ppid = 1, pgrp = 100)
        add(101, ppid = 100, pgrp = 100)
        for (pid in 10_000 until 11_000) {
            add(pid, ppid = 1, pgrp = pid)
        }

        val started = System.nanoTime()
        val report = terminator().terminate(listOf(identity(100)))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(setOf(100, 101), signalledPids())
        assertTrue(report.confirmed)
        assertTrue("a 1,000-entry /proc snapshot must stay bounded, took ${elapsedMs}ms", elapsedMs < 5_000)
    }
}
