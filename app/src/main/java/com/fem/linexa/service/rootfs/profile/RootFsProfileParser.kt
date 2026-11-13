package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.domain.packagemanager.PackageManagerDetector
import com.fem.linexa.service.installer.RootFsProbe
import com.fem.linexa.service.rootfs.ArchiveEntryPath
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * §1.146 — parses and validates a distribution profile document.
 *
 * ## One parser, two trust levels
 *
 * The same YAML shape is used for the profiles Linexa ships in its assets and
 * for a profile declared inside an untrusted rootfs archive. They are parsed by
 * the same code on purpose — a built-in profile that would be rejected as
 * embedded is a bug, and having one lenient path and one strict path would let
 * them drift.
 *
 * What differs is only the *consequence* of failure, and that is the caller's
 * decision (see [RootFsProfileResolver]): a malformed built-in asset is a
 * programming error worth failing loudly in tests, while a malformed embedded
 * profile falls back to the built-in one rather than blocking an import.
 *
 * ## Security posture
 *
 * Untrusted YAML is loaded with [SafeConstructor], so no arbitrary Java type
 * can be instantiated from the document (`!!javax.script.ScriptEngineManager`
 * and friends are rejected at parse time, not filtered afterwards).
 *
 * Beyond that, validation is a whitelist:
 *
 *  - every path is normalized and must stay **inside** the rootfs — no absolute
 *    paths, no `..`, no host paths, so a profile cannot point the installer at
 *    `/data/data/com.fem.linexa/…` or at the user's photos;
 *  - `install:` steps must name [InstallStep] constants, so a profile can only
 *    request behaviour that already exists in reviewed code;
 *  - `package_manager` must be one of [PackageManagerDetector]'s keys, so it
 *    cannot introduce a new command to run;
 *  - `login_shell` must be an absolute guest path free of shell metacharacters,
 *    matching the existing `linexa.environment.shell` rule;
 *  - unknown keys are rejected rather than ignored, so a typo is reported
 *    instead of silently doing nothing.
 */
object RootFsProfileParser {

    /** Upper bound on a profile document. Built-ins are ~1 KB. */
    const val MAX_PROFILE_BYTES = 64 * 1024

    sealed interface Result {
        data class Valid(val profile: RootFsProfile) : Result
        data class Invalid(val reason: String) : Result
    }

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_ALIASES = "aliases"
    private const val KEY_DETECTION = "detection"
    private const val KEY_ARCHITECTURES = "supported_architectures"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_INSTALL = "install"
    private const val KEY_ENVIRONMENT = "environment"

    private val TOP_LEVEL_KEYS = setOf(
        KEY_ID, KEY_NAME, KEY_ALIASES, KEY_DETECTION,
        KEY_ARCHITECTURES, KEY_LAYOUT, KEY_INSTALL, KEY_ENVIRONMENT
    )

    private const val DET_OS_RELEASE_IDS = "os_release_ids"
    private const val DET_MARKER_FILES = "marker_files"
    private const val DET_VERSION_FROM = "version_from"
    private const val DET_ARCH_HINTS = "architecture_hints"
    private const val DET_ELF_PATHS = "elf_probe_paths"
    private val DETECTION_KEYS = setOf(
        DET_OS_RELEASE_IDS, DET_MARKER_FILES, DET_VERSION_FROM, DET_ARCH_HINTS, DET_ELF_PATHS
    )

    private const val LAYOUT_REQUIRED_DIRS = "required_directories"
    private const val LAYOUT_SHELLS = "shell_candidates"
    private const val LAYOUT_ARCHIVE_KIND = "archive_kind"
    private val LAYOUT_KEYS = setOf(LAYOUT_REQUIRED_DIRS, LAYOUT_SHELLS, LAYOUT_ARCHIVE_KIND)

    private const val ENV_LOGIN_SHELL = "login_shell"
    private const val ENV_SHELL_ARGS = "shell_args"
    private const val ENV_PACKAGE_MANAGER = "package_manager"
    private val ENVIRONMENT_KEYS = setOf(ENV_LOGIN_SHELL, ENV_SHELL_ARGS, ENV_PACKAGE_MANAGER)

