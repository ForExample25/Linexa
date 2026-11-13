package com.fem.linexa.service.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

/**
 * §1.118 — magic-byte format detection, extracted from
 * `StagedRootFsInstaller.resolveDecompressor` so it is testable at all (it
 * previously existed only as a private method behind a content-provider read).
 *
 * The behaviour that matters and had no coverage: the header bytes must still
 * reach the decompressor. A pushback bug there would corrupt every install
 * while the format detection itself looked correct.
 */
class CompressedArchiveStreamTest {

    private val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val gzipMagic = byteArrayOf(0x1F, 0x8B.toByte())
    private val xzMagic = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)

    @Test
    fun detectsEachSupportedFormatFromItsMagicBytes() {
        assertEquals(RootFsArchiveFormat.TAR_ZST, CompressedArchiveStream.detect(zstdMagic, zstdMagic.size))
        assertEquals(RootFsArchiveFormat.TAR_GZ, CompressedArchiveStream.detect(gzipMagic, gzipMagic.size))
        assertEquals(RootFsArchiveFormat.TAR_XZ, CompressedArchiveStream.detect(xzMagic, xzMagic.size))
    }

    @Test
    fun rejectsAnUnknownHeader() {
        assertNull(CompressedArchiveStream.detect("not an archive".toByteArray(), 14))
    }

    @Test
    fun rejectsATruncatedHeaderRatherThanGuessing() {
        // Two bytes of XZ's magic are not enough to call it XZ.
        assertNull(CompressedArchiveStream.detect(xzMagic, 2))
        // One byte of gzip's is not gzip either.
        assertNull(CompressedArchiveStream.detect(gzipMagic, 1))
    }

    @Test
    fun rejectsAnEmptyStream() {
        assertNull(CompressedArchiveStream.detect(ByteArray(0), 0))
    }

    @Test
    fun openThrowsUnsupportedFormatForANonArchive() {
        val e = assertThrows(CompressedArchiveStream.UnsupportedFormat::class.java) {
            CompressedArchiveStream.open(ByteArrayInputStream("hello".toByteArray()))
        }
        assertTrue(e.message!!.contains("tar archive"))
    }

    @Test
    fun openPushesTheHeaderBackSoTheDecompressorSeesAWholeStream() {
        // The regression guard: if `unread` were dropped, detection would still
        // report TAR_GZ and every extraction would fail on a corrupt stream.
        val payload = "the quick brown fox".repeat(100).toByteArray()
        val compressed = ByteArrayOutputStream().also { sink ->
            GzipCompressorOutputStream(sink).use { it.write(payload) }
        }.toByteArray()

        val opened = CompressedArchiveStream.open(ByteArrayInputStream(compressed))
        assertEquals(RootFsArchiveFormat.TAR_GZ, opened.format)
        assertTrue(opened.stream.readBytes().contentEquals(payload))
    }

    @Test
    fun declarationMatchingIsToleranctOfSpellingButNotOfTheWrongFormat() {
        listOf("tar.gz", "gz", "GZIP", ".tgz").forEach {
            assertTrue("'$it' should match TAR_GZ", RootFsArchiveFormat.matchesDeclaration(RootFsArchiveFormat.TAR_GZ, it))
        }
        listOf("tar.xz", "xz", "txz").forEach {
            assertTrue("'$it' should match TAR_XZ", RootFsArchiveFormat.matchesDeclaration(RootFsArchiveFormat.TAR_XZ, it))
        }
        listOf("tar.lzma", "lzma", "tlz").forEach {
            assertTrue("'$it' should match TAR_LZMA", RootFsArchiveFormat.matchesDeclaration(RootFsArchiveFormat.TAR_LZMA, it))
        }
        assertTrue(!RootFsArchiveFormat.matchesDeclaration(RootFsArchiveFormat.TAR_XZ, "tar.lzma"))
        assertTrue(!RootFsArchiveFormat.matchesDeclaration(RootFsArchiveFormat.TAR_LZMA, "xz"))
        assertTrue(!RootFsArchiveFormat.matchesDeclaration(RootFsArchiveFormat.TAR_GZ, "tar.zst"))
        assertTrue(!RootFsArchiveFormat.matchesDeclaration(RootFsArchiveFormat.TAR_ZST, "tar.xz"))
    }
}
