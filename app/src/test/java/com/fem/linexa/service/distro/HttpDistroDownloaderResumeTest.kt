package com.fem.linexa.service.distro

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 06 (OPT-SEC-005): resume-integrity behavior of [HttpDistroDownloader]
 * against a scripted localhost server ([FakeHttpServer]).
 *
 * The interrupted-download state (partial + identity sidecar) is either
 * produced in-call by a first attempt that dies mid-body (server declares a
 * longer `Content-Length` than it sends), or pre-written to a fresh temp dir
 * — both are the states a killed process leaves behind in `.download-cache`.
 *
 * The retry loop's 3 s delay only fires on the Retryable outcome; the
 * restart-from-zero outcomes (bad `Content-Range`, `416`) continue
 * immediately, so these tests complete in wall-clock seconds — and under
 * `runTest` the delay is virtual anyway.
 */
class HttpDistroDownloaderResumeTest {

    /** Same JSON shape as the downloader's internal sidecar (same field names). */
    @Serializable
    data class Sidecar(
        val etag: String? = null,
        val lastModified: String? = null,
        val totalBytes: Long? = null,
        val finalUrl: String? = null
    )

    private val json = Json

    private fun newDir(): File = Files.createTempDirectory("linexa-dl").toFile()

    private fun sha256(vararg chunks: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bytes(size: Int, fill: Char): ByteArray = ByteArray(size) { fill.code.toByte() }

    private fun preWriteSidecar(dir: File, name: String, sidecar: Sidecar) {
        File(dir, name + HttpDistroDownloader.SIDECAR_SUFFIX)
            .writeText(json.encodeToString(sidecar))
    }

    private fun awaitServed(server: FakeHttpServer, expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && server.served < expected) {
            Thread.sleep(20)
        }
        assertEquals("connections served by the scripted server", expected, server.served)
    }

