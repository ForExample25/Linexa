package com.fem.linexa.service.workspaceconfig

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.hostinfo.HostInfoKey
import com.fem.linexa.domain.workspace.BindMount
import com.fem.linexa.service.rootfs.RootFsMetadataParser
import org.yaml.snakeyaml.Yaml
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-workspace configuration file, §1.26 (decision c of §1.25):
 * an optional `config.yaml` (or `linexa.yaml`) read from the *root of a
 * rootfs archive* at import time and persisted at
 * `{workspace}/config.yaml` next to `rootfs/`. The schema is Linexa's own
 * (deliberately tiny — the archive is still validated by
 * [com.fem.linexa.core.security.RealArchiveValidator] on every entry, and
 * this file is only ever a small configuration surface, never an
 * executable):
 *
 * ```yaml
 * linexa:                      # the only top-level key recognized
 *   features:                  # optional per-workspace feature overrides
 *     <feature-id>: true       #   values must be booleans
 *   env:                       # optional extra environment for PRoot launches
 *     <KEY>: <string>          #   values must be scalars
 *   launch:                    # optional launch overrides (§1.73)
 *     shell: /bin/zsh          #   optional absolute guest path to exec;
 *                              #   absent → resolveShellPath fallback
 *                              #   (/bin/bash → /usr/bin/bash → /bin/sh)
 *     shellArgs:               #   optional argv for that shell (replaces the
 *       - --login              #   default `--login`); requires `shell`
 *     bindMounts:              #   optional extra PRoot bind mounts
 *       - host: /sdcard/x      #     host path on the device (absolute)
 *         guest: /mnt/x        #     guest path inside the rootfs (absolute)
 *   host_info:                 # optional Android-host fact exposure
 *     enable: true             #   false removes LINEXA_HOST_* vars entirely;
 *                              #   absent → enabled with defaults
 *     keys:                    #   WHICH facts; unknown names FAIL import.
 *       - CPU_CORES            #   absent → conservative default set
 *       - UPTIME_SECONDS
 * ```
 *
 * Anything else — an unknown top-level key, a non-map shape, non-boolean
 * feature values, non-scalar env values, a file over 64 KiB — makes the
 * import fail with a precise reason (the same fail-hard stance as
 * tiny_container's `.tiny.yaml` handling: better to reject a malformed
 * config at import than to half-apply it later).
 *
 * §1.73 security model — the same discipline as env (§1.27/§1.49):
 * `launch.shell` and `launch.shellArgs` are typed, structured fields that
 * map directly into [com.fem.linexa.service.runtime.container.PRootLauncher.LaunchSpec]'s
 * argv array — there is NO raw command string anywhere in this path and
 * nothing is ever shell-interpreted. `launch.bindMounts` is additive only:
 * it can add binds, never remove or replace the host-owned defaults
 * (`/dev`, `/proc`, `/sys`, shared storage) or any other host-owned argv
 * segment (rootfs, cwd, `-0`, flags). A bind exposes host paths to the
 * guest at runtime — the guest already runs with the app's own sandbox
 * permissions, so an author should bind only what the workspace genuinely
 * needs. Validation is structural (absolute paths, no whitespace/NUL, a
 * sanity cap on bind count); content is the workspace author's choice.
 *
 * Catalog-override rule (§1.25): the distro catalog entry chosen by the
 * user is authoritative for the distro itself; this file only carries
 * per-workspace feature/env/launch overrides, so there is no id to
 * conflict. [WorkspaceConfig.features] is applied on top of the
 * workspace's own feature toggles when the first real feature consumer
 * exists (§1.27 — the merge point is recorded in `ImportService`, not
 * here); [WorkspaceConfig.env] is merged into the PRoot launch environment
 * by `PRootContainerRuntime` at launch (workspace config wins, §1.27).
 *
 * Warning: merged env fully replaces a same-named launcher default. In
 * particular, setting `LD_LIBRARY_PATH` (or `PATH`) here will override the
 * host-side library/executable paths the bundled tmux/PRoot rely on and can
 * break launching — the exclusion from the merge is a deliberate §1.27
 * "config wins" semantics, just documented here so authors know.
 */
