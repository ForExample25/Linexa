package com.fem.linexa.ui.screens.terminal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-08-30 — the terminal lifecycle **provenance** contract.
 *
 * The black-new-session defect could not be attributed statically: the device
 * proves the render pipeline works (the session-exit banner paints), which
 * means the break is in the output/bind chain — reader death, a view bound
 * without a size, a bind to the wrong emulator, or a lost repaint. Every one
 * of those links now emits a FIRST-OCCURRENCE, state-change-only log line
 * through the existing logging bridge, so one device run of
 * "create session → black → exit" pins the exact break:
 *
 *   TerminalSession.initializeEmulator   emulator=<id> fd pid thread=<name>
 *   TermSessionInputReader               first read bytes=N        (output alive)
 *   TermSessionInputReader               DIED <exception>          (silent death — FIXED)
 *   TerminalSession.handleMessage        first emulator append     (emulator content)
 *   TerminalView.attachSession           session=<id> view=<id> w h
 *   TerminalView.updateSize              EARLY-RETURN | BIND cols rows emulator=<id>
 *   TerminalView.onDraw                  NO-EMULATOR (black fill) | first draw
 *   RealTerminalView                     FACTORY / UPDATE re-attach / RELEASE
 *   RepaintBridge armed                  revision=N viewBound=…
 *
 * This test pins that each link still logs — the diagnostics are load-bearing
 * for the open device investigation and must not silently rot.
 */
class TerminalLifecycleDiagnosticsSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/MainActivity.kt")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun repoRoot(): File {
        var dir: File? = moduleRoot
        while (dir != null && !File(dir, "terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java").exists()) {
            dir = dir.parentFile
        }
        return dir ?: moduleRoot
    }

    private fun source(relative: String): String = File(repoRoot(), relative).readText()

    private val session: String
        get() = source("terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java")

    private val view: String
        get() = source("terminal-view/src/main/java/com/termux/view/TerminalView.java")

    private val realTerminalView: String
        get() = source("app/src/main/java/com/fem/linexa/ui/screens/terminal/RealTerminalView.kt")

    @Test
    fun aDeadReaderThreadIsNoLongerSwallowedSilently() {
        // THE diagnostic gap: a reader that dies left the session alive and
        // writable with ZERO output ever reaching the emulator — the exact
        // black-screen-with-working-shell signature — and no trace anywhere.
        assertTrue(
            "the reader thread must log its death loudly (client logging bridge)",
            session.contains("InputReader DIED") &&
                session.contains("logStackTraceWithMessage")
        )
        assertTrue(
            "reader EOF must be visible too — output can never arrive after it",
            session.contains("InputReader EOF")
        )
        assertTrue(
            "the first successful read must be logged (proof the pty output path is alive)",
            session.contains("InputReader first read")
        )
    }

    @Test
    fun emulatorConstructionLogsItsProvenance() {
        assertTrue(
            "initializeEmulator must record the emulator identity, fd, pid and the " +
                "CONSTRUCTING THREAD (that thread's Looper owns all emulator writes)",
            session.contains("initializeEmulator emulator=") &&
                session.contains("Thread.currentThread().getName()")
        )
        assertTrue(
            "the first emulator append must be logged (proof content reached the emulator)",
            session.contains("first emulator append")
        )
    }

    @Test
    fun theViewBindAndRenderLinksAreLogged() {
        assertTrue(
            "attachSession must log the (session, view, size) tuple",
            view.contains("attachSession session=")
        )
        assertTrue(
            "updateSize must log its EARLY-RETURN (unsized bind attempt — mEmulator stays null)",
            view.contains("updateSize EARLY-RETURN")
        )
        assertTrue(
            "updateSize must log the actual BIND (view, geometry, emulator identity)",
            view.contains("updateSize BIND")
        )
        assertTrue(
            "onDraw must distinguish NO-EMULATOR (black fill) from the first real draw",
            view.contains("onDraw NO-EMULATOR (black fill)") &&
                view.contains("onDraw first draw emulator=")
        )
    }

    @Test
    fun theComposeBridgeLogsItsLifecycle() {
        assertTrue(
            "factory/update/release bindings must be logged with identities",
            realTerminalView.contains("TerminalView FACTORY") &&
                realTerminalView.contains("TerminalView UPDATE re-attach") &&
                realTerminalView.contains("TerminalView RELEASE")
        )
        assertTrue(
            "the repaint bridge must log that it armed and whether a view was bound",
            realTerminalView.contains("RepaintBridge armed")
        )
    }

    @Test
    fun theDiagnosticsAreFirstOccurrenceOnlyNotPerFrame() {
        // Noisy per-frame logging would defeat the purpose; each link must be
        // gated on a state change or first occurrence.
        assertTrue(
            "reader first-read is a one-shot flag",
            session.contains("mFirstRead")
        )
        assertTrue(
            "first-append is a one-shot flag",
            session.contains("mFirstAppendLogged")
        )
        assertTrue(
            "view bind logging is state-change gated (early-return vs emulator identity)",
            view.contains("mLastBindLogState") && view.contains("mLastDrawEmulatorId")
        )
    }
}
