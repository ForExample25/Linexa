package com.fem.linexa.service.distro

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Test-only scripted HTTP server for the download tests: a plain
 * `ServerSocket` on an ephemeral localhost port — no new
 * dependency. Each accepted connection is answered with the NEXT response in
 * the script and then closed, which is exactly the connection-per-attempt
 * pattern [HttpDistroDownloader] produces (it calls `disconnect()` after
 * every attempt, so connections are never reused across attempts).
 *
 * Every response declares `Content-Length` (defaulting to the body size).
 * Setting `declaredLength` LARGER than the body simulates a connection that
 * dies mid-body: the client reads the headers + partial body, then sees a
 * clean FIN — the "connection closed early" short-read the downloader must
 * detect (pre-release audit fix F2) and retry.
 *
 * After writing the response the server sends `Connection: close` and
 * half-closes its output. `Connection: close` matters: without it,
 * `HttpURLConnection.disconnect()` parks a fully-read response's socket in
 * its keepalive pool (closed only after the client's ~5 s idle timeout), so
 * the server cannot observe the client finishing. With it, the client
 * discards the connection immediately — no pool reuse across attempts
 * either. A short bounded drain then confirms EOF where the client closes,
 * and the connection is counted served.
 */
internal class FakeHttpServer(private val responses: List<FakeResponse>) : Thread("fake-http") {

    /** One scripted HTTP response. */
    internal class FakeResponse(
        val statusLine: String,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        /** Content-Length value; defaults to [body] size. Larger than the body = dies mid-body. */
        val declaredLength: Long? = null
    )

    /** A request the client actually sent (header keys lowercased). */
    internal data class RecordedRequest(val methodLine: String, val headers: Map<String, String>)

    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val requestsRaw = mutableListOf<RecordedRequest>()

    /** Requests received so far, in connection order. */
    val requests: List<RecordedRequest>
        get() = synchronized(requestsRaw) { requestsRaw.toList() }

    /** Connections fully served (response written) so far. */
    private val servedCount = java.util.concurrent.atomic.AtomicInteger(0)
    val served: Int get() = servedCount.get()

    val url: String get() = "http://127.0.0.1:${server.localPort}/rootfs.tar.gz"

    init {
        isDaemon = true
        start()
    }

    override fun run() {
        try {
            server.soTimeout = 20_000
            for (response in responses) {
                val socket = server.accept()
                try {
                    socket.soTimeout = 20_000
                    val request = readRequest(socket)
                    synchronized(requestsRaw) { requestsRaw.add(request) }
                    writeResponse(socket, response)
                } finally {
                    socket.close()
                }
            }
        } catch (_: Exception) {
            // Test shut the server down (or an accept timed out) — normal exit.
        } finally {
            runCatching { server.close() }
        }
    }

    fun shutdown() {
        runCatching { server.close() }
    }

    private fun readRequest(socket: Socket): RecordedRequest {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val methodLine = reader.readLine() ?: ""
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        return RecordedRequest(methodLine, headers)
    }

    private fun writeResponse(socket: Socket, response: FakeResponse) {
        val length = response.declaredLength ?: response.body.size.toLong()
        val head = buildString {
            append("HTTP/1.1 ").append(response.statusLine).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            // One connection per response: tells the client not to pool the
            // socket (see the class doc) and to treat the FIN as the end.
            append("Connection: close\r\n")
            response.headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
        // Clean FIN: the client must see EOF where a real server would have
        // kept the connection, never an RST that discards received bytes.
        runCatching { socket.shutdownOutput() }
        // Bounded drain: EOF here means the client closed. With
        // `Connection: close` that arrives promptly; the bound keeps a
        // misbehaving client from wedging the server thread. The response is
        // already fully written and the FIN sent, and the client sends
        // nothing after the request, so closing cannot truncate its read.
        runCatching {
            socket.soTimeout = 250
            socket.getInputStream().read(ByteArray(256))
        }
        servedCount.incrementAndGet()
    }
}
