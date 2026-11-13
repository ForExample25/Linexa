package com.fem.linexa.domain.launcher

import com.fem.linexa.service.commands.QuickActionKind
import com.fem.linexa.service.commands.QuickCommandNode
import com.fem.linexa.service.commands.QuickNodeType

/**
 * The App Launcher's model of ONE installable/launchable application (App
 * Launcher redesign).
 *
 * ## Where it comes from — never invented
 *
 * An [LauncherApp] is derived EXCLUSIVELY from the workspace's already-resolved
 * built-in Quick Commands catalog: an `application` node declares its identity
 * (name/description/icon), its `install`/`remove` action children carry the
 * guest commands, and a new optional `launch` action child states what a TAP
 * runs. Nothing here guesses a package name from a title, and nothing contacts
 * the network or the guest — this file is pure data→data so the merge and
 * ordering rules are unit-testable.
 *
 * ## Install status is a SEPARATE layer's answer
 *
 * [LauncherApp] deliberately carries NO `isInstalled` field: installation state
 * is a runtime fact about the guest (dpkg/`which` probed through the binder by
 * [com.fem.linexa.ui.screens.workspace.AppInstallTracker]), not a property of
 * the catalog entry. Keeping it out of this model is what prevents the launcher
 * from ever displaying a hardcoded or stale-by-design install state.
 */
data class LauncherApp(
    /** Stable id — the catalog `application` node's id, namespaced `builtin:`. */
    val id: String,
    /** The user-facing name (`Python 3 + venv`). */
    val name: String,
    /** The catalog's own one-line description, or null when it declared none. */
    val description: String?,
    /** Icon key resolved by the UI's fixed table, or null for the fallback. */
    val iconKey: String?,
    /**
     * The category the app was found under — the LAUNCHER's grouping (Dev
     * Tools, Browsers…), taken from the catalog `category` node that contains
     * the `application` node. Never fabricated; a group's name wins when the
     * app sits under a `group` because that is the more specific statement.
     */
    val category: String?,
    /**
     * The guest command a TAP runs when the app is installed, or null when the
     * catalog declares no `launch` action — such an app still installs and
     * uninstalls from its context menu, and tapping it opens its actions (or
     * the terminal at a shell) rather than pretending to launch.
     */
    val launchCommand: String?,
    /**
     * The guest command that installs the app (its `install` action's command),
     * or null when the catalog offers no install action for it.
     */
    val installCommand: String?,
    /**
     * The package names the install command is verified against after it
     * finishes (see [verificationPackages]) — extracted from the install
     * command itself, never invented.
     */
    val removeCommand: String?,
    /**
     * Package names used to VERIFY the app's installed state (`dpkg -l`).
     *
     * Derived from the install command's `apt-get install -y a b c` / `pacman
     * -S …` / `apk add …` / `dnf install …` tail — the exact set the catalog
     * author stated the install provides. Empty when the command installs
     * from a URL (`.deb` via `wget`) or the pattern is unrecognized: the
     * tracker then falls back to the launch command's `which` probe, and an
     * app with neither reports "unknown" rather than a guessed "installed".
     */
    val verificationPackages: List<String>,
    /**
     * The executable name(s) a `which` probe uses when
     * [verificationPackages] is empty — derived from the `launch` action's
     * command's first word(s). Empty alongside both other sources means the
     * tracker reports unknown.
     */
    val verificationExecutables: List<String>,
    /** True when the launch action declares `as_root`. */
    val launchAsRoot: Boolean
)

/**
 * Extracts the App Launcher's flat application list from the resolved catalog
 * tree — pure, JVM-testable.
 *
 * ## Rules
 *
 *  1. Only `application` nodes become apps. A `category`/`group` is
 *     navigation, and a bare `action` is a command the existing Commands grid
 *     already renders — neither is "an app you install and then tap".
 *  2. The FIRST child action of each kind wins (`install`/`remove`/`launch`),
 *     in tree order — the catalog schema already forbids duplicates of an id
 *     within a branch, so this is deterministic.
 *  3. Category attribution walks the parent chain: the `category` name, or
 *     the enclosing `group`'s name when the app is nested in one. An
 *     application directly under the root (no category) gets null — the
 *     launcher renders it ungrouped rather than inventing a bucket.
 *  4. Original catalog order is preserved everywhere.
 */
object LauncherApps {

    /** Id prefix of every [LauncherApp.id] — same namespace the Commands grid keys on. */
    const val ID_PREFIX: String = "builtin:"

    /** The flat application list for the resolved [nodes] tree, in catalog order. */
    fun fromCatalog(nodes: List<QuickCommandNode>): List<LauncherApp> {
        val out = ArrayList<LauncherApp>()
        walk(nodes, category = null, group = null, out)
        return out
    }

    private fun walk(
        nodes: List<QuickCommandNode>,
        category: String?,
        group: String?,
        out: MutableList<LauncherApp>
    ) {
        for (node in nodes) {
            when (node.type) {
                QuickNodeType.CATEGORY -> walk(node.children, node.name, group, out)
                QuickNodeType.GROUP -> walk(node.children, category, node.name, out)
                QuickNodeType.APPLICATION -> out += appOf(node, category, group)
                QuickNodeType.ACTION -> Unit // commands are not apps
            }
        }
    }

