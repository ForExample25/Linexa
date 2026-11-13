package com.fem.linexa.ui.misc

import com.fem.linexa.ui.misc.LinexaDocumentsMapping.Decoded

/**
 * Answers "may a SAF client mutate this document right now?".
 *
 * ## Why this is a separate, framework-free object
 *
 * [LinexaDocumentsProvider] is an Android shell that needs a live Hilt graph.
 * The destructive policy is the part that must never drift between
 * `createDocument`, `deleteDocument`, `renameDocument` and writable
 * `openDocument`, so it lives here where every branch is a plain JVM test.
 *
 * ## Policy
 *
 * | Path | Inactive workspace | Active workspace |
 * |---|---|---|
 * | Workspace rootfs root (`containers:<id>`) | Delete/rename refused (use the app) | All mutations refused |
 * | Any path below `rootfs/` | Mutations allowed | Mutations refused; reads allowed |
 * | `Public` root and its contents | All mutations allowed | All mutations allowed |
 * | Workspace list root (`containers`) | Mutations refused (virtual root) | Mutations refused |
 *
 * The rootfs root is refused even when the workspace is inactive: deleting it
 * through SAF would leave the database row behind and turn a healthy workspace
 * into `Broken`. The app's Delete action is the only path that coordinates the
 * runtime stop, the disk tree and the row.
 *
 * "Active" includes `Starting`, `Running` and `Stopping`; a teardown is still
 * touching the tree, so it is not safe to mutate it.
 */
object WorkspaceMutationPolicy {

    /** The SAF operation being attempted. Reads are never checked here. */
    enum class Mutation { CREATE, DELETE, RENAME, WRITE }

    sealed interface Decision {
        data object Allowed : Decision
        data class Refused(val reason: String) : Decision
    }

    /** Liveness query, abstracted so the policy can be tested without a service. */
    fun interface WorkspaceLiveness {
        fun isActive(workspaceId: String): Boolean
    }

    fun check(
        documentId: String,
        mutation: Mutation,
        liveness: WorkspaceLiveness
    ): Decision {
        val decoded = LinexaDocumentsMapping.decode(documentId)
            ?: return Decision.Refused("Unknown document ID: $documentId")
        return when (decoded) {
            Decoded.ContainersRoot -> Decision.Refused(
                "The Linexa workspace list is read-only; open a workspace to change its files."
            )

            is Decoded.Workspace -> {
                // The rootfs root is never mutable through SAF: a delete here
                // would orphan the database row, and a rename would detach the
                // runtime's rootfs path.
                Decision.Refused(
                    "A workspace's root filesystem cannot be changed through Files. " +
                        "Use the Linexa app to delete or repair the workspace."
                )
            }

            is Decoded.ContainersFile -> {
                if (liveness.isActive(decoded.workspaceId)) {
                    Decision.Refused(
                        "This workspace is running. Stop it before ${mutation.verb()} files in its rootfs."
                    )
                } else {
                    Decision.Allowed
                }
            }

            Decoded.PublicRoot -> {
                if (mutation == Mutation.CREATE) {
                    Decision.Allowed
                } else {
                    Decision.Refused(
                        "The Public root cannot be renamed or deleted; change its contents instead."
                    )
                }
            }

            is Decoded.PublicFile -> Decision.Allowed
        }
    }

    private fun Mutation.verb(): String = when (this) {
        Mutation.CREATE -> "creating"
        Mutation.DELETE -> "deleting"
        Mutation.RENAME -> "renaming"
        Mutation.WRITE -> "writing"
    }
}
