package com.fem.linexa.data.repository

/**
 * Phase 9C (OPT-ARCH-003) — the workspace repository's secret vocabulary.
 *
 * Carved out of `WorkspaceRepositoryImpl` next to [WorkspaceDiskStore] so
 * the repository's read/write paths name the (de)cryption step as a
 * collaborator. The contract is unchanged and enforced here:
 *
 *  - everything ABOVE the repository boundary sees and writes **plaintext**
 *    secrets ([reveal] on every read, [protect] on every write);
 *  - the persisted form is ciphertext, never the plaintext it started as.
 *
 * Fail-closed rules (an unreadable secret must never surface as a silently
 * empty password; a ciphertext must never be destroyed on behalf of a
 * transient Keystore failure) are documented and implemented by
 * `WorkspaceSecretAttributes` — the single production implementation, which
 * is the class the repository tests already construct.
 *
 * [migrate] is included because the repository's own startup plumbing
 * (`migrateLegacySecrets`) drives it; the "startup plumbing stays off the
 * interface" rule refers to [WorkspaceRepository] — the consumer-facing
 * contract — not to this store seam.
 */
interface WorkspaceSecretStore {

    /** Whether writing [attributes] needs the currently stored row consulted. */
    fun needsStoredRow(attributes: Map<String, String>): Boolean

    /**
     * The form of [incoming] to persist; [existingRaw] is the currently
     * stored raw map (or null when none was read) so unchanged secrets keep
     * their stored ciphertext.
     */
    fun protect(
        workspaceId: String,
        incoming: Map<String, String>,
        existingRaw: Map<String, String>?
    ): Map<String, String>

    /** [stored] with every secret decrypted for in-memory use. */
    fun reveal(workspaceId: String, stored: Map<String, String>): Map<String, String>

    /** The migrated form of [stored], or null when nothing needs to change. */
    fun migrate(workspaceId: String, stored: Map<String, String>): Map<String, String>?
}
