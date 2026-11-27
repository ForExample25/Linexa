package com.fem.linexa.domain.resource

/**
 * LINEXA-PERF-002: one adjacency walk per root instead of
 * `ProcessTree.descendantsOf` + a full-list filter per session.
 *
 * The Sessions screen needs, for each terminal's pty pid, the subset of the
 * workspace process table that belongs to that terminal's tree (the root and
 * every process reachable through `parentPid` links). With N roots and P
 * processes the previous shape allocated a memo map and rescanned the whole
 * table N times; this builds one child index and walks only the members of
 * each tree.
 *
 * Semantics are identical to
 * `ProcessTree.descendantsOf(root, parents)` + `processes.filter { pid in ... }`:
 * a root with no children still yields itself when it appears in [processes],
 * cycles terminate via the visited set, and processes whose root is not
 * requested never appear.
 */
object RootTreePartition {

    fun byRoot(
        roots: Collection<Int>,
        processes: List<WorkspaceProcessSample>
    ): Map<Int, List<WorkspaceProcessSample>> {
        if (roots.isEmpty()) return emptyMap()
        val byPid = HashMap<Int, WorkspaceProcessSample>(processes.size)
        val childrenOf = HashMap<Int, MutableList<Int>>(processes.size)
        for (process in processes) {
            byPid[process.pid] = process
            val parent = process.parentPid ?: continue
            childrenOf.getOrPut(parent) { ArrayList(2) }.add(process.pid)
        }
        val result = HashMap<Int, List<WorkspaceProcessSample>>(roots.size)
        for (root in roots) {
            if (root in result) continue
            val members = ArrayList<WorkspaceProcessSample>()
            val seen = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            queue.addLast(root)
            while (queue.isNotEmpty()) {
                val pid = queue.removeFirst()
                if (!seen.add(pid)) continue
                byPid[pid]?.let(members::add)
                childrenOf[pid]?.let { queue.addAll(it) }
            }
            result[root] = members
        }
        return result
    }
}
