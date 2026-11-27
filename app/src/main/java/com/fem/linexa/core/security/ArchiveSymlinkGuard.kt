package com.fem.linexa.core.security

/**
 * Stateful companion to [ArchiveValidator.checkEntry] for the extraction
 * pre-scans (RF-001).
 *
 * `checkEntry` judges one entry in isolation. That is not enough for a tar
 * stream, for two reasons the audit identified:
 *
 * 1. **Order matters.** A tar can declare `usr/bin/x` as a symlink and then
 *    list `usr/bin/x/payload`. Extracting the second member through the
 *    already-created link can write where the link points, even though the
 *    second member's own name contains no `..`. This guard remembers declared
 *    links and rejects any later entry whose path has one as an ancestor.
 *    A later non-link entry at the same path is treated as a replacement (GNU
 *    tar unlinks the old member first), so legitimate archives that replace a
 *    link with a directory keep working.
 *
 * 2. **`--strip-components` moves the boundary.** The pre-scan sees the raw
 *    member name, but extraction strips N leading components before writing.
 *    A link that resolves exactly to the raw root (`usr/bin/x -> ../../..`)
 *    is safe raw and escapes after a one-component strip. [linksAfterStrip]
 *    yields the links in their post-strip coordinates so the caller can run
 *    the normal [ArchiveValidator.checkEntry] rule again.
 *
 * The guard stores only symlink paths and targets. A rootfs carries tens of
 * thousands of symlinks at most, so this is bounded by the archive itself and
 * far smaller than the extraction; it is never the full entry list.
 */
class ArchiveSymlinkGuard {

    data class Entry(
        /** Normalized path after stripping, suitable for [ArchiveValidator.checkEntry]. */
        val path: String,
        val target: String,
        /** Raw stored name, used for the user-facing diagnostics. */
        val rawName: String,
    )

    private data class Declared(val target: String)

    private val declared = LinkedHashMap<String, Declared>()

    /**
     * Returns a reason when [normalizedPath] would be extracted through a
     * symlink declared earlier in the same archive, or null when it is safe.
     *
     * Only *ancestors* are checked: the entry that declares a link is itself
     * allowed, and replacing that link later is allowed via [observe].
     */
    fun checkEntryPath(normalizedPath: String, rawName: String): String? {
        if (normalizedPath.isEmpty()) return null
        var slash = normalizedPath.indexOf('/')
        while (slash > 0) {
            val ancestor = normalizedPath.substring(0, slash)
            declared[ancestor]?.let { link ->
                return "entry '$rawName' is extracted through symlink '$ancestor' -> " +
                    "'${link.target}' declared earlier in the archive"
            }
            slash = normalizedPath.indexOf('/', slash + 1)
        }
        return null
    }

    /**
     * Records the entry after its own safety check passed. A non-symlink at a
     * path that previously held a link removes the declaration, because
     * extraction replaces that link with the new member.
     */
    fun observe(
        normalizedPath: String,
        isSymlink: Boolean,
        linkTarget: String?,
    ) {
        if (normalizedPath.isEmpty()) return
        if (isSymlink && linkTarget != null) {
            declared[normalizedPath] = Declared(linkTarget)
        } else {
            declared.remove(normalizedPath)
        }
    }

    /**
     * The declared links in extraction coordinates after
     * `--strip-components=[stripComponents]`. Members shorter than the strip
     * count are not extracted at all, so they are omitted.
     */
    fun linksAfterStrip(stripComponents: Int): List<Entry> {
        if (declared.isEmpty()) return emptyList()
        if (stripComponents <= 0) {
            return declared.map { (path, link) ->
                Entry(path = path, target = link.target, rawName = path)
            }
        }
        return declared.mapNotNull { (path, link) ->
            val stripped = stripLeading(path, stripComponents) ?: return@mapNotNull null
            Entry(path = stripped, target = link.target, rawName = path)
        }
    }

    private fun stripLeading(path: String, components: Int): String? {
        var remaining = path
        repeat(components) {
            val slash = remaining.indexOf('/')
            if (slash < 0) return null
            remaining = remaining.substring(slash + 1)
        }
        return remaining.takeIf { it.isNotEmpty() }
    }
}
