package com.fem.linexa.service.rootfs

import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/**
 * §1.118 — the container a rootfs archive actually uses, determined from the
 * stream's magic bytes.
 *
 * Detection is by content, never by file name or by the archive's own
 * `linexa.rootfs.compressed_format` declaration: a declaration is metadata
 * (it can be wrong, stale, or hostile) while the magic bytes are the thing the
 * decompressor will actually be handed. The declared value is still carried on
 * [RootFsDescriptor] so a mismatch can be reported as a warning, but it never
 * drives extraction.
 *
 * `TAR`/`ZIP` describe the *container*, not the payload. A ZIP is always a
 * wrapper: no Android-native tar can read it, so the installer unwraps a member
 * archive first. A plain `TAR` is extracted directly.
 */
enum class RootFsArchiveFormat(
    val label: String,
    /** True when the container is a tar stream (possibly compressed). */
    val isTar: Boolean = true,
    /** True when the container is a ZIP wrapper around another archive. */
    val isZip: Boolean = false
) {
    TAR("tar"),
    TAR_GZ("tar.gz"),
    TAR_BZ2("tar.bz2"),
    TAR_XZ("tar.xz"),
    TAR_LZMA("tar.lzma"),
    TAR_ZST("tar.zst"),
    ZIP("zip", isTar = false, isZip = true);

    companion object {
        /** True when [declared] names this format (case/dot tolerant). */
        fun matchesDeclaration(format: RootFsArchiveFormat, declared: String): Boolean {
            val normalized = declared.trim().lowercase().removePrefix(".")
            return normalized in declarationsFor(format)
        }

        private fun declarationsFor(format: RootFsArchiveFormat): Set<String> = when (format) {
            TAR -> setOf("tar", "application/x-tar")
            TAR_GZ -> setOf("tar.gz", "gz", "gzip", "tgz", "application/gzip", "application/x-gzip")
            TAR_BZ2 -> setOf("tar.bz2", "bz2", "bzip2", "tbz", "tbz2", "application/x-bzip2")
            TAR_XZ -> setOf("tar.xz", "xz", "txz", "application/x-xz")
            // LZMA is a distinct container from XZ. Before this split a `xz`
            // declaration matched an LZMA archive and vice versa, which hid
            // exactly the mismatch the warning exists to surface.
            TAR_LZMA -> setOf("tar.lzma", "lzma", "tlz", "application/x-lzma")
            TAR_ZST -> setOf("tar.zst", "zst", "zstd", "tzst", "application/zstd")
            ZIP -> setOf("zip", "application/zip", "application/x-zip-compressed")
        }
    }
}

/**
 * §1.118 — shared magic-byte sniffing + decompressor selection for rootfs
 * archives.
 *
 * Detection covers every container Linexa accepts: plain tar, gzip, bzip2, xz,
 * LZMA-alone, zstd, and ZIP. The header is peeked through a
 * [PushbackInputStream] and pushed back, so the chosen decompressor still sees
 * the magic bytes as part of its stream.
 *
 * The peek window is 512 bytes because a plain tar has no magic at offset 0:
 * its `ustar` marker lives at offset 257 and the header checksum spans the
 * first 512 bytes. Reading only six bytes (as this class used to) made an
 * uncompressed `.tar` indistinguishable from arbitrary data.
 */
object CompressedArchiveStream {

    /** A tar header is 512 bytes; every format signature fits inside one. */
    const val MAGIC_BYTES_TO_PEEK = 512

    data class Opened(val format: RootFsArchiveFormat, val stream: InputStream)

    /** Reason a stream could not be opened as a supported rootfs archive. */
    class UnsupportedFormat(message: String) : Exception(message)

    /**
     * Detects the container of [stream] and returns it wrapped in the matching
     * decompressor (or the raw stream for plain tar / ZIP).
     *
     * @throws UnsupportedFormat when the header matches none of the supported
     *   containers.
     */
    fun open(stream: InputStream): Opened {
        val pushback = PushbackInputStream(stream, MAGIC_BYTES_TO_PEEK)
        val header = ByteArray(MAGIC_BYTES_TO_PEEK)
        val read = readUpTo(pushback, header)
        if (read > 0) pushback.unread(header, 0, read)

        return when (val format = detect(header, read)) {
            RootFsArchiveFormat.TAR -> Opened(format, pushback)
            RootFsArchiveFormat.TAR_ZST -> Opened(format, ZstdCompressorInputStream(pushback))
            RootFsArchiveFormat.TAR_GZ -> Opened(format, GzipCompressorInputStream(pushback))
            RootFsArchiveFormat.TAR_BZ2 -> Opened(format, BZip2CompressorInputStream(pushback))
            RootFsArchiveFormat.TAR_XZ -> Opened(format, XZCompressorInputStream(pushback))
            RootFsArchiveFormat.TAR_LZMA -> Opened(format, LZMACompressorInputStream(pushback))
            RootFsArchiveFormat.ZIP -> Opened(format, pushback)
            null -> throw UnsupportedFormat(
                "Cannot detect archive format from stream header. Expected a tar archive " +
                    "(plain, gzip, bzip2, xz, lzma, zstd) or a ZIP container"
            )
        }
    }

