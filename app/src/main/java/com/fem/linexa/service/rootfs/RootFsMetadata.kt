package com.fem.linexa.service.rootfs

/**
 * §1.118 — Linexa's **native** RootFS metadata: the typed form of the
 * `linexa.yaml` file an archive carries at its root.
 *
 * The full schema — every block, the validation rules, the runtime consumers,
 * AND the deterministic metadata precedence (archive-native › bundled
 * version-specific › bundled generic › legacy `.tiny.yaml` › undeclared) — is
 * documented in `docs/ROOTFS_METADATA_SCHEMA.md`.
 *
 * This is Linexa's own format, not a re-use of tiny_container's `.tiny.yaml`
 * (see §1.118 for the compatibility decision). It exists so the app does not
 * have to *discover* by probing what an archive author already knows:
 *
 * - which distribution/version/codename this rootfs is,
 * - which CPU architecture it was built for,
 * - which package manager it ships (instead of a `which apt-get pacman apk
 *   dnf zypper` probe inside a running guest),
 * - whether it has a desktop, and which one (instead of grepping the
 *   installed-package list for XFCE/GNOME/KDE),
 * - which login shell to use (instead of probing `/bin/bash` → `/bin/sh`),
 * - how it should be presented to the user.
 *
 * Every field is optional at the *type* level, because a rootfs archive
 * without `linexa.yaml` is still perfectly installable — the pre-existing
 * signal-based detection ([com.fem.linexa.service.installer.RootFsProbe])
 * remains the fallback and stays fully supported. What the schema does
 * enforce, via [RootFsMetadataParser] + [RootFsMetadataValidator], is that a
 * file which *is* present must be complete and coherent: a half-declared
 * `desktop:` block or an unknown architecture fails the import loudly rather
 * than silently degrading to a guess.
 *
 * Security model (unchanged from §1.27/§1.49/§1.73): **this metadata is data,
 * never code.** There is no free-form command string anywhere in it —
 * [DeclaredPackageManager] carries a *type key* that selects one of the
 * vetted [com.fem.linexa.domain.packagemanager.PackageProvider]
 * implementations whose commands are compiled into the app, and
 * [DeclaredDesktop.session] / [DeclaredEnvironment.shell] are single,
 * whitespace-free argv tokens validated before they can reach a guest
 * command. See §1.118's "why `package_manager.commands:` is deliberately not
 * in the schema" note.
 */
data class RootFsMetadata(
    /** `linexa.schema_version` — the only value understood today is `1`. */
    val schemaVersion: Int,
    val distro: DeclaredDistro,
    val architecture: DeclaredArchitecture,
    val rootfs: DeclaredRootFs? = null,
    val desktop: DeclaredDesktop? = null,
    val packageManager: DeclaredPackageManager? = null,
    val environment: DeclaredEnvironment? = null,
    /** `linexa.workspace` — the declared per-workspace guest directory layout. */
    val workspace: DeclaredWorkspace? = null,
    /** `linexa.shell_config` — declared interactive-shell conveniences (aliases). */
    val shellConfig: DeclaredShellConfig? = null,
    /** `linexa.init` — declared one-shot initialization commands (first boot). */
    val init: DeclaredInit? = null,
    /** `linexa.health_check` — a declared guest command that proves the rootfs boots. */
    val healthCheck: DeclaredHealthCheck? = null,
    val presentation: DeclaredPresentation? = null,
    /**
     * §1.120 — `linexa.shortcuts`, the image's own Shortcuts catalog.
     *
     * **Nullable on purpose, and an empty list is not the same as null.** The
     * key's absence and an explicit `shortcuts: []` are different statements by
     * the author, and collapsing them would remove the only way an image can
     * say "I offer no shortcuts":
     *
     *  - `null` — the key was absent. The app keeps its built-in catalog
     *    Quick Commands catalog asset), which is
     *    what every pre-§1.120 image and every workspace installed before this
     *    schema existed relies on.
     *  - empty list — the author declared none deliberately. Only the app's own
     *    Custom Command card remains.
     *
     * the UI-layer command mappers are the
     * single place that turns this tri-state into a rendered list.
     */
    val shortcuts: List<DeclaredShortcut>? = null
) {
    companion object {
        /** Schema versions this build can install. */
        // Schema 2 (2026-09-03, issue #3/#4/#5): adds hierarchical shortcut entries
    // (`children:` groups), optional `action:` tags on leaves and `as_root:`
    // elevation launchers. Everything from schema 1 parses unchanged.
    val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1, 2)
    }
}

