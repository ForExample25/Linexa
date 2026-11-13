package com.fem.linexa.domain.runtime

/**
 * The compact, gap-free `T1..TN` labels the user sees for a workspace's
 * terminals.
 *
 * The runtime identity of a terminal (`T0`, `T3`, …) is internal and keeps
 * growing as terminals are opened and closed; what people see is the position
 * in that ordering. The terminal bar and the Activity tab already numbered this
 * way, while the Sessions tab printed the internal id — so the same terminal
 * was "T2" in one place and "T4" in another. Every screen that *shows* a
 * terminal's number goes through here instead; navigation keeps using the
 * internal id.
 *
 * Ordering: numeric suffix first, then lexicographic — the same rule the
 * terminal list applies.
 */
object TerminalNumbering {

    /** internal terminal id → visible label, for exactly the ids passed in. */
    fun compactLabels(terminalIds: Collection<String>): Map<String, String> {
        val ordered = terminalIds.distinct().sortedWith(
            compareBy<String>({ numberOf(it) }, { it })
        )
        val labels = LinkedHashMap<String, String>(ordered.size)
        ordered.forEachIndexed { index, id -> labels[id] = "T${index + 1}" }
        return labels
    }

    private fun numberOf(id: String): Int = id.trimStart('T').toIntOrNull() ?: Int.MAX_VALUE
}
