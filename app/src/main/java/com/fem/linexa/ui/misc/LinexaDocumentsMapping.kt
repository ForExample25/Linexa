package com.fem.linexa.ui.misc

import com.fem.linexa.domain.workspace.WorkspaceStatus

/**
 * Pure, framework-free mapping between SAF document IDs (§1.74) and Linexa's
 * on-disk layout, plus the display-name rules for the "Containers" root.
 * Deliberately android-free so every rule here is unit-testable on the JVM.
 *
 * Document-ID shape (the "<rootType>:<relative path>" concept from Tiny
 * Container, adapted — not copied):
 *   "containers"                       → the virtual Containers root
 *   "containers:<workspaceId>"         → one workspace's rootfs directory
 *   "containers:<workspaceId>/usr/bin" → a directory inside that rootfs
 *   "public"                           → the virtual Public root
 *   "public/Shared Music/song.mp3"     → a file under the shared-storage dir
 *
 * Only the FIRST ':' is structural — colons inside names are legal, so a
 * rootfs file named "a:b.txt" round-trips unchanged. Path segments are
 * validated so a document ID can never express an escape ("..", empty or
 * absolute segments are rejected); the provider additionally re-checks the
 * canonical path at resolution time.
 */
object LinexaDocumentsMapping {

    const val ROOT_CONTAINERS = "containers"
    const val ROOT_PUBLIC = "public"

    /** Display-name cap — generous, still file-manager friendly. */
    private const val MAX_DISPLAY_NAME_LENGTH = 64

    /** Fallback when sanitizing produces an empty name. */
    private const val FALLBACK_DISPLAY_NAME = "workspace"

    /** Result of parsing a document ID. */
    sealed interface Decoded {
        /** "containers" — virtual root listing browsable workspaces. */
        data object ContainersRoot : Decoded

        /** "containers:<workspaceId>" — the workspace's rootfs directory itself. */
        data class Workspace(val workspaceId: String) : Decoded

        /** "containers:<workspaceId>/a/b" — a path below that rootfs. */
        data class ContainersFile(val workspaceId: String, val relativeSegments: List<String>) : Decoded

        /** "public" — virtual root listing the shared-storage directory. */
        data object PublicRoot : Decoded

        /** "public/a/b" — a path below the shared-storage directory. */
        data class PublicFile(val relativeSegments: List<String>) : Decoded
    }

    /** Document ID of a virtual root itself. */
    fun rootDocumentId(root: String): String = root

    /** Document ID of a workspace's rootfs directory. */
    fun workspaceDocumentId(workspaceId: String): String = "$ROOT_CONTAINERS:$workspaceId"

    /**
     * Document ID of the child named [childName] below [parentId].
     * Children of a virtual root use the "{rootType}:{name}" form (the
     * root's own id has no colon), so "public:<name>" round-trips through
     * [decode]; everything else appends with "/".
     */
    fun childDocumentId(parentId: String, childName: String): String =
        if (parentId == ROOT_CONTAINERS || parentId == ROOT_PUBLIC) {
            "$parentId:$childName"
        } else {
            "$parentId/$childName"
        }

    /**
     * Parses a document ID. Returns null for any malformed or unknown ID —
     * the provider turns null into FileNotFoundException, per the
     * DocumentsProvider contract.
     */
    fun decode(documentId: String): Decoded? {
        if (documentId.isEmpty()) return null
        val colon = documentId.indexOf(':')
        if (colon < 0) {
            return when (documentId) {
                ROOT_CONTAINERS -> Decoded.ContainersRoot
                ROOT_PUBLIC -> Decoded.PublicRoot
                else -> null
            }
        }
        val rootType = documentId.substring(0, colon)
        val raw = documentId.substring(colon + 1)
        return when (rootType) {
            ROOT_CONTAINERS -> {
                if (raw.isEmpty()) {
                    Decoded.ContainersRoot
                } else {
                    val segments = splitSegments(raw) ?: return null
                    if (segments.size == 1) {
                        Decoded.Workspace(segments[0])
                    } else {
                        Decoded.ContainersFile(segments.first(), segments.drop(1))
                    }
                }
            }
            ROOT_PUBLIC -> {
                if (raw.isEmpty()) {
                    Decoded.PublicRoot
                } else {
                    Decoded.PublicFile(splitSegments(raw) ?: return null)
                }
            }
            else -> null
        }
    }

    /**
     * A workspace appears in the Containers root iff its DB status is
     * [WorkspaceStatus.Ready] AND the rootfs is real on disk — the same
     * gate as WorkspaceLaunchValidator.evaluate(...) == Ready, so the file
     * manager never shows a half-imported, half-deleted or wiped workspace.
     */
    fun isBrowsable(status: WorkspaceStatus, rootfsOnDisk: Boolean): Boolean =
        status is WorkspaceStatus.Ready && rootfsOnDisk

    /**
     * Filesystem-safe human-readable name: keeps letters, digits, space,
     * '.', '_' and '-'; everything else becomes '_'; leading/trailing dots,
     * spaces and underscores are trimmed; '_' runs collapse; length is
     * capped. Never returns an empty string.
     */
    fun sanitizeDisplayName(raw: String): String {
        val cleaned = buildString(raw.length) {
            raw.forEach { c ->
                val keep = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                    c == ' ' || c == '.' || c == '_' || c == '-'
                append(if (keep) c else '_')
            }
        }
        val trimmed = cleaned.trim(' ', '.')
        val collapsed = trimmed.replace(Regex("_+"), "_")
        val stripped = collapsed.trim(' ', '.', '_')
        val capped = stripped.take(MAX_DISPLAY_NAME_LENGTH)
        return capped.ifEmpty { FALLBACK_DISPLAY_NAME }
    }

/**
     * Stable, order-independent workspaceId → display-name mapping for the
     * Containers root. Every assigned name is guaranteed unique: a name
     * used by exactly one workspace is kept as-is unless it was already
     * taken (e.g. exactly matching another workspace's suffixed name), in
     * which case it too gets a short-id suffix. Colliding names get a
     * deterministic short-id suffix that lengthens until unique. Suffixed
     * names use only lower-case hex, so they keep document IDs stable
     * across queries and process restarts.
     */
    fun assignDisplayNames(entries: Collection<Pair<String, String>>): Map<String, String> {
        val sanitized = entries.associate { (id, raw) -> id to sanitizeDisplayName(raw) }
        val idsByName = sanitized.entries.groupBy({ it.value }, { it.key })
        val taken = mutableSetOf<String>()
        val result = LinkedHashMap<String, String>()
        for ((name, ids) in idsByName.toSortedMap()) {
            for (id in ids.sorted()) {
                var candidate = name
                if (ids.size > 1 || candidate in taken) {
                    var width = 8
                    candidate = "$name-${id.take(width)}"
                    while (candidate in taken) {
                        width += 4
                        candidate = "$name-${id.take(width)}"
                    }
                }
                result[id] = candidate
                taken += candidate
            }
        }
        return result
    }

    /**
     * Splits a relative path into validated segments. Rejects empty
     * segments, "..", and absolute paths; "." passes through untouched.
     */
    private fun splitSegments(relativePath: String): List<String>? {
        if (relativePath.startsWith('/')) return null
        val segments = relativePath.split('/')
        if (segments.any { it.isEmpty() || it == ".." }) return null
        return segments
    }
}