@Singleton
class WorkspaceConfigParser @Inject constructor() {

    sealed interface Result {
        data class Valid(val config: WorkspaceConfig) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(bytes: ByteArray): Result {
        if (bytes.size > MAX_CONFIG_BYTES) {
            return Result.Invalid("config file exceeds $MAX_CONFIG_BYTES bytes (${bytes.size})")
        }
        val root = try {
            Yaml().load<Any?>(bytes.inputStream().buffered())
        } catch (e: Exception) {
            return Result.Invalid("not valid YAML: ${e.message}")
        }
        if (root == null) return Result.Invalid("file is empty")
        if (root !is Map<*, *>) return Result.Invalid("top level must be a mapping")
        if (root.isEmpty()) return Result.Valid(WorkspaceConfig())

        val unknownTop = root.keys.filter { it != TOP_LEVEL_KEY }
        if (unknownTop.isNotEmpty()) {
            return Result.Invalid("unknown top-level key(s): ${unknownTop.joinToString(", ")} (only '$TOP_LEVEL_KEY' is supported)")
        }

        val linexa = root[TOP_LEVEL_KEY] ?: return Result.Valid(WorkspaceConfig())
        if (linexa !is Map<*, *>) return Result.Invalid("'$TOP_LEVEL_KEY' must be a mapping")

        var features = emptyMap<String, Boolean>()
        var env = emptyMap<String, String>()
        var launch = LaunchConfig()
        var hostInfo = HostInfoConfig()

        for (key in linexa.keys) {
            when (key) {
                FEATURES_KEY -> {
                    val value = linexa[key] ?: continue
                    if (value !is Map<*, *>) {
                        return Result.Invalid("'$TOP_LEVEL_KEY.$FEATURES_KEY' must be a mapping of feature id to boolean")
                    }
                    val out = linkedMapOf<String, Boolean>()
                    for ((featureId, enabled) in value) {
                        if (featureId !is String || enabled !is Boolean) {
                            return Result.Invalid(
                                "'$TOP_LEVEL_KEY.$FEATURES_KEY' entries must be '<feature-id>: true|false'"
                            )
                        }
                        out[featureId] = enabled
                    }
                    features = out
                }

                ENV_KEY -> {
                    val value = linexa[key] ?: continue
                    if (value !is Map<*, *>) {
                        return Result.Invalid("'$TOP_LEVEL_KEY.$ENV_KEY' must be a mapping of name to string")
                    }
                    val out = linkedMapOf<String, String>()
                    for ((name, v) in value) {
                        if (name !is String || v == null || v !is String) {
                            return Result.Invalid(
                                "'$TOP_LEVEL_KEY.$ENV_KEY' entries must be '<NAME>: <string>'"
                            )
                        }
                        out[name] = v
                    }
                    env = out
                }

                LAUNCH_KEY -> {
                    val value = linexa[key] ?: continue
                    if (value !is Map<*, *>) {
                        return Result.Invalid("'$TOP_LEVEL_KEY.$LAUNCH_KEY' must be a mapping")
                    }
                    try {
                        launch = parseLaunch(value)
                    } catch (e: InvalidLaunch) {
                        return Result.Invalid(e.reason)
                    }
                }

                HOST_INFO_KEY -> {
                    val value = linexa[key] ?: continue
                    if (value !is Map<*, *>) {
                        return Result.Invalid("'$TOP_LEVEL_KEY.$HOST_INFO_KEY' must be a mapping")
                    }
                    try {
                        hostInfo = parseHostInfo(value)
                    } catch (e: InvalidLaunch) {
                        return Result.Invalid(e.reason)
                    }
                }

                // §1.118: `linexa:` is shared with the RootFS *metadata*
                // schema (`schema_version`, `distro`, `architecture`,
                // `rootfs`, `desktop`, `package_manager`, `environment`,
                // `metadata`), which `RootFsMetadataParser` owns. Those keys
                // are skipped here rather than rejected — this parser stays
                // authoritative for the configuration keys only, and each
                // parser validates its own half of the same mapping. An
                // archive carrying only metadata is still a valid config
                // file (with no overrides), and vice versa.
                in RootFsMetadataParser.METADATA_KEYS -> continue

                RootFsMetadataParser.KEY_PRESENTATION_MISSPELLED -> return Result.Invalid(
                    RootFsMetadataParser.presentationMisspelledReason()
                )

                else -> return Result.Invalid(
                    "unknown key '$key' under '$TOP_LEVEL_KEY' (supported: $FEATURES_KEY, $ENV_KEY, $LAUNCH_KEY, $HOST_INFO_KEY, " +
                        "plus the RootFS metadata keys: ${RootFsMetadataParser.METADATA_KEYS.sorted().joinToString(", ")})"
                )
            }
        }
        return Result.Valid(WorkspaceConfig(features, env, launch, hostInfo))
    }

