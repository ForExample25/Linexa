package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.rootfs.ArchiveEntryPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * §1.146 — resolves the rootfs layer inside an OCI image layout.
 *
 * ## Why this exists
 *
 * A user who downloads "the Fedora rootfs" for aarch64 gets
 * `Fedora-Container-Base-Generic-42-1.1.aarch64.oci.tar.xz`, which is not a
 * rootfs tarball at all. Verified contents of that exact file:
 *
 * ```
 * oci-layout                    {"imageLayoutVersion":"1.0.0"}
 * index.json                    -> manifest digest sha256:cf9e82…
 * blobs/sha256/cf9e82…          the manifest (JSON)
 * blobs/sha256/cfc0be…          the rootfs layer (gzip tar)   <-- the rootfs
 * blobs/sha256/f8c61c…          the image config (JSON)
 * ```
 *
 * Handed to the pre-§1.146 pipeline, the outer tar carried no `etc/` and no
 * distro marker, so detection found nothing and the post-extraction check
 * reported "Extracted rootfs is incomplete — missing essential dirs" for a
 * perfectly good image. Nothing in the codebase knew what an OCI layout was.
 *
 * This resolver reads the layout's own index to find *which* blob is the rootfs,
 * rather than guessing "the largest blob" or "the gzip one". Guessing would
 * work on this particular file and break on any multi-layer image.
 *
 * ## Scope
 *
 * Single-layer images only, which is what every distribution's "container base"
 * publishes. A multi-layer image would need whiteout (`.wh.`) handling to
 * compose layers correctly, and silently extracting only the last layer would
 * produce a subtly broken rootfs — so [resolve] reports the count and refuses
 * rather than producing something wrong.
 *
 * ## Security
 *
 * Blob digests from `index.json` are used only to *locate* an entry inside the
 * same archive, and every digest is validated as a bare hex/algorithm token
 * before use. A digest can therefore never become a path: `../../etc/passwd` is
 * rejected as a malformed digest, not normalized into a traversal. The
 * resolved inner tar is then walked by the same
 * [com.fem.linexa.core.security.ArchiveValidator] as any other archive.
 */
object OciImageLayout {

    const val LAYOUT_MARKER = "oci-layout"
    const val INDEX_JSON = "index.json"
    const val BLOB_PREFIX = "blobs/"

    /** Bounds on the JSON documents; real ones are well under 1 KB. */
    const val MAX_JSON_BYTES = 1 * 1024 * 1024

    /** `sha256:<64 hex>` and friends — algorithm, colon, lower-case hex. */
    private val DIGEST_REGEX = Regex("^[a-z0-9]+(?:[.+_-][a-z0-9]+)*:[a-f0-9]{32,128}$")

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    sealed interface Result {
        /** [layerPath] is the archive-relative path of the rootfs layer blob. */
        data class Layer(val layerPath: String, val mediaType: String?) : Result

        /** Not an OCI layout at all — caller should treat the tar as a rootfs. */
        data object NotOciLayout : Result

        data class Invalid(val reason: String) : Result
    }

    /**
     * True when [normalizedPaths] look like an OCI image layout.
     *
     * Requires the marker file *and* the index: a rootfs that merely happens to
     * contain a file called `index.json` is not a layout.
     */
    fun looksLikeLayout(normalizedPaths: Set<String>): Boolean =
        LAYOUT_MARKER in normalizedPaths && INDEX_JSON in normalizedPaths

