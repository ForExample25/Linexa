package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DebugCapture
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import org.mockito.Mockito

/**
 * §1.80 unit tests for [GuestRootfsInitializer.ensureGuestTmp] — the guest
 * `/tmp` (drwxrwxrwt) guarantee. Runs the REAL initializer against a real
 * fake busybox `chmod` applet that forwards to the host's `/bin/chmod`, so
 * the sticky bit genuinely lands on disk and the mode is verified through
 * `unix:mode`.
 *
 * Phase 2E-2: every test runs with [GuestRootfsInitializer.LstatTestSeam]
 * wired to the JVM's no-follow `unix:mode` reader (android.system.Os cannot
 * execute in plain JVM unit tests), so the steady-state skip gate decides on
 * REAL filesystem states — real 1777 dirs skip, symlinks/files/wrong modes
 * repair — and the matrix below pins skip-vs-repair per state by counting
 * chmod spawns.
 */
class GuestRootfsInitializerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * UI/UX Phase 7: [DebugCapture] is a process-wide flag, so a test that enables
     * it must put it back or it leaks into every test running afterwards in this
     * JVM — including the ones above, which assert the *absence* of a log file.
     */
    @After
    fun resetCapture() {
        DebugCapture.resetForTesting()
        GuestRootfsInitializer.LstatTestSeam.override = null
    }

    /**
     * Phase 2E-2: substitutes android.system.Os.lstat (unavailable in JVM
     * unit tests) with the java.nio NOFOLLOW equivalent — same st_mode
     * semantics (dir 41777, symlink 120777, ENOENT → null).
     */
    @Before
    fun installJvmNoFollowLstat() {
        GuestRootfsInitializer.LstatTestSeam.override = { path ->
            try {
                val mode = (Files.getAttribute(
                    File(path).toPath(), "unix:mode", LinkOption.NOFOLLOW_LINKS
                ) as Number).toInt()
                Pair(mode and 0xFFF, mode and 0xF000)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Builds the initializer with a working busybox `chmod` applet:
     * `{filesDir}/bootstrap/bin/chmod` forwards to the host's real
     * `/bin/chmod` (present on CI ubuntu and Android's toybox), so 1777 is
     * actually applied — exactly like the harness's fake busybox does.
     */
    private fun newInitializer(filesDir: File = tmp.newFolder("files")): GuestRootfsInitializer {
        val binDir = File(filesDir, "bootstrap/bin")
        require(binDir.mkdirs())
        val chmod = File(binDir, "chmod")
        chmod.writeText("#!/bin/sh\nexec /bin/chmod \"\$@\"\n")
        require(chmod.setExecutable(true))

        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        return GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))
    }

    /** Asserts `{rootfs}/tmp` is a directory with permission bits exactly 01777. */
    private fun assertTmpMode1777(rootfs: File) {
        val tmpDir = File(rootfs, "tmp")
        assertTrue("guest /tmp must exist as a directory", tmpDir.isDirectory)
        val mode = Files.getAttribute(tmpDir.toPath(), "unix:mode") as Number
        val permissionBits = mode.toLong() and 0x3FF
        assertEquals("guest /tmp must be exactly 01777 (drwxrwxrwt)", 0x3FF, permissionBits)
    }

    /** Host-side chmod helper so a test can set a known starting mode. */
    private fun realChmod(target: File, mode: String) {
        val p = ProcessBuilder("/bin/chmod", mode, target.absolutePath)
            .redirectErrorStream(true)
            .start()
        assertEquals("host chmod $mode must succeed", 0, p.waitFor())
    }

    @Test
    fun freshRootfsGetsTmpWithExact1777() {
        val initializer = newInitializer()
        val rootfs = tmp.newFolder("rootfs")
        assertFalse("fresh workspace has no /tmp — the Debian tarball omits it", File(rootfs, "tmp").exists())

        assertNull("ensureGuestTmp must succeed on a fresh rootfs", initializer.ensureGuestTmp(rootfs, "ws-1"))

        assertTmpMode1777(rootfs)
    }

    @Test
    fun readOnlyRootfsRootIsRepairedEvenWhenTmpIsAlreadyHealthy() {
        // Arch/Fedora container archives restore `/` as 0555. The `/tmp`
        // fast path must not skip the root repair, or `.linexa` markers and
        // root-emulated top-level writes keep failing with EACCES.
        val initializer = newInitializer()
        val rootfs = tmp.newFolder("rootfs-readonly-root")
        val tmpDir = File(rootfs, "tmp").apply { mkdirs() }
        realChmod(tmpDir, "1777")

        val original = Files.getPosixFilePermissions(rootfs.toPath())
        val readOnly = original - PosixFilePermission.OWNER_WRITE
        Files.setPosixFilePermissions(rootfs.toPath(), readOnly)
        try {
            assertNull(
                "ensureGuestTmp must repair a read-only rootfs root",
                initializer.ensureGuestTmp(rootfs, "ws-readonly-root")
            )
            val repaired = Files.getPosixFilePermissions(rootfs.toPath())
            assertTrue(
                "the app must regain owner-write on the guest root",
                PosixFilePermission.OWNER_WRITE in repaired
            )
            assertEquals(
                "only the owner bits may be added; group/other stay untouched",
                readOnly - setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                ),
                repaired - setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
        } finally {
            Files.setPosixFilePermissions(rootfs.toPath(), original)
        }
    }

    @Test
    fun existingCorrectTmpIsKept() {
        val initializer = newInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())
        realChmod(tmpDir, "1777")

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-1"))
        assertTmpMode1777(rootfs)
    }

    @Test
    fun wrongPermissionsAreRepairedTo1777() {
        val initializer = newInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())
        realChmod(tmpDir, "0755")
        // sanity: the starting state is NOT 1777
        val before = Files.getAttribute(tmpDir.toPath(), "unix:mode") as Number
        assertTrue("precondition: mode must not already be 1777", (before.toLong() and 0x3FF) != 0x3FFL)

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-1"))
        assertTmpMode1777(rootfs)
    }

    @Test
    fun tmpThatIsAFileIsReplacedWithADirectory() {
        val initializer = newInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val tmpFile = File(rootfs, "tmp")
        tmpFile.writeText("a file where /tmp should be")

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-1"))
        assertTmpMode1777(rootfs)
    }

    @Test
    fun reportsFailureWhenChmodCannotRun() {
        // No binDir at all → resolveBinary("chmod") points at a missing file →
        // the applet run must fail loudly (returns a message, not a crash).
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        val initializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))
        val rootfs = tmp.newFolder("rootfs")

        val failure = initializer.ensureGuestTmp(rootfs, "ws-broken")

        assertNotNull("a missing chmod applet must be surfaced as a failure", failure)
        assertTrue("failure message must name the chmod step", failure!!.contains("chmod"))
    }

    // ── UI/UX Phase 7: the routine success line is gated, failures are not ──

    /**
     * The initializer's own log file, or null when it was never created.
     *
     * Path mirrors [DiagnosticLogManager.getLogsDir]: `{filesDir}/workspaces/{id}/logs/`.
     * A *missing* file is the assertion that matters below — "no content" and "no
     * file at all" are different claims, and the phase's requirement is the second.
     */
    private fun initLog(filesDir: File, workspaceId: String): File? =
        File(filesDir, "workspaces/$workspaceId/logs/rootfs-init.log").takeIf { it.exists() }

    @Test
    fun successLineIsNotWrittenWhileDebugCaptureIsOff() {
        // This is the frequency case, not a cosmetic one: ensureGuestTmp runs before
        // every terminal open AND every one-shot exec, so with capture off an apt
        // session or a File Manager walk used to append a line per operation to a
        // per-workspace log with no rotation and no size cap.
        DebugCapture.resetForTesting()
        val filesDir = tmp.newFolder("files")
        val initializer = newInitializer(filesDir)
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())
        realChmod(tmpDir, "1777")

        repeat(5) { assertNull(initializer.ensureGuestTmp(rootfs, "ws-quiet")) }

        assertNull(
            "an already-correct /tmp is a no-op — it must leave no log file behind at all",
            initLog(filesDir, "ws-quiet")
        )
    }

    @Test
    fun successLineIsWrittenOnceCaptureIsEnabled() {
        val filesDir = tmp.newFolder("files")
        val initializer = newInitializer(filesDir)
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())
        // Phase 2E-2: an already-1777 /tmp is now a SILENT skip (the steady-
        // state gate returns before any logging), so the trace-bearing case
        // is a repair — a wrong-mode dir still runs the full body below and
        // writes the ready line when the user asked for the trace.
        realChmod(tmpDir, "0755")

        DebugCapture.setEnabled(true)
        try {
            assertNull(initializer.ensureGuestTmp(rootfs, "ws-loud"))
        } finally {
            DebugCapture.resetForTesting()
        }

        val log = initLog(filesDir, "ws-loud")
        assertNotNull("with debug info on, the trace the user asked for must appear", log)
        assertTrue(log!!.readText().contains("guest /tmp ready"))
    }

    @Test
    fun failuresAreLoggedEvenWithDebugCaptureOff() {
        // The asymmetry is the decision: a user cannot be expected to have enabled a
        // debug setting *before* their workspace broke, so the entries that explain a
        // broken workspace stay unconditional.
        DebugCapture.resetForTesting()
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        val initializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))
        val rootfs = tmp.newFolder("rootfs")

        assertNotNull(initializer.ensureGuestTmp(rootfs, "ws-failed"))

        val log = initLog(filesDir, "ws-failed")
        assertNotNull("a chmod failure must leave evidence regardless of the toggle", log)
        assertTrue(log!!.readText().contains("chmod 1777 FAILED"))
    }

    @Test
    fun repairsAreLoggedEvenWithDebugCaptureOff() {
        // Creating a missing /tmp is a state change that happens once per workspace,
        // not a per-command event, so it is on the unconditional side too.
        DebugCapture.resetForTesting()
        val filesDir = tmp.newFolder("files")
        val initializer = newInitializer(filesDir)
        val rootfs = tmp.newFolder("rootfs")

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-repaired"))

        val log = initLog(filesDir, "ws-repaired")
        assertNotNull("the creation of a missing /tmp must be recorded", log)
        val text = log!!.readText()
        assertTrue(text.contains("created guest /tmp"))
        assertFalse(
            "…but the routine success line that follows it must still be suppressed",
            text.contains("guest /tmp ready")
        )
    }

    // ── Phase 2E-2: the steady-state lstat gate — correctness matrix ──────
    //
    // skip   = steady state: no chmod subprocess spawned at all (the spawn IS
    //          the ~100 ms cost being removed; the recording applet counts it).
    // repair = the existing body runs unchanged: chmod spawns, on-disk result
    //          identical to the pre-gate behavior (asserted throughout).

    /**
     * Initializer whose busybox `chmod` applet records every spawn to a file
     * before forwarding to the host's real chmod — so "skip" is pinned by the
     * ABSENCE of a spawn and "repair" by exactly one.
     */
    private fun newRecordingInitializer(
        filesDir: File = tmp.newFolder("files-rec")
    ): Pair<GuestRootfsInitializer, File> {
        val binDir = File(filesDir, "bootstrap/bin")
        require(binDir.mkdirs())
        val record = File(filesDir, "chmod-spawns.log")
        val escaped = record.absolutePath.replace("'", "'\\''")
        val chmod = File(binDir, "chmod")
        // ONE line per spawn, written with echo (a mandated shell builtin):
        // the production bootstrapEnv() restricts the child's PATH to binDir
        // only, and /bin/sh (Android mksh) has no printf builtin — a printf
        // applet resolves to nothing and the log stays empty. The recording
        // format is load-bearing: spawnCount and matrixI/J parse this log
        // line-by-line, exactly one line per chmod spawn.
        chmod.writeText(
            "#!/bin/sh\necho \"\$*\" >> '$escaped'\nexec /bin/chmod \"\$@\"\n"
        )
        require(chmod.setExecutable(true))

        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        return Pair(GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app)), record)
    }

    private fun spawnCount(record: File): Int =
        if (record.exists()) record.readLines().count { it.isNotBlank() } else 0

    /** Matrix A: already-1777 real directory → skip (zero spawns). */
    @Test
    fun matrixAAlready1777RealDirSkipsChmodSpawn() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())
        realChmod(tmpDir, "1777")

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-A"))

        assertEquals(
            "steady state: the lstat gate must skip the busybox chmod spawn entirely",
            0, spawnCount(record)
        )
        assertTmpMode1777(rootfs)
    }

    /** Matrix B: 0755 directory → repair (one spawn, mode now 1777). */
    @Test
    fun matrixBMode0755DirIsRepaired() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())
        realChmod(tmpDir, "0755")

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-B"))

        assertEquals(
            "wrong mode: the gate must NOT skip — the repair body runs and chmods",
            1, spawnCount(record)
        )
        assertTmpMode1777(rootfs)
    }

    /** Matrix C: mode drifts between calls → the next call repairs again. */
    @Test
    fun matrixCModeDriftBetweenCallsIsRepairedAgain() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-C"))
        assertTmpMode1777(rootfs)
        assertEquals("first ensure on a fresh dir must repair", 1, spawnCount(record))

        // Something flips the mode between launches — the next ensure must
        // NOT trust a cached decision and must re-chmod.
        realChmod(tmpDir, "0700")

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-C"))
        assertEquals("mode drifted after a prepared state: must repair again", 2, spawnCount(record))
        assertTmpMode1777(rootfs)
    }

    /**
     * Matrix D: a regular file at /tmp → the CURRENT body runs unchanged
     * (delete file, mkdirs, chmod). No symlink gate exists here and none is
     * added — the only gate change is the skip itself.
     */
    @Test
    fun matrixDRegularFileAtTmpRunsFullRepair() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        tmpFileAt(rootfs).writeText("payload")

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-D"))

        assertEquals("a regular file at /tmp must run the full repair (chmod)", 1, spawnCount(record))
        assertTmpMode1777(rootfs)
        assertFalse("the file must have been replaced by a directory", tmpFileAt(rootfs).isFile)
    }

    private fun tmpFileAt(rootfs: File): File = File(rootfs, "tmp")

    /**
     * Matrix E: a symlink at /tmp is REPLACED, never followed (Phase 3).
     * The gate cannot skip a symlink (lstat type is S_IFLNK, not S_IFDIR), so
     * the repair body runs; it must unlink the link itself, create a real
     * directory, and leave the link target's mode untouched. Before Phase 3
     * `File.isDirectory` followed the link and `chmod 1777` landed on the
     * target.
     */
    @Test
    fun matrixESymlinkedTmpIsReplacedWithoutTouchingTheTargetMode() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val target = tmp.newFolder("tmp-target")
        realChmod(target, "0700")
        val tmpPath = File(rootfs, "tmp").toPath()
        Files.createSymbolicLink(tmpPath, target.toPath())

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-E"))

        assertEquals("a symlinked /tmp must run the repair body", 1, spawnCount(record))
        assertFalse(
            "the symlink must have been replaced by a real directory",
            Files.isSymbolicLink(tmpPath)
        )
        assertTmpMode1777(rootfs)
        val mode = Files.getAttribute(target.toPath(), "unix:mode") as Number
        assertEquals(
            "chmod must NOT have landed through the link on the target",
            0x1C0L, mode.toLong() and 0x3FF
        )
        assertTrue("the link target itself must still exist", target.isDirectory)
    }

    /**
     * Matrix E2: a symlink at /tmp pointing at a regular file is unlinked and
     * replaced too; the target file's bytes and mode are untouched.
     */
    @Test
    fun matrixESymlinkToAFileIsReplacedWithoutTouchingTheTarget() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val target = File(tmp.newFolder("tmp-file-target"), "payload.txt")
        target.writeText("do not touch")
        realChmod(target, "0640")
        val tmpPath = File(rootfs, "tmp").toPath()
        Files.createSymbolicLink(tmpPath, target.toPath())

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-E2"))

        assertEquals("a symlinked /tmp must run the repair body", 1, spawnCount(record))
        assertFalse(Files.isSymbolicLink(tmpPath))
        assertTmpMode1777(rootfs)
        assertTrue("the link target file must still exist", target.isFile)
        assertEquals("the link target's bytes must be untouched", "do not touch", target.readText())
        val mode = Files.getAttribute(target.toPath(), "unix:mode") as Number
        assertEquals("the link target's mode must be untouched", 0x1A0L, mode.toLong() and 0x3FF)
    }

    /**
     * Matrix E3: a dangling symlink at /tmp (its target does not exist) is
     * still an lstat-visible symlink; it is unlinked and replaced. The old
     * `exists() && !isDirectory` gate saw a missing path and then `mkdirs()`
     * failed against the dangling link.
     */
    @Test
    fun matrixEDanglingSymlinkIsReplaced() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val missing = File(tmp.root, "does-not-exist-${System.nanoTime()}")
        val tmpPath = File(rootfs, "tmp").toPath()
        Files.createSymbolicLink(tmpPath, missing.toPath())

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-E3"))

        assertEquals("a dangling symlink must run the repair body", 1, spawnCount(record))
        assertFalse(Files.isSymbolicLink(tmpPath))
        assertTmpMode1777(rootfs)
    }

    /** Matrix F: missing dir (ENOENT) → body runs, creates + chmods. */
    @Test
    fun matrixFMissingTmpIsCreatedAndChmodded() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        assertFalse(File(rootfs, "tmp").exists())

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-F"))

        assertEquals("ENOENT: the gate must fall through to the creation body", 1, spawnCount(record))
        assertTmpMode1777(rootfs)
    }

    /**
     * Matrix G: lstat unavailable (seam returns null) or throwing → the body
     * runs (fail-safe = current behavior). Against an already-prepared dir,
     * "body runs" is observable as exactly one chmod spawn.
     */
    @Test
    fun matrixGLstatUnavailableRunsTheBodyFailSafe() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())
        realChmod(tmpDir, "1777")

        GuestRootfsInitializer.LstatTestSeam.override = { _ -> null }
        try {
            assertNull(initializer.ensureGuestTmp(rootfs, "ws-G"))
        } finally {
            GuestRootfsInitializer.LstatTestSeam.override = null
        }
        assertEquals("a null lstat must never skip — the body must run", 1, spawnCount(record))
        assertTmpMode1777(rootfs)
    }

    /** Matrix G (throwing variant): a throwing lstat is equally fail-safe. */
    @Test
    fun matrixGThrowingLstatRunsTheBodyFailSafe() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")
        val tmpDir = File(rootfs, "tmp")
        require(tmpDir.mkdirs())
        realChmod(tmpDir, "1777")

        GuestRootfsInitializer.LstatTestSeam.override = { _ ->
            throw IllegalStateException("simulated lstat explosion")
        }
        try {
            assertNull(initializer.ensureGuestTmp(rootfs, "ws-G-throw"))
        } finally {
            GuestRootfsInitializer.LstatTestSeam.override = null
        }
        assertEquals("a throwing lstat must never skip — the body must run", 1, spawnCount(record))
        assertTmpMode1777(rootfs)
    }

    /** Matrix H: repeated ensures on the same instance/path — idempotence. */
    @Test
    fun matrixHRepeatedEnsuresOnTheSamePathStayCorrect() {
        val (initializer, record) = newRecordingInitializer()
        val rootfs = tmp.newFolder("rootfs")

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-H"))
        assertEquals("first ensure must repair (create + chmod)", 1, spawnCount(record))

        assertNull(initializer.ensureGuestTmp(rootfs, "ws-H"))
        assertNull(initializer.ensureGuestTmp(rootfs, "ws-H"))
        assertEquals("prepared steady state must not spawn again", 1, spawnCount(record))
        assertTmpMode1777(rootfs)

        realChmod(File(rootfs, "tmp"), "0755")
        assertNull(initializer.ensureGuestTmp(rootfs, "ws-H"))
        assertEquals("drifted dir must repair again on the same path", 2, spawnCount(record))
        assertTmpMode1777(rootfs)
    }

    /** Matrix I: different rootfs paths are judged independently. */
    @Test
    fun matrixIIndependentRootfsPathsAreIndependentlyJudged() {
        val (initializer, record) = newRecordingInitializer()
        val rootfsA = tmp.newFolder("rootfs-i-a")
        val rootfsB = tmp.newFolder("rootfs-i-b")

        assertNull(initializer.ensureGuestTmp(rootfsA, "ws-I"))
        assertTmpMode1777(rootfsA)
        assertFalse("workspace B must be untouched by workspace A's ensure",
            File(rootfsB, "tmp").exists())

        // A is now prepared (skip), B is fresh (repair) — per-path decisions.
        val pathA = File(rootfsA, "tmp").absolutePath
        val pathB = File(rootfsB, "tmp").absolutePath
        assertNull(initializer.ensureGuestTmp(rootfsA, "ws-I"))
        assertNull(initializer.ensureGuestTmp(rootfsB, "ws-I"))
        // The log is cumulative: A's first-pass repair (line 1), two A skips
        // (no lines), then B's first-pass repair (line 2) — exactly two
        // spawns, each attributable to its own rootfs.
        val lines = record.readLines().filter { it.isNotBlank() }
        assertEquals("exactly the two repair spawns (A's first pass + B's first pass)", 2, lines.size)
        assertTrue("the first spawn must belong to workspace A's path: ${lines[0]}",
            lines[0].endsWith(pathA))
        assertTrue("the second spawn must belong to workspace B's path: ${lines[1]}",
            lines[1].endsWith(pathB))
        assertTmpMode1777(rootfsA)
        assertTmpMode1777(rootfsB)
    }

    /**
     * Matrix J: concurrent ensures on DIFFERENT paths do not interfere. The
     * gate (and the body) hold no shared per-path state, so two parallel
     * calls on separate rootfs each get their own verdict. Same-path
     * concurrency needs no test here: PRootContainerRuntime serializes every
     * repair section per workspace behind workspaceInitLock (LX-2 —
     * openTerminal, exec and the daemon path all lock before the ensures).
     */
    @Test
    fun matrixJConcurrentEnsuresOnDifferentPathsDoNotInterfere() {
        val (initializer, record) = newRecordingInitializer()
        val rootfsA = tmp.newFolder("rootfs-j-a")
        val rootfsB = tmp.newFolder("rootfs-j-b")
        val tmpA = File(rootfsA, "tmp")
        require(tmpA.mkdirs())
        realChmod(tmpA, "1777")

        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                pool.submit<String?> { initializer.ensureGuestTmp(rootfsA, "ws-J") },
                pool.submit<String?> { initializer.ensureGuestTmp(rootfsB, "ws-J") }
            )
            for (future in futures) {
                assertNull("concurrent ensure on its own rootfs must succeed", future.get(30, TimeUnit.SECONDS))
            }
        } finally {
            pool.shutdownNow()
        }
        assertTmpMode1777(rootfsA)
        assertTmpMode1777(rootfsB)
        assertEquals(
            "only the fresh rootfs may have spawned chmod (the prepared one skips)",
            1, spawnCount(record)
        )
        assertTrue(
            "the single spawn must belong to rootfs B",
            record.readLines().first { it.isNotBlank() }.endsWith(File(rootfsB, "tmp").absolutePath)
        )
    }

    /**
     * Strict-path signal preserved: when the repair body runs and FAILS
     * (here: no chmod applet at all), the failure message still flows out —
     * this is the value PRootContainerRuntime.openTerminal maps to a hard
     * IllegalStateException on the session path (and merely logs on the
     * exec/daemon paths). The gate must not swallow or bypass it.
     */
    @Test
    fun repairFailureStillSurfacesTheStrictSignal() {
        // No bootstrap/bin at all → resolveBinary("chmod") points at a
        // missing file; the gate cannot mask this because a missing /tmp
        // (ENOENT) never skips.
        val filesDir = tmp.newFolder("files-strict")
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        val initializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))
        val rootfs = tmp.newFolder("rootfs")

        val failure = initializer.ensureGuestTmp(rootfs, "ws-strict")

        assertNotNull("a repair failure must still surface the strict-path signal", failure)
        assertTrue("failure must name the chmod step", failure!!.contains("chmod"))
    }
}
