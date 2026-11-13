package com.fem.linexa.service.installer

import android.app.Application
import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.core.security.RealArchiveValidator
import com.fem.linexa.service.rootfs.RootFsInspector
import com.fem.linexa.service.rootfs.profile.AssetRootFsProfileCatalog
import com.fem.linexa.service.runtime.WorkspaceFileManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * §1.147 — the audit's real-archive gate.
 *
 * This is not a synthetic fixture test: it runs Pass 1 (`analyze`) over the
 * archives actually present on the device, through the same installer object
 * the app builds, and prints one machine-readable line per archive. Its purpose
 * is to keep the audit honest — a distribution is rated PASS only when its own
 * bytes reach [AnalyzeResult.Success] with the expected identity.
 *
 * Opt-in on purpose: the directory holds ~1.5 GB of images and scanning them
 * belongs to an audit run, not to every `testDebugUnitTest`.
 *
 * ```
 * ./gradlew :app:testDebugUnitTest \
 *   --tests 'com.fem.linexa.service.installer.RealArchiveAnalysisTest' \
 *   -Dlinexa.realArchives=true
 * ```
 *
 * Narrow a run with `-Dlinexa.realArchives.filter=alpine` and override the
 * directory with `-Dlinexa.realArchives.dir=/path`.
 *
 * The Android surface `analyze` touches is replaced, not emulated: a mocked
 * `ContentResolver` streams the file under test, and a mocked `AssetManager`
 * serves the real shipped profiles from disk. That keeps this runnable on the
 * aarch64 Termux JVM, where Robolectric's bundled conscrypt has no native
 * library.
 */
class RealArchiveAnalysisTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var installer: StagedRootFsInstaller
    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var resolver: ContentResolver

    /** The file the mocked content resolver opens for the current `analyze` call. */
    private var source: File? = null

    @Before
    fun setUp() {
        assumeTrue(
            "set -Dlinexa.realArchives=true to scan the device's archives",
            System.getProperty("linexa.realArchives") == "true"
        )

        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)

        resolver = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(app.contentResolver).thenReturn(resolver)
        Mockito.`when`(resolver.openInputStream(Mockito.any())).thenAnswer {
            val file = source ?: error("no source set for analyze()")
            FileInputStream(file)
        }
        // The length probe may answer null: the tee still hashes and the
        // progress denominator stays unknown, exactly like an unknown
        // content-provider length in production.
        Mockito.`when`(resolver.openAssetFileDescriptor(Mockito.any(), Mockito.anyString()))
            .thenReturn(null)

        val assets = Mockito.mock(AssetManager::class.java)
        val assetsDir = locateAssets()
        AssetRootFsProfileCatalog.BUILT_IN_FILES.forEach { fileName ->
            Mockito.`when`(assets.open("${AssetRootFsProfileCatalog.ASSET_DIR}/$fileName"))
                .thenAnswer { ByteArrayInputStream(File(assetsDir, fileName).readBytes()) }
        }
        Mockito.`when`(app.assets).thenReturn(assets)

        fileManager = WorkspaceFileManager(app)

        val bootstrap = Mockito.mock(BootstrapManager::class.java)
        Mockito.`when`(bootstrap.isReady).thenReturn(true)
        Mockito.`when`(bootstrap.diagnoseAll()).thenReturn("<real-archive-test>")
        val diagnostic = Mockito.mock(DiagnosticLogManager::class.java)

        installer = StagedRootFsInstaller(
            application = app,
            fileManager = fileManager,
            archiveValidator = RealArchiveValidator(),
            bootstrapManager = bootstrap,
            rootFsInspector = RootFsInspector(WorkspaceConfigParser()),
            rootfsExtractor = Mockito.mock(RootfsExtractor::class.java),
            l2sLinkRewriter = L2sLinkRewriter(diagnostic),
            archiveBoundaryAuditor = ArchiveBoundaryAuditor(diagnostic),
            rootfsFixupService = Mockito.mock(RootfsFixupService::class.java),
            guestRootfsInitializer = Mockito.mock(GuestRootfsInitializer::class.java),
            ociLayerUnwrapper = OciLayerUnwrapper(RealArchiveValidator()),
            profileCatalog = AssetRootFsProfileCatalog(app),
            runtimeVerifier = Mockito.mock(RootfsRuntimeVerifier::class.java)
        )
    }

    private fun locateAssets(): File {
        val relative = "src/main/assets/${AssetRootFsProfileCatalog.ASSET_DIR}"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isDirectory }?.let { return it }
            File(dir, "app/$relative").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("could not locate $relative")
    }

    @Test
    fun scanTheDevicesRealArchives() = runBlocking {
        val dir = File(
            System.getProperty("linexa.realArchives.dir")
                ?: "/storage/emulated/0/Download/Linexa/Distrbution & Rootfs"
        )
        assumeTrue("archive directory missing at ${dir.absolutePath}", dir.isDirectory)

        val filter = System.getProperty("linexa.realArchives.filter")?.lowercase()
        val archives = dir.walkTopDown()
            .filter { it.isFile }
            // A metadata declaration is not an archive; it is a fixture for
            // the metadata pipeline and is covered by its own tests.
            .filterNot { it.name.startsWith(".") && it.name.endsWith(".yaml") }
            .filter { file -> filter == null || file.name.lowercase().contains(filter) }
            // The same image is present at the top level and under Rootfs/.
            // Scanning the same bytes twice proves nothing and doubles the run.
            .distinctBy { it.name to it.length() }
            .sortedBy { it.name }
            .toList()

        assertTrue("no archives found under ${dir.absolutePath}", archives.isNotEmpty())

        // A distribution bundle that ships no rootfs is *correctly* rejected.
        // Encoding that here keeps the distinction between "the pipeline broke"
        // and "the file is not a rootfs" visible in the audit.
        val expectedRejections = mapOf(
            "images-main.zip" to "holds no rootfs archive"
        )

        val failures = LinkedHashMap<String, String>()
        val unexpectedSuccesses = LinkedHashMap<String, String>()
        val errors = LinkedHashMap<String, String>()
        for (archive in archives) {
            val workspaceId = "real-" + archive.name
                .replace(Regex("[^A-Za-z0-9]+"), "-")
                .take(60)
            val base = fileManager.getWorkspaceBasePath(workspaceId)
            base.mkdirs()
            var analysisFile = archive
            var note = ""
            try {
                val started = System.currentTimeMillis()
                val result = try {
                    analyze(analysisFile, workspaceId)
                } catch (e: Throwable) {
                    // zstd-jni's desktop build cannot load on the bionic JVM
                    // that runs these tests; the Android build in the APK can,
                    // and works on-device. The first touch throws
                    // UnsatisfiedLinkError and every later one the sticky
                    // NoClassDefFoundError from the same failed initializer.
                    // Decompress with the platform zstd CLI so the archive's
                    // own contents still go through the real scan, and say so
                    // in the audit line.
                    val zstdHostFailure = archive.name.endsWith(".zst") &&
                        (e is UnsatisfiedLinkError || e is NoClassDefFoundError) &&
                        (e.message?.contains("zstd", ignoreCase = true) == true ||
                            e.message?.contains("ZstdInputStream", ignoreCase = true) == true)
                    if (!zstdHostFailure) throw e
                    val tar = File(tmp.newFolder("zstd-${archive.name.hashCode()}"), "payload.tar")
                    decompressZstd(archive, tar)
                    analysisFile = tar
                    note = "\tnote=pre-decompressed-with-zstd-cli"
                    analyze(tar, workspaceId)
                }
                val elapsed = (System.currentTimeMillis() - started) / 1000
                val lineBase = "${archive.name}\t${archive.length()}\t${elapsed}s"
                when (result) {
                    is AnalyzeResult.Success -> {
                        println(
                            "AUDIT\tPASS\t$lineBase\t" +
                                "profile=${result.profileId}/${result.profileSource}\t" +
                                "id=${result.identity.distroId}/${result.identity.distroName}\t" +
                                "detected=${result.identity.detected}\t" +
                                "version=${result.identity.version}\tarch=${result.identity.arch}\t" +
                                "format=${result.payloadFormat}\tstrip=${result.stripComponents}\t" +
                                "shell=${result.loginShell}\tpm=${result.packageManager}\t" +
                                "entries=${result.totalEntries}$note"
                        )
                        if (archive.name in expectedRejections) {
                            unexpectedSuccesses[archive.name] = "expected rejection"
                        }
                    }

                    is AnalyzeResult.Failure -> {
                        println(
                            "AUDIT\tFAIL\t$lineBase\t${result.reason.replace('\n', ' ')}$note"
                        )
                        val expected = expectedRejections[archive.name]
                        if (expected == null) {
                            failures[archive.name] = result.reason
                        } else if (!result.reason.contains(expected)) {
                            failures[archive.name] =
                                "expected a rejection containing '$expected', got: ${result.reason}"
                        }
                    }
                }
            } catch (t: Throwable) {
                errors[archive.name] = "${t.javaClass.simpleName}: ${t.message}"
                println("AUDIT\tERROR\t${archive.name}\t${archive.length()}\t${t.javaClass.simpleName}: ${t.message}")
            } finally {
                source = null
                base.deleteRecursively()
                if (analysisFile != archive) analysisFile.delete()
            }
        }

        assertTrue("the real-archive audit errored: $errors", errors.isEmpty())
        assertTrue(
            "these files are not rootfs bundles but analyzed as one: $unexpectedSuccesses",
            unexpectedSuccesses.isEmpty()
        )
        assertEquals(
            "the real-archive audit has failures: $failures",
            emptyMap<String, String>(),
            failures
        )
    }

    private suspend fun analyze(file: File, workspaceId: String): AnalyzeResult {
        source = file
        val uri = Mockito.mock(Uri::class.java)
        Mockito.`when`(uri.toString()).thenReturn(file.absolutePath)
        return installer.analyze(
            workspaceId = workspaceId,
            sourceUri = uri,
            expectedSha256 = null,
            bundledMetadata = null,
            onProgress = {}
        )
    }

    /**
     * Test-host shim for `.tar.zst`: zstd-jni's linux-aarch64 binary is linked
     * against glibc and bionic refuses it. The device app uses the APK's
     * Android-native zstd build, so this only affects the audit host.
     */
    private fun decompressZstd(sourceFile: File, target: File) {
        val process = ProcessBuilder(
            "zstd", "-d", "--quiet", "--stdout", sourceFile.absolutePath
        ).redirectOutput(target).redirectErrorStream(false).start()
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) {
            "zstd failed for '${sourceFile.name}' (exit $exit): ${stderr.take(500)}"
        }
    }
}
