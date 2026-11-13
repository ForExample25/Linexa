package com.fem.linexa.domain.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 5B — the workspace's process tree, derived from parent links.
 *
 * The cases that matter are the ones a device cannot be made to produce on
 * demand: a cycle in the parent map, a pid that is its own parent, an orphan
 * reparented to init, and a chain long enough that a recursive implementation
 * would overflow. Each of those either hangs or crashes the sampler rather than
 * showing a wrong number, which is the reason the walk is iterative.
 */
class ProcessTreeTest {

    @Test
    fun `the root is always in its own tree`() {
        assertEquals(setOf(100), ProcessTree.descendantsOf(100, mapOf(100 to 1)))
        // Even when the root is not in the parent map at all (it exists, but no
        // process reported it as a parent and its own stat was unreadable).
        assertEquals(setOf(100), ProcessTree.descendantsOf(100, emptyMap()))
    }

    @Test
    fun `direct and transitive children are included`() {
        val parents = mapOf(
            100 to 1,     // root: proot
            101 to 100,   // bash
            102 to 101,   // apt
            103 to 102    // dpkg
        )
        assertEquals(setOf(100, 101, 102, 103), ProcessTree.descendantsOf(100, parents))
    }

    /**
     * The property that makes this panel workspace-scoped rather than app-scoped:
     * sibling processes of the app (and anything else visible in `/proc`) must not
     * be counted.
     */
    @Test
    fun `unrelated visible processes are excluded`() {
        val parents = mapOf(
            100 to 1,     // workspace proot
            101 to 100,   // its bash
            200 to 1,     // the app's own main process
            201 to 200,   // an app thread-process
            300 to 1      // another workspace entirely
        )
        assertEquals(setOf(100, 101), ProcessTree.descendantsOf(100, parents))
        // And that other workspace sees only its own.
        assertEquals(setOf(300), ProcessTree.descendantsOf(300, parents))
    }

    /**
     * A cycle cannot occur in a healthy kernel, but the map is assembled from many
     * separate unsynchronised reads while processes fork and exit, so two
     * inconsistent snapshots can describe one. A recursive ancestor check would
     * recurse forever.
     */
    @Test
    fun `a cycle terminates and does not join the tree`() {
        val parents = mapOf(
            100 to 1,
            101 to 100,
            // a -> b -> a, unrelated to the root
            500 to 501,
            501 to 500
        )
        val tree = ProcessTree.descendantsOf(100, parents)
        assertEquals(setOf(100, 101), tree)
        assertFalse(tree.contains(500))
        assertFalse(tree.contains(501))
    }

    /**
     * A cycle that includes the root itself: the root stays in its own tree (it
     * exists), and the cycle members are not falsely excluded when they genuinely
     * descend from it.
     */
    @Test
    fun `a cycle through the root still reports the root`() {
        val parents = mapOf(100 to 101, 101 to 100)
        val tree = ProcessTree.descendantsOf(100, parents)
        assertTrue(tree.contains(100))
        // 101's parent is the root, so it is legitimately a descendant.
        assertTrue(tree.contains(101))
    }

    @Test
    fun `a self-parented process is not a descendant`() {
        val parents = mapOf(100 to 1, 400 to 400)
        assertEquals(setOf(100), ProcessTree.descendantsOf(100, parents))
    }

    /**
     * An orphan reparented away from the workspace (its `proot` ancestor died and
     * the kernel gave it to init) is correctly no longer counted — the panel must
     * not attribute a process the workspace no longer owns.
     */
    @Test
    fun `a reparented orphan leaves the tree`() {
        val parents = mapOf(100 to 1, 101 to 1)
        assertEquals(setOf(100), ProcessTree.descendantsOf(100, parents))
    }

    /**
     * Deep chain: a recursive implementation would overflow well before this on a
     * default JVM stack, and the guest can legitimately nest (shell → make → cc →
     * as).
     */
    @Test
    fun `a very deep chain resolves without recursion`() {
        val depth = 10_000
        val parents = HashMap<Int, Int>(depth)
        parents[100] = 1
        for (i in 1..depth) {
            parents[100 + i] = 100 + i - 1
        }
        val tree = ProcessTree.descendantsOf(100, parents)
        assertEquals(depth + 1, tree.size)
        assertTrue(tree.contains(100 + depth))
    }

    @Test
    fun `an invalid root pid yields nothing`() {
        assertTrue(ProcessTree.descendantsOf(0, mapOf(1 to 0)).isEmpty())
        assertTrue(ProcessTree.descendantsOf(-1, mapOf(1 to 0)).isEmpty())
    }

    /**
     * Branching, not just a chain: `proot` typically has one child shell, but a
     * workspace running several terminals has several.
     */
    @Test
    fun `multiple branches are all included`() {
        val parents = mapOf(
            100 to 1,
            101 to 100, 102 to 100, 103 to 100,
            110 to 101, 120 to 102
        )
        assertEquals(
            setOf(100, 101, 102, 103, 110, 120),
            ProcessTree.descendantsOf(100, parents)
        )
    }
}
