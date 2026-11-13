package com.fem.linexa.service.rootfs

/**
 * §1.118 — Structural parse of the `linexa.yaml` metadata block.
 *
 * Split deliberately in two stages, matching the pipeline recorded in
 * §1.118 (`RootFsMetadataParser` → [RootFsMetadataValidator] →
 * [RootFsDescriptor]):
 *
 *  - **This class is shape-only.** It walks the already-loaded YAML mapping
 *    and rejects anything structurally wrong: an unknown key, a block that
 *    is not a mapping, a value of the wrong scalar type. It produces a
 *    [RootFsMetadataDraft] of raw strings/booleans and makes no judgement
 *    about whether those values are *acceptable*.
 *  - [RootFsMetadataValidator] then applies the semantic rules (supported
 *    schema version, known architecture, desktop coherence, supported
 *    package-manager key, safe shell/session tokens) and produces the typed
 *    [RootFsMetadata].
 *
 * Why the split: the two failure classes read very differently to a rootfs
 * author ("your YAML is malformed" vs "your YAML is well-formed but declares
 * `architecture: sparc`"), and the semantic rules are exactly the part that
 * changes as Linexa gains support for more distros/architectures — keeping
 * them out of the shape walk means adding an architecture never risks
 * loosening the structural checks.
 *
 * The parser is pure (no I/O, no Android types) and takes an already-parsed
 * YAML mapping rather than bytes, so the metadata block can share its file —
 * and therefore its 64 KiB size cap — with the pre-existing
 * `linexa.features`/`env`/`launch` configuration
 * ([com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser]). One file,
 * two schemas, each parser validating only its own half of the same mapping
 * and skipping the other's keys ([METADATA_KEYS] is what makes that
 * separation explicit). [RootFsInspector] is the entry point that loads the
 * YAML and drives this parser.
 */
object RootFsMetadataParser {

    /** YAML keys this parser owns under the `linexa:` mapping. */
    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_DISTRO = "distro"
    const val KEY_ARCHITECTURE = "architecture"
    const val KEY_ROOTFS = "rootfs"
    const val KEY_DESKTOP = "desktop"
    const val KEY_PACKAGE_MANAGER = "package_manager"
    const val KEY_ENVIRONMENT = "environment"
    const val KEY_WORKSPACE = "workspace"
    const val KEY_SHELL_CONFIG = "shell_config"
    const val KEY_INIT = "init"
    const val KEY_HEALTH_CHECK = "health_check"
    const val KEY_PRESENTATION = "metadata"
    const val KEY_SHORTCUTS = "shortcuts"

    /**
     * 2026-09-03 (install-profile fix): the presentation block's canonical
     * key is `metadata:` — but early bundled profiles shipped `presentation:`,
     * which reached [com.fem.linexa.service.workspaceconfig.WorkspaceConfigParser]
     * 's top-level unknown-key check and failed the whole install with a
     * message that never named the actual fix. Like `package_manager.commands`
     * below, the misspelling now gets an error that says exactly what to do.
     */
    const val KEY_PRESENTATION_MISSPELLED = "presentation"

    /**
     * The keys under `linexa:` that belong to the metadata schema. Anything
     * else under `linexa:` is the configuration schema's business (see
     * `WorkspaceConfigParser`), which is why this set is public: the two
     * schemas share one mapping and each must know which keys are not its
     * own rather than rejecting the other's.
     */
    val METADATA_KEYS: Set<String> = setOf(
        KEY_SCHEMA_VERSION,
        KEY_DISTRO,
        KEY_ARCHITECTURE,
        KEY_ROOTFS,
        KEY_DESKTOP,
        KEY_PACKAGE_MANAGER,
        KEY_ENVIRONMENT,
        KEY_WORKSPACE,
        KEY_SHELL_CONFIG,
        KEY_INIT,
        KEY_HEALTH_CHECK,
        KEY_PRESENTATION,
        KEY_SHORTCUTS
    )

    private const val DISTRO_ID = "id"
    private const val DISTRO_NAME = "name"
    private const val DISTRO_VERSION = "version"
    private const val DISTRO_CODENAME = "codename"

