package com.fem.linexa.service.rootfs.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.146 — OCI image layout resolution, modelled on the real Fedora image.
 *
 * Digests below are the actual ones from
 * `Fedora-Container-Base-Generic-42-1.1.aarch64.oci.tar.xz` (truncated to
 * 64 hex chars each), so the paths under test are the paths that file contains.
 */
class OciImageLayoutTest {

    private val manifestDigest = "sha256:" + "cf9e82".padEnd(64, '0')
    private val layerDigest = "sha256:" + "cfc0be".padEnd(64, '0')
    private val configDigest = "sha256:" + "f8c61c".padEnd(64, '0')

    private val manifestPath = "blobs/sha256/" + manifestDigest.substringAfter(':')
    private val layerPath = "blobs/sha256/" + layerDigest.substringAfter(':')

    private fun indexJson(vararg digests: String) = """
        {"schemaVersion":2,"manifests":[${digests.joinToString(",") {
        """{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"$it","size":491}"""
    }}]}
    """.trimIndent().toByteArray()

    private fun manifestJson(vararg layers: String) = """
        {"schemaVersion":2,
         "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$configDigest","size":2000},
         "layers":[${layers.joinToString(",") {
        """{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"$it","size":72000000}"""
    }}]}
    """.trimIndent().toByteArray()

    private fun resolveRealShape(): OciImageLayout.Result = OciImageLayout.resolve(
        indexJson(manifestDigest),
        mapOf(
            OciImageLayout.INDEX_JSON to indexJson(manifestDigest),
            manifestPath to manifestJson(layerDigest)
        )
    )

    // ── the real image ───────────────────────────────────────────────────

    @Test
    fun resolvesTheRootfsLayerFromTheLayoutsOwnIndex() {
        val result = resolveRealShape()
        assertTrue("expected a layer, got $result", result is OciImageLayout.Result.Layer)
        result as OciImageLayout.Result.Layer
        assertEquals(layerPath, result.layerPath)
        assertEquals("application/vnd.oci.image.layer.v1.tar+gzip", result.mediaType)
    }

    @Test
    fun theResolvedPathIsTheBlobsPathNotTheDigest() {
        // The caller compares this against tar entry names, so the shape matters.
        val result = resolveRealShape() as OciImageLayout.Result.Layer
        assertTrue(result.layerPath.startsWith(OciImageLayout.BLOB_PREFIX))
        assertFalse(result.layerPath.contains(":"))
    }

    // ── layout identification ────────────────────────────────────────────

    @Test
    fun aLayoutNeedsBothTheMarkerAndTheIndex() {
        assertTrue(
            OciImageLayout.looksLikeLayout(
                setOf(OciImageLayout.LAYOUT_MARKER, OciImageLayout.INDEX_JSON, "blobs/sha256/abc")
            )
        )
        // A rootfs that merely happens to contain an index.json is not a layout.
        assertFalse(OciImageLayout.looksLikeLayout(setOf(OciImageLayout.INDEX_JSON, "etc/os-release")))
        assertFalse(OciImageLayout.looksLikeLayout(setOf(OciImageLayout.LAYOUT_MARKER)))
        assertFalse(OciImageLayout.looksLikeLayout(emptySet()))
    }

    // ── refusals that prevent a subtly-broken rootfs ─────────────────────

    @Test
    fun aMultiLayerImageIsRefusedRatherThanSilentlyFlattened() {
        // Extracting only the last layer would produce a rootfs missing files
        // that the earlier layers provide, and whiteouts would not be applied —
        // broken in ways the user would only discover later.
        val second = "sha256:" + "aaaaaa".padEnd(64, '0')
        val result = OciImageLayout.resolve(
            indexJson(manifestDigest),
            mapOf(
                OciImageLayout.INDEX_JSON to indexJson(manifestDigest),
                manifestPath to manifestJson(layerDigest, second)
            )
        )
        assertTrue(result is OciImageLayout.Result.Invalid)
        assertTrue((result as OciImageLayout.Result.Invalid).reason.contains("2 layers"))
    }

    @Test
    fun aMultiArchitectureIndexIsRefusedRatherThanPickingTheFirst() {
        // Picking one would risk installing an architecture that contradicts the
        // image the user chose.
        val other = "sha256:" + "bbbbbb".padEnd(64, '0')
        val index = indexJson(manifestDigest, other)
        val result = OciImageLayout.resolve(index, mapOf(OciImageLayout.INDEX_JSON to index))
        assertTrue(result is OciImageLayout.Result.Invalid)
        assertTrue((result as OciImageLayout.Result.Invalid).reason.contains("multi-architecture"))
    }