/**
 * `linexa.distro` — the identity the app would otherwise have to read out of
 * `/etc/os-release` *after* extracting.
 *
 * @param id lower-case distro id, os-release `ID` semantics (`debian`,
 *   `alpine`, `archarm`, …). Required: it is what maps the workspace to its
 *   branded icon and its canonical package manager.
 * @param name human-readable name (os-release `NAME`), e.g.
 *   `Debian GNU/Linux`. Required.
 * @param version release version (os-release `VERSION_ID`), e.g. `13`. Null
 *   for rolling releases, which genuinely have none.
 * @param codename release codename, e.g. `trixie`. Optional.
 */
data class DeclaredDistro(
    val id: String,
    val name: String,
    val version: String? = null,
    val codename: String? = null
) {
    /**
     * UI/UX Phase 5C — the distribution rendered as one line, e.g.
     * `Debian GNU/Linux 13 (trixie)`.
     *
     * Composed from [name], [version] and [codename] rather than from a
     * pre-joined string, because only [name] is required: a rolling release
     * genuinely has no [version] and most images declare no [codename], so the
     * label degrades to `Arch Linux ARM` instead of `Arch Linux ARM null ()`.
     *
     * Deliberately built from the **declaration** and nowhere else. This is the
     * text Phase 5C attributes a command to, and a probe-detected identity —
     * however accurate — is not the image author stating which distribution
     * shipped that command.
     */
    val displayLabel: String
        get() = buildString {
            append(name.trim())
            version?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(' ')
                append(it)
            }
            codename?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(" (")
                append(it)
                append(')')
            }
        }
}

/**
 * `linexa.architecture` — the CPU architecture the rootfs binaries target.
 *
 * Declared with the names rootfs publishers actually use; [abi] is the
 * Android ABI the value maps to, so it can be compared against the device's
 * supported ABIs and persisted in `DistroInfo.arch` without a second
 * translation table.
 */
enum class DeclaredArchitecture(val yamlAliases: Set<String>, val abi: String, val label: String) {
    ARM64(setOf("arm64", "aarch64", "arm64-v8a", "armv8", "armv8a"), "arm64-v8a", "ARM64"),
    ARM32(setOf("arm", "armv7", "armv7l", "armhf", "armeabi-v7a", "armel"), "armeabi-v7a", "ARMv7"),
    X86_64(setOf("x86_64", "amd64", "x64"), "x86_64", "x86_64"),
    X86(setOf("x86", "i386", "i486", "i586", "i686"), "x86", "x86");

    companion object {
        /** Resolve a YAML value to an architecture, or null when unrecognized. */
        fun fromYaml(value: String): DeclaredArchitecture? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { normalized in it.yamlAliases }
        }

        /** Every accepted spelling, for error messages. */
        fun acceptedValues(): List<String> = entries.flatMap { it.yamlAliases }.sorted()
    }
}

/**
 * `linexa.rootfs` — how the archive was built. Purely informational: the
 * installer sniffs the real compression from the stream's magic bytes
 * ([com.fem.linexa.service.installer.StagedRootFsInstaller]) and never trusts
 * a declaration for that, so a wrong `compressed_format` cannot mis-drive
 * extraction. It is surfaced to the user and kept for diagnostics.
 *
 * @param type free-form flavour label (`minimal`, `base`, `desktop`, …).
 * @param compressedFormat declared container format (`tar.zst`, `tar.gz`, …).
 */
data class DeclaredRootFs(
    val type: String? = null,
    val compressedFormat: String? = null
)

/**
 * `linexa.desktop` — whether this rootfs has a graphical session, and which.
 *
 * Replaces "search the installed packages for XFCE/GNOME/KDE and guess".
 * When [enabled] is true, [type] is **required** (an enabled desktop with no
 * type is exactly the ambiguity this block exists to remove).
 *
 * @param enabled whether the rootfs ships a desktop session.
 * @param type desktop identifier (`xfce`, `gnome`, `kde`, `lxde`, `mate`,
 *   `openbox`, `i3`, …), a safe lower-case token. Required when [enabled].
 * @param session optional explicit session entry point — either a bare
 *   binary name (`startxfce4`) or an absolute guest path
 *   (`/usr/bin/startxfce4`). Validated to a single whitespace-free token so
 *   it can be handed to the desktop layer's guest command without ever being
 *   shell-split. When absent, the desktop layer derives candidates from
 *   [type].
 */
