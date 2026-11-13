package com.fem.linexa.service.rootfs

import com.fem.linexa.domain.packagemanager.PackageManagerDetector

/**
 * §1.118 — Semantic validation of a structurally-sound
 * [RootFsMetadataDraft], producing a typed [RootFsMetadata] or a precise
 * rejection reason.
 *
 * Every rule here exists because violating it would make the *installed*
 * workspace behave worse than one with no metadata at all — the whole point
 * of declared metadata is that consumers may trust it without re-probing, so
 * a value that cannot be trusted must be rejected at import rather than
 * silently ignored at launch. In particular:
 *
 *  - **`schema_version` must be present and supported.** An unknown version
 *    means the file may use keys with different meanings; guessing is worse
 *    than refusing (the same fail-hard stance as the rest of the
 *    `linexa.yaml` schema, §1.26).
 *  - **`distro.id`/`distro.name` are required.** They are what the workspace
 *    record persists and what the icon/branding resolves from.
 *  - **`architecture` must be a known value.** A rootfs built for the wrong
 *    CPU cannot execute under PRoot at all; a typo'd architecture that got
 *    accepted would be persisted as the workspace's arch and shown to the
 *    user as fact.
 *  - **An enabled desktop must name its type.** `desktop.enabled: true` with
 *    no type is exactly the ambiguity the block exists to remove.
 *  - **`package_manager.type` must be a supported provider key.** The key
 *    selects a compiled-in provider; an unknown key would leave the Package
 *    Manager screen with a declared-but-unusable manager.
 *  - **`environment.shell` must be an absolute, whitespace-free guest path**
 *    and **`desktop.session` a single safe token** — both end up as argv
 *    tokens in guest commands, so they get the same structural treatment
 *    §1.73 gave `linexa.launch.shell` (no shell interpretation anywhere, but
 *    also no value that *could* be split or globbed if a future consumer were
 *    less careful).
 *  - **§1.120: every `shortcuts[]` entry must be readable and uniquely
 *    keyed.** A shortcut is run only when the user taps its card, so the rules
 *    protect that consent — a required single-line label, a single-line
 *    length-capped command the card can display in full, unique ids, and a
 *    count cap. See [validateShortcuts].
 *  - **§1.121: `metadata.preview` must be a safe rootfs-relative image path.**
 *    It is the only field that names a file the app will then *render*, so an
 *    accepted `../../../etc/passwd` would put host bytes on the Home screen.
 *    [RootFsPreviewImage] owns the rule; this stage rejects a violation rather
 *    than dropping the field, because the author can see and fix it.
 *
 * Pure and JVM-testable: no I/O, no Android types.
 */
object RootFsMetadataValidator {

