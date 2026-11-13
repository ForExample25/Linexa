package com.fem.linexa.ui.misc

import com.fem.linexa.ui.misc.WorkspaceMutationPolicy.Decision
import com.fem.linexa.ui.misc.WorkspaceMutationPolicy.Mutation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPT-SEC-001 — the SAF mutation policy, tested without the Android framework.
 *
 * The provider is a thin shell over [WorkspaceMutationPolicy]; these tests pin
 * the destructive rules so `createDocument`, `deleteDocument`,
 * `renameDocument` and writable `openDocument` cannot drift apart.
 */
class WorkspaceMutationPolicyTest {

    private val alwaysActive = WorkspaceMutationPolicy.WorkspaceLiveness { true }
    private val neverActive = WorkspaceMutationPolicy.WorkspaceLiveness { false }

    private fun check(
        id: String,
        mutation: Mutation,
        liveness: WorkspaceMutationPolicy.WorkspaceLiveness = neverActive
    ) = WorkspaceMutationPolicy.check(id, mutation, liveness)

    private fun assertRefused(
        decision: Decision,
        message: String = ""
    ) {
        assertTrue("expected Refused, got $decision ($message)", decision is Decision.Refused)
    }

    @Test
    fun activeWorkspaceRootfsChildDeleteIsRefused() {
        val id = LinexaDocumentsMapping.childDocumentId(
            LinexaDocumentsMapping.workspaceDocumentId("ws-1"),
            "usr"
        )
        val decision = check(id, Mutation.DELETE, alwaysActive)
        assertRefused(decision)
        assertTrue((decision as Decision.Refused).reason.contains("running", ignoreCase = true))
        assertTrue(decision.reason.contains("stop", ignoreCase = true))
    }

    @Test
    fun inactiveWorkspaceRootfsChildDeleteIsAllowed() {
        val id = LinexaDocumentsMapping.childDocumentId(
            LinexaDocumentsMapping.workspaceDocumentId("ws-1"),
            "usr/bin/bash"
        )
        assertEquals(Decision.Allowed, check(id, Mutation.DELETE, neverActive))
    }

    @Test
    fun everyMutationUnderAnActiveRootfsIsRefused() {
        val id = LinexaDocumentsMapping.childDocumentId(
            LinexaDocumentsMapping.workspaceDocumentId("ws-1"),
            "etc"
        )
        Mutation.entries.forEach { mutation ->
            assertRefused(check(id, mutation, alwaysActive), mutation.name)
        }
    }

    @Test
    fun theRootfsRootIsNeverMutableThroughSaf() {
        val id = LinexaDocumentsMapping.workspaceDocumentId("ws-1")
        listOf(alwaysActive, neverActive).forEach { liveness ->
            Mutation.entries.forEach { mutation ->
                val decision = check(id, mutation, liveness)
                assertRefused(decision, "rootfs root / $mutation")
                assertTrue(
                    (decision as Decision.Refused).reason.contains("Linexa app", ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun theVirtualWorkspaceListIsReadOnly() {
        Mutation.entries.forEach { mutation ->
            assertRefused(
                check(LinexaDocumentsMapping.ROOT_CONTAINERS, mutation, neverActive),
                mutation.name
            )
        }
    }

    @Test
    fun thePublicRootAcceptsCreatesButCannotBeRenamedOrDeleted() {
        assertEquals(Decision.Allowed, check(LinexaDocumentsMapping.ROOT_PUBLIC, Mutation.CREATE))
        assertRefused(check(LinexaDocumentsMapping.ROOT_PUBLIC, Mutation.DELETE))
        assertRefused(check(LinexaDocumentsMapping.ROOT_PUBLIC, Mutation.RENAME))
        assertRefused(check(LinexaDocumentsMapping.ROOT_PUBLIC, Mutation.WRITE))
    }

    @Test
    fun publicFilesAreWritableEvenWhenAWorkspaceIsRunning() {
        val id = LinexaDocumentsMapping.childDocumentId(LinexaDocumentsMapping.ROOT_PUBLIC, "notes.txt")
        Mutation.entries.forEach { mutation ->
            assertEquals(Decision.Allowed, check(id, mutation, alwaysActive))
        }
    }

    @Test
    fun unknownDocumentIdsAreRefused() {
        Mutation.entries.forEach { mutation ->
            assertRefused(check("containers:../escape", mutation, neverActive), mutation.name)
        }
    }
}
