package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe
import com.fem.linexa.service.rootfs.DeclaredArchitecture
import com.fem.linexa.service.rootfs.RootFsDescriptor
import com.fem.linexa.service.rootfs.RootFsMetadata

/**
 * §1.146 — turns an archive's **existing** `.linexa.yaml` declaration into a
 * [RootFsProfile].
 *
 * ## Why this reuses `.linexa.yaml` instead of adding a profile file
 *
 * §1.118/§1.119 already established one root-level declaration convention
 * (`.linexa.yaml` → `linexa.yaml` → `config.yaml` → `.tiny.yaml`, priority
 * ordered by [com.fem.linexa.service.rootfs.RootFsMetadataFile]), already
 * parse it, already validate it fail-hard, and already persist the native
 * winner as `{workspace}/config.yaml`. Introducing a second embedded file — a
 * `.linexa-profile.yaml` — would mean two schemas that can disagree about the
 * same archive, two priority orders, and a new question at every call site
 * ("which file wins if both exist?").
 *
 * So the embedded profile is **derived** from the declaration that already
 * exists. Every field a profile needs is already declared there:
 *
 * | profile field | `.linexa.yaml` source |
 * |---|---|
 * | `id`, `name`, version | `linexa.distro.id` / `.name` / `.version` |
 * | architecture | `linexa.architecture` |
 * | login shell | `linexa.environment.shell` |
 * | package manager | `linexa.package_manager.type` |
 *
 * An author who wants full control over detection rules, layout requirements or
 * install steps is describing something no existing distribution needs, and
 * would be requesting behaviour outside [InstallStep] — which is deliberately
 * not expressible (see [InstallStep]'s kdoc on why that closed set is a
 * security property). Such an archive resolves through its declared identity
 * onto the matching built-in profile's rules, which is the correct outcome.
 *
 * ## What the declaration does *not* get to decide
 *
 * The derived profile inherits `detection`, `layout` and `install` from the
 * **built-in** profile of the same distribution when one exists, and from
 * [RootFsProfileResolver.GENERIC_PROFILE] otherwise. That is the deliberate
 * split: an archive is trusted to say *what it is*, and Linexa decides *what
 * installing that requires*. A hostile archive therefore cannot use its
 * declaration to relax a layout requirement or to reorder setup steps.
 */
object EmbeddedProfileAdapter {

    /**
     * Derives a profile from [descriptor]'s native declaration, or returns null
     * when the archive declared no native metadata.
     *
     * A legacy `.tiny.yaml` yields null by construction: it declares neither
     * `distro.id` nor `architecture` (§1.119), so there is nothing to derive
     * from and detection must fall through to the built-in profiles exactly as
     * it did before. That is not a limitation of this adapter — inventing an
     * identity for a Tiny Container image is the precise failure mode §1.118
     * was built to prevent.
     *
     * @param builtIns used to inherit rules for the declared distribution.
     */
    fun from(descriptor: RootFsDescriptor, builtIns: List<RootFsProfile>): RootFsProfile? {
        val metadata = descriptor.metadata ?: return null
        return from(metadata, builtIns)
    }

    /** [from] on an already-extracted [RootFsMetadata]. */
    fun from(metadata: RootFsMetadata, builtIns: List<RootFsProfile>): RootFsProfile {
        val declaredId = metadata.distro.id.lowercase()
        val base = builtIns.firstOrNull { declaredId in it.allIds }
            ?: RootFsProfileResolver.GENERIC_PROFILE

        return RootFsProfile(
            id = declaredId,
            name = metadata.distro.name,
            aliases = base.aliases,
            probeId = RootFsDescriptor.distroIdOf(declaredId),
            // Detection rules come from the built-in profile, not the archive:
            // the archive has already *told* us what it is, so its own detection
            // rules would never be consulted, and accepting them would only
            // widen the set of paths the scan reads on an untrusted image.
            detection = base.detection,
            supportedArchitectures = base.supportedArchitectures,
            layout = base.layout,
            install = base.install.ifEmpty { RootFsProfileResolver.GENERIC_PROFILE.install },
            environment = EnvironmentDefaults(
                // The two fields an archive genuinely knows better than we do.
                loginShell = metadata.environment?.shell ?: base.environment.loginShell,
                shellArgs = base.environment.shellArgs,
                packageManager = metadata.packageManager?.type ?: base.environment.packageManager
            ),
            source = RootFsProfileSource.EMBEDDED
        )
    }

    /**
     * The architecture the declaration states, in probe vocabulary.
     *
     * Kept separate from [from] because a declared architecture is a *fact about
     * this archive*, not part of the reusable profile: two Alpine images sharing
     * one profile can target different CPUs.
     */
    fun declaredArch(metadata: RootFsMetadata): RootFsProbe.RootFsArch =
        when (metadata.architecture) {
            DeclaredArchitecture.ARM64 -> RootFsProbe.RootFsArch.ARM64_V8A
            DeclaredArchitecture.ARM32 -> RootFsProbe.RootFsArch.ARMEABI_V7A
            DeclaredArchitecture.X86_64 -> RootFsProbe.RootFsArch.X86_64
            DeclaredArchitecture.X86 -> RootFsProbe.RootFsArch.X86
        }
}
