package com.fem.linexa.domain.resource

/**
 * The set of processes belonging to one workspace, derived from parent links
 * (UI/UX Phase 5B).
 *
 * Pure and separate from the `/proc` reader because the interesting cases are
 * the degenerate ones — a cycle, a pid that is its own parent, a tree deeper
 * than the process table, a reparented orphan — and none of them can be
 * produced on demand on a device.
 *
 * Why parent links rather than `children`: `/proc/<pid>/task/<tid>/children`
 * requires `CONFIG_PROC_CHILDREN`, which is **not enabled on the Android
 * kernels this app targets** (verified absent on 6.6 android15 during Phase
 * 5B). Walking downward from a child list is the more direct algorithm and it
 * silently returns nothing on those devices; walking upward from every visible
 * pid's `ppid` works anywhere `/proc` is readable.
 */
object ProcessTree {

    /**
     * Every pid in [parents] that is [rootPid] or a descendant of it, including
     * [rootPid] itself even when it has no children.
     *
     * [parents] maps pid → ppid for every process currently visible. Only
     * processes the app is allowed to see appear there: on modern Android
     * `/proc` is mounted with `hidepid`, so this is the app's own processes plus
     * its workspace children — which is exactly the required scope, and also the
     * reason this can never accidentally report the whole device.
     *
     * Resolution is iterative and memoized rather than recursive: a corrupted or
     * adversarial parent map can describe a cycle (`a → b → a`), and a recursive
     * ancestor check would overflow the stack on it. Each pid is classified once,
     * cycles resolve to "not a descendant", and the walk is bounded by
     * `parents.size` steps per pid.
     */
    fun descendantsOf(rootPid: Int, parents: Map<Int, Int>): Set<Int> {
        if (rootPid <= 0) return emptySet()
        val result = mutableSetOf(rootPid)
        // memo: pid -> is it inside the tree rooted at rootPid
        val memo = HashMap<Int, Boolean>(parents.size)
        memo[rootPid] = true

        for (pid in parents.keys) {
            classify(pid, rootPid, parents, memo)
        }
        for ((pid, inTree) in memo) {
            if (inTree) result.add(pid)
        }
        return result
    }

    /**
     * Whether [pid] is [rootPid] or below it, memoizing every pid on the path.
     *
     * The chain is followed with an explicit list so that (a) a cycle terminates
     * when a pid is seen twice on this walk, and (b) the answer can be written
     * back for every pid visited, making the overall pass linear rather than
     * quadratic.
     */
    private fun classify(
        pid: Int,
        rootPid: Int,
        parents: Map<Int, Int>,
        memo: MutableMap<Int, Boolean>
    ): Boolean {
        memo[pid]?.let { return it }

        val chain = ArrayList<Int>()
        val onThisWalk = HashSet<Int>()
        var current = pid
        var answer: Boolean? = null

        while (true) {
            if (!onThisWalk.add(current)) {
                // Cycle: nothing on it can reach the root through this path.
                answer = false
                break
            }
            // Note the explicit null check rather than `memo[current]?.let { break }`
            // — `break` inside an inline lambda needs Kotlin 2.2 and this module
            // targets 2.1.
            val known = memo[current]
            if (known != null) {
                answer = known
                break
            }
            if (current == rootPid) {
                answer = true
                break
            }
            chain.add(current)
            val parent = parents[current]
            if (parent == null || parent <= 0 || parent == current) {
                answer = false
                break
            }
            current = parent
        }

        val resolved = answer ?: false
        for (visited in chain) memo[visited] = resolved
        memo[pid] = resolved
        return resolved
    }
}
