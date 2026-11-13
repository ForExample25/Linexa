package com.fem.linexa.service.rootfs

/**
 * §1.119 — the root-level files a rootfs archive may use to describe itself,
 * and the order in which they win.
 *
 * Two *formats* live here, deliberately kept apart:
 *
 *  - **Native Linexa** ([NATIVE_DOT], [NATIVE], [CONFIG]) — the §1.26
 *    configuration schema and the §1.118 metadata schema, both under a single
 *    top-level `linexa:` mapping. `.linexa.yaml` is the canonical name a
 *    Linexa-Rootfs build emits; the two undotted spellings predate it
 *    (§1.26/§1.118 accepted `config.yaml`/`linexa.yaml` and archives already
 *    exist carrying them), so they stay accepted forever rather than becoming
 *    a silent no-op after this change.
 *  - **Legacy Tiny Container** ([LEGACY_TINY]) — `.tiny.yaml`, a foreign
 *    schema Linexa did not author and cannot validate to its own standards.
 *    Read through [TinyMetadataAdapter], which extracts only presentation and
 *    never anything executable.
 *
 * Why an explicit priority instead of "first entry in the tar wins": tar order
 * is a property of how the archive was *packed*, not of what its author meant.
 * An image that ships both `.linexa.yaml` and `.tiny.yaml` (the expected shape
 * of a Tiny Container image re-published for Linexa) must resolve to the same
 * declaration no matter which one the packer happened to write first, and the
 * native file is the one whose schema Linexa can actually validate. So the
 * scan collects every candidate it sees and [bestOf] picks the winner after
 * the walk.
 *
 * @param fileName the exact root-level entry name, matched literally.
 * @param priority lower wins. Only meaningful relative to the other entries.
 * @param isNative true when the file uses Linexa's own schema, and therefore
 *   (a) is validated fail-hard and (b) is the file persisted verbatim as
 *   `{workspace}/config.yaml`. A legacy file is neither — see
 *   [TinyMetadataAdapter].
 */
enum class RootFsMetadataFile(
    val fileName: String,
    val priority: Int,
    val isNative: Boolean
) {
    /** Canonical native name, emitted by Linexa's RootFS export packing. */
    NATIVE_DOT(".linexa.yaml", 0, isNative = true),

    /** Native, pre-`.linexa.yaml` spelling (§1.118). Still fully supported. */
    NATIVE("linexa.yaml", 1, isNative = true),

    /** Native, original §1.26 spelling. Still fully supported. */
    CONFIG("config.yaml", 2, isNative = true),

    /** Legacy Tiny Container metadata — compatibility only (§1.119). */
    LEGACY_TINY(".tiny.yaml", 3, isNative = false);

    companion object {

        /**
         * Matches a tar entry name against the known candidates.
         *
         * Root level only: a nested `etc/linexa.yaml` or `opt/tiny/.tiny.yaml`
         * is an ordinary file belonging to the guest, not a declaration about
         * the image, and treating it as one would let any subdirectory rename
         * the workspace. Names are matched case-sensitively because the
         * archive's own entry names are what a Linux guest will see.
         *
         * §1.146: matched on the **normalized** name ([ArchiveEntryPath]), so a
         * `./`-packed archive can declare itself too. Before that, the raw name
         * was compared directly and the `contains('/')` root-level guard
         * rejected `./.linexa.yaml` outright — the same packing-convention bug
         * that made Alpine and Arch undetectable, applied to declarations.
         */
        fun match(entryName: String): RootFsMetadataFile? {
            val normalized = ArchiveEntryPath.normalize(entryName)
            if (normalized.isEmpty() || normalized.contains('/')) return null
            return entries.firstOrNull { it.fileName == normalized }
        }

        /** The highest-priority candidate present, or null when none are. */
        fun bestOf(present: Collection<RootFsMetadataFile>): RootFsMetadataFile? =
            present.minByOrNull { it.priority }

        /** Every accepted name, highest priority first — for logs and errors. */
        fun acceptedNames(): List<String> =
            entries.sortedBy { it.priority }.map { it.fileName }
    }
}