    private const val ROOTFS_TYPE = "type"
    private const val ROOTFS_COMPRESSED_FORMAT = "compressed_format"

    private const val DESKTOP_ENABLED = "enabled"
    private const val DESKTOP_TYPE = "type"
    private const val DESKTOP_SESSION = "session"

    private const val PM_TYPE = "type"

    private const val ENV_SHELL = "shell"
    private const val ENV_USER = "user"
    private const val ENV_HOME = "home"
    private const val ENV_SHELL_ARGS = "shell_args"
    private const val ENV_VARIABLES = "variables"

    private const val WORKSPACE_DIRECTORY = "directory"

    private const val SHELL_CONFIG_ALIASES = "aliases"

    private const val INIT_POST_INSTALL = "post_install"
    private const val INIT_FIRST_BOOT = "first_boot"

    private const val HEALTH_CHECK_COMMAND = "command"

    private const val PRESENTATION_DISPLAY_NAME = "display_name"
    private const val PRESENTATION_DESCRIPTION = "description"
    private const val PRESENTATION_PREVIEW = "preview"

    private const val SHORTCUT_ID = "id"
    private const val SHORTCUT_LABEL = "label"
    private const val SHORTCUT_DESCRIPTION = "description"
    private const val SHORTCUT_COMMAND = "command"
    private const val SHORTCUT_ICON = "icon"
    // Schema v2 (issue #3/#4/#5, 2026-09-03): hierarchical, TinyContainer-like
    // catalogs and typed actions.
    private const val SHORTCUT_ACTION = "action"
    private const val SHORTCUT_CHILDREN = "children"

    /**
     * Issue #1 (2026-09-03): `as_root: true` marks a leaf as an ELEVATED
     * launcher — the command (or a bare root shell when `command` is
     * absent) runs in a terminal session started with the root emulation,
     * instead of being typed into a normal user terminal or queued as an
     * app-initiated exec. This is the metadata surface for "a specific
     * operation [that] explicitly requires root privileges".
     */
    private const val SHORTCUT_AS_ROOT = "as_root"

    /**
     * The label-path separator the flattener composes for nested
     * `children:` entries — the SAME separator [com.fem.linexa.ui.screens.workspace.CommandTree]
     * parses back into a navigable tree, so a structured YAML catalog renders
     * as categories \u2192 sub-entries \u2192 actions with zero UI changes.
     */
    const val SHORTCUT_LABEL_SEPARATOR = " > "

    sealed interface Result {
        /** The mapping carries none of [METADATA_KEYS] — a config-only file. */
        data object Absent : Result

        /** Structurally sound; hand to [RootFsMetadataValidator]. */
        data class Parsed(val draft: RootFsMetadataDraft) : Result

        /** Structurally wrong, with a reason naming the exact YAML path. */
        data class Malformed(val reason: String) : Result
    }