    /**
     * Parses the `linexa.host_info` block — the RootFS's SELECTION of which
     * Android-host facts it wants injected as `LINEXA_HOST_*` env vars at
     * every launch (§15 selective pass-through).
     *
     * Fail-hard on structure (`enable` must be boolean) AND on CONTENT: a key
     * name outside [com.fem.linexa.domain.hostinfo.HostInfoKey] entries is an
     * authoring typo that would otherwise silently thin the payload forever,
     * so it fails the import with the valid list spelled out.
     */
    private fun parseHostInfo(block: Map<*, *>): HostInfoConfig {
        var enable: Boolean? = null
        var keys: Set<HostInfoKey>? = null

        for (key in block.keys) {
            when (key) {
                HOST_ENABLE_KEY -> {
                    val value = block[key] ?: continue
                    if (value !is Boolean) {
                        throw InvalidLaunch("'$TOP_LEVEL_KEY.$HOST_INFO_KEY.$HOST_ENABLE_KEY' must be true or false")
                    }
                    enable = value
                }

                HOST_KEYS_KEY -> {
                    val value = block[key] ?: continue
                    if (value !is List<*>) {
                        throw InvalidLaunch("'$TOP_LEVEL_KEY.$HOST_INFO_KEY.$HOST_KEYS_KEY' must be a list of key names")
                    }
                    val validNames = HostInfoKey.entries.map { it.name }.sorted()
                    val out = LinkedHashSet<HostInfoKey>()
                    for (name in value) {
                        if (name !is String || name.isBlank()) {
                            throw InvalidLaunch(
                                "'$TOP_LEVEL_KEY.$HOST_INFO_KEY.$HOST_KEYS_KEY' entries must be non-empty strings"
                            )
                        }
                        val parsed = HostInfoKey.entries.firstOrNull { it.name == name }
                            ?: throw InvalidLaunch(
                                "unknown host-info key '$name' (valid: ${validNames.joinToString(", ")})"
                            )
                        out.add(parsed)
                    }
                    if (out.size > MAX_HOST_INFO_KEYS) {
                        throw InvalidLaunch(
                            "'$TOP_LEVEL_KEY.$HOST_INFO_KEY.$HOST_KEYS_KEY' exceeds the maximum of $MAX_HOST_INFO_KEYS entries"
                        )
                    }
                    keys = out
                }

                else -> throw InvalidLaunch(
                    "unknown key '$key' under '$TOP_LEVEL_KEY.$HOST_INFO_KEY' (supported: $HOST_ENABLE_KEY, $HOST_KEYS_KEY)"
                )
            }
        }
        return HostInfoConfig(enable, keys)
    }

