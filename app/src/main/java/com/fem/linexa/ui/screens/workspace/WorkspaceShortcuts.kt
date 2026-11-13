package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.R
import com.fem.linexa.data.Shortcut
import com.fem.linexa.service.commands.QuickCommandNode
import com.fem.linexa.service.commands.QuickNodeType
import com.fem.linexa.service.rootfs.DeclaredShortcut

/**
 * Maps the two *external* command declarations onto the [Shortcut] model the
 * Commands tree renders — nothing more.
 *
 * The old "which commands exist" question is gone from here: built-ins now
 * come from the project's Quick Commands catalog resolved by
 * [com.fem.linexa.service.commands.QuickCommandsResolver] (which owns every
 * distribution/version decision), user commands come from
 * [com.fem.linexa.domain.commands.CustomCommandsStore]. What this object
 * still owns is the two places where data from *other* files becomes the UI's
 * card model:
 *
 *  - [builtinShortcut] — one resolved catalog node → a card. The catalog is
 *    the app's own declaration for that distribution, so this is a straight
 *    field mapping (the typed node already carries name/description/command/
 *    icon/as-root explicitly).
 *  - [declaredShortcut] — one `linexa.shortcuts` entry declared by an
 *    installed image → a card, attributed to the distribution the image
 *    itself declared.
 *
 * There is deliberately NO fallback policy left here. An image that declares
 * its own shortcuts replaces the built-in branch entirely, an image that
 * declares none gets the catalog branch, and an unknown distribution gets the
 * catalog's portable `common` branch only — all of that is resolution policy
 * and lives with the resolver, not in a UI-package mapper.
 *
 * Pure and JVM-testable: it maps data to data, resolves icon keys against a
 * fixed drawable table, and never touches the runtime.
 */
object WorkspaceShortcuts {

    /**
     * Id of the app-owned "run a one-off command" affordance.
     *
     * It is the **run-state key** the one-off run is recorded under
     * ([ShortcutsViewModel.CUSTOM_KEY]); a catalog id, a declared id or a
     * user-command id is never allowed to claim it (catalog ids are
     * namespaced `builtin:`, user commands `user:`, declared ids
     * [DECLARED_ID_PREFIX]).
     */
    const val CUSTOM_ID = "custom"

    /** Prefix applied to image-declared ids before they become [Shortcut.id]s. */
    const val DECLARED_ID_PREFIX = "rootfs:"

    /**
     * Icon keys an image (or the catalog) may name, mapped to bundled
     * drawables.
     *
     * A fixed table rather than a resource-name lookup (`getIdentifier`) on
     * purpose: a string→resource lookup would let metadata reference *any*
     * drawable in the app, including ones with unrelated meaning, and would
     * turn an APK-internal resource name into part of the public metadata
     * contract. An unknown or absent key falls back to the terminal icon,
     * which is honest — the card runs a command.
     */
    val ICONS: Map<String, Int> = mapOf(
        "refresh" to R.drawable.ic_haven_refresh,
        "update" to R.drawable.ic_haven_refresh,
        "download" to R.drawable.ic_haven_download,
        "install" to R.drawable.ic_haven_download,
        "delete" to R.drawable.ic_haven_trash,
        "remove" to R.drawable.ic_haven_trash,
        "search" to R.drawable.ic_haven_file,
        "list" to R.drawable.ic_haven_file,
        "info" to R.drawable.ic_haven_info,
        "storage" to R.drawable.ic_haven_storage,
        "disk" to R.drawable.ic_haven_storage,
        "memory" to R.drawable.ic_haven_memory,
        "cpu" to R.drawable.ic_haven_cpu,
        "network" to R.drawable.ic_haven_globe,
        "terminal" to R.drawable.ic_haven_terminal,
        "settings" to R.drawable.ic_haven_settings,
        "desktop" to R.drawable.ic_haven_monitor,
        "ssh" to R.drawable.ic_haven_ssh,
        "audio" to R.drawable.ic_haven_audio,
        "clipboard" to R.drawable.ic_haven_clipboard,
        "folder" to R.drawable.ic_haven_folder,
        "warning" to R.drawable.ic_haven_warning
    )

    /** The icon used when nothing (or an unknown key) was declared. */
    val FALLBACK_ICON_RES: Int = R.drawable.ic_haven_terminal

    /** The icon keys an image may use, for documentation and error messages. */
    fun iconKeys(): List<String> = ICONS.keys.sorted()

    /** Resolves an icon key against the fixed table; absent/unknown → the terminal fallback. */
    fun iconFor(key: String?): Int = key?.let { ICONS[it.lowercase()] } ?: FALLBACK_ICON_RES

    /**
     * One resolved catalog ACTION node → the card the Commands tree renders.
     *
     * The caller (CommandTree.fromQuickCommands) guarantees this is called for
     * [QuickNodeType.ACTION] nodes only — the only type the catalog schema
     * allows to carry a command. The node's own description is shown when it
     * declared one, the command itself otherwise: showing the command is not
     * a placeholder, it is the honest answer to "what does this do".
     */
    fun builtinShortcut(node: QuickCommandNode): Shortcut = Shortcut(
        id = CommandTree.BUILTIN_KEY_PREFIX + node.id,
        title = node.name,
        description = node.description?.takeIf { it.isNotBlank() } ?: node.command.orEmpty(),
        // Schema note: an `action:` tag selects the icon when the entry
        // declares no explicit `icon` — install/remove/update map to the same
        // glyphs their icon keys would, matching the native shortcuts schema's
        // behavior.
        iconRes = iconFor(node.icon ?: node.actionKind?.yaml),
        command = node.command,
        distributionLabel = null,
        asRoot = node.asRoot
    )

    /**
     * One image-declared entry → a card.
     *
     * The description shown is the author's when they gave one, and the command
     * itself otherwise. The validator already guarantees it is single-line and
     * short enough to display in full.
     *
     * @param distributionLabel the image's own declared distribution line
     *   (`DeclaredDistro.displayLabel`), or null when it declared none — the
     *   line attributes the command to a distribution somebody actually
     *   claimed.
     */
    fun declaredShortcut(declared: DeclaredShortcut, distributionLabel: String?): Shortcut = Shortcut(
        id = DECLARED_ID_PREFIX + declared.id,
        title = declared.label,
        description = declared.description?.takeIf { it.isNotBlank() } ?: declared.command,
        iconRes = iconFor(declared.icon ?: declared.action),
        command = declared.command.takeIf { it.isNotBlank() },
        distributionLabel = distributionLabel,
        asRoot = declared.asRoot
    )

    /** The declared id prefix, exposed for tests that pin the namespacing. */
    fun declaredIdPrefix(): String = DECLARED_ID_PREFIX
}
