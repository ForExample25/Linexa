package com.fem.linexa.service.runtime.container

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * The shared home-directory architecture every supported distribution uses
 * (Arch Linux ARM, Debian, Ubuntu, Alpine, Fedora).
 *
 * ## Why this is not part of the File Manager
 *
 * The File Manager used to hardcode its "Home" place to `/root`. That was not
 * reporting a misconfigured account: the real Workspace account
 * (`linexa:x:1000:1000:Linexa User:/home/linexa:/bin/bash`) was correct in
 * `/etc/passwd`, and the terminal already started in `/home/linexa`. The
 * shortcut simply ignored the guest identity.
 *
 * Fixing only the tile would leave three other surfaces able to disagree about
 * "home": the installer, the launch path, and the desktop. This object is the
 * single description of the layout:
 *
 *  - [STANDARD_DIRECTORIES] — the nine directories created inside
 *    `/home/linexa`, including the extra `Photos` directory (which is NOT an
 *    XDG directory and never replaces `Pictures`);
 *  - [XDG_DIRECTORIES] — the eight standard XDG user directories;
 *  - [resolveHomes] — identity-aware resolution from the account database;
 *  - [provision] — idempotent, non-destructive creation and XDG configuration.
 *
 * ## Identity rules
 *
 * **The account database owns the facts.** `/etc/passwd` is parsed through
 * [GuestIdentity], and a matching account's own `uid`, `gid`, `home` and
 * `shell` win. Declared image metadata only chooses the account NAME (and is
 * the fallback when the account does not exist yet). A normal account whose
 * home points at `/root` is treated as unusable — that path belongs to the
 * root account — and resolves to the declared normal-user home instead.
 *
 * **Root stays independent.** A workspace explicitly configured with
 * `user: root` resolves Home to `/root`. A normal-user workspace resolves to
 * its own home and additionally exposes `/root` through a separate Root Home
 * shortcut. `/root` is never created, renamed, or populated by the normal
 * user's provisioning.
 *
 * ## Ownership and permissions
 *
 * Android app-private storage is owned by the app's kernel uid; a non-root app
 * cannot `chown` a file to the guest uid (1000/1001). PRoot's fake-id layer
 * maps files owned by the real Android uid to the emulated identity chosen by
 * `-0` / `--change-id`, which is why a `linexa` session sees its own home as
 * linexa-owned. What this code can and does guarantee host-side is:
 *
 *  - the directories are created by the app owner (the only owner PRoot can
 *    map back to the workspace user);
 *  - created directories get mode `0755`, so the guest identity and its group
 *    can traverse them;
 *  - `user-dirs.dirs` gets mode `0644`, readable and owner-writable — and the
 *    owner is the identity the guest session reports.
 *
 * Existing entries are never re-owned or re-permissioned: a migration must
 * not rewrite unrelated RootFS state.
 */
object WorkspaceHomeLayout {

    /** The nine directories every Linexa home contains. */
    val STANDARD_DIRECTORIES: List<String> = listOf(
        "Desktop",
        "Documents",
        "Downloads",
        "Music",
        "Pictures",
        "Photos",
        "Public",
        "Templates",
        "Videos"
    )

    /**
     * The eight standard XDG user directories, in the order
     * `user-dirs.dirs` declares them. `Photos` is deliberately absent: it is
     * an extra Linexa directory, not a replacement for `Pictures`.
     */
    val XDG_DIRECTORIES: List<Pair<String, String>> = listOf(
        "XDG_DESKTOP_DIR" to "Desktop",
        "XDG_DOCUMENTS_DIR" to "Documents",
        "XDG_DOWNLOAD_DIR" to "Downloads",
        "XDG_MUSIC_DIR" to "Music",
        "XDG_PICTURES_DIR" to "Pictures",
        "XDG_PUBLICSHARE_DIR" to "Public",
        "XDG_TEMPLATES_DIR" to "Templates",
        "XDG_VIDEOS_DIR" to "Videos"
    )

    const val CONFIG_DIRECTORY = ".config"
    const val USER_DIRS_FILE = "user-dirs.dirs"

