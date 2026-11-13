package com.fem.linexa.core.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * UI/UX Phase 4 — the app log's file-set identity and the read/clear half.
 *
 * `FileLogSink` itself is not tested here: it writes to `android.util.Log`, which is
 * stubbed to throw in plain JVM unit tests, and there is no Robolectric in this repo
 * (§1.124(a)). That is exactly why the *names* and the *lock* were lifted into
 * [AppLogFiles] — the half that decides which files are "the app log" is now pure and
 * can be asserted, and [AppLogStore] takes a plain directory so it runs against a
 * temp folder.
 *
 * The rotation arithmetic gets its own test because it is the specific way a clear
 * path silently under-deletes: the sink's loop produces suffixes up to
 * `MAX_GENERATIONS` inclusive, so a `1 until MAX_GENERATIONS` range in [names] would
 * leave the oldest file behind on every clear while every other test stayed green.
 */
class AppLogFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun theNameSetCoversTheActiveFileAndEveryRotatedGeneration() {
        val names = AppLogFiles.names()
        assertEquals(
            "active file + MAX_GENERATIONS archives",
            AppLogFiles.MAX_GENERATIONS + 1,
            names.size
        )
        assertEquals("the active file must come first", AppLogFiles.LOG_FILE_NAME, names.first())
        (1..AppLogFiles.MAX_GENERATIONS).forEach { generation ->
            assertTrue(
                "generation $generation must be owned",
                names.contains("${AppLogFiles.LOG_FILE_NAME}.$generation")
            )
        }
    }

    @Test
    fun theHighestRotatedGenerationIsIncluded() {
        // The off-by-one this guards: the sink renames .n -> .n+1 for
        // n = MAX_GENERATIONS-1 downTo 1, so `linexa.log.MAX_GENERATIONS` exists on
        // disk. An exclusive range here would orphan ~512 KiB permanently.
        assertTrue(
            AppLogFiles.isAppLogFile("${AppLogFiles.LOG_FILE_NAME}.${AppLogFiles.MAX_GENERATIONS}")
        )
    }

    @Test
    fun ownershipIsExactAndNotAPrefixMatch() {
        assertTrue(AppLogFiles.isAppLogFile("linexa.log"))
        assertTrue(AppLogFiles.isAppLogFile("linexa.log.1"))
        // Files that a prefix match would wrongly claim. Clearing must only ever
        // delete files this object actually manages.
        assertFalse(AppLogFiles.isAppLogFile("linexa.log.bak"))
        assertFalse(AppLogFiles.isAppLogFile("linexa.log.gz"))
        assertFalse(AppLogFiles.isAppLogFile("linexa.log.${AppLogFiles.MAX_GENERATIONS + 1}"))
        assertFalse(AppLogFiles.isAppLogFile("fixup.log"))
        assertFalse(AppLogFiles.isAppLogFile(""))
    }

    @Test
    fun anEmptyDirectoryReportsNothingRatherThanZeroBytesOfSomething() {
        val store = AppLogStore(temp.newFolder("logs"))
        assertEquals(0, store.fileCount())
        assertEquals(0L, store.totalSizeBytes())
        assertEquals("clearing nothing reclaims nothing", 0L, store.clear())
    }

    @Test
    fun sizeAndCountOnlyIncludeOwnedFiles() {
        val dir = temp.newFolder("logs")
        File(dir, "linexa.log").writeText("a".repeat(100))
        File(dir, "linexa.log.1").writeText("b".repeat(50))
        // Not ours: a workspace-style log name and an unrelated drop-in.
        File(dir, "fixup.log").writeText("c".repeat(9_999))
        File(dir, "notes.txt").writeText("d".repeat(9_999))

        val store = AppLogStore(dir)
        assertEquals(2, store.fileCount())
        assertEquals(150L, store.totalSizeBytes())
    }

    @Test
    fun clearRemovesEveryOwnedFileAndReportsWhatItReclaimed() {
        val dir = temp.newFolder("logs")
        File(dir, "linexa.log").writeText("x".repeat(10))
        (1..AppLogFiles.MAX_GENERATIONS).forEach { generation ->
            File(dir, "linexa.log.$generation").writeText("y".repeat(5))
        }
        val expected = 10L + 5L * AppLogFiles.MAX_GENERATIONS

        val store = AppLogStore(dir)
        assertEquals(expected, store.clear())
        assertEquals("nothing owned may remain", 0, store.fileCount())
        assertEquals(0L, store.totalSizeBytes())
    }

    @Test
    fun clearNeverTouchesFilesItDoesNotOwn() {
        // The safety property: a workspace's own install logs (§1.53) are diagnostic
        // evidence and are preserved on purpose. Enforced by construction — the
        // store only ever visits names AppLogFiles accepts — so this asserts the
        // construction, including the case where the two live side by side.
        val dir = temp.newFolder("logs")
        File(dir, "linexa.log").writeText("app log")
        val evidence = File(dir, "fixup.log").apply { writeText("install evidence") }
        val unrelated = File(dir, "linexa.log.bak").apply { writeText("backup") }

        AppLogStore(dir).clear()

        assertFalse(File(dir, "linexa.log").exists())
        assertTrue("workspace install evidence must survive", evidence.exists())
        assertEquals("install evidence", evidence.readText())
        assertTrue("a non-owned lookalike must survive", unrelated.exists())
    }

    @Test
    fun aZeroLengthLogStillCountsAsAFileToClear() {
        // The distinction the Diagnostics row depends on: an empty-but-present file
        // is still something Clear removes, so it must not read as "no logs yet".
        val dir = temp.newFolder("logs")
        File(dir, "linexa.log").createNewFile()

        val store = AppLogStore(dir)
        assertEquals(1, store.fileCount())
        assertEquals(0L, store.totalSizeBytes())
        assertEquals("nothing to reclaim, but the file goes", 0L, store.clear())
        assertEquals(0, store.fileCount())
    }

    @Test
    fun aDirectoryNamedLikeALogFileIsNotCounted() {
        // `isFile` rather than `exists`: a directory that happens to share the name
        // would otherwise be reported as part of the log and attempted for deletion.
        val dir = temp.newFolder("logs")
        File(dir, "linexa.log").mkdirs()

        val store = AppLogStore(dir)
        assertEquals(0, store.fileCount())
        assertEquals(0L, store.totalSizeBytes())
    }

    @Test
    fun aMissingDirectoryIsReadableWithoutThrowing() {
        // The store may be constructed before Logger.init has created the folder, and
        // a settings screen must not crash on a fresh install.
        val store = AppLogStore(File(temp.root, "never-created"))
        assertEquals(0, store.fileCount())
        assertEquals(0L, store.totalSizeBytes())
        assertEquals(0L, store.clear())
    }

    @Test
    fun theDisplayPathIsTheDirectoryItActuallyReads() {
        val dir = temp.newFolder("logs")
        assertEquals(dir.absolutePath, AppLogStore(dir).displayPath())
    }
}
