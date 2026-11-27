package com.fem.linexa.service.update

import com.fem.linexa.domain.update.GithubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The GitHub Releases API client — the update checker's only network read.
 *
 * Deliberately `java.net.HttpURLConnection`, mirroring the project's
 * established networking (`SimpleHttpTextFetcher`,
 * `HttpDistroDownloader`) rather than adding an HTTP-client dependency: the
 * request is one bounded GET of a KB-scale JSON list. Same discipline as
 * [com.fem.linexa.service.distro.SimpleHttpTextFetcher]: manual redirect
 * following, connect/read timeouts, and a hard body cap so a misbehaving or
 * hostile endpoint cannot buffer gigabytes into memory.
 *
 * Errors are DISTINGUISHED, because the UI says different things about them:
 * rate limiting (HTTP 403 with the rate-limit headers exhausted) is "try
 * again later", a 5xx is "GitHub's problem", a 404/other 4xx is "repository
 * unreachable", and malformed JSON is its own honest failure. All of it
 * surfaces as [GithubApiException] or [IOException] — the coordinator maps
 * kinds to localized copy.
 *
 * No token, ever: the endpoint is public and anonymous. The `User-Agent` is
 * required by GitHub's API terms and identifies the app honestly.
 */
interface GithubReleaseApi {
    /** The repository's releases, newest first. */
    suspend fun fetchReleases(): List<GithubRelease>

    /** GitHub answered, but with something other than a usable release list. */
    class GithubApiException(
        val kind: Kind,
        val httpCode: Int,
        detail: String? = null
    ) : IOException(detail ?: kind.name) {
        enum class Kind { RATE_LIMITED, SERVER, UNAVAILABLE }
    }
}

@Singleton
class HttpGithubReleaseApi @Inject constructor() : GithubReleaseApi {

    override suspend fun fetchReleases(): List<GithubRelease> = withContext(Dispatchers.IO) {
        val connection = openFollowingRedirects(UpdateServerConfig.RELEASES_API_URL)
        try {
            when (val code = connection.responseCode) {
                in 200..299 -> readBody(connection)?.let(GithubReleaseParser::parseList) ?: emptyList()
                403 -> {
                    if (rateLimitExhausted(connection)) {
                        throw GithubReleaseApi.GithubApiException(
                            GithubReleaseApi.GithubApiException.Kind.RATE_LIMITED,
                            code,
                            "GitHub rate limit exhausted"
                        )
                    }
                    throw GithubReleaseApi.GithubApiException(
                        GithubReleaseApi.GithubApiException.Kind.UNAVAILABLE,
                        code,
                        "HTTP 403 from the releases API"
                    )
                }
                in 500..599 -> throw GithubReleaseApi.GithubApiException(
                    GithubReleaseApi.GithubApiException.Kind.SERVER,
                    code,
                    "HTTP $code from the releases API"
                )
                else -> throw GithubReleaseApi.GithubApiException(
                    GithubReleaseApi.GithubApiException.Kind.UNAVAILABLE,
                    code,
                    "HTTP $code from the releases API"
                )
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            throw GithubReleaseApi.GithubApiException(
                GithubReleaseApi.GithubApiException.Kind.UNAVAILABLE,
                httpCode = 0,
                detail = "Unreadable releases response: ${e.message}"
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Bounded read (2 MiB cap — a 20-release page with long notes fits well
     * below it; anything larger is not a releases page). Fails closed.
     */
    private fun readBody(connection: HttpURLConnection): String? {
        val stream = connection.inputStream ?: return null
        val body = stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                total += n
                if (total > MAX_BODY_BYTES) {
                    throw IOException(
                        "Releases response exceeds the ${MAX_BODY_BYTES / 1024 / 1024} MiB cap — failing closed"
                    )
                }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        return String(body, Charsets.UTF_8)
    }

    private fun rateLimitExhausted(connection: HttpURLConnection): Boolean =
        connection.getHeaderField("X-RateLimit-Remaining") == "0"

    /** Manual redirect following — `instanceFollowRedirects` drops the custom headers on each hop. */
    private fun openFollowingRedirects(url: String, remainingRedirects: Int = MAX_REDIRECTS): HttpURLConnection {
        var currentUrl = url
        var redirectsLeft = remainingRedirects
        while (true) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty(
                    "User-Agent",
                    "linexa-android/${com.fem.linexa.BuildConfig.VERSION_NAME} (+${UpdateServerConfig.REPOSITORY_URL})"
                )
            }
            val code = connection.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) throw IOException("Redirect ($code) with no Location header from $currentUrl")
                if (redirectsLeft <= 0) throw IOException("Too many redirects fetching $url")
                currentUrl = URL(URL(currentUrl), location).toString()
                redirectsLeft--
                continue
            }
            return connection
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_REDIRECTS = 5
        const val BUFFER_SIZE = 64 * 1024

        /** A releases page is KB-scale; this cap is hostile-endpoint defence only. */
        const val MAX_BODY_BYTES = 2L * 1024L * 1024L
    }
}
