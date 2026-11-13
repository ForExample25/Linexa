package com.fem.linexa.ui.screens.filemanager

import com.fem.linexa.domain.filemanager.FileInfo
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FM-1 — search and ordering rules for the File Manager browse list.
 *
 * The two contracts this protects:
 *  1. [FileSortCriterion.NATIVE] never reorders a listing (Linux is the
 *     guest's `LC_ALL=C` output).
 *  2. Explicit sorts are deterministic, directories-first, and put missing
 *     metadata last instead of treating it as zero.
 */
class FileListPresentationTest {

    private fun file(
        name: String,
        directory: Boolean = false,
        size: Long = 0L,
        modified: String = "Aug 10 19:31",
        hasByteSize: Boolean = true,
        path: String = "/$name"
    ) = FileInfo(
        name = name,
        path = path,
        isDirectory = directory,
        size = size,
        permissions = "rw",
        lastModified = modified,
        hasByteSize = hasByteSize
    )

    private val native = FileListSort()
    private val now = LocalDateTime.of(2026, 8, 10, 12, 0)

    private fun visible(
        files: List<FileInfo>,
        query: String = "",
        sort: FileListSort = native,
        linux: Boolean = true
    ) = FileListPresentation.filterAndSort(files, query, sort, linux, now)

    // ── search ──────────────────────────────────────────────────────────────

    @Test
    fun emptyQueryReturnsTheNativeOrderUntouched() {
        val files = listOf(file("b.txt"), file("A.txt"), file(".hidden"), file("a.txt"))
        assertEquals(files, visible(files))
    }

    @Test
    fun whitespaceOnlyQueryIsTreatedAsEmpty() {
        val files = listOf(file("b.txt"), file("A.txt"))
        assertEquals(files, visible(files, query = "   "))
    }

    @Test
    fun searchIsCaseInsensitiveAndRanked() {
        val files = listOf(
            file("footnotes.md"),
            file("my notes.txt"),
            file("notesarchive"),
            file("notes.txt"),
            file("Notes")
        )
        assertEquals(
            listOf("Notes", "notesarchive", "notes.txt", "my notes.txt", "footnotes.md"),
            visible(files, query = "NOTES").map { it.name }
        )
    }

    @Test
    fun aQueryThatMatchesNothingYieldsAnEmptyList() {
        val files = listOf(file("alpha.txt"), file("beta.txt"))
        assertTrue(visible(files, query = "gamma").isEmpty())
    }

    @Test
    fun nativeOrderSurvivesWithinTheSameRankTier() {
        val files = listOf(file("ac.txt"), file("ab.txt"))
        // Both are prefix matches; a filter must not impose its own order.
        assertEquals(listOf("ac.txt", "ab.txt"), visible(files, query = "a").map { it.name })
    }

    @Test
    fun rankingDominatesDirectionAndSortCriterion() {
        val files = listOf(file("ba.txt"), file("ab.txt"))
        val result = visible(
            files,
            query = "a",
            sort = FileListSort(FileSortCriterion.NAME, FileSortDirection.DESCENDING)
        )
        assertEquals(listOf("ab.txt", "ba.txt"), result.map { it.name })
    }

    @Test
    fun hiddenFilesRemainSearchable() {
        val files = listOf(file(".bashrc"), file("bash.txt"))
        assertEquals(listOf(".bashrc"), visible(files, query = "bashr").map { it.name })
    }

    @Test
    fun searchCombinesWithAnExplicitSort() {
        val files = listOf(file("notes-b.txt"), file("notes-a.txt"))
        val result = visible(
            files,
            query = "notes",
            sort = FileListSort(FileSortCriterion.NAME)
        )
        assertEquals(listOf("notes-a.txt", "notes-b.txt"), result.map { it.name })
    }

    // ── name ordering ───────────────────────────────────────────────────────

    @Test
    fun linuxNameSortIsBytewiseCaseSensitiveWithDotfilesFirst() {
        val files = listOf(
            file("zeta"), file("Alpha"), file(".hidden"), file("alpha"),
            file("Beta"), file("beta"), file("Zeta")
        )
        assertEquals(
            listOf(".hidden", "Alpha", "Beta", "Zeta", "alpha", "beta", "zeta"),
            visible(files, sort = FileListSort(FileSortCriterion.NAME)).map { it.name }
        )
    }

    @Test
    fun androidNameSortKeepsTheExistingCaseInsensitivePrimary() {
        val files = listOf(
            file("zeta"), file("Alpha"), file(".hidden"), file("alpha"),
            file("Beta"), file("beta"), file("Zeta")
        )
        assertEquals(
            listOf(".hidden", "Alpha", "alpha", "Beta", "beta", "Zeta", "zeta"),
            visible(files, sort = FileListSort(FileSortCriterion.NAME), linux = false)
                .map { it.name }
        )
    }

    @Test
    fun androidDescendingNameKeepsTheCaseFallbackAscending() {
        val files = listOf(file("a.txt"), file("A.txt"), file("b.txt"))
        val result = visible(
            files,
            sort = FileListSort(FileSortCriterion.NAME, FileSortDirection.DESCENDING),
            linux = false
        )
        assertEquals(listOf("b.txt", "A.txt", "a.txt"), result.map { it.name })
    }

    @Test
    fun bytewiseComparisonMatchesTheCLocale() {
        assertTrue(
            FileListPresentation.compareBytes(
                "A".toByteArray(),
                "a".toByteArray()
            ) < 0
        )
        assertTrue(
            FileListPresentation.compareBytes(
                ".hidden".toByteArray(),
                "A".toByteArray()
            ) < 0
        )
        assertTrue(
            FileListPresentation.compareBytes(
                "z".toByteArray(),
                "za".toByteArray()
            ) < 0
        )
    }

    // ── directories-first ───────────────────────────────────────────────────

    @Test
    fun explicitSortsKeepDirectoriesFirstInBothDirections() {
        val files = listOf(
            file("zdir", directory = true),
            file("adir", directory = true),
            file("a.txt"),
            file("z.txt")
        )
        for (direction in FileSortDirection.entries) {
            val result = visible(
                files,
                sort = FileListSort(FileSortCriterion.NAME, direction)
            )
            assertEquals(
                "directories stay first regardless of direction",
                listOf(true, true),
                result.take(2).map { it.isDirectory }
            )
            assertTrue("files follow the directories", result.drop(2).none { it.isDirectory })
        }
    }

    @Test
    fun nativeSortLeavesDirectoriesWhereTheSourcePutThem() {
        val files = listOf(file("a.txt"), file("zdir", directory = true), file("b.txt"))
        assertEquals(files, visible(files))
    }

    // ── size ────────────────────────────────────────────────────────────────

    @Test
    fun sizeSortPutsUnknownSizesLastInBothDirections() {
        val files = listOf(
            file("unknown.bin", size = 0, hasByteSize = false),
            file("big.bin", size = 100),
            file("small.bin", size = 50)
        )
        assertEquals(
            listOf("small.bin", "big.bin", "unknown.bin"),
            visible(files, sort = FileListSort(FileSortCriterion.SIZE)).map { it.name }
        )
        assertEquals(
            listOf("big.bin", "small.bin", "unknown.bin"),
            visible(
                files,
                sort = FileListSort(FileSortCriterion.SIZE, FileSortDirection.DESCENDING)
            ).map { it.name }
        )
    }

    @Test
    fun equalSizeKeysTieBreakByName() {
        val files = listOf(file("b.bin", size = 10), file("a.bin", size = 10))
        assertEquals(
            listOf("a.bin", "b.bin"),
            visible(files, sort = FileListSort(FileSortCriterion.SIZE)).map { it.name }
        )
    }

    // ── modified ────────────────────────────────────────────────────────────

    @Test
    fun modifiedSortParsesGuestAndAndroidTimestampsAndPutsUnknownLast() {
        val files = listOf(
            file("iso.txt", modified = "2026-08-09 10:00"),
            file("recent.txt", modified = "Aug 10 09:00"),
            file("old.txt", modified = "Jan 1 2020"),
            file("unknown.txt", modified = "not a date")
        )
        assertEquals(
            listOf("old.txt", "iso.txt", "recent.txt", "unknown.txt"),
            visible(files, sort = FileListSort(FileSortCriterion.MODIFIED)).map { it.name }
        )
        assertEquals(
            listOf("recent.txt", "iso.txt", "old.txt", "unknown.txt"),
            visible(
                files,
                sort = FileListSort(FileSortCriterion.MODIFIED, FileSortDirection.DESCENDING)
            ).map { it.name }
        )
    }

    @Test
    fun yearLessGuestTimestampsRollBackAcrossNewYear() {
        val parsed = FileListPresentation.parseModified(
            "Dec 31 23:00",
            now = LocalDateTime.of(2026, 1, 2, 12, 0)
        )
        assertEquals(LocalDateTime.of(2025, 12, 31, 23, 0), parsed)
    }

    @Test
    fun invalidGuestTimestampsAreUnknownNotTheEpoch() {
        assertNull(FileListPresentation.parseModified("Feb 29 2025"))
        assertNull(FileListPresentation.parseModified("garbage"))
        assertNull(FileListPresentation.parseModified(""))
    }

    // ── type ────────────────────────────────────────────────────────────────

    @Test
    fun typeSortGroupsByExtensionAndSinksExtensionlessEntries() {
        val files = listOf(
            file("trailing."),
            file("docs", directory = true),
            file("notes.txt"),
            file("readme.md"),
            file("archive.tar.gz"),
            file("Makefile"),
            file(".bashrc"),
            file("UPPER.TXT")
        )
        assertEquals(
            listOf(
                "docs",
                "archive.tar.gz",
                "readme.md",
                "UPPER.TXT",
                "notes.txt",
                ".bashrc",
                "Makefile",
                "trailing."
            ),
            visible(files, sort = FileListSort(FileSortCriterion.TYPE)).map { it.name }
        )
    }

    @Test
    fun typeKeyIgnoresDotfilesAndTrailingDots() {
        assertNull(FileListPresentation.typeKey(".bashrc"))
        assertNull(FileListPresentation.typeKey("README"))
        assertNull(FileListPresentation.typeKey("trailing."))
        assertEquals("gz", FileListPresentation.typeKey("archive.tar.gz"))
        assertEquals("txt", FileListPresentation.typeKey("NOTES.TXT"))
    }

    // ── stability / navigation contract ─────────────────────────────────────

    @Test
    fun repeatedCallsProduceTheSameOrder() {
        val files = listOf(file("b", size = 2), file("a", size = 2), file("c", size = 1))
        val sort = FileListSort(FileSortCriterion.SIZE)
        assertEquals(visible(files, sort = sort), visible(files, sort = sort))
    }

    @Test
    fun duplicateNamesAreBrokenByPath() {
        val files = listOf(
            file("same.txt", path = "/b/same.txt"),
            file("same.txt", path = "/a/same.txt")
        )
        assertEquals(
            listOf("/a/same.txt", "/b/same.txt"),
            visible(files, sort = FileListSort(FileSortCriterion.NAME)).map { it.path }
        )
    }

    @Test
    fun theCycleVisitsEveryCriterionAndReturnsToNative() {
        var sort = FileListSort()
        val seen = mutableListOf<FileSortCriterion>()
        repeat(5) {
            seen += sort.criterion
            sort = sort.nextCriterion()
        }
        assertEquals(FileSortCriterion.entries.toList(), seen)
        assertEquals(FileSortCriterion.NATIVE, sort.criterion)
    }

    @Test
    fun togglingDirectionIsReversible() {
        val sort = FileListSort(FileSortCriterion.MODIFIED)
        assertEquals(FileSortDirection.DESCENDING, sort.toggledDirection().direction)
        assertEquals(sort, sort.toggledDirection().toggledDirection())
    }
}