data class DeclaredDesktop(
    val enabled: Boolean,
    val type: String? = null,
    val session: String? = null
)

/**
 * `linexa.package_manager` — which package manager this rootfs ships.
 *
 * [type] is a *provider key* matching
 * [com.fem.linexa.domain.packagemanager.PackageManagerDetector]'s keys
 * (`apt`, `pacman`, `apk`, `dnf`, `zypper`), so declaring it lets the
 * Package Manager screen skip its in-guest `which` probe entirely and go
 * straight to the vetted provider whose commands *and output parsers* are
 * compiled into the app.
 *
 * §1.118 deliberately does **not** accept per-operation command strings here.
 * A package manager is not just its command spelling: every provider also
 * needs an output parser for `dpkg-query`/`pacman -Q`/`apk info`/… formats,
 * and no YAML string can supply that. Accepting `commands:` would therefore
 * either be inert (a command that runs but whose output nothing can read) or
 * an arbitrary-command channel into the guest — both worse than selecting a
 * complete, tested provider by key.
 */
data class DeclaredPackageManager(
    val type: String
)

/**
 * `linexa.environment` — declared runtime environment defaults.
 *
 * @param shell absolute guest path of the login shell, replacing the
 *   `/bin/bash` → `/usr/bin/bash` → `/bin/sh` probe. A `linexa.launch.shell`
 *   override (§1.73) still wins over this, since that block exists precisely
 *   to override launch behaviour.
 * @param user `linexa.environment.user` — the effective guest identity name
 *   exposed via `USER`/`LOGNAME`. Under PRoot `-0` the guest runs with an
 *   emulated uid 0, so the honest default is `root`; a declaration that names
 *   anything else is honoured verbatim (the app does not verify /etc/passwd —
 *   the declaration is the image author's statement about their own image).
 * @param home `linexa.environment.home` — the guest `HOME` directory. Defaults
 *   to `/root` (consistent with the emulated uid and `/etc/passwd`); an image
 *   may declare a Termux-style normal-home layout instead. Must be an absolute
 *   guest path without traversal.
 * @param shellArgs `linexa.environment.shell_args` — argv for the declared
 *   shell, replacing the default `["--login"]`. A `linexa.launch.shellArgs`
 *   override (§1.73) still wins.
 * @param variables `linexa.environment.variables` — extra guest environment
 *   variables declared by the image, merged into every launch with the SAME
 *   host-key protection as the config `linexa.env` block (the runtime's
 *   `HOST_ENV_KEYS` guard wins; a declaration can never override
 *   `PATH`/`HOME`/`LD_LIBRARY_PATH`/… host plumbing).
 */
data class DeclaredEnvironment(
    val shell: String? = null,
    val user: String? = null,
    val home: String? = null,
    val shellArgs: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap()
)

/**
 * `linexa.workspace` — the declared per-workspace directory layout.
 *
 * The whole point of this block is issue #4's architecture: the terminal must
 * open in a dedicated workspace directory (a Termux-like "the user's stuff
 * lives HERE" location), not in a system directory like `/root`. The runtime
 * creates the directory on install and self-heals it at every launch, seeds a
 * short README pointing at the managed layout, and starts every interactive
 * shell there. [directory] is an absolute guest path; the runtime falls back
 * to `<home>/linexa` when absent, and to the declared home when even the
 * fallback cannot be created (launch must never fail over a mkdir).
 */
data class DeclaredWorkspace(
    /** `linexa.workspace.directory` — absolute guest path of the workspace. */
    val directory: String? = null
) {
    companion object {
        /** Default workspace directory relative to the effective home. */
        const val DEFAULT_DIRECTORY_NAME = "linexa"
    }
}

/**
 * `linexa.shell_config` — declared interactive-shell conveniences.
 *
 * Aliases are written to `/etc/profile.d/00-linexa-shell.sh` (login shells
 * only, so scripts never see them; the guest's own `~/.bashrc` is sourced
 * after `/etc/profile` and therefore always wins over a declared alias).
 * This is what makes `la` work identically on every distribution: Debian's
 * minimal rootfs ships no aliases in `/root/.bashrc` while Ubuntu's does —
 * the declaration removes the distribution lottery without faking commands
 * (each alias is a real shell alias, exactly as the user would have typed it).
 */
