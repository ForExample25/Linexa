package com.fem.linexa.service.rootfs

import com.fem.linexa.service.installer.RootFsProbe

/**
 * §1.118 — what an inspection of a rootfs archive concluded: the archive's
 * own declaration (if it carried a valid `linexa.yaml` metadata block), the
 * compression container its bytes actually use, and the list of ways the two
 * disagree.
 *
 * The descriptor is the *only* thing downstream stages see, and it is
 * deliberately shaped so that "the archive declared nothing" is an ordinary,
 * fully-supported state ([UNDECLARED]) rather than a missing-data error:
 * signal-based detection ([RootFsProbe]) predates this schema and stays the
 * fallback forever.
 *
 * Declaration-vs-reality mismatches are recorded as [warnings], never as
 * failures, because the two classes of disagreement have different weight and
 * neither one makes the rootfs unusable:
 *
 *  - a wrong `rootfs.compressed_format` is cosmetic — extraction is driven by
 *    the sniffed [archiveFormat] and never by the declaration, so the only
 *    harm is a misleading label;
 *  - a declared architecture the device cannot execute is a real problem, but
 *    it is the *declaration* saying so, and refusing the install on a
 *    self-reported field would mean a mislabelled-but-working rootfs could
 *    never be installed. The user is told instead.
 */
