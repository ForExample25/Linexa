package com.fem.linexa.domain.commands

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One user-created, persistently saved Workspace command — the "My Commands"
 * feature.
 *
 * Kept strictly separate from the built-in distribution catalog
 * ([com.fem.linexa.service.commands.QuickCommandsCatalog]): built-ins are
 * project data shipped in the APK, user commands are per-workspace records
 * the user owns. They never mix storage.
 *
 * @param id a stable unique identifier (UUID). Deliberately NOT the command
 *   text and NOT the display name — both are editable, and an identity that
 *   changes on edit is no identity. Renaming or rewriting the command keeps
 *   this id (and therefore the record's position and run state).
 * @param name the user-facing title. Displayed verbatim; never executed.
 * @param command the guest shell command. Executed verbatim; only shown as
 *   the record's detail, never as its identity.
 * @param createdAt epoch millis of creation; the stable ordering key.
 * @param updatedAt epoch millis of the last edit, or null when never edited.
 */
@Serializable
data class CustomCommand(
    val id: String,
    val name: String,
    val command: String,
    val createdAt: Long,
    val updatedAt: Long? = null
) {
    companion object {
        /** Cap on a name, mirroring the declared-shortcut label cap (card-consent rules). */
        const val MAX_NAME_CHARS = 80

        /**
         * Cap on a command, mirroring the declared-shortcut command cap: the
         * Commands row shows the command as its description, so the same
         * "the user can read the whole thing before tapping" bound applies.
         */
        const val MAX_COMMAND_CHARS = 512

        /** Cap on how many commands one workspace may store. */
        const val MAX_COUNT = 100
    }
}

/**
 * A validated name/command pair — the result of checking user input before it
 * may become or update a [CustomCommand]. Carries its own [error] instead of
 * throwing, so a dialog can render the reason inline.
 */
data class CustomCommandInput private constructor(
    val name: String,
    val command: String
) {
    companion object {
        /**
         * Validates and normalizes user input.
         *
         * The rules are deliberately about *application handling*, not shell
         * syntax (catalog requirement 13): blank names and blank commands are
         * refused, and a command containing control characters (a newline is
         * one) is refused because the execution contract guarantees a
         * single-line command — the same rule the declared-shortcut validator
         * enforces, so no saved command can ever be one the runtime would
         * refuse to write to a shell. Every legitimate single-line shell
         * construct (chains, pipes, globs, redirections, quotes) passes.
         */
        fun validate(name: String?, command: String?): CustomCommandInput? {
            val trimmedName = name?.trim().orEmpty()
            val trimmedCommand = command?.trim().orEmpty()
            if (trimmedName.isEmpty() || trimmedName.length > CustomCommand.MAX_NAME_CHARS) return null
            if (trimmedCommand.isEmpty()) return null
            if (trimmedCommand.any { it.isISOControl() }) return null
            if (trimmedCommand.length > CustomCommand.MAX_COMMAND_CHARS) return null
            return CustomCommandInput(name = trimmedName, command = trimmedCommand)
        }
    }
}

/**
 * JSON (de)serialization of the persisted custom-command list.
 *
 * Fail-open by contract: any malformed persisted payload decodes to an empty
 * list (with the malformed record dropped, not a crash) — the attribute can
 * only become unreadable through a downgrade or a hand-edited database, and
 * losing the commands page to it would be strictly worse than showing none.
 * Individual records that decode but violate the field caps are dropped
 * deterministically.
 */
object CustomCommandsCodec {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(CustomCommand.serializer())

    /** Encodes [commands] for persistence, preserving list order. */
    fun encode(commands: List<CustomCommand>): String = json.encodeToString(listSerializer, commands)

    /**
     * Decodes a persisted payload; malformed JSON yields an empty list.
     * Records violating the field caps are dropped; survivors keep their
     * stored order.
     */
    fun decode(payload: String?): List<CustomCommand> {
        if (payload.isNullOrBlank()) return emptyList()
        val parsed = try {
            json.decodeFromString(listSerializer, payload)
        } catch (_: Exception) {
            return emptyList()
        }
        return parsed.filter { record ->
            record.id.isNotBlank() &&
                record.name.isNotBlank() && record.name.length <= CustomCommand.MAX_NAME_CHARS &&
                record.command.isNotBlank() && record.command.length <= CustomCommand.MAX_COMMAND_CHARS
        }
    }
}
