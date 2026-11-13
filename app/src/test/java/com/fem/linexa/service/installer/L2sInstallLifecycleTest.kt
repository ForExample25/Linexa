package com.fem.linexa.service.installer

import android.app.Application
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.security.RealArchiveValidator
import com.fem.linexa.service.rootfs.RootFsInspector
import com.fem.linexa.service.rootfs.profile.InstallStep
import com.fem.linexa.service.rootfs.profile.RootFsProfile
import com.fem.linexa.service.rootfs.profile.RootFsProfileCatalog
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.runtime.container.GuestIdentity
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import com.fem.linexa.test.support.L2sExtraction
import com.fem.linexa.test.support.L2sExtraction.Member
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.168 — the defect at the level a user experiences it: one
 * [StagedRootFsInstaller.install] call over a hardlink-bearing archive must
 * produce a live rootfs whose emulated hardlinks resolve.
 *
 * ## Why this exists alongside [L2sLinkRewriterTest]
 *
 * [L2sLinkRewriterTest] proves the pass is correct. It cannot prove the pass is
 * *wired* — and an unwired repair is indistinguishable from no repair, which is
 * exactly the failure mode this project has hit repeatedly (§1.19). So this test
 * drives the real `install()`: the real ordering (extract → rewrite → validate →
 * steps → activate), the real [WorkspaceFileManager] rename, the real
 * [L2sLinkRewriter], and the real fail-closed handling in `install()` itself.
 *
 * ## What stands in for the native extraction
 *
 * Only [RootfsExtractor] is stubbed, and its stub *writes the staging tree* the
 * way `proot --link2symlink tar` does ([L2sExtraction]) instead of returning a
 * bare success — a stub that wrote nothing would let a broken pipeline pass.
 * Everything downstream of it is production code.
 */
