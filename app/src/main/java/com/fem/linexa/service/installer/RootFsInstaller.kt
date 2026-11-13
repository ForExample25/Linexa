package com.fem.linexa.service.installer

import android.net.Uri
import com.fem.linexa.service.rootfs.RootFsArchiveFormat
import com.fem.linexa.service.rootfs.RootFsDescriptor
import com.fem.linexa.service.rootfs.profile.InstallStep
import com.fem.linexa.service.rootfs.profile.RootFsProfileResolver
import com.fem.linexa.service.rootfs.profile.RootFsProfileSource

/**
 * §1.147: the teed copy of the user's archive inside
 * `{workspace}/.import-cache`. Shared between the analyzer (which writes it)
 * and the installer (which extracts it) so the two can never disagree about
 * the file name.
 */
const val SOURCE_ARCHIVE_FILE_NAME = "source-archive"

/**
 * Installs a RootFS archive into a Workspace (§1.20 staged installer).
 *
 * Contract: never touches the workspace's *live* rootfs until the archive
 * has been fully streamed, validated, and (optionally) checksum-verified.
 * Extraction lands in a staging directory the whole time; only a successful
 * finish promotes it. A crash, kill, or thrown exception at any point
 * before that leaves the previous live rootfs (if any) exactly as it was.
 *
 * §1.84: the flow is split into two phases so a UI can *confirm before
 * committing*. [analyze] does the full validation/checksum/config scan of
 * the source archive (Pass 1 — the expensive, read-everything part) and
 * reports what it found (size, checksum, config) without touching the live
 * rootfs; [install] then consumes a successful analysis and runs extraction
 * + fixups + activation (Passes 2–4). The original one-shot [install]
 * convenience overload composes the two, so background callers
 * ([com.fem.linexa.service.importservice.ImportService],
 * [com.fem.linexa.service.distro.DownloadService]) are unchanged.
 */
interface RootFsInstaller {

    /**
     * Pass 1: streams [sourceUri] once — validating every entry (§1.14),
     * computing the streamed SHA-256, teeing the bytes to the workspace's
     * import cache (so a later [install] can extract without re-reading an
     * arbitrary content provider), and capturing a root-level config.yaml.
     *
     * Does NOT touch staging or the live rootfs. The teed archive is kept
     * for the [install] phase and discarded there (or by
     * [InstallRecoveryManager] if the process dies first).
     *
     * @param workspaceId the workspace this is being prepared for. Its
     *   `{workspace}/.import-cache` directory is cleared first.
     * @param expectedSha256 if non-null, analysis fails (and the teed
     *   archive is discarded) unless the archive's actual streamed SHA-256
     *   matches. If null, the checksum is still computed and returned.
     * @param onProgress periodic cumulative progress during the scan, with
     *   [InstallProgress.phase] = [InstallPhase.ANALYZING].
     */
    suspend fun analyze(
        workspaceId: String,
        sourceUri: Uri,
        expectedSha256: String? = null,
        /**
         * 2026-09-01: optional Linexa-bundled `linexa.yaml` bytes for the
         * KNOWN distro being installed (download flow only). Applied ONLY
         * when the archive itself carries no metadata file — an in-archive
         * declaration always wins (§1.119). Null (the default) means "no
         * bundled profile", which is the normal case for file imports.
         *
         * Deliberately NOT the last parameter: a trailing lambda on this
         * method must keep binding to [onProgress] (the pre-2026-09-01
         * signature contract every positional caller relies on). Kotlin's
         * trailing-lambda rule binds the LAST parameter, so onProgress
         * stays last — a bundledMetadata-last signature broke
         * ImportService's trailing-lambda call (caught by the 2026-09-01
         * second-pass real-toolchain compile).
         */
        bundledMetadata: ByteArray? = null,
        onProgress: suspend (InstallProgress) -> Unit = {}
    ): AnalyzeResult

    /**
     * Passes 2–4: consumes a successful [analyze] result and runs native
     * extraction, rootfs validation, fixups, config write, and staged
     * activation. [onProgress] reports the phase transitions
     * ([InstallPhase.EXTRACTING]/[FIXING]/[FINALIZING]).
     */
    suspend fun install(
        workspaceId: String,
        analysis: AnalyzeResult.Success,
        onProgress: suspend (InstallProgress) -> Unit = {}
    ): InstallResult

