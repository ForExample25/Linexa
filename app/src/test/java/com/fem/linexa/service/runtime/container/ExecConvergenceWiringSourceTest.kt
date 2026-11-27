package com.fem.linexa.service.runtime.container

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0-1 (2026-11-23 performance remediation) structural guard.
 *
 * The convergence stamp is behavioural (GuestConvergenceRegistryTest), but
 * the wiring is what makes it effective — and a future edit could restore the
 * old per-exec battery without touching the registry. These assertions pin:
 *
 *  - every one-shot exec consults and marks/invalidates the stamp;
 *  - the host overlay layer owns the five bind-over /proc names on the normal
 *    path, with the static snapshots reachable only as a fallback;
 *  - session repair success, natural session exit and workspace stop are
 *    wired to the same stamp.
 */
class ExecConvergenceWiringSourceTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private val runtime: String
        get() = File(
            moduleRoot,
            "src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt"
        ).readText()

    private val initializer: String
        get() = File(
            moduleRoot,
            "src/main/java/com/fem/linexa/service/installer/GuestRootfsInitializer.kt"
        ).readText()

    private fun function(name: String, next: String): String {
        val start = runtime.indexOf(name)
        if (start < 0) return ""
        val end = runtime.indexOf(next, start).takeIf { it > start } ?: runtime.length
        return runtime.substring(start, end)
    }

    @Test
    fun `one-shot exec consults and updates the convergence stamp`() {
        val exec = function("private suspend fun execInternal", "private fun runGuestCommand")
        assertTrue(exec.isNotEmpty())
        assertTrue(exec.contains("guestConvergence.isConverged(workspaceId, rootfs)"))
        assertTrue("a package operation must invalidate first", exec.contains("guestConvergence.invalidate(workspaceId)"))
        assertTrue(exec.contains("guestConvergence.markConverged(workspaceId, rootfs)"))
        assertTrue(
            "the cheap liveness checks must run even on a converged exec",
            exec.contains("ensureGuestTmp(rootfs") &&
                exec.contains("ensureGuestDevShm(rootfs") &&
                exec.contains("ensureGuestRun(rootfs")
        )
    }

    @Test
    fun `the host overlay layer owns proc on the normal exec path`() {
        val exec = function("private suspend fun execInternal", "private fun runGuestCommand")
        val overlay = exec.indexOf("refreshHostProcOverlays(")
        val fallback = exec.indexOf("ensureProcSnapshots(")
        assertTrue("exec must refresh host overlays", overlay >= 0)
        assertTrue("exec must keep a static fallback", fallback >= 0)
        assertTrue(
            "the static snapshots are a fallback, not a pre-write: overlay first",
            overlay < fallback
        )
    }

    @Test
    fun `session convergence and workspace stop update the same stamp`() {
        assertTrue(
            "a successful session repair must mark the stamp",
            Regex("repairConverged\\[workspaceId\\] = rootfsPath[\\s\\S]{0,800}guestConvergence\\.markConverged")
                .containsMatchIn(runtime)
        )
        assertTrue(
            "a pending dpkg recovery must not mark the stamp (it may be deferred under live activity)",
            Regex("if \\(!health\\.needsConfigure\\)[\\s\\S]{0,300}guestConvergence\\.markConverged")
                .containsMatchIn(runtime)
        )
        assertTrue(
            "terminal close and full workspace stop must invalidate it",
            runtime.contains("guestConvergence.invalidate(workspaceId)")
        )
    }

    @Test
    fun `natural session exit invalidates the stamp with the last session`() {
        assertTrue(
            "the awaitExit watcher must invalidate the convergence stamp once no live session remains, " +
                "or stale-lock recovery stays unreachable after a natural shell exit",
            Regex(
                "private fun watchSessionActivity[\\s\\S]{0,2000}" +
                    "liveSessions\\[session\\.workspaceId\\]\\?\\.decrementAndGet\\(\\)" +
                    "[\\s\\S]{0,2000}guestConvergence\\.invalidate\\(session\\.workspaceId\\)"
            ).containsMatchIn(runtime)
        )
    }

    @Test
    fun `unchanged proc snapshots are not rewritten`() {
        assertTrue(
            "the static snapshot writer needs a content check, or a converged pass still writes five files",
            initializer.contains("isSnapshotCurrent(")
        )
        assertTrue(
            "the content check must reject a symlink rather than read through it",
            Regex("private fun isSnapshotCurrent[\\s\\S]{0,500}Files\\.isSymbolicLink").containsMatchIn(initializer)
        )
    }
}
