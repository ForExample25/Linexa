package com.fem.linexa.domain.packagemanager

/**
 * §1.92 — Pure parsers for zypper command output (JVM-testable).
 *
 * §1.146 — corrected against real zypper output (openSUSE Leap 16.0 arm64,
 * zypper 1.14.98). Two column-layout assumptions were wrong, and each one
 * silently emptied a tab:
 *
 * - `zypper packages --installed-only` has **5** columns
 *   (`S | Repository | Name | Version | Arch`), not 6, and `Name` is column
 *   **2**, not 1. The old parser required `cols.size >= 6` and read
 *   `cols[1]`, so it discarded every row (Installed tab always empty) — and
 *   would have reported the repository as the package name if it hadn't.
 * - `zypper search` (without `-s`) has 4 columns
 *   (`S | Name | Summary | Type`) and carries no version; `zypper search -s`
 *   has 6 (`S | Name | Type | Version | Arch | Repository`) and carries no
 *   summary. [parseSearch] handles both layouts by header, so the Store tab
 *   works either way.
 * - Sizes are not in any table; `zypper info` is the only source
 *   (`Installed Size : 1.2 MiB`) — see [parseInfo].
 */
internal object ZypperOutputParser {

    /** Split a zypper pipe-delimited table row on `|`. */
    private fun splitRow(line: String): List<String> = line.split('|').map { it.trim() }

    /** True for the `---+---+---` rule and the `S | Name | …` header row. */
    private fun isTableFurniture(cols: List<String>): Boolean =
        cols.firstOrNull()?.let { it.isEmpty() || it == "S" || it.all { c -> c == '-' } } ?: true

