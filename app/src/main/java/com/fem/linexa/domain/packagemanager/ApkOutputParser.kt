package com.fem.linexa.domain.packagemanager

/**
 * §1.92 — Pure parsers for apk command output (JVM-testable).
 *
 * §1.146 — corrected and extended against real apk output captured from two
 * generations (Alpine 3.18 / apk-tools 2.14.4 and Alpine 3.24 / apk-tools
 * 3.0.6):
 *
 * - **The name/version split was wrong.** The previous implementation split
 *   on the FIRST dash, with a comment asserting "Alpine package names never
 *   contain `-`". Alpine names contain dashes constantly
 *   (`alpine-baselayout`, `bash-completion`, `acf-alpine-baselayout`), so
 *   `alpine-baselayout-3.4.3-r1` parsed as name `alpine`, version
 *   `baselayout-3.4.3-r1`. The real rule is that apk always renders a package
 *   as `<name>-<pkgver>-r<pkgrel>`, so the version is the LAST two
 *   dash-separated segments — see [splitNameVersion].
 * - `apk info -e -s -v` does not print sizes: `-e` switches the applet into
 *   existence-check mode and only names/versions come back. Size comes from
 *   `apk info -s -v <names>` (`name: 129 KiB` — a *formatted* string, not
 *   bytes) or exactly from `apk query` on apk3.
 * - apk-tools 3 adds `apk query --format json|yaml --fields …`, which is
 *   exact (bytes) and unambiguous; apk-tools 2 rejects it
 *   (`unrecognized option: fields`), hence both paths exist.
 */
internal object ApkOutputParser {

    /**
     * apk renders every package as `<name>-<pkgver>-r<pkgrel>`. Names may
     * contain dashes; `pkgver` may not, and `pkgrel` is always `r<digits>`.
     * That makes the last two dash-separated segments the version and
     * everything before them the name.
     */
    private val NAME_VERSION = Regex("^(.+)-([^-\\s]+-r\\d+)$")

    /**
     * Split an apk `name-version` token, e.g.
     * `alpine-baselayout-3.4.3-r1` → `alpine-baselayout` / `3.4.3-r1`.
     * Returns null when the token does not carry a trailing `-r<n>` release,
     * because guessing a split point would invent data.
     */
    fun splitNameVersion(token: String): Pair<String, String>? {
        val m = NAME_VERSION.find(token.trim()) ?: return null
        val name = m.groupValues[1]
        val version = m.groupValues[2]
        return if (name.isEmpty()) null else name to version
    }

