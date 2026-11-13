package com.fem.linexa.service.runtime.bootstrap

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.test.support.TerminfoTestFixture
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

/**
 * §1.71 — Roadmap Item 5: JVM tests for `BootstrapManager.setup()`'s
 * bootstrap-manifest validation against a REAL [BootstrapManager] (mock
 * [Application] only, like the other bootstrap tests), driven through the
 * test seam ([BootstrapManifestTestSeam]) since `Application.getAssets()`
 * is final and cannot be stubbed.
 *
 * Contract under test:
 *  - a manifest that matches the live `nativeLibraryDir` layout passes
 *    silently (no `lastError`, manifest section shows OK),
 *  - no manifest available → validation skipped, existing behavior unchanged,
 *  - corrupted / missing binaries → loud failure merged into `lastError`
 *    (surfaced by the existing `diagnoseAll()` path), while `isReady`
 *    (binary ACCESS validation) is untouched — the manifest check is a
 *    layered addition, not a new gate,
 *  - the other ABI's entry never false-positives (a correct file always
 *    matches its own ABI's recorded hash),
 *  - the paired-artifact rule (loader from the same build as proot) fires on
 *    a buildId mismatch,
 *  - the idempotent symlink self-heal still runs with a valid manifest
 *    present (Item 5's must-not-break).
 */
class BootstrapManifestValidationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nativeLibDir: File
    private lateinit var filesDir: File
    private lateinit var manager: BootstrapManager

    private val managedFiles = listOf(
        "lib__bin__busybox__.so", "lib__bin__tar__.so", "lib__bin__zstd__.so",
        "lib__bin__tmux__.so", "lib__bin__loader__.so", "libproot.so",
        "lib__lib__libbusybox.so.1.38.0__.so", "lib__lib__libacl.so__.so",
        "lib__lib__libandroid-glob.so__.so", "lib__lib__libandroid-selinux.so__.so",
        "lib__lib__libandroid-support.so__.so", "lib__lib__libiconv.so__.so",
        "lib__lib__libpcre2-8.so__.so", "lib__lib__libzstd.so.1__.so",
        "lib__lib__libz.so.1__.so", "lib__lib__liblzma.so.5__.so",
        "lib__lib__libncursesw.so.6__.so", "lib__lib__libevent_core-2.1.so__.so"
    )

    @Before
    fun setUp() {
        BootstrapManifestTestSeam.overrideJson = null
        // §1.75: setup() step 7 installs host terminfo and reports a gap via
        // lastError when no assets are available; seed the seam (mirror of
        // the manifest seam) so a clean environment really is clean and this
        // suite's `lastError == null` contract stays about the manifest.
        BootstrapManager.TerminfoTestSeam.overrideBytes = TerminfoTestFixture.validEntries
        nativeLibDir = tmp.newFolder("native-lib")
        filesDir = tmp.newFolder("files")
        tmp.newFolder("cache")
        for (name in managedFiles) {
            File(nativeLibDir, name).writeText("inert bootstrap stand-in for JVM tests: $name\n")
        }
        manager = BootstrapManager(app())
    }

    @After
    fun tearDown() {
        BootstrapManifestTestSeam.overrideJson = null
        BootstrapManager.TerminfoTestSeam.overrideBytes = null
    }

    private fun app(): Application {
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = nativeLibDir.absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(File(filesDir, "cache"))
        Mockito.`when`(app.getExternalFilesDir(any())).thenReturn(File(filesDir, "shared"))
        return app
    }

    /** An artifact entry whose sha256 is taken from the file actually on disk. */
    private fun artifactFor(
        file: String,
        abi: String = "arm64-v8a",
        version: String = "test-1.0",
        buildId: String = "build-A"
    ): BootstrapManifest.Artifact {
        val actual = BootstrapManifestSupport.sha256Hex(File(nativeLibDir, file))
            ?: error("could not hash $file")
        return BootstrapManifest.Artifact(
            file = file, abi = abi, version = version, buildId = buildId, sha256 = actual
        )
    }

    private fun allArtifacts(buildId: String = "build-A", abi: String = "arm64-v8a") =
        managedFiles.map { artifactFor(it, abi = abi, buildId = buildId) }

    private val defaultPair = BootstrapManifest.PairedArtifacts(
        label = "loader must come from the same build as proot",
        members = listOf("libproot.so", "lib__bin__loader__.so")
    )

    private fun manifestJson(
        artifacts: List<BootstrapManifest.Artifact>,
        pairs: List<BootstrapManifest.PairedArtifacts> = listOf(defaultPair),
        schemaVersion: Int = 1
    ): String {
        return Json { prettyPrint = false }.encodeToString(
            BootstrapManifest(schemaVersion = schemaVersion, artifacts = artifacts, pairs = pairs)
        )
    }

    // ---- clean paths ----

    @Test
    fun matchingManifest_passesSilently() {
        BootstrapManifestTestSeam.overrideJson = manifestJson(allArtifacts())
        manager.setup()
        assertNull("valid manifest must not set lastError", manager.lastError)
        assertTrue(manager.isReady)
        val diag = manager.diagnoseManifest()
        assertTrue("expected OK state in diagnostics, got: $diag", diag.contains("-> OK"))
        assertTrue("expected no MISMATCH in diagnostics, got: $diag", !diag.contains("MISMATCH"))
    }

    @Test
    fun noManifest_validationSkipped_existingBehaviorUnchanged() {
        // No asset, no override -> validation must be a silent no-op and the
        // pre-§1.71 setup() behavior must be exactly preserved.
        manager.setup()
        assertNull(manager.lastError)
        assertTrue(manager.isReady)
        assertTrue(manager.diagnoseManifest().contains("<unavailable"))
    }

    // ---- loud failure paths ----

    @Test
    fun corruptedBinary_failsLoudly_butIsReadyUntouched() {
        BootstrapManifestTestSeam.overrideJson = manifestJson(allArtifacts())
        File(nativeLibDir, "libproot.so").writeText("corrupted bytes, not the recorded build\n")
        manager.setup()
        val error = manager.lastError
        assertNotNull("corrupted binary must fail loudly", error)
        assertTrue("expected sha256 mismatch in: $error", error!!.contains("sha256 mismatch"))
        assertTrue("expected the file named in: $error", error.contains("libproot.so"))
        // Layered check, not a new gate: the file is still a regular file, so
        // binary-ACCESS validation (isReady) must be untouched.
        assertTrue("manifest mismatch must not flip isReady", manager.isReady)
        assertTrue(manager.diagnoseManifest().contains("MISMATCH"))
    }

    @Test
    fun missingBinary_failsLoudly() {
        BootstrapManifestTestSeam.overrideJson = manifestJson(allArtifacts())
        assertTrue(File(nativeLibDir, "lib__bin__tar__.so").delete())
        manager.setup()
        val error = manager.lastError
        assertNotNull(error)
        assertTrue("expected missing-from-layout in: $error", error!!.contains("missing from nativeLibraryDir"))
        assertTrue(error.contains("lib__bin__tar__.so"))
    }

    @Test
    fun unparseableManifest_failsLoudly() {
        BootstrapManifestTestSeam.overrideJson = "{ this is not valid json"
        manager.setup()
        val error = manager.lastError
        assertNotNull(error)
        assertTrue("expected unparseable in: $error", error!!.contains("unparseable"))
    }

    @Test
    fun unsupportedSchemaVersion_failsLoudly() {
        BootstrapManifestTestSeam.overrideJson = manifestJson(allArtifacts(), schemaVersion = 2)
        manager.setup()
        val error = manager.lastError
        assertNotNull(error)
        assertTrue("expected schemaVersion=2 in: $error", error!!.contains("schemaVersion=2"))
    }

    @Test
    fun pairedBuildIdMismatch_failsLoudly() {
        // Same on-disk files, same correct hashes — but the manifest claims the
        // loader comes from a different build line than proot.
        val artifacts = allArtifacts().map { a ->
            if (a.file == "lib__bin__loader__.so") a.copy(buildId = "build-B") else a
        }
        BootstrapManifestTestSeam.overrideJson = manifestJson(artifacts)
        manager.setup()
        val error = manager.lastError
        assertNotNull("buildId mismatch must fail loudly", error)
        assertTrue("expected paired-artifact issue in: $error", error!!.contains("must come from the same build"))
    }

    // ---- ABI semantics ----

    @Test
    fun otherAbiEntry_doesNotFalsePositive() {
        // Both ABIs are recorded in the manifest; only one ABI's files are
        // installed. A correct install always matches its OWN ABI's entry, so
        // the other ABI's (non-matching) entry must never fire.
        val both = allArtifacts(abi = "arm64-v8a") +
            managedFiles.map { artifactFor(it, abi = "armeabi-v7a", buildId = "build-v7a") }
                .map { it.copy(sha256 = "0".repeat(64)) }
        BootstrapManifestTestSeam.overrideJson = manifestJson(both)
        manager.setup()
        assertNull("other-ABI entry must not false-positive: ${manager.lastError}", manager.lastError)
        assertTrue(manager.isReady)
    }

    // ---- must-not-break: self-heal still runs ----

    @Test
    fun selfHeal_recreatesMissingSymlinks_withValidManifestPresent() {
        BootstrapManifestTestSeam.overrideJson = manifestJson(allArtifacts())
        manager.setup()
        assertTrue(manager.isReady)

        // Simulate the common post-update breakage: a binDir symlink vanished.
        val tarLink = File(filesDir, "bootstrap/bin/tar")
        assertTrue(Files.isSymbolicLink(tarLink.toPath()))
        assertTrue(tarLink.delete())

        // Next launch: self-heal must recreate the symlink AND the manifest
        // still passes — the manifest check layers on top, never replaces.
        manager.setup()
        assertTrue("self-heal must recreate binDir/tar", Files.isSymbolicLink(tarLink.toPath()))
        assertNull("self-heal with valid manifest must stay clean: ${manager.lastError}", manager.lastError)
        assertTrue(manager.isReady)
        assertEquals("tar", manager.resolveBinary("tar").name)
    }
}
