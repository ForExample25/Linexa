package com.fem.linexa.service.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0-2 fix verification (ADR-0006), amended by Phase 07 (OPT-REL-005).
 *
 * RuntimeService.onDestroy is an Android lifecycle callback that cannot be
 * instantiated in a pure JVM test. The teardown CONTRACT is therefore verified
 * two ways:
 *
 * 1. Constant tests: the teardown timeout is bounded (3 s per ADR-0006).
 * 2. Source tests (mirroring [SessionConstructionThreadingSourceTest]): the
 *    scheduling contract is a thread-affinity/shape property of the source —
 *    the original bug was "cleanup launched into a scope that was cancelled
 *    before it ran", the first fix's cost was "runBlocking on Main for up to
 *    the full budget", and the final design is "synchronous marker write →
 *    bounded teardown on a separate IO scope → serviceScope cancelled". Those
 *    are exactly the things a source assertion can pin.
 *
 * Full integration testing (verifying that sessions are actually closed and
 * no orphans remain) requires a device; no instrumentation test covers it.
 */
class RuntimeServiceTeardownTest {

    private lateinit var moduleRoot: File

    @Before
    fun locateModuleRoot() {
        val marker = "src/main/java/com/fem/linexa/service/runtime/RuntimeService.kt"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, marker).exists()) {
                moduleRoot = dir
                return
            }
            dir = dir.parentFile
        }
        moduleRoot = File(".")
    }

    private fun source(relative: String): String =
        File(moduleRoot, "src/main/java/$relative").readText()

    private val serviceSource: String
        get() = source("com/fem/linexa/service/runtime/RuntimeService.kt")

    private val managerSource: String
        get() = source("com/fem/linexa/service/runtime/RuntimeLifecycleManager.kt")

    /** The onDestroy body: from its signature to the companion object. */
    private val onDestroyBody: String
        get() = serviceSource
            .substringAfter("override fun onDestroy() {")
            .substringBefore("\n    companion object {")

    /**
     * The onDestroy CODE — body with comment lines removed. The method's
     * history comment deliberately names `runBlocking` (documenting the two
     * prior designs), so the contract assertions must judge the code, not the
     * prose.
     */
    private val onDestroyCode: String
        get() = onDestroyBody
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    @Test
    fun teardownTimeoutIsThreeSeconds() {
        assertEquals(
            "TEARDOWN_TIMEOUT_MS must be 3000 (3 seconds) per ADR-0006",
            3_000L,
            RuntimeService.TEARDOWN_TIMEOUT_MS
        )
    }

    @Test
    fun teardownTimeoutIsReasonableUpperBound() {
        assertTrue(
            "Teardown timeout must be ≤5 seconds (bounded async teardown)",
            RuntimeService.TEARDOWN_TIMEOUT_MS <= 5_000L
        )
        assertTrue(
            "Teardown timeout must be ≥1 second (give closeWorkspaceSessions time to work)",
            RuntimeService.TEARDOWN_TIMEOUT_MS >= 1_000L
        )
    }

    @Test
    fun constantIsPublicAndStable() {
        // The constant must be accessible from tests and from the service itself.
        val timeout: Long = RuntimeService.TEARDOWN_TIMEOUT_MS
        assertTrue(timeout > 0)
    }

    // ── Phase 07 (OPT-REL-005): onDestroy scheduling contract ────────────

    @Test
    fun onDestroyDoesNotBlockTheMainThread() {
        assertTrue(
            "onDestroy must not runBlocking on Main — the bounded teardown runs on cleanupScope (IO). " +
                "Found the old synchronous form in:\n$onDestroyCode",
            !onDestroyCode.contains("runBlocking")
        )
        assertTrue(
            "onDestroy must hand teardown to cleanupScope",
            onDestroyCode.contains("cleanupScope")
        )
    }

    @Test
    fun onDestroyPersistsShutdownMarkersBeforeReturning() {
        val markersIndex = onDestroyCode.indexOf("writeShutdownMarkers()")
        val scopeIndex = onDestroyCode.indexOf("cleanupScope")
        assertTrue(
            "onDestroy must write the shutdown markers synchronously — the durable record of pending " +
                "teardown must exist before the method returns (the process may be killed right after)",
            markersIndex >= 0
        )
        assertTrue(
            "the marker write must come BEFORE the async teardown launch",
            scopeIndex < 0 || markersIndex < scopeIndex
        )
    }

    @Test
    fun onDestroyRunsBoundedTeardownOnCleanupScopeNotServiceScope() {
        assertTrue(
            "the teardown must stay bounded by TEARDOWN_TIMEOUT_MS",
            onDestroyCode.contains("withTimeoutOrNull(TEARDOWN_TIMEOUT_MS)")
        )
        assertTrue(
            "the teardown must be launched into cleanupScope (not serviceScope — the original P0-2 leak)",
            onDestroyCode.contains("cleanupScope") && onDestroyCode.contains("lifecycleManager.shutdown()")
        )
        assertTrue(
            "cleanupScope must self-cancel via invokeOnCompletion once the teardown job ends",
            onDestroyCode.contains("invokeOnCompletion")
        )
        assertTrue(
            "serviceScope (Main-scope UI/notification work) must still be cancelled",
            onDestroyCode.contains("serviceScope.cancel()")
        )
        assertTrue(
            "cleanupScope must be an IO scope separate from serviceScope's Main",
            serviceSource.contains("private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)")
        )
    }

    @Test
    fun theManagerWritesClearsAndConsumesShutdownMarkers() {
        assertTrue(
            "the marker constant must exist with its contract name",
            managerSource.contains("const val SHUTDOWN_MARKER = \".shutdown-pending\"")
        )
        val stopBody = managerSource
            .substringAfter("private suspend fun stopWorkspaceLocked")
            .substringBefore("\n    companion object {")
        assertTrue(
            "a SUCCESSFUL stop must clear the marker (the teardown it reserved has run)",
            stopBody.contains("clearShutdownMarker(workspaceId)")
        )
        val sweepBody = managerSource
            .substringAfter("private suspend fun runStartupSweepLocked(): RecoverySweepReport {")
            .substringBefore("\n    /**")
        assertTrue(
            "the startup sweep must consume markers left by a crashed previous process",
            sweepBody.contains("consumeShutdownMarkers()")
        )
    }
}
