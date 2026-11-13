package com.fem.linexa.domain.commands

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.workspace.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton

/** The workspace-attribute key the pins payload is stored under. */
const val ATTR_SHORTCUT_PINS = "linexa.shortcut_pins"

/**
 * Persistent storage for a workspace's pinned and favorited command ids
 * (App-grid redesign) — behind an interface so the screens' ViewModels stay
 * JVM-testable against a fake, exactly like [CustomCommandsStore]'s peers.
 *
 * ## Where the records live
 *
 * In the workspace's `WorkspaceMetadata.attributes` map (the Room row's
 * `Map<String,String>` converter) under [ATTR_SHORTCUT_PINS], encoded by
 * [ShortcutPinsCodec]. That is the project's established per-workspace store
 * for structured extra facts — the same mechanism the feature settings,
 * network settings and custom commands use — and deliberately NOT a new
 * table: the database is declared `version = 1` with no migration, so a
 * schema change would break every existing install.
 *
 * Workspace ownership falls out of the storage for free: attributes live on
 * the workspace's row, keyed by the workspace's stable id, so pins created
 * for workspace A can never surface in workspace B.
 */
interface ShortcutPinsStore {

    /**
     * Loads the workspace's pins. A workspace with no attribute yet, a blank
     * payload or a malformed one all decode to empty pins — the honest
     * "nothing pinned yet" rather than a fabricated default.
     */
    suspend fun load(workspaceId: String): ShortcutPins

    /**
     * Persists [updated] (the caller's already-mutated pins value). Returns
     * what was persisted, or [fallback] unchanged when the write failed —
     * a failed pin must never crash the grid or desynchronize the UI from
     * storage.
     */
    suspend fun persist(workspaceId: String, updated: ShortcutPins, fallback: ShortcutPins): ShortcutPins
}

/** The production store: reads and writes the workspace attributes map. */
@Singleton
class MetadataShortcutPinsStore @Inject constructor(
    private val repository: WorkspaceRepository
) : ShortcutPinsStore {

    override suspend fun load(workspaceId: String): ShortcutPins {
        val payload = repository.getById(workspaceId)
            ?.attributes
            ?.get(ATTR_SHORTCUT_PINS)
        return ShortcutPinsCodec.decode(payload)
    }

    override suspend fun persist(
        workspaceId: String,
        updated: ShortcutPins,
        fallback: ShortcutPins
    ): ShortcutPins = try {
        repository.updateAttributes(
            workspaceId,
            mapOf(ATTR_SHORTCUT_PINS to ShortcutPinsCodec.encode(updated))
        )
        updated
    } catch (e: Exception) {
        Logger.e(TAG, "shortcut pins persistence failed", e)
        fallback
    }

    private companion object {
        const val TAG = "ShortcutPinsStore"
    }
}
