package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe
import com.fem.linexa.service.rootfs.ArchiveEntryPath

/**
 * §1.146 — everything the scan pass learned about an archive, expressed in
 * normalized guest paths.
 *
 * This replaces the fixed five-field `RootFsProbe.Signals` as the *input* to
 * resolution. The difference is not cosmetic: the old shape had one field per
 * distribution Linexa knew about (`alpineRelease`, `archRelease`,
 * `debianVersion`, `fedoraRelease`), so adding a distribution meant adding a
 * field, threading it through `EntryScan`, and updating every construction
 * site. Here the scan collects whatever paths the loaded profiles asked about,
 * and a new distribution is a new YAML file with no Kotlin change at all.
 *
 * @param markerTexts normalized path → file contents, for the small text files
 *   profiles nominate as markers. A path present as a **symlink** appears in
 *   [presentPaths] but not here, because a tar symlink entry carries no data —
 *   this is why Fedora's profile lists `usr/lib/os-release` and not only
 *   `etc/os-release`.
 * @param presentPaths every nominated path seen in the archive, symlink or not.
 *   Presence alone identifies a distribution (`etc/arch-release` is empty).
 * @param archHintLabels architecture labels matched from entry names.
 * @param elfArchitectures normalized path → architecture read from that file's
 *   ELF header. The authoritative signal; see [ElfArchitecture].
 * @param embedded a valid profile the archive declared about itself, if any.
 */
data class ArchiveSignals(
    val markerTexts: Map<String, String> = emptyMap(),
    val presentPaths: Set<String> = emptySet(),
    val archHintLabels: Set<String> = emptySet(),
    val elfArchitectures: Map<String, RootFsProbe.RootFsArch> = emptyMap(),
    val embedded: RootFsProfile? = null
) {
    /** The `os-release` document, from whichever of its two standard paths won. */
    fun osReleaseText(): String? =
        markerTexts[OS_RELEASE_ETC] ?: markerTexts[OS_RELEASE_USR]

    companion object {
        const val OS_RELEASE_ETC = "etc/os-release"
        const val OS_RELEASE_USR = "usr/lib/os-release"
    }
}

/**
 * §1.146 — the outcome of resolving an archive against the profile set: which
 * profile governs the install, what the archive is, and how confident we are.
 *
 * @param profile the governing profile. Never null — an unrecognized archive
 *   gets [RootFsProfileResolver.GENERIC_PROFILE], so downstream stages always
 *   have a plan and never need a null branch.
 * @param identity distro/arch/version in the vocabulary the UI and DB already
 *   use.
 * @param plan the ordered, validated setup steps for this install.
 * @param notes human-readable explanation of how this was decided, for the
 *   diagnostic log. Empty for the ordinary "os-release said so" case.
 */
data class RootFsResolution(
    val profile: RootFsProfile,
    val identity: RootFsProbe.Identity,
    val plan: List<InstallStep>,
    val notes: List<String> = emptyList()
) {
    val source: RootFsProfileSource get() = profile.source

    /** True when identity was determined from the archive rather than guessed. */
    val detected: Boolean get() = identity.detected
}

/**
 * §1.146 — resolves an archive to the profile that governs its installation.
 *
 * ## Precedence, and why it is in this order
 *
 * 1. **A valid embedded profile** ([RootFsProfileSource.EMBEDDED]). The image's
 *    author knows what they built, and — unlike a guess — a declaration can be
 *    validated before it is trusted ([RootFsProfileParser]). An *invalid*
 *    embedded profile does not fail the import; it is discarded with a note and
 *    resolution continues at step 2, because a broken declaration should not
 *    make an otherwise-installable rootfs unusable.
 * 2. **A built-in profile matched by `os-release` `ID`**. os-release(5) is the
 *    only cross-distribution identity standard, it is machine-readable, and it
 *    carries a version.
 * 3. **A built-in profile matched by marker-file presence**. For images that
 *    omit os-release or ship it as a dangling symlink.
 * 4. **[GENERIC_PROFILE]**. Nothing matched, so the user's manual selection
 *    stands for *labelling* — but installation still runs the conservative
 *    generic plan rather than Debian's, which is the substantive half of the
 *    fix. Before §1.146 an undetected archive was pushed through the same
 *    hardcoded Debian-shaped path regardless.
 *
 * Note what an embedded profile can **not** override: it supplies detection and
 * setup *declarations*, and those are re-validated on load, but it cannot widen
 * the archive-safety rules ([com.fem.linexa.core.security.ArchiveValidator] runs
 * on raw entry names, before and independently of any profile) and it cannot
 * introduce a step outside [InstallStep]. Precedence applies to *description*,
 * never to host-side restrictions.
 */
