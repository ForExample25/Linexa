package com.fem.linexa.service.distro

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 06 (OPT-SEC-006): the checksum/directory text fetcher must fail
 * CLOSED when a response body exceeds the 1 MiB safety cap — a misconfigured
 * or malicious checksum URL used to be read whole into memory
 * (`readBytes()`), letting any attacker-controlled endpoint OOM the app.
 */
class SimpleHttpTextFetcherCapTest {

    @Test
    fun aBodyOverTheCapFailsClosedInsteadOfBeingBuffered() = runTest(timeout = 60.seconds) {
        val oversized = ByteArray((SimpleHttpTextFetcher.MAX_BODY_BYTES + 1).toInt()) { 'x'.code.toByte() }
        val server = FakeHttpServer(
            listOf(FakeHttpServer.FakeResponse("200 OK", body = oversized))
        )
        try {
            val outcome = runCatching { SimpleHttpTextFetcher().getText(server.url) }
            val error = outcome.exceptionOrNull()
            assertTrue("expected the cap to throw, got $error", error is IOException)
            assertTrue(
                "the cap message must name the limit, was: ${error?.message}",
                error?.message.orEmpty().contains("cap")
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aBodyAtTheCapIsStillFetched() = runTest(timeout = 60.seconds) {
        val body = ByteArray(SimpleHttpTextFetcher.MAX_BODY_BYTES.toInt()) { 'y'.code.toByte() }
        val server = FakeHttpServer(
            listOf(FakeHttpServer.FakeResponse("200 OK", body = body))
        )
        try {
            val text = SimpleHttpTextFetcher().getText(server.url)
            assertEquals(body.size, text.length)
        } finally {
            server.shutdown()
        }
    }
}
