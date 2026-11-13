package com.fem.linexa.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §1.147 — entry names that are **not clean UTF-8**, and names delivered through
 * a **PAX or GNU extended header** rather than the 100-byte `ustar` field.
 *
 * ## Why this class exists
 *
 * This was previously never examined and never tested: every fixture in the
 * suite was clean UTF-8 delivered in the ordinary header field. That mattered
 * because the root cause was a validator misreading the *bytes* of a name — so
 * "what does the validator see when the name is not what it appears to be" was
 * exactly the unasked question.
 *
 * Two distinct concerns, both settled here by observation rather than by
 * reasoning about the tar spec:
 *
 *  1. **Decoding.** A tar entry name is bytes, not text. Commons-compress must
 *     hand the validator *something*, and what it hands over decides what the
 *     validator can possibly conclude. Verified below: each raw byte survives as
 *     one char, so no name is lost, truncated, or rejected for being unreadable
 *     — and, critically, no byte sequence is *folded into* a `/` or a `.` on the
 *     way in.
 *  2. **Delivery.** PAX (`path=`) and GNU longname records override the header
 *     name entirely. If the validator ran on the header field while extraction
 *     honoured the override, every check would be bypassable by construction.
 *     Verified below: commons-compress resolves the override before
 *     `entry.name` is read, so the validator judges the name that will actually
 *     be used.
 *
 * ## Findings, stated as they were observed
 *
 * No defect was found. Three properties worth pinning, because each one *could*
 * have been a bypass and is not:
 *
 *  - An **overlong UTF-8 encoding** of `/` (`C0 AF`) or of `.` (`C0 AE`) does not
 *    decode to `/` or `.`. It stays two ordinary characters, so a segment
 *    separator and a traversal construct cannot be smuggled past checks that
 *    match on the real ASCII bytes. This is the classic Unicode-normalization
 *    bypass and it does not apply here.
 *  - A **PAX `path=` override** carrying traversal is surfaced as `entry.name`
 *    and rejected, even though the accompanying `ustar` header field is
 *    innocent (`innocent`).
 *  - A **non-UTF-8 name is accepted**, which is correct and deliberate: Linux
 *    filenames are byte strings and a Latin-1 or otherwise invalid-UTF-8 name is
 *    a legal file, not an attack. Rejecting it would be the §5.1 mistake in a new
 *    costume — inferring hostility from an encoding property that carries no
 *    structural meaning.
 *
 * What this class does **not** establish: how PRoot and GNU tar write these names
 * to disk on a device (audit §20.3 item 3, still open). It covers the host-side
 * judgement only.
 */
class ArchiveEntryNameEncodingTest {

    private lateinit var validator: RealArchiveValidator

    @Before
    fun setUp() {
        validator = RealArchiveValidator()
    }

    // ── decoding: every byte survives, nothing folds into a separator ──────

    @Test
    fun `a latin-1 name is accepted rather than rejected for being invalid utf-8`() {
        // "etc/caf<0xE9>" — Latin-1 'é', not valid UTF-8. A real filename on any
        // system whose locale predates UTF-8, and nothing about it is hostile.
        val name = decodedName(byteArrayOf(0x65, 0x74, 0x63, 0x2F, 0x63, 0x61, 0x66, 0xE9.toByte()))

        assertEquals("etc/caf\u00E9", name)
        assertNull("a non-UTF-8 name is a legal filename, not an attack", validator.checkEntry(name))
    }

    @Test
    fun `invalid utf-8 byte sequences reach the validator intact and are accepted`() {
        // A lone continuation byte and a truncated lead byte: both malformed
        // UTF-8, both legal filename bytes. The point is that they arrive as
        // themselves — a decoder that dropped or replaced them could turn one
        // name into another.
        val loneContinuation = decodedName(byteArrayOf(0x65, 0x74, 0x63, 0x2F, 0x80.toByte(), 0x81.toByte()))
        assertEquals(listOf(0x65, 0x74, 0x63, 0x2F, 0x80, 0x81), loneContinuation.map { it.code })
        assertNull(validator.checkEntry(loneContinuation))

        val truncatedLead = decodedName(byteArrayOf(0x65, 0x74, 0x63, 0x2F, 0xC3.toByte(), 0x2F, 0x78))
        assertEquals(listOf(0x65, 0x74, 0x63, 0x2F, 0xC3, 0x2F, 0x78), truncatedLead.map { it.code })
        assertNull(validator.checkEntry(truncatedLead))
    }

