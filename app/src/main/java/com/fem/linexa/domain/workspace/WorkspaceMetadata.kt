package com.fem.linexa.domain.workspace

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full metadata for a Workspace (§1.9). This is the Room entity and the
 * domain model. Schema version enables future Room migrations.
 */
@Entity(tableName = "workspaces")
@TypeConverters(
    WorkspaceStatusConverter::class,
    MapConverter::class,
    BooleanMapConverter::class,
    DistroInfoConverter::class,
    RuntimeConfigConverter::class,
    TerminalSettingsConverter::class,
    DesktopSettingsConverter::class
)
data class WorkspaceMetadata(
    @PrimaryKey
    val id: String,
    val schemaVersion: Int = 1,
    val status: WorkspaceStatus,
    val distro: DistroInfo,
    val displayName: String,
    val avatarLetter: String,
    val avatarColorHex: String,
    /**
     * Phase 9B (OPT-ARCH-002): **diagnostic only.** The workspace ID is the
     * single rootfs identity: every production launch path derives the path
     * from the id via `WorkspaceFileManager.getRootfsPath(id)`, and no
     * launch path reads this field. It is still WRITTEN from the same
     * authority (install/import), so it records where the rootfs is
     * expected to live — useful in exports/debug captures — and the
     * startup backfill repairs a row whose stored value disagrees with the
     * derived path (`WorkspaceMetadataBackfillManager`). Like the inert
     * plugin column below, it is kept rather than repurposed because
     * removing a column changes the Room schema.
     */
    val rootfsPath: String,
    val createdAt: Long,
    val lastLaunchedAt: Long? = null,
    val runtimeConfig: RuntimeConfig,
    val terminalSettings: TerminalSettings,
    val desktopSettings: DesktopSettings? = null,
    val featureToggles: Map<String, Boolean>,
    /**
     * §1.117: **inert.** A leftover of the §1.25 plugin cut with zero readers
     * and zero writers — no code path ever populates it. It is kept rather
     * than deleted because removing a column changes the Room schema and
     * would require a migration + version bump for no functional gain, and it
     * is where a future plugin layer would store per-workspace config. Do not
     * treat it as live state or start reading it without reinstating that
     * layer (which reopens §1.3/§1.17).
     */
    val pluginConfig: Map<String, String> = emptyMap(),
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class DistroInfo(
    val name: String,
    val version: String,
    val arch: String
)

@Serializable
data class RuntimeConfig(
    val bindMounts: List<String> = emptyList(),
    val environmentVars: Map<String, String> = emptyMap(),
    /**
     * §1.73: per-workspace launch-shell override (absolute guest path), or
     * null to keep the default `resolveShellPath` fallback
     * (`/bin/bash` → `/usr/bin/bash` → `/bin/sh`). Mirrored from
     * `config.yaml` `linexa.launch.shell` at import; the launch path reads
     * config.yaml directly (fail-open, §1.27), so this is a snapshot, never
     * authoritative at launch. Absent in workspaces created before §1.73
     * (deserializes to null via the default).
     */
    val launchShell: String? = null,
    /**
     * §1.73: argv fragments for the interactive login shell (replaces the
     * default `--login` when non-empty). Requires [launchShell] — enforced
     * by the config.yaml parser at import. Typed argv tokens only; never
     * shell-interpreted.
     */
    val launchShellArgs: List<String> = emptyList(),
    /**
     * §1.73: additional PRoot bind mounts beyond the built-in defaults
     * (`/dev`, `/proc`, `/sys`, shared storage), each a typed host/guest
     * pair appended as a single `-b host:guest` argv token. Additive only —
     * the default mounts can never be removed or replaced.
     */
    val extraBindMounts: List<BindMount> = emptyList()
)

/** §1.73: a typed PRoot bind mount pair (host path on device → guest path in rootfs). */
@Serializable
data class BindMount(
    val host: String,
    val guest: String
)

@Serializable
data class TerminalSettings(
    val fontSize: Int = 14,
    val fontFamily: String = "monospace",
    val colorScheme: String = "dark"
)

@Serializable
data class DesktopSettings(
    val displayScale: Float = 1.0f,
    val colorDepth: Int = 32
)

class WorkspaceStatusConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromStatus(status: WorkspaceStatus): String = json.encodeToString(status)

    @TypeConverter
    fun toStatus(json: String): WorkspaceStatus = runCatching { this.json.decodeFromString<WorkspaceStatus>(json) }
        .getOrElse { WorkspaceStatus.Ready }
}

class MapConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromMap(map: Map<String, String>): String = json.encodeToString(map)

    @TypeConverter
    fun toMap(json: String): Map<String, String> = runCatching { this.json.decodeFromString<Map<String, String>>(json) }
        .getOrElse { emptyMap() }
}

class BooleanMapConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromMap(map: Map<String, Boolean>): String = json.encodeToString(map)

    @TypeConverter
    fun toMap(json: String): Map<String, Boolean> = runCatching { this.json.decodeFromString<Map<String, Boolean>>(json) }
        .getOrElse { emptyMap() }
}

class DistroInfoConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromDistroInfo(distro: DistroInfo): String = json.encodeToString(distro)

    @TypeConverter
    fun toDistroInfo(json: String): DistroInfo = runCatching { this.json.decodeFromString<DistroInfo>(json) }
        .getOrElse { DistroInfo(name = "Unknown", version = "", arch = "") }
}

class RuntimeConfigConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromRuntimeConfig(config: RuntimeConfig): String = json.encodeToString(config)

    @TypeConverter
    fun toRuntimeConfig(json: String): RuntimeConfig = runCatching { this.json.decodeFromString<RuntimeConfig>(json) }
        .getOrElse { RuntimeConfig() }
}

class TerminalSettingsConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromTerminalSettings(settings: TerminalSettings): String = json.encodeToString(settings)

    @TypeConverter
    fun toTerminalSettings(json: String): TerminalSettings = runCatching { this.json.decodeFromString<TerminalSettings>(json) }
        .getOrElse { TerminalSettings() }
}

class DesktopSettingsConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromDesktopSettings(settings: DesktopSettings?): String {
        return if (settings != null) json.encodeToString(settings) else ""
    }

    @TypeConverter
    fun toDesktopSettings(json: String): DesktopSettings? {
        return if (json.isBlank()) null else runCatching { this.json.decodeFromString<DesktopSettings>(json) }.getOrNull()
    }
}
