package com.fem.linexa.service.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 9B (OPT-ARCH-002) — the rootfs-identity source contracts.
 *
 * The workspace ID is the single rootfs identity crossing a process
 * boundary; the rootfs path is derived inside [RuntimeService] from
 * `WorkspaceFileManager` (the single path authority), and the persisted
 * `WorkspaceMetadata.rootfsPath` is diagnostic only.
 *
 * These are source-shape tests (the `LifecycleConformanceSourceTest`
 * idiom) because the contract is about *what crosses the Intent
 * boundary* — a thing bytecode analysis cannot see: an extra can be put
 * and read without any type relationship, and a dropped extra leaves no
 * compile error. The shapes below are literal call shapes, not words, so
 * prose comments cannot satisfy them by accident — and the negative
 * contracts (no extra, no path read) are the acceptance criteria pinned here.
 */
class RootfsIdentitySourceTest {

    // ── RuntimeService: the derivation is THE path ────────────────────────

    @Test
    fun runtimeServiceDerivesTheRootfsInsideTheService() {
        val src = source("src/main/java/com/fem/linexa/service/runtime/RuntimeService.kt")

        assertTrue(
            "ACTION_START must derive the rootfs path from the workspace id via " +
                "WorkspaceFileManager (Phase 9B: the id is the single rootfs identity)",
            src.contains("fileManager.getRootfsPath(workspaceId).absolutePath")
        )
        assertTrue(
            "startIntent must take exactly (context, workspaceId) — no rootfs path parameter",
            src.contains("fun startIntent(context: Context, workspaceId: String)")
        )
    }

    @Test
    fun runtimeServiceHasNoRootfsPathExtra() {
        val src = source("src/main/java/com/fem/linexa/service/runtime/RuntimeService.kt")

        assertFalse(
            "EXTRA_ROOTFS_PATH must not exist — the extra was removed in Phase 9B; " +
                "re-adding it re-opens the 'identity arrives from the caller' hole the " +
                "§P0-fix audit G11 derivation closed",
            src.contains("EXTRA_ROOTFS_PATH")
        )
        assertFalse(
            "the 'rootfs_path' extra string must not exist in any form",
            src.contains("\"rootfs_path\"")
        )
    }

    // ── RuntimeViewModel: only the id is sent ─────────────────────────────

    @Test
    fun runtimeViewModelSendsOnlyTheWorkspaceId() {
        val src = source("src/main/java/com/fem/linexa/service/runtime/RuntimeViewModel.kt")

        assertTrue(
            "the start call must be the 2-arg startIntent (context, workspaceId)",
            src.contains("RuntimeService.startIntent(context, workspaceId)")
        )
        assertTrue(
            "the repository existence gate must survive — a start for a row that is " +
                "gone must stay a no-op, not a service start",
            src.contains("workspaceRepository.getById(workspaceId) ?: return@launch")
        )
        assertFalse(
            "no launch path may read the persisted rootfs path (Phase 9B: the field is " +
                "diagnostic; the service derives its own path from the id)",
            src.contains("rootfsPath")
        )
    }

    // ── The diagnostic field stays honest ─────────────────────────────────

    @Test
    fun backfillRepairsAStaleRootfsPath() {
        val src = source("src/main/java/com/fem/linexa/service/installer/WorkspaceMetadataBackfillManager.kt")

        assertTrue(
            "the startup backfill must compare the persisted rootfs path against the " +
                "derived authority (the workspace id's path)",
            src.contains("metadata.rootfsPath !=")
        )
        assertTrue(
            "the repair must rewrite the row through the repository mutate transform, " +
                "not a whole-row update (Phase 05 targeted-write rule)",
            src.contains("copy(rootfsPath =")
        )
    }

    @Test
    fun workspaceMetadataKeepsTheDiagnosticColumn() {
        val src = source("src/main/java/com/fem/linexa/domain/workspace/WorkspaceMetadata.kt")

        assertTrue(
            "the rootfsPath column stays (diagnostic; removing it changes the Room schema " +
                "for no functional gain — same rule as the inert plugin column)",
            src.contains("val rootfsPath: String,")
        )
    }

    private fun source(relativePath: String): String =
        File(moduleRoot(), relativePath).readText()

    /**
     * Walks up from the working directory to the `app` module root, keyed off a
     * file that is certain to exist. Same locator idiom as
     * `ImportBoundaryTest`/`MotionSystemSourceTest`.
     */
    private fun moduleRoot(): File {
        val marker = "src/main/java/com/fem/linexa/core/security/ArchiveValidator.kt"
        var dir: File? = File(requireNotNull(System.getProperty("user.dir")))
        while (dir != null) {
            if (File(dir, marker).exists()) return dir
            dir = dir.parentFile
        }
        error("module root not found from ${System.getProperty("user.dir")}")
    }
}
