package com.fem.linexa.domain.packagemanager

/**
 * §1.92 — Pure parsers for pacman command output (JVM-testable).
 *
 * §1.146 — corrected against real pacman output (Arch Linux ARM,
 * pacman v7.1.0 / libalpm v16.0.1):
 *
 * - **`pacman -Qi` has no `Repository` field.** The old [parseInstalledInfo]
 *   read one, so every installed package's repository was silently null.
 *   The repository of an installed package comes from `pacman -Sl`
 *   (`<repo> <name> <version> [installed]`) — see [parseRepoMap].
 * - `pacman -Ss` entries may carry a group suffix
 *   (`extra/gdc 8.2.1+2.068.2-2.1 (dlang)`) and/or an `[installed]` marker;
 *   both are separate whitespace tokens after the version and must not end
 *   up in it.
 * - `pacman -Si` (available packages) does expose `Repository`,
 *   `Download Size` and `Installed Size`, so the Store tab can show real
 *   sizes — see [parseAvailableInfo].
 */
internal object PacmanOutputParser {

    /**
     * Parse `pacman -Ss <query>` output:
     * ```
     * extra/git 2.45.0-1
     *     the fast distributed version control system
     * core/nano 9.2-1 [installed]
     *     Pico editor clone with enhancements
     * extra/gdc 8.2.1+2.068.2-2.1 (dlang)
     *     Compiler for D programming language
     * ```
     * Package line is `repo/name version [(group…)] [[installed]]`; the
     * description is the next non-empty, indented line. Sizes are not in
     * `-Ss` output, so they stay null (see [parseAvailableInfo] for the
     * enrichment that does carry them).
     */
    fun parseSearch(output: String): List<AvailablePkg> =
        parseSearchInternal(output, offset = 0, limit = Int.MAX_VALUE).packages

    /**
     * F-038 / R-27: page while parsing. `pacman -Ss` output is a block per
     * package, so the walk still visits every block to count them, but only
     * the requested rows are retained.
     */
    fun parseSearchPage(output: String, offset: Int, limit: Int): SearchPage =
        parseSearchInternal(output, offset.coerceAtLeast(0), limit.coerceAtLeast(0))

