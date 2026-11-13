package com.fem.linexa.domain.packagemanager

/**
 * §1.92 — Pure parsers for dnf command output (JVM-testable).
 *
 * §1.146: reworked against real dnf5 output (Fedora 44, dnf5 5.4.2.1) after
 * the dnf4-era assumptions were found to produce **zero** rows:
 * - `dnf list installed --quiet` (positional `installed`) exits 1 with
 *   "No matching packages to list" on dnf5; the option form
 *   `dnf list --installed` is what works on both dnf4 and dnf5.
 * - `dnf search` separates name and summary with a **TAB** on dnf5, and with
 *   ` : ` on dnf4, so [parseSearch] accepts either.
 * - The structured path ([parseRepoquery]) uses `dnf repoquery --queryformat`,
 *   which is a core command on both dnf4 and dnf5 and is the only way to get
 *   real install/download sizes. Note that dnf5's queryformat expands `\n`
 *   but **not** `\t`, so `|` is the field separator.
 */
internal object DnfOutputParser {

    /** Field separator used by our `--queryformat` strings (see [parseRepoquery]). */
    const val FIELD_SEPARATOR = "|"

    /**
     * `dnf repoquery --queryformat` for the Installed tab:
     * `name|evr|installsize|from_repo|group`. `from_repo` is what the rpmdb
     * recorded at install time — a real repo id (`fedora`, `updates`) for
     * packages the user installed, and an opaque build-time id for packages
     * baked into the image. Both are honest; neither is fabricated.
     *
     * §1.147: `group` is rpm's own category tag (`Development/Tools`,
     * `System Environment/Base`) and is what the card's category label shows.
     */
    const val INSTALLED_QUERYFORMAT = "%{name}|%{evr}|%{installsize}|%{from_repo}|%{group}\\n"

    /**
     * `dnf repoquery --queryformat` for the Store tab:
     * `name|evr|downloadsize|installsize|repoid|url|group|summary`. Summary is
     * last because it is the only field that may itself contain `|`.
     *
     * §1.147: `url` is the upstream project page behind the card's OPEN
     * action; `group` is the category label.
     */
    const val AVAILABLE_QUERYFORMAT =
        "%{name}|%{evr}|%{downloadsize}|%{installsize}|%{repoid}|%{url}|%{group}|%{summary}\\n"

    /** rpm prints `(none)` for a tag the package did not set. */
    private fun String.nullIfRpmNone(): String? =
        trim().takeIf { it.isNotEmpty() && !it.equals("(none)", ignoreCase = true) }