    /**
     * Resolves the rootfs layer from the layout's JSON documents.
     *
     * @param indexJson bytes of `index.json`.
     * @param blobs archive-relative normalized path → bytes, for the small JSON
     *   blobs only. Layer blobs are large and must **not** be buffered; the
     *   caller passes the manifest blob it captured and re-reads the layer as a
     *   stream once this returns its path.
     */
    fun resolve(indexJson: ByteArray, blobs: Map<String, ByteArray>): Result {
        if (indexJson.size > MAX_JSON_BYTES) {
            return Result.Invalid("index.json exceeds $MAX_JSON_BYTES bytes")
        }
        val index = parse(indexJson) ?: return Result.Invalid("index.json is not valid JSON")

        val manifests = index["manifests"]?.asArrayOrNull()
            ?: return Result.Invalid("index.json has no 'manifests' array")
        if (manifests.isEmpty()) return Result.Invalid("index.json declares no manifests")

        // A multi-arch index lists one manifest per platform. Linexa cannot pick
        // between them without knowing which the user wanted, and picking the
        // first would silently install the wrong architecture — the detected
        // architecture would then contradict the image the user chose. Refuse.
        if (manifests.size > 1) {
            return Result.Invalid(
                "OCI image declares ${manifests.size} manifests (multi-architecture index); " +
                    "use a single-architecture image"
            )
        }
        val manifestDigest = manifests[0].asObjectOrNull()?.get("digest")?.asStringOrNull()
            ?: return Result.Invalid("index.json manifest entry has no digest")
        val manifestPath = blobPathFor(manifestDigest)
            ?: return Result.Invalid("index.json manifest digest is malformed ('$manifestDigest')")

        val manifestBytes = blobs[manifestPath]
            ?: return Result.Invalid("manifest blob '$manifestPath' is missing from the archive")
        if (manifestBytes.size > MAX_JSON_BYTES) {
            return Result.Invalid("manifest blob exceeds $MAX_JSON_BYTES bytes")
        }
        val manifest = parse(manifestBytes) ?: return Result.Invalid("manifest blob is not valid JSON")

        val layers = manifest["layers"]?.asArrayOrNull()
            ?: return Result.Invalid("manifest has no 'layers' array")
        if (layers.isEmpty()) return Result.Invalid("manifest declares no layers")
        if (layers.size > 1) {
            return Result.Invalid(
                "OCI image has ${layers.size} layers; only single-layer images are supported " +
                    "(composing layers requires whiteout handling)"
            )
        }
        val layer = layers[0].asObjectOrNull() ?: return Result.Invalid("manifest layer entry is not an object")
        val layerDigest = layer["digest"]?.asStringOrNull()
            ?: return Result.Invalid("manifest layer has no digest")
        val layerPath = blobPathFor(layerDigest)
            ?: return Result.Invalid("manifest layer digest is malformed ('$layerDigest')")

        return Result.Layer(layerPath, layer["mediaType"]?.asStringOrNull())
    }

    /**
     * Maps a digest to its archive-relative blob path, or null when the digest
     * is not a well-formed digest.
     *
     * The validation is the security boundary: without it, a crafted
     * `index.json` could set `digest` to `../../../../data/data/…` and this
     * function would hand back a path the caller looks up. Because the result is
     * only ever used as a **map key / tar-entry comparison** against entries
     * from the same archive, and because the digest is proven to contain no
     * slashes or dots-as-segments, a traversal cannot be expressed.
     */
    fun blobPathFor(digest: String): String? {
        val trimmed = digest.trim()
        if (!DIGEST_REGEX.matches(trimmed)) return null
        val algorithm = trimmed.substringBefore(':')
        val hex = trimmed.substringAfter(':')
        val path = "$BLOB_PREFIX$algorithm/$hex"
        // Belt and braces: the regex already excludes '/' and '.', so this
        // cannot fail — but an assertion here means a future regex relaxation
        // cannot quietly reintroduce traversal.
        return path.takeIf { ArchiveEntryPath.normalize(it) == it }
    }

    private fun parse(bytes: ByteArray): JsonObject? = try {
        json.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun kotlinx.serialization.json.JsonElement.asArrayOrNull(): JsonArray? =
        try { jsonArray } catch (_: Exception) { null }

    private fun kotlinx.serialization.json.JsonElement.asObjectOrNull(): JsonObject? =
        try { jsonObject } catch (_: Exception) { null }

    private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
        try { jsonPrimitive.content } catch (_: Exception) { null }
}
