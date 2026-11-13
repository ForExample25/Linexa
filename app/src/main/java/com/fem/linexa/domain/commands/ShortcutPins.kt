package com.fem.linexa.domain.commands

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The workspace's pinned and favorited command ids (App-grid redesign).
 *
 * Two independent, order-preserving sets of [Shortcut] ids:
 *
 *  - **Pinned** ("Pin to top") — the ids the user lifted to the top of the
 *    Commands grid. A pinned id is rendered at the root level even when the
 *    command itself lives deep in the tree, which is the entire point: the
 *    catalog's most-used leaves should not need a drill-in every time.
 *  - **Favorites** ("Add to favorites") — a quieter bookmark. Favorites do
 *    not move a command; they mark it (star) and sort it ahead of its
 *    unmarked siblings inside their own level.
 *
 * The two sets are independent on purpose — a user can favorite without
 * pinning — and both are **id lists, not maps**: order is the user's ordering
 * (most recent pin first, mirroring how launchers order pinned apps), so the
 * codec must never silently sort.
 *
 * @property pinned pinned [Shortcut.id]s, most recent first.
 * @property favorites favorite [Shortcut.id]s, most recent first.
 */
@Serializable
data class ShortcutPins(
    val pinned: List<String> = emptyList(),
    val favorites: List<String> = emptyList()
) {
    /** True when [id] is pinned. O(n) with a tiny n; fine for grid sizes. */
    fun isPinned(id: String): Boolean = id in pinned

    /** True when [id] is favorited. */
    fun isFavorite(id: String): Boolean = id in favorites

    /**
     * A new pins value with [id]'s pinned state set to [pinned].
     *
     * Pinning prepends (most recent first); unpinning removes without
     * touching favorites. An id already in the requested state round-trips
     * unchanged, so a double tap cannot reorder the list.
     */
    fun withPinned(id: String, pinned: Boolean): ShortcutPins = when {
        pinned && id !in this.pinned -> copy(pinned = listOf(id) + this.pinned)
        !pinned -> copy(pinned = this.pinned - id)
        else -> this
    }

    /** The favorite-state twin of [withPinned], with the same contract. */
    fun withFavorite(id: String, favorite: Boolean): ShortcutPins = when {
        favorite && id !in favorites -> copy(favorites = listOf(id) + favorites)
        !favorite -> copy(favorites = favorites - id)
        else -> this
    }

    companion object {
        /** Hard cap per list: a grid stays legible and the attribute stays small. */
        const val MAX_PER_LIST = 24
    }
}

/**
 * JSON (de)serialization of [ShortcutPins] — the same fail-open contract as
 * [CustomCommandsCodec]: the attribute can only become unreadable through a
 * downgrade or a hand-edited database, and losing the whole grid's
 * arrangement to that would be strictly worse than showing it unpinned.
 *
 * Decoding also enforces the [ShortcutPins.MAX_PER_LIST] cap deterministically
 * (first N survive, in stored order) and drops blank ids.
 */
object ShortcutPinsCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** Encodes [pins] for persistence. */
    fun encode(pins: ShortcutPins): String = json.encodeToString(ShortcutPins.serializer(), pins)

    /** Decodes a persisted payload; malformed JSON (or null) yields empty pins. */
    fun decode(payload: String?): ShortcutPins {
        if (payload.isNullOrBlank()) return ShortcutPins()
        val parsed = try {
            json.decodeFromString(ShortcutPins.serializer(), payload)
        } catch (_: Exception) {
            return ShortcutPins()
        }
        return ShortcutPins(
            pinned = parsed.pinned
                .filter { it.isNotBlank() }
                .take(ShortcutPins.MAX_PER_LIST),
            favorites = parsed.favorites
                .filter { it.isNotBlank() }
                .take(ShortcutPins.MAX_PER_LIST)
        )
    }
}

/**
 * The App grid's ONE ordering rule, as pure data→data (JVM-testable, like the
 * rest of the command-tree layer).
 */
object ShortcutPinsOrdering {

    /**
     * Orders [ids] — the ids of one grid level — under [pins].
     *
     *  1. Pinned ids first, in the pins' own (most-recent-first) order;
     *  2. then favorites, then everything else, each preserving the incoming
     *     order (the tree's author order);
     *  3. an id that is neither pinned nor favorited keeps its position
     *     relative to other unmarked ids.
     *
     * Pinned ids that do not appear in [ids] (a stale pin after the image
     * changed) are simply absent from the result — the grid never invents a
     * row for them. Duplicate ids in [ids] keep every occurrence's relative
     * position (the input contract is "unique per level", but the function
     * stays honest if that is ever violated).
     */
    fun ordered(ids: List<String>, pins: ShortcutPins): List<String> {
        val pinnedSet = pins.pinned.toHashSet()
        val favoriteSet = pins.favorites.toHashSet()
        val pinnedInLevel = pins.pinned.filter { ids.contains(it) }
        val rest = ids.filterNot { it in pinnedSet }
        val favoritesFirst = rest.sortedByDescending { it in favoriteSet }
        return pinnedInLevel + favoritesFirst
    }

    /**
     * Reorders a list of items by their ids under [pins], preserving the
     * incoming order inside each band (pinned / favorited / rest). Generic so
     * the grid can order [CommandNode]s and the search can order leaves with
     * the same rule.
     */
    fun <T> orderedBy(items: List<T>, idOf: (T) -> String, pins: ShortcutPins): List<T> {
        val pinnedSet = pins.pinned.toHashSet()
        val favoriteSet = pins.favorites.toHashSet()
        // Band 0 = pinned (pins order), band 1 = favorite, band 2 = rest.
        // Pinned items sort by their position in pins.pinned so a pinned id
        // absent from the front still lands in the user's chosen order.
        val pinRank = HashMap<String, Int>(pins.pinned.size)
        pins.pinned.forEachIndexed { index, id -> pinRank[id] = index }
        return items.sortedWith(
            compareBy(
                { item ->
                    val id = idOf(item)
                    when {
                        id in pinnedSet -> 0
                        id in favoriteSet -> 1
                        else -> 2
                    }
                },
                { item -> pinRank[idOf(item)] ?: Int.MAX_VALUE }
            )
        )
    }
}
