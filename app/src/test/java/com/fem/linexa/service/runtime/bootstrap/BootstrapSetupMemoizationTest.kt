package com.fem.linexa.service.runtime.bootstrap

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.test.support.TerminfoTestFixture
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * §16.3.2 — memoisation of [BootstrapManager.setup].
 *
 * The audit's §16.3 asked for "an `AtomicBoolean` guard, it is already
 * documented as idempotent". Idempotent is not the same as free, and it is also
 * not the same as *unconditionally skippable*: `setup()` is the self-heal, so a
 * naive latch would mean a layout that breaks after the first launch never gets
 * repaired for the life of the process. The landed design memoises only a pass
 * that reported **no issues at all** (`lastError == null`), and every path that
 * has detected a problem goes through [BootstrapManager.forceSetup].
 *
 * These tests observe the memo through real, externally visible filesystem work
 * rather than through a counter: a symlink is deleted between calls, and whether
 * `setup()` recreates it is the observable. That is the property that actually
 * matters (does the self-heal still run?), and it cannot be satisfied by a test
 * double.
 *
 * The environment mirrors the other bootstrap suites — a Mockito [Application]
 * with `nativeLibraryDir` pointed at a temp folder of inert stand-in files, both
 * asset seams seeded so a clean run really is clean.
 */