    /**
     * Parses the metadata keys out of an already-loaded `linexa:` mapping.
     *
     * Returns [Result.Absent] when the mapping declares no metadata key at
     * all, so an archive carrying only the older feature/env/launch
     * configuration keeps working untouched.
     */
    fun parse(linexaBlock: Map<*, *>): Result {
        // The misspelling check runs before anything else so an author copying
        // an old example gets the fix named, whatever else the file declares.
        if (linexaBlock.containsKey(KEY_PRESENTATION_MISSPELLED)) {
            return Result.Malformed(presentationMisspelledReason())
        }
        val present = linexaBlock.keys.filterIsInstance<String>().filter { it in METADATA_KEYS }
        if (present.isEmpty()) return Result.Absent

        return try {
            Result.Parsed(
                RootFsMetadataDraft(
                    schemaVersion = scalarOrNull(linexaBlock[KEY_SCHEMA_VERSION], path(KEY_SCHEMA_VERSION)),
                    distro = parseDistro(mappingOrNull(linexaBlock[KEY_DISTRO], path(KEY_DISTRO))),
                    architecture = stringOrNull(linexaBlock[KEY_ARCHITECTURE], path(KEY_ARCHITECTURE)),
                    rootfs = parseRootFs(mappingOrNull(linexaBlock[KEY_ROOTFS], path(KEY_ROOTFS))),
                    desktop = parseDesktop(mappingOrNull(linexaBlock[KEY_DESKTOP], path(KEY_DESKTOP))),
                    packageManager = parsePackageManager(
                        mappingOrNull(linexaBlock[KEY_PACKAGE_MANAGER], path(KEY_PACKAGE_MANAGER))
                    ),
                    environment = parseEnvironment(mappingOrNull(linexaBlock[KEY_ENVIRONMENT], path(KEY_ENVIRONMENT))),
                    workspace = parseWorkspace(mappingOrNull(linexaBlock[KEY_WORKSPACE], path(KEY_WORKSPACE))),
                    shellConfig = parseShellConfig(mappingOrNull(linexaBlock[KEY_SHELL_CONFIG], path(KEY_SHELL_CONFIG))),
                    init = parseInit(mappingOrNull(linexaBlock[KEY_INIT], path(KEY_INIT))),
                    healthCheck = parseHealthCheck(mappingOrNull(linexaBlock[KEY_HEALTH_CHECK], path(KEY_HEALTH_CHECK))),
                    presentation = parsePresentation(
                        mappingOrNull(linexaBlock[KEY_PRESENTATION], path(KEY_PRESENTATION))
                    ),
                    shortcuts = parseShortcuts(linexaBlock[KEY_SHORTCUTS])
                )
            )
        } catch (e: MalformedMetadata) {
            Result.Malformed(e.reason)
        }
    }

    // ── block parsers ───────────────────────────────────────────────────

    private fun parseDistro(block: Map<*, *>?): DistroDraft? {
        if (block == null) return null
        rejectUnknownKeys(block, path(KEY_DISTRO), DISTRO_ID, DISTRO_NAME, DISTRO_VERSION, DISTRO_CODENAME)
        return DistroDraft(
            id = stringOrNull(block[DISTRO_ID], path(KEY_DISTRO, DISTRO_ID)),
            name = stringOrNull(block[DISTRO_NAME], path(KEY_DISTRO, DISTRO_NAME)),
            // A rolling release genuinely has no version, and YAML `13`
            // parses as an Int — accept any scalar and normalize to text.
            version = scalarOrNull(block[DISTRO_VERSION], path(KEY_DISTRO, DISTRO_VERSION)),
            codename = stringOrNull(block[DISTRO_CODENAME], path(KEY_DISTRO, DISTRO_CODENAME))
        )
    }

    private fun parseRootFs(block: Map<*, *>?): RootFsDraft? {
        if (block == null) return null
        rejectUnknownKeys(block, path(KEY_ROOTFS), ROOTFS_TYPE, ROOTFS_COMPRESSED_FORMAT)
        return RootFsDraft(
            type = stringOrNull(block[ROOTFS_TYPE], path(KEY_ROOTFS, ROOTFS_TYPE)),
            compressedFormat = stringOrNull(
                block[ROOTFS_COMPRESSED_FORMAT],
                path(KEY_ROOTFS, ROOTFS_COMPRESSED_FORMAT)
            )
        )
    }

    private fun parseDesktop(block: Map<*, *>?): DesktopDraft? {
        if (block == null) return null
        rejectUnknownKeys(block, path(KEY_DESKTOP), DESKTOP_ENABLED, DESKTOP_TYPE, DESKTOP_SESSION)
        val enabledValue = block[DESKTOP_ENABLED]
        val enabled = when (enabledValue) {
            null -> null
            is Boolean -> enabledValue
            else -> throw MalformedMetadata(
                "'${path(KEY_DESKTOP, DESKTOP_ENABLED)}' must be a boolean (true|false)"
            )
        }
        return DesktopDraft(
            enabled = enabled,
            type = stringOrNull(block[DESKTOP_TYPE], path(KEY_DESKTOP, DESKTOP_TYPE)),
            session = stringOrNull(block[DESKTOP_SESSION], path(KEY_DESKTOP, DESKTOP_SESSION))
        )
    }