    sealed interface Result {
        data class Valid(val metadata: RootFsMetadata) : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * Distro ids that identify a desktop-less base image are NOT special-cased
     * anywhere: absence of a `desktop:` block means "not declared", which
     * consumers treat as "fall back to probing", never as "no desktop". Only
     * an explicit `enabled: false` is a declaration that there is none.
     */
    fun validate(draft: RootFsMetadataDraft): Result {
        val schemaVersion = draft.schemaVersion
            ?: return invalid("'linexa.schema_version' is required")
        val version = schemaVersion.toIntOrNull()
            ?: return invalid("'linexa.schema_version' must be an integer (got '$schemaVersion')")
        if (version !in RootFsMetadata.SUPPORTED_SCHEMA_VERSIONS) {
            return invalid(
                "unsupported 'linexa.schema_version' $version " +
                    "(this build supports ${RootFsMetadata.SUPPORTED_SCHEMA_VERSIONS.sorted().joinToString(", ")})"
            )
        }

        val distroDraft = draft.distro
            ?: return invalid("'linexa.distro' is required")
        val distroId = distroDraft.id
            ?: return invalid("'linexa.distro.id' is required")
        if (!isSafeToken(distroId)) {
            return invalid("'linexa.distro.id' must be a lower-case identifier without whitespace (got '$distroId')")
        }
        val distroName = distroDraft.name
            ?: return invalid("'linexa.distro.name' is required")

        val architectureValue = draft.architecture
            ?: return invalid("'linexa.architecture' is required")
        val architecture = DeclaredArchitecture.fromYaml(architectureValue)
            ?: return invalid(
                "unknown 'linexa.architecture' '$architectureValue' " +
                    "(accepted: ${DeclaredArchitecture.acceptedValues().joinToString(", ")})"
            )

        val desktop = when (val desktopDraft = draft.desktop) {
            null -> null
            else -> {
                val enabled = desktopDraft.enabled
                    ?: return invalid("'linexa.desktop.enabled' is required when 'linexa.desktop' is present")
                if (enabled && desktopDraft.type == null) {
                    return invalid("'linexa.desktop.type' is required when 'linexa.desktop.enabled' is true")
                }
                desktopDraft.type?.let { type ->
                    if (!isSafeToken(type)) {
                        return invalid(
                            "'linexa.desktop.type' must be a lower-case identifier without whitespace (got '$type')"
                        )
                    }
                }
                desktopDraft.session?.let { session ->
                    if (!isSafeSessionToken(session)) {
                        return invalid(
                            "'linexa.desktop.session' must be a single command name or absolute path " +
                                "without whitespace, quotes or shell metacharacters (got '$session')"
                        )
                    }
                }
                DeclaredDesktop(
                    enabled = enabled,
                    type = desktopDraft.type?.lowercase(),
                    session = desktopDraft.session
                )
            }
        }

        val packageManager = when (val pmDraft = draft.packageManager) {
            null -> null
            else -> {
                val type = pmDraft.type
                    ?: return invalid("'linexa.package_manager.type' is required when 'linexa.package_manager' is present")
                val normalized = type.lowercase()
                if (normalized !in PackageManagerDetector.supportedKeys) {
                    return invalid(
                        "unsupported 'linexa.package_manager.type' '$type' " +
                            "(supported: ${PackageManagerDetector.supportedKeys.joinToString(", ")})"
                    )
                }
                DeclaredPackageManager(type = normalized)
            }
        }

        val environment = when (val envDraft = draft.environment) {
            null -> null
            else -> {
                envDraft.shell?.let { shell ->
                    if (!shell.startsWith("/")) {
                        return invalid("'linexa.environment.shell' must be an absolute guest path (got '$shell')")
                    }
                    if (!isSafeSessionToken(shell)) {
                        return invalid(
                            "'linexa.environment.shell' must not contain whitespace, quotes or shell " +
                                "metacharacters (got '$shell')"
                        )
                    }
                }
                envDraft.user?.let { user ->
                    if (!isSafeToken(user) || user.length > MAX_USER_NAME_CHARS) {
                        return invalid(
                            "'linexa.environment.user' must be an identifier of at most $MAX_USER_NAME_CHARS " +
                                "characters (got '$user')"
                        )
                    }
                }
                envDraft.home?.let { home ->
                    validateGuestDirectory(home, "linexa.environment.home")?.let { return invalid(it) }
                }
                envDraft.shellArgs?.let { args ->
                    if (args.size > MAX_SHELL_ARGS) {
                        return invalid(
                            "'linexa.environment.shell_args' declares ${args.size} entries (maximum is $MAX_SHELL_ARGS)"
                        )
                    }
                    args.forEachIndexed { index, arg ->
                        if (arg.any { it.isWhitespace() || it == '\u0000' }) {
                            return invalid(
                                "'linexa.environment.shell_args[$index]' must be a single argv token " +
                                    "without whitespace or NUL (got '$arg')"
                            )
                        }
                    }
                }
                envDraft.variables?.let { variables ->
                    if (variables.size > MAX_VARIABLES) {
                        return invalid(
                            "'linexa.environment.variables' declares ${variables.size} entries (maximum is $MAX_VARIABLES)"
                        )
                    }
                    variables.forEach { (name, value) ->
                        if (value.any { it.isISOControl() } || value.length > MAX_VARIABLE_VALUE_CHARS) {
                            return invalid(
                                "'linexa.environment.variables.$name' must be a single line of at most " +
                                    "$MAX_VARIABLE_VALUE_CHARS characters without control characters"
                            )
                        }
                    }
                }
                DeclaredEnvironment(
                    shell = envDraft.shell,
                    user = envDraft.user,
                    home = envDraft.home,
                    shellArgs = envDraft.shellArgs.orEmpty(),
                    variables = envDraft.variables.orEmpty()
                )
            }
        }

        val workspace = draft.workspace?.let { workspaceDraft ->
            workspaceDraft.directory?.let { directory ->
                validateGuestDirectory(directory, "linexa.workspace.directory")?.let { return invalid(it) }
                if (directory == "/") {
                    return invalid("'linexa.workspace.directory' must not be '/' — the workspace is a dedicated directory, not the root")
                }
            }
            DeclaredWorkspace(directory = workspaceDraft.directory)
        }

        val shellConfig = draft.shellConfig?.let { shellConfigDraft ->
            val aliases = shellConfigDraft.aliases.orEmpty()
            if (aliases.size > DeclaredShellConfig.MAX_COUNT) {
                return invalid(
                    "'linexa.shell_config.aliases' declares ${aliases.size} entries " +
                        "(maximum is ${DeclaredShellConfig.MAX_COUNT})"
                )
            }
            DeclaredShellConfig(aliases = aliases)
        }

        val init = draft.init?.let { initDraft ->
            val postInstall = initDraft.postInstall.orEmpty()
            val firstBoot = initDraft.firstBoot.orEmpty()
            if (postInstall.size > DeclaredInit.MAX_COMMANDS_PER_LIST) {
                return invalid(
                    "'linexa.init.post_install' declares ${postInstall.size} commands " +
                        "(maximum is ${DeclaredInit.MAX_COMMANDS_PER_LIST})"
                )
            }
            if (firstBoot.size > DeclaredInit.MAX_COMMANDS_PER_LIST) {
                return invalid(
                    "'linexa.init.first_boot' declares ${firstBoot.size} commands " +
                        "(maximum is ${DeclaredInit.MAX_COMMANDS_PER_LIST})"
                )
            }
            (postInstall + firstBoot).forEachIndexed { index, command ->
                if (!isSingleLineText(command, DeclaredInit.MAX_COMMAND_CHARS)) {
                    return invalid(
                        "'linexa.init' command #$index must be a single line of at most " +
                            "${DeclaredInit.MAX_COMMAND_CHARS} characters without control characters"
                    )
                }
            }
            DeclaredInit(postInstall = postInstall, firstBoot = firstBoot)
        }

        val healthCheck = draft.healthCheck?.let { healthDraft ->
            val command = healthDraft.command
                ?: return invalid("'linexa.health_check.command' is required when 'linexa.health_check' is present")
            if (!isSingleLineText(command, DeclaredHealthCheck.MAX_COMMAND_CHARS)) {
                return invalid(
                    "'linexa.health_check.command' must be a single line of at most " +
                        "${DeclaredHealthCheck.MAX_COMMAND_CHARS} characters without control characters"
                )
            }
            DeclaredHealthCheck(command = command)
        }

        val presentation = draft.presentation?.let { presentationDraft ->
            // §1.121: the preview is the one declaration that names a FILE, so
            // it is validated, not merely trimmed. Fail-hard here (unlike the
            // legacy `.tiny.yaml` path) because this is Linexa's own schema and
            // its author can be told to fix it — and because silently dropping
            // a preview would leave them debugging a card that renders the
            // letter avatar for no visible reason.
            val preview = presentationDraft.preview
            if (preview != null && RootFsPreviewImage.normalize(preview) == null) {
                return invalid(
                    "'linexa.metadata.preview' must be a rootfs-relative image path without '..' " +
                        "segments, ending in ${RootFsPreviewImage.SUPPORTED_EXTENSIONS.sorted().joinToString("/")} " +
                        "(got '$preview')"
                )
            }
            DeclaredPresentation(
                displayName = presentationDraft.displayName,
                description = presentationDraft.description,
                // Persist the normalized form only, so no consumer ever sees
                // the author's raw string (see RootFsPreviewImage's kdoc).
                previewImagePath = preview?.let { RootFsPreviewImage.normalize(it) }
            )
        }

        // §1.120: null propagates as null — "no shortcuts key" must stay
        // distinguishable from "an empty shortcuts list" all the way to the UI
        // (see RootFsMetadata.shortcuts).
        val shortcuts = when (val drafts = draft.shortcuts) {
            null -> null
            else -> when (val validated = validateShortcuts(drafts)) {
                is ShortcutsResult.Invalid -> return invalid(validated.reason)
                is ShortcutsResult.Valid -> validated.shortcuts
            }
        }

        return Result.Valid(
            RootFsMetadata(
                schemaVersion = version,
                distro = DeclaredDistro(
                    id = distroId.lowercase(),
                    name = distroName,
                    version = distroDraft.version,
                    codename = distroDraft.codename
                ),
                architecture = architecture,
                rootfs = draft.rootfs?.let { DeclaredRootFs(it.type, it.compressedFormat) },
                desktop = desktop,
                packageManager = packageManager,
                environment = environment,
                workspace = workspace,
                shellConfig = shellConfig,
                init = init,
                healthCheck = healthCheck,
                presentation = presentation,
                shortcuts = shortcuts
            )
        )
    }