    private const val USER_DIRS_HEADER =
        "# This file is written by Linexa (xdg-user-dirs compatible).\n" +
            "# Edit a line to change a directory; Linexa keeps every custom entry\n" +
            "# it does not manage and repairs only the standard eight below.\n" +
            "# Format: XDG_xxx_DIR=\"\$HOME/yyy\" or XDG_xxx_DIR=\"/absolute/path\".\n"

    /** Resolved homes for one Workspace. */
    data class WorkspaceHomes(
        val userName: String,
        val userHome: String,
        val rootHome: String = GuestIdentity.ROOT_HOME,
        val isRootIdentity: Boolean = false
    ) {
        /**
         * The File Manager offers a separate Root Home place only when the
         * selected identity's home is not already root's. A root-context
         * Workspace must not show two entries pointing at the same directory.
         */
        val showRootHomeShortcut: Boolean
            get() = !isRootIdentity && userHome != rootHome

        companion object {
            /** The normal-user default; also the pre-account-resolution placeholder. */
            fun default(): WorkspaceHomes = WorkspaceHomes(
                userName = GuestIdentity.DEFAULT_USER_NAME,
                userHome = GuestIdentity.DEFAULT_USER_HOME,
                rootHome = GuestIdentity.ROOT_HOME,
                isRootIdentity = false
            )
        }
    }

    /** What one idempotent [provision] pass changed (empty on steady state). */
    data class ProvisionReport(
        val createdDirectories: List<String> = emptyList(),
        val existingDirectories: List<String> = emptyList(),
        /** Entries that existed but were a symlink/regular file — left untouched. */
        val blockedEntries: List<String> = emptyList(),
        val userDirsCreated: Boolean = false,
        val userDirsUpdated: Boolean = false,
        /** True when `user-dirs.dirs` was already byte-identical. */
        val userDirsUnchanged: Boolean = false,
        val configDirectoryCreated: Boolean = false
    ) {
        val changed: Boolean
            get() = createdDirectories.isNotEmpty() || userDirsCreated || userDirsUpdated || configDirectoryCreated
    }

    // ── identity-aware resolution ─────────────────────────────────────────

    /**
     * Resolves the homes a Workspace should navigate to, from its real
     * `/etc/passwd` first and the declared environment second.
     *
     * @param rootfs the installed workspace rootfs (null/unreadable degrades to
     *   the declared environment — a broken image must still have a Home).
     * @param declaredUser the account name the image declared (`linexa` for
     *   every bundled profile).
     * @param declaredHome the declared home for that account.
     */
    fun resolveHomes(
        rootfs: File?,
        declaredUser: String = GuestIdentity.DEFAULT_USER_NAME,
        declaredHome: String? = GuestIdentity.DEFAULT_USER_HOME
    ): WorkspaceHomes {
        val normalizedDeclaredHome = usableNormalHome(declaredHome) ?: GuestIdentity.DEFAULT_USER_HOME
        val entries = rootfs?.let { GuestIdentity.readPasswd(it) }.orEmpty()
        val rootEntry = entries.firstOrNull { it.name == "root" && it.uid == 0 }
        val rootHome = normalizeHome(rootEntry?.home) ?: GuestIdentity.ROOT_HOME

        if (declaredUser == "root") {
            val home = usableRootHome(rootEntry?.home) ?: GuestIdentity.ROOT_HOME
            return WorkspaceHomes(
                userName = "root",
                userHome = home,
                rootHome = home,
                isRootIdentity = true
            )
        }

        val account = entries.firstOrNull { it.name == declaredUser }
        val home = when {
            // The account database owns the facts, except that a NON-root
            // account may never share root's home. That is the one passwd
            // value provisioning repairs (see ensureGuestUser).
            account != null && account.uid != 0 -> usableNormalHome(account.home)
            else -> null
        } ?: normalizedDeclaredHome

        return WorkspaceHomes(
            userName = declaredUser.ifBlank { GuestIdentity.DEFAULT_USER_NAME },
            userHome = home,
            rootHome = rootHome,
            isRootIdentity = false
        )
    }

    /**
     * A usable home for a NORMAL account: absolute, not the filesystem root,
     * not root's home, and impossible to escape the rootfs with `..`.
     */
    fun usableNormalHome(raw: String?): String? {
        val home = normalizeHome(raw) ?: return null
        if (home == GuestIdentity.ROOT_HOME) return null
        if (home.split('/').any { it == ".." }) return null
        return home
    }

