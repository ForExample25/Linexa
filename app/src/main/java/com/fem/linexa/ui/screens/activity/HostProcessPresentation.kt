package com.fem.linexa.ui.screens.activity

import com.fem.linexa.domain.resource.HostProcessSample

/**
 * ACT-2 — pure presentation rules for the Activity tab's host-process list.
 *
 * The list is a monitor of the Android process table (not a workspace
 * drill-down), so the ordering stays "loudest first": CPU share descending,
 * then name, then pid. The rules were inline in [ActivityScreen] before this
 * item; extracting them keeps the screen's composition logic thin and makes
 * the ordering, keys and collapsed-state behaviour JVM-testable.
 *
 * The scanner and [ActivityViewModel] are deliberately untouched: collapsing
 * the section hides rows, it never pauses collection, sampling, attribution
 * or the end-process flow.
 */
internal object HostProcessPresentation {

    private const val KEY_PREFIX = "host-"

    /** CPU-descending, then lowercase name, then pid — the established order. */
    fun ordered(processes: List<HostProcessSample>): List<HostProcessSample> =
        processes.sortedWith(
            compareByDescending<HostProcessSample> { it.cpuPercentOfDevice ?: -1f }
                .thenBy { it.name.lowercase() }
                .thenBy { it.pid }
        )

    /**
     * Stable LazyColumn identity. A pid is unique within one process table, and
     * pairing it with the section prefix keeps this namespace distinct from the
     * session rows. Duration/CPU updates re-use the row instead of recreating it.
     */
    fun key(process: HostProcessSample): String = "$KEY_PREFIX${process.pid}"

    /**
     * The rows the collapsed/expanded section composes. Returning an empty list
     * while collapsed is what guarantees zero per-process composition: the
     * screen's `if (expanded)` branch then emits no `items` at all, rather than
     * composing cards inside an invisible container.
     */
    fun visible(ordered: List<HostProcessSample>, expanded: Boolean): List<HostProcessSample> =
        if (expanded) ordered else emptyList()
}
