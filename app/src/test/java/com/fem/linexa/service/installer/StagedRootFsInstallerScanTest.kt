package com.fem.linexa.service.installer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import com.fem.linexa.core.security.RealArchiveValidator
import com.fem.linexa.service.rootfs.RootFsArchiveFormat
import com.fem.linexa.service.rootfs.RootFsInspector
import com.fem.linexa.service.rootfs.profile.AssetRootFsProfileCatalog
import com.fem.linexa.service.rootfs.profile.ElfArchitecture
import com.fem.linexa.service.rootfs.profile.InstallStep
import com.fem.linexa.service.rootfs.profile.RootFsProfile
import com.fem.linexa.service.rootfs.profile.RootFsProfileCatalog
import com.fem.linexa.service.rootfs.profile.RootFsProfileSource
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.147 — Pass 1 (`analyze`) end to end, over archives built in-test.
 *
 * ## Why this class exists
 *
 * The Fedora/Arch install failure lived in the seam
 * between two correct-looking pieces: the validator judged one entry name, and
 * [StagedRootFsInstaller.scanEntries] turned that judgement into a fatal abort.
 * Both halves had tests; the join had none, so nothing in the suite could tell
 * you what an archive containing that name *does to an install*. That gap was
 * the reason the bug survived, and two predecessors with it.
 *
 * So these tests assert on the two outcomes a user can actually observe:
 *
 *  - a legitimate archive reaches [AnalyzeResult.Success] with the right profile
 *    and install plan, and the teed archive survives for Pass 2;
 *  - a hostile archive reaches [AnalyzeResult.Failure] with the
 *    `Unsafe archive entry '<name>': <reason>` message, and the import cache is
 *    gone.
 *
 * ## What is real here and what is not
 *
 * Real: the validator, the shipped profile YAML (loaded from `src/main/assets`
 * by the same [AssetRootFsProfileCatalog.load] production uses), the signal
 * collector, the resolver, the metadata inspector, [WorkspaceFileManager], and
 * the whole tar/gzip walk over genuine archive bytes.
 *
 * Mocked: only [Application]/[ContentResolver] (no Android runtime) and
 * [BootstrapManager] (Pass 1 requires the native binaries to be *present*, and
 * this test is not about bootstrap). Nothing that decides an entry's fate is
 * mocked — that is the point.
 *
 * The archives are built rather than checked in: the real images are 4-180 MB
 * (see [com.fem.linexa.core.security.RealRootFsEntryNameInventoryTest] for the
 * name-level guard against all five), while the *shapes* that matter here are a
 * handful of entries. Every fixture name below is one verified to exist in a
 * real image.
 */
class StagedRootFsInstallerScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var installer: StagedRootFsInstaller
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var resolver: ContentResolver

    /**
     * The exact entry that aborted the Fedora and Arch installs: one path
     * segment, two literal backslashes (systemd's `\x2d` escaping of `-`), zero
     * separator semantics. Kotlin needs `\\` per backslash.
     */
    private val escapedUnitName =
        "./usr/lib/systemd/system/system-systemd\\x2dmute\\x2dconsole.slice"

    /**
     * [RootFsProfileCatalog] is a plain interface (not a `fun interface`), so
     * the catalog is supplied as an explicit fake rather than a lambda.
     */
    private class FixedCatalog(private val profiles: List<RootFsProfile>) : RootFsProfileCatalog {
        override fun profiles(): List<RootFsProfile> = profiles
    }

    private fun setUp(profiles: RootFsProfileCatalog = assetCatalog()) {
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        resolver = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        Mockito.`when`(app.contentResolver).thenReturn(resolver)

        val bootstrap = Mockito.mock(BootstrapManager::class.java)
        Mockito.`when`(bootstrap.isReady).thenReturn(true)
        Mockito.`when`(bootstrap.diagnoseAll()).thenReturn("<bootstrap diagnostics stubbed>")

        fileManager = WorkspaceFileManager(app)
        installer = StagedRootFsInstaller(
            application = app,
            fileManager = fileManager,
            // The REAL validator — a stub here would test nothing.
            archiveValidator = RealArchiveValidator(),
            bootstrapManager = bootstrap,
            rootFsInspector = RootFsInspector(WorkspaceConfigParser()),
            // Pass 2 collaborators: analyze() never calls them.
            rootfsExtractor = Mockito.mock(RootfsExtractor::class.java),
            l2sLinkRewriter = Mockito.mock(L2sLinkRewriter::class.java),
            // analyze() never reaches the post-extraction audit.
            archiveBoundaryAuditor = Mockito.mock(ArchiveBoundaryAuditor::class.java),
            rootfsFixupService = Mockito.mock(RootfsFixupService::class.java),
            guestRootfsInitializer = Mockito.mock(GuestRootfsInitializer::class.java),
            ociLayerUnwrapper = Mockito.mock(OciLayerUnwrapper::class.java),
            // §1.147: analyze() never runs the pre-activation smoke test.
            runtimeVerifier = Mockito.mock(RootfsRuntimeVerifier::class.java),
            profileCatalog = profiles
        )
    }

    /** Runs `analyze` over [archive]'s bytes as if a content provider served them. */
    private fun analyze(archive: ByteArray, workspaceId: String = "ws"): AnalyzeResult {
        val uri = Mockito.mock(Uri::class.java)
        Mockito.`when`(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(archive) }
        return runBlocking { installer.analyze(workspaceId, uri) }
    }

    private fun importCache(workspaceId: String = "ws") =
        File(fileManager.getWorkspaceBasePath(workspaceId), ".import-cache")

    // ── the regression: a real Fedora shape installs ──────────────────────

    @Test
    fun aFedoraArchiveCarryingTheEscapedUnitNameAnalyzesSuccessfully() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                dir("./etc/"),
                dir("./usr/"),
                dir("./var/"),
                file("./etc/os-release", "NAME=\"Fedora Linux\"\nID=fedora\nVERSION_ID=44\n"),
                elf("./usr/bin/bash"),
                // The entry that used to abort the install, alongside its two
                // real siblings from the same image.
                file(escapedUnitName, "[Unit]\nDescription=Slice\n"),
                file("./usr/lib/systemd/system/system-systemd\\x2dcryptsetup.slice", "[Unit]\n"),
                file("./usr/lib/systemd/system/system-systemd\\x2dveritysetup.slice", "[Unit]\n")
            )
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("analysis must succeed", result)

        assertEquals("fedora", success.profileId)
        assertEquals(RootFsProfileSource.BUILT_IN, success.profileSource)
        assertEquals("Fedora", success.identity.distroName)
        assertEquals("44", success.identity.version)
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, success.identity.arch)
        assertTrue("identity must be detected, not guessed", success.identity.detected)
        assertEquals(RootFsArchiveFormat.TAR_GZ, success.archiveFormat)
        assertEquals(listOf("etc", "usr", "var"), success.requiredDirectories)

        // Fedora's profile declares `archive_kind: oci_image_layout` and so
        // carries UNWRAP_OCI_LAYER in its plan, but this archive is a plain
        // rootfs tarball (as the locally sampled Fedora image also is). The step
        // stays in the plan as documentation; payload resolution is driven by
        // what the file actually contains, never by the constant.
        assertTrue(
            "fedora's plan carries the unwrap step",
            InstallStep.UNWRAP_OCI_LAYER in success.installSteps
        )
        assertEquals("a plain rootfs tarball is its own payload", "source-archive", success.payloadFileName)
        assertEquals("nothing to strip from a bare rootfs", 0, success.stripComponents)
        assertEquals(RootFsArchiveFormat.TAR_GZ, success.payloadFormat)

        // The teed archive must survive Pass 1 — install() extracts from it.
        assertTrue(
            "the teed source archive must remain for install()",
            File(importCache(), "source-archive").isFile
        )
    }

    @Test
    fun anArchArchiveCarryingTheEscapedUnitNameAnalyzesSuccessfully() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                dir("./etc/"),
                dir("./usr/"),
                dir("./var/"),
                // Arch's `etc/os-release` is a symlink to the usr/lib spelling,
                // exactly as the real image ships it, so only the target carries
                // text. Getting this wrong is what made Arch undetectable.
                symlink("./etc/os-release", "../usr/lib/os-release"),
                file("./usr/lib/os-release", "NAME=\"Arch Linux ARM\"\nID=archarm\n"),
                elf("./usr/bin/bash"),
                file(escapedUnitName, "[Unit]\n")
            )
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("analysis must succeed", result)

        assertEquals("arch", success.profileId)
        assertEquals("Arch Linux ARM", success.identity.distroName)
        // Rolling release: no VERSION_ID to report, which is not a detection
        // failure.
        assertEquals("unknown", success.identity.version)
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, success.identity.arch)
        assertTrue(success.identity.detected)
        assertFalse(
            "arch is not an OCI layout",
            InstallStep.UNWRAP_OCI_LAYER in success.installSteps
        )
    }

    // ── the plumbing: a rejection becomes a clean, named failure ──────────

    @Test
    fun aTraversingEntryFailsAnalysisWithTheUnsafeEntryMessage() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                file("./etc/os-release", "ID=debian\n"),
                file("../../etc/passwd", "root:x:0:0\n")
            )
        )

        val failure = result as? AnalyzeResult.Failure
            ?: fail("a traversing entry must fail analysis", result)
        assertEquals(
            "Unsafe archive entry '../../etc/passwd': " +
                "Archive entry contains parent directory traversal",
            failure.reason
        )
    }

    @Test
    fun aBackslashSeparatedEntryFailsAnalysisAsAWindowsPathNotAsAnEscapeCount() {
        setUp()
        val result = analyze(tarGz(dir("./"), file("usr\\bin\\sh", "#!/bin/sh\n")))

        val failure = result as? AnalyzeResult.Failure
            ?: fail("a backslash-separated entry must fail analysis", result)
        // The reason must name the structural observation. The pre-§1.147
        // message ("backslash path separator", from a whole-string count) also
        // fired on legitimate systemd unit names, so the wording is part of the
        // fix, not decoration.
        assertEquals(
            "Unsafe archive entry 'usr\\bin\\sh': " +
                "Archive entry uses backslash instead of '/' as path separator",
            failure.reason
        )
    }

    @Test
    fun aRejectedArchiveLeavesNoImportCacheBehind() {
        setUp()
        val result = analyze(tarGz(dir("./"), file("../../etc/passwd", "x")))

        assertTrue(result is AnalyzeResult.Failure)
        // The teed archive is a full copy of the upload; leaving it after a
        // refusal would keep an archive the app just declared unsafe, and would
        // let InstallRecoveryManager find a cache for an install that never
        // happened.
        assertFalse(
            "the import cache must be deleted after a rejected analysis",
            importCache().exists()
        )
    }

    // ── Phase 3: hardlinks and special files reach the same abort ─────────

    @Test
    fun aFifoEntryFailsAnalysisBeforeExtraction() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                dir("./etc/"),
                file("./etc/os-release", "ID=debian\n"),
                fifo("./run/initctl")
            )
        )

        val failure = result as? AnalyzeResult.Failure
            ?: fail("a FIFO entry must fail analysis", result)
        assertEquals(
            "Unsafe archive entry './run/initctl': " +
                "Special file type (device/FIFO/socket) is not allowed in a rootfs archive",
            failure.reason
        )
        assertFalse("no staging may survive a special-file refusal", importCache().exists())
    }

    @Test
    fun aCharacterDeviceEntryFailsAnalysis() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                file("./etc/os-release", "ID=debian\n"),
                characterDevice("./dev/null")
            )
        )

        val failure = result as? AnalyzeResult.Failure
            ?: fail("a character device entry must fail analysis", result)
        assertTrue(
            "reason must name the special-file policy, got: ${failure.reason}",
            failure.reason.contains("Special file type")
        )
    }

    @Test
    fun aHardlinkEscapingTheRootFailsAnalysis() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                file("./etc/os-release", "ID=debian\n"),
                file("./usr/bin/tool.real", "payload"),
                hardlink("./usr/bin/tool", "../../outside")
            )
        )

        val failure = result as? AnalyzeResult.Failure
            ?: fail("an escaping hardlink must fail analysis", result)
        assertEquals(
            "Unsafe archive entry './usr/bin/tool': " +
                "Hardlink target contains parent directory traversal",
            failure.reason
        )
        assertFalse(importCache().exists())
    }

    @Test
    fun anAbsoluteHardlinkTargetFailsAnalysis() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                file("./etc/os-release", "ID=debian\n"),
                hardlink("./usr/bin/tool", "/etc/passwd")
            )
        )

        val failure = result as? AnalyzeResult.Failure
            ?: fail("an absolute hardlink target must fail analysis", result)
        assertTrue(
            "reason must name the absolute hardlink target, got: ${failure.reason}",
            failure.reason.contains("Hardlink target has absolute path")
        )
    }

    @Test
    fun anInRootHardlinkStillAnalyzesSuccessfully() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                dir("./etc/"),
                dir("./usr/"),
                dir("./var/"),
                file("./etc/os-release", "ID=debian\nVERSION_ID=12\n"),
                file("./usr/bin/tool.real", "payload"),
                hardlink("./usr/bin/tool", "usr/bin/tool.real"),
                elf("./usr/bin/bash")
            ),
            workspaceId = "ws-hardlink"
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("an in-root hardlink must not fail analysis", result)
        assertEquals("debian", success.profileId)
    }

    // ── the same archive, if the validator had not been fixed ─────────────

    @Test
    fun theEscapedUnitNameIsTheOnlyThingThatChangedAboutTheFedoraArchive() {
        // Guards against a fix that "works" because the fixture is too weak: the
        // same archive minus the escaped unit name must analyze identically. If
        // this passes while the test above fails, the regression is specifically
        // the backslash rule and not the Fedora shape.
        setUp()
        val withoutTheUnit = analyze(
            tarGz(
                dir("./"),
                dir("./etc/"),
                dir("./usr/"),
                dir("./var/"),
                file("./etc/os-release", "ID=fedora\nVERSION_ID=44\n"),
                elf("./usr/bin/bash")
            ),
            workspaceId = "ws-without"
        )
        val success = withoutTheUnit as? AnalyzeResult.Success
            ?: fail("the control archive must succeed", withoutTheUnit)
        assertEquals("fedora", success.profileId)
        assertEquals("44", success.identity.version)
    }

    // ── an undetectable archive still gets a real plan ────────────────────

    @Test
    fun anUnrecognizedArchiveResolvesToTheGenericPlanRatherThanFailing() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./"),
                dir("./etc/"),
                dir("./usr/"),
                file("./usr/bin/sh", "#!/bin/sh\n")
            ),
            workspaceId = "ws-generic"
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("an unrecognized but safe archive must still analyze", result)
        assertEquals("generic", success.profileId)
        assertEquals(RootFsProfileSource.GENERIC, success.profileSource)
        assertFalse("nothing matched, so nothing is claimed as detected", success.identity.detected)
        assertTrue("the generic plan is never empty", success.installSteps.isNotEmpty())
    }

    @Test
    fun aMissingProfileCatalogDegradesToGenericInsteadOfFailingTheImport() {
        // The catalog is injected precisely so a missing/unreadable asset cannot
        // crash an import (see StagedRootFsInstaller's kdoc). An empty catalog is
        // that case in its most extreme form.
        setUp(profiles = FixedCatalog(emptyList()))
        val result = analyze(
            tarGz(
                dir("./"),
                dir("./etc/"),
                dir("./usr/"),
                file("./etc/os-release", "ID=fedora\nVERSION_ID=44\n"),
                elf("./usr/bin/bash")
            ),
            workspaceId = "ws-nocatalog"
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("a missing catalog must not fail the import", result)
        assertEquals("generic", success.profileId)
    }

    // ── §1.147: wrappers, ZIP containers, nested payloads ────────────────

    @Test
    fun aRootfsWrappedInOneDirectoryResolvesAStripCountInsteadOfFailing() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./rootfs/"),
                dir("./rootfs/etc/"),
                dir("./rootfs/usr/"),
                dir("./rootfs/var/"),
                file("./rootfs/etc/os-release", "ID=debian\nVERSION_ID=12\n"),
                elf("./rootfs/usr/bin/bash")
            ),
            workspaceId = "ws-wrapped"
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("a wrapped rootfs must analyze", result)
        assertEquals("debian", success.profileId)
        assertEquals("one wrapper directory must be stripped", 1, success.stripComponents)
        assertEquals("the wrapped tar is its own payload", "source-archive", success.payloadFileName)
    }

    @Test
    fun anArchiveInsideASingleWrapperDirectoryIsFoundByContent() {
        setUp()
        val inner = tarGz(
            dir("./etc/"),
            dir("./usr/"),
            dir("./var/"),
            file("./etc/os-release", "NAME=\"Alpine Linux\"\nID=alpine\nVERSION_ID=3.24.1\n"),
            file("./etc/alpine-release", "3.24.1\n"),
            // A real rootfs is never tiny; keep the fixture above the nested
            // candidate size floor.
            file("./usr/share/doc/noise.bin", noise(8 * 1024)),
            elf("./usr/bin/sh")
        )
        val result = analyze(
            tar(
                dir("./image/"),
                // The name is a hint only; selection is by the member's bytes.
                Member("./image/whatever.bin", bytes = inner)
            ),
            workspaceId = "ws-nested"
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("a nested rootfs must analyze", result)
        assertEquals("alpine", success.profileId)
        assertTrue(
            "the extracted payload must be named, got '${success.payloadFileName}'",
            success.payloadFileName.startsWith("payload-nested-")
        )
        assertEquals(RootFsArchiveFormat.TAR_GZ, success.payloadFormat)
        assertTrue(
            "the nested payload is what install() must hand to tar",
            File(importCache("ws-nested"), success.payloadFileName).isFile
        )
    }

    @Test
    fun aZipContainerIsUnwrappedByContentNotByExtension() {
        setUp()
        val inner = tarGz(
            dir("./etc/"),
            dir("./usr/"),
            dir("./var/"),
            file("./etc/os-release", "NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\nVERSION_ID=\"22.04\"\n"),
            file("./usr/share/doc/noise.bin", noise(32 * 1024)),
            elf("./usr/bin/bash")
        )
        val result = analyze(
            zip(
                "readme.txt" to "download the archive inside".toByteArray(),
                // Deliberately not named .tar.gz or .tgz: selection must come
                // from the member's first bytes, never its name.
                "images/rootfs-payload.bin" to inner
            ),
            workspaceId = "ws-zip"
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("a ZIP-wrapped rootfs must analyze", result)
        assertEquals("ubuntu", success.profileId)
        assertEquals("Ubuntu", success.identity.distroName)
        assertTrue(
            "the ZIP member must be materialized as a payload, got '${success.payloadFileName}'",
            success.payloadFileName.startsWith("payload-zip-")
        )
        assertEquals(RootFsArchiveFormat.TAR_GZ, success.payloadFormat)
        assertEquals("the member archive itself is not wrapped", 0, success.stripComponents)
    }

    @Test
    fun aDeclarationInsideTheWrapperIsTheArchivesOwnDeclaration() {
        setUp()
        val declaration = """
            linexa:
              schema_version: 2
              distro:
                id: fedora
                name: Fedora Linux
              architecture: arm64
        """.trimIndent()
        val result = analyze(
            tarGz(
                dir("./rootfs/"),
                dir("./rootfs/etc/"),
                dir("./rootfs/usr/"),
                file("./rootfs/etc/os-release", "ID=fedora\nVERSION_ID=44\n"),
                file("./rootfs/.linexa.yaml", declaration),
                elf("./rootfs/usr/bin/bash")
            ),
            workspaceId = "ws-wrapped-meta"
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("a wrapped archive with a declaration must analyze", result)
        assertEquals(1, success.stripComponents)
        assertTrue("the declaration must be persisted", success.capturedConfig != null)
        assertEquals("fedora", success.profileId)
    }

    @Test
    fun aGuestConfigYamlIsNotMistakenForTheArchiveMetadata() {
        setUp()
        val result = analyze(
            tarGz(
                dir("./rootfs/"),
                dir("./rootfs/etc/"),
                dir("./rootfs/usr/"),
                file("./rootfs/etc/os-release", "ID=debian\n"),
                // A guest-owned file with a metadata name one level too deep.
                // It is not a declaration about the image and must not be
                // parsed (this text is not even YAML).
                file("./rootfs/etc/config.yaml", "not: [valid"),
                elf("./rootfs/usr/bin/bash")
            ),
            workspaceId = "ws-guest-config"
        )

        val success = result as? AnalyzeResult.Success
            ?: fail("a malformed guest file must not fail the import", result)
        assertNull("no declaration was captured", success.capturedConfig)
        assertEquals("debian", success.profileId)
        assertEquals(1, success.stripComponents)
    }

    @Test
    fun aTruncatedArchiveIsRejectedInsteadOfInstalledPartially() {
        setUp()
        val full = tarGz(
            dir("./etc/"),
            dir("./usr/"),
            file("./etc/os-release", "ID=debian\n"),
            file("./usr/share/doc/noise.bin", noise(64 * 1024))
        )
        // Cut through the deflate stream / gzip trailer, the shape of an
        // interrupted download.
        val truncated = full.copyOf(full.size / 2)
        val result = analyze(truncated, workspaceId = "ws-truncated")

        assertTrue("a truncated archive must fail analysis", result is AnalyzeResult.Failure)
        assertFalse("its import cache must be discarded", importCache("ws-truncated").exists())
    }

    @Test
    fun anUnsupportedContainerIsRejectedWithAnActionableReason() {
        setUp()
        val result = analyze("this is not an archive".toByteArray(), workspaceId = "ws-unknown")

        val failure = result as? AnalyzeResult.Failure
            ?: fail("an unknown container must fail analysis", result)
        assertTrue(
            "the reason must say what was expected, got: ${failure.reason}",
            failure.reason.contains("Cannot detect archive format")
        )
    }

    // ── fixture plumbing ─────────────────────────────────────────────────

    private class Member(
        val name: String,
        val bytes: ByteArray? = null,
        val isDirectory: Boolean = false,
        val linkTarget: String? = null,
        /** Tar typeflag for hardlinks/special files; null keeps the legacy shape. */
        val linkFlag: Byte? = null
    )

    private fun dir(name: String) = Member(name, isDirectory = true)

    private fun file(name: String, text: String) = Member(name, text.toByteArray())

    private fun file(name: String, bytes: ByteArray) = Member(name, bytes)

    private fun symlink(name: String, target: String) = Member(name, linkTarget = target)

    private fun hardlink(name: String, target: String) =
        Member(name, linkTarget = target, linkFlag = TarArchiveEntry.LF_LINK)

    private fun fifo(name: String) = Member(name, linkFlag = TarArchiveEntry.LF_FIFO)

    private fun characterDevice(name: String) =
        Member(name, linkFlag = TarArchiveEntry.LF_CHR)

    /**
     * A 20-byte little-endian ELF64 header declaring `EM_AARCH64` (183) — what
     * the architecture probe reads, and the only architecture signal Arch
     * supplies at all.
     */
    private fun elf(name: String): Member {
        val header = ByteArray(ElfArchitecture.HEADER_BYTES)
        header[0] = 0x7F
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2 // ELFCLASS64
        header[5] = 1 // little-endian
        header[18] = 183.toByte()
        return Member(name, header)
    }

    /** Builds a real (uncompressed) `.tar`, the container for nested payloads. */
    private fun tar(vararg members: Member): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { archive ->
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            members.forEach { member ->
                archive.putArchiveEntry(member.toEntry())
                member.bytes?.let { archive.write(it) }
                archive.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    /** Builds a real ZIP container, the shape a browser download produces. */
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Deterministic pseudo-random bytes: large enough to defeat size heuristics,
     * stable enough that a failure is reproducible.
     */
    private fun noise(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var state = 0x5EED_1234
        for (i in bytes.indices) {
            state = state * 1_103_515_245 + 12_345
            bytes[i] = (state ushr 16).toByte()
        }
        return bytes
    }

    /** Builds a real `.tar.gz`, so the scan pass sniffs and walks genuine bytes. */
    private fun tarGz(vararg members: Member): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                members.forEach { member ->
                    tar.putArchiveEntry(member.toEntry())
                    val payload = member.bytes ?: ByteArray(0)
                    if (payload.isNotEmpty()) tar.write(payload)
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun Member.toEntry(): TarArchiveEntry {
        val entry = when {
            linkFlag != null -> TarArchiveEntry(name, linkFlag)
            linkTarget != null -> TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
            isDirectory -> TarArchiveEntry(name, TarArchiveEntry.LF_DIR)
            else -> TarArchiveEntry(name)
        }
        linkTarget?.let { entry.linkName = it }
        entry.size = (bytes ?: ByteArray(0)).size.toLong()
        return entry
    }

    private fun assetCatalog(): RootFsProfileCatalog {
        val dir = assetDir()
        val profiles: List<RootFsProfile> = AssetRootFsProfileCatalog.load { fileName ->
            File(dir, fileName).takeIf { it.isFile }?.readBytes()
        }
        check(profiles.size == AssetRootFsProfileCatalog.BUILT_IN_FILES.size) {
            "every shipped profile must load: got ${profiles.map { it.id }}"
        }
        return FixedCatalog(profiles)
    }

    private fun fail(message: String, result: AnalyzeResult): Nothing {
        val detail = (result as? AnalyzeResult.Failure)?.reason ?: result.toString()
        throw AssertionError("$message, but was: $detail")
    }

    private companion object {
        fun assetDir(): File {
            val relative = "src/main/assets/${AssetRootFsProfileCatalog.ASSET_DIR}"
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                File(dir, relative).takeIf { it.isDirectory }?.let { return it }
                File(dir, "app/$relative").takeIf { it.isDirectory }?.let { return it }
                dir = dir.parentFile
            }
            error("could not locate $relative")
        }
    }
}
