package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.data.Shortcut
import com.fem.linexa.domain.commands.CustomCommand
import com.fem.linexa.service.commands.QuickCommandNode
import com.fem.linexa.service.commands.QuickNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkspaceCommandsAssembler] + [CommandTree.fromQuickCommands]: the seam
 * where the typed built-in catalog, the image's declared shortcuts and the
 * user's own commands become the ONE tree the Commands screen renders.
 *
 * The structural guarantee under test: the structured catalog maps to the
 * navigation tree BY TYPE (category/group/application → Category, action →
 * Leaf) with no string parsing anywhere, declared entries replace the
 * built-ins (the author's statement wins), and the user's section always
 * exists last with its own name and its own records.
 */
class WorkspaceCommandsAssemblerTest {

    private fun action(id: String, name: String, command: String) = QuickCommandNode(
        id = id, name = name, type = QuickNodeType.ACTION, command = command
    )

    private fun application(id: String, name: String, command: String) = QuickCommandNode(
        id = id,
        name = name,
        type = QuickNodeType.APPLICATION,
        children = listOf(
            QuickCommandNode(
                id = "$id-install", name = "Install", type = QuickNodeType.ACTION,
                command = command, actionKind = com.fem.linexa.service.commands.QuickActionKind.INSTALL
            ),
            QuickCommandNode(
                id = "$id-remove", name = "Uninstall", type = QuickNodeType.ACTION,
                command = "remove $id", actionKind = com.fem.linexa.service.commands.QuickActionKind.REMOVE
            )
        )
    )

    @Test
    fun theStructuredCatalogMapsByTypeAndNothingIsDropped() {
        val nodes = listOf(
            QuickCommandNode(
                id = "system", name = "System", type = QuickNodeType.CATEGORY,
                children = listOf(action("update", "Update", "apt-get update"))
            ),
            application("chromium-browser", "Chromium Browser", "apt-get install -y chromium")
        )
        val tree = CommandTree.fromQuickCommands(nodes)

        assertEquals(listOf("System", "Chromium Browser"), tree.map { it.name })
        val system = tree[0] as CommandNode.Category
        val leaf = system.children.single() as CommandNode.Leaf
        assertEquals("apt-get update", leaf.shortcut.command)
        // The application is navigational; its typed children are the actions.
        val app = tree[1] as CommandNode.Category
        assertEquals(
            listOf("Install", "Uninstall"),
            app.children.map { it.name }
        )
        assertTrue(app.children.all { it is CommandNode.Leaf })
        // Namespace: every built-in key/id carries the prefix.
        assertTrue(tree.all { it.key.startsWith(CommandTree.BUILTIN_KEY_PREFIX) })
        assertEquals(
            "${CommandTree.BUILTIN_KEY_PREFIX}chromium-browser-install",
            (app.children[0] as CommandNode.Leaf).shortcut.id
        )
    }

    @Test
    fun theFullPipelineResolvesMapsAndNavigates() {
        val nodes = listOf(
            QuickCommandNode(
                id = "system", name = "System", type = QuickNodeType.CATEGORY,
                children = listOf(action("update", "Update", "apt-get update"))
            ),
            application("chromium-browser", "Chromium Browser", "apt-get install -y chromium")
        )
        val tree = WorkspaceCommandsAssembler.assemble(
            builtinNodes = nodes,
            declaredShortcuts = null,
            custom = emptyList(),
            customSectionTitle = "My Commands",
            customIconRes = 7
        )
        // Drill in like the UI does: name-based path, no parsing.
        val browser = CommandTree.childrenAt(tree, listOf("Chromium Browser"))!!.first()
        assertEquals("Install", browser.name)
        assertEquals(
            "apt-get install -y chromium",
            (browser as CommandNode.Leaf).shortcut.command
        )
    }

    @Test
    fun declaredEntriesReplaceTheBuiltInBranchButTheCustomSectionStays() {
        val custom = listOf(
            CustomCommand(id = "u1", name = "Update System", command = "sudo apt update", createdAt = 1)
        )
        val tree = WorkspaceCommandsAssembler.assemble(
            builtinNodes = listOf(
                QuickCommandNode(
                    id = "system", name = "System", type = QuickNodeType.CATEGORY,
                    children = listOf(action("update", "Built-in Update", "apt-get update"))
                )
            ),
            declaredShortcuts = listOf(
                Shortcut(
                    id = "rootfs:declared",
                    title = "Image Command",
                    description = "d",
                    iconRes = 1,
                    command = "declared"
                )
            ),
            custom = custom,
            customSectionTitle = "My Commands",
            customIconRes = 7
        )
        assertEquals(
            listOf("Image Command", "My Commands"),
            tree.map { it.name }
        )
        assertTrue(
            "no built-in entry may leak when the image declares its own",
            tree.none { it.name == "System" }
        )
    }

    @Test
    fun theCustomSectionAlwaysExistsAndCarriesStableRunnableLeaves() {
        val custom = listOf(
            CustomCommand(id = "u1", name = "Update System", command = "sudo apt update", createdAt = 1),
            CustomCommand(id = "u2", name = "Clean", command = "apt-get clean", createdAt = 2)
        )
        val tree = WorkspaceCommandsAssembler.assemble(
            builtinNodes = emptyList(),
            declaredShortcuts = null,
            custom = custom,
            customSectionTitle = "My Commands",
            customIconRes = 7
        )
        assertEquals(1, tree.size)
        val section = tree.single() as CommandNode.Category
        assertEquals("My Commands", section.name)
        assertEquals(listOf("Update System", "Clean"), section.children.map { it.name })
        val first = section.children[0] as CommandNode.Leaf
        // Displayed by NAME, executed by the STORED COMMAND, never a pseudo-entry.
        assertEquals("Update System", first.shortcut.title)
        assertEquals("sudo apt update", first.shortcut.command)
        assertTrue(first.shortcut.isCustom)
        // The stable id namespacing: user:<uuid>, never the one-off key.
        assertEquals("user:u1", first.shortcut.id)
        assertTrue(first.shortcut.id != ShortcutsViewModel.CUSTOM_KEY)
    }

    @Test
    fun anEmptyCustomSectionIsStillANavigableLevel() {
        val tree = WorkspaceCommandsAssembler.assemble(
            builtinNodes = emptyList(),
            declaredShortcuts = null,
            custom = emptyList(),
            customSectionTitle = "My Commands",
            customIconRes = 7
        )
        val section = tree.single() as CommandNode.Category
        assertTrue(section.children.isEmpty())
        // childrenAt returns the (empty) level so the UI can render its
        // empty state — not an unresolvable path.
        assertEquals(emptyList<CommandNode>(), CommandTree.childrenAt(tree, listOf("My Commands")))
    }

    @Test
    fun customCardsAreNeverWrittenIntoOrConfusedWithTheBuiltInCatalog() {
        // The assembler maps user records to cards at render time only; the
        // built-in node ids stay catalog ids (namespaced), and a user record
        // cannot collide with them.
        val nodes = listOf(action("update", "Update", "apt-get update"))
        val custom = listOf(CustomCommand(id = "update", name = "My Update", command = "mine", createdAt = 1))
        val tree = WorkspaceCommandsAssembler.assemble(
            builtinNodes = nodes,
            declaredShortcuts = null,
            custom = custom,
            customSectionTitle = "My Commands",
            customIconRes = 7
        )
        // The built-in branch is an action at the root (a leaf), the custom
        // section is the trailing category.
        val builtinLeaf = (tree[0] as CommandNode.Leaf)
        val customLeaf = ((tree[1] as CommandNode.Category).children.single() as CommandNode.Leaf)
        assertEquals("builtin:update", builtinLeaf.shortcut.id)
        assertEquals("user:update", customLeaf.shortcut.id)
        assertTrue(builtinLeaf.key != customLeaf.key)
        assertTrue(!builtinLeaf.shortcut.isCustom)
        assertTrue(customLeaf.shortcut.isCustom)
    }
}
