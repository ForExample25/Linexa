package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.data.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX Phase 5C — [CommandTree]: turning the `>`-encoded hierarchy in command
 * titles into a real navigation tree.
 *
 * These are the rules that are silent when broken. A missing grouping renders as
 * a duplicated category; a dropped entry renders as a command the image declared
 * and the user simply cannot find; an executable parent renders as a card that
 * looks identical to its siblings and runs the wrong thing. All three would ship
 * looking plausible, which is why they are pinned here rather than left to the
 * (Compose-only, untestable in this repo) UI.
 *
 * Nothing here names a real command, category or distribution — the fixtures use
 * `A>B>C` and the plan's own example only where the example *is* the requirement.
 */
class CommandTreeTest {

    private var next = 0

    /** A command whose declared title is [title]. */
    private fun shortcut(title: String, command: String = "run-$title") = Shortcut(
        id = "id-${next++}",
        title = title,
        description = command,
        iconRes = 0,
        command = command
    )

    private fun build(vararg titles: String): List<CommandNode> =
        CommandTree.build(titles.map { shortcut(it) })

    private fun namesAt(nodes: List<CommandNode>, vararg path: String): List<String> =
        CommandTree.childrenAt(nodes, path.toList())!!.map { it.name }

    // ── parsing ───────────────────────────────────────────────────────────────

    @Test
    fun aTitleWithTwoSeparatorsParsesIntoThreeLevels() {
        val tree = build("A>B>C")

        assertEquals(listOf("A"), tree.map { it.name })
        assertEquals(listOf("B"), namesAt(tree, "A"))
        assertEquals(listOf("C"), namesAt(tree, "A", "B"))
        // The leaf is at the bottom and nothing above it is executable.
        assertTrue(CommandTree.childrenAt(tree, listOf("A", "B"))!!.single() is CommandNode.Leaf)
    }

    @Test
    fun theSeparatorNeverAppearsInAnyRenderedName() {
        // The whole defect being corrected: `>` is structure, not text. Every
        // node's `name` is one segment, so no card can print the separator.
        val tree = build("A>B>C", "A>B>D", "E")
        val names = mutableListOf<String>()
        fun walk(nodes: List<CommandNode>) {
            nodes.forEach { node ->
                names += node.name
                if (node is CommandNode.Category) walk(node.children)
            }
        }
        walk(tree)
        assertTrue(
            "no rendered name may contain the hierarchy separator: $names",
            names.none { it.contains(CommandTree.SEPARATOR) }
        )
    }

    @Test
    fun aTitleWithNoSeparatorStaysASingleLeafAtTheRoot() {
        val tree = build("Update System")
        val leaf = tree.single()
        assertTrue(leaf is CommandNode.Leaf)
        assertEquals("Update System", leaf.name)
    }

    @Test
    fun paddingAndEmptySegmentsDoNotCreateUnnamedLevels() {
        // `A > B`, `A>>B` and `A>B>` are all the same two-level path — an author's
        // stray separator must not produce a level with no name to tap.
        assertEquals(listOf("A"), build("A > B").map { it.name })
        assertEquals(listOf("B"), namesAt(build("A > B"), "A"))
        assertEquals(listOf("B"), namesAt(build("A>>B"), "A"))
        assertEquals(listOf("B"), namesAt(build("A>B>"), "A"))
    }

    @Test
    fun titlesWithSurroundingWhitespaceStillParse() {
        // Padding around the separator must not leak into a level's name.
        val tree = CommandTree.build(listOf(shortcut("Group > Sub > Install")))
        assertEquals(listOf("Group"), tree.map { it.name })
        assertEquals(listOf("Install"), namesAt(tree, "Group", "Sub"))
    }

    // ── grouping ──────────────────────────────────────────────────────────────

    @Test
    fun commandsSharingAParentAreGroupedUnderOneCategory() {
        val tree = build("A>Install", "A>Uninstall")

        assertEquals("siblings must not each produce their own category", 1, tree.size)
        assertEquals(listOf("Install", "Uninstall"), namesAt(tree, "A"))
    }

    @Test
    fun siblingCategoriesUnderASharedParentAreBothReachable() {
        // The plan's example, which is the requirement: one parent, two
        // sub-categories, two leaves each.
        val tree = build(
            "Software Management>Krita>Install",
            "Software Management>Krita>Uninstall",
            "Software Management>Chrome>Install",
            "Software Management>Chrome>Uninstall"
        )

        assertEquals(listOf("Software Management"), tree.map { it.name })
        assertEquals(listOf("Krita", "Chrome"), namesAt(tree, "Software Management"))
        assertEquals(
            listOf("Install", "Uninstall"),
            namesAt(tree, "Software Management", "Krita")
        )
        assertEquals(
            listOf("Install", "Uninstall"),
            namesAt(tree, "Software Management", "Chrome")
        )
    }

