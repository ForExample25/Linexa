package com.fem.linexa.service.installer

import android.app.Application
import android.content.pm.ApplicationInfo
import com.fem.linexa.core.logging.DiagnosticLogManager
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
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
 * F-5 (2026-08-25 audit) regression tests for
 * [GuestRootfsInitializer.ensureGuestDevShm] — the guest POSIX shm surface.
 *
 * The guest's `/dev` is the Android host's `/dev` (PRoot bind), which has no
 * `/dev/shm`; the runtime fixes this by binding a rootfs-owned, 1777-mode
 * real directory over the guest `/dev/shm` path. These tests pin the
 * directory-level half of that contract:
 *
 *  - created when absent (both `/dev` and `/dev/shm`),
 *  - mode exactly 1777 (`drwxrwxrwt`),
 *  - idempotent (a second run changes nothing),
 *  - a shipped real directory is kept,
 *  - **symlink safety**: a symlinked `/dev/shm` is REPLACED with a real
 *    directory (its target never touched), and a symlinked `/dev` makes the
 *    repair REFUSE rather than write through the link.
 *
 * Phase 2E-2: every test runs with [GuestRootfsInitializer.LstatTestSeam]
 * wired to the JVM's no-follow `unix:mode` reader (android.system.Os cannot
 * execute in plain JVM unit tests), so the steady-state skip gate decides on
 * REAL filesystem states; the matrix below pins skip-vs-repair per state by
 * counting chmod spawns through a recording applet.
 */
class GuestRootfsInitializerDevShmTest {

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

    private fun shmDir(): File = File(rootfs, "dev/shm")

    private fun assertShmMode1777() {
        val shm = shmDir()
        assertTrue("guest /dev/shm must exist as a directory", shm.isDirectory)
        assertTrue("guest /dev/shm must be a REAL directory, not a symlink",
            !Files.isSymbolicLink(shm.toPath()))
        val mode = Files.getAttribute(shm.toPath(), "unix:mode") as Number
        assertEquals("guest /dev/shm must be exactly 01777 (drwxrwxrwt)", 0x3FF, mode.toLong() and 0x3FF)
    }

    @Test
    fun freshRootfsGetsDevShmWithExact1777() {
        assertFalse("fresh rootfs has no /dev at all", File(rootfs, "dev").exists())

        assertNull(
            "ensureGuestDevShm must succeed on a fresh rootfs",
            initializer.ensureGuestDevShm(rootfs, "ws-1")
        )

        assertShmMode1777()
    }

    @Test
    fun existingDevShmIsKeptAndModeEnforced() {
        require(File(rootfs, "dev").mkdirs())
        require(shmDir().mkdirs())
        // wrong mode on purpose (0755 — no sticky bit, no group/other write)
        java.nio.file.Files.setPosixFilePermissions(
            shmDir().toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
        )
        // sanity: the starting state is NOT 1777
        val before = Files.getAttribute(shmDir().toPath(), "unix:mode") as Number
        assertTrue(
            "precondition: mode must not already be 1777",
            (before.toLong() and 0x3FF) != 0x3FFL
        )

        assertNull(initializer.ensureGuestDevShm(rootfs, "ws-1"))
        assertShmMode1777()
    }

    @Test
    fun isIdempotent() {
        assertNull(initializer.ensureGuestDevShm(rootfs, "ws-1"))
        val firstMode = Files.getAttribute(shmDir().toPath(), "unix:mode") as Number

        assertNull(initializer.ensureGuestDevShm(rootfs, "ws-1"))
        val secondMode = Files.getAttribute(shmDir().toPath(), "unix:mode") as Number

        assertEquals("a second run must not change the mode", firstMode.toLong(), secondMode.toLong())
        assertTrue("a second run must not remove the directory", shmDir().isDirectory)
    }

