package com.fem.linexa.service.exporter

/**
 * What a RootFS export does with guest credential material (finding 022).
 *
 * A RootFS export is a copy of the whole guest filesystem, which means it also
 * copies whatever the guest stores under `~/.vnc`, `~/.ssh` and `~/.gnupg`.
 * The export UI therefore always asks the user first; the choice is carried
 * through [ExportRequest] and applied by the packing walk, never by mutating
 * the live workspace.
 *
 * [REMOVE_CREDENTIALS] is the default for a bare [ExportRequest] so a caller
 * that forgets to decide cannot silently leak credentials. The UI always
 * decides explicitly, because it must show the warning either way.
 */
enum class ExportCredentialPolicy {
    /** Omit the paths matched by [ExportCredentialPaths] from the archive. */
    REMOVE_CREDENTIALS,

    /** Pack the guest tree verbatim, credentials included, after the warning. */
    INCLUDE_CREDENTIALS,
}

/**
 * The guest paths [ExportCredentialPolicy.REMOVE_CREDENTIALS] excludes.
 *
 * These are the audit's three concrete secrets, matched on archive-relative
 * paths (always `/`-separated, never a filesystem path):
 *
 *  - `.vnc` files whose names end in `passwd`, at any depth — the VNC password
 *    file, including the app's own `linexa-vnc-passwd`;
 *  - `.ssh/id_*`, at any depth — SSH private keys. Public `.pub` companions
 *    are kept: they are not secrets and are useless without the private key;
 *  - `.gnupg/`, at any depth — the whole GnuPG home, including keyrings and
 *    agent state.
 *
 * Matching a directory prunes its entire subtree during the walk, so a
 * removed `.gnupg` directory is never descended (and its unreadable members
 * can never fail the export). Paths that merely resemble these names — a
 * `notes/vnc-passwd.txt`, a `.gnupg-backup` directory, or a public key — are
 * deliberately left alone.
 */
internal object ExportCredentialPaths {

    private const val VNC_DIR = ".vnc"
    private const val VNC_PASSWD_SUFFIX = "passwd"
    private const val SSH_DIR = ".ssh"
    private const val SSH_PRIVATE_KEY_PREFIX = "id_"
    private const val SSH_PUBLIC_KEY_SUFFIX = ".pub"
    private const val GNUPG_DIR = ".gnupg"

    /** True when [relativePath] itself is credential material (file, link or directory). */
    fun matches(relativePath: String): Boolean {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false

        // The whole GnuPG home, at any depth. Matching the directory entry
        // also prunes every descendant in the walk.
        if (GNUPG_DIR in segments) return true

        if (segments.size < 2) return false
        val name = segments.last()
        return when (segments[segments.size - 2]) {
            VNC_DIR -> name.endsWith(VNC_PASSWD_SUFFIX)
            SSH_DIR -> name.startsWith(SSH_PRIVATE_KEY_PREFIX) &&
                !name.endsWith(SSH_PUBLIC_KEY_SUFFIX)
            else -> false
        }
    }
}