    /**
     * §1.73: parses the `linexa.launch` block. Every value here is
     * structured and typed — a shell path string, argv fragment strings,
     * host/guest path pairs — and is mapped directly into
     * `PRootLauncher.LaunchSpec`'s argv at launch. Malformed input fails
     * the import (fail-hard, same as the rest of the schema); there is no
     * path from YAML to a shell-interpreted command string.
     */
    private fun parseLaunch(launch: Map<*, *>): LaunchConfig {
        var shell: String? = null
        var shellArgs: List<String> = emptyList()
        var bindMounts: List<BindMount> = emptyList()

        for (key in launch.keys) {
            when (key) {
                SHELL_KEY -> {
                    val value = launch[key] ?: continue
                    if (value !is String || value.isBlank()) {
                        throw InvalidLaunch(
                            "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$SHELL_KEY' must be a non-empty string"
                        )
                    }
                    val pathError = validateGuestPath(value, "$TOP_LEVEL_KEY.$LAUNCH_KEY.$SHELL_KEY")
                    if (pathError != null) throw InvalidLaunch(pathError)
                    shell = value
                }

                SHELL_ARGS_KEY -> {
                    val value = launch[key] ?: continue
                    if (value !is List<*>) {
                        throw InvalidLaunch(
                            "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$SHELL_ARGS_KEY' must be a list of strings"
                        )
                    }
                    val out = mutableListOf<String>()
                    for (arg in value) {
                        if (arg !is String || arg.isBlank()) {
                            throw InvalidLaunch(
                                "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$SHELL_ARGS_KEY' entries must be non-empty strings"
                            )
                        }
                        val bad = arg.any { it.isWhitespace() || it == '\u0000' }
                        if (bad) {
                            throw InvalidLaunch(
                                "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$SHELL_ARGS_KEY' entries must be single argv tokens without whitespace or NUL"
                            )
                        }
                        out.add(arg)
                    }
                    shellArgs = out
                }

                BIND_MOUNTS_KEY -> {
                    val value = launch[key] ?: continue
                    if (value !is List<*>) {
                        throw InvalidLaunch(
                            "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$BIND_MOUNTS_KEY' must be a list of 'host:'/'guest:' mappings"
                        )
                    }
                    if (value.size > MAX_BIND_MOUNTS) {
                        throw InvalidLaunch(
                            "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$BIND_MOUNTS_KEY' exceeds the maximum of $MAX_BIND_MOUNTS entries"
                        )
                    }
                    val out = mutableListOf<BindMount>()
                    for (entry in value) {
                        if (entry !is Map<*, *>) {
                            throw InvalidLaunch(
                                "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$BIND_MOUNTS_KEY' entries must be 'host:'/'guest:' mappings"
                            )
                        }
                        out.add(parseBindMount(entry))
                    }
                    bindMounts = out
                }

                else -> throw InvalidLaunch(
                    "unknown key '$key' under '$TOP_LEVEL_KEY.$LAUNCH_KEY' (supported: $SHELL_KEY, $SHELL_ARGS_KEY, $BIND_MOUNTS_KEY)"
                )
            }
        }

        if (shellArgs.isNotEmpty() && shell == null) {
            throw InvalidLaunch(
                "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$SHELL_ARGS_KEY' requires '$TOP_LEVEL_KEY.$LAUNCH_KEY.$SHELL_KEY'"
            )
        }
        return LaunchConfig(shell, shellArgs, bindMounts)
    }

    private fun parseBindMount(entry: Map<*, *>): BindMount {
        val unknown = entry.keys.filter { it != BIND_HOST_KEY && it != BIND_GUEST_KEY }
        if (unknown.isNotEmpty()) {
            throw InvalidLaunch(
                "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$BIND_MOUNTS_KEY' entries support only '$BIND_HOST_KEY' and '$BIND_GUEST_KEY' (unknown: ${unknown.joinToString(", ")})"
            )
        }
        val host = entry[BIND_HOST_KEY]
        val guest = entry[BIND_GUEST_KEY]
        if (host !is String || host.isBlank() || guest !is String || guest.isBlank()) {
            throw InvalidLaunch(
                "'$TOP_LEVEL_KEY.$LAUNCH_KEY.$BIND_MOUNTS_KEY' entries must have non-empty string '$BIND_HOST_KEY' and '$BIND_GUEST_KEY'"
            )
        }
        val hostError = validateHostPath(host)
        if (hostError != null) throw InvalidLaunch(hostError)
        val guestError = validateGuestPath(guest, "$TOP_LEVEL_KEY.$LAUNCH_KEY.$BIND_MOUNTS_KEY.$BIND_GUEST_KEY")
        if (guestError != null) throw InvalidLaunch(guestError)
        return BindMount(host = host, guest = guest)
    }