    @Test
    fun `an overlong utf-8 encoding of a slash does not become a path separator`() {
        // C0 AF is the overlong (illegal) encoding of '/'. A decoder that
        // accepted it would produce "etc/x" from bytes that a POSIX kernel reads
        // as a single-segment name, and every '/'-splitting check downstream
        // would then disagree with the filesystem.
        val name = decodedName(byteArrayOf(0x65, 0x74, 0x63, 0xC0.toByte(), 0xAF.toByte(), 0x78))

        assertFalse("the overlong sequence must not fold into '/'", name.contains('/'))
        assertEquals(1, name.split('/').size)
        assertNull(validator.checkEntry(name))
    }

    @Test
    fun `an overlong utf-8 encoding of dots does not become a traversal segment`() {
        // C0 AE is the overlong encoding of '.'. Twice, followed by a real '/',
        // is an attempt to spell "../" without using the ASCII bytes that the
        // segment comparison looks for.
        val name = decodedName(
            byteArrayOf(0xC0.toByte(), 0xAE.toByte(), 0xC0.toByte(), 0xAE.toByte(), 0x2F, 0x65, 0x74, 0x63)
        )

        assertFalse("the overlong dots must not fold into '..'", name.startsWith(".."))
        assertFalse("no segment may equal '..'", name.split('/').any { it == ".." })
        // Accepted because it names a directory whose name is two odd bytes —
        // strictly inside the extraction root, whatever it looks like.
        assertNull(validator.checkEntry(name))
    }

    @Test
    fun `unicode lookalikes for slash and dot are ordinary filename characters`() {
        // DIVISION SLASH, FULLWIDTH SOLIDUS, FULLWIDTH FULL STOP, ONE DOT
        // LEADER. None is a path separator to a POSIX kernel, so none may be
        // treated as one here — in either direction. Treating them as separators
        // would reject legal names; folding them to ASCII would be a bypass.
        listOf(
            "etc\u2215passwd",
            "etc\uFF0Fpasswd",
            "\uFF0E\uFF0E/etc",
            "\u2024\u2024/etc"
        ).forEach { name ->
            assertNull("'$name' is a legal filename", validator.checkEntry(name))
            assertFalse("no lookalike may be read as '..'", name.split('/').any { it == ".." })
        }
    }

    @Test
    fun `a valid multibyte utf-8 name is accepted`() {
        val name = decodedName("etc/\u65E5\u672C\u8A9E.conf".toByteArray(Charsets.UTF_8))
        assertNull(validator.checkEntry(name))
    }

    @Test
    fun `a null byte is still rejected wherever it is decoded from`() {
        // The one byte that is not a legal filename byte. Pinned here because the
        // decoding tests above establish that bytes pass through unchanged, which
        // is only safe as long as this remains true.
        assertEquals(
            "Archive entry contains null byte",
            reasonFor(validator.checkEntry("etc/pas\u0000swd"))
        )
    }

    // ── delivery: extended headers do not bypass the validator ─────────────

    @Test
    fun `a pax long name round-trips exactly including backslashes`() {
        // Over 100 bytes, so the name cannot live in the ustar field and PAX
        // carries it. It also carries the escaped-unit shape from the audit's
        // root cause, so this pins the two concerns together: a long name and a
        // backslash-bearing name in one entry.
        val original = "etc/" + "d".repeat(150) + "/system-systemd\\x2dmute\\x2dconsole.slice"
        val surfaced = namesFrom(tar(TarArchiveOutputStream.LONGFILE_POSIX, original))

        assertEquals(listOf(original), surfaced)
        assertNull("a long escaped-unit name must be accepted", validator.checkEntry(original))
    }

