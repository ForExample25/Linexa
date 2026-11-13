package com.fem.linexa.domain.runtime

import com.fem.linexa.service.runtime.RuntimeBinder
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.container.OPERATION_TIMEOUT_SECONDS
import com.fem.linexa.domain.workspace.RootfsInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9A (OPT-ARCH-001) — the port pins.
 *
 * The ArchUnit rules in `ImportBoundaryTest` forbid `domain → service`
 * dependencies; these tests pin the OTHER half of the same claim: the service
 * adapters really do implement the domain ports, so a caller that holds the
 * concrete type can still pass it where the port is expected (that
 * substitution is exactly what made the 9A swap compile-clean for every
 * existing call site).
 *
 * Reflection only — no binder is constructed (`RuntimeBinder` extends
 * `android.os.Binder`; loading the class is fine in a JVM test,
 * instantiating it is not, and is not needed for an assignability check).
 */
class DomainPortsTest {

    @Test
    fun runtimeBinderImplementsCommandExecutor() {
        assertTrue(
            "RuntimeBinder must implement the domain CommandExecutor port — the domain's " +
                "exec/execStreaming surface IS this port (Phase 9A); if the supertype was dropped, " +
                "every domain call site holding the concrete binder still compiles but the port " +
                "claim is void",
            CommandExecutor::class.java.isAssignableFrom(RuntimeBinder::class.java)
        )
    }

    @Test
    fun workspaceFileManagerImplementsRootfsInspector() {
        assertTrue(
            "WorkspaceFileManager must implement the domain RootfsInspector port — " +
                "WorkspaceLaunchValidator's disk-sufficiency checks go through it (Phase 9A)",
            RootfsInspector::class.java.isAssignableFrom(WorkspaceFileManager::class.java)
        )
    }

    @Test
    fun thePortTimeoutMatchesTheContainerSeam() {
        assertEquals(
            "CommandExecutor.DEFAULT_OPERATION_TIMEOUT_SECONDS and the container seam's " +
                "OPERATION_TIMEOUT_SECONDS must stay equal — the port duplicates the constant " +
                "because domain cannot import the container seam (ImportBoundaryTest Rule 1); " +
                "this pin turns a silent drift into a build failure",
            OPERATION_TIMEOUT_SECONDS,
            CommandExecutor.DEFAULT_OPERATION_TIMEOUT_SECONDS
        )
    }
}
