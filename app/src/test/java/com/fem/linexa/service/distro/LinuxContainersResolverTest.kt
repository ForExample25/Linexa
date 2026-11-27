package com.fem.linexa.service.distro

import com.fem.linexa.domain.distro.DistroCatalog
import com.fem.linexa.domain.distro.DistroResolverIds
import com.fem.linexa.domain.distro.DistroSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance finding (2026-11-26): the live images.linuxcontainers.org
 * autoindex now percent-encodes the colon in build directory names
 * (`href="20261116_22%3A14/"`). The previous `HH:MM`-only pattern matched
 * nothing, so a linuxcontainers.org resolve would fail with "No build
 * directories found" on a directory that plainly exists — the same class of
 * breakage that made the old static Debian URL return 404.
 */
class LinuxContainersResolverTest {

    private val resolver = LinuxContainersResolver(
        com.fem.linexa.service.distro.SimpleHttpTextFetcher()
    )

    @Test
    fun `plain colon timestamps are parsed`() {
        val html = """
            <html><head><title>Index</title></head><body><pre>
            <a href="../">../</a>
            <a href="20240724_20:33/">20240724_20:33/</a>
            <a href="20250101_09:05/">20250101_09:05/</a>
            </pre></body></html>
        """.trimIndent()
        assertEquals("20250101_09:05", resolver.latestBuildTimestamp(html))
    }

    @Test
    fun `percent-encoded timestamps are parsed and the newest wins`() {
        val html = """
            <html><body><pre>
            <a href="20261116_22%3A14/">20261116_22:14/</a>
            <a href="20260102_03%3A59/">20260102_03:59/</a>
            </pre></body></html>
        """.trimIndent()
        assertEquals("20261116_22%3A14", resolver.latestBuildTimestamp(html))
    }

    @Test
    fun `no build directories yields null`() {
        assertNull(resolver.latestBuildTimestamp("<html><body>empty</body></html>"))
    }

    @Test
    fun `the bundled Debian entry resolves through the verified image server`() {
        val debian = DistroCatalog.findById("debian")!!
        val source = debian.source as DistroSource.Resolved
        assertEquals(DistroResolverIds.LINUX_CONTAINERS, source.resolverId)
        assertEquals("debian", source.distro)
        assertEquals("bookworm", source.release)
        assertEquals("arm64", source.arch)
        assertTrue(
            "the LXC path verifies against SHA256SUMS, unlike the stale static URL it replaced",
            source.resolverId == DistroResolverIds.LINUX_CONTAINERS
        )
    }
}