    /**
     * §1.73: a host path on the Android device. Must be absolute and free
     * of whitespace/NUL so it stays a single typed argv token when paired
     * into `-b host:guest` — no shell, no globbing, no surprises.
     *
     * Pre-release audit fix (2026-09-02, security finding S4): the
     * filesystem ROOT is now rejected. A `host: /` bind exposed the app's
     * entire private filesystem — every workspace's rootfs, the Room
     * database (workspace attributes carry the VNC password) and the
     * bootstrap binaries — inside one guest, voiding the containment every
     * other layer enforces. The path is exactly "/" (the bind of the whole
     * tree); specific sub-path binds remain the workspace author's choice,
     * as documented.
     */
    private fun validateHostPath(path: String): String? {
        if (!path.startsWith("/")) return "'host' must be an absolute path (starts with '/')"
        if (path == "/") {
            return "'host' must not be '/' — binding the filesystem root would expose " +
                "the app's entire private storage (including every other workspace) to this guest"
        }
        if (path.any { it.isWhitespace() || it == '\u0000' }) {
            return "'host' must not contain whitespace or NUL"
        }
        return null
    }

    /** §1.73: a guest path inside the rootfs — same structural rules as [validateHostPath]. */
    private fun validateGuestPath(path: String, where: String): String? {
        if (!path.startsWith("/")) return "'$where' must be an absolute path (starts with '/')"
        if (path.any { it.isWhitespace() || it == '\u0000' }) {
            return "'$where' must not contain whitespace or NUL"
        }
        return null
    }