    private fun parsePackageManager(block: Map<*, *>?): PackageManagerDraft? {
        if (block == null) return null
        // §1.118: `commands:` is intentionally NOT part of the schema (see
        // DeclaredPackageManager's kdoc). Name it explicitly in the error so
        // an author porting a tiny_container-style file learns *why* rather
        // than just "unknown key".
        if (block.keys.any { it == "commands" }) {
            throw MalformedMetadata(
                "'${path(KEY_PACKAGE_MANAGER)}.commands' is not supported — declare only " +
                    "'$PM_TYPE' and Linexa uses its own vetted commands and output parsers for that manager"
            )
        }
        rejectUnknownKeys(block, path(KEY_PACKAGE_MANAGER), PM_TYPE)
        return PackageManagerDraft(
            type = stringOrNull(block[PM_TYPE], path(KEY_PACKAGE_MANAGER, PM_TYPE))
        )
    }

    private fun parseEnvironment(block: Map<*, *>?): EnvironmentDraft? {
        if (block == null) return null
        rejectUnknownKeys(block, path(KEY_ENVIRONMENT), ENV_SHELL, ENV_USER, ENV_HOME, ENV_SHELL_ARGS, ENV_VARIABLES)
        return EnvironmentDraft(
            shell = stringOrNull(block[ENV_SHELL], path(KEY_ENVIRONMENT, ENV_SHELL)),
            user = stringOrNull(block[ENV_USER], path(KEY_ENVIRONMENT, ENV_USER)),
            home = stringOrNull(block[ENV_HOME], path(KEY_ENVIRONMENT, ENV_HOME)),
            shellArgs = stringListOrNull(block[ENV_SHELL_ARGS], path(KEY_ENVIRONMENT, ENV_SHELL_ARGS)),
            variables = parseVariables(block[ENV_VARIABLES])
        )
    }

    private fun parseWorkspace(block: Map<*, *>?): WorkspaceDraft? {
        if (block == null) return null
        rejectUnknownKeys(block, path(KEY_WORKSPACE), WORKSPACE_DIRECTORY)
        return WorkspaceDraft(
            directory = stringOrNull(block[WORKSPACE_DIRECTORY], path(KEY_WORKSPACE, WORKSPACE_DIRECTORY))
        )
    }

    private fun parseShellConfig(block: Map<*, *>?): ShellConfigDraft? {
        if (block == null) return null
        rejectUnknownKeys(block, path(KEY_SHELL_CONFIG), SHELL_CONFIG_ALIASES)
        return ShellConfigDraft(
            aliases = parseAliases(block[SHELL_CONFIG_ALIASES])
        )
    }

    private fun parseInit(block: Map<*, *>?): InitDraft? {
        if (block == null) return null
        rejectUnknownKeys(block, path(KEY_INIT), INIT_POST_INSTALL, INIT_FIRST_BOOT)
        return InitDraft(
            postInstall = stringListOrNull(block[INIT_POST_INSTALL], path(KEY_INIT, INIT_POST_INSTALL)),
            firstBoot = stringListOrNull(block[INIT_FIRST_BOOT], path(KEY_INIT, INIT_FIRST_BOOT))
        )
    }

    private fun parseHealthCheck(block: Map<*, *>?): HealthCheckDraft? {
        if (block == null) return null
        rejectUnknownKeys(block, path(KEY_HEALTH_CHECK), HEALTH_CHECK_COMMAND)
        return HealthCheckDraft(
            command = stringOrNull(block[HEALTH_CHECK_COMMAND], path(KEY_HEALTH_CHECK, HEALTH_CHECK_COMMAND))
        )
    }