data class RootFsDescriptor(
    /** The validated declaration, or null when the archive declared none. */
    val metadata: RootFsMetadata?,
    /** Compression container sniffed from the archive's magic bytes. */
    val archiveFormat: RootFsArchiveFormat?,
    /** Human-readable declaration-vs-reality disagreements (never fatal). */
    val warnings: List<String> = emptyList(),
    /**
     * §1.119 — which root-level file this descriptor was read from, or null
     * for [UNDECLARED]. Recorded so logs and the import UI can say *which*
     * format an image used (native `.linexa.yaml` vs legacy `.tiny.yaml`)
     * rather than only whether something was declared.
     */
    val source: RootFsMetadataFile? = null,
    /**
     * §1.119 — presentation salvaged from a **legacy** `.tiny.yaml`
     * ([TinyMetadataAdapter]). Mutually exclusive with [metadata]: a legacy
     * file cannot produce a [RootFsMetadata] at all, because it states neither
     * `distro.id`/`distro.name` nor `architecture` and those are required.
     *
     * Kept as a separate field rather than folded into a synthesized
     * [RootFsMetadata] on purpose. A fabricated metadata object would make
     * `isDeclared` true and hand every consumer a declared *identity* that no
     * one actually declared — `declaredIdentity()` would then outrank the probe
     * with invented values, which is the exact failure §1.118 was built to
     * prevent. Consumers that only want a display string read
     * [presentation]; consumers that need facts read [metadata] and get null.
     *
     * The legacy QUICK-COMMANDS half of §1.122 is gone: the Quick Commands
     * re-architecture moved built-ins to the project catalog, so a legacy file
     * contributes presentation text and nothing else.
     */
    val legacyPresentation: DeclaredPresentation? = null
) {
    /** True only for a valid *native* declaration (§1.118 semantics, unchanged). */
    val isDeclared: Boolean get() = metadata != null

    /** §1.119: true when the image described itself with a legacy `.tiny.yaml`. */
    val isLegacy: Boolean get() = source != null && !source.isNative

    /**
     * §1.119 — how to present this image, from whichever format supplied it.
     *
     * This is the one place the two formats genuinely unify: a display name and
     * a description are format-independent text, so the confirmation screen
     * needs no branch on which file it came from. Everything else about a
     * legacy image stays undeclared.
     */
    val presentation: DeclaredPresentation? get() = metadata?.presentation ?: legacyPresentation

    /**
     * §1.120 — the image's declared shortcuts, or null when it declared none
     * at all. Native declarations only: the legacy `.tiny.yaml` path no longer
     * contributes commands (see [legacyPresentation]).
     *
     * Null vs empty is preserved from the native side (§1.120: `shortcuts: []`
     * is the author deliberately offering none).
     */
    val declaredShortcuts: List<DeclaredShortcut>?
        get() = metadata?.shortcuts

    /**
     * The declared identity expressed in [RootFsProbe]'s vocabulary, so a
     * declaring archive and a probed one are interchangeable to every consumer
     * downstream. `detected = true` because a declaration is a stronger signal
     * than any probe — the author stated it.
     *
     * Returns null when nothing was declared, which is the caller's cue to use
     * the probe result it already has.
     */
    fun declaredIdentity(): RootFsProbe.Identity? {
        val md = metadata ?: return null
        return RootFsProbe.Identity(
            distroId = distroIdOf(md.distro.id),
            distroName = md.distro.name,
            version = md.distro.version?.takeIf { it.isNotBlank() } ?: "unknown",
            arch = archOf(md.architecture),
            detected = true
        )
    }

    companion object {
        /** An archive with no recognized metadata file at all (the common case). */
        val UNDECLARED = RootFsDescriptor(metadata = null, archiveFormat = null)

        /**
         * §1.119 — a descriptor for an archive whose only metadata was a legacy
         * `.tiny.yaml`.
         *
         * [metadata] stays null by construction, so every fact-consuming stage
         * behaves exactly as it did before §1.119 (probe-based detection,
         * shell/desktop/package-manager probes). Only [presentation] gains a
         * value. No warnings: a legacy file disagreeing with reality is not
         * something this adapter can even detect, since it declares no facts.
         */
        fun legacy(
            presentation: DeclaredPresentation,
            archiveFormat: RootFsArchiveFormat?
        ): RootFsDescriptor = RootFsDescriptor(
            metadata = null,
            archiveFormat = archiveFormat,
            source = RootFsMetadataFile.LEGACY_TINY,
            legacyPresentation = presentation
        )

        /**
         * Builds a descriptor and computes its [warnings].
         *
         * @param supportedAbis the device's supported ABIs (`Build.SUPPORTED_ABIS`),
         *   passed in so this stays pure and JVM-testable. Empty skips the
         *   architecture check rather than warning about everything.
         * @param source §1.119: the native file the declaration came from, for
         *   diagnostics. Null keeps the pre-§1.119 call shape working.
         */
        fun of(
            metadata: RootFsMetadata?,
            archiveFormat: RootFsArchiveFormat?,
            supportedAbis: List<String> = emptyList(),
            source: RootFsMetadataFile? = null
        ): RootFsDescriptor {
            if (metadata == null) return RootFsDescriptor(null, archiveFormat)
            val warnings = mutableListOf<String>()

            val declaredFormat = metadata.rootfs?.compressedFormat
            if (declaredFormat != null && archiveFormat != null &&
                !RootFsArchiveFormat.matchesDeclaration(archiveFormat, declaredFormat)
            ) {
                warnings += "Archive declares '$declaredFormat' but its bytes are ${archiveFormat.label} " +
                    "(the real format is used; the declaration is ignored)"
            }

            if (supportedAbis.isNotEmpty() && metadata.architecture.abi !in supportedAbis) {
                warnings += "Archive declares ${metadata.architecture.label} " +
                    "(${metadata.architecture.abi}), which this device does not support " +
                    "(${supportedAbis.joinToString(", ")}) — its programs may fail to run"
            }

            return RootFsDescriptor(metadata, archiveFormat, warnings, source)
        }

        /**
         * Maps a declared os-release-style distro id onto [RootFsProbe.DistroId].
         * An id with no enum of its own is [RootFsProbe.DistroId.OTHER] — the
         * enum only exists for branding/iconography, and the human-readable
         * name comes from `distro.name` regardless, so an unrecognized id
         * degrades gracefully instead of failing validation.
         */
        fun distroIdOf(id: String): RootFsProbe.DistroId = when (id.lowercase()) {
            "debian" -> RootFsProbe.DistroId.DEBIAN
            "ubuntu" -> RootFsProbe.DistroId.UBUNTU
            "alpine" -> RootFsProbe.DistroId.ALPINE
            "arch", "archarm", "archlinux", "archlinuxarm" -> RootFsProbe.DistroId.ARCH
            "fedora" -> RootFsProbe.DistroId.FEDORA
            else -> RootFsProbe.DistroId.OTHER
        }

        private fun archOf(architecture: DeclaredArchitecture): RootFsProbe.RootFsArch =
            when (architecture) {
                DeclaredArchitecture.ARM64 -> RootFsProbe.RootFsArch.ARM64_V8A
                DeclaredArchitecture.ARM32 -> RootFsProbe.RootFsArch.ARMEABI_V7A
                DeclaredArchitecture.X86_64 -> RootFsProbe.RootFsArch.X86_64
                DeclaredArchitecture.X86 -> RootFsProbe.RootFsArch.X86
            }
    }
}