    @Test
    fun symlinkedDevShmIsReplacedWithRealDirectory() {
        require(File(rootfs, "dev").mkdirs())
        // A symlink at /dev/shm — the shape an unsafe or foreign rootfs could
        // ship. Its "target" is a real directory whose contents must survive.
        val outside = tmp.newFolder("target")
        File(outside, "precious.txt").writeText("must not be deleted")
        Files.createSymbolicLink(shmDir().toPath(), outside.toPath())

        assertNull(initializer.ensureGuestDevShm(rootfs, "ws-1"))

        assertShmMode1777()
        assertTrue(
            "the symlink's TARGET must never be touched",
            File(outside, "precious.txt").exists()
        )
    }

    @Test
    fun danglingSymlinkedDevShmIsReplaced() {
        require(File(rootfs, "dev").mkdirs())
        Files.createSymbolicLink(shmDir().toPath(), File(rootfs, "does/not/exist").toPath())

        assertNull(initializer.ensureGuestDevShm(rootfs, "ws-1"))
        assertShmMode1777()
    }

    @Test
    fun devShmRegularFileIsReplaced() {
        require(File(rootfs, "dev").mkdirs())
        shmDir().writeText("not a directory")

        assertNull(initializer.ensureGuestDevShm(rootfs, "ws-1"))
        assertShmMode1777()
    }

    @Test
    fun symlinkedDevIsRefusedNotTraversed() {
        // /dev itself a symlink pointing outside the rootfs — creating
        // dev/shm through it would write OUTSIDE the rootfs.
        val outside = tmp.newFolder("outside-dev")
        Files.createSymbolicLink(File(rootfs, "dev").toPath(), outside.toPath())

        val failure = initializer.ensureGuestDevShm(rootfs, "ws-1")

        assertNotNull("a symlinked /dev must be refused, not silently healed", failure)
        assertTrue("the refusal must be user-meaningful", failure!!.contains("refusing", ignoreCase = true))
        assertFalse(
            "nothing may be created through the symlink",
            File(outside, "shm").exists()
        )
        assertTrue(
            "the symlink itself must be left in place (caller's decision)",
            Files.isSymbolicLink(File(rootfs, "dev").toPath())
        )
    }

    @Test
    fun absoluteSymlinkedDevShmIsReplaced() {
        require(File(rootfs, "dev").mkdirs())
        // absolute target — even if it exists on this host, the repair must
        // replace the link with a real in-rootfs directory.
        Files.createSymbolicLink(shmDir().toPath(), File("/tmp").toPath())

        assertNull(initializer.ensureGuestDevShm(rootfs, "ws-1"))
        assertShmMode1777()
    }

    // ── Phase 2E-2: the steady-state lstat gate — correctness matrix ──────
    //
    // skip   = steady state: no chmod subprocess spawned at all.
    // repair = the existing body runs unchanged: chmod spawns, on-disk result
    //          identical to the pre-gate behavior.

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

    private fun preparedDevShmRootfs(): File {
        val fresh = tmp.newFolder("rootfs-prep")
        require(File(fresh, "dev").mkdirs())
        require(File(fresh, "dev/shm").mkdirs())
        realChmod(File(fresh, "dev/shm"), "1777")
        return fresh
    }

    /** Matrix A: already-1777 real directory (under a real /dev) → skip. */
    @Test
    fun matrixAAlready1777RealDirSkipsChmodSpawn() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = preparedDevShmRootfs()

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-A"))

        assertEquals(
            "steady state: the lstat gate must skip the busybox chmod spawn entirely",
            0, spawnCount(record)
        )
        // The recording initializer's rootfs differs from the class field;
        // assert the PREPARED rootfs's mode directly.
        val mode = Files.getAttribute(File(fresh, "dev/shm").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
    }

    /** Matrix B: 0755 directory → repair (one spawn, mode now 1777). */
    @Test
    fun matrixBMode0755DirIsRepaired() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-b")
        require(File(fresh, "dev").mkdirs())
        require(File(fresh, "dev/shm").mkdirs())
        realChmod(File(fresh, "dev/shm"), "0755")

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-B"))

        assertEquals(
            "wrong mode: the gate must NOT skip — the repair body runs and chmods",
            1, spawnCount(record)
        )
        val mode = Files.getAttribute(File(fresh, "dev/shm").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
    }