    /**
     * Parse `apk info -e -v <names>` / `apk info -v` output — one
     * `name-version` per line, no sizes:
     * ```
     * alpine-baselayout-3.7.2-r1
     * bash-5.3.9-r1
     * ```
     * Sizes and descriptions are enriched separately (see [parseSizes] /
     * [parseDescriptions]) because apk needs a different invocation for each.
     */
    fun parseInstalled(output: String): List<InstalledPkg> =
        output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("WARNING:") || line.startsWith("ERROR:")) return@mapNotNull null
            val (name, version) = splitNameVersion(line) ?: return@mapNotNull null
            InstalledPkg(
                name = name,
                version = version,
                installedSizeBytes = null,
                repository = null,
                description = null
            )
        }

    /**
     * Parse `apk info -s -v <names>` output — `name: <formatted size>`:
     * ```
     * alpine-baselayout: 6552 B
     * busybox: 906 KiB
     * ```
     * Note apk prints a *formatted* size here (B/KiB/MiB/GiB, powers of
     * 1024), not bytes; [parseFormattedSize] converts it. On apk-tools 3
     * prefer [parseQueryYaml], which reports exact bytes.
     */
    fun parseSizes(output: String): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("WARNING:") || line.startsWith("ERROR:")) return@forEach
            val colon = line.indexOf(": ")
            if (colon <= 0) return@forEach
            val name = line.substring(0, colon).trim()
            if (name.isEmpty()) return@forEach
            parseFormattedSize(line.substring(colon + 2))?.let { result[name] = it }
        }
        return result
    }

    /**
     * Convert an apk-formatted size (`6552 B`, `906 KiB`, `1.2 MiB`) to
     * bytes. apk uses 1024-based units. Returns null for anything else
     * rather than guessing.
     */
    fun parseFormattedSize(raw: String): Long? {
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
        return (value * multiplier).toLong()
    }

    /**
     * Parse `apk search -v <query>` / `apk search -v -d <query>` output —
     * `name-version - description`:
     * ```
     * bash-5.3.9-r1 - The GNU Bourne Again shell
     * acf-alpine-baselayout-0.13.3-r2 - Web-based system administration interface
     * ```
     * The description is optional (plain `apk search -v` on some versions
     * omits it). apk exposes no size in search output, so sizes stay null.
     */
    fun parseSearch(output: String): List<AvailablePkg> =
        output.lines().mapNotNull { parseSearchLine(it) }

    /**
     * F-038 / R-27: parse only the requested page while still counting the
     * whole result set, so a broad query does not materialize every row.
     */
    fun parseSearchPage(output: String, offset: Int, limit: Int): SearchPage {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(0)
        var total = 0
        val page = ArrayList<AvailablePkg>(safeLimit.coerceAtMost(64))
        output.lineSequence().forEach { raw ->
            val pkg = parseSearchLine(raw) ?: return@forEach
            if (total >= safeOffset && page.size < safeLimit) page += pkg
            total++
        }
        return SearchPage(total, page)
    }

    private fun parseSearchLine(raw: String): AvailablePkg? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("WARNING:") || line.startsWith("ERROR:")) return null
        val sep = line.indexOf(" - ")
        val token = if (sep > 0) line.substring(0, sep).trim() else line
        val description = if (sep > 0) line.substring(sep + 3).trim().takeIf { it.isNotEmpty() } else null
        val (name, version) = splitNameVersion(token) ?: return null
        return AvailablePkg(
            name = name,
            latestVersion = version,
            downloadSizeBytes = null,
            installedSizeBytes = null,
            repository = null,
            description = description
        )
    }

    /** One page of search results plus the full count (F-038). */
    data class SearchPage(val total: Int, val packages: List<AvailablePkg>)

    /**
     * Parse `apk info -d -v <names>` output — `name: description`:
     * ```
     * musl: the musl c library (libc) implementation
     * zlib: A compression/decompression Library
     * ```
     * `-v` is what makes apk emit the compact one-line-per-package form
     * instead of multi-line `name-version description:` blocks.
     */
    fun parseDescriptions(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("WARNING:") || line.startsWith("ERROR:")) return@forEach
            val colon = line.indexOf(": ")
            if (colon <= 0) return@forEach
            val name = line.substring(0, colon).trim()
            val description = line.substring(colon + 2).trim()
            if (name.isEmpty() || description.isEmpty()) return@forEach
            // A formatted size is not a description (`apk info -s -v` shape).
            if (parseFormattedSize(description) != null) return@forEach
            result[name] = description
        }
        return result
    }

    /** Fields requested from `apk query` (apk-tools 3+); order matches the output. */
    const val QUERY_FIELDS = "name,version,description,installed-size"

    /**
     * Parse `apk info -w <names>` output into name → upstream URL:
     * ```
     * bash-5.3.9-r1 webpage:
     * https://www.gnu.org/software/bash/bash.html
     *
     * musl-1.2.6-r2 webpage:
     * https://musl.libc.org/
     * ```
     * apk emits a `<name>-<version> webpage:` header followed by the URL on
     * its own line. Only absolute http(s) URLs are kept — anything else would
     * be offered to the browser as a broken link.
     */
    fun parseWebpages(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var pending: String? = null
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("WARNING:") || line.startsWith("ERROR:")) return@forEach
            if (line.endsWith("webpage:")) {
                val token = line.removeSuffix("webpage:").trim()
                pending = splitNameVersion(token)?.first ?: token.takeIf { it.isNotEmpty() }
                return@forEach
            }
            val name = pending ?: return@forEach
            if (line.startsWith("http://") || line.startsWith("https://")) {
                result[name] = line
            }
            pending = null
        }
        return result
    }

    /**
     * Parse `apk query --format yaml --fields [QUERY_FIELDS]` output
     * (apk-tools 3+):
     * ```
     * # 2 items
     * - name: musl
     *   version: 1.2.6-r2
     *   description: the musl c library (libc) implementation
     *   installed-size: 723480
     * ```
     * `installed-size` is exact bytes. Only the four keys we request are
     * recognised; anything else is ignored, and the `# n items` comment is
     * skipped. A minimal reader is used rather than a YAML dependency
     * because the shape is a flat list of scalar maps.
     */
    fun parseQueryYaml(output: String): List<InstalledPkg> {
        val result = ArrayList<InstalledPkg>()
        var name: String? = null
        var version = ""
        var description: String? = null
        var size: Long? = null

        fun flush() {
            val n = name ?: return
            result.add(
                InstalledPkg(
                    name = n,
                    version = version,
                    installedSizeBytes = size,
                    repository = null,
                    description = description
                )
            )
            name = null
            version = ""
            description = null
            size = null
        }

        output.lines().forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val isNewItem = trimmed.startsWith("- ")
            val body = if (isNewItem) trimmed.removePrefix("- ") else trimmed
            val colon = body.indexOf(": ")
            if (colon <= 0) return@forEach
            val key = body.substring(0, colon).trim()
            val value = body.substring(colon + 2).trim()
            if (isNewItem) flush()
            when (key) {
                "name" -> name = value.takeIf { it.isNotEmpty() }
                "version" -> version = value
                "description" -> description = value.takeIf { it.isNotEmpty() }
                "installed-size" -> size = value.toLongOrNull()?.takeIf { it > 0 }
            }
        }
        flush()
        return result
    }

    /**
     * Extract the apk version from `apk --version`
     * (`apk-tools 3.0.6-r0, compiled for aarch64.`).
     */
    fun parseVersion(output: String): String? =
        Regex("apk-tools\\s+(\\S+?)(?:-r\\d+)?[,\\s]").find(output)?.groupValues?.get(1)
            ?: Regex("apk-tools\\s+([0-9][^\\s,]*)").find(output)?.groupValues?.get(1)
            ?: output.lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Parse `apk version -l '<'` — apk's own list of installed packages whose
     * repository version is newer:
     * ```
     * Installed:                Available:
     * busybox-1.36.1-r5       < busybox-1.36.1-r6
     * musl-1.2.4-r2           < musl-1.2.5-r0
     * ```
     * Both sides are `name-version` tokens; the right-hand version is the
     * candidate. The `Installed:`/`Available:` header carries no `<` separator
     * of its own and is skipped. Only packages listed here get an UP action.
     */
    fun parseUpgradable(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("WARNING:") || line.startsWith("ERROR:")) return@forEach
            if (line.startsWith("Installed:")) return@forEach
            val sep = line.indexOf('<')
            if (sep <= 0) return@forEach
            val installedToken = line.substring(0, sep).trim()
            val availableToken = line.substring(sep + 1).trim()
            val name = splitNameVersion(installedToken)?.first ?: return@forEach
            val version = splitNameVersion(availableToken)?.second ?: return@forEach
            result[name] = version
        }
        return result
    }

    /**
     * True when [output] shows this apk rejects `apk query` — apk-tools 2
     * fails with `unrecognized option: fields`, and the provider then uses
     * the `apk info` path instead.
     */
    fun isQueryUnsupported(output: String): Boolean =
        output.contains("unrecognized option") || output.contains("Usage: apk")
}
