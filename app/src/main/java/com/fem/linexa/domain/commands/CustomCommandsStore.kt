package com.fem.linexa.domain.commands

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.workspace.WorkspaceRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent storage for a workspace's custom commands.
 *
 * ## Where the records live
 *
 * In the workspace's `WorkspaceMetadata.attributes` map (the Room row's
 * `Map<String,String>` converter) under [ATTR_CUSTOM_COMMANDS], encoded by
 * [CustomCommandsCodec]. That is the project's established per-workspace
 * store for structured extra facts — the same mechanism the feature settings,
 * network settings and detected-package-manager key use — and deliberately
 * NOT a new table: the database is declared `version = 1` with no migration,
 * so a schema change would break every existing install (see FeatureSettings'
 * kdoc, which documents this exact decision).
 *
 * Workspace ownership falls out of the storage for free: attributes live on
 * the workspace's row, keyed by the workspace's stable id, so a command
 * created for workspace A can never surface in workspace B.
 *
 * ## Read-modify-write
 *
 * The caller supplies the record list it is currently rendering (decoded from
 * the same attributes map), this store validates the mutation, re-encodes and
 * persists the whole list. Single-writer by construction — the workspace
 * detail screen is the only surface that mutates it — so a whole-list write
 * cannot lose a concurrent edit.
 */
@Singleton
class CustomCommandsStore @Inject constructor(
    private val repository: WorkspaceRepository
) {

    /**
     * Adds [name]/[command] as a new record (new UUID, appended after the
     * existing ones — stable creation order), or, when [editId] names an
     * existing record, updates THAT record in place (same id, same position,
     * newer [CustomCommand.updatedAt]) instead of creating a duplicate.
     *
     * Returns the resulting list — also what was persisted. Returns the
     * caller's list unchanged when validation failed, the edit target does
     * not exist, the cap is reached, or the write itself failed: a failed
     * save must never lose data or desynchronize the UI from storage.
     */
    suspend fun save(
        workspaceId: String,
        current: List<CustomCommand>,
        name: String?,
        command: String?,
        editId: String? = null
    ): List<CustomCommand> {
        val input = CustomCommandInput.validate(name, command)
        if (input == null) {
            Logger.w(TAG, "custom command rejected by validation (edit=$editId)")
            return current
        }
        val now = System.currentTimeMillis()
        val updated: List<CustomCommand> = if (editId != null) {
            val existing = current.firstOrNull { it.id == editId } ?: run {
                Logger.w(TAG, "custom command edit for unknown id — refusing to create a duplicate")
                return current
            }
            current.map {
                if (it.id == editId) {
                    it.copy(name = input.name, command = input.command, updatedAt = now)
                } else {
                    it
                }
            }
        } else {
            if (current.size >= CustomCommand.MAX_COUNT) {
                Logger.w(TAG, "custom command cap (${CustomCommand.MAX_COUNT}) reached — save refused")
                return current
            }
            current + CustomCommand(
                id = UUID.randomUUID().toString(),
                name = input.name,
                command = input.command,
                createdAt = now
            )
        }
        return persist(workspaceId, current, updated)
    }

    /**
     * Deletes the record with [id]. Built-in commands, the distro catalog and
     * every other workspace fact are untouched — only this attribute is
     * rewritten. Returns the resulting list (also persisted), or the caller's
     * list unchanged when nothing matched or the write failed.
     */
    suspend fun delete(workspaceId: String, current: List<CustomCommand>, id: String): List<CustomCommand> {
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return current // nothing matched; no write
        return persist(workspaceId, current, updated)
    }

    /** Persists [updated]; on failure logs and returns [fallback] unchanged. */
    private suspend fun persist(
        workspaceId: String,
        fallback: List<CustomCommand>,
        updated: List<CustomCommand>
    ): List<CustomCommand> = try {
        repository.updateAttributes(workspaceId, mapOf(ATTR_CUSTOM_COMMANDS to CustomCommandsCodec.encode(updated)))
        updated
    } catch (e: Exception) {
        Logger.e(TAG, "custom command persistence failed", e)
        fallback
    }

    companion object {
        const val TAG = "CustomCommandsStore"

        /**
         * The attributes key carrying the workspace's encoded custom commands.
         * Namespaced `linexa.` like every other structured attribute.
         */
        const val ATTR_CUSTOM_COMMANDS = "linexa.custom_commands"
    }
}
