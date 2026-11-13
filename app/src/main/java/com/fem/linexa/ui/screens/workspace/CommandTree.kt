package com.fem.linexa.ui.screens.workspace

import com.fem.linexa.data.Shortcut
import com.fem.linexa.service.commands.QuickCommandNode
import com.fem.linexa.service.commands.QuickNodeType

/**
 * One node of the workspace Commands hierarchy (UI/UX Phase 5C).
 *
 * Deliberately two cases and no third: a node either **navigates** or it
 * **runs**. The Commands list used to be flat, so a command whose declared title
 * was `Software Management>Krita>Install` rendered that string literally and the
 * user saw the separator instead of the structure it encodes. Modelling the two
 * kinds separately is what makes "a parent never executes a command" a property
 * of the type rather than a rule the UI has to remember: a [Category] has no
 * [Shortcut] to run, and a [Leaf] has no children to open.
 */
sealed interface CommandNode {

    /**
     * This node's own name — a single path segment (`Krita`), never the full
     * declared title.
     *
     * The full path is deliberately *not* what a card shows: it is already
     * implied by how the user got there (the breadcrumb), and repeating it makes
     * every sibling card start with the same prefix.
     */
    val name: String

    /**
     * Stable, unique identity for list keying.
     *
     * Needed because names are not unique across the whole tree — a catalog
     * carries many leaves named exactly `Install` — and a `LazyColumn` with
     * duplicate keys throws.
     */
    val key: String

    /**
     * A level to open. Has children; has nothing to run.
     *
     * @param description the level's own one-line explanation from the catalog
     *   (`QuickCommandNode.description`), shown in place of a derived count
     *   when present. Null for levels that carry none.
     */
    data class Category(
        override val name: String,
        override val key: String,
        val children: List<CommandNode>,
        val description: String? = null
    ) : CommandNode

    /** An executable command. The end of a path. */
    data class Leaf(
        override val name: String,
        override val key: String,
        val shortcut: Shortcut
    ) : CommandNode
}

/**
 * Builds the Commands navigation tree the screen renders.
 *
 * ## Two entry shapes, one tree
 *
 *  - [build] — the **image-declared** path. A native `linexa.shortcuts` entry
 *    arrives as a flat [Shortcut] list whose `title` encodes its path with
 *    [SEPARATOR] (`Software Management>Krita>Install`): an author writing
 *    that string is stating a hierarchy, and [segmentsOf] parses it back
 *    into segments. The separator is a structural character and must never
 *    reach the screen.
 *  - [fromQuickCommands] — the **built-in catalog** path. The project's Quick
 *    Commands catalog is structured end to end ([QuickCommandNode], typed
 *    `category`/`group`/`application`/`action`), so it maps to
 *    [CommandNode]s by TYPE and never touches a separator. This is the point
 *    of the catalog's explicitness: "Install/Uninstall" children are declared
 *    `action`s on an `application`, not a naming convention to be inferred.
 *  - [customSection] — the **user's own commands** as one navigable section
 *    of runnable leaves, in their stable creation order.
 *
 * ## Guarantees
 *
 * Pure data→data, Compose-free, and therefore JVM-testable — which matters here
 * because every rule below is silent when broken:
 *
 *  1. **No command is ever dropped.** Every input [Shortcut] appears exactly once
 *     as a [CommandNode.Leaf] somewhere in the result.
 *  2. **Siblings are grouped once.** Entries sharing a prefix produce one
 *     [CommandNode.Category] per level, not one per entry.
 *  3. **Original order is preserved** at every level (first appearance wins for
 *     categories), because the author's order is the only ordering information
 *     available — sorting alphabetically would scatter `Install`/`Uninstall`
 *     pairs.
 *  4. **A parent never runs anything.** See [CommandNode] and the
 *     prefix-that-is-also-a-command case below.
 *  5. **Arbitrary depth**, with no per-level special cases.
 *
 * Nothing here knows any command, category or distribution name; the whole tree
 * is a function of the structure it is given.
 */
object CommandTree {

