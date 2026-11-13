package com.fem.linexa.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceListPolicyTest {

    private data class Item(
        val id: String,
        val name: String,
        val createdAt: Long,
        val status: WorkspaceStatus = WorkspaceStatus.Ready,
        val extras: List<String> = emptyList()
    )

    private fun entry(i: Item) = WorkspaceListEntry(i.name, i.extras, i.status, i.createdAt)

    private fun apply(items: List<Item>, options: WorkspaceListOptions = WorkspaceListOptions()) =
        WorkspaceListPolicy.apply(items, options, ::entry).map { it.id }

    /** What the DAO hands over: newest first. */
    private val daoOrder = listOf(
        Item("c", "Cherry", createdAt = 300),
        Item("b", "banana", createdAt = 200),
        Item("a", "Apple", createdAt = 100)
    )

    // ── the status filter ──────────────────────────────────────────────────

    private val everyStatus: List<WorkspaceStatus> = listOf(
        WorkspaceStatus.Created,
        WorkspaceStatus.Importing(0.5f),
        WorkspaceStatus.Installed,
        WorkspaceStatus.Ready,
        WorkspaceStatus.Broken("x"),
        WorkspaceStatus.Repairing,
        WorkspaceStatus.Deleting
    )

    @Test
    fun theAllFilterMatchesEveryStatus() {
        everyStatus.forEach { assertTrue("$it", WorkspaceStatusFilter.ALL.matches(it)) }
    }

    @Test
    fun everyStatusBelongsToExactlyOneSpecificFilter() {
        val specific = WorkspaceStatusFilter.entries - WorkspaceStatusFilter.ALL
        everyStatus.forEach { status ->
            assertEquals("$status", 1, specific.count { it.matches(status) })
        }
    }

    @Test
    fun statusesFallIntoTheDocumentedGroups() {
        assertTrue(WorkspaceStatusFilter.READY.matches(WorkspaceStatus.Ready))
        assertTrue(WorkspaceStatusFilter.READY.matches(WorkspaceStatus.Installed))
        for (s in listOf(WorkspaceStatus.Created, WorkspaceStatus.Importing(0.1f), WorkspaceStatus.Repairing, WorkspaceStatus.Deleting)) {
            assertTrue("$s", WorkspaceStatusFilter.IN_PROGRESS.matches(s))
        }
        assertTrue(WorkspaceStatusFilter.BROKEN.matches(WorkspaceStatus.Broken("disk")))
        assertFalse(WorkspaceStatusFilter.BROKEN.matches(WorkspaceStatus.Ready))
    }

    @Test
    fun theFilterDropsNonMatchingItems() {
        val items = listOf(
            Item("r", "R", 3, WorkspaceStatus.Ready),
            Item("i", "I", 2, WorkspaceStatus.Importing(0.2f)),
            Item("x", "X", 1, WorkspaceStatus.Broken("bad"))
        )
        assertEquals(listOf("r"), apply(items, WorkspaceListOptions(filter = WorkspaceStatusFilter.READY)))
        assertEquals(listOf("i"), apply(items, WorkspaceListOptions(filter = WorkspaceStatusFilter.IN_PROGRESS)))
        assertEquals(listOf("x"), apply(items, WorkspaceListOptions(filter = WorkspaceStatusFilter.BROKEN)))
        assertEquals(listOf("r", "i", "x"), apply(items))
    }

    // ── the sort ───────────────────────────────────────────────────────────

    @Test
    fun theSortChipCyclesNewestOldestNameThenBack() {
        assertEquals(WorkspaceSort.OLDEST, WorkspaceSort.NEWEST.next())
        assertEquals(WorkspaceSort.NAME, WorkspaceSort.OLDEST.next())
        assertEquals(WorkspaceSort.NEWEST, WorkspaceSort.NAME.next())
    }

    @Test
    fun theDefaultKeepsTheDatabaseNewestFirstOrderExactly() {
        assertEquals(listOf("c", "b", "a"), apply(daoOrder))
    }

    @Test
    fun theDefaultIsTheIdentityWhenEveryCreatedAtTies() {
        // Hand-built UI models default createdAt to 0: incoming order must survive.
        val tied = listOf(Item("z", "Z", 0), Item("y", "Y", 0), Item("x", "X", 0))
        assertEquals(listOf("z", "y", "x"), apply(tied))
    }

    @Test
    fun newestOrdersByCreatedAtDescendingEvenForUnsortedInput() {
        val shuffled = listOf(daoOrder[2], daoOrder[0], daoOrder[1])
        assertEquals(listOf("c", "b", "a"), apply(shuffled))
    }

    @Test
    fun oldestIsTheExactMirrorOfNewest() {
        val options = WorkspaceListOptions(sort = WorkspaceSort.OLDEST)
        assertEquals(listOf("a", "b", "c"), apply(daoOrder, options))
        val tied = listOf(Item("z", "Z", 0), Item("y", "Y", 0), Item("x", "X", 0))
        assertEquals(listOf("x", "y", "z"), apply(tied, options))
    }

    @Test
    fun nameSortIgnoresCaseAndAccents() {
        val items = listOf(
            Item("1", "banana", 1), Item("2", "Apple", 2), Item("3", "Éclair", 3), Item("4", "cherry", 4)
        )
        assertEquals(
            listOf("2", "1", "4", "3"),
            apply(items, WorkspaceListOptions(sort = WorkspaceSort.NAME))
        )
    }

    @Test
    fun nameSortTreatsArabicAndPersianSpellingsAsTheSameLetters() {
        // Both normalise to the same string, so they tie and the newer one comes first.
        val items = listOf(Item("old", "كتاب", 1), Item("new", "کتاب", 2))
        assertEquals(listOf("new", "old"), apply(items, WorkspaceListOptions(sort = WorkspaceSort.NAME)))
    }

    // ── search ─────────────────────────────────────────────────────────────

    @Test
    fun aSearchPutsTheBestMatchFirstWhateverTheSort() {
        val items = listOf(
            Item("word", "alpha dev", 3),      // word-prefix match
            Item("prefix", "dev-zeta", 2),     // name-prefix match
            Item("sub", "prodev", 1)           // substring match
        )
        assertEquals(
            listOf("prefix", "word", "sub"),
            apply(items, WorkspaceListOptions(query = "dev", sort = WorkspaceSort.NAME))
        )
    }

    @Test
    fun theSortOrdersItemsThatRankEqually() {
        val items = listOf(Item("b", "dev-b", 1), Item("a", "dev-a", 2), Item("c", "dev-c", 3))
        assertEquals(
            listOf("a", "b", "c"),
            apply(items, WorkspaceListOptions(query = "dev", sort = WorkspaceSort.NAME))
        )
        assertEquals(
            listOf("c", "a", "b"),
            apply(items, WorkspaceListOptions(query = "dev", sort = WorkspaceSort.NEWEST))
        )
    }

    @Test
    fun searchFindsDistroFactsThroughTheExtras() {
        val items = listOf(
            Item("deb", "My box", 2, extras = listOf("Debian 13", "trixie")),
            Item("ubu", "Other", 1, extras = listOf("Ubuntu 24.04"))
        )
        assertEquals(listOf("deb"), apply(items, WorkspaceListOptions(query = "trixie")))
        assertEquals(listOf("ubu"), apply(items, WorkspaceListOptions(query = "ubuntu 24")))
    }

    @Test
    fun searchAndFilterCombine() {
        val items = listOf(
            Item("1", "dev ready", 3, WorkspaceStatus.Ready),
            Item("2", "dev broken", 2, WorkspaceStatus.Broken("x")),
            Item("3", "prod ready", 1, WorkspaceStatus.Ready)
        )
        assertEquals(
            listOf("1"),
            apply(items, WorkspaceListOptions(query = "dev", filter = WorkspaceStatusFilter.READY))
        )
    }

    @Test
    fun aQueryThatMatchesNothingGivesAnEmptyList() {
        assertEquals(emptyList<String>(), apply(daoOrder, WorkspaceListOptions(query = "zzz")))
    }

    @Test
    fun applyReturnsTheCallersOwnInstances() {
        val result = WorkspaceListPolicy.apply(daoOrder, WorkspaceListOptions(sort = WorkspaceSort.NAME), ::entry)
        assertSame(daoOrder[2], result[0]) // Apple
    }

    // ── options ────────────────────────────────────────────────────────────

    @Test
    fun onlyAQueryOrFilterNarrowsTheList() {
        assertFalse(WorkspaceListOptions().narrowsList)
        assertFalse(WorkspaceListOptions(sort = WorkspaceSort.NAME).narrowsList)
        assertFalse(WorkspaceListOptions(query = "   ").narrowsList)
        assertTrue(WorkspaceListOptions(query = "a").narrowsList)
        assertTrue(WorkspaceListOptions(filter = WorkspaceStatusFilter.BROKEN).narrowsList)
    }

    @Test
    fun isDefaultIsTrueOnlyForUntouchedOptions() {
        assertTrue(WorkspaceListOptions().isDefault)
        assertFalse(WorkspaceListOptions(sort = WorkspaceSort.OLDEST).isDefault)
        assertFalse(WorkspaceListOptions(query = " ").isDefault)
        assertFalse(WorkspaceListOptions(filter = WorkspaceStatusFilter.READY).isDefault)
    }

    // ── the toolbar threshold ──────────────────────────────────────────────

    @Test
    fun theToolbarAppearsFromThreeWorkspaces() {
        assertEquals(3, WorkspaceListPolicy.TOOLBAR_MIN_WORKSPACES)
        assertFalse(WorkspaceListPolicy.showsToolbar(0))
        assertFalse(WorkspaceListPolicy.showsToolbar(2))
        assertTrue(WorkspaceListPolicy.showsToolbar(3))
        assertTrue(WorkspaceListPolicy.showsToolbar(10))
    }

    @Test
    fun belowTheThresholdOptionsCannotHideAnything() {
        val narrowing = WorkspaceListOptions(query = "zzz", sort = WorkspaceSort.NAME, filter = WorkspaceStatusFilter.BROKEN)
        assertEquals(WorkspaceListOptions(), WorkspaceListPolicy.effective(narrowing, 2))
        assertEquals(narrowing, WorkspaceListPolicy.effective(narrowing, 3))
    }
}