    @Test
    fun multipleTopLevelCategoriesCoexist() {
        val tree = build("A>1", "B>1", "C>1")
        assertEquals(listOf("A", "B", "C"), tree.map { it.name })
        assertTrue(tree.all { it is CommandNode.Category })
    }

    @Test
    fun aTopLevelLeafSitsAlongsideTopLevelCategories() {
        // Mixed levels are ordinary: the reference `.tiny.yaml` has both
        // standalone commands and groups at its root.
        val tree = build("A>1", "Standalone", "B>1")
        assertEquals(listOf("A", "Standalone", "B"), tree.map { it.name })
        assertTrue(tree[1] is CommandNode.Leaf)
    }

    @Test
    fun theSamePathIsNeverDuplicatedNoMatterHowManyCommandsShareIt() {
        val tree = build("A>B>1", "A>B>2", "A>B>3", "A>C>1")

        assertEquals(1, tree.size)
        assertEquals(listOf("B", "C"), namesAt(tree, "A"))
        assertEquals(3, CommandTree.childrenAt(tree, listOf("A", "B"))!!.size)
    }

    @Test
    fun interleavedDeclarationsStillGroupTogether() {
        // Metadata order is the author's, and nothing guarantees siblings are
        // adjacent. A naive fold over consecutive runs would produce `A` twice.
        val tree = build("A>1", "B>1", "A>2")
        assertEquals(listOf("A", "B"), tree.map { it.name })
        assertEquals(listOf("1", "2"), namesAt(tree, "A"))
    }

    @Test
    fun declarationOrderIsPreservedAtEveryLevel() {
        // Not sorted: alphabetical ordering would scatter Install/Uninstall pairs
        // and reorder an author's deliberate sequence.
        val tree = build("Z>b", "Z>a", "Y>1")
        assertEquals(listOf("Z", "Y"), tree.map { it.name })
        assertEquals(listOf("b", "a"), namesAt(tree, "Z"))
    }

    @Test
    fun everyDeclaredCommandSurvivesAsExactlyOneLeaf() {
        // The guarantee that matters most: a grouping bug that swallowed an entry
        // would hide a command the image declared, with nothing on screen to
        // suggest it existed.
        val titles = listOf("A>B>1", "A>B>2", "A>3", "C", "D>E>F>G>4")
        val shortcuts = titles.map { shortcut(it) }
        val tree = CommandTree.build(shortcuts)

        val leaves = mutableListOf<CommandNode.Leaf>()
        fun walk(nodes: List<CommandNode>) {
            nodes.forEach { if (it is CommandNode.Category) walk(it.children) else leaves += it as CommandNode.Leaf }
        }
        walk(tree)

        assertEquals(shortcuts.size, leaves.size)
        assertEquals(shortcuts.map { it.id }.toSet(), leaves.map { it.shortcut.id }.toSet())
    }

