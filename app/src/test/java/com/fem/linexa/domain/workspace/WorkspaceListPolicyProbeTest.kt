package com.fem.linexa.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isolated S6/`PERF-014`/`AP-001` measurement: the pure Home list policy at
 * synthetic workspace counts.
 *
 * The owner's production database must never receive synthetic rows, and the
 * aarch64 host cannot run the Robolectric/Conscrypt classes. [WorkspaceListPolicy]
 * is deliberately pure Kotlin, so this test can measure exactly the function
 * [com.fem.linexa.ui.screens.home.HomeViewModel.visibleWorkspaces] runs inside
 * `stateIn(viewModelScope)` — i.e. on Main — without a device or the owner's data.
 *
 * The timing output is a JVM/host probe, not a device measurement. It is printed
 * with the `PERF-014-PROBE` prefix so the raw line can be quoted in the
 * performance reports without inventing a value. The generous elapsed bound only
 * catches a pathological regression; the printed median is the evidence.
 */
class WorkspaceListPolicyProbeTest {

    private data class Item(
        val id: String,
        val name: String,
        val createdAt: Long,
        val status: WorkspaceStatus = WorkspaceStatus.Ready,
        val extras: List<String>
    )

    private fun entry(item: Item) =
        WorkspaceListEntry(item.name, item.extras, item.status, item.createdAt)

    /**
     * Deterministic mixed-script fixture. Accents, Persian spellings and the
     * Arabic/Persian letter variants are the expensive part of `normalize`, so
     * the probe does not understate the policy's worst-case work.
     */
    private fun fixture(count: Int): List<Item> {
        val tokens = listOf(
            "Debian", "Ubuntu", "Arch", "Fedora", "کتاب", "كتاب", "Éclair",
            "Trixie", "Bookworm", "می‌خواهم", "Work", "Lab", "Notes", "Server"
        )
        return (0 until count).map { i ->
            Item(
                id = "ws-$i",
                name = "${tokens[i % tokens.size]} ${i + 1}",
                createdAt = (count - i).toLong(),
                status = when (i % 7) {
                    5 -> WorkspaceStatus.Installed
                    6 -> WorkspaceStatus.Broken("probe")
                    else -> WorkspaceStatus.Ready
                },
                extras = listOf("Debian 13", "trixie", "arm64", "Linexa workspace ${i + 1}")
            )
        }
    }

    private fun median(values: List<Long>): Long =
        values.sorted()[values.size / 2]

    private fun measure(block: () -> Unit): Pair<Long, Long> {
        // Two warm-ups: the first call pays class loading and the JIT's first
        // pass at `Normalizer.normalize`, which is not what this probe reports.
        repeat(2) { block() }
        val samples = (0 until 5).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000L // µs
        }
        return median(samples) to samples.max()
    }

    @Test
    fun thePolicyIsMeasuredAcrossSyntheticCollectionSizes() {
        val sizes = listOf(4, 50, 250, 1000)
        for (count in sizes) {
            val items = fixture(count)
            val expectedDefault = items.map { it.id }

            // Correctness first: the default sort is the database's newest-first
            // order identity, and NAME is ordered by the same normalized key the
            // search uses. A timing change must not alter either result.
            val defaultResult = WorkspaceListPolicy.apply(items, WorkspaceListOptions(), ::entry)
            assertEquals("default count n=$count", expectedDefault, defaultResult.map { it.id })

            val nameOptions = WorkspaceListOptions(sort = WorkspaceSort.NAME)
            val nameResult = WorkspaceListPolicy.apply(items, nameOptions, ::entry)
            val expectedName = items.sortedWith(
                compareBy<Item> { WorkspaceSearch.normalize(it.name) }
                    .thenByDescending { it.createdAt }
                    .thenBy { items.indexOf(it) }
            ).map { it.id }
            assertEquals("name count n=$count", expectedName, nameResult.map { it.id })

            val (defaultMedian, defaultMax) = measure {
                WorkspaceListPolicy.apply(items, WorkspaceListOptions(), ::entry)
            }
            val (nameMedian, nameMax) = measure {
                WorkspaceListPolicy.apply(items, nameOptions, ::entry)
            }

            println(
                "PERF-014-PROBE n=$count default_median_us=$defaultMedian " +
                    "default_max_us=$defaultMax name_median_us=$nameMedian name_max_us=$nameMax"
            )

            assertTrue(
                "policy n=$count should complete far below the guard rail " +
                    "(default=${defaultMedian}us name=${nameMedian}us)",
                defaultMedian < 5_000_000L && nameMedian < 5_000_000L
            )
        }
    }
}