    @Test
    fun aMissingManifestBlobIsReportedRatherThanAssumed() {
        val result = OciImageLayout.resolve(
            indexJson(manifestDigest),
            mapOf(OciImageLayout.INDEX_JSON to indexJson(manifestDigest))
        )
        assertTrue(result is OciImageLayout.Result.Invalid)
        assertTrue((result as OciImageLayout.Result.Invalid).reason.contains("is missing from the archive"))
    }

    @Test
    fun malformedJsonAtEitherLevelIsReported() {
        val brokenIndex = OciImageLayout.resolve("{not json".toByteArray(), emptyMap())
        assertTrue(brokenIndex is OciImageLayout.Result.Invalid)

        val brokenManifest = OciImageLayout.resolve(
            indexJson(manifestDigest),
            mapOf(
                OciImageLayout.INDEX_JSON to indexJson(manifestDigest),
                manifestPath to "{not json".toByteArray()
            )
        )
        assertTrue(brokenManifest is OciImageLayout.Result.Invalid)
    }

    @Test
    fun anEmptyOrAbsentManifestListIsReported() {
        val empty = """{"schemaVersion":2,"manifests":[]}""".toByteArray()
        assertTrue(OciImageLayout.resolve(empty, emptyMap()) is OciImageLayout.Result.Invalid)

        val none = """{"schemaVersion":2}""".toByteArray()
        assertTrue(OciImageLayout.resolve(none, emptyMap()) is OciImageLayout.Result.Invalid)
    }

    @Test
    fun anOversizedIndexIsRejectedWithoutParsing() {
        val huge = ByteArray(OciImageLayout.MAX_JSON_BYTES + 1) { '{'.code.toByte() }
        val result = OciImageLayout.resolve(huge, emptyMap())
        assertTrue(result is OciImageLayout.Result.Invalid)
        assertTrue((result as OciImageLayout.Result.Invalid).reason.contains("exceeds"))
    }

    // ── the security boundary: a digest can never become a path ──────────

    @Test
    fun aTraversalShapedDigestIsRejectedAsMalformedRatherThanNormalized() {
        assertNull(OciImageLayout.blobPathFor("../../../../data/data/com.fem.linexa/files"))
        assertNull(OciImageLayout.blobPathFor("sha256:../../etc/passwd"))
        assertNull(OciImageLayout.blobPathFor("sha256:/etc/passwd"))
        assertNull(OciImageLayout.blobPathFor("sha256:abc/../../def"))
    }

    @Test
    fun onlyAWellFormedAlgorithmAndHexDigestYieldsAPath() {
        assertEquals(
            "blobs/sha256/${"ab".repeat(32)}",
            OciImageLayout.blobPathFor("sha256:${"ab".repeat(32)}")
        )
        // Uppercase hex is not what OCI emits and is not accepted — accepting it
        // would mean two spellings of one blob.
        assertNull(OciImageLayout.blobPathFor("sha256:${"AB".repeat(32)}"))
        assertNull(OciImageLayout.blobPathFor("sha256:tooshort"))
        assertNull(OciImageLayout.blobPathFor("nocolon" + "a".repeat(64)))
        assertNull(OciImageLayout.blobPathFor(""))
        assertNull(OciImageLayout.blobPathFor("sha256:"))
    }

    @Test
    fun aCraftedDigestCannotEscapeTheBlobsDirectory() {
        // The end-to-end property, stated once: whatever a hostile index.json
        // says, either resolution fails or the path stays under blobs/.
        listOf(
            "sha256:" + "ab".repeat(32),
            "sha512:" + "cd".repeat(32),
            "../etc/passwd",
            "sha256:..",
            "sha256:%2e%2e%2f"
        ).forEach { digest ->
            val path = OciImageLayout.blobPathFor(digest)
            if (path != null) {
                assertTrue("'$digest' produced '$path'", path.startsWith(OciImageLayout.BLOB_PREFIX))
                assertFalse(".." in path.split('/'))
            }
        }
    }

    @Test
    fun aLayerEntryWithoutADigestIsReported() {
        val manifest = """
            {"schemaVersion":2,"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip"}]}
        """.trimIndent().toByteArray()
        val result = OciImageLayout.resolve(
            indexJson(manifestDigest),
            mapOf(
                OciImageLayout.INDEX_JSON to indexJson(manifestDigest),
                manifestPath to manifest
            )
        )
        assertTrue(result is OciImageLayout.Result.Invalid)
        assertTrue((result as OciImageLayout.Result.Invalid).reason.contains("no digest"))
    }
}