data class DeclaredShellConfig(
    /** `linexa.shell_config.aliases` — alias name → expansion (raw shell text). */
    val aliases: Map<String, String> = emptyMap()
) {
    companion object {
        /** Cap on declared aliases per image. */
        const val MAX_COUNT = 40

        /** Cap on a single alias expansion's length. */
        const val MAX_VALUE_CHARS = 200

        /** Cap on an alias name's length. */
        const val MAX_NAME_CHARS = 32
    }
}

/**
 * `linexa.init` — declared one-shot initialization commands.
 *
 * Both lists run INSIDE the guest, once per rootfs, before the first
 * interactive terminal opens (a `.linexa/first-boot.done` marker inside the
 * rootfs records completion; [MAX_ATTEMPTS] bounds retries after a failed
 * attempt so a permanently-failing command cannot slow every launch forever).
 * [postInstall] runs first (the image's "right after install" steps), then
 * [firstBoot] (the image's "before you first use me" steps).
 *
 * Security posture is the shortcuts' posture (§1.120): these are guest shell
 * commands with the same power a user's first typed command has — never host
 * execution — and they run visibly in the runtime trace. They exist so a
 * distribution image can carry its own light initialization (create the
 * workspace, seed configs) instead of the app hardcoding distro knowledge.
 */
