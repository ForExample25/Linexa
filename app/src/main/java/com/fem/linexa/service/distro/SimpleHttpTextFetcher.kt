package com.fem.linexa.service.distro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a small remote text file (directory listings, checksum sidecars)
 * and returns its whole body as a string. Deliberately separate from
 * [DistroDownloader]: that one has to support resumable, progress-reporting
 * downloads of multi-hundred-MB archives; this one is for the handful-of-
 * KB lookups [LinuxContainersResolver] and
 * [com.fem.linexa.domain.distro.DistroManifest]'s checksum
 * URLs need, where resumability would be pure overhead.
 */
@Singleton
class SimpleHttpTextFetcher @Inject constructor() {

    suspend fun getText(url: String, remainingRedirects: Int = 5): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
        }
        try {
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    // Phase 06 (OPT-SEC-006): bounded read, fail closed. The
                    // callers fetch handful-of-KB sidecars and directory
                    // listings; a misconfigured or malicious checksum URL
                    // answering with gigabytes used to be read whole into
                    // memory (readBytes()). The cap stops the read mid-body
                    // and throws — ChecksumResolver turns that into the
                    // honest "unverified" outcome, and
                    // LinuxContainersResolver surfaces it as a resolve
                    // failure — a truncated body is never handed to a
                    // checksum comparison.
                    val body = connection.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_BODY_BYTES) {
                                throw IOException(
                                    "Body from $url exceeds the ${MAX_BODY_BYTES / 1024} KiB text-fetch cap — failing closed instead of buffering it"
                                )
                            }
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    String(body, Charsets.UTF_8)
                }
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect ($code) with no Location header from $url")
                    if (remainingRedirects <= 0) throw IOException("Too many redirects fetching $url")
                    // Pre-release audit fix (2026-09-02, finding F13): resolve
                    // relative Location headers against the current URL (see
                    // HttpDistroDownloader — same class of failure).
                    getText(URL(URL(url), location).toString(), remainingRedirects - 1)
                }
                else -> throw IOException("HTTP $code fetching $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000

        /** 1 MiB — checksum sidecars and directory listings are KB-scale; anything larger is an attack or a misconfiguration. */
        const val MAX_BODY_BYTES = 1024L * 1024L

        private const val BUFFER_SIZE = 64 * 1024
    }
}