    /** Matrix C: mode drifts between calls → the next call repairs again. */
    @Test
    fun matrixCModeDriftBetweenCallsIsRepairedAgain() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-c")
        require(File(fresh, "dev").mkdirs())
        require(File(fresh, "dev/shm").mkdirs())

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-C"))
        assertEquals("first ensure on a fresh dir must repair", 1, spawnCount(record))

        realChmod(File(fresh, "dev/shm"), "0700")

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-C"))
        assertEquals("mode drifted after a prepared state: must repair again", 2, spawnCount(record))
        val mode = Files.getAttribute(File(fresh, "dev/shm").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
    }

    /** Matrix D: a regular file at /dev/shm → replaced + chmodded (current). */
    @Test
    fun matrixDRegularFileAtDevShmRunsFullRepair() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-d")
        require(File(fresh, "dev").mkdirs())
        File(fresh, "dev/shm").writeText("payload")

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-D"))

        assertEquals("a regular file at /dev/shm must run the full repair", 1, spawnCount(record))
        val mode = Files.getAttribute(File(fresh, "dev/shm").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
        assertFalse(File(fresh, "dev/shm").isFile)
    }

    /**
     * Matrix E: a symlink at /dev/shm → replaced, never chmod-through.
     * Unlike /tmp (no gate, chmod through the link), the /dev/shm gate
     * REPLACES the link: the target's own mode must remain untouched.
     */
    @Test
    fun matrixESymlinkedDevShmIsReplacedNeverChmodThrough() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-e")
        require(File(fresh, "dev").mkdirs())
        val target = tmp.newFolder("shm-target")
        realChmod(target, "0700")
        Files.createSymbolicLink(File(fresh, "dev/shm").toPath(), target.toPath())

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-E"))

        assertEquals("a symlinked /dev/shm must run the replace body (chmod included)", 1, spawnCount(record))
        assertTrue(
            "the symlink must be gone (real directory in its place)",
            !Files.isSymbolicLink(File(fresh, "dev/shm").toPath())
        )
        val mode = Files.getAttribute(File(fresh, "dev/shm").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
        val targetMode = Files.getAttribute(target.toPath(), "unix:mode") as Number
        assertEquals("the symlink TARGET must not have been chmodded through the link",
            0x1C0L, targetMode.toLong() and 0x3FF)
    }

    /** Matrix F: missing dir (ENOENT) → body runs, creates + chmods. */
    @Test
    fun matrixFMissingDevShmIsCreatedAndChmodded() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-f")
        assertFalse(File(fresh, "dev/shm").exists())

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-F"))

