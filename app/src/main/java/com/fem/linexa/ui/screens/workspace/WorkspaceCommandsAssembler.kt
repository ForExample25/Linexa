package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.data.Shortcut
import com.fem.linexa.domain.commands.CustomCommand
import com.fem.linexa.service.commands.QuickCommandNode

/**
 * Assembles the Workspace Commands screen's ONE presentation tree from its
 * three distinct sources — the place where "which sections exist, in what
 * order" is decided, so neither the ViewModel nor the UI has to.
 *
 * ## The sources, and why they stay distinct until here
 *
 *  - **Built-in** — the project-owned Quick Commands catalog, already
 *    resolved to a typed [QuickCommandNode] tree for THIS workspace's
 *    distribution/version by [com.fem.linexa.service.commands.QuickCommandsResolver].
 *  - **Declared** — the installed image's own `linexa.shortcuts`, as
 *    [Shortcut]s (the image author's statement; an author who declares
 *    commands replaces the built-in catalog entirely — the §1.120 contract,
 *    unchanged).
 *  - **Custom** — the workspace's saved user commands
 *    ([com.fem.linexa.domain.commands.CustomCommandsStore]); always rendered,
 *    under their own section, never merged into either catalog's data.
 *
 * The assembly order is fixed and deliberate: the primary catalog (declared
 * or built-in) first, the user's own section last — the user's commands stay
 * one glance away without ever burying the distro-appropriate entries.
 *
 * Pure and JVM-testable: data in, [CommandNode]s out.
 */
object WorkspaceCommandsAssembler {

    /**
     * @param builtinNodes the resolved built-in catalog tree (possibly empty —
     *   a catalog load failure renders no built-ins rather than crashing).
     * @param declaredShortcuts the image's declared commands, or null when it
     *   declared none. Non-null REPLACES the built-in branch (see the class
     *   kdoc); an empty list is the author's deliberate "none".
     * @param custom the workspace's saved user commands, in storage order.
     * @param customSectionTitle the localized "My Commands" section title.
     * @param customIconRes the icon a custom command card carries.
     */
    fun assemble(
        builtinNodes: List<QuickCommandNode>,
        declaredShortcuts: List<Shortcut>?,
        custom: List<CustomCommand>,
        customSectionTitle: String,
        customIconRes: Int
    ): List<CommandNode> {
        val primary = if (declaredShortcuts != null) {
            CommandTree.build(declaredShortcuts)
        } else {
            CommandTree.fromQuickCommands(builtinNodes)
        }
        return primary + CommandTree.customSection(
            title = customSectionTitle,
            commands = custom.map { customShortcut(it, customIconRes) }
        )
    }

    /**
     * The card for one saved user command: displayed by NAME, executed by its
     * STORED COMMAND. The command doubles as the card's description — the same
     * honest convention the declared entries follow — the id is namespaced
     * `user:` so it can never collide with a catalog id, a declared id or the
     * one-off run-state key, and [Shortcut.isCustom] marks the record as the
     * user's own so the row can offer Edit/Delete.
     */
    fun customShortcut(command: CustomCommand, iconRes: Int): Shortcut = Shortcut(
        id = CUSTOM_ID_PREFIX + command.id,
        title = command.name,
        description = command.command,
        iconRes = iconRes,
        command = command.command,
        isCustom = true
    )

    /** Prefix of every user-command [Shortcut.id] (and therefore run-state key). */
    const val CUSTOM_ID_PREFIX = "user:"
}
