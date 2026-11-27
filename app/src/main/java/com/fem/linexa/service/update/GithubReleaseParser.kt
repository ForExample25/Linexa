package com.fem.linexa.service.update

import com.fem.linexa.domain.update.GithubRelease
import com.fem.linexa.domain.update.GithubReleaseAsset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * GitHub's wire shapes for `/releases`, parsed into the domain
 * [GithubRelease] models.
 *
 * Everything is lenient-by-default (`ignoreUnknownKeys`, nullable optional
 * fields) EXCEPT the two things a release cannot lack: a tag name and, for
 * assets, a name + download URL — entries without them are dropped rather
 * than half-parsed, because a release the checker cannot identify is worse
 * than no release at all.
 *
 * The `digest` field ("sha256:<hex>") is the GitHub-provided asset integrity
 * value; it is newer than some historic assets and may be absent — nullable,
 * and the downloader treats absence as "verify size only". The `state` field
 * filters out GitHub's placeholder assets ("starter") from new releases.
 */
internal object GithubReleaseParser {

    @Serializable
    internal data class WireRelease(
        @SerialName("tag_name") val tagName: String? = null,
        val name: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        val body: String? = null,
        val assets: List<WireAsset> = emptyList()
    )

    @Serializable
    internal data class WireAsset(
        val name: String? = null,
        val size: Long? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
        @SerialName("content_type") val contentType: String? = null,
        val digest: String? = null,
        val state: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A hidden HTML comment in the release body:
     * `<!-- linexa-version-code: 1002 -->`. It is invisible on GitHub and stripped
     * before the body reaches the in-app notes, but it gives the updater the one
     * value GitHub does not expose: the APK's Android `versionCode`.
     */
    private val versionCodeMarker =
        Regex("""(?is)<!--\s*linexa-version-code\s*:\s*(\d+)\s*-->""")

    /**
     * A marker on its own line leaves the blank line before it and the line
     * break after it, producing a three-newline gap where the visible notes
     * had a paragraph break. Collapse runs of blank lines to one paragraph
     * separator; markdown treats either the same, but the in-app preview shows
     * the difference.
     */
    private val blankLineRun = Regex("""(?:\r?\n){3,}""")

    /**
     * Parse a releases LIST body. Malformed input throws
     * [SerializationException] (the caller maps it to a check failure); a
     * structurally valid but entry-less body is an empty list.
     */
    fun parseList(body: String): List<GithubRelease> {
        val wires = json.decodeFromString<List<WireRelease>>(body)
        return wires.mapNotNull(::toDomain)
    }

    /** One wire release → domain release, or null when it is unusable. */
    internal fun toDomain(wire: WireRelease): GithubRelease? {
        val tag = wire.tagName?.trim().orEmpty()
        if (tag.isEmpty()) return null
        val assets = wire.assets.mapNotNull(::toAssetDomain)
        val body = wire.body
        return GithubRelease(
            tagName = tag,
            displayName = wire.name?.trim()?.takeIf { it.isNotEmpty() },
            isPrerelease = wire.prerelease,
            isDraft = wire.draft,
            publishedAtMs = wire.publishedAt?.let(::parseInstantMs),
            declaredVersionCode = body?.let { raw ->
                versionCodeMarker.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            },
            notes = body
                ?.replace(versionCodeMarker, "")
                ?.replace(blankLineRun, "\n\n")
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            assets = assets
        )
    }

    private fun toAssetDomain(wire: WireAsset): GithubReleaseAsset? {
        val name = wire.name?.trim().orEmpty()
        val url = wire.browserDownloadUrl?.trim().orEmpty()
        if (name.isEmpty() || !url.startsWith("https://")) return null
        // GitHub marks not-yet-uploaded assets "starter"; only fully
        // uploaded assets are downloadable.
        if (wire.state != null && wire.state != "uploaded") return null
        return GithubReleaseAsset(
            name = name,
            downloadUrl = url,
            sizeBytes = wire.size?.takeIf { it > 0 },
            contentType = wire.contentType?.trim()?.takeIf { it.isNotEmpty() },
            sha256Hex = wire.digest?.let(::parseDigestHex)
        )
    }

    /** `"sha256:<hex>"` → lowercase hex; anything else → null. */
    private fun parseDigestHex(raw: String): String? {
        val value = raw.trim()
        if (!value.startsWith("sha256:", ignoreCase = true)) return null
        val hex = value.removePrefix("sha256:").trim().lowercase()
        if (hex.length != 64 || hex.any { !it.isDigit() && it !in 'a'..'f' }) return null
        return hex
    }

    /** ISO-8601 instant (`2026-11-14T08:25:46Z`) → epoch ms; null on anything else. */
    private fun parseInstantMs(raw: String): Long? = try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    /** Marker for tests: parse failures surface as this type. */
    @Suppress("unused")
    private val failureMarker: Class<SerializationException> = SerializationException::class.java
}
