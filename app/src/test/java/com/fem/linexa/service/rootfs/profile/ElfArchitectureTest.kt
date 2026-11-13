package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §1.146 — `e_machine` decoding, including the cases that must return null.
 *
 * A wrong architecture is worse than no architecture: it would be shown to the
 * user as detected and persisted as the workspace's arch. So every rejection
 * path here is as load-bearing as the happy path.
 */
class ElfArchitectureTest {

    /** Builds an ELF header with the given class/endianness/machine. */
    private fun header(
        machine: Int,
        littleEndian: Boolean = true,
        elfClass: Int = 2,
        magic: ByteArray = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    ): ByteArray {
        val bytes = ByteArray(ElfArchitecture.HEADER_BYTES)
        magic.copyInto(bytes, 0, 0, minOf(magic.size, 4))
        bytes[4] = elfClass.toByte()
        bytes[5] = if (littleEndian) 1 else 2
        val lo = (machine and 0xFF).toByte()
        val hi = ((machine shr 8) and 0xFF).toByte()
        if (littleEndian) {
            bytes[18] = lo
            bytes[19] = hi
        } else {
            bytes[18] = hi
            bytes[19] = lo
        }
        return bytes
    }

    @Test
    fun readsEveryArchitectureLinexaTargets() {
        assertEquals(RootFsProbe.RootFsArch.ARM64_V8A, ElfArchitecture.fromHeader(header(183)))
        assertEquals(RootFsProbe.RootFsArch.ARMEABI_V7A, ElfArchitecture.fromHeader(header(40)))
        assertEquals(RootFsProbe.RootFsArch.X86_64, ElfArchitecture.fromHeader(header(62)))
        assertEquals(RootFsProbe.RootFsArch.X86, ElfArchitecture.fromHeader(header(3)))
    }

    @Test
    fun honoursDeclaredEndiannessInsteadOfAssumingLittle() {
        // Reading aarch64 (183) the wrong way round yields 46848 — a silent
        // wrong answer, which is exactly what must not happen.
        assertEquals(
            RootFsProbe.RootFsArch.ARM64_V8A,
            ElfArchitecture.fromHeader(header(183, littleEndian = false))
        )
    }

    @Test
    fun acceptsBoth32BitAnd64BitClasses() {
        assertEquals(
            RootFsProbe.RootFsArch.ARMEABI_V7A,
            ElfArchitecture.fromHeader(header(40, elfClass = 1))
        )
    }

    @Test
    fun aShellScriptContributesNothingRatherThanAGuess() {
        // `/bin/sh` is very often a script, and Alpine's is a symlink to
        // busybox. Neither has ELF magic.
        val script = "#!/bin/sh\necho hello world padding padding".toByteArray()
        assertNull(ElfArchitecture.fromHeader(script))
    }

    @Test
    fun rejectsATruncatedHeader() {
        val full = header(183)
        assertNull(ElfArchitecture.fromHeader(full, length = ElfArchitecture.HEADER_BYTES - 1))
        assertNull(ElfArchitecture.fromHeader(ByteArray(4)))
        assertNull(ElfArchitecture.fromHeader(ByteArray(0)))
    }

    @Test
    fun rejectsAMalformedClassOrEndiannessByte() {
        assertNull(ElfArchitecture.fromHeader(header(183, elfClass = 0)))
        assertNull(ElfArchitecture.fromHeader(header(183, elfClass = 7)))

        val badEndian = header(183)
        badEndian[5] = 9
        assertNull(ElfArchitecture.fromHeader(badEndian))
    }

    @Test
    fun rejectsBadMagicEvenWhenEverythingElseIsWellFormed() {
        val notElf = header(183, magic = byteArrayOf(0x7F, 'X'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        assertNull(ElfArchitecture.fromHeader(notElf))
    }

    @Test
    fun anUnknownMachineYieldsNullInsteadOfTheNearestGuess() {
        // EM_RISCV (243) is a real machine Linexa has no RootFsArch for.
        assertNull(ElfArchitecture.fromHeader(header(243)))
        assertNull(ElfArchitecture.fromHeader(header(0)))
    }
}