    /**
     * One-shot convenience: [analyze] then [install], used by the
     * background import/download services which have no confirmation step.
     *
     * [onProgress] is forwarded to the analysis scan only — the install
     * phase's byte-less phase-transition events are not surfaced here, so a
     * service computing a fraction from `bytesRead/totalBytes` never sees
     * its status jump backwards during extraction (exactly the behavior
     * these services had before the two-phase split). Callers that want the
     * phase events call the two-phase API directly.
     */
    suspend fun install(
        workspaceId: String,
        sourceUri: Uri,
        expectedSha256: String? = null,
        /** 2026-09-01: forwarded to [analyze] — see its kdoc for ordering. */
        bundledMetadata: ByteArray? = null,
        onProgress: suspend (InstallProgress) -> Unit = {}
    ): InstallResult = when (val result = analyze(workspaceId, sourceUri, expectedSha256, bundledMetadata, onProgress)) {
        is AnalyzeResult.Failure -> InstallResult.Failure(result.reason)
        is AnalyzeResult.Success -> install(workspaceId, result)
    }
}

/** Which stage of the install pipeline a progress event belongs to. */
enum class InstallPhase {
    /** Pass 1 — the validation/checksum/config scan of the source archive. */
    ANALYZING,

    /** Pass 2 — native `proot --link2symlink tar` extraction into staging. */
    EXTRACTING,

    /** Pass 3 — rootfs user/group fixups. */
    FIXING,

    /** Pass 4 — config write + staged activation. */
    FINALIZING
}

data class InstallProgress(
    val bytesRead: Long,
    /** Null if the content provider for [InstallProgress]'s source didn't report a size. */
    val totalBytes: Long?,
    val currentEntryName: String?,
    /** Which pipeline stage this event belongs to (defaults to the scan). */
    val phase: InstallPhase = InstallPhase.ANALYZING,
    /**
     * §1.88: real stage progress, when the stage can measure actual work.
     * Analysis sets it from bytesRead/totalBytes; extraction sets it from
     * GNU tar checkpoint markers. `null` = indeterminate.
     */
    val fraction: Float? = null
)