    /**
     * `linexa.environment.variables` — a map of env NAME to scalar. Keys are
     * validated against the POSIX name shape here (the shape walk owns
     * structure; the validator's semantic pass re-checks nothing for these —
     * the runtime's host-key guard is the authority that matters at launch).
     */
    private fun parseVariables(value: Any?): Map<String, String>? {
        if (value == null) return null
        if (value !is Map<*, *>) {
            throw MalformedMetadata("'${path(KEY_ENVIRONMENT, ENV_VARIABLES)}' must be a mapping of NAME to value")
        }
        val out = linkedMapOf<String, String>()
        for ((rawName, rawValue) in value) {
            val name = rawName as? String
                ?: throw MalformedMetadata("'${path(KEY_ENVIRONMENT, ENV_VARIABLES)}' keys must be strings")
            if (!ENV_NAME_REGEX.matches(name)) {
                throw MalformedMetadata(
                    "'${path(KEY_ENVIRONMENT, ENV_VARIABLES)}.$name' must be a valid environment variable name " +
                        "(letters, digits, underscore; must not start with a digit)"
                )
            }
            out[name] = scalarOrNull(rawValue, "${path(KEY_ENVIRONMENT, ENV_VARIABLES)}.$name")
                ?: throw MalformedMetadata("'${path(KEY_ENVIRONMENT, ENV_VARIABLES)}.$name' must not be empty")
        }
        return out
    }

    /** `linexa.shell_config.aliases` — alias name to expansion text. */
    private fun parseAliases(value: Any?): Map<String, String>? {
        if (value == null) return null
        if (value !is Map<*, *>) {
            throw MalformedMetadata("'${path(KEY_SHELL_CONFIG, SHELL_CONFIG_ALIASES)}' must be a mapping of name to expansion")
        }
        val out = linkedMapOf<String, String>()
        for ((rawName, rawValue) in value) {
            val name = rawName as? String
                ?: throw MalformedMetadata("'${path(KEY_SHELL_CONFIG, SHELL_CONFIG_ALIASES)}' keys must be strings")
            // Shape-only identifier rule (the same shape the validator's
            // isSafeToken enforces for ids): letters/digits/+._- and no
            // whitespace. Kept here because the parser may not depend on the
            // validator — the two run in the same pipeline but validate
            // different failure classes.
            if (!name.matches(ALIAS_NAME_REGEX) || name.length > DeclaredShellConfig.MAX_NAME_CHARS) {
                throw MalformedMetadata(
                    "'${path(KEY_SHELL_CONFIG, SHELL_CONFIG_ALIASES)}.$name' must be an identifier of at most " +
                        "${DeclaredShellConfig.MAX_NAME_CHARS} characters"
                )
            }
            val expansion = stringOrNull(rawValue, "${path(KEY_SHELL_CONFIG, SHELL_CONFIG_ALIASES)}.$name")
            if (expansion != null && (expansion.any { it.isISOControl() } || expansion.length > DeclaredShellConfig.MAX_VALUE_CHARS)) {
                throw MalformedMetadata(
                    "'${path(KEY_SHELL_CONFIG, SHELL_CONFIG_ALIASES)}.$name' must be a single line of at most " +
                        "${DeclaredShellConfig.MAX_VALUE_CHARS} characters without control characters"
                )
            }
            out[name] = expansion
                ?: throw MalformedMetadata("'${path(KEY_SHELL_CONFIG, SHELL_CONFIG_ALIASES)}.$name' must not be empty")
        }
        return out
    }

    /** A YAML list of single-line strings (shell_args, init commands). */
    private fun stringListOrNull(value: Any?, where: String): List<String>? {
        if (value == null) return null
        if (value !is List<*>) {
            throw MalformedMetadata("'$where' must be a list of strings")
        }
        return value.mapIndexed { index, entry ->
            stringOrNull(entry, "$where[$index]")
                ?: throw MalformedMetadata("'$where[$index]' must not be empty")
        }
    }