    @Test
    fun `a pax path override carrying traversal is what the validator judges`() {
        // The attack this test exists for: the ustar header field says
        // "innocent" while a PAX record sets path=../../../etc/passwd. If the
        // validator saw the header field, extraction would honour the override
        // and every check above would be bypassable by construction.
        val evil = "../../../etc/passwd"
        val archive = paxOverride(headerName = "innocent", record = "path=$evil")

        val surfaced = namesFrom(archive)

        assertEquals("the override must be what reaches the validator", listOf(evil), surfaced)
        assertEquals(
            "Archive entry contains parent directory traversal",
            reasonFor(validator.checkEntry(surfaced.single()))
        )
    }

    @Test
    fun `a gnu longname record carrying traversal is also judged on the real name`() {
        // GNU's own long-name mechanism ('L' entry) predates PAX and is still
        // emitted by default by some tars. Same property required.
        val evil = "../../../etc/passwd"
        val surfaced = namesFrom(tar(TarArchiveOutputStream.LONGFILE_GNU, evil))

        assertEquals(listOf(evil), surfaced)
        assertEquals(
            "Archive entry contains parent directory traversal",
            reasonFor(validator.checkEntry(surfaced.single()))
        )
    }

    @Test
    fun `a pax linkpath override is surfaced as the symlink target`() {
        // linkpath= overrides a symlink's target the way path= overrides its
        // name, so the validator must judge the override here too.
        //
        // The verdict is *accept*, and that is correct rather than lenient: an
        // absolute target anchors at the rootfs root under PRoot, so
        // '/etc/shadow' resolves to <rootfs>/etc/shadow — inside the boundary.
        // Alpine's busybox tree is built almost entirely from absolute symlinks
        // (see checkSymlinkEscape's kdoc and the §1.35 finding it cites), and the
        // pre-§1.35 blanket refusal of absolute targets is what broke those
        // imports. What must stay rejected is a target that *escapes*, which the
        // next test covers through the same override path.
        val archive = paxOverride(
            headerName = "link",
            record = "linkpath=/etc/shadow",
            symlinkTarget = "harmless"
        )

        TarArchiveInputStream(ByteArrayInputStream(archive)).use { tar ->
            val entry = tar.nextEntry!!
            assertTrue(entry.isSymbolicLink)
            assertEquals("the override must reach the validator", "/etc/shadow", entry.linkName)
            assertNull(
                "an absolute target anchors at the rootfs root and stays inside it",
                validator.checkEntry(entry.name, entry.isSymbolicLink, entry.linkName)
            )
        }
    }

    @Test
    fun `a pax linkpath override that escapes the rootfs is rejected`() {
        // The negative half of the case above: same delivery mechanism, target
        // that climbs out. Without this, the acceptance above would read as a
        // hole rather than as a decision.
        val archive = paxOverride(
            headerName = "link",
            record = "linkpath=../../../../etc/shadow",
            symlinkTarget = "harmless"
        )

        TarArchiveInputStream(ByteArrayInputStream(archive)).use { tar ->
            val entry = tar.nextEntry!!
            assertEquals("../../../../etc/shadow", entry.linkName)
            assertNotNull(
                "a relative target climbing out of the rootfs must be rejected",
                validator.checkEntry(entry.name, entry.isSymbolicLink, entry.linkName)
            )
        }
    }

    @Test
    fun `a pax override cannot smuggle a null byte past the validator`() {
        // PAX records are length-prefixed, so unlike the 100-byte ustar field
        // they *can* carry a NUL. It must still be caught.
        val archive = paxOverride(headerName = "innocent", record = "path=etc/pas\u0000swd")

        val surfaced = namesFrom(archive).single()
        assertTrue("the NUL must survive into the name", surfaced.contains('\u0000'))
        assertEquals(
            "Archive entry contains null byte",
            reasonFor(validator.checkEntry(surfaced))
        )
    }