    /** Root's home may legitimately be `/root`; it must still be absolute. */
    private fun usableRootHome(raw: String?): String? {
        val home = normalizeHome(raw) ?: return null
        if (home.split('/').any { it == ".." }) return null
        return home
    }

    private fun normalizeHome(raw: String?): String? {
        val value = raw?.trim()?.trimEnd('/') ?: return null
        if (!value.startsWith("/")) return null
        if (value.isEmpty() || value == "/") return null
        return value
    }

    // ── idempotent provisioning ───────────────────────────────────────────

    /**
     * Creates the nine standard directories and writes the XDG configuration
     * under [homeDir].
     *
     * Non-destructive by construction:
     *  - existing directories are left alone (ownership, mode and contents);
     *  - existing files with a standard directory's name are NOT replaced;
     *  - symlinks are not followed and not modified;
     *  - an existing `user-dirs.dirs` keeps every unmanaged line and custom
     *    key; only the eight managed keys are brought to the canonical values;
     *  - repeated calls on a healthy home do no writes at all.
     *
     * Returns a report rather than a failure: provisioning is fail-open, and
     * callers log the blocked entries while still launching the workspace.
     */
    fun provision(homeDir: File): ProvisionReport {
        if (!homeDir.isDirectory || isSymlink(homeDir)) {
            return ProvisionReport(blockedEntries = listOf(homeDir.name.ifBlank { "home" }))
        }

        val created = ArrayList<String>()
        val existing = ArrayList<String>()
        val blocked = ArrayList<String>()

        for (name in STANDARD_DIRECTORIES) {
            ensureDirectory(homeDir, name, created, existing, blocked)
        }

        var configCreated = false
        val configDir = File(homeDir, CONFIG_DIRECTORY)
        if (!configDir.exists()) {
            if (configDir.mkdir()) {
                configCreated = true
                setMode(configDir, DIRECTORY_MODE)
            } else {
                blocked += CONFIG_DIRECTORY
            }
        } else if (!configDir.isDirectory || isSymlink(configDir)) {
            blocked += CONFIG_DIRECTORY
        }

        var userDirsCreated = false
        var userDirsUpdated = false
        var userDirsUnchanged = false
        if (configDir.isDirectory && !isSymlink(configDir)) {
            val target = File(configDir, USER_DIRS_FILE)
            when {
                isSymlink(target) -> blocked += "$CONFIG_DIRECTORY/$USER_DIRS_FILE"
                !target.exists() -> {
                    writeUserDirs(target, renderUserDirs())
                    userDirsCreated = true
                }
                !target.isFile -> blocked += "$CONFIG_DIRECTORY/$USER_DIRS_FILE"
                else -> {
                    val current = runCatching { target.readText() }.getOrNull()
                    if (current == null) {
                        blocked += "$CONFIG_DIRECTORY/$USER_DIRS_FILE"
                    } else {
                        val merged = mergeUserDirs(current)
                        if (merged == current) {
                            userDirsUnchanged = true
                        } else {
                            writeUserDirs(target, merged)
                            userDirsUpdated = true
                        }
                    }
                }
            }
        }

        return ProvisionReport(
            createdDirectories = created,
            existingDirectories = existing,
            blockedEntries = blocked,
            userDirsCreated = userDirsCreated,
            userDirsUpdated = userDirsUpdated,
            userDirsUnchanged = userDirsUnchanged,
            configDirectoryCreated = configCreated
        )
    }

    /** The canonical `user-dirs.dirs` for a fresh home. */
    fun renderUserDirs(): String = buildString {
        append(USER_DIRS_HEADER)
        for ((key, directory) in XDG_DIRECTORIES) {
            append(key).append("=\"\$HOME/").append(directory).append("\"\n")
        }
    }

