package com.fem.linexa.service.installer

import android.app.Application
import android.net.Uri
import android.os.Build
import com.fem.linexa.core.format.FileSizeFormatter
import com.fem.linexa.core.filesystem.deleteRecursivelyAllowingReadOnly
import com.fem.linexa.core.logging.Logger
import com.fem.linexa.core.security.ArchiveValidator
import com.fem.linexa.core.security.toArchiveEntryType
import com.fem.linexa.service.rootfs.ArchiveEntryPath
import com.fem.linexa.service.rootfs.ArchiveRootDiscovery
import com.fem.linexa.service.rootfs.ArchitectureCompatibility
import com.fem.linexa.service.rootfs.CompressedArchiveStream
import com.fem.linexa.service.rootfs.RootFsArchiveFormat
import com.fem.linexa.service.rootfs.RootFsDescriptor
import com.fem.linexa.service.rootfs.RootFsInspector
import com.fem.linexa.service.rootfs.RootFsMetadataFile
import com.fem.linexa.service.rootfs.profile.ArchiveSignalCollector
import com.fem.linexa.service.rootfs.profile.ElfArchitecture
import com.fem.linexa.service.rootfs.profile.EmbeddedProfileAdapter
import com.fem.linexa.service.rootfs.profile.InstallStep
import com.fem.linexa.service.rootfs.profile.OciImageLayout
import com.fem.linexa.service.rootfs.profile.RootFsProfile
import com.fem.linexa.service.rootfs.profile.RootFsProfileCatalog
import com.fem.linexa.service.rootfs.profile.RootFsProfileResolver
import com.fem.linexa.service.rootfs.profile.RootFsResolution
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Staged rootfs installer — §1.20 contract, §1.26 native engine.
 *
 * The JVM-only single-pass extraction of the original §1.20 design is
 * replaced by a two-pass design per §1.25 decision (b): JVM-side
 * validation/checksum pass, then native extraction with the bundled GNU
 * tar under PRoot.
 *
 * Pass 1 (JVM, one read of the source): the archive is streamed once from
 * its `content://` URI while (a) the compressed bytes are teed to a temp
 * file under `{workspace}/.import-cache/` (native tar needs a seekable
 * file, and re-opening arbitrary content providers is unreliable),
 * (b) the streaming SHA-256 of the compressed bytes is computed,
 * (c) every entry is run through [ArchiveValidator.checkEntry] (§1.14:
 * path traversal, zip-slip, symlink escape) with the 8 GiB archive-bomb
 * cap, and (d) every recognized root-level metadata file is captured
 * (§1.26 decision c; §1.119: `.linexa.yaml` → `linexa.yaml` →
 * `config.yaml` → `.tiny.yaml`, highest priority wins — see
 * [RootFsMetadataFile]). Checksum is checked before
 * any extraction happens — a corrupt or wrong-archive download fails
 * without touching staging.
 *
 * Pass 2 (native): extraction runs through
 * [RootfsExtractor.extract] — `proot --link2symlink <bin>/tar -xf <temp>
 * -C <staging> --delay-directory-restore --preserve-permissions`, the
 * invocation tiny_container runs on real devices. PRoot's `--link2symlink`
 * turns hard links into symlinks, which is what lets an unprivileged app
 * extract a rootfs that would otherwise need root to materialize hard
 * links; GNU tar 1.35 natively strips leading `/` and refuses `..`
 * members (exit 2), and replaces symlink parents with real directories
 * instead of writing through them (both verified empirically against GNU
 * tar 1.35, the same codebase as the bundled termux tar 1.35-3), so the
 * JVM checks are defense in depth, not the only defense.
 *
 * Pass 2.5 (§1.168): [L2sLinkRewriter.rewrite] rewrites the artifacts that
 * `--link2symlink` just wrote — their targets are absolute *staging* paths, so
 * without this every emulated hardlink would dangle the instant staging is
 * renamed to `rootfs`. Runs inside staging, so a failure discards it like any
 * other Pass 2 failure.
 *
 * Pass 3: rootfs user/group fixups run through
 * [RootfsFixupService.fixUp] (proot-distro lineage — see that class for
 * the §1.56 instrumentation record).
 *
 * Pass 4: write the captured config.yaml to `{workspace}/config.yaml`.
 *
 * Only after every pass does
 * [WorkspaceFileManager.activateStagedRootfs] promote staging to the live
 * rootfs (atomic rename, §1.20). Failure at any point discards staging
 * and the import cache, leaving any previous live rootfs untouched.
 *
 * Kept JVM-side (not dropped): commons-compress + zstd-jni + xz. §1.25
 * decision (b) removed them from the *extraction* path only; they are
 * still required here for Pass 1's format sniffing, validation walk, and
 * config capture (see §1.26).
 */