    @Test
    fun `a pax linkpath override cannot smuggle a null byte into a symlink target`() {
        // The target side of the case above. A NUL in a target truncates the
        // path for any C API that consumes it, so the byte the host validated
        // and the byte the kernel acts on would differ.
        val archive = paxOverride(
            headerName = "link",
            record = "linkpath=/etc/shadow\u0000/harmless",
            symlinkTarget = "harmless"
        )

        TarArchiveInputStream(ByteArrayInputStream(archive)).use { tar ->
            val entry = tar.nextEntry!!
            assertTrue("the NUL must survive into the target", entry.linkName.contains('\u0000'))
            assertEquals(
                "Symlink target contains null byte",
                reasonFor(validator.checkEntry(entry.name, entry.isSymbolicLink, entry.linkName))
            )
        }
    }

    // ── fixture plumbing ─────────────────────────────────────────────────

    /**
     * Writes [nameBytes] into a tar header and reads back what
     * commons-compress decodes — the exact value production hands the validator
     * (`StagedRootFsInstaller.scanEntries` reads `entry.name` from a plain
     * [TarArchiveInputStream], as this does).
     *
     * ISO-8859-1 is used only to *carry* arbitrary bytes into a String
     * one-byte-per-char on the way in; it is not a claim about the encoding.
     */
    private fun decodedName(nameBytes: ByteArray): String {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            val entry = TarArchiveEntry(String(nameBytes, Charsets.ISO_8859_1))
            entry.size = 1
            tar.putArchiveEntry(entry)
            tar.write("x".toByteArray())
            tar.closeArchiveEntry()
        }
        return namesFrom(out.toByteArray()).single()
    }

    /** A single-entry archive using [longFileMode] for the name. */
    private fun tar(longFileMode: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(longFileMode)
            val entry = TarArchiveEntry(name)
            entry.size = 1
            tar.putArchiveEntry(entry)
            tar.write("x".toByteArray())
            tar.closeArchiveEntry()
        }
        return out.toByteArray()
    }

    /**
     * An `x`-typeflag PAX extended header carrying [record], followed by the
     * entry it applies to — whose own header name is [headerName], deliberately
     * innocent, so the test can tell which of the two the validator judged.
     */
    private fun paxOverride(
        headerName: String,
        record: String,
        symlinkTarget: String? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            val payload = paxRecord(record)
            val header = TarArchiveEntry(
                "PaxHeaders.0/$headerName",
                TarArchiveEntry.LF_PAX_EXTENDED_HEADER_LC
            )
            header.size = payload.size.toLong()
            tar.putArchiveEntry(header)
            tar.write(payload)
            tar.closeArchiveEntry()

            val entry = if (symlinkTarget != null) {
                TarArchiveEntry(headerName, TarArchiveEntry.LF_SYMLINK).apply { linkName = symlinkTarget }
            } else {
                TarArchiveEntry(headerName).apply { size = 1 }
            }
            tar.putArchiveEntry(entry)
            if (symlinkTarget == null) tar.write("x".toByteArray())
            tar.closeArchiveEntry()
        }
        return out.toByteArray()
    }

    /**
     * A PAX record: `"<length> <keyword>=<value>\n"`, where `length` counts its
     * own digits. Solved by iteration because adding a digit can change the
     * length (the 9→10 and 99→100 boundaries).
     */
    private fun paxRecord(keywordAndValue: String): ByteArray {
        val body = "$keywordAndValue\n"
        var length = body.length + 2
        while ("$length $body".length != length) {
            length = "$length $body".length
        }
        return "$length $body".toByteArray(Charsets.ISO_8859_1)
    }

    /** Every entry name commons-compress surfaces from [archive], in order. */
    private fun namesFrom(archive: ByteArray): List<String> {
        val names = mutableListOf<String>()
        TarArchiveInputStream(ByteArrayInputStream(archive)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                names += entry.name
                entry = tar.nextEntry
            }
        }
        return names
    }

    private fun reasonFor(result: ArchiveValidator.ValidationResult?): String? =
        (result as? ArchiveValidator.ValidationResult.Invalid)?.reason
}