    private val ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]*$")
    private val ARCH_HINT_REGEX = Regex("^[a-zA-Z0-9._/-]+$")

    /**
     * Guest paths only. Rejects the metacharacters that would matter if a path
     * ever reached a shell, plus the separators that would let it escape.
     */
    private val UNSAFE_PATH_CHARS = charArrayOf(
        ';', '|', '&', '$', '`', '<', '>', '(', ')', '{', '}', '[', ']',
        '\'', '"', '\\', '*', '?', '!', '#', '~', '\n', '\r', '\u0000'
    ).toSet()

    fun parse(bytes: ByteArray, source: RootFsProfileSource): Result {
        if (bytes.size > MAX_PROFILE_BYTES) {
            return Result.Invalid("profile exceeds $MAX_PROFILE_BYTES bytes (${bytes.size})")
        }
        val root = try {
            safeYaml().load<Any?>(bytes.inputStream().buffered())
        } catch (e: Exception) {
            return Result.Invalid("not valid YAML: ${e.message}")
        } ?: return Result.Invalid("profile is empty")

        if (root !is Map<*, *>) return Result.Invalid("profile must be a YAML mapping")

        return try {
            Result.Valid(build(root, source))
        } catch (e: ProfileException) {
            Result.Invalid(e.message ?: "invalid profile")
        }
    }

    /**
     * snakeyaml restricted to plain scalars/collections. Nesting/alias limits
     * are set so a hostile document cannot exhaust memory through billion-laughs
     * style alias expansion before validation gets a chance to run.
     */
    private fun safeYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 16
            codePointLimit = MAX_PROFILE_BYTES
        }
        return Yaml(SafeConstructor(options))
    }

    private fun build(root: Map<*, *>, source: RootFsProfileSource): RootFsProfile {
        rejectUnknown(root, TOP_LEVEL_KEYS, "profile")

        val id = requiredString(root, KEY_ID, "id")
        if (!ID_REGEX.matches(id)) {
            fail("'id' must match ${ID_REGEX.pattern} (got '$id')")
        }
        val name = requiredString(root, KEY_NAME, "name")
        val aliases = stringSet(root[KEY_ALIASES], KEY_ALIASES).onEach {
            if (!ID_REGEX.matches(it)) fail("'aliases' entry '$it' must match ${ID_REGEX.pattern}")
        }

        val detection = buildDetection(root[KEY_DETECTION])
        val architectures = stringSet(root[KEY_ARCHITECTURES], KEY_ARCHITECTURES)
        val layout = buildLayout(root[KEY_LAYOUT])
        val install = buildInstall(root[KEY_INSTALL])
        val environment = buildEnvironment(root[KEY_ENVIRONMENT])

        if (layout.archiveKind == ArchiveKind.OCI_IMAGE_LAYOUT &&
            InstallStep.UNWRAP_OCI_LAYER !in install
        ) {
            // A coherence rule, not a style rule: an OCI layout whose plan
            // omits the unwrap step would be handed to tar as if the
            // `blobs/`+`index.json` tree were a rootfs, fail the
            // required-directories check, and report "incomplete rootfs" for a
            // perfectly good image. Better to name the real problem here.
            fail("'layout.archive_kind: oci_image_layout' requires 'install' to include 'unwrap_oci_layer'")
        }

        return RootFsProfile(
            id = id,
            name = name,
            aliases = aliases,
            probeId = probeIdOf(id, aliases),
            detection = detection,
            supportedArchitectures = architectures,
            layout = layout,
            install = install,
            environment = environment,
            source = source
        )
    }

    private fun buildDetection(node: Any?): DetectionRules {
        if (node == null) return DetectionRules()
        val map = node as? Map<*, *> ?: fail("'detection' must be a mapping")
        rejectUnknown(map, DETECTION_KEYS, "detection")

        val markers = stringSet(map[DET_MARKER_FILES], "detection.$DET_MARKER_FILES")
            .map { guestPath(it, "detection.$DET_MARKER_FILES") }
            .toSet()
        val versionFrom = (map[DET_VERSION_FROM] as? String)
            ?.let { guestPath(it, "detection.$DET_VERSION_FROM") }
        if (versionFrom != null && versionFrom !in markers) {
            fail("'detection.$DET_VERSION_FROM' ('$versionFrom') must also appear in '$DET_MARKER_FILES'")
        }

        val hints = LinkedHashMap<String, String>()
        (map[DET_ARCH_HINTS] as? Map<*, *>)?.forEach { (needle, arch) ->
            val n = (needle as? String)?.trim()?.lowercase()
                ?: fail("'detection.$DET_ARCH_HINTS' keys must be strings")
            if (n.isEmpty() || !ARCH_HINT_REGEX.matches(n)) {
                fail("'detection.$DET_ARCH_HINTS' key '$n' must match ${ARCH_HINT_REGEX.pattern}")
            }
            val label = (arch as? String)?.trim()?.lowercase()
                ?: fail("'detection.$DET_ARCH_HINTS' values must be strings")
            if (archOf(label) == null) {
                fail("'detection.$DET_ARCH_HINTS' value '$label' is not a known architecture")
            }
            hints[n] = label
        } ?: run {
            if (map.containsKey(DET_ARCH_HINTS) && map[DET_ARCH_HINTS] != null) {
                fail("'detection.$DET_ARCH_HINTS' must be a mapping of name-fragment to architecture")
            }
        }

        return DetectionRules(
            osReleaseIds = stringSet(map[DET_OS_RELEASE_IDS], "detection.$DET_OS_RELEASE_IDS")
                .map { it.lowercase() }.toSet(),
            markerFiles = markers,
            versionFrom = versionFrom,
            architectureHints = hints,
            elfProbePaths = stringList(map[DET_ELF_PATHS], "detection.$DET_ELF_PATHS")
                .map { guestPath(it, "detection.$DET_ELF_PATHS") }
        )
    }

    private fun buildLayout(node: Any?): LayoutRules {
        if (node == null) return LayoutRules()
        val map = node as? Map<*, *> ?: fail("'layout' must be a mapping")
        rejectUnknown(map, LAYOUT_KEYS, "layout")

        val dirs = stringSet(map[LAYOUT_REQUIRED_DIRS], "layout.$LAYOUT_REQUIRED_DIRS")
            .map { guestPath(it, "layout.$LAYOUT_REQUIRED_DIRS") }.toSet()
        val shells = stringList(map[LAYOUT_SHELLS], "layout.$LAYOUT_SHELLS")
            .map { guestPath(it, "layout.$LAYOUT_SHELLS") }
        val kind = when (val raw = (map[LAYOUT_ARCHIVE_KIND] as? String)?.trim()?.lowercase()) {
            null -> ArchiveKind.ROOTFS_TAR
            "rootfs_tar" -> ArchiveKind.ROOTFS_TAR
            "oci_image_layout" -> ArchiveKind.OCI_IMAGE_LAYOUT
            else -> fail("'layout.$LAYOUT_ARCHIVE_KIND' must be rootfs_tar or oci_image_layout (got '$raw')")
        }
        val defaults = LayoutRules()
        return LayoutRules(
            requiredDirectories = dirs.ifEmpty { defaults.requiredDirectories },
            shellCandidates = shells.ifEmpty { defaults.shellCandidates },
            archiveKind = kind
        )
    }

    private fun buildInstall(node: Any?): List<InstallStep> {
        if (node == null) return emptyList()
        val raw = stringList(node, KEY_INSTALL)
        val steps = raw.map { value ->
            val normalized = value.trim().uppercase().replace('-', '_')
            InstallStep.entries.firstOrNull { it.name == normalized }
                ?: fail(
                    "'install' step '$value' is not supported " +
                        "(supported: ${InstallStep.entries.joinToString(", ") { it.name.lowercase() }})"
                )
        }
        val duplicates = steps.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            fail("'install' repeats ${duplicates.joinToString(", ") { it.name.lowercase() }}")
        }
        return steps
    }

    private fun buildEnvironment(node: Any?): EnvironmentDefaults {
        if (node == null) return EnvironmentDefaults()
        val map = node as? Map<*, *> ?: fail("'environment' must be a mapping")
        rejectUnknown(map, ENVIRONMENT_KEYS, "environment")

        val shell = (map[ENV_LOGIN_SHELL] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (shell != null) {
            if (!shell.startsWith("/")) {
                fail("'environment.$ENV_LOGIN_SHELL' must be an absolute guest path (got '$shell')")
            }
            if (shell.any { it in UNSAFE_PATH_CHARS } || shell.contains("..")) {
                fail("'environment.$ENV_LOGIN_SHELL' contains unsafe characters ('$shell')")
            }
        }
        val args = stringList(map[ENV_SHELL_ARGS], "environment.$ENV_SHELL_ARGS").onEach {
            if (it.any { c -> c in UNSAFE_PATH_CHARS }) {
                fail("'environment.$ENV_SHELL_ARGS' entry '$it' contains unsafe characters")
            }
        }
        val pm = (map[ENV_PACKAGE_MANAGER] as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (pm != null && pm !in PackageManagerDetector.supportedKeys) {
            fail(
                "'environment.$ENV_PACKAGE_MANAGER' must be one of " +
                    "${PackageManagerDetector.supportedKeys.joinToString(", ")} (got '$pm')"
            )
        }
        val defaults = EnvironmentDefaults()
        return EnvironmentDefaults(
            loginShell = shell,
            shellArgs = args.ifEmpty { defaults.shellArgs },
            packageManager = pm
        )
    }

    /**
     * Normalizes a declared path and proves it stays inside the rootfs.
     *
     * This is the single choke point that makes an embedded profile safe to act
     * on: every path-shaped field goes through it, so there is no field through
     * which an archive can name a host location or climb out of the guest tree.
     */
    private fun guestPath(value: String, field: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) fail("'$field' contains an empty path")
        if (trimmed.any { it in UNSAFE_PATH_CHARS }) {
            fail("'$field' path '$trimmed' contains unsafe characters")
        }
        if (trimmed.startsWith("/")) {
            fail("'$field' path '$trimmed' must be relative to the rootfs root")
        }
        val normalized = ArchiveEntryPath.normalize(trimmed)
        if (normalized.isEmpty()) fail("'$field' path '$trimmed' normalizes to nothing")
        if (normalized.split('/').any { it == ".." }) {
            fail("'$field' path '$trimmed' escapes the rootfs root")
        }
        return normalized
    }

    private fun probeIdOf(id: String, aliases: Set<String>): RootFsProbe.DistroId {
        val all = aliases + id
        return RootFsProbe.DistroId.entries.firstOrNull { candidate ->
            candidate != RootFsProbe.DistroId.OTHER && candidate.name.lowercase() in all
        } ?: when {
            "archarm" in all || "archlinux" in all -> RootFsProbe.DistroId.ARCH
            else -> RootFsProbe.DistroId.OTHER
        }
    }

    private fun archOf(label: String): RootFsProbe.RootFsArch? =
        RootFsProbe.RootFsArch.entries.firstOrNull {
            it != RootFsProbe.RootFsArch.UNKNOWN && (it.label == label || it.abi == label)
        }

    private fun requiredString(map: Map<*, *>, key: String, field: String): String =
        (map[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: fail("'$field' is required and must be a non-empty string")

    private fun stringList(node: Any?, field: String): List<String> = when (node) {
        null -> emptyList()
        is List<*> -> node.map {
            (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() }
                ?: fail("'$field' entries must be non-empty strings")
        }
        else -> fail("'$field' must be a list")
    }

    private fun stringSet(node: Any?, field: String): Set<String> =
        stringList(node, field).toSet()

    private fun rejectUnknown(map: Map<*, *>, allowed: Set<String>, where: String) {
        val unknown = map.keys.mapNotNull { it as? String }.filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            fail("unknown $where key(s) ${unknown.joinToString(", ")}; supported: ${allowed.sorted().joinToString(", ")}")
        }
        val nonString = map.keys.filter { it !is String }
        if (nonString.isNotEmpty()) fail("$where keys must be strings")
    }

    private class ProfileException(message: String) : Exception(message)

    private fun fail(reason: String): Nothing = throw ProfileException(reason)
}
