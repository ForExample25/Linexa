package com.fem.linexa.service.distro

import com.fem.linexa.core.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real resumable downloader (download/bootstrap system fix): the audit's
 * "download / verify checksum / resume interrupted downloads / extract /
 * verify installation" pipeline, minus extract/verify-installation which
 * are [com.fem.linexa.service.installer.RootFsInstaller]'s job
 * — reused as-is via [com.fem.linexa.service.distro.DownloadService]
 * rather than duplicated here.
 *
 * Deliberately `java.net.HttpURLConnection` rather than adding a new HTTP
 * client dependency (OkHttp/Ktor): this project had zero networking code
 * before this, and the JDK-provided client is enough for "download a file
 * with Range-based resume" without introducing a dependency whose Gradle
 * resolution can't be verified from this sandbox (see the docx skill's
 * general caution about unverified new dependencies — same reasoning
 * applies to build.gradle changes).
 *
 * Resume strategy (Phase 06 / OPT-SEC-005 — was "trust the 206 and append"):
 *
 * 1. When a download starts fresh, the response's `ETag`, `Last-Modified`
 *    and `Content-Length` — the representation identity — are persisted to a
 *    sidecar next to the partial (`<destination>.meta`, written atomically
 *    via temp-file rename). Not in the database: this is download-local
 *    state, and a row would survive the cache it describes.
 * 2. A resume is only attempted when a sidecar with at least one validator
 *    (ETag/Last-Modified) exists. Missing or unreadable sidecar → restart
 *    from zero; guessing the representation is how a poisoned archive gets
 *    assembled from two different files.
 * 3. The resume request carries `Range` AND `If-Range: <validator>`. A
 *    `206` then certifies the server matched the validator — an `If-Range`
 *    mismatch comes back as `200` with the whole (current) representation,
 *    which is consumed as a fresh full download (truncate + replace), the
 *    spec-mandated behavior, and the sidecar is rewritten with the new
 *    identity.
 * 4. A `206` is additionally validated locally: `Content-Range` must parse,
 *    start at exactly the resume offset, and announce a total consistent
 *    with the sidecar's recorded size. Any deviation restarts from zero —
 *    appending the wrong bytes is never the lesser error.
 * 5. `RestartFromZero` deletes BOTH the partial and the sidecar before the
 *    next attempt: a zero-length partial with a stale sidecar would look
 *    like a resumable download on the next run.
 *
 * A `200` response to a non-ranged request is consumed as a fresh full
 * download in the same attempt (truncating the stale partial first) instead
 * of wasting the already-open connection. A `416` specifically restarts from
 * zero (our partial doesn't correspond to this resource at all).
 */
@Singleton
class HttpDistroDownloader @Inject constructor() : DistroDownloader {

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (Long, Long?) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val sidecar = sidecarFor(destination)
        var resumeFrom = destination.length()
        var meta: DownloadSidecarMeta? = null
        var lastError = "Download did not complete"

        // Phase 06: a partial without its identity sidecar (or without any
        // validator in it) cannot be resumed safely — restart from zero
        // rather than append to an unverified representation.
        if (resumeFrom > 0) {
            meta = readSidecar(sidecar)
            if (meta == null || (meta.etag == null && meta.lastModified == null)) {
                Logger.w(TAG, "Partial download has no usable identity sidecar — restarting from zero")
                restartFromZero(destination, sidecar)
                resumeFrom = 0L
            }
        }

        for (attempt in 1..MAX_ATTEMPTS) {
            when (val outcome = attemptDownload(url, destination, resumeFrom, meta, onProgress)) {
                is AttemptOutcome.Success -> {
                    // The archive is complete; the sidecar described a PARTIAL.
                    // (DownloadService deletes the whole cache on success; this
                    // covers the case where the caller keeps the file.)
                    sidecar.delete()
                    return@withContext DownloadResult.Success(destination, outcome.sha256)
                }
                is AttemptOutcome.Fatal ->
                    return@withContext DownloadResult.Failure(outcome.reason)
                is AttemptOutcome.RestartFromZero -> {
                    Logger.w(TAG, "Restarting from zero: ${outcome.reason}")
                    restartFromZero(destination, sidecar)
                    resumeFrom = 0L
                    meta = null
                    lastError = outcome.reason
                }
                is AttemptOutcome.Retryable -> {
                    Logger.w(TAG, "Retryable failure (attempt $attempt/$MAX_ATTEMPTS): ${outcome.reason}")
                    // A full-body attempt that died mid-stream replaced the
                    // representation identity — persist the new one so the
                    // next attempt resumes against the CURRENT resource.
                    outcome.observedMeta?.let { observed ->
                        if (writeSidecar(sidecar, observed)) meta = observed
                    }
                    resumeFrom = destination.length()
                    lastError = outcome.reason
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        DownloadResult.Failure(lastError)
    }

    /**
     * One HTTP attempt. [meta] is the persisted identity for the partial on
     * disk (non-null exactly when [resumeFrom] is a verified resume point).
     */
    private suspend fun attemptDownload(
        url: String,
        destination: File,
        resumeFrom: Long,
        meta: DownloadSidecarMeta?,
        onProgress: suspend (Long, Long?) -> Unit
    ): AttemptOutcome {
        val connection = try {
            openConnectionFollowingRedirects(url, resumeFrom, meta)
        } catch (e: IOException) {
            return AttemptOutcome.Retryable(e.message ?: "Connection failed")
        }

        // Declared outside the try so the catch can tell WHICH response was
        // in flight when the I/O died: a mid-body death of a 200 (truncate)
        // response means the representation on disk is now the NEW one, and
        // the next attempt must resume against it — so its identity is
        // captured from the connection headers.
        var code = -1
        try {
            code = connection.responseCode
            val (startingOffset, truncateFirst) = when (code) {
                206 -> validate206(connection, resumeFrom, meta)
                    ?: return AttemptOutcome.RestartFromZero(
                        "Server's 206 does not match the resume position or recorded size (Content-Range: " +
                            "${connection.getHeaderField("Content-Range") ?: "<missing>"})"
                    )
                200 -> 0L to true
                416 -> return AttemptOutcome.RestartFromZero(
                    "Server rejected the resume offset (HTTP 416); the partial file may be stale"
                )
                in 500..599 -> return AttemptOutcome.Retryable("HTTP $code from $url")
                else -> return AttemptOutcome.Fatal("HTTP $code from $url")
            }

            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
            // On a 206, Content-Length is only the remaining chunk's size, not the whole file's.
            val totalBytes = if (code == 206) contentLength?.let { it + startingOffset } else contentLength

            // Phase 06: what THIS response says about the representation's
            // identity. On a full-body (200) response the validators are the
            // new truth; on a 206 the sidecar already carries them (If-Range
            // certified the match) — only a previously-unknown total size is
            // filled in from Content-Range.
            val observedMeta = if (truncateFirst) {
                DownloadSidecarMeta(
                    etag = connection.getHeaderField("ETag"),
                    lastModified = connection.getHeaderField("Last-Modified"),
                    totalBytes = contentLength,
                    finalUrl = connection.url.toString()
                )
            } else {
                meta?.let { current ->
                    val rangeTotal = parseContentRangeTotal(connection.getHeaderField("Content-Range"))
                    if (current.totalBytes == null && rangeTotal != null) {
                        current.copy(totalBytes = rangeTotal, finalUrl = connection.url.toString())
                    } else current
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            if (!truncateFirst && startingOffset > 0) {
                // Re-digest what's already on disk from a prior attempt so
                // the final hash covers the whole file, not just this
                // attempt's chunk.
                destination.inputStream().use { existing ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = existing.read(buffer)
                        if (n <= 0) break
                        digest.update(buffer, 0, n)
                    }
                }
            }

            var downloaded = startingOffset
            FileOutputStream(destination, /* append = */ !truncateFirst).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastEmit = downloaded
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        downloaded += n
                        if (downloaded - lastEmit >= PROGRESS_EMIT_INTERVAL_BYTES) {
                            lastEmit = downloaded
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
            }
            onProgress(downloaded, totalBytes)

            // Pre-release audit fix (2026-09-02, finding F2): a clean EOF used
            // to end the loop "successfully" even when the server closed the
            // connection early (proxies, Doze, flaky mobile links) — a
            // TRUNCATED archive was returned as Success and only surfaced
            // later as a confusing tar error (or not at all, when no checksum
            // was available). Compare against the announced length and treat
            // a short read as retryable.
            if (totalBytes != null && totalBytes > 0 && downloaded < totalBytes) {
                return AttemptOutcome.Retryable(
                    "connection closed early: downloaded $downloaded of $totalBytes bytes",
                    observedMeta.takeIf { truncateFirst }
                )
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            // A completed full-body response is the moment the identity is
            // authoritative; persist it so an interrupted LATER write (caller
            // crash before the cache is consumed) can still resume correctly.
            if (truncateFirst) writeSidecar(sidecarFor(destination), observedMeta!!)
            return AttemptOutcome.Success(sha256)
        } catch (e: IOException) {
            return AttemptOutcome.Retryable(
                e.message ?: "I/O error during download",
                if (code == 200) DownloadSidecarMeta(
                    etag = connection.getHeaderField("ETag"),
                    lastModified = connection.getHeaderField("Last-Modified"),
                    totalBytes = connection.getHeaderField("Content-Length")?.toLongOrNull(),
                    finalUrl = connection.url.toString()
                ) else null
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The 206 contract (Phase 06): `Content-Range` must parse as
     * `bytes <start>-<end>/<total|*>`, start exactly at [resumeFrom], end at
     * or after the start, and announce a total consistent with the sidecar's
     * recorded size. Returns the (startingOffset, truncateFirst=false) pair
     * when the response is trustworthy, or null to force a restart.
     */
    private fun validate206(
        connection: HttpURLConnection,
        resumeFrom: Long,
        meta: DownloadSidecarMeta?
    ): Pair<Long, Boolean>? {
        val contentRange = connection.getHeaderField("Content-Range") ?: return null
        val match = CONTENT_RANGE_PATTERN.matchEntire(contentRange.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        // A ranged request for offset 0 is a full-body request in practice —
        // consume it like a 200 (truncate, full identity capture).
        if (resumeFrom == 0L) return 0L to true
        if (start != resumeFrom) return null
        if (end < start) return null
        if (total != null && total < end + 1) return null
        // The server says the resource is a different size than the one the
        // partial came from — the two representations don't match.
        if (meta?.totalBytes != null && total != null && total != meta.totalBytes) return null
        return start to false
    }

    /** `bytes <start>-<end>/<total|*>` — group 3 is `*` when the server omits the total. */
    private fun parseContentRangeTotal(contentRange: String?): Long? =
        contentRange?.let { raw ->
            CONTENT_RANGE_PATTERN.matchEntire(raw.trim())
                ?.groupValues
                ?.get(3)
                ?.takeIf { it != "*" }
                ?.toLongOrNull()
        }

    /**
     * The `If-Range` validator, in preference order: a strong ETag (the only
     * kind `If-Range` reliably honors), then `Last-Modified`, then a weak
     * ETag as a last resort — a server that ignores a weak `If-Range`
     * answers `200` with the full current body, which this downloader
     * consumes as a fresh download (correct, just not bandwidth-optimal).
     */
    private fun validatorFor(meta: DownloadSidecarMeta?): String? {
        val strongEtag = meta?.etag?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
        val lastModified = meta?.lastModified?.takeIf { it.isNotBlank() }
        val weakEtag = meta?.etag?.takeIf { it.isNotBlank() }
        return strongEtag ?: lastModified ?: weakEtag
    }

    /** Manual redirect following with the Range/If-Range headers re-applied on every hop, since [HttpURLConnection.setInstanceFollowRedirects] doesn't preserve custom request headers across a redirect. */
    private fun openConnectionFollowingRedirects(
        url: String,
        resumeFrom: Long,
        meta: DownloadSidecarMeta?,
        remainingRedirects: Int = MAX_REDIRECTS
    ): HttpURLConnection {
        var currentUrl = url
        var redirectsLeft = remainingRedirects
        val validator = if (resumeFrom > 0) validatorFor(meta) else null
        while (true) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                // Phase 06: a resume is only ever REQUESTED with an If-Range
                // validator. A Range without one invites an uncertified 206
                // append (the representation identity is unknown the moment a
                // sidecar write failed); with no validator no Range is sent,
                // so the server's full-body response is consumed as a fresh
                // download — the brief's "no validator → restart from zero",
                // enforced at the header level.
                if (validator != null) {
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                    // Phase 06: If-Range is what turns a silent wrong-append
                    // into a full re-download.
                    setRequestProperty("If-Range", validator)
                }
            }
            val code = connection.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) throw IOException("Redirect ($code) with no Location header from $currentUrl")
                if (redirectsLeft <= 0) throw IOException("Too many redirects fetching $url")
                // Pre-release audit fix (2026-09-02, finding F13): resolve
                // relative Location headers against the current URL — a bare
                // `Location: /mirrors/…` used to throw MalformedURLException
                // and burn all retries before failing the download.
                currentUrl = URL(URL(currentUrl), location).toString()
                redirectsLeft--
                continue
            }
            return connection
        }
    }

    /** The identity sidecar that accompanies [destination]'s partial bytes. */
    private fun sidecarFor(destination: File): File =
        File(destination.parentFile, destination.name + SIDECAR_SUFFIX)

    /** Missing/unreadable/corrupt sidecar → null: the caller must restart from zero. */
    private fun readSidecar(sidecar: File): DownloadSidecarMeta? = runCatching {
        if (!sidecar.isFile) return null
        json.decodeFromString<DownloadSidecarMeta>(sidecar.readText())
    }.getOrNull()

    /**
     * Atomic sidecar write (temp file + rename): a crash mid-write must
     * leave either the old sidecar or none — never a half-parsed one (a
     * corrupt sidecar would also trigger a full restart, but the atomic
     * write keeps the resumable case cheap).
     */
    private fun writeSidecar(sidecar: File, meta: DownloadSidecarMeta): Boolean = runCatching {
        val tmp = File(sidecar.parentFile, sidecar.name + ".tmp")
        tmp.writeText(json.encodeToString(meta))
        if (!tmp.renameTo(sidecar)) {
            tmp.delete()
            false
        } else true
    }.getOrDefault(false)

    /**
     * Phase 06: a restart removes BOTH artifacts — a zero-length partial
     * with a stale sidecar would be treated as a resumable download by the
     * next process (and `destination.length() == 0` + a stale total could
     * pair a fresh body with the OLD resource's validators).
     */
    private fun restartFromZero(destination: File, sidecar: File) {
        if (!destination.delete() && destination.exists()) {
            Logger.w(TAG, "Could not delete the stale partial ${destination.absolutePath} — it will be truncated by the next full-body response")
        }
        sidecar.delete()
    }

    private sealed interface AttemptOutcome {
        data class Success(val sha256: String) : AttemptOutcome

        /**
         * The attempt made progress but the connection died mid-body. When
         * the response was a full-body `200` (truncate path), [observedMeta]
         * carries the NEW representation identity to persist before the next
         * attempt resumes; for a `206` death the sidecar is already right.
         */
        data class Retryable(val reason: String, val observedMeta: DownloadSidecarMeta? = null) : AttemptOutcome
        data class RestartFromZero(val reason: String) : AttemptOutcome
        data class Fatal(val reason: String) : AttemptOutcome
    }

    companion object {
        private const val TAG = "HttpDistroDownloader"
        private const val MAX_ATTEMPTS = 5
        private const val MAX_REDIRECTS = 5
        private const val RETRY_DELAY_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_EMIT_INTERVAL_BYTES = 512L * 1024L

        /** Phase 06: identity sidecar suffix next to the partial download. */
        const val SIDECAR_SUFFIX = ".meta"

        private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Phase 06 (OPT-SEC-005): the representation identity of the resource a
 * partial download came from. `ETag`/`Last-Modified` are the `If-Range`
 * validators exactly as the server sent them (weak ETags included — the
 * validator selection prefers strong ones); `totalBytes` is the recorded
 * full size used to reject a `206` that announces a different resource;
 * `finalUrl` is diagnostic (the redirect-resolved location at capture time).
 *
 * Download-local state by design: it describes a file in `.download-cache`,
 * never a workspace, so it lives beside that file and dies with it.
 */
@Serializable
internal data class DownloadSidecarMeta(
    val etag: String? = null,
    val lastModified: String? = null,
    val totalBytes: Long? = null,
    val finalUrl: String? = null
)