        assertEquals("ENOENT: the gate must fall through to the creation body", 1, spawnCount(record))
        val mode = Files.getAttribute(File(fresh, "dev/shm").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, mode.toLong() and 0x3FF)
    }

    /** Matrix G: null lstat → body runs (fail-safe), never skip. */
    @Test
    fun matrixGLstatUnavailableRunsTheBodyFailSafe() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = preparedDevShmRootfs()

        GuestRootfsInitializer.LstatTestSeam.override = { _ -> null }
        try {
            assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-G"))
        } finally {
            GuestRootfsInitializer.LstatTestSeam.override = null
        }
        assertEquals("a null lstat must never skip — the body must run", 1, spawnCount(record))
    }

    /** Matrix G (throwing variant): a throwing lstat is equally fail-safe. */
    @Test
    fun matrixGThrowingLstatRunsTheBodyFailSafe() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = preparedDevShmRootfs()

        GuestRootfsInitializer.LstatTestSeam.override = { _ ->
            throw IllegalStateException("simulated lstat explosion")
        }
        try {
            assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-G-throw"))
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
        require(File(fresh, "dev").mkdirs())
        require(File(fresh, "dev/shm").mkdirs())

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-H"))
        assertEquals("first ensure must repair", 1, spawnCount(record))

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-H"))
        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-H"))
        assertEquals("prepared steady state must not spawn again", 1, spawnCount(record))

        realChmod(File(fresh, "dev/shm"), "0755")
        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-H"))
        assertEquals("drifted dir must repair again on the same path", 2, spawnCount(record))
    }

    /** Matrix I: different rootfs paths are judged independently. */
    @Test
    fun matrixIIndependentRootfsPathsAreIndependentlyJudged() {
        val (recInitializer, record) = newRecordingInitializer()
        val rootfsA = preparedDevShmRootfs()
        val rootfsB = tmp.newFolder("rootfs-i-b")

        assertNull(recInitializer.ensureGuestDevShm(rootfsA, "ws-I"))
        assertEquals("prepared rootfs A must skip", 0, spawnCount(record))

        assertNull(recInitializer.ensureGuestDevShm(rootfsB, "ws-I"))
        assertEquals("fresh rootfs B must repair", 1, spawnCount(record))
        assertTrue(
            "the spawn must belong to rootfs B",
            record.readLines().first { it.isNotBlank() }
                .endsWith(File(rootfsB, "dev/shm").absolutePath)
        )
    }

    /** Matrix J: concurrent ensures on different paths do not interfere. */
    @Test
    fun matrixJConcurrentEnsuresOnDifferentPathsDoNotInterfere() {
        val (recInitializer, record) = newRecordingInitializer()
        val rootfsA = preparedDevShmRootfs()
        val rootfsB = tmp.newFolder("rootfs-j-b")

        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                pool.submit<String?> { recInitializer.ensureGuestDevShm(rootfsA, "ws-J") },
                pool.submit<String?> { recInitializer.ensureGuestDevShm(rootfsB, "ws-J") }
            )
            for (future in futures) {
                assertNull("concurrent ensure on its own rootfs must succeed", future.get(30, TimeUnit.SECONDS))
            }
        } finally {
            pool.shutdownNow()
        }
        val modeA = Files.getAttribute(File(rootfsA, "dev/shm").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, modeA.toLong() and 0x3FF)
        val modeB = Files.getAttribute(File(rootfsB, "dev/shm").toPath(), "unix:mode") as Number
        assertEquals(0x3FF, modeB.toLong() and 0x3FF)
        assertEquals(
            "only the fresh rootfs may have spawned chmod (the prepared one skips)",
            1, spawnCount(record)
        )
    }

    /**
     * Phase 2E-2 edge, pinned deliberately: the skip decision is on the LEAF.
     * A symlinked `/dev` whose dev/shm already resolves to a real 1777
     * directory SKIPS the whole body — including the /dev refusal gate —
     * because the skip performs zero writes. (Pre-gate code refused
     * unconditionally; that stricter-but-writeless refusal only ever guarded
     * repairs, which still run unchanged whenever the leaf is NOT prepared.)
     */
    @Test
    fun gateSkipBypassesTheDevRefusalOnlyWhenLeafIsAlreadyPrepared() {
        val (recInitializer, record) = newRecordingInitializer()
        val fresh = tmp.newFolder("rootfs-edge")
        val outside = tmp.newFolder("outside-dev")
        Files.createSymbolicLink(File(fresh, "dev").toPath(), outside.toPath())
        val resolvedShm = File(outside, "shm")
        require(resolvedShm.mkdirs())
        realChmod(resolvedShm, "1777")

        assertNull(recInitializer.ensureGuestDevShm(fresh, "ws-edge"))
        assertEquals("prepared leaf must skip (no spawn)", 0, spawnCount(record))
        assertTrue("the /dev symlink must be left in place (nothing to repair)",
            Files.isSymbolicLink(File(fresh, "dev").toPath()))

        // And when the leaf is NOT prepared, the refusal still bites. A
        // separate outside dir WITHOUT a shm leaf: sharing `outside` would
        // leave fresh2's leaf resolved to the SAME 1777 dir as fresh's, and
        // the skip is then the CORRECT verdict (the bind source resolves to
        // a prepared directory either way).
        val fresh2 = tmp.newFolder("rootfs-edge-2")
        val outside2 = tmp.newFolder("outside-dev-2")
        Files.createSymbolicLink(File(fresh2, "dev").toPath(), outside2.toPath())
        val refusal = recInitializer.ensureGuestDevShm(fresh2, "ws-edge-2")
        assertNotNull("a symlinked /dev with an unprepared leaf must still be refused", refusal)
        assertTrue(refusal!!.contains("refusing", ignoreCase = true))
    }
}