    /**
     * Structural rule for a declared guest directory (home / workspace):
     * absolute, whitespace-free, no traversal segments. These values become
     * `HOME` and the terminal's working directory — a relative path would be
     * interpreted against whatever cwd the process happened to have, and a
     * traversal path would declare a location outside the layout it claims
     * to describe. Returns the rejection reason, or null when acceptable.
     */
    private fun validateGuestDirectory(path: String, where: String): String? {
        if (!path.startsWith("/")) {
            return "'$where' must be an absolute guest path (got '$path')"
        }
        if (path.any { it.isWhitespace() || it == '\u0000' }) {
            return "'$where' must not contain whitespace or NUL (got '$path')"
        }
        if (path.split('/').any { it == ".." }) {
            return "'$where' must not contain '..' segments (got '$path')"
        }
        return null
    }

    private const val MAX_USER_NAME_CHARS = 32
    private const val MAX_SHELL_ARGS = 8
    private const val MAX_VARIABLES = 24
    private const val MAX_VARIABLE_VALUE_CHARS = 256

    private sealed interface ShortcutsResult {
        data class Valid(val shortcuts: List<DeclaredShortcut>) : ShortcutsResult
        data class Invalid(val reason: String) : ShortcutsResult
    }

    /**
     * §1.120 — validates `linexa.shortcuts`.
     *
     * The rules exist to protect the *consent* the tap represents, not to
     * second-guess the command itself (see [DeclaredShortcut]'s kdoc on why the
     * command is allowed to be a guest shell string at all). A card the user
     * cannot read honestly is the actual risk here, so:
     *
     *  - **`id` is a safe token and unique.** It keys per-shortcut run state; a
     *    duplicate would make two cards share a spinner and a result.
     *  - **`label` is required, single-line and length-capped.** A label
     *    carrying `\n` or control characters could render across other cards'
     *    space or spoof a second entry.
     *  - **`command` is required, single-line and length-capped.** The card
     *    shows the command verbatim; a multi-line or 4 KiB command would be
     *    displayed truncated, hiding what the rest of it does.
     *  - **The list is count-capped**, so declared entries cannot push the
     *    built-in catalog off the screen.
     *
     * A violation fails the *import*, consistent with the rest of §1.118: the
     * author can fix it, and a half-usable Shortcuts list is worse than a clear
     * rejection at the one moment someone is watching.
     */
    private fun validateShortcuts(drafts: List<ShortcutDraft>): ShortcutsResult {
        if (drafts.size > DeclaredShortcut.MAX_COUNT) {
            return ShortcutsResult.Invalid(
                "'linexa.shortcuts' declares ${drafts.size} entries " +
                    "(maximum is ${DeclaredShortcut.MAX_COUNT})"
            )
        }

        val seen = mutableSetOf<String>()
        val out = mutableListOf<DeclaredShortcut>()

        drafts.forEachIndexed { index, draft ->
            val where = "linexa.shortcuts[$index]"

            val id = draft.id ?: return ShortcutsResult.Invalid("'$where.id' is required")
            if (!isSafeToken(id)) {
                return ShortcutsResult.Invalid(
                    "'$where.id' must be an identifier without whitespace (got '$id')"
                )
            }
            val normalizedId = id.lowercase()
            if (!seen.add(normalizedId)) {
                return ShortcutsResult.Invalid("'$where.id' duplicates an earlier shortcut id ('$id')")
            }

            val label = draft.label ?: return ShortcutsResult.Invalid("'$where.label' is required")
            if (!isSingleLineText(label, DeclaredShortcut.MAX_LABEL_CHARS)) {
                return ShortcutsResult.Invalid(
                    "'$where.label' must be a single line of at most " +
                        "${DeclaredShortcut.MAX_LABEL_CHARS} characters without control characters"
                )
            }

            draft.description?.let { description ->
                if (!isSingleLineText(description, DeclaredShortcut.MAX_DESCRIPTION_CHARS)) {
                    return ShortcutsResult.Invalid(
                        "'$where.description' must be a single line of at most " +
                            "${DeclaredShortcut.MAX_DESCRIPTION_CHARS} characters without control characters"
                    )
                }
            }

            // Issue #1 (2026-09-03): an as-root entry MAY omit the command —
            // it then opens a bare root shell. Any other entry still requires
            // one.
            val asRoot = draft.asRoot ?: false
            val command = draft.command
            if (command == null && !asRoot) {
                return ShortcutsResult.Invalid("'$where.command' is required")
            }
            command?.let {
                if (!isSingleLineText(it, DeclaredShortcut.MAX_COMMAND_CHARS)) {
                    return ShortcutsResult.Invalid(
                        "'$where.command' must be a single line of at most " +
                            "${DeclaredShortcut.MAX_COMMAND_CHARS} characters without control characters " +
                            "(so the card can show the user the whole command)"
                    )
                }
            }

            draft.icon?.let { icon ->
                if (!isSafeToken(icon)) {
                    return ShortcutsResult.Invalid(
                        "'$where.icon' must be an identifier without whitespace (got '$icon')"
                    )
                }
            }

            // Schema v2 (issue #5): the optional action tag must be one of the
            // known actions — a typo'd action silently rendering as a plain
            // card would be worse than naming it here.
            draft.action?.let { action ->
                val normalized = action.lowercase()
                if (normalized !in DeclaredShortcut.ACTIONS) {
                    return ShortcutsResult.Invalid(
                        "'$where.action' must be one of " +
                            DeclaredShortcut.ACTIONS.sorted().joinToString(", ") + " (got '$action')"
                    )
                }
            }

            out += DeclaredShortcut(
                id = normalizedId,
                label = label,
                description = draft.description,
                command = command ?: "",
                icon = draft.icon?.lowercase(),
                action = draft.action?.lowercase(),
                asRoot = asRoot
            )
        }

        return ShortcutsResult.Valid(out)
    }