    /**
     * Loads a persisted per-workspace config.yaml (§1.26). Returns null
     * when the file is absent. Fail-open on parse/schema errors: the file
     * was already validated hard at import time, so an unparseable file at
     * launch can only mean later tampering — degrading to defaults (with a
     * log) beats refusing to start a workspace over a config file.
     */
    fun load(configFile: File): WorkspaceConfig? {
        if (!configFile.exists()) return null
        return try {
            when (val result = parse(configFile.readBytes())) {
                is Result.Valid -> result.config
                is Result.Invalid -> {
                    Logger.w(TAG, "Ignoring invalid ${configFile.path}: ${result.reason}")
                    null
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Could not read ${configFile.path}", e)
            null
        }
    }

    companion object {
        private const val TAG = "WorkspaceConfigParser"

        private const val TOP_LEVEL_KEY = "linexa"
        private const val FEATURES_KEY = "features"
        private const val ENV_KEY = "env"
        private const val LAUNCH_KEY = "launch"
        private const val SHELL_KEY = "shell"
        private const val SHELL_ARGS_KEY = "shellArgs"
        private const val BIND_MOUNTS_KEY = "bindMounts"
        private const val BIND_HOST_KEY = "host"
        private const val BIND_GUEST_KEY = "guest"
        private const val HOST_INFO_KEY = "host_info"
        private const val HOST_ENABLE_KEY = "enable"
        private const val HOST_KEYS_KEY = "keys"

        /**
         * §1.73: sanity cap on extra bind mounts per workspace. The 64 KiB
         * config cap already bounds the file, but a hard count keeps the
         * launch argv from being an unbounded list.
         */
        const val MAX_BIND_MOUNTS = 16

        /** Same idea as [MAX_BIND_MOUNTS], applied to host-info key requests. */
        const val MAX_HOST_INFO_KEYS = 24

        /** Same cap the installer uses when pulling the file out of the archive. */
        const val MAX_CONFIG_BYTES = 64 * 1024
    }

    /** §1.73: internal — carries a precise validation reason out of [parseLaunch]. */
    private class InvalidLaunch(val reason: String) : RuntimeException(reason)
}

/**
 * Parsed `config.yaml` contents ([WorkspaceConfigParser]).
 *
 * @param features per-workspace feature overrides (application deferred to
 *   the first real feature consumer, §1.27; merge point: `ImportService`
 *   at import completion).
 * @param env extra environment variables merged into the PRoot launch
 *   environment by `PRootContainerRuntime` (workspace config wins over
 *   any default, §1.27).
 * @param launch §1.73 launch overrides (shell, shell args, extra bind
 *   mounts) applied by `PRootContainerRuntime` at launch; empty when the
 *   file carries no `linexa.launch` block.
 */
data class WorkspaceConfig(
    val features: Map<String, Boolean> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val launch: LaunchConfig = LaunchConfig(),
    val hostInfo: HostInfoConfig = HostInfoConfig()
)

/**
 * §1.73: the `linexa.launch` block — structured, typed launch overrides.
 *
 * All fields are optional and additive. [shell] replaces the default
 * `resolveShellPath` probe result; [shellArgs] replaces the default
 * `--login` when non-empty; [bindMounts] adds PRoot binds on top of the
 * built-in defaults. Every value maps directly into
 * `PRootLauncher.LaunchSpec`'s argv — nothing is ever shell-interpreted.
 *
 * @param shell absolute guest path to exec as the interactive login shell;
 *   null keeps the `resolveShellPath` fallback (`/bin/bash` →
 *   `/usr/bin/bash` → `/bin/sh`).
 * @param shellArgs argv fragments for [shell]; empty keeps the default
 *   `--login`. Requires [shell] (parser rejects `shellArgs` alone).
 * @param bindMounts extra typed host/guest bind-mount pairs.
 */
data class LaunchConfig(
    val shell: String? = null,
    val shellArgs: List<String> = emptyList(),
    val bindMounts: List<BindMount> = emptyList()
) {
    val hasOverrides: Boolean
        get() = shell != null || shellArgs.isNotEmpty() || bindMounts.isNotEmpty()
}

/**
 * `linexa.host_info` block — WHICH Android-host facts a rootfs wants.
 *
 * @param enable master switch; null/absent means the default posture
 *   (enabled). Explicit `false` suppresses ALL `LINEXA_HOST_*` variables for
 *   this workspace while still leaving the /proc overlays active (those are a
 *   compatibility layer, not information disclosure beyond what the guest can
 *   already observe via `/proc`).
 * @param keys explicit selection; null/absent keeps the runtime bridge's
 *   [com.fem.linexa.service.runtime.hostinfo.HostInfoRuntimeBridge.DEFAULT_GUEST_KEYS]
 *   minimal set. Parsed to enum values AT IMPORT TIME so typos fail loudly.
 */
data class HostInfoConfig(
    val enable: Boolean? = null,
    val keys: Set<HostInfoKey>? = null
) {
    fun isEnabledByDefault(): Boolean = enable != false

    fun effectiveKeys(defaults: Set<HostInfoKey>): Set<HostInfoKey> = keys ?: defaults
}

/**
 * §1.73: snapshot of this config's launch overrides into the persisted
 * [com.fem.linexa.domain.workspace.RuntimeConfig], written once at import
 * completion by `ImportService`/`DownloadService` so the workspace record
 * carries what the archive authored. The launch path itself always
 * re-reads `config.yaml` (fail-open, §1.27), so this snapshot is for
 * inspection/UI — never authoritative at launch.
 */
fun WorkspaceConfig.launchSnapshot(): com.fem.linexa.domain.workspace.RuntimeConfig =
    com.fem.linexa.domain.workspace.RuntimeConfig(
        launchShell = launch.shell,
        launchShellArgs = launch.shellArgs,
        extraBindMounts = launch.bindMounts
    )