class BootstrapSetupMemoizationTest {

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
        "lib__lib__libncursesw.so.6__.so", "lib__lib__libevent_core-2.1.so__.so",
        // §1.77 proot dependencies — present so the manifest can cover the
        // whole managed set and a clean run has nothing to report.
        "lib__lib__libtalloc.so.2__.so", "lib__lib__libandroid-shmem.so__.so"
    )

    @Before
    fun setUp() {
        BootstrapManifestTestSeam.overrideJson = null
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

    private fun app(
        filesDirOverride: File = filesDir,
        nativeLibDirOverride: File = nativeLibDir
    ): Application {
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = nativeLibDirOverride.absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDirOverride)
        Mockito.`when`(app.cacheDir).thenReturn(File(filesDirOverride, "cache"))
        Mockito.`when`(app.getExternalFilesDir(any())).thenReturn(File(filesDirOverride, "shared"))
        return app
    }

    /**
     * A manifest covering every stand-in, hashed from the files actually on
     * disk, so step 6 reports nothing and the run can converge. Without this the
     * bundled asset is absent under Mockito and validation is skipped — which
     * also converges, but for a less interesting reason; seeding it means the
     * memo is being tested against a run that did the full hash work.
     */
    private fun seedMatchingManifest() {
        val artifacts = managedFiles.map { file ->
            BootstrapManifest.Artifact(
                file = file,
                abi = "arm64-v8a",
                version = "test-1.0",
                buildId = "build-A",
                sha256 = BootstrapManifestSupport.sha256Hex(File(nativeLibDir, file))
                    ?: error("could not hash $file")
            )
        }
        BootstrapManifestTestSeam.overrideJson = Json { prettyPrint = false }.encodeToString(
            BootstrapManifest(
                schemaVersion = 1,
                artifacts = artifacts,
                pairs = listOf(
                    BootstrapManifest.PairedArtifacts(
                        label = "loader must come from the same build as proot",
                        members = listOf("libproot.so", "lib__bin__loader__.so")
                    )
                )
            )
        )
    }

    private fun binLink(name: String): File = File(filesDir, "bootstrap/bin/$name")

    private fun terminfoKey(): File = File(File(filesDir, "terminfo"), "x/xterm-256color")

    // ---- Test 1: the first pass still does the full work ----

    @Test
    fun firstSetupPerformsTheFullPassAndMemoizesIt() {
        seedMatchingManifest()

        assertFalse("nothing may be memoized before the first call", manager.isSetupMemoized)
        manager.setup()

        // Every step's output is present: bin symlinks (2), applet layer (3b),
        // lib symlinks (4), terminfo (7).
        assertTrue("binDir symlink must exist", Files.isSymbolicLink(binLink("tar").toPath()))
        assertTrue("applet link must exist", Files.isSymbolicLink(binLink("sed").toPath()))
        assertTrue(
            "libDir symlink must exist",
            Files.isSymbolicLink(File(filesDir, "bootstrap/lib/libz.so.1").toPath())
        )
        assertTrue("terminfo entry must be installed", terminfoKey().isFile)
        assertNull("a clean pass must not set lastError: ${manager.lastError}", manager.lastError)
        assertTrue(manager.isReady)
        assertTrue("a clean pass must publish the memo", manager.isSetupMemoized)
        assertEquals("exactly one full pass", 1, manager.setupRunCount)
    }

    // ---- Test 2: the second pass is skipped ----

    @Test
    fun secondSetupIsSkippedAndDoesNoFilesystemWork() {
        seedMatchingManifest()
        manager.setup()
        assertTrue(manager.isSetupMemoized)

        // Delete a terminfo entry and an applet link. Neither is part of
        // BIN_ALIASES, so the fast-path integrity guard still passes — this is
        // exactly the state where the pre-§16.3.2 code would have rewritten both,
        // and where the memo's whole saving lies.
        assertTrue(terminfoKey().delete())
        assertTrue(binLink("sed").delete())

        manager.setup()

        assertEquals("the full pass must not have run again", 1, manager.setupRunCount)
        assertFalse(
            "a memoized setup() must not re-write terminfo entries",
            terminfoKey().exists()
        )
        assertFalse(
            "a memoized setup() must not re-run the applet layer",
            Files.exists(binLink("sed").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        )
        assertTrue("the memo must still stand", manager.isSetupMemoized)
    }

    @Test
    fun manyRepeatLaunchesRunTheFullPassOnceEach() {
        seedMatchingManifest()
        // The §16.3.2 scenario in the audit's terms: a cold Desktop start reaches
        // ensureBootstrap from openTerminal, execInternal and startDaemon, and
        // the user then opens more terminals. Before the memo this was one full
        // pass per call.
        repeat(12) { manager.setup() }
        assertEquals("12 launches, one pass", 1, manager.setupRunCount)
    }

    // ---- Test 3: failure stays retryable ----

    @Test
    fun aFailedSetupIsNotMemoizedAndTheNextCallRetries() {
        // A terminfo gap: assets unavailable (no seam) makes step 7 report
        // issues, so lastError is non-null and the pass must not converge. This
        // is the realistic partial failure — isReady is still true, which is
        // precisely why keying the memo on isReady would have been wrong.
        BootstrapManager.TerminfoTestSeam.overrideBytes = null
        seedMatchingManifest()

        manager.setup()

        assertNotNull("the terminfo gap must be reported", manager.lastError)
        assertTrue("isReady is untouched by a terminfo gap", manager.isReady)
        assertFalse("a pass with issues must NOT be memoized", manager.isSetupMemoized)

        // The retry can now succeed, and the work really does re-run: prove it
        // by deleting a link the second pass has to recreate.
        assertTrue(binLink("sed").delete())
        BootstrapManager.TerminfoTestSeam.overrideBytes = TerminfoTestFixture.validEntries

        manager.setup()

        assertTrue("the retry must re-run the applet layer", Files.isSymbolicLink(binLink("sed").toPath()))
        assertTrue("the retry must install terminfo", terminfoKey().isFile)
        assertNull("the retry converged: ${manager.lastError}", manager.lastError)
        assertTrue("only now may the memo be published", manager.isSetupMemoized)
    }

    @Test
    fun aHardEarlyReturnIsNotMemoized() {
        // The other failure shape: nativeLibraryDir empty, which returns before
        // touching anything. Nothing was created, so nothing may be cached.
        val emptyNativeDir = tmp.newFolder("empty-native-lib")
        val isolated = BootstrapManager(app(nativeLibDirOverride = emptyNativeDir))

        isolated.setup()

        assertEquals("nativeLibraryDir empty or missing", isolated.lastError)
        assertFalse("an aborted setup must never memoize", isolated.isSetupMemoized)
    }

    // ---- Test 4: invalidation through the integrity guard ----

    @Test
    fun aBrokenManagedBinaryInvalidatesTheMemoOnTheNextSetup() {
        seedMatchingManifest()
        manager.setup()
        assertTrue(manager.isSetupMemoized)

        // Break a BIN_ALIASES entry — the launch gate's own subject, and the
        // thing the fast-path integrity guard checks.
        assertTrue(binLink("tar").delete())
        // Also delete something the guard does NOT cover, to prove the retract
        // triggers the FULL pass rather than a partial repair.
        assertTrue(terminfoKey().delete())

        manager.setup()

        assertEquals("the retract must trigger a second full pass", 2, manager.setupRunCount)
        assertTrue("self-heal must recreate binDir/tar", Files.isSymbolicLink(binLink("tar").toPath()))
        assertTrue("and the retracted memo must re-run the whole pass", terminfoKey().isFile)
        assertTrue(manager.isReady)
        assertTrue("the healed pass re-publishes the memo", manager.isSetupMemoized)
    }

    @Test
    fun theIntegrityGuardIsCheckedBeforeTheMemoIsTrusted() {
        seedMatchingManifest()
        manager.setup()
        assertTrue(manager.isSetupMemoized)

        assertTrue(binLink("proot").delete())
        // The memo is still set at this point — nothing has looked yet. The
        // guarantee is that setup() does not act on it without verifying.
        assertTrue("the memo is not retracted until setup() looks", manager.isSetupMemoized)

        manager.setup()

        assertTrue(
            "a memo whose binaries no longer validate must not be trusted",
            Files.isSymbolicLink(binLink("proot").toPath())
        )
    }

    @Test
    fun isReadyHasNoSideEffectsOnTheMemo() {
        seedMatchingManifest()
        manager.setup()
        assertTrue(manager.isSetupMemoized)

        // isReady is the launch gate and is read on paths that must not mutate
        // anything — including from diagnostics. Breaking the layout and reading
        // it must report the breakage without touching the memo; retraction is
        // setup()'s job, at a point where it can immediately act on it.
        assertTrue(binLink("tar").delete())
        assertFalse("the layout must no longer validate", manager.isReady)
        assertTrue("but reading it must not mutate the memo", manager.isSetupMemoized)
    }

    @Test
    fun isReadyNeverPublishesTheMemoOnItsOwn() {
        seedMatchingManifest()
        // A healthy layout with no setup() ever run on THIS instance: the
        // readiness read must not be mistakable for a converged setup, or the
        // first pass of a fresh process would be skipped.
        manager.setup()
        val healthy = BootstrapManager(app())
        assertTrue("layout from the previous manager is healthy", healthy.isReady)
        assertFalse("a readiness read must not publish a memo", healthy.isSetupMemoized)
    }

    // ---- Test 5: forced refresh ----

    @Test
    fun forceSetupBypassesTheMemoAndRedoesTheWork() {
        seedMatchingManifest()
        manager.setup()
        assertTrue(manager.isSetupMemoized)

        // Same deletions as the "second setup is skipped" case, so the contrast
        // between setup() and forceSetup() is the only variable.
        assertTrue(terminfoKey().delete())
        assertTrue(binLink("sed").delete())

        manager.forceSetup()

        assertEquals("forceSetup must actually run the pass", 2, manager.setupRunCount)
        assertTrue("forceSetup must re-install terminfo", terminfoKey().isFile)
        assertTrue("forceSetup must re-run the applet layer", Files.isSymbolicLink(binLink("sed").toPath()))
        assertNull(manager.lastError)
        assertTrue("a clean forced pass re-publishes the memo", manager.isSetupMemoized)
    }

    @Test
    fun forceSetupOnAFailingLayoutLeavesTheMemoUnset() {
        seedMatchingManifest()
        manager.setup()
        assertTrue(manager.isSetupMemoized)

        // Force a run that cannot converge.
        BootstrapManager.TerminfoTestSeam.overrideBytes = null
        manager.forceSetup()

        assertNotNull(manager.lastError)
        assertFalse(
            "a forced pass that reports issues must clear, not keep, the memo",
            manager.isSetupMemoized
        )
    }

    // ---- Test 6: concurrency ----

    @Test
    fun concurrentSetupCallersProduceExactlyOneRun() {
        seedMatchingManifest()
        val threads = 8
        val start = CountDownLatch(1)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val failures = ConcurrentHashMap<String, Throwable>()
        try {
            repeat(threads) { i ->
                pool.submit {
                    ready.countDown()
                    try {
                        // Deterministic barrier — all threads released together,
                        // no sleeps.
                        start.await(30, TimeUnit.SECONDS)
                        manager.setup()
                    } catch (t: Throwable) {
                        failures["t$i"] = t
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue("all workers must reach the barrier", ready.await(30, TimeUnit.SECONDS))
            start.countDown()
            assertTrue("all workers must finish", done.await(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertTrue("no worker may fail: $failures", failures.isEmpty())
        assertEquals(
            "the full pass must execute exactly once across $threads concurrent callers",
            1,
            manager.setupRunCount
        )
        assertTrue("the layout must have converged", manager.isSetupMemoized)
        assertNull(manager.lastError)
        assertTrue(manager.isReady)
        // The layout is complete, which a racing double-run over the
        // delete-then-recreate steps could have corrupted.
        assertTrue(Files.isSymbolicLink(binLink("tar").toPath()))
        assertTrue(Files.isSymbolicLink(binLink("sed").toPath()))
        assertTrue(Files.isSymbolicLink(File(filesDir, "bootstrap/lib/libz.so.1").toPath()))
        assertTrue(terminfoKey().isFile)
    }

    @Test
    fun concurrentForceSetupCallersAreSerializedAndAllConverge() {
        seedMatchingManifest()
        val threads = 6
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val failures = ConcurrentHashMap<String, Throwable>()
        try {
            repeat(threads) { i ->
                pool.submit {
                    try {
                        start.await(30, TimeUnit.SECONDS)
                        manager.forceSetup()
                    } catch (t: Throwable) {
                        failures["t$i"] = t
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        // forceSetup deletes and recreates lib symlinks on every pass, so
        // unserialized runs are exactly where a "file exists"/"no such file"
        // race would surface. Every call must run — force means force — but they
        // must run one at a time.
        assertTrue("no worker may fail: $failures", failures.isEmpty())
        assertEquals(
            "every forceSetup must execute, none may be skipped",
            threads,
            manager.setupRunCount
        )
        assertNull("every forced pass must converge: ${manager.lastError}", manager.lastError)
        assertTrue(manager.isSetupMemoized)
        assertTrue(manager.isReady)
    }

    // ---- Test 8: instance scoping ----

    @Test
    fun theMemoIsPerInstanceSoASecondManagerStillRunsItsFirstSetup() {
        seedMatchingManifest()
        manager.setup()
        assertTrue(manager.isSetupMemoized)

        // BootstrapManager is a @Singleton in production, so a second instance
        // only exists in tests — but the scope must still be the instance, not a
        // static: a static latch would make one test (or one process-wide
        // consumer) suppress another's first-run setup entirely.
        val second = BootstrapManager(app())
        assertFalse("a fresh instance must not inherit the memo", second.isSetupMemoized)

        assertTrue(binLink("sed").delete())
        second.setup()
        assertTrue(
            "the fresh instance's first setup must do the full pass",
            Files.isSymbolicLink(binLink("sed").toPath())
        )
        assertTrue(second.isSetupMemoized)
    }

    @Test
    fun twoManagersOverSeparateFilesDirsDoNotShareState() {
        seedMatchingManifest()
        manager.setup()
        assertTrue(manager.isSetupMemoized)

        val otherFiles = tmp.newFolder("files-2")
        val other = BootstrapManager(app(filesDirOverride = otherFiles))
        other.setup()

        assertTrue(
            "the second layout must be built independently",
            Files.isSymbolicLink(File(otherFiles, "bootstrap/bin/tar").toPath())
        )
        assertTrue(other.isSetupMemoized)
        assertTrue("and the first must be unaffected", manager.isSetupMemoized)
    }

    // ---- diagnostics ----

    @Test
    fun diagnosticsReportTheMemoStateAndRunCount() {
        seedMatchingManifest()
        // A stale-bootstrap report has to be able to tell "good layout" from
        // "good layout, and we stopped re-checking" — and a run count that
        // tracks the launch count is the signal that something outside this
        // class keeps breaking the layout.
        assertTrue(manager.diagnoseAll().contains("setupMemoized (§16.3.2): false"))
        assertTrue(manager.diagnoseAll().contains("setupRuns (§16.3.2): 0"))
        manager.setup()
        manager.setup()
        val dump = manager.diagnoseAll()
        assertTrue(dump.contains("setupMemoized (§16.3.2): true"))
        assertTrue("two calls, one run", dump.contains("setupRuns (§16.3.2): 1"))
    }
}