object RootFsProfileResolver {

    /**
     * The safe fallback profile.
     *
     * Its requirements are deliberately the *intersection* of what real
     * distributions guarantee rather than Debian's list: `etc` and `usr` are
     * present in every rootfs examined (Debian, Ubuntu, Alpine, Arch, Fedora),
     * whereas `bin`, `lib` and `var` are not universally top-level directories
     * in a usr-merged tree. Its shell list is sh-first so a bash-less image
     * passes. Its plan is the three steps every distribution needs.
     */
    val GENERIC_PROFILE = RootFsProfile(
        id = "generic",
        name = "Linux",
        probeId = RootFsProbe.DistroId.OTHER,
        layout = LayoutRules(
            requiredDirectories = setOf("etc", "usr"),
            shellCandidates = listOf("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash")
        ),
        install = listOf(
            InstallStep.ENSURE_TMP,
            // F-5: POSIX shm is as universal a requirement as /tmp — the
            // guest /dev is the host's, and no Android host has /dev/shm.
            InstallStep.ENSURE_DEV_SHM,
            // R-5: /run for pid files, XDG_RUNTIME_DIR, utmp.
            InstallStep.ENSURE_RUN,
            // R-6: /proc bind-over snapshots so btop/top/vmstat don't
            // refuse to start on Android's hidepid=invisible /proc.
            InstallStep.ENSURE_PROC_SNAPSHOTS,
            // 2026-08-27 expansion: guest linexa-hostinfo helper (Phase 15).
            InstallStep.ENSURE_HOSTINFO_BIN,
            InstallStep.ANDROID_ACCOUNT_FIXUP,
            InstallStep.SEED_RESOLV_CONF
        ),
        source = RootFsProfileSource.GENERIC
    )

    /**
     * Every path the scan must watch for, across [profiles] — the union of
     * marker files, ELF probe paths, and the two os-release locations.
     *
     * Computed from the profiles rather than hardcoded so the scan never drifts
     * out of sync with what the profiles ask about.
     */
    fun watchedMarkerPaths(profiles: List<RootFsProfile>): Set<String> =
        profiles.flatMapTo(mutableSetOf()) { it.detection.markerFiles } +
            ArchiveSignals.OS_RELEASE_ETC + ArchiveSignals.OS_RELEASE_USR

    /** Every ELF path to probe, across [profiles]. */
    fun watchedElfPaths(profiles: List<RootFsProfile>): Set<String> =
        profiles.flatMapTo(mutableSetOf()) { it.detection.elfProbePaths }

    /**
     * Every architecture-hint fragment, across [profiles], as fragment → label.
     *
     * Merged across profiles because a hint is a fact about a *toolchain path*,
     * not about a distribution: `aarch64-linux-gnu` means arm64 wherever it
     * appears, so there is no need to know which distro an entry belongs to
     * before using its hint (and at scan time we do not yet know).
     */
    fun watchedArchHints(profiles: List<RootFsProfile>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        profiles.forEach { merged.putAll(it.detection.architectureHints) }
        return merged
    }

