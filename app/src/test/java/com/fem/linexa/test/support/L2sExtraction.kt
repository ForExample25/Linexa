package com.fem.linexa.test.support

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/**
 * §1.168 — extracts a hardlink-bearing tar archive the way
 * `proot --link2symlink tar -xf … -C <staging>` does on a device, so
 * [com.fem.linexa.service.installer.L2sLinkRewriter] can be tested against the
 * artifact shape it exists to repair.
 *
 * ## Why this emulation exists at all
 *
 * The real Pass 2 runs an aarch64 `libproot.so` plus the bundled GNU tar. A JVM
 * unit test has neither (and `src/test` has no Android runtime), while the
 * artifact shape is the whole subject of the test. So the shape is reproduced
 * here from the output observed on-device, and
 * [com.fem.linexa.service.installer.L2sLinkRewriterRealProotTest] cross-checks
 * this emulation against a real `proot --link2symlink tar` run wherever such a
 * pair is available, so the emulation cannot silently drift away from the thing
 * it stands in for.
 *
 * ## The observed shape
 *
 * `link(2)` is refused outright in app-private storage, so PRoot emulates each
 * hardlink family — the data-carrying member plus every hardlink to it — with
 * three kinds of entry. For `./usr/bin/base` plus hardlinks `l1`, `l2`, `l3`:
 *
 * ```
 * .l2s.base0001        -> /abs/staging/usr/bin/.l2s.base0001.0004   (sentinel)
 * .l2s.base0001.0004                                                (data file)
 * base                 -> /abs/staging/usr/bin/.l2s.base0001        (member)
 * l1                   -> /abs/staging/usr/bin/.l2s.base0001        (member)
 * l2                   -> /abs/staging/usr/bin/.l2s.base0001        (member)
 * l3                   -> /abs/staging/usr/bin/.l2s.base0001        (member)
 * ```
 *
 * Two properties matter and are reproduced exactly: every target is an
 * **absolute path containing the staging directory**, and the original member
 * name (`base`) becomes a symlink too rather than staying a regular file. A
 * member in a *different* directory than the data file (Debian ships none, but
 * nothing forbids it) still points at the sentinel beside the data file, which
 * is what makes the rewritten target need a `..` segment.
 *
 * The trailing counters (`0001`, `.0004`) are cosmetic here and are chosen to
 * match what was observed (`.0002` for a two-name family, `.0004` for a
 * four-name one). The production rewriter matches only the `.l2s.` prefix and
 * never parses them — see its kdoc.
 */
object L2sExtraction {

    /** PRoot's emulation prefix, mirrored from the observed on-device names. */
    const val L2S_PREFIX = ".l2s."

    /**
     * A tar member for [archive] — a regular file, a directory, a symlink, or a
     * hardlink to an earlier regular file.
     */
    sealed interface Member {
        val name: String

        data class Dir(override val name: String) : Member

        data class Regular(
            override val name: String,
            val content: String,
            val mode: Int = 0b111_101_101 // 0755
        ) : Member

        data class Symlink(override val name: String, val target: String) : Member

        /** A tar hardlink entry (`LF_LINK`) whose [target] is another member's name. */
        data class Hardlink(override val name: String, val target: String) : Member
    }

    /** Builds a real uncompressed tar carrying [members], in the given order. */
    fun archive(vararg members: Member): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (member in members) {
                when (member) {
                    is Member.Dir -> tar.putArchiveEntry(TarArchiveEntry(member.name, TarArchiveEntry.LF_DIR))
                        .also { tar.closeArchiveEntry() }

                    is Member.Regular -> {
                        val bytes = member.content.toByteArray()
                        val entry = TarArchiveEntry(member.name)
                        entry.size = bytes.size.toLong()
                        entry.mode = member.mode
                        tar.putArchiveEntry(entry)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }

                    is Member.Symlink -> {
                        val entry = TarArchiveEntry(member.name, TarArchiveEntry.LF_SYMLINK)
                        entry.linkName = member.target
                        tar.putArchiveEntry(entry)
                        tar.closeArchiveEntry()
                    }

                    is Member.Hardlink -> {
                        val entry = TarArchiveEntry(member.name, TarArchiveEntry.LF_LINK)
                        entry.linkName = member.target
                        tar.putArchiveEntry(entry)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Extracts [archiveBytes] into [staging] with PRoot's `--link2symlink`
     * hardlink emulation — the exact state Pass 2 leaves behind.
     *
     * Two phases, because a tar hardlink entry refers to a member extracted
     * earlier: everything else lands first, then each hardlink family converts
     * its already-written data file into the sentinel/data-file/member trio.
     */
    fun extractWithLink2Symlink(archiveBytes: ByteArray, staging: File) {
        require(staging.isDirectory) { "staging must exist: $staging" }
        val hardlinks = LinkedHashMap<String, MutableList<String>>()

        TarArchiveInputStream(archiveBytes.inputStream()).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val relative = normalize(entry.name)
                val target = File(staging, relative)
                when {
                    entry.isDirectory -> target.mkdirs()

                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
                    }

                    entry.isLink -> hardlinks
                        .getOrPut(normalize(entry.linkName)) { mutableListOf() }
                        .add(relative)

                    else -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { tar.copyTo(it) }
                        target.setExecutable((entry.mode and 0b001_000_000) != 0, false)
                    }
                }
                entry = tar.nextEntry
            }
        }

        for ((dataRelative, members) in hardlinks) {
            emulateFamily(staging, dataRelative, members)
        }
    }

    /**
     * Converts one hardlink family into the observed trio. [dataRelative] is the
     * regular file already on disk; [members] are the hardlink names.
     */
    private fun emulateFamily(staging: File, dataRelative: String, members: List<String>) {
        val data = File(staging, dataRelative)
        require(data.isFile) { "hardlink target was not extracted first: $dataRelative" }

        // Counter width and value mirror the observed names: a family of N total
        // names (data member + N-1 hardlinks) got `.000N`.
        val familySize = members.size + 1
        val sentinelName = "$L2S_PREFIX${data.name}0001"
        val dataName = "$sentinelName.%04d".format(familySize)

        val dir = data.parentFile
        val dataFile = File(dir, dataName)
        require(data.renameTo(dataFile)) { "could not stage data file $dataName" }

        val sentinel = File(dir, sentinelName)
        // Absolute target — the defect under test.
        Files.createSymbolicLink(sentinel.toPath(), dataFile.absoluteFile.toPath())

        for (name in listOf(dataRelative) + members) {
            val link = File(staging, name)
            link.parentFile?.mkdirs()
            Files.deleteIfExists(link.toPath())
            Files.createSymbolicLink(link.toPath(), sentinel.absoluteFile.toPath())
        }
    }

    /** Strips the leading `./` tar convention; never used as a security check. */
    private fun normalize(name: String): String = name.removePrefix("./").trimEnd('/')
}
