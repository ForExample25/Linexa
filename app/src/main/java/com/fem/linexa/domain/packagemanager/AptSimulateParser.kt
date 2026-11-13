package com.fem.linexa.domain.packagemanager

/**
 * 2026-09-02 (guided desktop installer, Phase 3): pure JVM parsers for the
 * read-only APT queries the guided desktop installer runs before the user
 * confirms anything.
 *
 * Mirrors [AptOutputParser]'s convention (pure object, no Android, strictly
 * defensive, never throws — a parse failure degrades to "unknown", never to
 * a fabricated number).
 */
object AptSimulateParser {

    /**
     * Parses `apt-get -s install <pkgs>` output into the quoted sizes.
     *
     * Real shapes (apt 2.6.x, Debian bookworm arm64 — same family the
     * existing [AptOutputParser] shapes were verified against):
     * ```
     * Reading package lists...
     * Building dependency tree...
     * The following additional packages will be installed:
     *   ...
     * The following NEW packages will be installed:
     *   dbus-x11 openbox xterm ...
     * 0 upgraded, 127 newly installed, 0 to remove and 4 not upgraded.
     * Need to get 84.5 MB of archives.
     * After this operation, 284 MB of additional disk space will be used.
     * ```
     *
     * "Need to get" is the DOWNLOAD size; "After this operation" is the
     * INSTALLED size delta. apt prints them in binary-prefixed SI units
     * (kB/MB/GB, 1 kB = 1000 B per apt's own formatting), so the returned
     * values are approximations by nature — the caller must label them as
     * estimates, exactly as apt itself does ("~"). Returns nulls for any
     * line that is absent or unparseable: a simulation that failed to run
     * (no network, apt broken) must show "size unknown", never a guess.
     */
    fun parseSimulate(output: String): SimulateInfo {
        var downloadBytes: Long? = null
        var installedBytes: Long? = null
        var newPackages: Int? = null
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (downloadBytes == null && line.startsWith("Need to get ")) {
                downloadBytes = parseAptSize(line.removePrefix("Need to get ").substringBefore(" of archives"))
            }
            if (installedBytes == null && line.startsWith("After this operation,")) {
                installedBytes = parseAptSize(
                    line.removePrefix("After this operation,").trim()
                        .removeSuffix(" of additional disk space will be used.")
                        .trim()
                )
            }
            if (newPackages == null && line.contains(" newly installed,")) {
                // "0 upgraded, 127 newly installed, 0 to remove and 4 not upgraded."
                val count = line.substringBefore(" newly installed,")
                    .trim().substringAfterLast(' ').toLongOrNull()
                if (count != null) newPackages = count.toInt().takeIf { it >= 0 }
            }
        }
        return SimulateInfo(downloadBytes, installedBytes, newPackages)
    }

    data class SimulateInfo(
        /** Download size in bytes, or null when the simulation did not say. */
        val downloadSizeBytes: Long?,
        /** Installed-size delta in bytes, or null when the simulation did not say. */
        val installedSizeBytes: Long?,
        /** Count of NEW packages apt would install, or null. */
        val newPackages: Int?
    )

    /**
     * Parses an apt human size ("84.5 MB", "284 MB", "512 kB") into bytes.
     * apt's units are SI (1 kB = 1000 B); anything unparsable yields null.
     */
    fun parseAptSize(token: String): Long? = runCatching {
        val trimmed = token.trim()
        val match = SIZE_REGEX.find(trimmed) ?: return@runCatching null
        val number = match.groupValues[1].toDoubleOrNull() ?: return@runCatching null
        val unit = match.groupValues[2].uppercase()
        val multiplier = when {
            unit.startsWith("K") -> 1000L
            unit.startsWith("M") -> 1000L * 1000
            unit.startsWith("G") -> 1000L * 1000 * 1000
            unit.startsWith("B") -> 1L
            else -> return@runCatching null
        }
        (number * multiplier).toLong()
    }.getOrNull()

    private val SIZE_REGEX = Regex("""^([\d.]+)\s*(kB|MB|GB|B)$""", RegexOption.IGNORE_CASE)

    /**
     * Parses the package-level progress from a streaming
     * `DEBIAN_FRONTEND=noninteractive apt-get install` output.
     *
     * Contract (per the phase spec): package-level parsing is only wired
     * where the format is deterministic. apt's deterministic, locale-proof
     * package-level signals are:
     *  - `Selecting previously unselected package <name>.` — dpkg unpack begins
     *  - `Setting up <name> (<version>) ...` — dpkg configure completes
     *  - `Unpacking <name> (<version>) ...` — unpack completes
     * Everything else (progress bars `[##....]`, download percentages,
     * pre-configure lines) is deliberately NOT parsed: formats vary across
     * apt/dpkg versions and locales, and a misparse would fabricate a
     * package name or percentage — the exact thing the honesty contract
     * forbids. `Preparing to unpack`/`update-alternatives` lines are
     * likewise left alone.
     */
    fun parseInstallProgress(line: String): InstallEvent? = runCatching {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("Selecting previously unselected package ") ->
                InstallEvent(
                    packageName = trimmed
                        .removePrefix("Selecting previously unselected package ")
                        .removeSuffix(".")
                        .trim(),
                    stage = InstallStage.SELECTED
                )
            trimmed.startsWith("Unpacking ") ->
                InstallEvent(
                    packageName = trimmed.removePrefix("Unpacking ").substringBefore(" (").trim(),
                    stage = InstallStage.UNPACKING
                )
            trimmed.startsWith("Setting up ") ->
                InstallEvent(
                    packageName = trimmed.removePrefix("Setting up ").substringBefore(" (").trim(),
                    stage = InstallStage.CONFIGURING
                )
            else -> null
        }
    }.getOrNull()

    data class InstallEvent(
        val packageName: String,
        val stage: InstallStage
    )

    enum class InstallStage { SELECTED, UNPACKING, CONFIGURING }
}