    /** The hierarchy separator used by declared command titles. */
    const val SEPARATOR: Char = '>'

    /** Separator shown between segments in the breadcrumb. */
    const val BREADCRUMB_SEPARATOR: String = " › "

    /** Key of the always-present "My Commands" section node. */
    const val CUSTOM_SECTION_KEY: String = "my-commands"

    /** Prefix of a custom command leaf's list key. */
    const val CUSTOM_LEAF_KEY_PREFIX: String = "mycmd:"

    /** Prefix namespacing built-in catalog node keys/ids away from declared and user ids. */
    const val BUILTIN_KEY_PREFIX: String = "builtin:"

    /**
     * Splits a declared title into its path segments.
     *
     * Blank segments are dropped, so `A>>B`, `A > B` and `A>B>` all read as the
     * same two-level path — an author's stray separator or padding must not
     * create an unnamed level the user cannot identify.
     *
     * A title with no usable segment at all (in practice unreachable: both
     * metadata paths reject a blank label) yields an empty list, and [build]
     * falls back to the raw title so the command still appears. Losing a command
     * to a parsing edge case would be worse than showing an odd name.
     */
    fun segmentsOf(title: String): List<String> =
        title.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** The tree for [shortcuts], in their own order. */
    fun build(shortcuts: List<Shortcut>): List<CommandNode> =
        group(
            entries = shortcuts.mapIndexed { index, shortcut ->
                Entry(
                    shortcut = shortcut,
                    index = index,
                    // The fallback keeps guarantee 1 (no command is dropped)
                    // true even for a title that parses to nothing.
                    segments = segmentsOf(shortcut.title).ifEmpty { listOf(shortcut.title) }
                )
            },
            prefix = ""
        )

    /**
     * The tree for the structured built-in catalog: [QuickCommandNode] →
     * [CommandNode] mapped by TYPE, not by parsing anything.
     *
     *  - [QuickNodeType.CATEGORY]/[QuickNodeType.GROUP]/[QuickNodeType.APPLICATION]
     *    are navigational — they become [CommandNode.Category]s carrying their
     *    own name, description and (mapped) children. The catalog's
     *    `application` type exists precisely so "Install/Uninstall" children
     *    never have to be inferred from their names; here that explicitness
     *    pays off as a direct mapping.
     *  - [QuickNodeType.ACTION] is the executable leaf — a [CommandNode.Leaf]
     *    wrapping the [Shortcut] the resolver produced for it.
     *
     * Every node's key/id carries [BUILTIN_KEY_PREFIX], so a catalog id can
     * never collide with a declared id, a user-command id or the one-off run
     * state key, no matter what the catalog file says.
     */
    fun fromQuickCommands(nodes: List<QuickCommandNode>): List<CommandNode> =
        nodes.mapNotNull { fromQuickCommand(it) }

    private fun fromQuickCommand(node: QuickCommandNode): CommandNode? =
        when (node.type) {
            QuickNodeType.ACTION -> CommandNode.Leaf(
                name = node.name,
                key = "$BUILTIN_KEY_PREFIX${node.id}",
                shortcut = WorkspaceShortcuts.builtinShortcut(node)
            )

            QuickNodeType.CATEGORY, QuickNodeType.GROUP, QuickNodeType.APPLICATION ->
                if (node.children.isEmpty()) {
                    // Cannot render meaningfully (and the validator makes this
                    // unreachable for a well-formed catalog).
                    null
                } else {
                    CommandNode.Category(
                        name = node.name,
                        key = "$BUILTIN_KEY_PREFIX${node.id}",
                        description = node.description,
                        children = node.children.mapNotNull { fromQuickCommand(it) }
                    )
                }
        }