data class DeclaredInit(
    /** `linexa.init.post_install` — runs before [firstBoot] on first boot. */
    val postInstall: List<String> = emptyList(),
    /** `linexa.init.first_boot` — runs after [postInstall] on first boot. */
    val firstBoot: List<String> = emptyList()
) {
    companion object {
        /** Cap on each list's length. */
        const val MAX_COMMANDS_PER_LIST = 16

        /** Cap on a single command's length (same rationale as shortcuts). */
        const val MAX_COMMAND_CHARS = 512

        /** Total first-boot attempts before the marker is written anyway. */
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * `linexa.health_check` — a declared guest command that proves the rootfs can
 * actually boot and execute binaries. Run once by the install pipeline after
 * activation (issue #2's "detect a broken rootfs before presenting it as
 * successfully installed"); a non-zero exit marks the workspace Broken with
 * the command's output as the diagnostic, instead of advertising a workspace
 * that can never start.
 */
data class DeclaredHealthCheck(
    /** `linexa.health_check.command` — the guest command to run. */
    val command: String
) {
    companion object {
        /** Cap on the command's length. */
        const val MAX_COMMAND_CHARS = 256

        /** Seconds the health check may run before it is treated as failed. */
        const val TIMEOUT_SECONDS = 60L
    }
}

/**
 * `linexa.metadata` — how to present this rootfs to the user.
 *
 * @param displayName suggested workspace name (e.g. `Debian 13 XFCE`). Used
 *   as the pre-filled container name on the confirmation screen; the user can
 *   always edit it.
 * @param description one-paragraph description shown on the confirmation
 *   screen and, since §1.121, on the Home card.
 * @param previewImagePath §1.121 — `linexa.metadata.preview`, an image
 *   **inside the rootfs** to show instead of the letter avatar.
 *
 *   Stored **rootfs-relative and normalized** (`opt/linexa/preview.png`), never
 *   absolute. The workspace's on-disk location is an implementation detail of
 *   the host, and a declaration that could name a host path would be a
 *   file-disclosure channel: the Home card renders this, so an accepted
 *   `/data/data/.../databases/linexa.db` or `../../../etc/passwd` would put
 *   host bytes on screen. [RootFsPreviewImage] owns both halves of that rule —
 *   it normalizes the declared value at import (rejecting traversal outright)
 *   and re-verifies containment against the real rootfs at read time, because
 *   the file can be a symlink planted after extraction.
 */
data class DeclaredPresentation(
    val displayName: String? = null,
    val description: String? = null,
    val previewImagePath: String? = null
)

/**
 * §1.120 — one entry of `linexa.shortcuts`: a labelled command the Shortcuts
 * tab offers for **this** image.
 *
 * Why the schema carries commands at all, when §1.118 deliberately refused
 * `package_manager.commands:`. The two are different questions:
 *
 *  - `package_manager.type` selects a compiled-in
 *    [com.fem.linexa.domain.packagemanager.PackageProvider] whose *output
 *    parser* the app owns. A YAML command string cannot supply a parser, so
 *    accepting one there would produce a manager whose output nothing can
 *    read. That rejection stands, unchanged.
 *  - A shortcut has no parser to supply. Its entire contract is "run this and
 *    show me the output", which is exactly what the Shortcuts tab already did
 *    with the hard-coded catalog and what a Tiny Container `.tiny.yaml`
 *    `quick_commands:` entry has always meant. Declaring it moves distro
 *    knowledge out of Kotlin (`apt` for Debian, `pacman` for Arch) and into
 *    the image that actually knows which package manager it ships.
 *
 * Security posture, and its honest limit. [command] is a **guest shell
 * command**, run inside the workspace through the same `/bin/sh -c` bridge as
 * the existing built-in shortcuts and the Package Manager
 * ([com.fem.linexa.service.runtime.RuntimeBinder.execCommandStreaming]) — so a
 * declared shortcut can do anything a shortcut could already do, and nothing
 * more. It is confined to the PRoot guest, runs only when the **user taps the
 * card**, and never runs at import, at launch, or in the background. It is
 * *not* host execution and never reaches the launcher argv that §1.49/§1.73
 * protect.
 *
 * What is still enforced, because "the user tapped it" is only meaningful if
 * the user could see what they were tapping ([RootFsMetadataValidator]):
 *  - [id] is a safe identifier (it keys run state and must not collide),
 *  - [label] is required and control-characters/newlines are rejected — a
 *    label that renders as two lines, or that pads itself with spaces to look
 *    like a different card, defeats the confirmation the tap is supposed to be,
 *  - [command] must be single-line, non-blank, and within a length cap, so the
 *    card can show the user the whole thing rather than a truncated prefix that
 *    hides a `; rm -rf /` tail,
 *  - the list is count-capped, so an image cannot bury the built-ins under a
 *    thousand entries.
 *
 * @param id stable key for this shortcut, unique within the list. Also the
 *   run-state key, so it must be a safe token.
 * @param label the card's title. Required — an unlabelled command is one the
 *   user cannot consent to.
 * @param description optional one-line explanation shown under the label.
 * @param command the guest shell command to run. Shown on the card verbatim,
 *   so the user sees precisely what a tap will run.
 * @param icon optional icon key, resolved against a fixed set of bundled
 *   drawables by the UI. An unknown key falls back to the terminal icon; the
 *   metadata cannot reference arbitrary app resources.
 */
data class DeclaredShortcut(
    val id: String,
    val label: String,
    val description: String? = null,
    val command: String,
    val icon: String? = null,
    /**
     * Schema v2 (issue #5, 2026-09-03): the entry's semantic action — one
     * of [ACTION_INSTALL], [ACTION_REMOVE], [ACTION_UPDATE], [ACTION_LAUNCH],
     * [ACTION_CHECK] — or null when the author did not tag it. The UI maps
     * a tagged action to its icon when the entry declares no explicit
     * `icon`, and the tag documents the entry's EFFECT independently of its
     * (fully displayed) command string. Actions are OPTIONAL: an entry may
     * carry any subset, and the UI renders only what exists — the exact
     * "Install / Remove / …" model the requirement draws, with no forced
     * completeness.
     */
    val action: String? = null,
    /**
     * Issue #1 (2026-09-03): when true, tapping this entry opens its
     * terminal with the ROOT emulation (the explicit elevation surface).
     * Command may be empty — a bare root shell — but the validator only
     * allows the empty command FOR an as-root entry.
     */
    val asRoot: Boolean = false
) {
    companion object {
        /** Sanity cap on declared shortcuts per image. */
        const val MAX_COUNT = 120

        /** Cap on a command's length, so a card can display all of it. */
        const val MAX_COMMAND_CHARS = 512

        /** Cap on label/description length. */
        const val MAX_LABEL_CHARS = 80
        const val MAX_DESCRIPTION_CHARS = 200

        /** Schema v2 action tags (issue #5). Optional, free-order, subset-able. */
        const val ACTION_INSTALL = "install"
        const val ACTION_REMOVE = "remove"
        const val ACTION_UPDATE = "update"
        const val ACTION_LAUNCH = "launch"
        const val ACTION_CHECK = "check"

        /** The closed set of recognized actions; anything else is a validator error. */
        val ACTIONS: Set<String> = setOf(
            ACTION_INSTALL, ACTION_REMOVE, ACTION_UPDATE, ACTION_LAUNCH, ACTION_CHECK
        )
    }
}
