package com.fem.linexa.service.rootfs.profile

import com.fem.linexa.service.installer.RootFsProbe

/**
 * §1.146 — architecture detection from an ELF header's `e_machine` field.
 *
 * ## Why a binary probe was needed
 *
 * Pre-§1.146 architecture detection matched substrings of tar entry names
 * against known toolchain paths (`lib/aarch64-linux-gnu/`,
 * `lib/ld-musl-aarch64.so.1`). That works for the distributions those paths
 * came from and fails silently elsewhere:
 *
 *  - Debian/Ubuntu use multiarch directories → matched.
 *  - Alpine ships `lib/ld-musl-aarch64.so.1` → matched.
 *  - **Arch Linux ARM ships neither.** Its loader is `usr/lib/ld-linux-aarch64.so.1`
 *    in some builds but the published aarch64 rootfs carries no such member at
 *    all; the only `aarch64` substring anywhere in its file list is
 *    `etc/mkinitcpio.d/linux-aarch64.preset`, a kernel preset file whose name
 *    is a coincidence, not a contract. Matching on it would be guessing.
 *
 * So Arch reported `RootFsArch.UNKNOWN`, and because
 * [RootFsProbe.detect] requires *both* a distribution and an architecture to
 * call detection successful, Arch fell through to "could not be detected
 * automatically" even once its `etc/arch-release` marker was being seen.
 *
 * Reading `e_machine` from a binary the rootfs actually ships settles the
 * question with the same information the kernel itself would use. It is not a
 * heuristic: `EM_AARCH64` is `183` by definition (Linux `elf-em.h`).
 *
 * ## Cost
 *
 * 20 bytes per probed entry, taken during the scan pass that is already
 * streaming every byte of the archive — no extra pass, no extra I/O. Only
 * entries a profile nominates ([DetectionRules.elfProbePaths]) are probed, and
 * only until one yields an answer.
 */
object ElfArchitecture {

    /** Bytes of header needed to reach `e_machine` (offset 18) plus its width. */
    const val HEADER_BYTES = 20

    private const val EI_CLASS = 4
    private const val EI_DATA = 5
    private const val E_MACHINE_OFFSET = 18

    // Linux include/uapi/linux/elf-em.h
    private const val EM_386 = 3
    private const val EM_ARM = 40
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183

    /**
     * Reads the architecture from the first [HEADER_BYTES] of an ELF file.
     *
     * Returns null — never a guess — when [header] is too short, is not an ELF
     * file, or names a machine Linexa has no [RootFsProbe.RootFsArch] for. A
     * null must leave the caller's existing signals untouched so a
     * shell-script `/bin/sh` (very common: it has no ELF magic) simply
     * contributes nothing instead of poisoning detection.
     *
     * Endianness is honoured via `EI_DATA` rather than assumed little-endian.
     * Every architecture Linexa targets is little-endian in practice, but
     * `e_machine` is a 16-bit field whose byte order the header itself
     * declares, and reading it the other way round would turn `EM_AARCH64`
     * (183) into 46848 — a silent wrong answer, which is worse than none.
     */
    fun fromHeader(header: ByteArray, length: Int = header.size): RootFsProbe.RootFsArch? {
        if (length < HEADER_BYTES) return null
        if (header[0] != 0x7F.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) {
            return null
        }
        // ELFCLASS32 = 1, ELFCLASS64 = 2; anything else is malformed.
        val elfClass = header[EI_CLASS].toInt()
        if (elfClass != 1 && elfClass != 2) return null

        val littleEndian = when (header[EI_DATA].toInt()) {
            1 -> true
            2 -> false
            else -> return null
        }
        val lo = header[E_MACHINE_OFFSET].toInt() and 0xFF
        val hi = header[E_MACHINE_OFFSET + 1].toInt() and 0xFF
        val machine = if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi

        return when (machine) {
            EM_AARCH64 -> RootFsProbe.RootFsArch.ARM64_V8A
            EM_ARM -> RootFsProbe.RootFsArch.ARMEABI_V7A
            EM_X86_64 -> RootFsProbe.RootFsArch.X86_64
            EM_386 -> RootFsProbe.RootFsArch.X86
            else -> null
        }
    }
}
