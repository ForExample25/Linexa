package com.fem.linexa.service.update

import com.fem.linexa.domain.update.GithubReleaseAsset
import com.fem.linexa.service.distro.DownloadResult
import com.fem.linexa.service.distro.FakeHttpServer
import com.fem.linexa.service.distro.HttpDistroDownloader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

/**
 * The size/digest verification layer of [HttpApkUpdateDownloader], against
 * the REAL downloader ([HttpDistroDownloader]) and a scripted localhost
 * server — the two failure modes that matter for an installable file:
 *
 *   * a download whose bytes match the release's declared size AND
 *     GitHub-provided SHA-256 digest → success;
 *   * ANY mismatch (digest, size) → the file is DELETED and VERIFY is
 *     reported — a failed verification never leaves an installable-looking
 *     artifact behind.
 */
class HttpApkUpdateDownloaderTest {

    private fun sha256(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    private fun newDir(): File = Files.createTempDirectory("linexa-apk").toFile()

    /**
     * The production trust rule rejects the tests' localhost server, so the
     * size/digest cases opt out of it explicitly. The trust rule itself is
     * covered by [anUntrustedUrlIsRejectedBeforeAnyBytesAreFetched].
     */
    private fun downloader() = HttpApkUpdateDownloader(HttpDistroDownloader()).apply {
        assetUrlPolicy = { true }
    }

    @Test
    fun aCompleteDownloadMatchingSizeAndDigestSucceeds() = runTest(timeout = 60.seconds) {
        val body = ByteArray(4096) { 'A'.code.toByte() }
        val server = FakeHttpServer(listOf(FakeHttpServer.FakeResponse("200 OK", body = body)))
        val dir = newDir()
        try {
            val asset = GithubReleaseAsset(
                name = "app-release.apk",
                downloadUrl = server.url,
                sizeBytes = body.size.toLong(),
                sha256Hex = sha256(body)
            )
            val destination = File(dir, "update.apk")
            var lastProgress: Pair<Long, Long?>? = null

            val outcome = downloader()
                .download(asset, destination) { downloaded, total -> lastProgress = downloaded to total }

            val success = outcome as ApkUpdateDownloader.Outcome.Success
            assertEquals(body.size.toLong(), destination.length())
            assertEquals(sha256(body), success.sha256)
            // The final progress report carries the real total.
            assertEquals(body.size.toLong() to (body.size.toLong() as Long?), lastProgress)
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aDigestMismatchDeletesTheFileAndFailsVerification() = runTest(timeout = 60.seconds) {
        val body = ByteArray(2048) { 'B'.code.toByte() }
        val server = FakeHttpServer(listOf(FakeHttpServer.FakeResponse("200 OK", body = body)))
        val dir = newDir()
        try {
            val asset = GithubReleaseAsset(
                name = "app-release.apk",
                downloadUrl = server.url,
                sizeBytes = body.size.toLong(),
                sha256Hex = "00".repeat(32) // wrong on purpose
            )
            val destination = File(dir, "update.apk")

            val outcome = downloader()
                .download(asset, destination) { _, _ -> }

            val failure = outcome as ApkUpdateDownloader.Outcome.Failure
            assertEquals(ApkUpdateDownloader.FailureKind.VERIFY, failure.kind)
            assertFalse("a rejected download must not stay on disk", destination.exists())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSizeMismatchDeletesTheFileAndFailsVerification() = runTest(timeout = 60.seconds) {
        val body = ByteArray(4096) { 'C'.code.toByte() }
        val server = FakeHttpServer(listOf(FakeHttpServer.FakeResponse("200 OK", body = body)))
        val dir = newDir()
        try {
            val asset = GithubReleaseAsset(
                name = "app-release.apk",
                downloadUrl = server.url,
                sizeBytes = 5000L, // release declared more than arrived
                sha256Hex = null
            )
            val destination = File(dir, "update.apk")

            val outcome = downloader()
                .download(asset, destination) { _, _ -> }

            val failure = outcome as ApkUpdateDownloader.Outcome.Failure
            assertEquals(ApkUpdateDownloader.FailureKind.VERIFY, failure.kind)
            assertFalse(destination.exists())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aNetworkFailureSurfacesAsNetworkWithoutDeletingAnything() = runTest(timeout = 60.seconds) {
        val server = FakeHttpServer(listOf(FakeHttpServer.FakeResponse("404 Not Found")))
        val dir = newDir()
        try {
            val asset = GithubReleaseAsset(
                name = "app-release.apk",
                downloadUrl = server.url,
                sizeBytes = 4096L
            )
            val destination = File(dir, "update.apk")

            val outcome = downloader()
                .download(asset, destination) { _, _ -> }

            val failure = outcome as ApkUpdateDownloader.Outcome.Failure
            assertEquals(ApkUpdateDownloader.FailureKind.NETWORK, failure.kind)
            assertFalse(destination.exists())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun anUntrustedUrlIsRejectedBeforeAnyBytesAreFetched() = runTest(timeout = 60.seconds) {
        // Default production policy: the release metadata may only point at this
        // repository's own GitHub release assets. No server is started, so a
        // regression that reaches the network would fail rather than pass quietly.
        val dir = newDir()
        try {
            val asset = GithubReleaseAsset(
                name = "app-release.apk",
                downloadUrl = "https://example.invalid/ForExample25/Linexa/releases/download/v0.2.0/app-release.apk",
                sizeBytes = 4096L
            )
            val destination = File(dir, "update.apk")

            val outcome = HttpApkUpdateDownloader(HttpDistroDownloader())
                .download(asset, destination) { _, _ -> }

            val failure = outcome as ApkUpdateDownloader.Outcome.Failure
            assertEquals(ApkUpdateDownloader.FailureKind.UNTRUSTED, failure.kind)
            assertFalse(destination.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theUnderlyingDownloaderReportsItsSha256InTheSuccess() = runTest(timeout = 60.seconds) {
        // Direct on the seam the APK layer relies on: the streamed SHA-256 is
        // the one compared against GitHub's digest.
        val body = ByteArray(1024) { 'D'.code.toByte() }
        val server = FakeHttpServer(listOf(FakeHttpServer.FakeResponse("200 OK", body = body)))
        val dir = newDir()
        try {
            val result = HttpDistroDownloader().download(
                server.url, File(dir, "raw.bin")
            ) { _, _ -> }
            assertTrue(result is DownloadResult.Success)
            assertEquals(sha256(body), (result as DownloadResult.Success).sha256)
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }
}
