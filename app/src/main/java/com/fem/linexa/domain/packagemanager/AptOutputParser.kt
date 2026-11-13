package com.fem.linexa.domain.packagemanager

/**
 * §1.92 — Pure parsers for apt/dpkg command output (JVM-testable).
 *
 * Mirrors the `XvncProvisioner`/`FileSizeFormatter` pure-object convention:
 * no Android/Application dependency, so every parse rule can be asserted in
 * a plain JUnit test against real captured output shapes.
 *
 * §1.146 — verified against real output from Debian bookworm arm64
 * (apt 2.6.1, dpkg-query with the `-f` format below). [parseInstalled] and
 * [parsePolicy] matched the live shapes as written; [parseShow] is new and
 * closes the one real gap — `apt-cache show` carries `Size` (download,
 * bytes) and `Installed-Size` (KiB), so the Store tab no longer has to leave
 * both sizes null.
 */
internal object AptOutputParser {

    /**
     * Parse `dpkg-query -W` with the format
     * `${Package}\t${Version}\t${Installed-Size}\t${Status}\t${Section}\t${Essential}\t${Priority}\n`.
     *
     * Installed-Size is KiB (dpkg's documented unit) → bytes. Only
     * fully-installed (`install ok installed`) packages are returned.
     *
     * §1.147 — the trailing `Essential`/`Priority` columns decide
     * [InstalledPkg.removable]: dpkg marks the packages that must not be
     * removed with `Essential: yes` (bash, coreutils, dpkg, …) or
     * `Priority: required` (apt, libc6, …). Both come straight from the local
     * dpkg database, so the DEL button's disabled state is the distro's own
     * declaration rather than an app-side blocklist. Older/shorter formats
     * (5 columns) still parse — the two new columns are then absent and the
     * package is treated as removable, which is dpkg's default.
     */
    fun parseInstalled(output: String): List<InstalledPkg> =
        output.lines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 5) return@mapNotNull null
                val status = parts[3]
                if (!status.trim().endsWith(" installed")) return@mapNotNull null
                val section = parts[4].trim().takeIf { it.isNotEmpty() }
                val essential = parts.getOrNull(5)?.trim()?.equals("yes", ignoreCase = true) == true
                val required = parts.getOrNull(6)?.trim()?.equals("required", ignoreCase = true) == true
                InstalledPkg(
                    name = parts[0].trim(),
                    version = parts[1].trim(),
                    installedSizeBytes = parts[2].trim().toLongOrNull()?.let { it * 1024L },
                    // dpkg records the archive *section* (`admin`, `shells`),
                    // not the mirror; it is the category the card shows, and
                    // repository stays null because dpkg has no such field.
                    repository = null,
                    description = null,
                    section = section,
                    removable = !essential && !required
                )
            }

    /**
     * Parse `apt list --upgradable` into name → candidate version:
     * ```
     * Listing...
     * apt/stable-security 2.6.1 arm64 [upgradable from: 2.6.0]
     * bash/stable 5.2.15-2+b7 arm64 [upgradable from: 5.2.15-2+b2]
     * ```
     * The name is the token before `/`, the candidate version is the token
     * after the suite. Only rows apt itself marked `[upgradable from: …]`
     * are returned, so a package absent from this map has genuinely no update
     * and its UP action is not rendered at all.
     */
    fun parseUpgradable(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || !line.contains("[upgradable from:")) return@forEach
            val slash = line.indexOf('/')
            if (slash <= 0) return@forEach
            val name = line.substring(0, slash)
            val parts = line.split(Regex("\\s+"))
            val version = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@forEach
            result[name] = version
        }
        return result
    }

    /**
     * Parse `apt-cache search <query>` output: lines of `name - description`.
     */
    fun parseSearch(output: String): List<Pair<String, String>> =
        output.lines().mapNotNull { line ->
            val idx = line.indexOf(" - ")
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 3).trim()
        }

    /**
     * Parse `apt-cache policy <names>` output into name → (version, repo).
     * Policy blocks look like:
     * ```
     * bash:
     *   Installed: (none)
     *   Candidate: 5.2.21-2
     *   Version table:
     *  *** 5.2.21-2 500
     *         500 http://deb.debian.org/debian bookworm/main amd64 Packages
     * ```
     * The candidate version and the first repository URL are taken.
     */
    fun parsePolicy(output: String): Map<String, PolicyInfo> {
        val result = LinkedHashMap<String, PolicyInfo>()
        var current: String? = null
        output.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEach
            if (!line.startsWith(" ") && line.endsWith(":") && line.count { it == ' ' } == 0) {
                current = line.removeSuffix(":").trim()
                if (current!!.isNotEmpty()) result[current] = PolicyInfo()
            } else if (line.startsWith("  Candidate:") && current != null) {
                result[current] = result[current]!!.copy(version = line.substringAfter("Candidate:").trim())
            } else if (line.startsWith("        ") && current != null) {
                // version-table repo line: "500 http://deb.debian.org/debian bookworm/main amd64 Packages"
                val repo = line.trim().split(Regex("\\s+")).getOrNull(1)
                    ?.takeIf { it.startsWith("http") || it.startsWith("file:") }
                if (repo != null && result[current]!!.repository == null) {
                    result[current] = result[current]!!.copy(repository = repo)
                }
            }
        }
        return result
    }

    data class PolicyInfo(
        val version: String = "",
        val repository: String? = null
    )

    /**
     * Parse `apt-cache show <names>` output into name → (sizes, section,
     * description). Blocks are RFC822-style and separated by a blank line:
     * ```
     * Package: nano
     * Version: 7.2-1+deb12u1
     * Installed-Size: 2848
     * Description-en: small, friendly text editor inspired by Pico
     *  GNU nano is an easy-to-use text editor ...
     * Section: editors
     * Size: 656704
     * ```
     * `Size` is the download size in bytes; `Installed-Size` is KiB (dpkg's
     * documented unit) and is converted. Only the first block per package is
     * kept, which is apt's own candidate-first ordering. Continuation lines
     * (leading space) belong to the previous field and are skipped — the
     * short description is the first line of `Description`/`Description-en`.
     */
    fun parseShow(output: String): Map<String, ShowInfo> {
        val result = LinkedHashMap<String, ShowInfo>()
        var name: String? = null
        var current: ShowInfo? = null
        output.lines().forEach { raw ->
            if (raw.isBlank()) {
                name = null
                current = null
                return@forEach
            }
            // Continuation of the previous field (long description body).
            if (raw.first().isWhitespace()) return@forEach
            val colon = raw.indexOf(": ")
            if (colon <= 0) return@forEach
            val key = raw.substring(0, colon).trim()
            val value = raw.substring(colon + 2).trim()
            if (key == "Package") {
                // apt prints the candidate first; keep it and ignore repeats.
                if (value.isEmpty() || result.containsKey(value)) {
                    name = null
                    current = null
                } else {
                    name = value
                    current = ShowInfo()
                    result[value] = current!!
                }
                return@forEach
            }
            val info = current ?: return@forEach
            when (key) {
                "Version" -> if (info.version == null) info.version = value.takeIf { it.isNotEmpty() }
                "Size" -> if (info.downloadSizeBytes == null) info.downloadSizeBytes = value.toLongOrNull()?.takeIf { it > 0 }
                "Installed-Size" -> if (info.installedSizeBytes == null) {
                    info.installedSizeBytes = value.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1024L }
                }
                "Section" -> if (info.section == null) info.section = value.takeIf { it.isNotEmpty() }
                // §1.147: the upstream project page, used by the Store card's
                // OPEN action. Absent for many Debian packages, in which case
                // the action is simply not rendered.
                "Homepage" -> if (info.homepageUrl == null) info.homepageUrl = value.takeIf { it.isNotEmpty() }
                "Description", "Description-en" -> if (info.description == null) {
                    info.description = value.takeIf { it.isNotEmpty() }
                }
            }
        }
        return result
    }

    data class ShowInfo(
        var version: String? = null,
        var downloadSizeBytes: Long? = null,
        var installedSizeBytes: Long? = null,
        var section: String? = null,
        var description: String? = null,
        var homepageUrl: String? = null
    )

    /**
     * Extract the apt version from `apt-get --version`, whose first line is
     * `apt 2.6.1 (arm64)`.
     */
    fun parseVersion(output: String): String? =
        Regex("apt\\s+(\\S+)").find(output.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)
}
