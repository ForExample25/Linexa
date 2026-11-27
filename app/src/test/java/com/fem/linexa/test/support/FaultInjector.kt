package com.fem.linexa.test.support

import com.fem.linexa.data.repository.WorkspaceDiskStore
import java.io.File

/**
 * 10E (OPT-TEST-005) — the named fault-injection entry points the roadmap's
 * fault tests are built on. Test-only by construction (this file lives under
 * `src/test`); no production seam was added.
 *
 * The roadmap names three failure classes, and each is injected through the
 * seam that already fits it — one added here, two pre-existing:
 *
 * 1. **A deletion that fails partway through the walk** ("EACCES mid-walk",
 *    Phase 1) — a real same-UID JVM process cannot be denied by POSIX
 *    permissions on a path it owns: the deletion helper widens every
 *    directory inside the tree, and only the workspace base's own parent can
 *    be made unwritable (the shape `WorkspaceRepositoryDeletionTest` already
 *    exercises — a failure before anything is deleted). The failure shape the
 *    repository contract must ALSO survive — a walk that has already deleted
 *    some entries and then reports failure — is injected by
 *    [PartialDiskStore]: a delegating disk store that deletes a real prefix
 *    of the tree and then reports failure.
 *
 * 2. **HTTP responses that lie** (status, `Content-Range`, `ETag`,
 *    truncation — Phase 6) — the seam already exists:
 *    `com.fem.linexa.service.distro.FakeHttpServer` scripts exact response
 *    sequences (declared lengths, headers, bodies) against a real localhost
 *    socket. No second implementation is added here; the resume fault tests
 *    live beside it in `HttpDistroDownloaderResumeTest`.
 *
 * 3. **A descendant that ignores SIGTERM** (Phase 4) — the seam already
 *    exists: [FakeProot] scenario scripts run as REAL child processes, so
 *    signals are real. The `IGNORES_TERM` scenario added for this phase
 *    traps TERM, so the containment escalation is exercised end-to-end
 *    (`RootfsExtractorTermEscalationContainmentTest`).
 */
object FaultInjector {

    /**
     * The Phase 1 fault injection: a [WorkspaceDiskStore] that delegates
     * everything to [real] except [deleteWorkspaceTree], which deletes every
     * child of the workspace's rootfs EXCEPT the subtrees named in [keep] —
     * real filesystem deletions, the visible state a mid-walk failure leaves
     * behind — and then reports failure, exactly as a walk that hit an
     * `EACCES` would.
     */
    class PartialDiskStore(
        private val real: WorkspaceDiskStore,
        /** Subtrees of the rootfs that survive the injected failure. */
        private val keep: Set<String>
    ) : WorkspaceDiskStore by real {

        override fun deleteWorkspaceTree(workspaceId: String): Boolean {
            val rootfs = File(real.getWorkspaceBasePath(workspaceId), "rootfs")
            rootfs.listFiles()?.forEach { child ->
                if (child.name !in keep) child.deleteRecursively()
            }
            // Something survived — the injected failure the repository must honor.
            return false
        }
    }
}