@Singleton
class StagedRootFsInstaller @Inject constructor(
    private val application: Application,
    private val fileManager: WorkspaceFileManager,
    private val archiveValidator: ArchiveValidator,
    private val bootstrapManager: BootstrapManager,
    private val rootFsInspector: RootFsInspector,
    private val rootfsExtractor: RootfsExtractor,
    /**
     * §1.168: rewrites PRoot's `--link2symlink` artifacts to relative targets
     * so they survive the staging → live rename. Runs between extraction and
     * validation; a failure discards staging like any other Pass 2 failure.
     */
    private val l2sLinkRewriter: L2sLinkRewriter,
    /**
     * RFS-03: the post-extraction, pre-fixup NOFOLLOW boundary audit. No
     * archive may reach the account fixup or activation while a link can
     * redirect a host-side write outside staging.
     */
    private val archiveBoundaryAuditor: ArchiveBoundaryAuditor,
    private val rootfsFixupService: RootfsFixupService,
    private val guestRootfsInitializer: GuestRootfsInitializer,
    /**
     * §1.146/§1.147: unwraps an OCI image layout's rootfs layer during
     * payload resolution, before extraction. The resolved layer becomes the
     * analyzer's `payloadFileName`; see [resolvePayload].
     */
    private val ociLayerUnwrapper: OciLayerUnwrapper,
    /**
     * §1.146: the built-in distribution profiles. Injected rather than
     * constructed here so a JVM test can supply the very same YAML files from
     * disk (see [RootFsProfileCatalog]) and so a missing asset degrades to the
     * generic path instead of crashing the import.
     */
    private val profileCatalog: RootFsProfileCatalog,
    /**
     * §1.147: the pre-activation runtime smoke test. It boots the resolved
     * login shell through the real PRoot launcher while the tree is still in
     * staging, so a rootfs that extracts cleanly but cannot execute can never
     * replace a working one.
     */
    private val runtimeVerifier: RootfsRuntimeVerifier
) : RootFsInstaller {

    // ── Pass 1 split (§1.84): analyze() ─────────────────────────────────
    // The whole scan/validate/checksum/config phase runs here, WITHOUT
    // touching staging or the live rootfs, so a UI can show real progress,
    // learn the required storage, and let the user confirm before the
    // destructive pass. The teed archive survives in the import cache for
    // the paired install() call.

    override suspend fun analyze(
        workspaceId: String,
        sourceUri: Uri,
        expectedSha256: String?,
        bundledMetadata: ByteArray?,
        onProgress: suspend (InstallProgress) -> Unit
    ): AnalyzeResult = withContext(Dispatchers.IO) {
        val staging = fileManager.getStagingRootfsPath(workspaceId)

        // §1.147 tee-then-scan. The archive is materialized ONCE into the
        // import cache while its SHA-256 is computed; every later inspection
        // (ZIP member selection, nested-payload descent, OCI layer scan) reads
        // that seekable file. Before this split, a ZIP or a nested archive
        // could not be inspected at all: the only pass over the source was a
        // tar walk, which is precisely the wrong question for a container.
        //
        // The cache is deliberately NOT deleted at the end of analyze —
        // install() reuses it and deletes it (InstallRecoveryManager handles a
        // crash in between).
        val importCache = File(fileManager.getWorkspaceBasePath(workspaceId), IMPORT_CACHE_DIR)
        importCache.deleteRecursively()
        importCache.mkdirs()
        val tempArchive = File(importCache, SOURCE_ARCHIVE_FILE_NAME)

        // §16.3.2: `forceSetup` — the guard below is a corruption check (it
        // stats every managed binary), so this is a repair request. A memoised
        // `setup()` would return from the cache and leave the broken layout in
        // place, then fail the readiness check below for a reason the repair was
        // supposed to fix.
        if (!bootstrapManager.isReady) {
            bootstrapManager.forceSetup()
        }
        // §1.40: always log bootstrap state for device debugging
        Logger.i(TAG, bootstrapManager.diagnoseAll())
        if (!bootstrapManager.isReady) {
            importCache.deleteRecursively()
            return@withContext AnalyzeResult.Failure("Native bootstrap binaries are missing (busybox/tar/proot)")
        }

        // Disk space check before the tee: the scan itself only needs room for
        // the archive (plus headroom). The full requirement is checked after
        // the payload is known.
        val archiveBytes = queryContentLength(sourceUri) ?: 0L
        val availableBytes = staging.parentFile?.usableSpace ?: 0L
        if (archiveBytes > 0 && availableBytes < archiveBytes + SLACK_BYTES) {
            importCache.deleteRecursively()
            return@withContext AnalyzeResult.Failure(
                "Insufficient disk space to scan archive: need ~${formatBytes(archiveBytes + SLACK_BYTES)}, have ${formatBytes(availableBytes)}"
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val teeFailure = teeSource(sourceUri, tempArchive, digest, archiveBytes.takeIf { it > 0 }, onProgress)
        if (teeFailure != null) {
            importCache.deleteRecursively()
            return@withContext AnalyzeResult.Failure(teeFailure)
        }

        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
            importCache.deleteRecursively()
            return@withContext AnalyzeResult.Failure(
                "Checksum mismatch: expected $expectedSha256, got $actualSha256"
            )
        }

        // §1.147: resolve what the user's file actually contains. A plain
        // rootfs tar is its own payload; a ZIP, a nested archive, or an OCI
        // image layout is unwrapped into a separate file inside the import
        // cache, and THAT file is what the extractor will be handed.
        val outerFormat = CompressedArchiveStream.detectFile(tempArchive)
        if (outerFormat == null) {
            importCache.deleteRecursively()
            return@withContext AnalyzeResult.Failure(
                "Cannot detect archive format: the file is not a tar archive " +
                    "(plain, gzip, bzip2, xz, lzma, zstd) or a ZIP container"
            )
        }
        val payload = when (val resolved = resolvePayload(tempArchive, outerFormat, importCache, depth = 0)) {
            is PayloadOutcome.Failure -> {
                importCache.deleteRecursively()
                return@withContext AnalyzeResult.Failure(resolved.reason)
            }
            is PayloadOutcome.Success -> resolved.payload
        }
        Logger.i(
            TAG,
            "§1.147 payload resolved: file='${payload.file.name}' format=${payload.format.label} " +
                "stripComponents=${payload.scan.stripComponents} entries=${payload.scan.totalEntries} " +
                "uncompressed=${payload.scan.totalUncompressed}"
        )

        // §1.88: full disk-space requirement — the install peak holds the
        // compressed archive in the import cache WHILE staging grows to the
        // full extracted size, so required free = extracted + archive + slack.
        if (payload.scan.totalUncompressed > 0) {
            val requiredFree = payload.scan.totalUncompressed + archiveBytes + SLACK_BYTES
            if (availableBytes < requiredFree) {
                importCache.deleteRecursively()
                return@withContext AnalyzeResult.Failure(
                    "Insufficient disk space to install: need ~${formatBytes(requiredFree)}, have ${formatBytes(availableBytes)}"
                )
            }
        }

        // §1.146: one resolution, used for BOTH the identity shown to the user
        // and the plan the install phase follows. Deriving them separately is
        // how the pre-§1.146 pipeline could show "Alpine" while running
        // Debian's hardcoded install path.
        val resolution = resolveProfile(payload.scan, fallbackName = null)

        // §1.147: reject a known-incompatible CPU during analysis. Every
        // later stage is expensive (extract, fixup, smoke test) and none of
        // them can make an amd64 binary run on an arm64 phone; the user should
        // learn now, not after a multi-minute extraction.
        ArchitectureCompatibility.rejectionReason(resolution.identity.arch, deviceAbis())?.let { reason ->
            Logger.w(TAG, "§1.147 architecture rejected: $reason")
            importCache.deleteRecursively()
            return@withContext AnalyzeResult.Failure(reason)
        }

        // 2026-09-01: bundled-metadata fallback. When the archive carries NO
        // metadata of its own and the caller knows which distro this is (the
        // download flow), Linexa's shipped `linexa.yaml` for that distro is
        // inspected and used exactly as if it had been the archive's own
        // declaration: validated fail-hard, persisted as the workspace's
        // config.yaml, and folded into profile resolution via the descriptor.
        // An in-archive declaration always wins (§1.119 priority).
        var effectiveCaptured = payload.scan.capturedConfig
        var effectiveDescriptor = payload.scan.descriptor
        if (effectiveCaptured == null && bundledMetadata != null) {
            // Issue #10 precedence: Linexa's own metadata is the default
            // source of truth. It outranks BOTH "no declaration at all" and
            // "a legacy .tiny.yaml only" — a native declaration still wins
            // (capturedConfig is only set for native files). Say which case
            // this is, so the log explains the decision instead of just the
            // action.
            val legacySource = payload.scan.descriptor.source?.takeIf { !it.isNative }
            Logger.i(
                TAG,
                if (legacySource != null) {
                    "Archive carries only a legacy '${legacySource.fileName}' declaration — " +
                        "applying Linexa's bundled install profile (${bundledMetadata.size} bytes), " +
                        "which outranks the compatibility source (issue #10 precedence: native > " +
                        "bundled > .tiny.yaml)"
                } else {
                    "Archive carries no metadata — applying Linexa's bundled install profile (${bundledMetadata.size} bytes)"
                }
            )
            val bundledInspection = rootFsInspector.inspectFile(
                source = RootFsMetadataFile.NATIVE_DOT,
                bytes = bundledMetadata,
                // The declaration describes the file the user downloaded, not
                // the payload an OCI/ZIP wrapper was unwrapped from.
                archiveFormat = outerFormat,
                supportedAbis = deviceAbis()
            )
            when (bundledInspection) {
                is RootFsInspector.Result.Success -> {
                    effectiveCaptured = bundledMetadata
                    effectiveDescriptor = bundledInspection.descriptor
                }
                is RootFsInspector.Result.Invalid -> throw InstallAbortException(
                    "Linexa's bundled install profile for this distro is invalid: ${bundledInspection.reason}"
                )
            }
        }

        AnalyzeResult.Success(
            sha256 = actualSha256,
            requiredStorageBytes = payload.scan.totalUncompressed,
            archiveBytes = archiveBytes,
            totalEntries = payload.scan.totalEntries,
            // §1.118: a valid declaration outranks detection — the archive's
            // author knows what they built, and the declaration was validated
            // hard (unknown architecture / half-declared desktop already
            // failed the import above). §1.146: that precedence now lives in
            // RootFsProfileResolver, which folds the declaration in as the
            // highest-priority profile source, so identity and install plan
            // can never disagree about which one won.
            identity = resolution.identity,
            requiredFreeBytes = if (payload.scan.totalUncompressed > 0) {
                payload.scan.totalUncompressed + archiveBytes + SLACK_BYTES
            } else {
                0L
            },
            capturedConfig = effectiveCaptured,
            descriptor = effectiveDescriptor,
            archiveFormat = outerFormat,
            profileId = resolution.profile.id,
            profileSource = resolution.source,
            requiredDirectories = resolution.profile.layout.requiredDirectories.toList(),
            shellCandidates = resolution.profile.layout.shellCandidates,
            installSteps = resolution.plan,
            payloadFileName = payload.file.name,
            stripComponents = payload.scan.stripComponents,
            payloadFormat = payload.format,
            loginShell = resolution.profile.environment.loginShell,
            packageManager = resolution.profile.environment.packageManager,
            resolutionNotes = resolution.notes
        )
    }

    // ── Passes 2–4 split (§1.84): install(analysis) ─────────────────────
    // Consumes a successful analyze() result: the archive was already
    // validated/checksummed and teed into the import cache, so this phase
    // skips the scan entirely and goes straight to native extraction.

    override suspend fun install(
        workspaceId: String,
        analysis: AnalyzeResult.Success,
        onProgress: suspend (InstallProgress) -> Unit
    ): InstallResult {
        return try {
            installStaged(workspaceId, analysis, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is not an install failure — leave staging to the
            // recovery pass (mirrors DownloadService's contract) and unwind.
            throw e
        } catch (e: Exception) {
            // F8 fix (2026-09-02): the cleanup contract ("failure at any
            // point discards staging") used to hold only for RETURNED
            // failures — an exception mid-install (e.g. ENOSPC while seeding
            // resolv.conf, or a fixup write failure now surfaced by the F16
            // fix) propagated out of install() and left staging +
            // .import-cache on disk until the next process's recovery pass.
            // The exception is now converted to the regular Failure shape:
            // staging + import cache are discarded here, and every caller
            // (ImportService / DownloadService / InstallCoordinator) reports
            // it through the exact path it already uses for every other
            // failed install.
            Logger.e(TAG, "install($workspaceId) threw — discarding staging debris", e)
            fileManager.getStagingRootfsPath(workspaceId).deleteRecursivelyAllowingReadOnly()
            File(fileManager.getWorkspaceBasePath(workspaceId), IMPORT_CACHE_DIR).deleteRecursively()
            InstallResult.Failure(e.message ?: "Installation failed: ${e.javaClass.simpleName}")
        }
    }

    /** The real install body (extracted by the F8 fix so exceptions can be caught around it). */
    private suspend fun installStaged(
        workspaceId: String,
        analysis: AnalyzeResult.Success,
        onProgress: suspend (InstallProgress) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        val staging = fileManager.getStagingRootfsPath(workspaceId)
        // A fresh install() call never trusts leftover staging content for
        // this workspaceId — if a previous attempt was interrupted,
        // InstallRecoveryManager deals with that at app startup; this call
        // always starts from empty.
        staging.deleteRecursivelyAllowingReadOnly()
        staging.mkdirs()

        val importCache = File(fileManager.getWorkspaceBasePath(workspaceId), IMPORT_CACHE_DIR)
        if (!File(importCache, SOURCE_ARCHIVE_FILE_NAME).isFile) {
            return@withContext failAndCleanUp(
                staging, importCache,
                "Prepared archive is missing — analyze() must run first"
            )
        }

        // §16.3.2: `forceSetup` for the same reason as in analyze() — `!isReady`
        // is a detected-corruption signal, and the memo must not be allowed to
        // turn the repair into a no-op.
        if (!bootstrapManager.isReady) {
            bootstrapManager.forceSetup()
        }
        if (!bootstrapManager.isReady) {
            return@withContext failAndCleanUp(
                staging, importCache,
                "Native bootstrap binaries are missing (busybox/tar/proot)"
            )
        }

        onProgress(InstallProgress(0, null, null, InstallPhase.EXTRACTING))

        // §1.147: analyze() already unwrapped any ZIP / nested archive / OCI
        // layer into a real rootfs archive inside the import cache. The
        // extractor is pointed at THAT file — never at a container that merely
        // contains a rootfs — and at the resolved strip count.
        val extractionSource = File(importCache, analysis.payloadFileName)
        if (!extractionSource.isFile) {
            return@withContext failAndCleanUp(
                staging, importCache,
                "Prepared rootfs payload '${analysis.payloadFileName}' is missing — analyze() must run first"
            )
        }

        // §1.88: the extractor reports REAL progress by parsing GNU tar
        // checkpoint markers against the uncompressed size known from the
        // analysis pass — no more frozen/fake phase fractions.
        //
        // 2026-09-02 ("Now processing" fix): the extractor also reports the
        // member GNU tar `-v` is currently writing (its stdout carries exactly
        // those names, drained separately from the proot/tar diagnostics), and
        // install() forwards it as `InstallProgress.currentEntryName` — the
        // field the UI already rendered. No fabricated names: when `-v` output
        // is unavailable the field simply stays null and the UI hides the row.
        val extractFailure = rootfsExtractor.extract(
            tempArchive = extractionSource,
            staging = staging,
            workspaceId = workspaceId,
            expectedExtractedBytes = analysis.requiredStorageBytes,
            stripComponents = analysis.stripComponents,
            onProgress = { fraction ->
                onProgress(InstallProgress(0, null, null, InstallPhase.EXTRACTING, fraction))
            },
            onEntry = { entryName ->
                onProgress(InstallProgress(0, null, entryName, InstallPhase.EXTRACTING))
            }
        )
        if (extractFailure != null) {
            return@withContext failAndCleanUp(staging, importCache, extractFailure)
        }

        // §1.168: PRoot's `--link2symlink` hardlink emulation writes symlinks
        // whose targets are ABSOLUTE staging paths, so every one of them
        // dangles the moment activateStagedRootfs() renames staging to
        // `rootfs` (Arch ships 1835 such members, Fedora 1353, Debian and
        // Ubuntu 2 each, Alpine none). Rewriting them to relative targets here
        // — still inside staging, before validation — makes them correct both
        // before and after the rename, so activation stays a plain rename and
        // nothing needs repairing afterwards. Fail-closed: a tree that cannot
        // be fully repaired is discarded rather than promoted half-valid.
        val linkRepairFailure = l2sLinkRewriter.rewrite(staging, workspaceId)
        if (linkRepairFailure != null) {
            return@withContext failAndCleanUp(staging, importCache, linkRepairFailure)
        }

        // RFS-03: the archive boundary is audited BEFORE any account chmod,
        // account write, resolv.conf replacement, or activation. Safe absolute
        // links are rewritten to relative targets; escaping links and
        // symlinked account files reject the archive. This is the last gate
        // between extracted attacker-controlled links and host-side writes.
        val boundaryFailure = archiveBoundaryAuditor.auditAndNormalize(staging, workspaceId)
        if (boundaryFailure != null) {
            return@withContext failAndCleanUp(staging, importCache, boundaryFailure)
        }

        // §1.146: validated against the resolved profile's layout rules, not a
        // hardcoded Debian directory list. Failure here still discards staging.
        val rootfsValidation = validateExtractedRootfs(
            staging = staging,
            requiredDirectories = analysis.requiredDirectories,
            shellCandidates = analysis.shellCandidates,
            profileId = analysis.profileId
        )
        if (rootfsValidation != null) {
            return@withContext failAndCleanUp(staging, importCache, rootfsValidation)
        }

        // §1.146: the setup passes are the profile's ordered plan, not a fixed
        // sequence. Each constant maps to code that already existed and was
        // already audited (see InstallStep) — the plan chooses whether and in
        // what order, never what.
        Logger.i(TAG, "§1.146 install plan for '${analysis.profileId}': ${analysis.installSteps.joinToString(" → ")}")
        val stepFailure = runInstallSteps(staging, workspaceId, analysis.installSteps, onProgress)
        if (stepFailure != null) {
            return@withContext failAndCleanUp(staging, importCache, stepFailure)
        }

        // Issue #4 (2026-09-03) + #2 (2026-09-04): the guest startup directory
        // (since #2, always the account HOME) is ensured at INSTALL time, so
        // the very first terminal already opens in it (the runtime re-ensures
        // it at every launch, self-healing workspaces imported before this
        // step existed). Fail-open: a failed mkdir here only costs the first
        // launch its workspace fallback — the launch path falls back to HOME —
        // and must not fail an install whose rootfs itself is fine.
        val guestEnv = com.fem.linexa.service.runtime.container.GuestEnvironmentResolver
            .resolve(analysis.descriptor.metadata)
        // 2026-11-13 unified home architecture: fresh installations get the
        // real `linexa` account, its nine standard directories and the XDG
        // `user-dirs.dirs` before activation. The same `ensureGuestUser` runs
        // on every launch, so existing workspaces are repaired idempotently.
        // It never throws; an image without a recognizable account database
        // degrades to the root identity exactly as at launch time.
        val installedIdentity = guestRootfsInitializer.ensureGuestUser(
            staging,
            workspaceId,
            preferredUser = guestEnv.user,
            preferredHome = guestEnv.home
        )
        Logger.i(
            TAG,
            "workspace user for $workspaceId: ${installedIdentity.name} " +
                "(uid=${installedIdentity.uid} gid=${installedIdentity.gid} home=${installedIdentity.home})"
        )
        guestRootfsInitializer.ensureGuestWorkspace(staging, workspaceId, installedIdentity.home)?.let { message ->
            Logger.w(TAG, "workspace directory provisioning failed during install for $workspaceId — continuing: $message")
        }

        // §1.147: the last gate before activation — prove the extracted tree
        // can EXECUTE under the real PRoot launcher. Every earlier check is
        // static (directories exist, a shell path exists, links are safe), and
        // none of them can catch the failure users actually report: "the
        // install completed but the terminal exits immediately". The smoke
        // test starts the resolved login shell and the declared package
        // manager inside staging; a failure discards staging and leaves any
        // previous live rootfs untouched.
        onProgress(InstallProgress(0, null, null, InstallPhase.FINALIZING))
        val runtimeFailure = runtimeVerifier.verify(staging, workspaceId, analysis)
        if (runtimeFailure != null) {
            return@withContext failAndCleanUp(staging, importCache, runtimeFailure)
        }

        val configFailure = writeWorkspaceConfig(workspaceId, analysis.capturedConfig)
        if (configFailure != null) {
            return@withContext failAndCleanUp(staging, importCache, configFailure)
        }

        if (!fileManager.activateStagedRootfs(workspaceId)) {
            return@withContext failAndCleanUp(staging, importCache, "Could not activate staged rootfs (rename failed)")
        }

        importCache.deleteRecursively()
        Logger.i(TAG, "Installed $workspaceId via native tar, sha256=${analysis.sha256}")
        InstallResult.Success(analysis.sha256)
    }

    /**
     * §1.146 — runs the resolved profile's setup plan, in order.
     *
     * ## Why the account fixup and resolv.conf are separate steps here
     *
     * Before §1.146 `RootfsFixupService.fixUp` did both unconditionally, so a
     * profile had no way to request one without the other. They are genuinely
     * independent: the account fixup writes `/etc/passwd`-family files that only
     * matter because PRoot emulates the app's uid, while `resolv.conf` is a
     * networking concern. The service still performs both by default, so nothing
     * outside this method changed behaviour.
     *
     * Duplicate steps are collapsed: a plan is data and could name a step twice
     * (an embedded profile inherits its plan from a built-in, but a future
     * hand-written one might not), and running the account fixup twice would
     * append a second `aid_*` line for the same uid.
     *
     * [InstallStep.UNWRAP_OCI_LAYER] is intentionally a no-op here: unwrapping
     * has to happen *before* extraction, which is upstream of this method, and
     * it is driven by the payload resolution performed during analysis — a
     * resolved file beats a step that would have to re-derive it. The constant
     * remains in the plan as documentation of what the profile expects.
     *
     * Returns null when every step succeeded, or the first failure message.
     */
    private suspend fun runInstallSteps(
        staging: File,
        workspaceId: String,
        steps: List<InstallStep>,
        onProgress: suspend (InstallProgress) -> Unit
    ): String? {
        val plan = steps.distinct().ifEmpty { RootFsProfileResolver.GENERIC_PROFILE.install }
        val needsFixing = plan.any {
            it == InstallStep.ANDROID_ACCOUNT_FIXUP || it == InstallStep.SEED_RESOLV_CONF
        }
        if (needsFixing) {
            onProgress(InstallProgress(0, null, null, InstallPhase.FIXING))
        }

        for (step in plan) {
            when (step) {
                InstallStep.ANDROID_ACCOUNT_FIXUP -> {
                    val failure = rootfsFixupService.fixUp(
                        staging = staging,
                        workspaceId = workspaceId,
                        accountFixup = true,
                        seedResolvConf = false
                    )
                    if (failure != null) return failure
                }

                InstallStep.SEED_RESOLV_CONF ->
                    rootfsFixupService.seedResolvConf(staging, workspaceId)

                // §1.80: distro tarballs omit /tmp entirely (Debian's is a
                // boot-time tmpfs mount), so it is created during installation
                // rather than shipped broken. The runtime re-ensures it at every
                // launch too; this makes fresh installs start clean. Idempotent,
                // so a distribution that ships its own /tmp pays only a stat.
                InstallStep.ENSURE_TMP -> {
                    val failure = guestRootfsInitializer.ensureGuestTmp(staging, workspaceId)
                    if (failure != null) return failure
                }

                // F-5: guest POSIX shm surface — created at install time so
                // fresh workspaces start clean; the runtime re-ensures it on
                // every launch so existing/imported workspaces self-heal.
                // Idempotent: a distribution that ships its own /dev/shm
                // pays only a stat + chmod.
                InstallStep.ENSURE_DEV_SHM -> {
                    val failure = guestRootfsInitializer.ensureGuestDevShm(staging, workspaceId)
                    if (failure != null) return failure
                }

                // R-5: guest /run surface — created at install time so fresh
                // workspaces start clean; the runtime re-ensures it on every
                // launch so existing/imported workspaces self-heal. Same
                // shape as ENSURE_DEV_SHM.
                InstallStep.ENSURE_RUN -> {
                    val failure = guestRootfsInitializer.ensureGuestRun(staging, workspaceId)
                    if (failure != null) return failure
                }

                // R-6: /proc bind-over snapshots — written at install time
                // so the first launch has them already in place; the runtime
                // re-writes them on every launch so the values stay fresh
                // across PRoot upgrades. Static content (the host's /proc is
                // EACCES even at install time), so the "snapshot" is a
                // synthetic minimum.
                InstallStep.ENSURE_PROC_SNAPSHOTS -> {
                    val failure = guestRootfsInitializer.ensureProcSnapshots(staging, workspaceId)
                    if (failure != null) return failure
                }

                // 2026-08-27 expansion (Phase 15): guest linexa-hostinfo
                // helper, installed 0755. Data flows through /run/linexa/
                // hostinfo.conf at runtime; this only places the viewer.
                InstallStep.ENSURE_HOSTINFO_BIN -> {
                    val failure = guestRootfsInitializer.ensureGuestHostInfo(staging, workspaceId)
                    if (failure != null) return failure
                }

                InstallStep.UNWRAP_OCI_LAYER -> Unit
            }
        }
        return null
    }

    // ── Pass 1: tee the source, then scan the seekable copy ─────────────

    /**
     * Copies [sourceUri] into [tempArchive] exactly once while feeding every
     * byte through [digest] (SHA-256 of the *compressed* archive — what the
     * catalog hashes).
     *
     * §1.147: this replaced the old "tee while tar-walking" pass. A tar walk
     * cannot inspect a ZIP container or a nested archive at all, and reopening
     * an arbitrary `content://` provider a second time is unreliable (the
     * provider may not be seekable, may revoke permission, or may be a
     * one-shot pipe). Materializing the file first makes every later
     * inspection a local, repeatable read.
     *
     * @return null on success, or a user-facing failure reason. The caller
     *   deletes the import cache on failure.
     */
    private suspend fun teeSource(
        sourceUri: Uri,
        tempArchive: File,
        digest: MessageDigest,
        totalBytes: Long?,
        onProgress: suspend (InstallProgress) -> Unit
    ): String? {
        val opened = try {
            application.contentResolver.openInputStream(sourceUri)
        } catch (e: Exception) {
            null
        } ?: return "Could not open source: $sourceUri"

        return try {
            opened.use { raw ->
                DigestInputStream(raw, digest).use { digested ->
                    FileOutputStream(tempArchive).use { out ->
                        val buffer = ByteArray(TEE_BUFFER_BYTES)
                        var delivered = 0L
                        var lastProgressAt = 0L
                        while (true) {
                            val n = digested.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            delivered += n
                            if (delivered - lastProgressAt >= PROGRESS_EMIT_INTERVAL_BYTES) {
                                lastProgressAt = delivered
                                onProgress(InstallProgress(delivered, totalBytes, null, InstallPhase.ANALYZING))
                            }
                        }
                        onProgress(InstallProgress(delivered, totalBytes, null, InstallPhase.ANALYZING))
                    }
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Tee pass failed for $sourceUri", e)
            "Could not read the archive: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** A file ready for native extraction, plus the scan that describes it. */
    private data class PayloadScan(
        val file: File,
        val format: RootFsArchiveFormat,
        val scan: EntryScan
    )

    private sealed interface PayloadOutcome {
        data class Success(val payload: PayloadScan) : PayloadOutcome
        data class Failure(val reason: String) : PayloadOutcome
    }

    /** A member of a container that itself looks like an archive. */
    private data class NestedCandidate(
        val path: String,
        val size: Long,
        val format: RootFsArchiveFormat
    )

    /**
     * §1.147 — resolves [archive] to the file that actually contains a rootfs.
     *
     * The user's download is not always a rootfs tarball:
     *
     *  - a **ZIP** holds one or more archives as members (the member is
     *    selected by content, never by the outer file's name);
     *  - a **nested archive** is a tar member that is itself the rootfs;
     *  - an **OCI image layout** is an outer tar whose `blobs/sha256/…` member
     *    is the rootfs layer.
     *
     * Every descent re-runs the same tar scan, so validation and metadata
     * capture apply to whichever file really is the payload. The resolved
     * file stays in the import cache and `AnalyzeResult.Success` names it.
     *
     * Descent is deliberately conservative: a tar that already carries a
     * profile-nominated identity signal, or that has rootfs-shaped top-level
     * directories (`etc/`, `usr/`), is treated as the rootfs itself. Only a
     * container with neither is searched for a nested payload, which keeps a
     * documentation tarball inside `usr/share/doc` from being mistaken for
     * the image.
     */
    private fun resolvePayload(
        archive: File,
        format: RootFsArchiveFormat,
        importCache: File,
        depth: Int
    ): PayloadOutcome {
        if (depth > MAX_PAYLOAD_DEPTH) {
            return PayloadOutcome.Failure(
                "Archive nesting exceeds the supported depth ($MAX_PAYLOAD_DEPTH); refusing to guess which layer is the rootfs"
            )
        }
        if (format.isZip) return resolveZipPayload(archive, importCache, depth)

        val scan = try {
            scanTarFile(archive, format)
        } catch (e: InstallAbortException) {
            return PayloadOutcome.Failure(e.reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Scan failed for '${archive.name}'", e)
            return PayloadOutcome.Failure(
                "Could not read archive '${archive.name}': ${e.message ?: e.javaClass.simpleName}"
            )
        }

        // OCI image layout: the rootfs is a tar inside one of the blobs. The
        // layer path came from the layout's own index.json, never from a name
        // heuristic; the unwrapper validates the layer's internal entries.
        when (val oci = scan.collector.resolveOciLayer(scan.stripComponents)) {
            is OciImageLayout.Result.Layer -> {
                Logger.i(TAG, "§1.147 OCI image layout: rootfs layer is '${oci.layerPath}'")
                return when (
                    val unwrapped = ociLayerUnwrapper.unwrap(
                        archive,
                        importCache,
                        oci.layerPath,
                        scan.stripComponents
                    )
                ) {
                    is OciLayerUnwrapper.Result.Failure -> PayloadOutcome.Failure(unwrapped.reason)
                    is OciLayerUnwrapper.Result.Layer -> {
                        val layerFormat = CompressedArchiveStream.detectFile(unwrapped.file)
                            ?: return PayloadOutcome.Failure(
                                "OCI rootfs layer '${oci.layerPath}' is not a recognized archive"
                            )
                        val layerScan = try {
                            scanTarFile(unwrapped.file, layerFormat)
                        } catch (e: Exception) {
                            return PayloadOutcome.Failure(
                                "Could not read the OCI rootfs layer '${oci.layerPath}': ${e.message}"
                            )
                        }
                        PayloadOutcome.Success(
                            PayloadScan(unwrapped.file, layerFormat, mergeMetadata(layerScan, scan))
                        )
                    }
                }
            }

            is OciImageLayout.Result.Invalid -> return PayloadOutcome.Failure(
                "This is an OCI image but its rootfs layer could not be resolved: ${oci.reason}"
            )

            is OciImageLayout.Result.NotOciLayout -> Unit
        }

        // Nested-payload search: only for a container with no rootfs shape of
        // its own. A single wrapper directory is handled by `stripComponents`
        // for a REAL rootfs, but it must not suppress this search: a tar whose
        // only top-level directory holds `rootfs.tar.gz` has exactly that
        // shape, and its identity signal is absent (the collector checks the
        // nested interpretation too), so the member — not the wrapper — is the
        // payload.
        if (!scan.collector.hasIdentitySignal(scan.stripComponents) &&
            !scan.hasRootfsLayout &&
            scan.nestedCandidates.isNotEmpty()
        ) {
            tryNestedPayloads(archive, scan, importCache, depth)?.let { return it }
        }

        return PayloadOutcome.Success(PayloadScan(archive, format, scan))
    }

    /**
     * §1.147 — scans one seekable archive file with the same tar walk a plain
     * rootfs gets: every entry validated, metadata captured, root shape
     * observed, and nested archive members noted.
     */
    private fun scanTarFile(file: File, format: RootFsArchiveFormat): EntryScan {
        file.inputStream().use { raw ->
            val opened = CompressedArchiveStream.open(raw)
            // The re-sniff is deliberate: the bytes on disk decide the format,
            // not the caller's label. A nested member whose declared name lied
            // is still walked as what it actually is.
            opened.stream.use { decompressed ->
                TarArchiveInputStream(decompressed).use { tar ->
                    val scan = scanEntries(tar, opened.format)
                    // Drain the decompressor to EOF. The tar walk stops at the
                    // end-of-archive marker; trailing compressed bytes are
                    // where a truncated download and a corrupt gzip/xz/zstd
                    // trailer actually surface. Ignoring them would accept a
                    // half-downloaded archive as complete.
                    while (decompressed.read() != -1) {
                    }
                    return scan
                }
            }
        }
    }

    /**
     * §1.147 — ZIP container resolution.
     *
     * Members are candidates only when their first bytes sniff as a supported
     * archive; candidates are tried largest-first, because a rootfs archive is
     * normally the largest member of a distribution bundle. The first member
     * that yields a profile identity wins; if none does, the largest member's
     * scan is returned so an undetectable-but-valid rootfs still installs
     * through the generic plan.
     *
     * Nothing from the ZIP's member names reaches the filesystem as a path:
     * the target is constructed here (`payload-zip-N`) and the member is
     * copied through `ZipFile.getInputStream`.
     */
    private fun resolveZipPayload(zipFile: File, importCache: File, depth: Int): PayloadOutcome {
        val candidates = try {
            ZipFile(zipFile).use { zip ->
                val out = mutableListOf<NestedCandidate>()
                val entries = zip.entries()
                while (entries.hasMoreElements() && out.size < MAX_ZIP_CANDIDATES) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val size = entry.size
                    if (size < MIN_NESTED_ARCHIVE_BYTES || size > MAX_NESTED_ARCHIVE_BYTES) continue
                    val header = ByteArray(CompressedArchiveStream.MAGIC_BYTES_TO_PEEK)
                    val read = zip.getInputStream(entry).use { input -> readUpTo(input, header) }
                    val detected = CompressedArchiveStream.detect(header, read) ?: continue
                    out += NestedCandidate(entry.name, size, detected)
                }
                out.sortedWith(
                    compareByDescending<NestedCandidate> { nestedNameScore(it.path) }
                        .thenByDescending { it.size }
                )
            }
        } catch (e: Exception) {
            return PayloadOutcome.Failure("Could not read ZIP container '${zipFile.name}': ${e.message}")
        }

        if (candidates.isEmpty()) {
            return PayloadOutcome.Failure(
                "ZIP container holds no rootfs archive: no member is a tar, compressed tar, or ZIP"
            )
        }

        var fallback: PayloadScan? = null
        var lastFailure: String? = null
        for ((index, candidate) in candidates.take(MAX_PAYLOAD_ATTEMPTS).withIndex()) {
            val target = File(importCache, "payload-zip-$depth-$index")
            val extractFailure = extractZipMember(zipFile, candidate.path, target)
            if (extractFailure != null) {
                lastFailure = extractFailure
                continue
            }
            val nestedFormat = CompressedArchiveStream.detectFile(target)
            if (nestedFormat == null) {
                target.delete()
                lastFailure = "ZIP member '${candidate.path}' is not a recognized archive"
                continue
            }
            when (val resolved = resolvePayload(target, nestedFormat, importCache, depth + 1)) {
                is PayloadOutcome.Failure -> {
                    lastFailure = resolved.reason
                    target.delete()
                }
                is PayloadOutcome.Success -> {
                    if (resolved.payload.scan.collector
                            .hasIdentitySignal(resolved.payload.scan.stripComponents)
                    ) {
                        return resolved
                    }
                    if (fallback == null) fallback = resolved.payload
                }
            }
        }
        return fallback?.let { PayloadOutcome.Success(it) }
            ?: PayloadOutcome.Failure(lastFailure ?: "ZIP container holds no installable rootfs")
    }

    /**
     * §1.147 — tries the container's nested archive members, largest and most
     * rootfs-looking first. Returns null when there is nothing to try so the
     * caller can fall through to the ordinary generic result.
     */
    private fun tryNestedPayloads(
        container: File,
        scan: EntryScan,
        importCache: File,
        depth: Int
    ): PayloadOutcome? {
        val candidates = scan.nestedCandidates
            .sortedWith(
                compareByDescending<NestedCandidate> { nestedNameScore(it.path) }
                    .thenByDescending { it.size }
            )
            .take(MAX_PAYLOAD_ATTEMPTS)
        if (candidates.isEmpty()) return null

        var fallback: PayloadScan? = null
        var lastFailure: String? = null
        for ((index, candidate) in candidates.withIndex()) {
            val target = File(importCache, "payload-nested-$depth-$index")
            val extractFailure = extractTarMember(container, candidate.path, target)
            if (extractFailure != null) {
                lastFailure = extractFailure
                continue
            }
            val nestedFormat = CompressedArchiveStream.detectFile(target)
            if (nestedFormat == null) {
                target.delete()
                lastFailure = "Nested archive '${candidate.path}' is not a recognized archive"
                continue
            }
            when (val resolved = resolvePayload(target, nestedFormat, importCache, depth + 1)) {
                is PayloadOutcome.Failure -> {
                    lastFailure = resolved.reason
                    target.delete()
                }
                is PayloadOutcome.Success -> {
                    // The payload's own declaration outranks the container's.
                    val scanWithMetadata = mergeMetadata(resolved.payload.scan, scan)
                    val payload = resolved.payload.copy(scan = scanWithMetadata)
                    if (scanWithMetadata.collector.hasIdentitySignal(scanWithMetadata.stripComponents)) {
                        return PayloadOutcome.Success(payload)
                    }
                    if (fallback == null) fallback = payload
                }
            }
        }
        return fallback?.let { PayloadOutcome.Success(it) }
            ?: lastFailure?.let { PayloadOutcome.Failure(it) }
    }

    /** §1.147: a name is a weak hint, used only to order candidates by size ties. */
    private fun nestedNameScore(path: String): Int {
        val lower = path.lowercase()
        var score = 0
        if (lower.contains("rootfs")) score += 4
        if (lower.contains("base")) score += 2
        if (lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".tar.zst") ||
            lower.endsWith(".tar.bz2") || lower.endsWith(".zip")
        ) score += 1
        return score
    }

    /**
     * §1.147: copies one ZIP member to [target]. The target path is built by
     * this class, never from [memberPath]; the member name is used only as an
     * exact lookup key, so a hostile `../../…` member cannot escape.
     */
    private fun extractZipMember(zipFile: File, memberPath: String, target: File): String? {
        target.delete()
        return try {
            ZipFile(zipFile).use { zip ->
                var entry = zip.getEntry(memberPath)?.takeUnless { it.isDirectory }
                if (entry == null) {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val candidate = entries.nextElement()
                        if (!candidate.isDirectory &&
                            ArchiveEntryPath.normalize(candidate.name) ==
                            ArchiveEntryPath.normalize(memberPath)
                        ) {
                            entry = candidate
                            break
                        }
                    }
                }
                if (entry == null) return "ZIP member '$memberPath' is no longer present"
                var copied = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(TEE_BUFFER_BYTES)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            if (copied > MAX_NESTED_ARCHIVE_BYTES) {
                                throw PayloadTooLarge(
                                    "ZIP member '$memberPath' exceeds the ${formatBytes(MAX_NESTED_ARCHIVE_BYTES)} nested-archive limit"
                                )
                            }
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (copied == 0L) "ZIP member '$memberPath' is empty" else null
            }
        } catch (e: Exception) {
            target.delete()
            "Could not extract ZIP member '$memberPath': ${e.message}"
        }.also { failure -> if (failure != null) target.delete() }
    }

    /**
     * §1.147: copies one tar member to [target] by normalized-name equality.
     * The entry name is never used as a filesystem path.
     */
    private fun extractTarMember(container: File, memberPath: String, target: File): String? {
        target.delete()
        return try {
            container.inputStream().use { raw ->
                val opened = CompressedArchiveStream.open(raw)
                opened.stream.use { decompressed ->
                    TarArchiveInputStream(decompressed).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory &&
                                ArchiveEntryPath.normalize(entry.name) ==
                                ArchiveEntryPath.normalize(memberPath)
                            ) {
                                var copied = 0L
                                FileOutputStream(target).use { out ->
                                    val buffer = ByteArray(TEE_BUFFER_BYTES)
                                    while (true) {
                                        val n = tar.read(buffer)
                                        if (n < 0) break
                                        copied += n
                                        if (copied > MAX_NESTED_ARCHIVE_BYTES) {
                                            throw PayloadTooLarge(
                                                "Nested archive '$memberPath' exceeds the ${formatBytes(MAX_NESTED_ARCHIVE_BYTES)} limit"
                                            )
                                        }
                                        out.write(buffer, 0, n)
                                    }
                                }
                                return if (copied == 0L) "Nested archive '$memberPath' is empty" else null
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            "Nested archive '$memberPath' was not found in '${container.name}'"
        } catch (e: Exception) {
            target.delete()
            "Could not extract nested archive '$memberPath': ${e.message}"
        }.also { failure -> if (failure != null) target.delete() }
    }

    /** Internal control-flow signal for the bounded member copy loops. */
    private class PayloadTooLarge(message: String) : Exception(message)

    /**
     * §1.147: the payload's own declaration wins; the enclosing container's
     * metadata is a fallback for an image whose wrapper carries the
     * declaration (e.g. an OCI layout with a root-level `.linexa.yaml`).
     */
    private fun mergeMetadata(inner: EntryScan, outer: EntryScan): EntryScan {
        val captured = inner.capturedConfig ?: outer.capturedConfig
        val descriptor = if (inner.descriptor != RootFsDescriptor.UNDECLARED) {
            inner.descriptor
        } else {
            outer.descriptor
        }
        if (captured === inner.capturedConfig && descriptor == inner.descriptor) return inner
        return inner.copy(capturedConfig = captured, descriptor = descriptor)
    }

    /**
     * Walks every tar entry: §1.14 safety check, archive-bomb accounting,
     * metadata capture, root-shape observation, and nested archive detection.
     *
     * §1.147 additions:
     *  - [EntryScan.topLevelSegments] records the archive's shape so
     *    [ArchiveRootDiscovery] can decide whether extraction must strip a
     *    wrapper directory. The decision is made after the whole walk, never
     *    from the first member.
     *  - [EntryScan.nestedCandidates] records members whose first bytes sniff
     *    as an archive, which is how a tar-inside-a-tar is discovered without
     *    trusting file names.
     *  - Metadata is captured both at the exact root and exactly one wrapper
     *    below it; the wrapper interpretation is selected only when the shape
     *    resolves to `stripComponents == 1`.
     *
     * Throws [InstallAbortException] on any validation failure.
     */
    private fun scanEntries(
        tarStream: TarArchiveInputStream,
        archiveFormat: RootFsArchiveFormat
    ): EntryScan {
        var totalUncompressed = 0L
        var totalEntries = 0L
        // §1.119: every recognized root-level metadata file is collected during
        // the walk and the winner is chosen *after* it (see
        // RootFsMetadataFile.bestOf). Deciding inside the loop would make the
        // result depend on tar member order, so an image shipping both
        // `.linexa.yaml` and `.tiny.yaml` would resolve differently depending on
        // how it was packed. Bounded by construction: at most one entry per enum
        // constant, each capped at MAX_CONFIG_BYTES.
        val metadataCandidates = linkedMapOf<RootFsMetadataFile, ByteArray>()
        // §1.147: a declaration one wrapper directory down is only the
        // archive's declaration when its parent IS the wrapper. Keying by
        // parent segment keeps an ordinary guest file such as
        // `etc/config.yaml` from being mistaken for one: that parent is not
        // the archive's sole top-level directory.
        val nestedMetadataCandidates =
            linkedMapOf<String, MutableMap<RootFsMetadataFile, ByteArray>>()
        // §1.146: the six per-distribution locals and the `when (lowerName)`
        // over hardcoded literals are gone. The collector derives which paths
        // matter from the loaded profiles, so a new distribution is a YAML file
        // and no change here.
        val collector = ArchiveSignalCollector(builtInProfiles())
        val topLevelSegments = LinkedHashSet<String>()
        val nestedCandidates = mutableListOf<NestedCandidate>()
        var shape = ArchiveRootDiscovery.Shape(emptySet(), rootLevelFile = false)
        var entry = tarStream.nextEntry

        while (entry != null) {
            // SECURITY: runs on the RAW entry name, before any normalization —
            // see ArchiveEntryPath's kdoc. Normalizing first would hand the
            // validator `../etc/passwd` already laundered. The shared
            // classifier also lets the validator apply hardlink-target and
            // special-file rules, which a name+symlink-flag pair cannot express.
            val safety = archiveValidator.checkEntry(
                name = entry.name,
                isSymlink = entry.isSymbolicLink,
                linkTarget = entry.linkName,
                entryType = entry.toArchiveEntryType()
            )
            if (safety is ArchiveValidator.ValidationResult.Invalid) {
                throw InstallAbortException("Unsafe archive entry '${entry.name}': ${safety.reason}")
            }

            val normalized = ArchiveEntryPath.normalize(entry.name)
            if (normalized.isNotEmpty()) {
                topLevelSegments += normalized.substringBefore('/')
                shape = ArchiveRootDiscovery.observe(shape, normalized, entry.isDirectory)
            }

            // §1.146: presence is recorded for directories and symlinks too — a
            // symlinked `etc/os-release` carries no data but still proves the
            // path exists, and an OCI layout is identified by entry names alone.
            val action = collector.inspectEntry(
                rawEntryName = entry.name,
                isSymbolicLink = entry.isSymbolicLink,
                isDirectory = entry.isDirectory,
                size = entry.size
            )

            if (!entry.isDirectory) {
                totalEntries++
                totalUncompressed += entry.size
                if (totalUncompressed > ArchiveValidator.DEFAULT_MAX_UNCOMPRESSED_SIZE) {
                    throw InstallAbortException(
                        "Archive bomb: uncompressed size exceeds limit ($totalUncompressed bytes)"
                    )
                }

                var consumed = false

                // Root-level metadata capture (§1.26 config, §1.118 native
                // metadata, §1.119 legacy `.tiny.yaml`) takes precedence over a
                // signal read: it is the only thing whose bytes are persisted,
                // and a `.linexa.yaml` is never also a marker file. Validation
                // runs after the walk, on the winning candidate only.
                val candidate = RootFsMetadataFile.match(entry.name)
                val nestedCandidate = if (candidate == null) nestedMetadataName(normalized) else null
                if (candidate != null || nestedCandidate != null) {
                    val targetMap = if (candidate != null) {
                        metadataCandidates
                    } else {
                        nestedMetadataCandidates.getOrPut(nestedCandidate!!.first) { linkedMapOf() }
                    }
                    val file = candidate ?: nestedCandidate!!.second
                    if (!targetMap.containsKey(file)) {
                        if (entry.size < 0 || entry.size > WorkspaceConfigParser.MAX_CONFIG_BYTES) {
                            Logger.w(TAG, "Skipping oversized metadata candidate '${entry.name}' (${entry.size} bytes)")
                        } else {
                            val buffer = ByteArrayOutputStream()
                            tarStream.copyTo(buffer)
                            targetMap[file] = buffer.toByteArray()
                            consumed = true
                        }
                    }
                }

                // §1.146 signal capture — profile-nominated markers, ELF headers
                // and the small OCI JSON documents. Each branch reads the entry
                // exactly once; the collector decided which, so the set of paths
                // read on an untrusted archive is bounded by the profiles.
                if (!consumed) {
                    when (action) {
                        ArchiveSignalCollector.Action.CAPTURE_TEXT -> {
                            collector.recordMarkerText(
                                entry.name,
                                captureSmallText(tarStream, MARKER_MAX_BYTES)
                            )
                            consumed = true
                        }

                        ArchiveSignalCollector.Action.CAPTURE_ELF -> {
                            val header = captureHeader(tarStream, ElfArchitecture.HEADER_BYTES)
                            collector.recordElfHeader(entry.name, header.bytes, header.length)
                            consumed = true
                        }

                        ArchiveSignalCollector.Action.CAPTURE_BLOB -> {
                            val buffer = ByteArrayOutputStream()
                            tarStream.copyTo(buffer)
                            collector.recordBlob(entry.name, buffer.toByteArray())
                            consumed = true
                        }

                        ArchiveSignalCollector.Action.SKIP -> Unit
                    }
                }

                // Anything neither captured nor consumed must still be drained
                // so the next entry starts at its own header. The same read is
                // reused to sniff a possible nested archive: reading 512 bytes
                // of every non-trivial member costs one extra copy of bytes tar
                // was going to skip anyway, and it is the only content-based
                // way to find a tar-inside-a-tar.
                if (!consumed) {
                    if (entry.size >= MIN_NESTED_ARCHIVE_BYTES &&
                        entry.size <= MAX_NESTED_ARCHIVE_BYTES &&
                        nestedCandidates.size < MAX_NESTED_CANDIDATES
                    ) {
                        val header = captureHeader(
                            tarStream,
                            CompressedArchiveStream.MAGIC_BYTES_TO_PEEK
                        )
                        val detected = CompressedArchiveStream.detect(header.bytes, header.length)
                        if (detected != null) {
                            nestedCandidates += NestedCandidate(normalized, entry.size, detected)
                        }
                    } else {
                        drainEntry(tarStream)
                    }
                }
            } else {
                // Directory entries carry no data.
                drainEntry(tarStream)
            }

            entry = tarStream.nextEntry
        }

        val stripComponents = ArchiveRootDiscovery.stripComponents(shape)
        val wrapper = topLevelSegments.singleOrNull()
        val chosenMetadata = if (stripComponents > 0 && wrapper != null) {
            nestedMetadataCandidates[wrapper] ?: emptyMap()
        } else {
            metadataCandidates
        }
        val resolved = resolveMetadata(chosenMetadata, archiveFormat)
        return EntryScan(
            capturedConfig = resolved.capturedConfig,
            descriptor = resolved.descriptor,
            totalUncompressed = totalUncompressed,
            totalEntries = totalEntries,
            collector = collector,
            stripComponents = stripComponents,
            topLevelSegments = topLevelSegments,
            nestedCandidates = nestedCandidates
        )
    }

    /**
     * §1.147: the metadata spelling exactly one wrapper directory below the
     * archive root (`rootfs/.linexa.yaml`), as `(parentSegment, file)`.
     *
     * The parent is returned rather than discarded so the caller can require
     * that it is the archive's *sole* top-level directory. Without it,
     * `etc/config.yaml` — an ordinary guest file — would be read as the
     * image's declaration.
     */
    private fun nestedMetadataName(normalized: String): Pair<String, RootFsMetadataFile>? {
        if (normalized.count { it == '/' } != 1) return null
        val parent = normalized.substringBefore('/')
        val base = normalized.substringAfterLast('/')
        val file = RootFsMetadataFile.entries.firstOrNull { it.fileName == base } ?: return null
        return parent to file
    }

    /** Reads at most [buffer].size bytes, tolerating short reads. */
    private fun readUpTo(stream: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val n = stream.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            if (n == 0) continue
            filled += n
        }
        return filled
    }

    /**
     * §1.119 — picks the winning root-level metadata file and inspects it.
     *
     * Priority is [RootFsMetadataFile]'s, not tar order: `.linexa.yaml` beats
     * `linexa.yaml` beats `config.yaml` beats `.tiny.yaml`. An image that
     * ships a native file *and* a legacy one is the expected shape of a Tiny
     * Container image republished for Linexa, and the native file is the only
     * one whose schema Linexa can validate — so it wins, and the legacy file is
     * left alone as an ordinary guest file (Tiny Container can still read it).
     *
     * Only the winner is inspected. That matters for the failure semantics: a
     * malformed *native* winner still fails the import (§1.26/§1.118 fail-hard,
     * unchanged), but a malformed loser cannot, and a malformed `.tiny.yaml`
     * never can (fail-open — see [TinyMetadataAdapter]).
     *
     * [ResolvedMetadata.capturedConfig] is non-null only for a native winner:
     * `{workspace}/config.yaml` is Linexa's own schema, re-read at every launch,
     * and persisting a foreign `.tiny.yaml` there would make every later read
     * log a warning about a file the app itself wrote.
     */
    private fun resolveMetadata(
        candidates: Map<RootFsMetadataFile, ByteArray>,
        archiveFormat: RootFsArchiveFormat
    ): ResolvedMetadata {
        if (candidates.isEmpty()) return ResolvedMetadata(null, RootFsDescriptor.UNDECLARED)

        val winner = RootFsMetadataFile.bestOf(candidates.keys)
            ?: return ResolvedMetadata(null, RootFsDescriptor.UNDECLARED)
        val bytes = candidates.getValue(winner)

        if (candidates.size > 1) {
            val losers = candidates.keys.filter { it != winner }.joinToString(", ") { it.fileName }
            Logger.i(
                TAG,
                "§1.119 archive carries ${candidates.size} metadata files — using '${winner.fileName}' " +
                    "(ignored: $losers)"
            )
        }

        val inspection = rootFsInspector.inspectFile(
            source = winner,
            bytes = bytes,
            archiveFormat = archiveFormat,
            supportedAbis = deviceAbis()
        )
        return when (inspection) {
            is RootFsInspector.Result.Success -> ResolvedMetadata(
                // Only a native file is Linexa's own schema, so only a native
                // file is persisted as the workspace's config.yaml.
                capturedConfig = bytes.takeIf { winner.isNative },
                descriptor = inspection.descriptor
            )

            is RootFsInspector.Result.Invalid -> throw InstallAbortException(
                "Invalid metadata file '${winner.fileName}': ${inspection.reason}"
            )
        }
    }

    /** §1.119: outcome of [resolveMetadata] — what to persist, and what was declared. */
    private data class ResolvedMetadata(
        val capturedConfig: ByteArray?,
        val descriptor: RootFsDescriptor
    )

    // ── Rootfs validation after extraction ──────────────────────────────

    /**
     * Validates that the extracted tree matches what the resolved profile says
     * a rootfs of this distribution looks like. Returns null when valid.
     *
     * §1.146 replaced the hardcoded `listOf("bin", "etc", "lib", "usr", "var")`
     * — a Debian assumption presented as a universal one — with the profile's
     * own [com.fem.linexa.service.rootfs.profile.LayoutRules]. A usr-merged tree
     * with no top-level `lib` is a legitimate rootfs and used to be rejected
     * with "Extracted rootfs is incomplete" after a full extraction had already
     * been written to staging.
     *
     * The shell check reads the same profile's candidate list, so an
     * Alpine image with only busybox `ash` at `bin/sh` passes and the error
     * message names what was actually looked for instead of naming bash.
     *
     * A symlink is accepted as satisfying a requirement: in a usr-merged tree
     * `bin` *is* a symlink to `usr/bin`, so requiring a real directory would
     * reject the very layout this method was rewritten to accept. [File.exists]
     * follows symlinks, and a dangling one is caught by the shell check that
     * follows.
     */
    private fun validateExtractedRootfs(
        staging: File,
        requiredDirectories: List<String>,
        shellCandidates: List<String>,
        profileId: String
    ): String? {
        val missing = requiredDirectories.filter { !File(staging, it).exists() }
        if (missing.isNotEmpty()) {
            val msg = "Extracted rootfs is incomplete — missing essential dirs: ${missing.joinToString(", ")}"
            Logger.e(TAG, "$msg (profile '$profileId')")
            return msg
        }
        val shell = shellCandidates.firstOrNull { File(staging, it).exists() }
        if (shell == null) {
            val msg = "Extracted rootfs has no shell (looked for ${shellCandidates.joinToString(", ") { "/$it" }})"
            Logger.e(TAG, "$msg (profile '$profileId')")
            return msg
        }
        Logger.i(
            TAG,
            "§1.146 rootfs validation passed for profile '$profileId': " +
                "dirs=${requiredDirectories.joinToString(",")} shell=/$shell"
        )
        return null
    }

    // ── Pass 4: persist config.yaml ────────────────────────────────────

    private fun writeWorkspaceConfig(workspaceId: String, configBytes: ByteArray?): String? {
        val bytes = configBytes ?: return null
        val target = File(fileManager.getWorkspaceBasePath(workspaceId), "config.yaml")
        return try {
            target.writeBytes(bytes)
            null
        } catch (e: Exception) {
            Logger.e(TAG, "Could not write config.yaml", e)
            "Could not write config.yaml: ${e.message}"
        }
    }

    // ── shared plumbing ────────────────────────────────────────────────

    /**
     * §1.84/§1.88: intermediate tally returned by [scanEntries] — the
     * uncompressed-size estimate, the entry count (extraction progress
     * denominator), and the captured root config bytes. §1.118 adds the
     * archive's own declaration.
     *
     * §1.146 replaced the five per-distribution signal fields (`osRelease`,
     * `alpineRelease`, `archRelease`, `debianVersion`, `fedoraRelease`) plus
     * `archHints` with the [collector]. That is the point of the change: the
     * old shape had one field per distribution Linexa knew about, so adding a
     * distribution meant editing this class, `scanEntries`, and every
     * construction site — and Arch Linux ARM, whose rootfs reveals its
     * architecture only through an ELF header, could not be expressed at all.
     */
    private data class EntryScan(
        /**
         * The winning **native** metadata file's bytes, persisted verbatim as
         * `{workspace}/config.yaml`. Null when the archive carried no native
         * file — including when it carried only a legacy `.tiny.yaml`, which is
         * never written there (§1.119).
         */
        val capturedConfig: ByteArray?,
        /**
         * §1.118/§1.119: the archive's declaration, from whichever metadata
         * file won, or UNDECLARED.
         */
        val descriptor: RootFsDescriptor,
        /** Total uncompressed archive size — required-storage estimate. */
        val totalUncompressed: Long,
        /** Non-directory entry count — extraction checkpoint denominator. */
        val totalEntries: Long,
        /**
         * §1.146: everything the walk observed, ready to be resolved against
         * the profile set. Carried rather than pre-resolved because the
         * embedded declaration in [descriptor] must be folded in first (see
         * [resolveProfile]) and that needs both halves.
         */
        val collector: ArchiveSignalCollector,
        /**
         * §1.147: leading components the extractor must strip. Non-zero only
         * when every entry shares one wrapper directory and no file sits at
         * the archive root — see [ArchiveRootDiscovery].
         */
        val stripComponents: Int = 0,
        /**
         * §1.147: the archive's first path segment for every entry, used only
         * to decide whether the payload search should look inside it and to
         * keep the root-shape decision auditable.
         */
        val topLevelSegments: Set<String> = emptySet(),
        /**
         * §1.147: members whose first bytes sniff as an archive. Only
         * collected for a container that has no rootfs shape of its own, so
         * this stays empty for ordinary images.
         */
        val nestedCandidates: List<NestedCandidate> = emptyList()
    ) {
        /**
         * True when the root-level layout already looks like a Linux rootfs
         * (`etc/`, `usr/`, …). A container is only searched for a nested
         * payload when it has neither this nor a profile identity signal.
         */
        val hasRootfsLayout: Boolean
            get() = topLevelSegments.any { it in ROOTFS_TOP_LEVEL_SEGMENTS }
    }

    // ── §1.146: profile resolution ──────────────────────────────────────

    /**
     * The built-in profiles, or an empty list if the catalog cannot be read.
     *
     * An empty list is a working state, not a failure: [RootFsProfileResolver]
     * then resolves every archive to
     * [RootFsProfileResolver.GENERIC_PROFILE], which is the same conservative
     * outcome an unrecognized archive gets. Failing the import instead would
     * turn a packaging mistake in Linexa's own assets into "no rootfs can be
     * installed", which is strictly worse.
     */
    private fun builtInProfiles(): List<RootFsProfile> =
        runCatching { profileCatalog.profiles() }
            .onFailure { Logger.e(TAG, "§1.146 could not load built-in profiles", it) }
            .getOrDefault(emptyList())

    /**
     * §1.146 — resolves the scan into the profile that governs this install.
     *
     * Precedence is [RootFsProfileResolver]'s, with the embedded declaration
     * folded in here because only this layer has both the descriptor (§1.118's
     * validated `.linexa.yaml`) and the collected signals:
     *
     *  1. a **valid** embedded declaration → [EmbeddedProfileAdapter], which
     *     takes the archive's word on *what it is* and the built-in profile's
     *     word on *what installing that requires*;
     *  2. built-in profile by os-release `ID`, then by distinctive marker;
     *  3. the generic profile.
     *
     * An *invalid* embedded declaration never reaches here: [resolveMetadata]
     * already failed the import for a malformed native file (§1.118's
     * fail-hard rule), and a legacy `.tiny.yaml` yields no metadata at all, so
     * [EmbeddedProfileAdapter.from] returns null and resolution falls through
     * to the built-ins. Either way the outcome is deterministic and nothing
     * from the archive is executed — a profile is data, and [InstallStep] is a
     * closed enum precisely so an embedded declaration cannot name an action
     * that does not already exist in reviewed code.
     */
    private fun resolveProfile(scan: EntryScan, fallbackName: String?): RootFsResolution {
        val profiles = builtInProfiles()
        val embedded = EmbeddedProfileAdapter.from(scan.descriptor, profiles)
        val signals = scan.collector.build(embedded, scan.stripComponents)
        val resolution = RootFsProfileResolver.resolve(signals, profiles, fallbackName)

        Logger.i(
            TAG,
            "§1.146 resolved profile '${resolution.profile.id}' (source=${resolution.source}) " +
                "identity=${resolution.identity.distroName} ${resolution.identity.version} " +
                "${resolution.identity.arch.label} detected=${resolution.detected}"
        )
        resolution.notes.forEach { Logger.i(TAG, "§1.146   $it") }
        return resolution
    }

    /**
     * Reads the current entry's text content up to [maxBytes], draining the
     * remainder so [TarArchiveInputStream.nextEntry] advances past it.
     * Returns null when the entry is a symlink or empty.
     */
    private fun captureSmallText(tarStream: TarArchiveInputStream, maxBytes: Int): String? {
        if (tarStream.currentEntry?.isSymbolicLink == true) return null
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var kept = 0
        while (true) {
            val n = tarStream.read(chunk)
            if (n < 0) break
            if (kept < maxBytes) {
                val toKeep = minOf(n, maxBytes - kept)
                out.write(chunk, 0, toKeep)
                kept += toKeep
            }
        }
        val text = out.toString(Charsets.UTF_8).trim()
        return text.ifEmpty { null }
    }

    /** Reads and discards the current entry's data (advances to the next header). */
    private fun drainEntry(tarStream: TarArchiveInputStream) {
        val chunk = ByteArray(8192)
        while (tarStream.read(chunk) != -1) {
        }
    }

    /**
     * §1.146 — reads the first [maxBytes] of the current entry and drains the
     * rest, for the ELF header probe.
     *
     * Separate from [captureSmallText] because an ELF header is bytes, not
     * text: decoding it as UTF-8 and re-encoding would corrupt `e_machine`
     * (0xB7 is not valid UTF-8 and would become U+FFFD). The remainder is
     * always drained — reading 20 bytes of a 900 KB `bin/busybox` and stopping
     * would leave the tar stream mid-member and desynchronize every later
     * entry.
     */
    private fun captureHeader(tarStream: TarArchiveInputStream, maxBytes: Int): CapturedHeader {
        val header = ByteArray(maxBytes)
        var filled = 0
        while (filled < maxBytes) {
            val n = tarStream.read(header, filled, maxBytes - filled)
            if (n < 0) break
            filled += n
        }
        drainEntry(tarStream)
        return CapturedHeader(header, filled)
    }

    /** §1.146: [captureHeader]'s result — the buffer plus how much of it is real. */
    private class CapturedHeader(val bytes: ByteArray, val length: Int)

    private fun failAndCleanUp(staging: File, importCache: File, reason: String): InstallResult.Failure {
        staging.deleteRecursivelyAllowingReadOnly()
        importCache.deleteRecursively()
        return InstallResult.Failure(reason)
    }

    private fun queryContentLength(uri: Uri): Long? {
        return try {
            application.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * §1.118: the device's supported ABIs, used only to *warn* about an
     * archive whose declared architecture this device cannot execute. Read
     * through a helper so the surrounding scan logic stays pure enough to
     * reason about, and so a missing/empty list degrades to "skip the check"
     * rather than to a spurious warning on every archive.
     */
    private fun deviceAbis(): List<String> =
        runCatching { Build.SUPPORTED_ABIS?.toList().orEmpty() }.getOrDefault(emptyList())

    /** Control-flow exception: a definite, user-meaningful reason to fail the install. */
    private class InstallAbortException(val reason: String) : Exception(reason)

    companion object {
        private const val TAG = "StagedRootFsInstaller"

        /** Where the teed archive bytes land between the scan pass and the native pass. */
        private const val IMPORT_CACHE_DIR = ".import-cache"

        /** §1.147: how deep a ZIP/nested/OCI payload may nest before refusing. */
        private const val MAX_PAYLOAD_DEPTH = 3

        /** §1.147: at most this many archive-shaped ZIP members are considered. */
        private const val MAX_ZIP_CANDIDATES = 16

        /** §1.147: at most this many payload candidates are actually extracted. */
        private const val MAX_PAYLOAD_ATTEMPTS = 4

        /** §1.147: at most this many archive-shaped tar members are remembered. */
        private const val MAX_NESTED_CANDIDATES = 8

        /** §1.147: a nested payload smaller than this is not a rootfs archive. */
        private const val MIN_NESTED_ARCHIVE_BYTES = 1024L

        /**
         * §1.147: upper bound on a single nested payload. A rootfs archive is
         * tens to hundreds of MB; anything beyond this is a bomb or a mistake.
         */
        private const val MAX_NESTED_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024

        /** §1.147: copy buffer for the tee and member extraction. */
        private const val TEE_BUFFER_BYTES = 64 * 1024

        /**
         * §1.147: top-level directories every supported base rootfs has. Used
         * only as a negative signal for nested-payload search — never as a
         * substitute for profile detection.
         */
        private val ROOTFS_TOP_LEVEL_SEGMENTS = setOf("etc", "usr", "var", "bin", "sbin", "lib", "opt")

        // §1.88: headroom added on top of the true storage requirement
        // (extracted + compressed archive) before the install is allowed to
        // proceed. Covers tar's padding, fixup copies, and filesystem
        // metadata that the entry-count-based estimate can't see.
        private const val SLACK_BYTES = 64L * 1024 * 1024

        /** Upper bound for a distro marker file (alpine-release, etc.). */
        private const val MARKER_MAX_BYTES = 16 * 1024

        // Emit progress roughly every 512KB read, not once per read() call —
        // a 200MB rootfs extracted with an event every 8-64KB read would
        // flood a StateFlow/UI with tens of thousands of updates.
        private const val PROGRESS_EMIT_INTERVAL_BYTES = 512L * 1024

        private fun ByteArray.tail(limit: Int = 2000): String {
            val text = toString(Charsets.UTF_8).trim()
            return if (text.length > limit) "…${text.takeLast(limit)}" else text
        }

        /** §1.88: single binary (1024-based) size formatter across the installer. */
        private fun formatBytes(bytes: Long): String = FileSizeFormatter.format(bytes)
    }
}
