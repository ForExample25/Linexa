package com.fem.linexa.domain.workspace

/** The order of the Home list. */
enum class WorkspaceSort {
    /** Newest first — the order the database already returns, and the default. */
    NEWEST,

    /** Oldest first: the exact mirror of [NEWEST]. */
    OLDEST,

    /** By name, ignoring case, accents and Arabic/Persian spelling variants. */
    NAME;

    /** The next option in the cycle shown by the single sort chip: Newest → Oldest → Name. */
    fun next(): WorkspaceSort = entries[(ordinal + 1) % entries.size]
}

/**
 * Which workspaces the Home list shows, by their *persisted* status.
 *
 * There is deliberately no "Running" filter: Home does not know which workspaces are
 * running (it does not bind the runtime service), so such a filter would always be empty.
 */
enum class WorkspaceStatusFilter {
    ALL,

    /** Ready to launch: [WorkspaceStatus.Ready] and [WorkspaceStatus.Installed]. */
    READY,

    /** Something is happening or about to: created, importing, repairing, deleting. */
    IN_PROGRESS,

    /** [WorkspaceStatus.Broken]. */
    BROKEN;

    fun matches(status: WorkspaceStatus): Boolean = when (this) {
        ALL -> true
        READY -> status.group == StatusGroup.READY
        IN_PROGRESS -> status.group == StatusGroup.IN_PROGRESS
        BROKEN -> status.group == StatusGroup.BROKEN
    }

    private enum class StatusGroup { READY, IN_PROGRESS, BROKEN }

    // An exhaustive `when` over the sealed interface: a new WorkspaceStatus variant stops
    // compiling here until someone decides which filter it belongs to.
    private val WorkspaceStatus.group: StatusGroup
        get() = when (this) {
            WorkspaceStatus.Ready, WorkspaceStatus.Installed -> StatusGroup.READY
            WorkspaceStatus.Created,
            is WorkspaceStatus.Importing,
            WorkspaceStatus.Repairing,
            WorkspaceStatus.Deleting -> StatusGroup.IN_PROGRESS
            is WorkspaceStatus.Broken -> StatusGroup.BROKEN
        }
}

/** The user's current search, filter and sort for the Home list. Not persisted. */
data class WorkspaceListOptions(
    val query: String = "",
    val sort: WorkspaceSort = WorkspaceSort.NEWEST,
    val filter: WorkspaceStatusFilter = WorkspaceStatusFilter.ALL
) {
    /**
     * Whether the query or filter can hide workspaces. A sort alone never removes a card,
     * so it does not count — this is what decides between "no matches" and the ordinary
     * empty state.
     */
    val narrowsList: Boolean
        get() = query.isNotBlank() || filter != WorkspaceStatusFilter.ALL

    /** Whether everything is at its default (nothing for a reset to do). */
    val isDefault: Boolean
        get() = query.isEmpty() && sort == WorkspaceSort.NEWEST && filter == WorkspaceStatusFilter.ALL
}

/** What the policy needs to know about one list item. */
data class WorkspaceListEntry(
    val name: String,
    val extras: List<String>,
    val status: WorkspaceStatus,
    val createdAt: Long
)

/**
 * Filtering, searching and sorting of the Home workspace list (P3).
 *
 * Generic in the item type so the UI model never reaches the domain: the caller supplies
 * a [WorkspaceListEntry] for each item and gets its own items back, in order.
 *
 * Order of operations: the status filter drops items, the search ranks and drops items,
 * then the chosen sort orders whatever is left *within* equal search ranks — so a search
 * puts the best match first and the sort still decides among equally good ones. With a
 * blank query every item ranks equally and the sort alone decides.
 */
object WorkspaceListPolicy {

    /** The search/filter/sort toolbar appears from this many workspaces. */
    const val TOOLBAR_MIN_WORKSPACES = 3

    /** Longer input is cut: a search box has no business holding a paragraph. */
    const val MAX_QUERY_LENGTH = 100

    fun showsToolbar(totalWorkspaces: Int): Boolean = totalWorkspaces >= TOOLBAR_MIN_WORKSPACES

    /**
     * [options] as they apply to a list of [totalWorkspaces]: below the toolbar threshold
     * there is no way to see or clear them, so they must not hide anything.
     */
    fun effective(options: WorkspaceListOptions, totalWorkspaces: Int): WorkspaceListOptions =
        if (showsToolbar(totalWorkspaces)) options else WorkspaceListOptions()

    fun <T> apply(
        items: List<T>,
        options: WorkspaceListOptions,
        entryOf: (T) -> WorkspaceListEntry
    ): List<T> {
        val tokens = WorkspaceSearch.tokens(options.query)
        val candidates = ArrayList<Candidate<T>>(items.size)
        items.forEachIndexed { index, item ->
            val entry = entryOf(item)
            if (!options.filter.matches(entry.status)) return@forEachIndexed
            val rank = WorkspaceSearch.rank(WorkspaceSearchEntry(entry.name, entry.extras), tokens)
                ?: return@forEachIndexed
            candidates += Candidate(item, entry, index, rank, WorkspaceSearch.normalize(entry.name))
        }
        return candidates.sortedWith(comparatorFor(options.sort)).map { it.item }
    }

    private class Candidate<T>(
        val item: T,
        val entry: WorkspaceListEntry,
        val index: Int,
        val rank: WorkspaceSearchRank,
        val sortName: String
    )

    private fun <T> comparatorFor(sort: WorkspaceSort): Comparator<Candidate<T>> {
        val byRank = compareBy<Candidate<T>> { it.rank }
        return when (sort) {
            // Ties keep the incoming order, so for the database's newest-first input this
            // is the identity — the pre-existing behaviour.
            WorkspaceSort.NEWEST ->
                byRank.thenByDescending { it.entry.createdAt }.thenBy { it.index }
            // The exact mirror: ties come out in reverse incoming order.
            WorkspaceSort.OLDEST ->
                byRank.thenBy { it.entry.createdAt }.thenByDescending { it.index }
            WorkspaceSort.NAME ->
                byRank.thenBy { it.sortName }.thenByDescending { it.entry.createdAt }.thenBy { it.index }
        }
    }
}