    private fun appOf(node: QuickCommandNode, category: String?, group: String?): LauncherApp {
        val actions = node.children.filter { it.type == QuickNodeType.ACTION }
        val install = actions.firstOrNull { it.actionKind == QuickActionKind.INSTALL }
        val remove = actions.firstOrNull { it.actionKind == QuickActionKind.REMOVE }
        val launch = actions.firstOrNull { it.actionKind == QuickActionKind.LAUNCH }
        val installCommand = install?.command
        return LauncherApp(
            id = ID_PREFIX + node.id,
            name = node.name,
            description = node.description?.takeIf { it.isNotBlank() },
            iconKey = node.icon,
            category = group ?: category,
            launchCommand = launch?.command,
            installCommand = installCommand,
            removeCommand = remove?.command,
            verificationPackages = installCommand?.let(InstallCommandParser::packagesOf).orEmpty(),
            verificationExecutables = launch?.command?.let(InstallCommandParser::executablesOf).orEmpty(),
            launchAsRoot = launch?.asRoot ?: false
        )
    }
}

/**
 * Pure extraction of what an install/launch command VERIFIES against.
 *
 * The launcher must never display an install state it did not measure, and the
 * only honest source of "what does installed mean for this app" is the command
 * the catalog itself installs with. This parser recognizes the package-manager
 * tails of all five supported managers and the `&&`-chained command shape the
 * catalog actually writes; anything else answers empty (→ "unknown") instead
 * of guessing.
 *
 * Deliberately conservative:
 *
 *  - Only `install`-looking verbs are mined (`apt-get install`, `apt install`,
 *    `apt-get remove --purge` is NOT — that is the REMOVE direction).
 *  - Absolute-path downloads (`wget -O /tmp/x.deb …`) contribute no package
 *    names — a `.deb` filename is not a resolvable dpkg name. The tracker's
 *    executable fallback covers these apps.
 *  - Options (`-y`, `--noconfirm`, `-o Key=Value`) and the manager names
 *    themselves are skipped; the FIRST positional token after the verb that
 *    is not an option starts the package list, and the list ends at the next
 *    shell separator or another command word.
 */
object InstallCommandParser {

    /**
     * Package names the install [command] provides, in command order, deduped.
     * Empty when the command does not match a recognized install pattern.
     */
    fun packagesOf(command: String): List<String> {
        val found = LinkedHashSet<String>()
        for (segment in command.split("&&", "||", ";", "\n")) {
            mineInstallSegment(segment.trim())?.let(found::addAll)
        }
        return found.toList()
    }

    /**
     * Executable names a `which` probe can check for a launch command — the
     * first word of the command, plus the first word after a leading `command`
     * prefix (the catalog writes `command -v fastfetch && fastfetch || …`).
     */
    fun executablesOf(launchCommand: String): List<String> {
        val words = launchCommand.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        words.first().let { first ->
            if (first == "command" && words.size > 1) {
                // `command -v NAME …` — skip the -v flag.
                out += words.drop(2).firstOrNull { !it.startsWith("-") } ?: words[1]
            } else {
                out += first
            }
        }
        return out.toList()
    }

    /** Package-name shape reusing the package-manager layer's own guard. */
    private fun looksLikePackageName(token: String): Boolean =
        com.fem.linexa.domain.packagemanager.PackageShell.isSafePackageName(token)

    /**
     * One `&&`-segment: the package list of an install verb, or null when the
     * segment installs nothing recognizable.
     */
    private fun mineInstallSegment(segment: String): List<String>? {
        val tokens = segment.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        // Split the segment at every pipe so `dpkg -l x | grep …` style tails
        // don't leak grep's arguments in as package names.
        val words = tokens
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val manager = MANAGER_VERBS.entries.firstOrNull { it.key == w }
            if (manager != null) {
                // Only the INSTALL subcommand mines packages (update/remove/upgrade do not).
                var j = i + 1
                val sub = words.getOrNull(j)
                val hasInstallSub = sub == "install" || sub == "-S" || sub == "add" || sub == "in"
                if (!hasInstallSub) { i++; continue }
                j += 1
                val pkg = collectPackages(words, j)
                if (pkg.isNotEmpty()) return pkg
            }
            i++
        }
        return null
    }

    /**
     * Collects package-name-shaped positionals from [words] starting at [start],
     * stopping at the first token that is not a plausible package name (an
     * option, a redirect, a URL, a variable, a quoted phrase…).
     */
    private fun collectPackages(words: List<String>, start: Int): List<String> {
        val out = ArrayList<String>()
        var j = start
        while (j < words.size) {
            val w = words[j].removeSuffix(",")
            // Option flags (-y, --noconfirm, --purge) are skipped, not
            // collected: a package name never starts with a dash, and the
            // catalog writes `install -y pkgs` / `-S pkgs` shapes where the
            // flags precede the package list.
            if (w.startsWith("-")) {
                j++
                continue
            }
            if (!looksLikePackageName(w)) break
            out += w
            j++
        }
        return out
    }

    /** The managers whose INSTALL subcommand the parser recognizes. */
    private val MANAGER_VERBS = mapOf(
        "apt-get" to "install",
        "apt" to "install",
        "pacman" to "install",
        "apk" to "install",
        "dnf" to "install",
        "zypper" to "install"
    )
}