    /**
     * Resolves [signals] against [profiles].
     *
     * @param fallbackName distro name to show when nothing is detected — the
     *   user's manual selection. Only ever used for labelling.
     */
    fun resolve(
        signals: ArchiveSignals,
        profiles: List<RootFsProfile>,
        fallbackName: String? = null
    ): RootFsResolution {
        val notes = mutableListOf<String>()

        val osRelease = signals.osReleaseText()?.let(::parseOsRelease)

        // ── 1. embedded declaration ──────────────────────────────────────
        val embedded = signals.embedded
        if (embedded != null) {
            notes += "profile from archive declaration (id='${embedded.id}')"
            return build(embedded, signals, osRelease, notes, fallbackName)
        }

        // ── 2. os-release ID ─────────────────────────────────────────────
        val osId = osRelease?.id?.lowercase()
        if (osId != null) {
            val match = profiles.firstOrNull { osId in it.detection.osReleaseIds || osId in it.allIds }
            if (match != null) {
                notes += "profile from os-release ID '$osId'"
                return build(match, signals, osRelease, notes, fallbackName)
            }
            notes += "os-release ID '$osId' matches no built-in profile"

            // ── 2b. os-release ID_LIKE ──────────────────────────────────
            // A derivative (Linux Mint, EndeavourOS, Rocky, …) reports its own
            // ID and names its parent(s) in ID_LIKE. Falling through to the
            // parent's profile is the honest answer: installing the parent's
            // package-manager/setup plan is exactly what the derivative
            // documents. The exact ID above always wins, so a known distro can
            // never be re-labelled by a stray ID_LIKE.
            for (like in osRelease.idLike) {
                val parent = profiles.firstOrNull {
                    like in it.detection.osReleaseIds || like in it.allIds
                }
                if (parent != null) {
                    notes += "profile from os-release ID_LIKE '$like' (derivative '$osId')"
                    return build(parent, signals, osRelease, notes, fallbackName)
                }
            }
            if (osRelease.idLike.isNotEmpty()) {
                notes += "os-release ID_LIKE '${osRelease.idLike.joinToString(" ")}' matches no built-in profile"
            }
        }

        // ── 3. marker-file presence ──────────────────────────────────────
        // A profile matches when the archive carries a marker that is
        // *distinctive* to it — one no other profile also claims. Without the
        // distinctiveness filter, `etc/os-release` (which every profile lists)
        // would match the first profile in the list for every archive on earth.
        val distinctive = distinctiveMarkers(profiles)
        val markerMatch = profiles.firstOrNull { profile ->
            distinctive[profile.id]?.any { it in signals.presentPaths } == true
        }
        if (markerMatch != null) {
            val hit = distinctive[markerMatch.id]?.first { it in signals.presentPaths }
            notes += "profile from marker file '$hit'"
            return build(markerMatch, signals, osRelease, notes, fallbackName)
        }

        // ── 4. generic ───────────────────────────────────────────────────
        notes += "no profile matched; using generic safe defaults"
        return build(GENERIC_PROFILE, signals, osRelease, notes, fallbackName)
    }

    /**
     * Markers unique to one profile. `etc/os-release` is shared by all and so is
     * distinctive to none; `etc/alpine-release` is Alpine's alone.
     *
     * `etc/debian_version` is claimed by both Debian and Ubuntu, so it is
     * distinctive to neither — which is correct. An image carrying it with no
     * readable os-release cannot be told apart, and guessing "Ubuntu" from a
     * Debian marker would be worse than falling through to generic. Debian's
     * own identity in that case comes from `etc/debian_version` being its
     * `version_from` plus os-release when readable.
     */
    private fun distinctiveMarkers(profiles: List<RootFsProfile>): Map<String, List<String>> {
        val counts = HashMap<String, Int>()
        profiles.forEach { profile ->
            profile.detection.markerFiles.forEach { marker ->
                counts[marker] = (counts[marker] ?: 0) + 1
            }
        }
        return profiles.associate { profile ->
            profile.id to profile.detection.markerFiles.filter { counts[it] == 1 }
        }
    }

    private fun build(
        profile: RootFsProfile,
        signals: ArchiveSignals,
        osRelease: OsRelease?,
        notes: MutableList<String>,
        fallbackName: String?
    ): RootFsResolution {
        val arch = resolveArch(profile, signals, notes)
        val version = resolveVersion(profile, signals, osRelease)
        val isGeneric = profile.source == RootFsProfileSource.GENERIC
        val detected = !isGeneric && arch != RootFsProbe.RootFsArch.UNKNOWN

        if (!isGeneric && arch == RootFsProbe.RootFsArch.UNKNOWN) {
            notes += "distribution identified as '${profile.id}' but architecture could not be determined"
        }
        if (arch != RootFsProbe.RootFsArch.UNKNOWN &&
            profile.supportedArchitectures.isNotEmpty() &&
            arch.label !in profile.supportedArchitectures &&
            arch.abi !in profile.supportedArchitectures
        ) {
            // Advisory only: a self-built rootfs for an unlisted architecture is
            // legitimate, and the profile's list is what THIS BUILD can run
            // (packaged PRoot ABIs), not what the distribution publishes. The
            // hard rejection is ArchitectureCompatibility, before extraction.
            notes += "architecture ${arch.label} is not among the architectures this build runs for ${profile.id}"
        }

        val identity = RootFsProbe.Identity(
            distroId = profile.probeId,
            // A known profile owns its display name. For the generic fallback
            // os-release's own PRETTY_NAME is more truthful than "Linux" and
            // costs nothing — a derivative that matched no built-in profile
            // still shows the name its author chose.
            distroName = if (isGeneric) {
                osRelease?.prettyName ?: osRelease?.name ?: fallbackName ?: profile.name
            } else {
                profile.name
            },
            version = version,
            arch = arch,
            detected = detected
        )
        return RootFsResolution(profile, identity, profile.install.ifEmpty { GENERIC_PROFILE.install }, notes)
    }