    /**
     * Merges the canonical eight XDG keys into existing content.
     *
     * Every non-managed line (comments, unknown keys, locale settings) is
     * preserved verbatim and in place. A managed key is replaced only when its
     * first occurrence differs; duplicate managed lines after the first are
     * dropped so the file cannot declare two values for one key. Missing keys
     * are appended in canonical order.
     */
    fun mergeUserDirs(existing: String): String {
        val managed = XDG_DIRECTORIES.toMap()
        val emitted = HashSet<String>()
        // `lineSequence()` yields a trailing empty element for a file that
        // ends with a newline. Re-emitting it would append one blank line per
        // provisioning pass — a real growth bug, and the reason a second pass
        // must be byte-identical rather than merely "equivalent".
        val rawLines = if (existing.isEmpty()) emptyList() else existing.lineSequence().toList()
        val endsWithBreak = existing.endsWith("\n") || existing.endsWith("\r")
        val lines = if (endsWithBreak && rawLines.lastOrNull() == "") rawLines.dropLast(1) else rawLines

        val out = StringBuilder()
        var first = true
        fun appendLine(line: String) {
            if (!first) out.append('\n')
            out.append(line)
            first = false
        }

        lines.forEach { line ->
            val key = line.substringBefore('=').trim()
            if (key in managed) {
                if (emitted.add(key)) {
                    appendLine(renderUserDirsLine(key, managed.getValue(key)).removeSuffix("\n"))
                }
                // A duplicate managed key is dropped: the first occurrence wins
                // and is normalized, so the last line cannot silently override.
            } else {
                appendLine(line)
            }
        }
        for ((key, directory) in XDG_DIRECTORIES) {
            if (emitted.add(key)) appendLine(renderUserDirsLine(key, directory).removeSuffix("\n"))
        }
        if (out.isNotEmpty()) out.append('\n')
        return out.toString()
    }

    private fun renderUserDirsLine(key: String, directory: String): String =
        "$key=\"\$HOME/$directory\"\n"

    // ── helpers ───────────────────────────────────────────────────────────

    private fun ensureDirectory(
        homeDir: File,
        name: String,
        created: MutableList<String>,
        existing: MutableList<String>,
        blocked: MutableList<String>
    ) {
        val target = File(homeDir, name)
        when {
            isSymlink(target) -> blocked += name
            target.isDirectory -> existing += name
            target.exists() -> blocked += name
            target.mkdir() -> {
                created += name
                setMode(target, DIRECTORY_MODE)
            }
            // A concurrent creator (or a race with the guest) is not a failure
            // when the directory is there now.
            target.isDirectory -> existing += name
            else -> blocked += name
        }
    }

    private fun writeUserDirs(target: File, content: String) {
        val tmp = File(target.parentFile, ".${target.name}.linexa-tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("rename failed for ${target.absolutePath}")
            }
        }
        setMode(target, USER_DIRS_MODE)
    }

    private fun setMode(target: File, mode: Set<PosixFilePermission>) {
        runCatching { Files.setPosixFilePermissions(target.toPath(), mode) }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /** 0755 — the normal Linux user-directory mode. */
    val DIRECTORY_MODE: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE
    )

    /** 0644 — readable by the user and other local processes, owner-writable. */
    val USER_DIRS_MODE: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ
    )

    /** Kept for callers that only need the read-only XDG check. */
    fun readsAsCanonicalUserDirs(content: String): Boolean {
        val values = content.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
        return XDG_DIRECTORIES.all { (key, directory) ->
            values[key] == "\"\$HOME/$directory\""
        }
    }

    /** Diagnostic: names under `root` that are not stock root-account entries. */
    fun unexpectedRootHomeEntries(rootfs: File): List<String> {
        val rootHome = File(rootfs, "root")
        if (!rootHome.isDirectory || isSymlink(rootHome)) return emptyList()
        val entries = rootHome.listFiles() ?: return emptyList()
        return entries.map { it.name }.filterNot { it in ROOT_HOME_BASELINE }.sorted()
    }

    /**
     * Stock `/root` content shipped by the supported distributions. Anything
     * else may be user data; it is reported and LEFT IN PLACE. A migration must
     * never move or delete ambiguous root-owned files.
     */
    private val ROOT_HOME_BASELINE = setOf(
        ".bashrc", ".bash_profile", ".bash_logout", ".profile", ".cshrc", ".tcshrc",
        ".zshrc", ".zprofile", ".zshenv", ".inputrc", ".viminfo", ".lesshst",
        ".config", ".cache", ".local", ".ssh", ".gnupg", ".dbus", ".termux",
        ".ICEauthority", ".Xauthority", ".vnc", ".l2s", "README.md"
    )
}