package com.fem.linexa.service.exporter

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Deterministic, stable naming for exported RootFS backups.
 *
 * The name is the only metadata a backup file carries on the user's storage
 * (the *inside* of the archive carries the real declaration — see
 * [ExportRequest.embeddedConfig]), so it encodes what the user needs to
 * recognise the file a month later, in the order that matters:
 *
 * ```
 * linexa-<distro>[-<version>][-<arch>]-<yyyyMMdd-HHmmss>-<id8>.tar.gz
 * ```
 *
 * e.g. `linexa-debian-12.9-arm64-v8a-20260829-143052-a1b2c3d4.tar.gz`.
 *
 * Rules:
 *  - every component is slugified to `[a-z0-9._-]` (the same character set
 *    [com.fem.linexa.service.installer.InstallCoordinator.slugify] keeps for
 *    container codes) so the name is safe on every SAF provider and every
 *    host filesystem;
 *  - `"unknown"` / blank components are dropped rather than encoded — the
 *    literal `"unknown"` is what an undetected import persists, and printing
 *    it in a filename would read as a measurement (the card's own rule);
 *  - the timestamp and the workspace-id suffix keep successive exports of
 *    the same workspace from colliding (the SAF create dialog offers the
 *    name, but a provider may still auto-uniquify);
 *  - the extension is always `.tar.gz` — the format [TarRootFsExporter]
 *    writes, and one of the three the installer sniffs by content.
 */
object ExportNames {

    /** The compression container [TarRootFsExporter] writes. */
    const val EXPORT_EXTENSION = "tar.gz"

    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /**
     * Builds the suggested file name for one export.
     *
     * @param distroName e.g. "Debian" — display form is fine; it is slugified.
     * @param distroVersion e.g. "12.9", "unknown", null.
     * @param distroArch e.g. "arm64-v8a" (the persisted ABI), or null.
     * @param workspaceId the workspace's UUID; its first 8 hex characters
     *   disambiguate successive exports.
     * @param now the wall-clock instant to encode (defaults to now; injected
     *   for determinism in tests).
     */
    fun suggestedFileName(
        distroName: String?,
        distroVersion: String?,
        distroArch: String?,
        workspaceId: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val identity = listOf(distroName, distroVersion, distroArch)
            .mapNotNull(::meaningfulSlug)
            .joinToString("-")
        val timestamp = TIMESTAMP_FORMAT.format(now.atZone(zone))
        val idSuffix = workspaceId.take(8).ifBlank { "workspace" }
        return buildString {
            append("linexa")
            if (identity.isNotEmpty()) {
                append('-').append(identity)
            }
            append('-').append(timestamp)
            append('-').append(idSuffix)
            append('.').append(EXPORT_EXTENSION)
        }
    }

    /**
     * Lower-case `[a-z0-9._-]` slug, or null when the input carries no
     * information (`null`, blank, or the literal "unknown" in any casing —
     * see class KDoc).
     */
    private fun meaningfulSlug(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.equals("unknown", ignoreCase = true)) return null
        val slug = raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
        return slug.ifBlank { null }
    }
}
