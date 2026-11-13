package com.fem.linexa.service.distro

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.distro.ChecksumFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [ChecksumFormat] + sidecar URL into the actual expected SHA-256
 * hex digest for one specific archive filename, or `null` if it can't be
 * determined — never a guess, never a fabricated value. A `null` here is
 * exactly what makes [com.fem.linexa.domain.distro.DistroManifest]
 * entries like Arch Linux ARM's (no [ChecksumFormat] that applies) or
 * Debian's (best-effort sidecar path, unconfirmed) honest: the download
 * still proceeds, but [com.fem.linexa.service.distro.DownloadService]
 * records it as unverified rather than claiming a verification that never
 * actually happened.
 */
@Singleton
class ChecksumResolver @Inject constructor(
    private val textFetcher: SimpleHttpTextFetcher
) {

    suspend fun resolve(checksumUrl: String?, format: ChecksumFormat, targetFileName: String): String? {
        if (checksumUrl == null || format == ChecksumFormat.NONE) return null

        val hash = try {
            val text = textFetcher.getText(checksumUrl)
            when (format) {
                ChecksumFormat.SINGLE_HASH_LINE -> text.trim().split(Regex("\\s+")).firstOrNull()
                ChecksumFormat.MULTI_HASH_LIST -> text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { line -> line.endsWith(targetFileName) || line.endsWith("*$targetFileName") }
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                ChecksumFormat.NONE -> null
            }
        } catch (e: Exception) {
            Logger.w("ChecksumResolver", "Could not fetch/parse checksum from $checksumUrl: ${e.message}")
            null
        }

        // Never hand back something that merely *looks* present (e.g. a 404
        // error page's body happened to parse as "a first whitespace-
        // delimited token") without actually being a 64-character hex
        // digest — a caller comparing against that would either always
        // fail or, worse, coincidentally never get exercised.
        return hash?.takeIf { SHA256_HEX_PATTERN.matches(it) }
    }

    companion object {
        private val SHA256_HEX_PATTERN = Regex("[a-fA-F0-9]{64}")
    }
}
