package com.fem.linexa.service.rootfs

import java.util.Locale

/**
 * §1.146 — normalization of a tar entry name into the guest-absolute path it
 * will occupy after extraction, so every stage that *matches* on a path
 * matches on the same string.
 *
 * ## Why this exists
 *
 * A tar archive's entry names are a property of how it was **packed**, not of
 * the filesystem it describes. GNU tar invoked as `tar -czf out.tar.gz -C
 * rootfs .` writes every member with a `./` prefix (`./etc/os-release`);
 * invoked as `tar -czf out.tar.gz *` from inside the directory it writes them
 * bare (`etc/os-release`). Both archives extract to a byte-identical rootfs.
 *
 * Before §1.146 the scan pass compared raw entry names against a literal set
 * (`"etc/os-release"`, `"etc/alpine-release"`, …). That silently split the
 * distro world in two along a line that has nothing to do with the distro:
 *
 *  - Debian and Ubuntu rootfs tarballs are packed **without** the prefix, so
 *    every signal matched and detection worked;
 *  - Alpine minirootfs and Arch Linux ARM tarballs are packed **with** it
 *    (verified: all 515 entries of `alpine-minirootfs-3.24.1-aarch64.tar.gz`
 *    begin `./`), so *no* signal matched, [RootFsProbe.detect] returned
 *    `DistroId.OTHER` with `detected = false`, and the import screen fell back
 *    to "Distribution and architecture could not be detected automatically".
 *
 * Debian/Ubuntu detection was therefore never *correct*, only *lucky*. The fix
 * is to normalize once, here, rather than to add a second literal per signal
 * — which would leave the next packer convention (`/etc/os-release`, absolute)
 * broken in exactly the same way.
 *
 * ## What this is not
 *
 * This is **not** a security boundary and must never be used as one. It
 * deliberately *accepts* traversal segments and hands them back unchanged, so
 * a caller cannot mistake a normalized path for a validated one.
 * [com.fem.linexa.core.security.ArchiveValidator.checkEntry] remains the only
 * authority on whether an entry is safe to extract, it runs on the **raw**
 * entry name (before this normalizer, see the scan pass), and it rejects
 * absolute paths and `..` outright. Normalizing first would weaken it by
 * making `./../etc/passwd` look tamer than it is.
 */
object ArchiveEntryPath {

    /**
     * Normalizes [entryName] to a guest-absolute path **without** its leading
     * slash — the form `etc/os-release`, matching what the pre-§1.146 literals
     * already expected for Debian/Ubuntu.
     *
     * Handles, in order:
     *  - a leading `./` (GNU tar's `-C dir .` convention), repeatedly, so
     *    `././etc` normalizes too;
     *  - a leading `/` (absolute members; GNU tar strips these on extraction
     *    and so do we, for the same reason — the member lands *inside* the
     *    extraction root either way);
     *  - interior `./` segments and duplicate slashes;
     *  - a trailing `/` (directory members), so a directory and a file entry
     *    for the same path compare equal.
     *
     * `..` segments are preserved verbatim: resolving them is the validator's
     * job (see the class kdoc), and silently collapsing them here would hide
     * an escape attempt from it.
     *
     * Returns an empty string for the archive root itself (`.`, `./`, `/`),
     * which matches nothing and needs no special case at the call sites.
     */
    fun normalize(entryName: String): String {
        if (entryName.isEmpty()) return ""
        val segments = entryName.split('/')
        val kept = ArrayList<String>(segments.size)
        for (segment in segments) {
            // Drops "" (leading "/", duplicate "//", trailing "/") and "."
            // (the "./" prefix and any interior "./"). ".." is intentionally
            // kept — see the class kdoc.
            if (segment.isEmpty() || segment == ".") continue
            kept += segment
        }
        return kept.joinToString("/")
    }

    /**
     * [normalize] plus a lower-casing of the result, for the case-insensitive
     * signal matching the scan pass does.
     *
     * Split from [normalize] rather than folded into it because the two uses
     * are genuinely different: matching a *known* lower-case literal wants
     * case folding, while recording a path for logs or for
     * [RootFsMetadataFile] matching must preserve the case a Linux guest will
     * actually see. [Locale.ROOT] because a locale-sensitive fold would make
     * detection depend on the user's phone language (the Turkish dotless-i
     * problem: `I`.lowercase() is `ı` under `tr-TR`).
     */
    fun normalizeForMatch(entryName: String): String =
        normalize(entryName).lowercase(Locale.ROOT)

    /**
     * True when [entryName] names a file directly at the archive root — the
     * precondition [RootFsMetadataFile.match] enforces, expressed on the
     * normalized form so a `./`-packed image can declare itself too.
     */
    fun isRootLevel(entryName: String): Boolean {
        val normalized = normalize(entryName)
        return normalized.isNotEmpty() && !normalized.contains('/')
    }
}