    private fun parsePresentation(block: Map<*, *>?): PresentationDraft? {
        if (block == null) return null
        rejectUnknownKeys(
            block,
            path(KEY_PRESENTATION),
            PRESENTATION_DISPLAY_NAME,
            PRESENTATION_DESCRIPTION,
            PRESENTATION_PREVIEW
        )
        return PresentationDraft(
            displayName = stringOrNull(
                block[PRESENTATION_DISPLAY_NAME],
                path(KEY_PRESENTATION, PRESENTATION_DISPLAY_NAME)
            ),
            description = stringOrNull(
                block[PRESENTATION_DESCRIPTION],
                path(KEY_PRESENTATION, PRESENTATION_DESCRIPTION)
            ),
            // §1.121: shape-only here (a non-empty string). Whether the path is
            // *safe* is a semantic rule and belongs to the validator, via
            // RootFsPreviewImage.normalize — keeping it out of the shape walk
            // means the traversal rule has exactly one implementation.
            preview = stringOrNull(
                block[PRESENTATION_PREVIEW],
                path(KEY_PRESENTATION, PRESENTATION_PREVIEW)
            )
        )
    }

    /**
     * §1.120 — `linexa.shortcuts`, a **list** rather than a mapping.
     *
     * A list is what makes the Shortcuts screen a template: order is the
     * author's, ids are explicit, and adding an entry needs no agreement with
     * the app about which keys are meaningful. A mapping keyed by operation
     * (`update:`, `install:`, …) would have re-introduced exactly the fixed
     * vocabulary this replaces — the app would have to know each key's label,
     * so a new key would render as nothing until Kotlin changed.
     *
     * Schema v2 (issue #3/#4/#5, 2026-09-03) — hierarchical entries:
     *
     * An entry may declare `children:` (a recursive list) instead of (or in
     * addition to) its own `command:` — the TinyContainer `.tiny.yaml` shape:
     * a category ("Software Management") contains software ("Chrome"), and a
     * software entry contains the runnable actions ("Install", "Remove"…).
     * The parser flattens the tree depth-first into the SAME flat
     * [ShortcutDraft] list the UI has always consumed, composing each
     * descendant's label from its path (`Software Management > Chrome >
     * Install`) with [SHORTCUT_LABEL_SEPARATOR] — the exact separator
     * CommandTree re-parses into navigation categories. Order is preserved,
     * so authors keep full control of the rendered catalog; ids stay unique
     * across the whole flattened list (the validator enforces it).
     *
     * Leaves additionally accept `action:` — a semantic tag
     * (install/remove/update/launch/check) the UI uses for icon selection
     * and that makes an entry's EFFECT explicit even when the command string
     * is long. The full action model (one entry per action, optional per
     * action) is expressed exactly as the requirement draws it: a software
     * entry with install/remove/update/launch/check children, each child
     * tagged with its action.
     *
     * Returns null (not an empty list) when the key is absent, so
     * "declared no shortcuts" and "declared an empty list" stay
     * distinguishable — the validator treats an explicit empty list as the
     * author deliberately offering none, which suppresses the built-in
     * fallback, while absence keeps it.
     */
    private fun parseShortcuts(value: Any?): List<ShortcutDraft>? {
        if (value == null) return null
        if (value !is List<*>) {
            throw MalformedMetadata("'${path(KEY_SHORTCUTS)}' must be a list of shortcut mappings")
        }
        val out = mutableListOf<ShortcutDraft>()
        parseShortcutEntries(value, labelPath = emptyList(), out = out)
        return out
    }

