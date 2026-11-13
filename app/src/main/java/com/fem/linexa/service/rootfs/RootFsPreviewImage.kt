package com.fem.linexa.service.rootfs

import java.io.File
import java.io.IOException

/**
 * §1.121 — the two-stage safety rule for a declared preview image, shared by
 * both metadata formats (`linexa.metadata.preview` and `.tiny.yaml`'s
 * `preview:`).
 *
 * A preview is the only field in either schema that names a **file** rather
 * than describing one, and the Home card renders whatever it names. That makes
 * it the one declaration that could turn into a file-disclosure channel, so it
 * gets a rule of its own rather than reusing the identifier/token checks the
 * other fields share:
 *
 *  1. [normalize] — at *import*, the declared string is reduced to a
 *     rootfs-relative path with no `.`/`..` segments, no scheme, no backslash
 *     and no NUL, or rejected. Only the normalized form is ever persisted, so
 *     nothing downstream has to re-derive what an author meant by
 *     `/opt/../opt/./preview.png`.
 *  2. [resolveInside] — at *read* time, the normalized path is resolved
 *     against the workspace's real rootfs and the **canonical** result is
 *     re-checked for containment.
 *
 * Both stages are necessary, and neither is redundant:
 *
 *  - Stage 1 alone would miss a symlink. `opt/tiny/preview.png` is a perfectly
 *    valid relative path, and the archive may ship it as a symlink to
 *    `/data/data/com.fem.linexa/databases/linexa.db` — or the guest may replace
 *    it with one after install, long after any import-time check ran. Only
 *    canonicalizing against the live filesystem catches that, which is why the
 *    check is at read time and not cached alongside the declaration.
 *  - Stage 2 alone would accept an absolute declared path on any rootfs whose
 *    layout happened to contain it, and would leave every consumer re-parsing
 *    the raw author string. Normalizing once at the boundary means the stored
 *    value is already the narrow shape.
 *
 * **A failed check is "no preview", never an error.** The image is decoration:
 * the Home card's letter avatar/branded icon is the pre-§1.121 rendering and
 * remains a complete answer. Failing an install because a cosmetic field named
 * a bad path would reject an otherwise working rootfs over nothing, and
 * throwing at read time would break the Home list — so every rejection here
 * collapses to null (and, in the native schema, to a validation failure only
 * for the *shape* violations an author can see and fix).
 */
object RootFsPreviewImage {

    /**
     * Upper bound on the declared path's length. Generous next to any real
     * `opt/…/preview.png` and short enough that a hostile file cannot ship a
     * multi-kilobyte path.
     */
    const val MAX_PATH_CHARS = 256

    /** Image extensions a preview may use, matched case-insensitively. */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "webp")

    /**
     * Reduces a declared preview path to the canonical rootfs-relative form,
     * or returns null when it cannot be trusted.
     *
     * A leading `/` is **stripped, not rejected**: `.tiny.yaml` writes
     * `/opt/tiny/preview.png` and means "inside the container", which is the
     * guest's own view of its filesystem root and exactly the path this
     * function returns relative to the rootfs directory. What *is* rejected is
     * anything that could escape or that names something other than a plain
     * in-rootfs image:
     *
     *  - any `..` segment (the traversal case — `../../../etc/passwd`),
     *  - a Windows-style backslash or a drive/scheme prefix (`file:`,
     *    `C:\`), which would otherwise survive as a literal filename on Linux
     *    and make the rejection depend on the host's path parser,
     *  - control characters and NUL,
     *  - an unsupported or absent extension, so a `preview: /etc/shadow`
     *    cannot be handed to an image decoder at all.
     */
    fun normalize(declared: String): String? {
        val raw = declared.trim()
        if (raw.isEmpty() || raw.length > MAX_PATH_CHARS) return null
        if (raw.any { it == '\u0000' || it.isISOControl() }) return null
        if (raw.contains('\\')) return null
        // A scheme or drive prefix (`file:`, `http:`, `C:`): meaningless inside
        // a rootfs, and accepting it would leave a colon-bearing literal name.
        if (raw.contains(':')) return null

        val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null

        val extension = segments.last().substringAfterLast('.', "").lowercase()
        if (extension !in SUPPORTED_EXTENSIONS) return null

        return segments.joinToString("/")
    }

    /**
     * Resolves an already-[normalize]d [relativePath] inside [rootfs] and
     * returns the file only when it is a real, non-empty, readable file whose
     * canonical path is still inside the canonical rootfs.
     *
     * Returns null for every other case — missing file, directory, unreadable,
     * symlink pointing out of the rootfs, or an I/O error while canonicalizing.
     * Callers treat null exactly as "this workspace has no preview image".
     */
    fun resolveInside(rootfs: File, relativePath: String?): File? {
        val path = relativePath?.let { normalize(it) } ?: return null
        return try {
            val root = rootfs.canonicalFile
            val candidate = File(root, path).canonicalFile
            if (!isInside(root, candidate)) return null
            if (!candidate.isFile || !candidate.canRead() || candidate.length() <= 0L) return null
            candidate
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Containment on canonical paths, compared segment-wise via the parent
     * chain rather than with `startsWith`. A prefix-string test would accept
     * `/…/workspaces/w1/rootfs-evil/x.png` as inside `/…/workspaces/w1/rootfs`,
     * which is the classic sibling-directory bypass.
     */
    private fun isInside(root: File, candidate: File): Boolean {
        var parent: File? = candidate.parentFile
        while (parent != null) {
            if (parent == root) return true
            parent = parent.parentFile
        }
        return false
    }
}
