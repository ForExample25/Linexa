package com.fem.linexa.service.runtime

import android.app.Application
import android.content.ContentResolver
import java.io.File
import java.nio.file.Files
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
 * RF-021 and RF-031 at the filesystem authority: crash-swap debris
 * (`rootfs.previous-*`), containment debris
 * (`.staging-rootfs.quarantine-*`), and the staged `config.yaml`.
 *
 * These are real-filesystem tests against a real [WorkspaceFileManager], so
 * the guards that matter — exact-name matching, age, an active staging tree,
 * and no-follow deletion of a symlinked debris name — are observed on disk,
 * not inferred from the source.
 */
class WorkspaceFileManagerInstallDebrisTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fileManager: WorkspaceFileManager
    private lateinit var workspace: File

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        val app = Mockito.mock(Application::class.java)
        Mockito.`when`(app.filesDir).thenReturn(filesDir)
        Mockito.`when`(app.cacheDir).thenReturn(tmp.newFolder("cache"))
        Mockito.`when`(app.contentResolver).thenReturn(Mockito.mock(ContentResolver::class.java))
        fileManager = WorkspaceFileManager(app)
        workspace = fileManager.getWorkspaceBasePath("ws")
        assertTrue(workspace.mkdirs())
    }

    private fun debris(name: String, payload: String = "payload"): File =
        File(workspace, name).apply {
            assertTrue("must create $name", mkdirs())
            File(this, "file").writeText(payload)
        }

    // ── RF-021: sweep ─────────────────────────────────────────────────────

    @Test
    fun sweepDeletesOnlyOldExactNameDebris() {
        // A live rootfs exists, so the sweep must not consume `previous` as a
        // restore candidate (that path is tested separately).
        assertTrue(fileManager.getRootfsPath("ws").mkdirs())
        val oldPrevious = debris("rootfs.previous-1000")
        val freshPrevious = debris("rootfs.previous-9000")
        val oldQuarantine = debris(".staging-rootfs.quarantine-1000")
        val freshQuarantine = debris(".staging-rootfs.quarantine-9000")
        val unrelated = debris("rootfs.previous-not-a-number")
        val lookalike = debris("rootfs.previous-1000-extra")

        val sweep = fileManager.sweepInstallDebris(
            "ws",
            minAgeMs = 5_000L,
            nowMs = 10_000L
        )

        assertFalse("old activation debris must go", oldPrevious.exists())
        assertFalse("old containment debris must go", oldQuarantine.exists())
        assertTrue("fresh activation debris is protected by the age guard", freshPrevious.exists())
        assertTrue("fresh containment debris is protected by the age guard", freshQuarantine.exists())
        assertTrue("a non-numeric name is not our debris", unrelated.exists())
        assertTrue("a name with trailing text is not our debris", lookalike.exists())
        assertEquals(1, sweep.deletedPrevious.size)
        assertEquals(1, sweep.deletedStagingQuarantine.size)
    }

    @Test
    fun sweepSkipsQuarantinesWhileAStagingTreeExists() {
        debris(".staging-rootfs.quarantine-1000")
        assertTrue("a live staging tree", fileManager.getStagingRootfsPath("ws").mkdirs())

        val sweep = fileManager.sweepInstallDebris("ws", nowMs = 10_000L)

        assertEquals(
            "a quarantine must not be reclaimed while a newer install owns the workspace",
            0,
            sweep.deletedStagingQuarantine.size
        )
        assertTrue(File(workspace, ".staging-rootfs.quarantine-1000").exists())
    }

    @Test
    fun sweepUnlinksASymlinkedDebrisNameWithoutTouchingItsTarget() {
        val target = tmp.newFolder("outside-target")
        File(target, "keep.txt").writeText("keep")
        val link = File(workspace, "rootfs.previous-1000").toPath()
        Files.createSymbolicLink(link, target.toPath())

        val sweep = fileManager.sweepInstallDebris("ws", nowMs = 10_000L)

        assertFalse("the debris name itself must be gone", Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue("the symlink target must be untouched", File(target, "keep.txt").isFile)
        assertEquals("the link is unlinking debris, not a restorable tree", 1, sweep.deletedPrevious.size)
    }

    // ── RF-021: activation-swap restore ───────────────────────────────────

    @Test
    fun restoreUsesTheNewestPreviousTreeOnlyWhenTheLiveRootfsIsAbsent() {
        val older = debris("rootfs.previous-1000", payload = "older")
        val newer = debris("rootfs.previous-2000", payload = "newer")

        val restored = fileManager.restorePreviousRootfsIfMissing("ws")

        assertNotNull("a missing live rootfs must be restored", restored)
        assertEquals("the newest tree wins", "newer", File(restored!!, "file").readText())
        assertTrue("the older tree is left for the sweep", older.exists())
        assertFalse("the consumed name must be gone", newer.exists())

        val live = fileManager.getRootfsPath("ws")
        live.mkdirs()
        assertNull(
            "an existing live rootfs must never be overwritten",
            fileManager.restorePreviousRootfsIfMissing("ws")
        )
        assertTrue("the older previous is still untouched by restore", older.exists())
    }

    @Test
    fun restoreDoesNotReplaceADanglingLiveSymlink() {
        val previous = debris("rootfs.previous-1000")
        val live = fileManager.getRootfsPath("ws")
        Files.createSymbolicLink(
            live.toPath(),
            File(tmp.root, "missing-target").toPath()
        )

        assertNull(
            "a dangling link still occupies the live name",
            fileManager.restorePreviousRootfsIfMissing("ws")
        )
        assertTrue("previous must stay intact", previous.exists())
    }

    // ── RF-031: staged config ─────────────────────────────────────────────

    @Test
    fun stagedConfigIsPromotedOnceTheLiveRootfsExists() {
        fileManager.getRootfsPath("ws").mkdirs()
        val staged = fileManager.getStagedConfigPath("ws")
        staged.writeText("workspace: Staged\n")

        assertTrue(fileManager.promoteStagedConfigIfPresent("ws"))

        assertEquals(
            "workspace: Staged\n",
            fileManager.getWorkspaceConfigPath("ws").readText()
        )
        assertFalse(staged.exists())
        assertFalse(
            "promotion is idempotent and reports nothing left to do",
            fileManager.promoteStagedConfigIfPresent("ws")
        )
    }

    @Test
    fun stagedConfigWithoutARootfsIsDiscarded() {
        val staged = fileManager.getStagedConfigPath("ws")
        staged.writeText("workspace: NeverActivated\n")

        assertFalse(fileManager.promoteStagedConfigIfPresent("ws"))

        assertFalse("an orphaned staged config must not survive", staged.exists())
        assertFalse(
            "and it must not appear as the workspace's real config",
            fileManager.getWorkspaceConfigPath("ws").exists()
        )
    }

    @Test
    fun aSymlinkedStagedConfigIsNeverPromotedOrFollowed() {
        fileManager.getRootfsPath("ws").mkdirs()
        val outside = File(tmp.root, "outside.yaml").apply { writeText("secret") }
        val staged = fileManager.getStagedConfigPath("ws").toPath()
        Files.createSymbolicLink(staged, outside.toPath())

        assertFalse(
            "NOFOLLOW: a symlink is not an installer-written staged config",
            fileManager.promoteStagedConfigIfPresent("ws")
        )

        assertFalse("nothing may be written to config.yaml", fileManager.getWorkspaceConfigPath("ws").exists())
        assertEquals("the symlink target must be untouched", "secret", outside.readText())
    }
}