    /** Depth-first walk of one shortcuts level, flattening groups into [out]. */
    private fun parseShortcutEntries(
        entries: List<*>,
        labelPath: List<String>,
        out: MutableList<ShortcutDraft>
    ) {
        entries.forEachIndexed { index, entry ->
            val where = "${path(KEY_SHORTCUTS)}[$index]"
            val block = entry as? Map<*, *>
                ?: throw MalformedMetadata("'$where' must be a mapping")
            rejectUnknownKeys(
                block,
                where,
                SHORTCUT_ID,
                SHORTCUT_LABEL,
                SHORTCUT_DESCRIPTION,
                SHORTCUT_COMMAND,
                SHORTCUT_ICON,
                SHORTCUT_ACTION,
                SHORTCUT_AS_ROOT,
                SHORTCUT_CHILDREN
            )
            val id = stringOrNull(block[SHORTCUT_ID], "$where.$SHORTCUT_ID")
            val ownLabel = stringOrNull(block[SHORTCUT_LABEL], "$where.$SHORTCUT_LABEL")
            val children = block[SHORTCUT_CHILDREN]
            if (children != null) {
                if (children !is List<*>) {
                    throw MalformedMetadata("'$where.$SHORTCUT_CHILDREN' must be a list of shortcut mappings")
                }
                if (children.isEmpty()) {
                    throw MalformedMetadata("'$where.$SHORTCUT_CHILDREN' must not be empty — drop the key instead")
                }
                // A group composes its label onto every descendant. Groups
                // carrying their own `command:` are rejected — a parent that
                // both navigates and executes is ambiguous in the UI (the
                // tree rule is "a parent with children never executes").
                if (block.containsKey(SHORTCUT_COMMAND)) {
                    throw MalformedMetadata(
                        "'$where' declares both '$SHORTCUT_COMMAND' and '$SHORTCUT_CHILDREN' — " +
                            "a category navigates, a command executes; split them"
                    )
                }
                if (block.containsKey(SHORTCUT_ACTION)) {
                    throw MalformedMetadata(
                        "'$where' declares '$SHORTCUT_ACTION' but has no '$SHORTCUT_COMMAND' — " +
                            "actions belong on runnable leaves"
                    )
                }
                if (block.containsKey(SHORTCUT_AS_ROOT)) {
                    throw MalformedMetadata(
                        "'$where' declares '$SHORTCUT_AS_ROOT' but has no '$SHORTCUT_COMMAND' — " +
                            "elevation belongs on runnable leaves"
                    )
                }
                // An unnamed group contributes no label segment rather than an
                // empty one — its leaves stay perfectly usable.
                val childPath = if (ownLabel != null) labelPath + ownLabel else labelPath
                parseShortcutEntries(children, childPath, out)
                return@forEachIndexed
            }
            val composedLabel = if (labelPath.isEmpty()) ownLabel
            else (labelPath + (ownLabel ?: "")).joinToString(SHORTCUT_LABEL_SEPARATOR)
            out += ShortcutDraft(
                id = id,
                label = composedLabel,
                description = stringOrNull(block[SHORTCUT_DESCRIPTION], "$where.$SHORTCUT_DESCRIPTION"),
                command = stringOrNull(block[SHORTCUT_COMMAND], "$where.$SHORTCUT_COMMAND"),
                icon = stringOrNull(block[SHORTCUT_ICON], "$where.$SHORTCUT_ICON"),
                action = stringOrNull(block[SHORTCUT_ACTION], "$where.$SHORTCUT_ACTION"),
                asRoot = booleanOrNull(block[SHORTCUT_AS_ROOT], "$where.$SHORTCUT_AS_ROOT")
            )
        }
    }

    // ── scalar helpers ──────────────────────────────────────────────────
    private fun mappingOrNull(value: Any?, where: String): Map<*, *>? = when (value) {
        null -> null
        is Map<*, *> -> value
        else -> throw MalformedMetadata("'$where' must be a mapping")
    }

    private fun stringOrNull(value: Any?, where: String): String? = when (value) {
        null -> null
        is String -> value.trim().ifEmpty {
            throw MalformedMetadata("'$where' must not be empty")
        }
        else -> throw MalformedMetadata("'$where' must be a string")
    }

    /**
     * A scalar that is *semantically* text but which YAML may hand over as a
     * number or boolean (`version: 13`, `schema_version: 1`). Collections are
     * still rejected — this is a convenience for unquoted scalars, not a
     * "accept anything" escape hatch.
     */
    private fun scalarOrNull(value: Any?, where: String): String? = when (value) {
        null -> null
        is String -> value.trim().ifEmpty { throw MalformedMetadata("'$where' must not be empty") }
        is Int, is Long, is Double, is Float, is Boolean -> value.toString()
        else -> throw MalformedMetadata("'$where' must be a scalar value")
    }