    @Test
    fun aTruncatedFirstAttemptResumesWithRangeAndIfRangeAndProducesTheRightHash() = runTest(timeout = 60.seconds) {
        val dir = newDir()
        try {
            val part1 = bytes(4096, 'A')
            val part2 = bytes(8192, 'B')
            val full = part1 + part2
            val server = FakeHttpServer(
                listOf(
                    // Attempt 1: announces the full length, sends only part1, dies.
                    FakeHttpServer.FakeResponse(
                        "200 OK",
                        mapOf("ETag" to "\"v1\"", "Last-Modified" to "Wed, 12 Nov 2026 10:00:00 GMT"),
                        body = part1,
                        declaredLength = full.size.toLong()
                    ),
                    // Attempt 2: honest 206 for the exact resume offset.
                    FakeHttpServer.FakeResponse(
                        "206 Partial Content",
                        mapOf(
                            "ETag" to "\"v1\"",
                            "Content-Range" to "bytes ${part1.size}-${full.size - 1}/${full.size}"
                        ),
                        body = part2
                    )
                )
            )
            val destination = File(dir, "rootfs.tar.gz")
            try {
                val result = HttpDistroDownloader().download(server.url, destination) { _, _ -> }
                assertTrue("expected Success, was $result", result is DownloadResult.Success)
                result as DownloadResult.Success
                assertEquals(sha256(full), result.sha256)
                assertTrue("final file must be the whole representation", full.contentEquals(destination.readBytes()))
                awaitServed(server, 2)
                val requests = server.requests
                assertEquals(2, requests.size)
                assertFalse("a fresh download must not send Range", requests[0].headers.containsKey("range"))
                assertEquals("bytes=${part1.size}-", requests[1].headers["range"])
                assertEquals("the sidecar ETag must gate the resume", "\"v1\"", requests[1].headers["if-range"])
                assertFalse(
                    "the sidecar must be gone once the archive is complete",
                    File(dir, "rootfs.tar.gz" + HttpDistroDownloader.SIDECAR_SUFFIX).exists()
                )
            } finally {
                server.shutdown()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a206StartingAtTheWrongOffsetRestartsFromZero() = runTest(timeout = 60.seconds) {
        val dir = newDir()
        try {
            val part1 = bytes(4096, 'A')
            val part2 = bytes(8192, 'B')
            val full = part1 + part2
            val server = FakeHttpServer(
                listOf(
                    FakeHttpServer.FakeResponse(
                        "200 OK",
                        mapOf("ETag" to "\"v1\""),
                        body = part1,
                        declaredLength = full.size.toLong()
                    ),
                    // A 206 that does not start where we asked — appending it
                    // would assemble a poisoned archive.
                    FakeHttpServer.FakeResponse(
                        "206 Partial Content",
                        mapOf(
                            "ETag" to "\"v1\"",
                            "Content-Range" to "bytes ${part1.size - 100}-${full.size - 1}/${full.size}"
                        ),
                        body = part2
                    ),
                    // After the restart: a fresh, complete representation.
                    FakeHttpServer.FakeResponse("200 OK", mapOf("ETag" to "\"v1\""), body = full)
                )
            )
            val destination = File(dir, "rootfs.tar.gz")
            try {
                val result = HttpDistroDownloader().download(server.url, destination) { _, _ -> }
                assertTrue("expected Success, was $result", result is DownloadResult.Success)
                result as DownloadResult.Success
                assertEquals(sha256(full), result.sha256)
                assertTrue(full.contentEquals(destination.readBytes()))
                awaitServed(server, 3)
                val requests = server.requests
                assertEquals(3, requests.size)
                assertEquals("bytes=${part1.size}-", requests[1].headers["range"])
                assertFalse(
                    "after the restart the fresh request must not carry Range",
                    requests[2].headers.containsKey("range")
                )
                assertFalse(
                    "the sidecar must be gone after success",
                    File(dir, "rootfs.tar.gz" + HttpDistroDownloader.SIDECAR_SUFFIX).exists()
                )
            } finally {
                server.shutdown()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a206AnnouncingADifferentTotalRestartsFromZero() = runTest(timeout = 60.seconds) {
        val dir = newDir()
        try {
            val part1 = bytes(4096, 'A')
            val part2 = bytes(8192, 'B')
            val full = part1 + part2
            val server = FakeHttpServer(
                listOf(
                    FakeHttpServer.FakeResponse(
                        "200 OK",
                        mapOf("ETag" to "\"v1\""),
                        body = part1,
                        declaredLength = full.size.toLong()
                    ),
                    // Same start, but the total names a DIFFERENT resource than
                    // the one the sidecar recorded.
                    FakeHttpServer.FakeResponse(
                        "206 Partial Content",
                        mapOf(
                            "ETag" to "\"v1\"",
                            "Content-Range" to "bytes ${part1.size}-${full.size - 1}/${full.size + 500}"
                        ),
                        body = part2
                    ),
                    FakeHttpServer.FakeResponse("200 OK", mapOf("ETag" to "\"v1\""), body = full)
                )
            )
            val destination = File(dir, "rootfs.tar.gz")
            try {
                val result = HttpDistroDownloader().download(server.url, destination) { _, _ -> }
                assertTrue("expected Success, was $result", result is DownloadResult.Success)
                result as DownloadResult.Success
                assertEquals(sha256(full), result.sha256)
                assertTrue(full.contentEquals(destination.readBytes()))
                awaitServed(server, 3)
                assertFalse(
                    "the fresh request after the restart must not carry Range",
                    server.requests[2].headers.containsKey("range")
                )
            } finally {
                server.shutdown()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a206WithoutContentRangeRestartsFromZero() = runTest(timeout = 60.seconds) {
        val dir = newDir()
        try {
            val part1 = bytes(4096, 'A')
            val part2 = bytes(8192, 'B')
            val full = part1 + part2
            val server = FakeHttpServer(
                listOf(
                    FakeHttpServer.FakeResponse(
                        "200 OK",
                        mapOf("ETag" to "\"v1\""),
                        body = part1,
                        declaredLength = full.size.toLong()
                    ),
                    // 206 with no Content-Range at all: nothing to validate.
                    FakeHttpServer.FakeResponse(
                        "206 Partial Content",
                        mapOf("ETag" to "\"v1\""),
                        body = part2
                    ),
                    FakeHttpServer.FakeResponse("200 OK", mapOf("ETag" to "\"v1\""), body = full)
                )
            )
            val destination = File(dir, "rootfs.tar.gz")
            try {
                val result = HttpDistroDownloader().download(server.url, destination) { _, _ -> }
                assertTrue("expected Success, was $result", result is DownloadResult.Success)
                result as DownloadResult.Success
                assertEquals(sha256(full), result.sha256)
                assertTrue(full.contentEquals(destination.readBytes()))
                awaitServed(server, 3)
            } finally {
                server.shutdown()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Covers the changed-ETag case and the "200 to a ranged request" case:
     * they are the same client-visible event — the `If-Range` validator no
     * longer matches, so the server answers `200` with the WHOLE current
     * representation, which must be consumed as a fresh download (truncate),
     * never appended to the stale partial.
     */
    @Test
    fun a200ToARangedRequestTruncatesAndReplacesThePartial() = runTest(timeout = 60.seconds) {
        val dir = newDir()
        try {
            val stalePartial = bytes(4096, 'Z')
            val newBody = bytes(12288, 'C')
            val destination = File(dir, "rootfs.tar.gz")
            destination.writeBytes(stalePartial)
            preWriteSidecar(
                dir,
                "rootfs.tar.gz",
                Sidecar(etag = "\"old\"", lastModified = "Tue, 10 Nov 2026 00:00:00 GMT", totalBytes = 12288)
            )
            val server = FakeHttpServer(
                listOf(
                    // If-Range "old" no longer matches → per spec the server
                    // answers 200 with the full NEW representation.
                    FakeHttpServer.FakeResponse(
                        "200 OK",
                        mapOf("ETag" to "\"new\"", "Last-Modified" to "Wed, 12 Nov 2026 10:00:00 GMT"),
                        body = newBody
                    )
                )
            )
            try {
                val result = HttpDistroDownloader().download(server.url, destination) { _, _ -> }
                assertTrue("expected Success, was $result", result is DownloadResult.Success)
                result as DownloadResult.Success
                assertEquals(sha256(newBody), result.sha256)
                assertTrue(
                    "the stale partial must be truncated, not appended to",
                    newBody.contentEquals(destination.readBytes())
                )
                awaitServed(server, 1)
                val request = server.requests.single()
                assertEquals("bytes=4096-", request.headers["range"])
                assertEquals("\"old\"", request.headers["if-range"])
                assertFalse(
                    "the sidecar must be gone after success",
                    File(dir, "rootfs.tar.gz" + HttpDistroDownloader.SIDECAR_SUFFIX).exists()
                )
            } finally {
                server.shutdown()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aPartialWithoutASidecarRestartsFromZeroWithoutRange() = runTest(timeout = 60.seconds) {
        val dir = newDir()
        try {
            val stalePartial = bytes(4096, 'Z')
            val full = bytes(12288, 'C')
            val destination = File(dir, "rootfs.tar.gz")
            destination.writeBytes(stalePartial)
            // NO sidecar: the identity of the bytes on disk is unknowable.
            val server = FakeHttpServer(
                listOf(FakeHttpServer.FakeResponse("200 OK", mapOf("ETag" to "\"v1\""), body = full))
            )
            try {
                val result = HttpDistroDownloader().download(server.url, destination) { _, _ -> }
                assertTrue("expected Success, was $result", result is DownloadResult.Success)
                result as DownloadResult.Success
                assertEquals(sha256(full), result.sha256)
                assertTrue(full.contentEquals(destination.readBytes()))
                awaitServed(server, 1)
                assertFalse(
                    "without an identity sidecar the first request must not send Range",
                    server.requests.single().headers.containsKey("range")
                )
                assertFalse(
                    "no sidecar may outlive a completed download",
                    File(dir, "rootfs.tar.gz" + HttpDistroDownloader.SIDECAR_SUFFIX).exists()
                )
            } finally {
                server.shutdown()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a416RestartsFromZero() = runTest(timeout = 60.seconds) {
        val dir = newDir()
        try {
            val stalePartial = bytes(4096, 'Z')
            val full = bytes(12288, 'C')
            val destination = File(dir, "rootfs.tar.gz")
            destination.writeBytes(stalePartial)
            preWriteSidecar(
                dir,
                "rootfs.tar.gz",
                Sidecar(etag = "\"old\"", lastModified = "Tue, 10 Nov 2026 00:00:00 GMT", totalBytes = 12288)
            )
            val server = FakeHttpServer(
                listOf(
                    FakeHttpServer.FakeResponse("416 Range Not Satisfiable"),
                    FakeHttpServer.FakeResponse("200 OK", mapOf("ETag" to "\"v1\""), body = full)
                )
            )
            try {
                val result = HttpDistroDownloader().download(server.url, destination) { _, _ -> }
                assertTrue("expected Success, was $result", result is DownloadResult.Success)
                result as DownloadResult.Success
                assertEquals(sha256(full), result.sha256)
                assertTrue(full.contentEquals(destination.readBytes()))
                awaitServed(server, 2)
                val requests = server.requests
                assertEquals(2, requests.size)
                assertEquals("bytes=4096-", requests[0].headers["range"])
                assertEquals("\"old\"", requests[0].headers["if-range"])
                assertFalse(
                    "after the 416 restart the fresh request must not carry Range",
                    requests[1].headers.containsKey("range")
                )
                assertFalse(
                    "the 416 restart must delete the stale sidecar",
                    File(dir, "rootfs.tar.gz" + HttpDistroDownloader.SIDECAR_SUFFIX).exists()
                )
            } finally {
                server.shutdown()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