    /**
     * Parse `zypper packages --installed-only` output. Verified columns:
     * ```
     * S  | Repository | Name     | Version                   | Arch
     * ---+------------+----------+---------------------------+--------
     * i+ | @System    | bash     | 5.2.37-160000.2.2         | aarch64
     * ```
     * Installed rows have a status starting with `i` (`i`/`i+`). `@System`
     * is zypper's pseudo-repository for the rpmdb and carries no information,
     * so it is dropped rather than shown as a repository name.
     */
    fun parseInstalled(output: String): List<InstalledPkg> =
        output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val cols = splitRow(line)
            if (cols.size < 4 || isTableFurniture(cols)) return@mapNotNull null
            if (!cols[0].startsWith("i")) return@mapNotNull null
            val name = cols[2]
            if (name.isEmpty()) return@mapNotNull null
            InstalledPkg(
                name = name,
                version = cols[3],
                installedSizeBytes = null,
                repository = cols[1].takeIf { it.isNotEmpty() && it != "@System" },
                description = null
            )
        }

    /**
     * Parse `zypper search` output in either of its two real layouts,
     * distinguished by the header row:
     * ```
     * S  | Name    | Summary                    | Type      (plain `se`)
     * S  | Name    | Type    | Version | Arch | Repository  (`se -s`)
     * ```
     * The plain form has no version, the `-s` form has no summary; whichever
     * fields are present are filled and the rest stay null. `(System
     * Packages)` is zypper's label for the rpmdb, not a repository, so it is
     * dropped.
     */
    fun parseSearch(output: String): List<AvailablePkg> {
        var detailed: Boolean? = null
        val result = ArrayList<AvailablePkg>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val cols = splitRow(line)
            if (cols.size < 4) return@forEach
            // The header row identifies the layout: `-s` puts Type in col 2.
            if (cols[0] == "S") {
                detailed = cols.getOrNull(2)?.equals("Type", ignoreCase = true) == true
                return@forEach
            }
            if (isTableFurniture(cols)) return@forEach
            val name = cols[1]
            if (name.isEmpty()) return@forEach
            val isDetailed = detailed ?: (cols.size >= 6)
            result.add(
                AvailablePkg(
                    name = name,
                    latestVersion = if (isDetailed) cols.getOrNull(3).orEmpty() else "",
                    downloadSizeBytes = null,
                    installedSizeBytes = null,
                    repository = if (isDetailed) {
                        cols.getOrNull(5)?.takeIf { it.isNotEmpty() && it != "(System Packages)" }
                    } else {
                        null
                    },
                    description = if (isDetailed) null else cols.getOrNull(2)?.takeIf { it.isNotEmpty() }
                )
            )
        }
        return result
    }

    /**
     * Parse `zypper info <names>` blocks into name → details. Verified shape:
     * ```
     * Information for package bash:
     * -----------------------------
     * Repository     : @System
     * Name           : bash
     * Version        : 5.2.37-160000.2.2
     * Installed Size : 1.2 MiB
     * Summary        : The GNU Bourne-Again Shell
     * ```
     * `Repository` precedes `Name`, so it is buffered until the name is
     * known. `Installed Size` is a formatted value (`1.2 MiB`), converted by
     * [parseSize]. The multi-line `Description` body is deliberately not
     * collected — `Summary` is the one-line description the UI shows.
     */
    fun parseInfo(output: String): Map<String, InfoDetails> {
        val result = LinkedHashMap<String, InfoDetails>()
        var pendingRepository: String? = null
        var current: InfoDetails? = null
        output.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("-") || line.startsWith("Information for ")) return@forEach
            // Fields are `Key : value` at column 0; description bodies are indented.
            if (line.first().isWhitespace()) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "Repository" -> pendingRepository = value.takeIf { it.isNotEmpty() && it != "@System" }
                "Name" -> {
                    if (value.isNotEmpty()) {
                        current = InfoDetails(repository = pendingRepository)
                        result[value] = current!!
                        pendingRepository = null
                    }
                }
                "Version" -> current?.version = value.takeIf { it.isNotEmpty() }
                "Installed Size" -> current?.installedSizeBytes = parseSize(value)
                "Summary" -> current?.description = value.takeIf { it.isNotEmpty() }
                // §1.147: the upstream project page behind the Store card's
                // OPEN action. libzypp labels this field `Upstream URL` on
                // current zypper (verified in the 1.14.98 capture the tests
                // use) and plain `URL` on older builds, so both are accepted —
                // matching only one leaves the action permanently unrendered.
                // zypper prints a literal `(none)` when the rpm set no URL tag.
                "URL", "Upstream URL" -> current?.homepageUrl =
                    value.takeIf { it.isNotEmpty() && !it.equals("(none)", ignoreCase = true) }
            }
        }
        return result
    }

    /**
     * Parse `zypper --quiet list-updates` — zypper's own list of installed
     * packages with a newer version available:
     * ```
     * S | Repository | Name | Current Version | Available Version | Arch
     * --+------------+------+-----------------+-------------------+--------
     * v | repo-oss   | bash | 5.2.37-1        | 5.2.37-2          | aarch64
     * ```
     * The available version is column 4. Only packages listed here get an UP
     * action; the table's header and rule rows are skipped as furniture.
     */
    fun parseUpdates(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val cols = splitRow(line)
            if (cols.size < 5 || isTableFurniture(cols)) return@forEach
            val name = cols[2]
            val available = cols[4]
            if (name.isEmpty() || available.isEmpty()) return@forEach
            result[name] = available
        }
        return result
    }

    /**
     * Parse `zypper --quiet locks` into the set of locked package names —
     * zypper's own declaration of packages that must not be changed:
     * ```
     * # | Name | Type    | Repository
     * --+------+---------+-----------
     * 1 | zypper | package | (any)
     * ```
     * The name is column 1. An empty output (no locks configured) yields an
     * empty set, which is the honest answer.
     */
    fun parseLocks(output: String): Set<String> {
        val result = LinkedHashSet<String>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val cols = splitRow(line)
            if (cols.size < 2) return@forEach
            // The locks table's first column is a number, not a status letter,
            // so the shared furniture check is not reused here.
            if (cols[0].isEmpty() || cols[0] == "#" || cols[0].all { it == '-' }) return@forEach
            cols[1].takeIf { it.isNotEmpty() }?.let { result.add(it) }
        }
        return result
    }

    /**
     * Convert a zypper-formatted size (`1.2 MiB`, `259.1 KiB`, `0 B`) to
     * bytes. zypper uses 1024-based units. Returns null for anything else
     * rather than guessing.
     */
    fun parseSize(raw: String): Long? {
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(B|KiB|MiB|GiB|TiB)$").find(raw.trim()) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (m.groupValues[2]) {
            "B" -> 1L
            "KiB" -> 1024L
            "MiB" -> 1024L * 1024
            "GiB" -> 1024L * 1024 * 1024
            "TiB" -> 1024L * 1024 * 1024 * 1024
            else -> return null
        }
        return (value * multiplier).toLong().takeIf { it > 0 }
    }

    /**
     * Parse `rpm -qa --queryformat '%{NAME}|%{EVR}|%{SIZE}|%{GROUP}\n'` — the
     * offline fallback for the Installed tab. openSUSE is an rpm distro, so
     * this works even when every repository is unreachable (verified: zypper
     * needs repo metadata for its own table and errors out without it).
     * `gpg-pubkey` pseudo-packages are excluded — they are keys, not
     * software. §1.147: `%{GROUP}` is rpm's category tag and becomes the
     * card's category label; rpm prints `(none)` when unset.
     */
    fun parseRpmQuery(output: String): List<InstalledPkg> =
        output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val cols = line.split('|')
            if (cols.size < 2) return@mapNotNull null
            val name = cols[0].trim()
            if (name.isEmpty() || name == "gpg-pubkey") return@mapNotNull null
            InstalledPkg(
                name = name,
                version = cols[1].trim(),
                installedSizeBytes = cols.getOrNull(2)?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                repository = null,
                description = null,
                section = cols.getOrNull(3)?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("(none)", ignoreCase = true) }
            )
        }

    /** Extract the version from `zypper --version` (`zypper 1.14.98`). */
    fun parseVersion(output: String): String? =
        Regex("zypper\\s+(\\S+)").find(output)?.groupValues?.get(1)

    data class InfoDetails(
        val repository: String? = null,
        var version: String? = null,
        var installedSizeBytes: Long? = null,
        var description: String? = null,
        var homepageUrl: String? = null
    )
}