    /**
     * Architecture, ELF header first.
     *
     * The ELF probe outranks name hints because it reads what the CPU will
     * actually be asked to execute, while a hint reads a directory name that
     * merely tends to correlate. When both are available they agree; when they
     * disagree the header is right. And for Arch Linux ARM only the header
     * exists at all.
     */
    private fun resolveArch(
        profile: RootFsProfile,
        signals: ArchiveSignals,
        notes: MutableList<String>
    ): RootFsProbe.RootFsArch {
        for (path in profile.detection.elfProbePaths) {
            val fromElf = signals.elfArchitectures[path]
            if (fromElf != null) {
                notes += "architecture ${fromElf.label} from ELF header of '$path'"
                return fromElf
            }
        }
        // Any ELF the scan managed to read, even one this profile did not
        // nominate: a correct answer from an unexpected binary beats UNKNOWN,
        // and every candidate path came from some profile's whitelist.
        signals.elfArchitectures.entries.firstOrNull()?.let { (path, arch) ->
            notes += "architecture ${arch.label} from ELF header of '$path' (not profile-nominated)"
            return arch
        }
        val hinted = signals.archHintLabels.firstNotNullOfOrNull { label ->
            RootFsProbe.RootFsArch.entries.firstOrNull {
                it != RootFsProbe.RootFsArch.UNKNOWN && (it.label == label || it.abi == label)
            }
        }
        if (hinted != null) {
            notes += "architecture ${hinted.label} from entry-name hint"
            return hinted
        }
        return RootFsProbe.RootFsArch.UNKNOWN
    }

    /**
     * Version: os-release `VERSION_ID` first, then the profile's declared
     * version marker, then `"unknown"`.
     *
     * `"unknown"` is a real answer for Arch, which is a rolling release with no
     * version at all — its `etc/arch-release` is empty by design, which is why
     * its profile declares no `version_from`.
     */
    private fun resolveVersion(
        profile: RootFsProfile,
        signals: ArchiveSignals,
        osRelease: OsRelease?
    ): String {
        osRelease?.versionId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // A few images (rolling releases) only publish `VERSION`; using it is
        // better than "unknown" as long as it is a short, single-line value.
        osRelease?.version?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_CHARS && !it.contains('\n') }
            ?.let { return it }
        profile.detection.versionFrom
            ?.let { signals.markerTexts[it] }
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_CHARS && !it.contains('\n') }
            ?.let { return it }
        return "unknown"
    }

    /**
     * Guards against a marker file that is technically small but not a version
     * (Fedora's `usr/lib/fedora-release` reads "Fedora release 42 (Adams)").
     * Such files are simply not nominated as `version_from`; this is the
     * backstop that keeps a stray one out of the UI.
     */
    private const val MAX_VERSION_CHARS = 64

    /** os-release(5): `KEY=value` / `KEY="value"`. */
    fun parseOsRelease(text: String): OsRelease {
        var id: String? = null
        var versionId: String? = null
        var version: String? = null
        var name: String? = null
        var prettyName: String? = null
        var idLike: List<String> = emptyList()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim()
            var value = trimmed.substring(eq + 1).trim()
            if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') ||
                    (value.first() == '\'' && value.last() == '\''))
            ) {
                value = value.substring(1, value.length - 1)
            }
            when (key) {
                "ID" -> id = value
                "VERSION_ID" -> versionId = value
                "VERSION" -> version = value
                "NAME" -> name = value
                "PRETTY_NAME" -> prettyName = value
                // ID_LIKE is a space-separated list, e.g. `debian ubuntu`.
                "ID_LIKE" -> idLike = value.split(Regex("\\s+"))
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            }
        }
        return OsRelease(id, versionId, name, prettyName, idLike, version)
    }

    data class OsRelease(
        val id: String?,
        val versionId: String?,
        val name: String?,
        /** os-release `PRETTY_NAME`, preferred over [name] for display. */
        val prettyName: String? = null,
        /** os-release `ID_LIKE`, in declaration order (parent distros). */
        val idLike: List<String> = emptyList(),
        /** os-release `VERSION`, used only when `VERSION_ID` is absent. */
        val version: String? = null
    )

    /**
     * Matches an entry name against the watched arch-hint fragments.
     *
     * Normalizes first so a `./`-packed archive matches — the same root cause as
     * the marker bug. Fragment matching stays `contains`-based because a
     * toolchain path's *position* varies (`lib/aarch64-linux-gnu/` vs
     * `usr/lib/aarch64-linux-gnu/`) even though the fragment does not.
     */
    fun matchArchHint(entryName: String, hints: Map<String, String>): String? {
        val normalized = ArchiveEntryPath.normalizeForMatch(entryName)
        return hints.entries.firstOrNull { normalized.contains(it.key) }?.value
    }
}
