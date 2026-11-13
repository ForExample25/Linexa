package com.fem.linexa.domain.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [RootfsMirrorResolver] (§25). The invariant under test is
 * the whole point of the feature: a mirror may replace the *download* URL,
 * but the checksum sidecar and its format must always keep coming from
 * the catalog — and any override that is not a clean http/https URL must
 * be ignored entirely (fail-open to the catalog default).
 */
class RootfsMirrorResolverTest {

    private val manifest = DistroManifest(
        id = "alpine",
        displayName = "Alpine Linux",
        version = "3.24.1",
        avatarLetter = "A",
        avatarColorHex = "#0D597F",
        approxDownloadSizeLabel = "~4 MB",
        source = DistroSource.Direct(
            downloadUrl = "https://example.com/a.tar.gz",
            checksumUrl = "https://example.com/a.tar.gz.sha256",
            checksumFormat = ChecksumFormat.SINGLE_HASH_LINE
        )
    )

    private val resolved = ResolvedDownload(
        downloadUrl = "https://example.com/a.tar.gz",
        checksumUrl = "https://example.com/a.tar.gz.sha256",
        checksumFormat = ChecksumFormat.SINGLE_HASH_LINE
    )

    @Test
    fun nullOverrideReturnsResolvedUnchanged() {
        val out = RootfsMirrorResolver.resolve(manifest, resolved, null)
        assertSame(resolved, out)
        assertEquals("https://example.com/a.tar.gz", out.downloadUrl)
        assertEquals("https://example.com/a.tar.gz.sha256", out.checksumUrl)
        assertEquals(ChecksumFormat.SINGLE_HASH_LINE, out.checksumFormat)
    }

    @Test
    fun validOverrideReplacesOnlyTheDownloadUrl() {
        val out = RootfsMirrorResolver.resolve(manifest, resolved, "https://mirror.example.com/a.tar.gz")
        assertEquals("https://mirror.example.com/a.tar.gz", out.downloadUrl)
        // Integrity is never weakened: the checksum still comes from the
        // distribution's official sidecar, so a mirror serving different
        // bytes fails validation.
        assertEquals("https://example.com/a.tar.gz.sha256", out.checksumUrl)
        assertEquals(ChecksumFormat.SINGLE_HASH_LINE, out.checksumFormat)
    }

    @Test
    fun invalidOverridesFailOpenToTheCatalogDefault() {
        for (bad in listOf("ftp://x", "has space", "https://bad'quote", "not-a-url")) {
            val out = RootfsMirrorResolver.resolve(manifest, resolved, bad)
            assertSame("override '$bad' must be ignored", resolved, out)
            assertEquals("https://example.com/a.tar.gz", out.downloadUrl)
        }
    }

    @Test
    fun blankOverrideIsTreatedAsNoOverride() {
        for (blank in listOf("", "   ", "\t\n")) {
            assertSame(resolved, RootfsMirrorResolver.resolve(manifest, resolved, blank))
        }
    }

    @Test
    fun overrideIsTrimmedBeforeUse() {
        val out = RootfsMirrorResolver.resolve(manifest, resolved, "  https://mirror.example.com/a.tar.gz  ")
        assertEquals("https://mirror.example.com/a.tar.gz", out.downloadUrl)
    }

    @Test
    fun validOverrideUrls() {
        assertTrue(RootfsMirrorResolver.isValidOverrideUrl("https://mirror.example.com/a.tar.gz"))
        assertTrue(RootfsMirrorResolver.isValidOverrideUrl("http://mirror.lan:8080/dir/file.tar.gz"))
    }

    @Test
    fun invalidOverrideUrls() {
        assertFalse(RootfsMirrorResolver.isValidOverrideUrl("ftp://x"))
        assertFalse(RootfsMirrorResolver.isValidOverrideUrl("has space"))
        assertFalse(RootfsMirrorResolver.isValidOverrideUrl(""))
        assertFalse(RootfsMirrorResolver.isValidOverrideUrl("https://")) // no host
        assertFalse(RootfsMirrorResolver.isValidOverrideUrl("https://x'y")) // quote
        assertFalse(RootfsMirrorResolver.isValidOverrideUrl("relative/path.tar.gz")) // no scheme
    }
}