    /**
     * Displayable, tappable text: non-blank, within [maxChars], and free of
     * control characters (including newlines and tabs). Everything here ends up
     * on a card the user reads before consenting, so a value that can break out
     * of its line is rejected rather than sanitized — silently rewriting an
     * author's label would hide the mistake from them.
     */
    private fun isSingleLineText(value: String, maxChars: Int): Boolean {
        if (value.isBlank() || value.length > maxChars) return false
        return value.none { it.isISOControl() }
    }

    /**
     * A lower-case identifier token: letters, digits, `-`, `_`, `.`, `+`.
     * Deliberately narrower than "no shell metacharacters" — these values are
     * identifiers Linexa compares and lower-cases, so anything exotic is a
     * mistake worth reporting.
     */
    private fun isSafeToken(value: String): Boolean =
        value.isNotBlank() && TOKEN_REGEX.matches(value)

    /**
     * A single argv token that may be handed to a guest command: an absolute
     * path or a bare command name, with no whitespace, NUL, quotes or shell
     * metacharacters. Even though every consumer passes these as typed
     * fragments today, the guard is enforced here so a future consumer cannot
     * turn declared metadata into a command-injection surface (§1.14).
     */
    private fun isSafeSessionToken(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.any { it.isWhitespace() || it == '\u0000' }) return false
        if (value.any { it in SHELL_METACHARACTERS }) return false
        return SESSION_TOKEN_REGEX.matches(value)
    }

    private fun invalid(reason: String): Result.Invalid = Result.Invalid(reason)

    private val TOKEN_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9+._-]*$")

    /** Absolute path or bare command name, `/`-separated safe segments. */
    private val SESSION_TOKEN_REGEX = Regex("^/?([a-zA-Z0-9+._-]+/)*[a-zA-Z0-9+._-]+$")

    private val SHELL_METACHARACTERS = charArrayOf(
        ';', '|', '&', '$', '`', '<', '>', '(', ')', '{', '}', '[', ']',
        '\'', '"', '\\', '*', '?', '!', '#', '~', '\n', '\r'
    ).toSet()
}