    @Test
    fun everyNodeKeyIsUniqueAcrossTheWholeTree() {
        // A LazyColumn throws on duplicate keys, and 14 leaves named `Install` is
        // a real file, not a hypothetical.
        val tree = CommandTree.build(
            listOf(
                shortcut("A>Install"),
                shortcut("B>Install"),
                shortcut("C>Install"),
                shortcut("Install")
            )
        )
        val keys = mutableListOf<String>()
        fun walk(nodes: List<CommandNode>) {
            nodes.forEach {
                keys += it.key
                if (it is CommandNode.Category) walk(it.children)
            }
        }
        walk(tree)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun twoCommandsWithIdenticalTitlesBothSurvive() {
        // Nothing forbids it — ids are unique, titles are not.
        val tree = CommandTree.build(listOf(shortcut("A>Install"), shortcut("A>Install")))
        val children = CommandTree.childrenAt(tree, listOf("A"))!!
        assertEquals(2, children.size)
        assertEquals(2, children.map { it.key }.toSet().size)
    }

    // ── arbitrary depth ───────────────────────────────────────────────────────

    @Test
    fun depthIsNotCappedAndNeedsNoPerLevelSpecialCase() {
        val depth = 12
        val path = (1..depth).map { "L$it" }
        val tree = CommandTree.build(listOf(shortcut(path.joinToString(">"))))

        // Every level but the last navigates…
        for (i in 0 until depth - 1) {
            val level = CommandTree.childrenAt(tree, path.take(i))!!
            assertTrue("level $i must be a category", level.single() is CommandNode.Category)
        }
        // …and the last is the command.
        val leaf = CommandTree.childrenAt(tree, path.dropLast(1))!!.single()
        assertTrue(leaf is CommandNode.Leaf)
        assertEquals("L$depth", leaf.name)
    }

    // ── parents are not executable, leaves are ────────────────────────────────

    @Test
    fun aParentIsACategoryAndCarriesNothingToExecute() {
        val tree = build("A>B>Install")
        val a = tree.single()
        assertTrue(a is CommandNode.Category)
        // The type has no shortcut to run — asserted through the exhaustive
        // `when` the UI itself uses, so this cannot rot into a nullable field.
        val runnable = when (a) {
            is CommandNode.Category -> null
            is CommandNode.Leaf -> a.shortcut
        }
        assertNull("a category must have no command", runnable)
    }

    @Test
    fun aLeafIsExecutableAndCarriesItsDeclaredCommand() {
        val declared = shortcut("A>Install", command = "install-thing")
        val tree = CommandTree.build(listOf(declared))
        val leaf = CommandTree.childrenAt(tree, listOf("A"))!!.single() as CommandNode.Leaf
        assertEquals("install-thing", leaf.shortcut.command)
        assertEquals(declared.id, leaf.shortcut.id)
    }

    @Test
    fun aTitleThatIsBothAPrefixAndACommandBecomesACategoryWithThatCommandInside() {
        // `A>B` declared alongside `A>B>Install` makes `B` both. Requirement 9
        // wins — a node with children must never execute — but the colliding
        // command must stay reachable rather than being swallowed by the category
        // that took its place.
        val parentCommand = shortcut("A>B", command = "run-b")
        val childCommand = shortcut("A>B>Install", command = "install")
        val tree = CommandTree.build(listOf(parentCommand, childCommand))

        val b = CommandTree.childrenAt(tree, listOf("A"))!!.single()
        assertTrue("the prefix must become a category", b is CommandNode.Category)

        val children = CommandTree.childrenAt(tree, listOf("A", "B"))!!
        assertEquals(
            "the colliding command must be re-parented, not dropped",
            2,
            children.size
        )
        assertEquals(
            setOf("run-b", "install"),
            children.filterIsInstance<CommandNode.Leaf>().map { it.shortcut.command }.toSet()
        )
        assertEquals(
            "and it must come first, since it is the level's own command",
            "run-b",
            (children.first() as CommandNode.Leaf).shortcut.command
        )
    }

    // ── navigation helpers ────────────────────────────────────────────────────

    @Test
    fun theRootIsTheWholeTree() {
        val tree = build("A>1", "B>1")
        assertEquals(tree, CommandTree.childrenAt(tree, emptyList()))
    }

    @Test
    fun anUnresolvablePathIsNullSoTheUiCanResetInsteadOfShowingABlankLevel() {
        // Distinguishable from an empty level on purpose: an empty category
        // cannot exist, so a null always means the tree changed underneath the UI
        // (metadata re-read, or the workspace switched while drilled in).
        val tree = build("A>1")
        assertNull(CommandTree.childrenAt(tree, listOf("Nope")))
        assertNull(CommandTree.childrenAt(tree, listOf("A", "1")))
        assertNull(
            "a leaf is not a level",
            CommandTree.childrenAt(tree, listOf("A", "1", "deeper"))
        )
    }

    @Test
    fun anEmptyCommandListIsAnEmptyTree() {
        assertTrue(CommandTree.build(emptyList()).isEmpty())
    }

    @Test
    fun aCategoryCountsEveryCommandBeneathItAtAnyDepth() {
        // The count is what a category card shows instead of a description —
        // neither metadata path carries a group description, so an invented one
        // would be the only alternative.
        val tree = build("A>B>1", "A>B>2", "A>C>D>3", "E>4")
        assertEquals(3, CommandTree.leafCount(tree.first { it.name == "A" }))
        assertEquals(1, CommandTree.leafCount(tree.first { it.name == "E" }))
        assertEquals(
            1,
            CommandTree.leafCount(CommandTree.childrenAt(tree, listOf("A", "C"))!!.single())
        )
    }

    // ── it composes with the resolver that feeds it ───────────────────────────

    @Test
    fun aDeclaredDistributionSurvivesIntoEveryLeaf() {
        // Phase 5C's distribution slot must not be lost on the way through the
        // tree — the leaf is what the card renders now.
        val resolved = listOf(
            WorkspaceShortcuts.declaredShortcut(
                com.fem.linexa.service.rootfs.DeclaredShortcut(
                    id = "install",
                    label = "A>B>Install",
                    command = "install"
                ),
                distributionLabel = "Debian GNU/Linux 13 (trixie)"
            )
        )
        val tree = CommandTree.build(resolved)
        val leaf = CommandTree.childrenAt(tree, listOf("A", "B"))!!.single() as CommandNode.Leaf
        assertEquals("Debian GNU/Linux 13 (trixie)", leaf.shortcut.distributionLabel)
    }
}