    /**
     * The "My Commands" section: one category carrying the workspace's saved
     * user commands as runnable leaves, in their stable creation order.
     *
     * Rendered even when [commands] is empty — the empty state (a line under
     * the opened section) is how the screen invites the first save.
     */
    fun customSection(
        title: String,
        commands: List<Shortcut>
    ): CommandNode.Category = CommandNode.Category(
        name = title,
        key = CUSTOM_SECTION_KEY,
        children = commands.map { command ->
            CommandNode.Leaf(
                name = command.title,
                key = "$CUSTOM_LEAF_KEY_PREFIX${command.id}",
                shortcut = command
            )
        }
    )

    /**
     * The children shown at [path], or **null** when [path] no longer resolves.
     *
     * Null rather than an empty list on purpose: "this category is empty" cannot
     * happen for a *catalog* category (a category exists only because something
     * is under it), so an unresolvable path always means the tree changed
     * underneath the UI — the workspace's metadata was re-read, or the user
     * switched workspace while drilled in. The caller must reset to the root
     * rather than render a blank level, and an empty list would make that
     * indistinguishable from success. (The one legitimately-empty level — the
     * My Commands section before the first save — is an empty-list return the
     * caller renders as its empty state.)
     */
    fun childrenAt(nodes: List<CommandNode>, path: List<String>): List<CommandNode>? {
        var level = nodes
        for (name in path) {
            val category = level.firstOrNull { it is CommandNode.Category && it.name == name }
                as? CommandNode.Category
                ?: return null
            level = category.children
        }
        return level
    }

    /**
     * How many executable commands sit under [node], at any depth.
     *
     * This is what a category card shows in place of a description when the
     * level carries none of its own. It is *derived*, not invented.
     */
    fun leafCount(node: CommandNode): Int = when (node) {
        is CommandNode.Leaf -> 1
        is CommandNode.Category -> node.children.sumOf { leafCount(it) }
    }

    private data class Entry(
        val shortcut: Shortcut,
        val index: Int,
        val segments: List<String>
    )

    /**
     * Groups one level by its first segment and recurses.
     *
     * The prefix-that-is-also-a-command case is handled here and is the reason
     * this is not a plain `groupBy`: metadata may declare both
     * `Software Management>Krita` and `Software Management>Krita>Install`, which
     * makes `Krita` a category *and* a command. Requirement 9 — a parent with
     * children must never execute — wins, so `Krita` becomes a category and its
     * own command is re-parented as the **first child** of that category. It
     * stays reachable and runnable rather than being silently swallowed by the
     * category it collided with, and the category itself stays inert.
     */
    private fun group(entries: List<Entry>, prefix: String): List<CommandNode> {
        val groups = LinkedHashMap<String, MutableList<Entry>>()
        entries.forEach { entry ->
            groups.getOrPut(entry.segments.first()) { mutableListOf() } += entry
        }

        val out = mutableListOf<CommandNode>()
        groups.forEach { (name, group) ->
            val path = if (prefix.isEmpty()) name else "$prefix$SEPARATOR$name"
            // Entries that end at this level are commands named `name`;
            // everything else describes something below it.
            val ownCommands = group
                .filter { it.segments.size == 1 }
                .map { CommandNode.Leaf(name = name, key = leafKey(it), shortcut = it.shortcut) }
            val deeper = group
                .filter { it.segments.size > 1 }
                .map { it.copy(segments = it.segments.drop(1)) }

            if (deeper.isEmpty()) {
                out += ownCommands
            } else {
                out += CommandNode.Category(
                    name = name,
                    key = "$CATEGORY_KEY_PREFIX$path",
                    children = ownCommands + group(deeper, path)
                )
            }
        }
        return out
    }

    /**
     * A leaf's key.
     *
     * Carries the entry's position as well as its [Shortcut.id]: ids are unique
     * by construction today (the native validator rejects duplicates and
     * built-in/user ids are namespaced), but a `LazyColumn` throws on a
     * duplicate key, and a crash is too high a price for trusting an invariant
     * that lives in another layer.
     */
    private fun leafKey(entry: Entry): String =
        "$LEAF_KEY_PREFIX${entry.index}:${entry.shortcut.id}"

    private const val LEAF_KEY_PREFIX = "leaf:"
    private const val CATEGORY_KEY_PREFIX = "cat:"
}
