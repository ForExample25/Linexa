package com.fem.linexa.service.rootfs

/**
 * §1.147 — decides whether an archive wraps its rootfs in a single top-level
 * directory, and therefore whether extraction must strip one path component.
 *
 * Many publishers pack `tar -cf rootfs.tar rootfs/` instead of
 * `tar -cf rootfs.tar -C rootfs .`. Both are valid tarballs, but only the
 * second extracts to a usable rootfs: the first produces
 * `<staging>/rootfs/etc/...`, so the post-extraction layout check reports a
 * missing `etc` and the install fails for an archive that is perfectly fine.
 *
 * The rule is deliberately conservative:
 *
 *  - every entry must share **one** first path segment;
 *  - that segment must not itself be a standard rootfs directory (`etc`,
 *    `usr`, `bin`, …), because `tar -C /etc .` style archives are not wrapped;
 *  - no *file* may exist directly at the archive root (`README`, `.linexa.yaml`
 *    next to `rootfs/` means the root is meaningful and must not be stripped).
 *
 * A wrapped archive whose root also carries a metadata declaration is a real
 * shape (a publisher escaping a README). The installer resolves metadata
 * against the **unwrapped** root first; if a declaration lives outside the
 * wrapper it is treated as wrapper metadata and does not trigger a fallback
 * extraction layout. The conservative rule above keeps that unambiguous: a
 * root-level file means "do not strip", so such an archive fails the layout
 * check with a clear message rather than installing a nested tree.
 */
object ArchiveRootDiscovery {

    /** Entry shape the resolver needs; only names and the file/dir bit. */
    data class Shape(
        /** Distinct first path segments, capped by the caller at 2 (see [UNKNOWN]). */
        val firstSegments: Set<String>,
        /** True when a non-directory entry exists directly at the archive root. */
        val rootLevelFile: Boolean
    ) {
        companion object {
            /** Sentinel segment used when more than one distinct segment exists. */
            const val UNKNOWN = "\u0000multiple"
        }
    }

    /**
     * Standard rootfs directories. An archive whose single first segment is one
     * of these is the rootfs itself, not a wrapper around it.
     */
    private val ROOT_DIRECTORIES = setOf(
        "bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64",
        "media", "mnt", "opt", "proc", "root", "run", "sbin", "srv",
        "sys", "tmp", "usr", "var"
    )

    /** Number of components to strip: 0 for a normal rootfs, 1 for a wrapper. */
    fun stripComponents(shape: Shape): Int {
        if (shape.rootLevelFile) return 0
        if (shape.firstSegments.size != 1) return 0
        val segment = shape.firstSegments.first()
        if (segment.isEmpty() || segment == Shape.UNKNOWN) return 0
        if (segment.lowercase() in ROOT_DIRECTORIES) return 0
        return 1
    }

    /**
     * Folds one entry into a running [Shape]. [normalized] must come from
     * [ArchiveEntryPath.normalize]; an empty result (the archive root) is
     * ignored.
     */
    fun observe(shape: Shape, normalized: String, isDirectory: Boolean): Shape {
        if (normalized.isEmpty()) return shape
        val first = normalized.substringBefore('/')
        val segments = if (shape.firstSegments.size >= 2 || first in shape.firstSegments) {
            shape.firstSegments
        } else {
            shape.firstSegments + first
        }
        val rootFile = shape.rootLevelFile || (!isDirectory && !normalized.contains('/'))
        return Shape(segments, rootFile)
    }

    /**
     * Convenience over a complete list of `(normalized name, isDirectory)`
     * pairs, used by tests and by the ZIP wrapper scan (which can see every
     * member up front).
     */
    fun stripComponents(entries: List<Pair<String, Boolean>>): Int {
        var shape = Shape(emptySet(), rootLevelFile = false)
        entries.forEach { (name, isDirectory) ->
            shape = observe(shape, ArchiveEntryPath.normalize(name), isDirectory)
        }
        return stripComponents(shape)
    }
}