/** Outcome of [RootFsInstaller.analyze]. */
sealed interface AnalyzeResult {
    /**
     * §1.88: a successful analysis carries everything the confirmation
     * screen needs (distro/arch identity, sizes) plus the two numbers the
     * native extraction phase needs for real progress reporting
     * ([totalUncompressed] scaled against checkpoint markers) — so analysis
     * stays read-only and the confirmation/install stages never re-derive
     * or re-scan anything.
     */
    data class Success(
        val sha256: String,
        /**
         * The archive's total uncompressed size in bytes — the best
         * available estimate of the on-disk space the extracted rootfs will
         * need, computed during the scan pass (shown on the confirmation
         * screen as "required installation size").
         */
        val requiredStorageBytes: Long,
        /** The compressed archive size in bytes (from the content length). */
        val archiveBytes: Long,
        /** Non-directory entry count — the extraction progress denominator. */
        val totalEntries: Long,
        /**
         * Distribution/architecture identity. §1.118: the archive's own
         * `linexa.yaml` declaration when it carried a valid one (see
         * [descriptor]), otherwise the [RootFsProbe] result — or a
         * low-confidence fallback when neither produced usable signals.
         */
        val identity: RootFsProbe.Identity,
        /**
         * Best-effort minimum free bytes the install needs on top of the
         * workspace directory: extracted size + compressed archive + a
         * headroom slack for the staging/import-cache peak.
         */
        val requiredFreeBytes: Long,
        val capturedConfig: ByteArray?,
        /**
         * §1.118: what the archive *declared* about itself, plus the
         * declaration-vs-reality warnings. [RootFsDescriptor.UNDECLARED] for
         * every archive without a `linexa.yaml` metadata block — which is the
         * normal case and never an error.
         */
        val descriptor: RootFsDescriptor = RootFsDescriptor.UNDECLARED,
        /**
         * §1.118: the compression container sniffed from the archive's magic
         * bytes (never from a declaration or a file name).
         */
        val archiveFormat: RootFsArchiveFormat? = null,

        /**
         * §1.146: the id of the profile that governs this install
         * ([RootFsProfileResolver.GENERIC_PROFILE]'s `"generic"` when nothing
         * matched). Diagnostics only — the fields below are what the install
         * phase acts on.
         */
        val profileId: String = RootFsProfileResolver.GENERIC_PROFILE.id,

        /** §1.146: where that profile came from. Drives the "detected" story. */
        val profileSource: RootFsProfileSource = RootFsProfileSource.GENERIC,

        /**
         * §1.146: directories that must exist after extraction, from the
         * resolved profile.
         *
         * Carried on the analysis rather than re-resolved in [install] because
         * the archive is not read again after Pass 1: re-deriving the layout
         * rules would mean re-scanning, and any drift between the two
         * derivations would be a validation that passed during analysis and
         * failed after extraction, with the staging tree already written.
         *
         * Defaults to the generic profile's intersection (`etc`, `usr`) rather
         * than the pre-§1.146 Debian list (`bin`, `etc`, `lib`, `usr`, `var`),
         * which rejected legitimately usr-merged trees.
         */
        val requiredDirectories: List<String> =
            RootFsProfileResolver.GENERIC_PROFILE.layout.requiredDirectories.toList(),

        /**
         * §1.146: guest paths any one of which satisfies "this rootfs has a
         * shell", in preference order. Alpine ships no bash at all, so the
         * pre-§1.146 hardcoded sh/bash pair happened to work for it but the
         * *ordering* did not: profiles put the shell that exists first.
         */
        val shellCandidates: List<String> =
            RootFsProfileResolver.GENERIC_PROFILE.layout.shellCandidates,

        /**
         * §1.146: the ordered setup steps this install runs. A closed enum, not
         * commands — an embedded profile can choose *whether* and *in what
         * order*, never *what*.
         */
        val installSteps: List<InstallStep> = RootFsProfileResolver.GENERIC_PROFILE.install,

        /**
         * §1.147: the file in the workspace's `.import-cache` that [install]
         * hands to the native extractor. Normally `source-archive`, but a ZIP
         * container, a nested archive, or an OCI image layout is unwrapped
         * during analysis into a payload file beside it — the extractor must
         * never be pointed at a container that merely contains a rootfs.
         */
        val payloadFileName: String = SOURCE_ARCHIVE_FILE_NAME,

        /**
         * §1.147: leading path components the extractor must strip. `1` when
         * the archive wraps its rootfs in a single top-level directory
         * (`rootfs/etc/...`), `0` for the ordinary `tar -C rootfs .` shape.
         * Resolved from the whole archive's entry shape, never guessed from
         * the first member — see `ArchiveRootDiscovery`.
         */
        val stripComponents: Int = 0,

        /**
         * §1.147: the container of [payloadFileName]. Distinct from
         * [archiveFormat], which describes the file the user supplied: an OCI
         * image is a `.tar.xz` outer archive whose rootfs payload is a gzip
         * layer, and the UI/diagnostics should be able to say both.
         */
        val payloadFormat: RootFsArchiveFormat? = null,

        /**
         * §1.147: the profile's preferred login shell, as a guest-absolute
         * path. The pre-activation runtime smoke test starts this exact shell
         * through the real PRoot launcher before staging is promoted.
         */
        val loginShell: String? = null,

        /**
         * §1.147: the profile's package-manager key (see
         * `PackageManagerDetector`), or null when the profile does not declare
         * one. The smoke test runs `<binary> --version` so a rootfs whose
         * shell works but whose package manager is missing is caught at
         * install time.
         */
        val packageManager: String? = null,

        /**
         * §1.146: how resolution reached its conclusion, for the diagnostic
         * log. Empty for the ordinary "os-release said so" case.
         */
        val resolutionNotes: List<String> = emptyList()
    ) : AnalyzeResult

    data class Failure(val reason: String) : AnalyzeResult
}

sealed interface InstallResult {
    data class Success(val sha256: String) : InstallResult
    data class Failure(val reason: String) : InstallResult
}