    private fun booleanOrNull(value: Any?, where: String): Boolean? = when (value) {
        null -> null
        is Boolean -> value
        else -> throw MalformedMetadata("'$where' must be a boolean (true|false)")
    }

    private fun rejectUnknownKeys(block: Map<*, *>, where: String, vararg supported: String) {
        val unknown = block.keys.map { it?.toString() ?: "null" }.filter { it !in supported }
        if (unknown.isNotEmpty()) {
            throw MalformedMetadata(
                "unknown key(s) under '$where': ${unknown.sorted().joinToString(", ")} " +
                    "(supported: ${supported.sorted().joinToString(", ")})"
            )
        }
    }

    private val ENV_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** Shape-only alias-name rule, mirroring the validator's token rule. */
    private val ALIAS_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9+._-]*$")

    /** The actionable error for the old `presentation:` spelling. */
    fun presentationMisspelledReason(): String =
        "'presentation:' is not a supported key under 'linexa:' — rename it to 'metadata:' " +
            "(the presentation block's canonical key; its sub-keys are " +
            "$PRESENTATION_DISPLAY_NAME, $PRESENTATION_DESCRIPTION, $PRESENTATION_PREVIEW)"

    private fun path(vararg segments: String): String =
        (listOf("linexa") + segments).joinToString(".")

    /** Control-flow exception carrying a precise structural reason. */
    private class MalformedMetadata(val reason: String) : RuntimeException(reason)
}

/**
 * §1.118 — raw, untyped metadata straight out of the YAML shape walk. Every
 * field is nullable because "absent" and "invalid" are different answers and
 * only [RootFsMetadataValidator] is allowed to conflate them into a failure.
 */
data class RootFsMetadataDraft(
    val schemaVersion: String? = null,
    val distro: DistroDraft? = null,
    val architecture: String? = null,
    val rootfs: RootFsDraft? = null,
    val desktop: DesktopDraft? = null,
    val packageManager: PackageManagerDraft? = null,
    val environment: EnvironmentDraft? = null,
    val workspace: WorkspaceDraft? = null,
    val shellConfig: ShellConfigDraft? = null,
    val init: InitDraft? = null,
    val healthCheck: HealthCheckDraft? = null,
    val presentation: PresentationDraft? = null,
    /** §1.120: null = key absent, empty list = author declared none. */
    val shortcuts: List<ShortcutDraft>? = null
)

data class DistroDraft(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val codename: String? = null
)

data class RootFsDraft(
    val type: String? = null,
    val compressedFormat: String? = null
)

data class DesktopDraft(
    val enabled: Boolean? = null,
    val type: String? = null,
    val session: String? = null
)

data class PackageManagerDraft(
    val type: String? = null
)

data class EnvironmentDraft(
    val shell: String? = null,
    val user: String? = null,
    val home: String? = null,
    val shellArgs: List<String>? = null,
    val variables: Map<String, String>? = null
)

/** Raw `linexa.workspace` block. */
data class WorkspaceDraft(
    val directory: String? = null
)

/** Raw `linexa.shell_config` block. */
data class ShellConfigDraft(
    val aliases: Map<String, String>? = null
)

/** Raw `linexa.init` block. */
data class InitDraft(
    val postInstall: List<String>? = null,
    val firstBoot: List<String>? = null
)

/** Raw `linexa.health_check` block. */
data class HealthCheckDraft(
    val command: String? = null
)

data class PresentationDraft(
    val displayName: String? = null,
    val description: String? = null,
    /** §1.121: raw `linexa.metadata.preview`, still unnormalized. */
    val preview: String? = null
)

/** §1.120: one raw `linexa.shortcuts[]` entry. */
data class ShortcutDraft(
    val id: String? = null,
    val label: String? = null,
    val description: String? = null,
    val command: String? = null,
    val icon: String? = null,
    /** Schema v2: semantic action tag (install/remove/update/launch/check). */
    val action: String? = null,
    /** Schema v2: launch this entry's terminal with the root emulation. */
    val asRoot: Boolean? = null
)
