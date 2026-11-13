package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
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
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * R-5 / R-6 (2026-08-26 runtime parity audit) regression tests for
 * [GuestRootfsInitializer.ensureGuestRun] and
 * [GuestRootfsInitializer.ensureProcSnapshots].
 *
 * Mirrors the shape of [GuestRootfsInitializerDevShmTest] so the test
 * surface grows uniformly with each new guest-side self-heal.
 */
class GuestRootfsInitializerRunAndProcTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var initializer: GuestRootfsInitializer
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        val binDir = File(filesDir, "bootstrap/bin")
        require(binDir.mkdirs())
        val chmod = File(binDir, "chmod")
        chmod.writeText("#!/bin/sh\nexec /bin/chmod \"$@\"\n")
        require(chmod.setExecutable(true))

        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder("native").absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        initializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))

        rootfs = tmp.newFolder("rootfs")

        // Phase 2E-2: substitute android.system.Os.lstat (unavailable in JVM
        // unit tests) with the java.nio NOFOLLOW equivalent — same st_mode
        // semantics (dir 41777, symlink 120777, ENOENT → null).
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

    @After
    fun resetLstatSeam() {
        GuestRootfsInitializer.LstatTestSeam.override = null
    }

    /** Host-side chmod helper so a test can set a known starting mode. */
    private fun realChmod(target: File, mode: String) {
        val p = ProcessBuilder("/bin/chmod", mode, target.absolutePath)
            .redirectErrorStream(true)
            .start()
        assertEquals("host chmod $mode must succeed", 0, p.waitFor())
    }

    /** Recording initializer: the fake applet logs every spawn, then chmods. */
    private fun newRecordingInitializer(): Pair<GuestRootfsInitializer, File> {
        val filesDir = tmp.newFolder("files-rec")
        val binDir = File(filesDir, "bootstrap/bin")
        require(binDir.mkdirs())
        val record = File(filesDir, "chmod-spawns.log")
        val escaped = record.absolutePath.replace("'", "'\\''")
        val chmod = File(binDir, "chmod")
        chmod.writeText(
            // echo, not printf: the production bootstrapEnv() restricts the
            // child's PATH to binDir only, and /bin/sh (Android mksh) has no
            // printf builtin — printf resolved to nothing, the log stayed
            // empty, and every repair looked like a skip. echo is a
            // mandated builtin and writes the same one-line-per-spawn.
            "#!/bin/sh\necho \"\$*\" >> '$escaped'\nexec /bin/chmod \"\$@\"\n"
        )
        require(chmod.setExecutable(true))

        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder().absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder())
        return Pair(GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app)), record)
    }

    private fun spawnCount(record: File): Int =
        if (record.exists()) record.readLines().count { it.isNotBlank() } else 0

    private fun preparedRunRootfs(): File {
        val fresh = tmp.newFolder("rootfs-prep")
        require(File(fresh, "run").mkdirs())
        realChmod(File(fresh, "run"), "1777")
        return fresh
    }

    // ── ensureGuestRun ─────────────────────────────────────────────────

    private fun runDir(): File = File(rootfs, "run")

    private fun assertRunMode1777() {
        val run = runDir()
        assertTrue("guest /run must exist as a directory", run.isDirectory)
        assertTrue("guest /run must be a REAL directory, not a symlink",
            !Files.isSymbolicLink(run.toPath()))
        val mode = Files.getAttribute(run.toPath(), "unix:mode") as Number
        assertEquals("guest /run must be exactly 01777 (drwxrwxrwt)", 0x3FF, mode.toLong() and 0x3FF)
    }

    @Test
    fun freshRootfsGetsRunWithExact1777() {
        assertFalse("fresh rootfs has no /run at all", File(rootfs, "run").exists())

        assertNull(
            "ensureGuestRun must succeed on a fresh rootfs",
            initializer.ensureGuestRun(rootfs, "ws-run-1")
        )

        assertRunMode1777()
    }

    @Test
    fun existingRunIsKeptAndModeEnforced() {
        require(runDir().mkdirs())
        // Wrong mode on purpose (0755 — no sticky bit, no group/other write).
        java.nio.file.Files.setPosixFilePermissions(
            runDir().toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
        )

        assertNull(
            "ensureGuestRun must succeed on a rootfs with an existing /run",
            initializer.ensureGuestRun(rootfs, "ws-run-2")
        )

        assertRunMode1777()
    }

    @Test
    fun symlinkedRunIsReplacedWithRealDirectory() {
        // A symlinked /run pointing outside the rootfs (Debian's historical
        // /var/run -> /run symlink). ensureGuestRun must REPLACE the symlink,
        // never write through it.
        val targetOutside = tmp.newFolder("outside-target")
        Files.createSymbolicLink(runDir().toPath(), targetOutside.toPath())

        assertNull(
            "ensureGuestRun must replace a symlinked /run",
            initializer.ensureGuestRun(rootfs, "ws-run-3")
        )

        // The symlink target is untouched.
        assertTrue("the symlink target must not be removed",
            targetOutside.exists())
        // /run is now a real directory with the right mode.
        assertRunMode1777()
    }

    @Test
    fun ensureGuestRunIsIdempotent() {
        // Two consecutive runs must produce the same result (no double-
        // chmod, no errors on the second run).
        initializer.ensureGuestRun(rootfs, "ws-run-4")
        val firstMode = Files.getAttribute(runDir().toPath(), "unix:mode") as Number

        initializer.ensureGuestRun(rootfs, "ws-run-4")
        val secondMode = Files.getAttribute(runDir().toPath(), "unix:mode") as Number

        assertEquals("ensureGuestRun must be idempotent on mode",
            firstMode.toLong(), secondMode.toLong())
    }

    // ── Phase 2E-2: the steady-state lstat gate — correctness matrix ──────
    //
    // skip   = steady state: no chmod subprocess spawned at all.
    // repair = the existing body runs unchanged: chmod spawns, on-disk result
    //          identical to the pre-gate behavior.

    /** Matrix A: already-1777 real directory → skip (zero spawns). */
    @Test
    fun matrixAAlready1777RealDirSkipsChmodSpawn() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = preparedRunRootfs()

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-A"))

        assertEquals(
            "steady state: the lstat gate must skip the busybox chmod spawn entirely",
            0, spawnCount(record)
        )
    }

    /** Matrix B: 0755 directory → repair (one spawn, mode now 1777). */
    @Test
    fun matrixBMode0755DirIsRepaired() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-b")
        require(File(fresh, "run").mkdirs())
        realChmod(File(fresh, "run"), "0755")

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-B"))

        assertEquals(
            "wrong mode: the gate must NOT skip — the repair body runs and chmods",
            1, spawnCount(record)
        )
        val mode = Files.getAttribute(File(fresh, "run").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
    }

    /** Matrix C: mode drifts between calls → the next call repairs again. */
    @Test
    fun matrixCModeDriftBetweenCallsIsRepairedAgain() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-c")
        require(File(fresh, "run").mkdirs())

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-C"))
        assertEquals("first ensure on a fresh dir must repair", 1, spawnCount(record))

        realChmod(File(fresh, "run"), "0700")

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-C"))
        assertEquals("mode drifted after a prepared state: must repair again", 2, spawnCount(record))
        val mode = Files.getAttribute(File(fresh, "run").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
    }

    /** Matrix D: a regular file at /run → replaced + chmodded (current). */
    @Test
    fun matrixDRegularFileAtRunRunsFullRepair() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-d")
        File(fresh, "run").writeText("payload")

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-D"))

        assertEquals("a regular file at /run must run the full repair", 1, spawnCount(record))
        val mode = Files.getAttribute(File(fresh, "run").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
        assertFalse(File(fresh, "run").isFile)
    }

    /**
     * Matrix E: a symlink at /run → replaced, never chmod-through. The gate
     * cannot skip a symlink (lstat type S_IFLNK) and the body REPLACES it.
     */
    @Test
    fun matrixESymlinkedRunIsReplacedNeverChmodThrough() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-e")
        val target = tmp.newFolder("run-target")
        realChmod(target, "0700")
        Files.createSymbolicLink(File(fresh, "run").toPath(), target.toPath())

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-E"))

        assertEquals("a symlinked /run must run the replace body (chmod included)", 1, spawnCount(record))
        assertTrue(
            "the symlink must be gone (real directory in its place)",
            !Files.isSymbolicLink(File(fresh, "run").toPath())
        )
        val mode = Files.getAttribute(File(fresh, "run").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
        val targetMode = Files.getAttribute(target.toPath(), "unix:mode") as Number
        assertEquals("the symlink TARGET must not have been chmodded through the link",
            0x1C0L, targetMode.toLong() and 0x3FF)
    }

    /** Matrix F: missing dir (ENOENT) → body runs, creates + chmods. */
    @Test
    fun matrixFMissingRunIsCreatedAndChmodded() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-f")
        assertFalse(File(fresh, "run").exists())

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-F"))

        assertEquals("ENOENT: the gate must fall through to the creation body", 1, spawnCount(record))
        val mode = Files.getAttribute(File(fresh, "run").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
    }

    /** Matrix G: null lstat → body runs (fail-safe), never skip. */
    @Test
    fun matrixGLstatUnavailableRunsTheBodyFailSafe() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = preparedRunRootfs()

        GuestRootfsInitializer.LstatTestSeam.override = { _ -> null }
        try {
            assertNull(recInitializer.ensureGuestRun(fresh, "ws-G"))
        } finally {
            GuestRootfsInitializer.LstatTestSeam.override = null
        }
        assertEquals("a null lstat must never skip — the body must run", 1, spawnCount(record))
    }

    /** Matrix G (throwing variant): a throwing lstat is equally fail-safe. */
    @Test
    fun matrixGThrowingLstatRunsTheBodyFailSafe() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = preparedRunRootfs()

        GuestRootfsInitializer.LstatTestSeam.override = { _ ->
            throw IllegalStateException("simulated lstat explosion")
        }
        try {
            assertNull(recInitializer.ensureGuestRun(fresh, "ws-G-throw"))
        } finally {
            GuestRootfsInitializer.LstatTestSeam.override = null
        }
        assertEquals("a throwing lstat must never skip — the body must run", 1, spawnCount(record))
    }

    /** Matrix H: repeated ensures on the same path — idempotence. */
    @Test
    fun matrixHRepeatedEnsuresOnTheSamePathStayCorrect() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-h")
        require(File(fresh, "run").mkdirs())

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-H"))
        assertEquals("first ensure must repair", 1, spawnCount(record))

        assertNull(recInitializer.ensureGuestRun(fresh, "ws-H"))
        assertNull(recInitializer.ensureGuestRun(fresh, "ws-H"))
        assertEquals("prepared steady state must not spawn again", 1, spawnCount(record))

        realChmod(File(fresh, "run"), "0755")
        assertNull(recInitializer.ensureGuestRun(fresh, "ws-H"))
        assertEquals("drifted dir must repair again on the same path", 2, spawnCount(record))
    }

    /** Matrix I: different rootfs paths are judged independently. */
    @Test
    fun matrixIIndependentRootfsPathsAreIndependentlyJudged() {
        val (recInitializer, record) = newRecordingInitializer()
        val rootfsA = preparedRunRootfs()
        val rootfsB = tmp.newFolder("rootfs-i-b")

        assertNull(recInitializer.ensureGuestRun(rootfsA, "ws-I"))
        assertEquals("prepared rootfs A must skip", 0, spawnCount(record))

        assertNull(recInitializer.ensureGuestRun(rootfsB, "ws-I"))
        assertEquals("fresh rootfs B must repair", 1, spawnCount(record))
        assertTrue(
            "the spawn must belong to rootfs B",
            record.readLines().first { it.isNotBlank() }
                .endsWith(File(rootfsB, "run").absolutePath)
        )
    }

    /** Matrix J: concurrent ensures on different paths do not interfere. */
    @Test
    fun matrixJConcurrentEnsuresOnDifferentPathsDoNotInterfere() {
        val (recInitializer, record) = newRecordingInitializer()
        val rootfsA = preparedRunRootfs()
        val rootfsB = tmp.newFolder("rootfs-j-b")

        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                pool.submit<String?> { recInitializer.ensureGuestRun(rootfsA, "ws-J") },
                pool.submit<String?> { recInitializer.ensureGuestRun(rootfsB, "ws-J") }
            )
            for (future in futures) {
                assertNull("concurrent ensure on its own rootfs must succeed", future.get(30, TimeUnit.SECONDS))
            }
        } finally {
            pool.shutdownNow()
        }
        val modeA = Files.getAttribute(File(rootfsA, "run").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, modeA.toLong() and 0x3FF)
        val modeB = Files.getAttribute(File(rootfsB, "run").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, modeB.toLong() and 0x3FF)
        assertEquals(
            "only the fresh rootfs may have spawned chmod (the prepared one skips)",
            1, spawnCount(record)
        )
    }

    /**
     * Strict-path signal preserved: when the repair body runs and FAILS
     * (here: no chmod applet at all), the failure message still flows out —
     * the value PRootContainerRuntime.openTerminal maps to a hard
     * IllegalStateException on the session path. The gate must not swallow it.
     */
    @Test
    fun repairFailureStillSurfacesTheStrictSignal() {
        val filesDir = tmp.newFolder("files-strict")
        val app = Mockito.mock(Application::class.java)
        val appInfo = Mockito.mock(ApplicationInfo::class.java)
        appInfo.nativeLibraryDir = tmp.newFolder().absolutePath
        Mockito.`when`(app.applicationInfo).thenReturn(appInfo)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder())
        val bareInitializer = GuestRootfsInitializer(BootstrapManager(app), DiagnosticLogManager(app))
        val fresh = tmp.newFolder("rootfs-strict")

        val failure = bareInitializer.ensureGuestRun(fresh, "ws-strict")

        assertNotNull("a repair failure must still surface the strict-path signal", failure)
        assertTrue("failure must name the chmod step", failure!!.contains("chmod"))
    }

    // ── ensureProcSnapshots ────────────────────────────────────────────

    @Test
    fun ensureProcSnapshotsWritesAllExpectedFiles() {
        require(File(rootfs, "proc").mkdirs())

        assertNull(
            "ensureProcSnapshots must succeed on a rootfs with /proc",
            initializer.ensureProcSnapshots(rootfs, "ws-proc-1")
        )

        val procDir = File(rootfs, "proc")
        for ((name, _) in GuestRootfsInitializer.PROC_SNAPSHOT_CONTENTS) {
            val snap = File(procDir, ".$name")
            assertTrue("snapshot for $name must exist at ${snap.absolutePath}",
                snap.isFile)
        }
    }

    @Test
    fun ensureProcSnapshotsWidensAndRestoresAReadOnlyProcDirectory() {
        // Arch 3.24 / Fedora 44 shape: the rootfs archive restores /proc as
        // 0555, and a literal write there fails with EACCES before this fix.
        val procDir = File(rootfs, "proc").apply { mkdirs() }
        val original = Files.getPosixFilePermissions(procDir.toPath())
        val readOnly = original - PosixFilePermission.OWNER_WRITE
        Files.setPosixFilePermissions(procDir.toPath(), readOnly)
        try {
            val probe = File(procDir, ".write-probe")
            val canWrite = try {
                probe.writeText("probe")
                probe.delete()
                true
            } catch (_: Exception) {
                false
            }
            Assume.assumeFalse("filesystem/user ignores read-only directories", canWrite)

            assertNull(
                "a read-only /proc must not fail snapshot provisioning",
                initializer.ensureProcSnapshots(rootfs, "ws-proc-readonly")
            )
            assertEquals(
                "the archive's /proc mode must be restored exactly",
                readOnly,
                Files.getPosixFilePermissions(procDir.toPath())
            )
            for ((name, _) in GuestRootfsInitializer.PROC_SNAPSHOT_CONTENTS) {
                assertTrue(
                    "snapshot for $name must exist after a read-only-directory install",
                    File(procDir, ".$name").isFile
                )
            }
            assertFalse(
                "no snapshot temporary may remain",
                File(procDir, ".stat.tmp").exists()
            )
        } finally {
            Files.setPosixFilePermissions(procDir.toPath(), original)
        }
    }

    @Test
    fun ensureProcSnapshotsIsIdempotentAndOverwrites() {
        require(File(rootfs, "proc").mkdirs())
        val procDir = File(rootfs, "proc")

        // Pre-write a bogus snapshot for stat.
        File(procDir, ".stat").writeText("bogus-content\n")

        initializer.ensureProcSnapshots(rootfs, "ws-proc-2")

        val statContent = File(procDir, ".stat").readText()
        assertFalse(
            "the bogus pre-existing .stat content must be overwritten",
            statContent.contains("bogus-content")
        )
        assertTrue(
            "the new .stat content must be the real snapshot (start with 'cpu ')",
            statContent.startsWith("cpu ")
        )
    }

    @Test
    fun ensureProcSnapshotsFailsOpenWhenRootfsHasNoProcDir() {
        // No /proc directory at all — fail-open (return null), the
        // PRootLauncher.procBindOvers helper handles the "no snapshot
        // file" case at runtime.
        assertNull(
            "ensureProcSnapshots must succeed (skip) when /proc is absent",
            initializer.ensureProcSnapshots(rootfs, "ws-proc-3")
        )
    }

    @Test
    fun ensureProcSnapshotsRefusesASymlinkedProcAndNeverTouchesItsTarget() {
        // A crafted archive could ship /proc as a symlink to another
        // workspace (or any app-private directory). Widening or writing
        // through that link would mutate a tree outside this rootfs, so the
        // snapshot pass must skip it entirely.
        val target = tmp.newFolder("proc-symlink-target")
        val targetMode = Files.getPosixFilePermissions(target.toPath())
        Files.createSymbolicLink(File(rootfs, "proc").toPath(), target.toPath())

        assertNull(
            "a symlinked /proc is skipped, not a provisioning failure",
            initializer.ensureProcSnapshots(rootfs, "ws-proc-symlink")
        )

        assertEquals(
            "the symlink target's mode must be untouched",
            targetMode,
            Files.getPosixFilePermissions(target.toPath())
        )
        assertFalse(
            "no snapshot may be written through the symlink",
            File(target, ".stat").exists()
        )
        assertTrue(
            "the symlink itself must remain a symlink",
            Files.isSymbolicLink(File(rootfs, "proc").toPath())
        )
    }

    @Test
    fun procSnapshotContentsKeysAreSubsetOfProcBindOverNames() {
        // The launcher is the source of truth for which bind-overs are
        // emitted. The snapshot writer (this class) must NOT write files
        // the launcher will never bind — they'd be dead weight in the
        // rootfs.
        val launcherNames = com.fem.linexa.service.runtime.container.PRootLauncher.PROC_BIND_OVER_NAMES.toSet()
        val snapshotNames = GuestRootfsInitializer.PROC_SNAPSHOT_CONTENTS.map { it.first }.toSet()
        assertTrue(
            "snapshot names ($snapshotNames) must be a subset of launcher's PROC_BIND_OVER_NAMES ($launcherNames)",
            launcherNames.containsAll(snapshotNames)
        )
    }

    @Test
    fun procStatSnapshotContainsCpuLineAndPerCoreLines() {
        // The /proc/stat snapshot must include the aggregate `cpu` line
        // plus at least 8 per-core `cpu0`..`cpu7` lines so `nproc` (which
        // parses /proc/stat for `_SC_NPROCESSORS_ONLN`) reads a non-zero
        // count, and btop (which iterates per-core bars) finds the expected
        // shape.
        require(File(rootfs, "proc").mkdirs())
        initializer.ensureProcSnapshots(rootfs, "ws-proc-4")
        val stat = File(rootfs, "proc/.stat").readText()
        assertTrue(".stat must contain aggregate cpu line", stat.startsWith("cpu "))
        for (i in 0 until 8) {
            assertTrue(".stat must contain cpu$i line", stat.contains("\ncpu$i "))
        }
        // btime is what `ps` reads to compute process ages — must be present.
        assertTrue(".stat must contain btime", stat.contains("\nbtime "))
    }

    @Test
    fun procVersionSnapshotMatchesSpoofedKernelRelease() {
        // The /proc/version snapshot's release token must match
        // PRootLauncher.SPOOFED_KERNEL_RELEASE so `uname -r` and
        // `cat /proc/version` agree.
        require(File(rootfs, "proc").mkdirs())
        initializer.ensureProcSnapshots(rootfs, "ws-proc-5")
        val version = File(rootfs, "proc/.version").readText()
        assertTrue(
            "/proc/version snapshot must reference the spoofed kernel release",
            version.contains(com.fem.linexa.service.runtime.container.PRootLauncher.SPOOFED_KERNEL_RELEASE)
        )
    }
}