    private fun parseSearchInternal(output: String, offset: Int, limit: Int): SearchPage {
        val page = ArrayList<AvailablePkg>(limit.coerceAtMost(64))
        var total = 0
        val lines = output.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i++
            // A package line starts at column 0; descriptions are indented.
            if (line.isBlank() || line.first().isWhitespace()) continue
            val trimmed = line.trim()
            val slash = trimmed.indexOf('/')
            if (slash <= 0) continue
            val repo = trimmed.substring(0, slash)
            val rest = trimmed.substring(slash + 1).trim()
            val parts = rest.split(' ').filter { it.isNotEmpty() }
            val name = parts.firstOrNull() ?: continue
            // Token 1 is the version; anything after it is a group list
            // `(a b c)` or the `[installed]` marker — never part of the version.
            val version = parts.getOrNull(1)?.takeIf { !it.startsWith("(") && !it.startsWith("[") }.orEmpty()
            var description = ""
            if (i < lines.size) {
                val next = lines[i]
                if (next.isNotBlank() && next.first().isWhitespace()) {
                    description = next.trim()
                    i++
                }
            }
            if (total >= offset && page.size < limit) {
                page += AvailablePkg(
                    name = name,
                    latestVersion = version,
                    downloadSizeBytes = null,
                    installedSizeBytes = null,
                    repository = repo,
                    description = description.takeIf { it.isNotEmpty() }
                )
            }
            total++
        }
        return SearchPage(total, page)
    }

    /** One page of search results plus the full count (F-038). */
    data class SearchPage(val total: Int, val packages: List<AvailablePkg>)

    /**
     * Parse `pacman -Qi` blocks into name → (size, description).
     * Fields appear as `Field : value` at column 0; `Name` starts a new
     * package block. Verified field set on pacman 7.1.0:
     * ```
     * Name            : acl
     * Version         : 2.4.0-1
     * Description     : Access control list utilities, libraries and headers
     * Installed Size  : 485.39 KiB
     * Install Date    : Wed Aug  5 12:37:25 2026
     * ```
     * There is deliberately no `Repository` handling here — `-Qi` does not
     * report one (that is [parseRepoMap]'s job).
     */
    fun parseInstalledInfo(output: String): Map<String, PkgInfo> {
        val result = LinkedHashMap<String, PkgInfo>()
        var current: PkgInfo? = null
        output.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key == "Name") {
                if (value.isNotEmpty()) {
                    current = PkgInfo()
                    result[value] = current!!
                }
            } else if (current != null) {
                when (key) {
                    "Installed Size" -> current!!.installedSizeBytes = parseSize(value)
                    "Description" -> current!!.description = value.takeIf { it.isNotEmpty() }
                    // §1.147: pacman's `Groups` is the closest thing it has to
                    // a category (`base`, `base-devel`); `None` means the
                    // package belongs to no group and must not be shown.
                    "Groups" -> current!!.groups = value
                        .takeIf { it.isNotEmpty() && !it.equals("None", ignoreCase = true) }
                        ?.split(' ')
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                }
            }
        }
        return result
    }

    /**
     * Parse `pacman -Qu` (installed packages with a newer version in the
     * synced databases) into name → new version:
     * ```
     * bash 5.2.032-1 -> 5.3.009-1
     * pacman 7.0.0-8 -> 7.1.0-1
     * ```
     * The arrow form is pacman's own upgrade report, so only packages listed
     * here get an UP action. `-Qu` exits 1 when nothing is upgradable, which
     * the caller treats as an empty map, not a failure.
     */
    fun parseUpgradable(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val arrow = line.indexOf("->")
            if (arrow <= 0) return@forEach
            val name = line.substring(0, arrow).trim().split(' ').firstOrNull()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            val version = line.substring(arrow + 2).trim().split(' ').firstOrNull()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            result[name] = version
        }
        return result
    }

    /**
     * Parse `pacman -Sl` output into name → repository for the packages that
     * are installed:
     * ```
     * core acl 2.4.0-1 [installed]
     * core amd-ucode 20260810-2
     * ```
     * Only `[installed]`-marked rows are returned, so the map is exactly the
     * repository attribution for the Installed tab. (`pacman -Sl` also emits
     * `[installed: <other-version>]` when the installed version differs from
     * the repo's, which the `[installed` prefix check covers.)
     */
    fun parseRepoMap(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || !line.contains("[installed")) return@forEach
            val parts = line.split(' ').filter { it.isNotEmpty() }
            if (parts.size < 3) return@forEach
            val repo = parts[0]
            val name = parts[1]
            if (repo.isNotEmpty() && name.isNotEmpty()) result[name] = repo
        }
        return result
    }

    /**
     * Parse `pacman -Si <names>` blocks — the available-package counterpart
     * of [parseInstalledInfo]. Verified fields on pacman 7.1.0:
     * ```
     * Repository      : core
     * Name            : nano
     * Version         : 9.2-1
     * Description     : Pico editor clone with enhancements
     * Download Size   : 605.91 KiB
     * Installed Size  : 2948.54 KiB
     * ```
     * `Repository` precedes `Name` in `-Si` output, so it is buffered until
     * the block's name is known.
     */
    fun parseAvailableInfo(output: String): Map<String, AvailableInfo> {
        val result = LinkedHashMap<String, AvailableInfo>()
        var pendingRepository: String? = null
        var current: AvailableInfo? = null
        output.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "Repository" -> pendingRepository = value.takeIf { it.isNotEmpty() }
                "Name" -> {
                    if (value.isNotEmpty()) {
                        current = AvailableInfo(repository = pendingRepository)
                        result[value] = current!!
                        pendingRepository = null
                    }
                }
                "Version" -> current?.version = value.takeIf { it.isNotEmpty() }
                "Description" -> current?.description = value.takeIf { it.isNotEmpty() }
                "Download Size" -> current?.downloadSizeBytes = parseSize(value)
                "Installed Size" -> current?.installedSizeBytes = parseSize(value)
                // §1.147: `URL` is the upstream project page behind the Store
                // card's OPEN action; `Groups` is the category label.
                "URL" -> current?.homepageUrl = value.takeIf { it.isNotEmpty() }
                "Groups" -> current?.groups = value
                    .takeIf { it.isNotEmpty() && !it.equals("None", ignoreCase = true) }
                    ?.split(' ')
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            }
        }
        return result
    }

    /** Parse a pacman size string like `1.05 MiB`, `512.00 KiB`, or `42 B`. */
    fun parseSize(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val m = Regex("([0-9.]+)\\s*(B|KiB|MiB|GiB|TiB)").find(t) ?: return t.toLongOrNull()
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "B" -> value.toLong()
            "KiB" -> (value * 1024).toLong()
            "MiB" -> (value * 1024 * 1024).toLong()
            "GiB" -> (value * 1024 * 1024 * 1024).toLong()
            "TiB" -> (value * 1024 * 1024 * 1024 * 1024).toLong()
            else -> null
        }
    }

    /**
     * Extract the pacman version from `pacman --version`, whose first
     * non-blank line is ASCII art plus
     * `Pacman v7.1.0 - libalpm v16.0.1`.
     */
    fun parseVersion(output: String): String? =
        Regex("Pacman\\s+v?(\\S+)").find(output)?.groupValues?.get(1)

    data class PkgInfo(
        var installedSizeBytes: Long? = null,
        var description: String? = null,
        var groups: List<String> = emptyList()
    )

    data class AvailableInfo(
        val repository: String? = null,
        var version: String? = null,
        var description: String? = null,
        var downloadSizeBytes: Long? = null,
        var installedSizeBytes: Long? = null,
        var homepageUrl: String? = null,
        var groups: List<String> = emptyList()
    )
}