    /**
     * Parse `dnf list --installed` table output:
     * ```
     * Installed packages
     * bash.aarch64                 5.3.9-3.fc44        fedora
     * ```
     * `name.arch version repoid`. dnf4 prefixes the repo with `@`; dnf5 does
     * not — the marker is stripped either way. This is the fallback for when
     * [parseRepoquery] is unavailable, so sizes stay null (the table has none).
     */
    fun parseInstalled(output: String): List<InstalledPkg> =
        output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || isInstalledHeader(line)) return@mapNotNull null
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) return@mapNotNull null
            InstalledPkg(
                name = parts[0].substringBefore('.'),
                version = parts[1],
                installedSizeBytes = null,
                repository = parts[2].removePrefix("@").takeIf { it.isNotEmpty() },
                description = null
            )
        }

    /**
     * Parse `dnf repoquery --installed --queryformat [INSTALLED_QUERYFORMAT]`:
     * ```
     * bash|5.3.9-3.fc44|8822851|fedora|Unspecified
     * ```
     * `installsize` is already in bytes. A repo id of `@System` (dnf's
     * pseudo-repo for the rpmdb) carries no information and is dropped, as is
     * an `Unspecified`/`(none)` group.
     */
    fun parseRepoquery(output: String): List<InstalledPkg> =
        output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val cols = line.split(FIELD_SEPARATOR)
            if (cols.size < 2) return@mapNotNull null
            val name = cols[0].trim()
            if (name.isEmpty()) return@mapNotNull null
            InstalledPkg(
                name = name,
                version = cols[1].trim(),
                installedSizeBytes = cols.getOrNull(2)?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                repository = cols.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() && it != "@System" },
                description = null,
                section = cols.getOrNull(4)?.nullIfRpmNone()?.takeIf { !it.equals("Unspecified", ignoreCase = true) }
            )
        }

    /**
     * Parse `dnf repoquery --available --queryformat [AVAILABLE_QUERYFORMAT]`:
     * ```
     * bash|5.3.9-3.fc44|1930272|8822851|fedora|https://www.gnu.org/software/bash/|Unspecified|The GNU Bourne Again shell
     * ```
     * Sizes are bytes. The summary is the remainder of the line so a `|`
     * inside it cannot shift the columns.
     */
    fun parseAvailable(output: String): List<AvailablePkg> =
        output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val cols = line.split(FIELD_SEPARATOR, limit = 8)
            if (cols.size < 2) return@mapNotNull null
            val name = cols[0].trim()
            if (name.isEmpty()) return@mapNotNull null
            AvailablePkg(
                name = name,
                latestVersion = cols[1].trim(),
                downloadSizeBytes = cols.getOrNull(2)?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                installedSizeBytes = cols.getOrNull(3)?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                repository = cols.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() },
                description = cols.getOrNull(7)?.trim()?.takeIf { it.isNotEmpty() },
                section = cols.getOrNull(6)?.nullIfRpmNone()?.takeIf { !it.equals("Unspecified", ignoreCase = true) },
                homepageUrl = cols.getOrNull(5)?.nullIfRpmNone()
            )
        }

    /**
     * Parse `dnf check-update` / `dnf repoquery --upgrades` output into
     * name → candidate version. dnf5's check-update table is
     * `name.arch  evr  repoid`, and `--upgrades --queryformat '%{name}|%{evr}'`
     * gives the same pairs pipe-separated. Both shapes are accepted so the
     * caller can use whichever command the guest's dnf supports:
     * ```
     * bash|5.3.9-3.fc44
     * bash.aarch64    5.3.9-3.fc44    updates
     * ```
     * Only packages listed here get an UP action.
     */
    fun parseUpgrades(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("Last metadata expiration check")) return@forEach
            if (line.contains(FIELD_SEPARATOR)) {
                val cols = line.split(FIELD_SEPARATOR)
                val name = stripArch(cols[0].trim())
                val version = cols.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                if (name.isNotEmpty()) result[name] = version
                return@forEach
            }
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val name = stripArch(parts[0])
            val version = parts[1].takeIf { it.isNotEmpty() && it.first().isDigit() } ?: return@forEach
            if (name.isNotEmpty()) result[name] = version
        }
        return result
    }

    /**
     * Parse `dnf repoquery --queryformat '%{name}' --whatprovides` style output
     * — a bare list of names, one per line — into a set. Used for dnf's
     * protected-package list (the `.conf` files in `/etc/dnf/protected.d`,
     * read via `cat`),
     * which is dnf's own declaration of what must never be removed.
     */
    fun parseNameList(output: String): Set<String> =
        output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { stripArch(it) }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Parse `dnf search <query>` output, accepting both dialects:
     * ```
     * dnf5:  Matched fields: name, summary
     *         bash.aarch64<TAB>The GNU Bourne Again shell
     * dnf4:  =============== Name Matched: git ===============
     *        git.x86_64 : Fast Version Control System
     * ```
     * Section headers, `=====` rules and dnf4's metadata-expiration notice
     * carry no separator (or are filtered explicitly) and are skipped.
     */
    fun parseSearch(output: String): List<Pair<String, String>> =
        output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("=") || isSearchHeader(line)) return@mapNotNull null
            val (rawName, summary) = splitSearchRow(line) ?: return@mapNotNull null
            // `name.arch` → name. Arch is the last dot-segment, and package
            // names legitimately contain dots, so only a known arch suffix is
            // stripped rather than everything after the first dot.
            val name = stripArch(rawName)
            if (name.isEmpty() || summary.isEmpty()) null else name to summary
        }

    /** dnf5 uses a tab; dnf4 uses ` : `. Tab wins because it cannot appear in a name. */
    private fun splitSearchRow(line: String): Pair<String, String>? {
        val tab = line.indexOf('\t')
        if (tab > 0) return line.substring(0, tab).trim() to line.substring(tab + 1).trim()
        val colon = line.indexOf(" : ")
        if (colon > 0) return line.substring(0, colon).trim() to line.substring(colon + 3).trim()
        return null
    }

    /** rpm arch suffixes dnf appends to the printed package name. */
    private val ARCH_SUFFIXES = listOf(
        "noarch", "x86_64", "i686", "i386", "aarch64", "armv7hl", "armv6hl",
        "ppc64le", "s390x", "riscv64", "src"
    )

    /** Strip a trailing `.<arch>` from a printed `name.arch` token. */
    fun stripArch(token: String): String {
        val dot = token.lastIndexOf('.')
        if (dot <= 0) return token
        val suffix = token.substring(dot + 1)
        return if (suffix in ARCH_SUFFIXES) token.substring(0, dot) else token
    }

    /** dnf4 prints "Installed Packages"; dnf5 prints "Installed packages". */
    private fun isInstalledHeader(line: String): Boolean =
        line.equals("Installed Packages", ignoreCase = true) ||
            line.equals("Available Packages", ignoreCase = true)

    /** dnf5 groups results under "Matched fields: …" headers. */
    private fun isSearchHeader(line: String): Boolean =
        line.startsWith("Matched fields:") || line.startsWith("Last metadata expiration check")

    /**
     * Extract a version from `dnf --version`, which differs by generation:
     * ```
     * dnf5: dnf5 version 5.4.2.1
     * dnf4: 4.14.0
     * ```
     * The first dot-containing numeric token is the version; the `dnf5`
     * program-name token must not be mistaken for one.
     */
    fun parseVersion(output: String): String? =
        output.lines().asSequence()
            .flatMap { it.trim().split(' ').asSequence() }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.contains('.') && it.first().isDigit() }
}