    /** Opens [file] and detects its container without consuming the file. */
    fun detectFile(file: File): RootFsArchiveFormat? =
        file.inputStream().use { stream ->
            val header = ByteArray(MAGIC_BYTES_TO_PEEK)
            val read = readUpTo(stream, header)
            detect(header, read)
        }

    /**
     * Pure magic-byte match over the first [read] bytes of [header].
     *
     * Order matters: compressed containers are checked before plain tar
     * because a compressed stream can contain any bytes at all, and ZIP is
     * checked before tar for the same reason.
     */
    fun detect(header: ByteArray, read: Int): RootFsArchiveFormat? = when {
        read >= 4 && header[0] == 0x28.toByte() && header[1] == 0xB5.toByte() &&
            header[2] == 0x2F.toByte() && header[3] == 0xFD.toByte() -> RootFsArchiveFormat.TAR_ZST

        read >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> RootFsArchiveFormat.TAR_GZ

        read >= 3 && header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() &&
            header[2] == 'h'.code.toByte() &&
            read >= 4 && header[3].toInt().toChar() in '1'..'9' -> RootFsArchiveFormat.TAR_BZ2

        read >= 6 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() &&
            header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() &&
            header[4] == 0x5A.toByte() && header[5] == 0x00.toByte() -> RootFsArchiveFormat.TAR_XZ

        isLzmaAlone(header, read) -> RootFsArchiveFormat.TAR_LZMA

        isZip(header, read) -> RootFsArchiveFormat.ZIP

        looksLikeTar(header, read) -> RootFsArchiveFormat.TAR

        else -> null
    }

    /**
     * LZMA-alone has no fixed magic; its first byte is the lc/lp/pb properties
     * byte (0x5D for the universal defaults) followed by a 32-bit dictionary
     * size. Requiring both keeps a random stream from being called LZMA while
     * still accepting every `.lzma` produced by `xz --format=lzma`.
     */
    private fun isLzmaAlone(header: ByteArray, read: Int): Boolean {
        if (read < 13) return false
        if (header[0] != 0x5D.toByte()) return false
        val dict = (header[1].toLong() and 0xFF) or
            ((header[2].toLong() and 0xFF) shl 8) or
            ((header[3].toLong() and 0xFF) shl 16) or
            ((header[4].toLong() and 0xFF) shl 24)
        return dict in 4096L..(1L shl 30)
    }

    private fun isZip(header: ByteArray, read: Int): Boolean =
        read >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            ((header[2] == 3.toByte() && header[3] == 4.toByte()) ||
                (header[2] == 5.toByte() && header[3] == 6.toByte()) ||
                (header[2] == 7.toByte() && header[3] == 8.toByte()))

    /**
     * A plain tar has no leading magic. It is recognized by the POSIX `ustar`
     * marker at offset 257, or — for pre-POSIX archives — by a valid header
     * checksum. The checksum is the structural proof: it makes a false
     * positive on arbitrary data vanishingly unlikely.
     */
    private fun looksLikeTar(header: ByteArray, read: Int): Boolean {
        if (read < 512) return false
        if (matchesAscii(header, 257, "ustar")) return true
        return hasValidTarChecksum(header)
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset + text.length > bytes.size) return false
        return text.indices.all { bytes[offset + it] == text[it].code.toByte() }
    }

    private fun hasValidTarChecksum(header: ByteArray): Boolean {
        val stored = readOctal(header, 148, 8) ?: return false
        if (stored <= 0) return false
        var unsigned = 0L
        var signed = 0L
        for (i in 0 until 512) {
            // The checksum field itself is treated as spaces (0x20).
            val value = if (i in 148 until 156) 0x20 else (header[i].toInt() and 0xFF)
            unsigned += value
            signed += if (i in 148 until 156) 0x20 else header[i].toLong()
        }
        return stored == unsigned || stored == signed
    }

    private fun readOctal(bytes: ByteArray, offset: Int, length: Int): Long? {
        var value = 0L
        var seen = false
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0 || b == 0x20 -> {
                    if (seen) return value
                }
                b in 0x30..0x37 -> {
                    value = value * 8 + (b - 0x30)
                    seen = true
                }
                else -> return null
            }
        }
        return value.takeIf { seen }
    }

    /** InputStream.read may legally return short; fill the probe window. */
    private fun readUpTo(stream: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val n = stream.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            if (n == 0) continue
            filled += n
        }
        return filled
    }
}