class L2sInstallLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var extractor: RootfsExtractor
    private lateinit var installer: StagedRootFsInstaller
    private lateinit var runtimeVerifier: RootfsRuntimeVerifier
    private lateinit var application: Application
    private lateinit var bootstrap: BootstrapManager
    private lateinit var guestRootfsInitializer: GuestRootfsInitializer

    private val workspaceId = "l2s-install"

    /** The archive shape: Debian's real `gunzip`/`uncompress` hardlink family. */
    private val members = arrayOf(
        Member.Dir("./etc/"),
        Member.Dir("./usr/"),
        Member.Dir("./usr/bin/"),
        Member.Regular("./usr/bin/sh", "#!/bin/sh\n"),
        Member.Regular("./etc/os-release", "ID=debian\n"),
        Member.Regular("./usr/bin/gunzip", "gunzip-payload"),
        Member.Hardlink("./usr/bin/uncompress", "./usr/bin/gunzip")
    )

    private class FixedCatalog(private val profiles: List<RootFsProfile>) : RootFsProfileCatalog {
        override fun profiles(): List<RootFsProfile> = profiles
    }

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        application = Mockito.mock(Application::class.java)
        Mockito.`when`(application.filesDir).thenReturn(filesDir)
        Mockito.`when`(application.cacheDir).thenReturn(tmp.newFolder("cache"))

        bootstrap = Mockito.mock(BootstrapManager::class.java)
        Mockito.`when`(bootstrap.isReady).thenReturn(true)
        Mockito.`when`(bootstrap.diagnoseAll()).thenReturn("<stubbed>")

        // install() resolves the guest account before activation. A default
        // answer is used for the same reason as the extractor stub below:
        // Mockito's null default cannot satisfy the non-null GuestIdentity
        // return type.
        guestRootfsInitializer = Mockito.mock(GuestRootfsInitializer::class.java) { invocation ->
            if (invocation.method.name == "ensureGuestUser") GuestIdentity.root()
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }

        fileManager = WorkspaceFileManager(application)
        // Default answer rather than per-call stubbing — see [stubExtraction].
        // `extract` writes the staging tree and returns null (its success value);
        // every other member of the mock keeps Mockito's usual default.
        extractor = Mockito.mock(RootfsExtractor::class.java) { invocation ->
            if (invocation.method.name == "extract") {
                val staging = invocation.getArgument<File>(1)
                staging.mkdirs()
                extractionWriter(staging)
                null
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        runtimeVerifier = Mockito.mock(RootfsRuntimeVerifier::class.java)
        installer = buildInstaller(runtimeVerifier)
    }

    private fun buildInstaller(verifier: RootfsRuntimeVerifier): StagedRootFsInstaller {
        return StagedRootFsInstaller(
            application = application,
            fileManager = fileManager,
            archiveValidator = RealArchiveValidator(),
            bootstrapManager = bootstrap,
            rootFsInspector = RootFsInspector(WorkspaceConfigParser()),
            rootfsExtractor = extractor,
            // The class under test's collaborator — real, not mocked.
            l2sLinkRewriter = L2sLinkRewriter(DiagnosticLogManager(application)),
            // RFS-03 runs for real too: extraction-written links must pass the
            // post-extraction boundary audit before fixup/activation.
            archiveBoundaryAuditor = ArchiveBoundaryAuditor(DiagnosticLogManager(application)),
            // Pass 3 is not this test's subject; both are no-ops that report success.
            rootfsFixupService = Mockito.mock(RootfsFixupService::class.java),
            guestRootfsInitializer = guestRootfsInitializer,
            ociLayerUnwrapper = Mockito.mock(OciLayerUnwrapper::class.java),
            // §1.147: extraction is the subject here; the smoke test's own
            // argv/behaviour is covered by RootfsRuntimeVerifierTest.
            runtimeVerifier = verifier,
            profileCatalog = FixedCatalog(emptyList())
        )
    }

    /**
     * A verifier whose suspend `verify` answers with [reason] — the same
     * default-answer technique [stubExtraction] uses, for the same reason:
     * Mockito matchers and suspend continuations do not mix.
     */
    private fun failingVerifier(reason: String): RootfsRuntimeVerifier =
        Mockito.mock(RootfsRuntimeVerifier::class.java) { invocation ->
            if (invocation.method.name == "verify") reason
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }

    /**
     * Stubs [RootfsExtractor.extract] so that instead of merely reporting success
     * it *populates* the staging directory it was handed — [write] receives that
     * directory. A stub that wrote nothing would let a broken pipeline pass.
     *
     * Installed as the mock's **default answer** rather than through
     * `when(...)`/argument matchers: `extract` is a suspend function, so its
     * compiled signature carries an extra `Continuation` parameter that is passed
     * as a real value while every other argument would be a matcher — a mix
     * Mockito rejects (`InvalidUseOfMatchersException`). Dispatching on the method
     * name sidesteps matchers entirely and keeps the stub honest about which call
     * it is answering.
     */
    private fun stubExtraction(write: (File) -> Unit) {
        extractionWriter = write
    }

    /** What the stubbed extraction writes into staging; set per test. */
    private var extractionWriter: (File) -> Unit = {}

    /** The plain success case: the artifact shape Pass 2 leaves behind. */
    private fun stubExtractionWriting(vararg archiveMembers: Member) {
        val bytes = L2sExtraction.archive(*archiveMembers)
        stubExtraction { staging -> L2sExtraction.extractWithLink2Symlink(bytes, staging) }
    }

    /** Writes the `source-archive` install() requires in the import cache. */
    private fun seedImportCache() {
        val importCache = File(fileManager.getWorkspaceBasePath(workspaceId), ".import-cache")
        importCache.mkdirs()
        File(importCache, "source-archive").writeText("not read — extraction is stubbed")
    }

    /** An analysis matching the fixture: `etc`/`usr` present, `usr/bin/sh` as the shell. */
    private fun analysis() = AnalyzeResult.Success(
        sha256 = "0".repeat(64),
        requiredStorageBytes = 4096,
        archiveBytes = 1024,
        totalEntries = members.size.toLong(),
        identity = RootFsProbe.Identity(
            distroId = RootFsProbe.DistroId.DEBIAN,
            distroName = "Debian",
            version = "12",
            arch = RootFsProbe.RootFsArch.ARM64_V8A,
            detected = true
        ),
        requiredFreeBytes = 8192,
        capturedConfig = null,
        requiredDirectories = listOf("etc", "usr"),
        shellCandidates = listOf("usr/bin/sh"),
        installSteps = listOf(InstallStep.ENSURE_TMP)
    )

    private fun install(): InstallResult = runBlocking { installer.install(workspaceId, analysis()) }

    private fun target(root: File, relative: String): String =
        Files.readSymbolicLink(File(root, relative).toPath()).toString()

    @Test
    fun installLeavesEveryEmulatedHardlinkResolvableInTheLiveRootfs() {
        seedImportCache()
        stubExtractionWriting(*members)

        val result = install()

        assertTrue("install must succeed, got: $result", result is InstallResult.Success)

        val rootfs = fileManager.getRootfsPath(workspaceId)
        assertFalse(
            "staging must not survive a successful install",
            fileManager.getStagingRootfsPath(workspaceId).exists()
        )

        for (member in listOf("usr/bin/gunzip", "usr/bin/uncompress")) {
            val file = File(rootfs, member)
            assertTrue("$member must be a symlink", Files.isSymbolicLink(file.toPath()))
            assertTrue(
                "$member must resolve in the live rootfs, but points at '${target(rootfs, member)}'",
                file.exists()
            )
            assertFalse(
                "$member must not reference the staging path",
                target(rootfs, member).contains(".staging-rootfs")
            )
            assertFalse(
                "$member must not carry an absolute target",
                Paths.get(target(rootfs, member)).isAbsolute
            )
            assertEquals("$member must expose the payload", "gunzip-payload", file.readText())
        }

        // The import cache is discarded on success, exactly as before §1.168.
        assertFalse(
            "the import cache must be cleaned up",
            File(fileManager.getWorkspaceBasePath(workspaceId), ".import-cache").exists()
        )
    }

    /**
     * Fail-closed at the install level: when the rewrite cannot complete, the
     * install must fail **and** discard staging, leaving nothing to promote —
     * requirement 11's "rather than silently producing a partially-invalid
     * rootfs".
     *
     * The unrepairable artifact is one whose target lies outside staging, the
     * same case [L2sLinkRewriterTest] covers at the unit level; here what is
     * asserted is what `install()` does about it.
     */
    @Test
    fun anUnrepairableArtifactFailsTheInstallAndLeavesNoRootfs() {
        seedImportCache()
        // A target left over from a different staging tree: artifact-shaped by
        // name (the `.l2s.` prefix is what the pass keys on), but outside the
        // tree being installed and so not expressible as a relative target.
        val strayDir = tmp.newFolder("other-staging", "usr", "bin")
        val strayTarget = File(strayDir, "${L2sLinkRewriter.L2S_PREFIX}stray0001").apply { writeText("stray") }
        stubExtraction { staging ->
            L2sExtraction.extractWithLink2Symlink(L2sExtraction.archive(*members), staging)
            // An artifact-shaped link pointing out of the tree — unexpressible as
            // a relative target, so the pass must refuse the whole rootfs.
            Files.createSymbolicLink(
                File(staging, "usr/bin/stray").toPath(),
                strayTarget.absoluteFile.toPath()
            )
        }

        val result = install()

        val failure = result as? InstallResult.Failure
            ?: throw AssertionError("install must fail on an unrepairable rootfs, got: $result")
        assertTrue(
            "the failure must explain the unresolvable link, got: ${failure.reason}",
            failure.reason.contains("points outside")
        )
        assertFalse(
            "staging must be discarded on failure",
            fileManager.getStagingRootfsPath(workspaceId).exists()
        )
        assertFalse(
            "no live rootfs may be created from a rejected tree",
            fileManager.getRootfsPath(workspaceId).exists()
        )
    }

    /**
     * §1.147 — the smoke test's whole reason for existing: a staged tree that
     * does not boot must never replace a rootfs that does.
     *
     * This drives the real `install()` through extraction, link repair, the
     * boundary audit, and the setup plan, and fails only at the last gate. The
     * assertion is on bytes the user already had, not on a call count: the old
     * rootfs's sentinel must still be readable under the same path.
     */
    @Test
    fun aFailedRuntimeSmokeTestLeavesThePreviousRootfsUntouched() {
        val liveRootfs = fileManager.getRootfsPath(workspaceId)
        val sentinel = File(liveRootfs, "etc/live-sentinel").apply {
            parentFile.mkdirs()
            writeText("previous working rootfs")
        }
        seedImportCache()
        stubExtractionWriting(*members)

        installer = buildInstaller(
            failingVerifier("Rootfs failed its pre-activation runtime smoke test (synthetic)")
        )

        val result = install()

        val failure = result as? InstallResult.Failure
            ?: throw AssertionError("a failed smoke test must fail the install, got: $result")
        assertTrue(
            "the user must see the smoke test's reason, got: ${failure.reason}",
            failure.reason.contains("synthetic")
        )
        assertTrue("the previous live rootfs must still exist", liveRootfs.isDirectory)
        assertTrue(
            "the previous rootfs content must be untouched",
            sentinel.isFile && sentinel.readText() == "previous working rootfs"
        )
        assertFalse(
            "the failed staging tree must be discarded",
            fileManager.getStagingRootfsPath(workspaceId).exists()
        )
        assertFalse(
            "the import cache must be discarded",
            File(fileManager.getWorkspaceBasePath(workspaceId), ".import-cache").exists()
        )
    }

    /**
     * Ordering guard: the rewrite must run **before** activation, not after.
     *
     * Asserted by consequence rather than by spying: after a successful install
     * the staging directory no longer exists, so if any target still named it the
     * link could not resolve — and the previous test's `exists()` assertions
     * would fail. This test pins the *complementary* fact that makes that
     * argument airtight: at no point does the live rootfs contain a link into
     * staging, including for the sentinel and data-file entries the archive never
     * named.
     */
    @Test
    fun noLinkInTheLiveRootfsReferencesTheStagingDirectory() {
        seedImportCache()
        stubExtractionWriting(*members)

        assertTrue(install() is InstallResult.Success)

        val rootfs = fileManager.getRootfsPath(workspaceId)
        val links = rootfs.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { Files.isSymbolicLink(it.toPath()) }
            .map { it to Files.readSymbolicLink(it.toPath()).toString() }
            .toList()

        // Guard against a vacuous pass: an extraction that wrote no links at all
        // would satisfy the emptiness assertion below for the wrong reason.
        assertTrue("the fixture must have produced links to inspect", links.isNotEmpty())

        val offenders = links
            .filter { (_, target) -> target.contains(".staging-rootfs") || Paths.get(target).isAbsolute }
            .map { (link, target) -> "${link.name} -> $target" }

        assertTrue("no live link may point into staging or be absolute: $offenders", offenders.isEmpty())
    }

    /**
     * Structural guard for the *next* extraction path, not this one.
     *
     * The behavioural tests above prove the wiring is right for the single
     * `extract()` call site that exists today. They cannot prove it for one
     * added later — and an extraction that skips the rewrite reproduces the whole
     * defect while every test here still passes. So the source is asserted to
     * contain exactly one extraction call, one rewrite call, and one activation,
     * in that order. Same idiom as [com.fem.linexa.core.logging.DebugInfoWiringSourceTest].
     */
    @Test
    fun theInstallerRewritesBetweenExtractionAndActivation() {
        val source = installerSource()

        val extractAt = source.indexOf("rootfsExtractor.extract(")
        val rewriteAt = source.indexOf("l2sLinkRewriter.rewrite(")
        val activateAt = source.indexOf("fileManager.activateStagedRootfs(")

        assertTrue("the installer must call rootfsExtractor.extract()", extractAt >= 0)
        assertTrue("the installer must call l2sLinkRewriter.rewrite()", rewriteAt >= 0)
        assertTrue("the installer must call activateStagedRootfs()", activateAt >= 0)
        assertTrue("the rewrite must follow extraction", rewriteAt > extractAt)
        assertTrue("the rewrite must precede activation", rewriteAt < activateAt)

        assertEquals(
            "a second extraction path must be given its own rewrite — see this test's kdoc",
            1,
            occurrences(source, "rootfsExtractor.extract(")
        )
        assertEquals(
            "a second activation must be preceded by a rewrite — see this test's kdoc",
            1,
            occurrences(source, "fileManager.activateStagedRootfs(")
        )
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    /** [StagedRootFsInstaller]'s source, located by walking up from the test's CWD. */
    private fun installerSource(): String {
        val relative = "src/main/java/com/fem/linexa/service/installer/StagedRootFsInstaller.kt"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
            File(dir, "app/$relative").takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("could not locate $relative")
    }
}
