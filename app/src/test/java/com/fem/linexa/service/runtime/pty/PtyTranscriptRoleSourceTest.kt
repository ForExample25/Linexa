package com.fem.linexa.service.runtime.pty

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §16.2/§26.6 (terminal audit) — pins the two claims the corrected
 * [PtySession]/[TermuxPtySession] kdocs now make, so the *next* reader gets a
 * failing test instead of a second generation of stale comments.
 *
 * ## Why this finding needed a test at all
 *
 * The audit rated §16.2 documentation-only and recommended no coverage, which
 * is right about behaviour and wrong about durability. The comments were false
 * for as long as they were because nothing anywhere could notice: the claim
 * "no `TerminalView` is attached, nothing paints to screen yet" is not
 * expressible as an assertion about program state, and
 * [PtySession.currentTranscript]'s role — diagnostics rather than display — is
 * a fact about *who calls it*, which no unit test of either class can see.
 *
 * This codebase treats its comments as load-bearing (the audit's own severity
 * rationale: a reader trusting the old text could reasonably "restore" a
 * transcript-based fallback renderer). A doc fix with no guard is a fix with a
 * half-life.
 *
 * ## Why source text
 *
 * Both properties are structural. The first is the absence of a phrase; the
 * second is the shape of the call graph across module boundaries, and the one
 * production caller lives behind `RuntimeService`'s supervisor, which a JVM
 * test cannot instantiate. Same technique and rationale as
 * [com.fem.linexa.ui.screens.terminal.TerminalRepaintBridgeSourceTest]
 * (§1.145) and `ManagementTerminalColorEnvSourceTest` (§26.2).
 */
class PtyTranscriptRoleSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = File("src/main/java/com/fem/linexa/service/runtime/pty/PtySession.kt")
        var dir: File? = System.getProperty("user.dir")?.let { File(it) }
        while (dir != null) {
            if (File(dir, marker.path).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private val mainSourceRoot: File
        get() = File(moduleRoot, "src/main/java/com/fem/linexa")

    private fun source(path: String): String = File(mainSourceRoot, path).readText()

    private val ptySession: String get() = source("service/runtime/pty/PtySession.kt")

    private val termuxPtySession: String get() = source("service/runtime/pty/TermuxPtySession.kt")

    /**
     * The pty package's own files are excluded: the declaration site and the
     * implementation's `override` necessarily mention the name, and neither is
     * a consumer.
     */
    private val ptyPackage: File
        get() = File(mainSourceRoot, "service/runtime/pty")

    @Test
    fun neitherPtyDocStillClaimsTheTerminalIsHeadless() {
        // `RealTerminalView` attaches a real terminal-lib `TerminalView` to
        // TermuxPtySession's `nativeSession` and is the only renderer in the
        // app, so "headless" describes no version of Linexa that shipped this
        // class.
        for ((name, text) in listOf("PtySession.kt" to ptySession, "TermuxPtySession.kt" to termuxPtySession)) {
            assertFalse(
                "$name must not describe the terminal as headless or its renderer as " +
                    "prospective (§16.2) — RealTerminalView has always been the production " +
                    "rendering path for this session",
                text.contains("headless", ignoreCase = true)
            )
            assertFalse(
                "$name must not describe currentTranscript() as a stopgap for a " +
                    "\"line-oriented Terminal UI\" (§16.2) — that UI was never built",
                text.contains("line-oriented Terminal UI")
            )
        }
    }

    @Test
    fun theTranscriptDocPointsAtItsRealCaller() {
        assertTrue(
            "PtySession.currentTranscript()'s kdoc must name its actual role and caller, so a " +
                "reader can check the claim instead of inheriting it (§16.2)",
            ptySession.contains("WorkspaceSessionSupervisor")
        )
    }

    @Test
    fun theTranscriptHasExactlyOneProductionCallerAndItIsNotAUi() {
        // This is the assertion that keeps the corrected doc true rather than
        // merely currently-accurate: wiring currentTranscript() into a screen
        // is exactly the change that would falsify it, and it would otherwise
        // be invisible — the call compiles and returns plausible text.
        val callers = mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.startsWith(ptyPackage) }
            .filter { it.readText().contains("currentTranscript()") }
            .map { it.relativeTo(mainSourceRoot).path }
            .toSortedSet()

        assertEquals(
            "currentTranscript() is diagnostics-only: §1.76's dead-child-at-open path is its " +
                "single production consumer. A new caller — especially in ui/ — means the " +
                "PtySession kdoc is now wrong again and the transcript has quietly become a " +
                "rendering or product surface",
            setOf("service/runtime/WorkspaceSessionSupervisor.kt"),
            callers.toSet()
        )
    }
}
