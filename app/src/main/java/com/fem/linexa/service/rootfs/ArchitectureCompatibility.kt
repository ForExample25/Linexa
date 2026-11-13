package com.fem.linexa.service.rootfs

import com.fem.linexa.service.installer.RootFsProbe

/**
 * §1.147 — rejects an archive whose detected CPU this device cannot execute
 * **before** extraction begins.
 *
 * The profile system already recorded a detected architecture, but only as a
 * warning in the resolution notes: a user could confirm an amd64 Debian tarball
 * on an arm64 phone, wait through a multi-minute extraction, and only then get
 * `exec format error` from the first shell. The check is cheap and the failure
 * is knowable during analysis, so it belongs there.
 *
 * Unknown architecture is deliberately *not* a rejection. Detection can fail
 * for a legitimate stripped image (no ELF probe path present, no name hint), and
 * refusing every such archive would turn "we could not tell" into "we refuse to
 * install", which is worse than letting the pre-activation smoke test decide.
 */
object ArchitectureCompatibility {

    /**
     * Returns a user-facing rejection reason when [arch] is known and no
     * supported ABI can execute it, or null when compatible/undetermined.
     *
     * @param deviceAbis `Build.SUPPORTED_ABIS` in preference order. Empty skips
     *   the check (a host that cannot report its ABIs must not reject anything).
     */
    fun rejectionReason(arch: RootFsProbe.RootFsArch, deviceAbis: List<String>): String? {
        if (arch == RootFsProbe.RootFsArch.UNKNOWN) return null
        if (deviceAbis.isEmpty()) return null
        val normalized = deviceAbis.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return null

        val accepted = acceptedAbis(arch)
        if (accepted.any { it in normalized }) return null

        return "This archive is built for ${arch.label} (${arch.abi}), but this device supports " +
            "${normalized.joinToString(", ")}. Install an archive matching one of those architectures."
    }

    /** ABIs that can execute [arch], including the 32-bit fallbacks Android reports. */
    fun acceptedAbis(arch: RootFsProbe.RootFsArch): Set<String> = when (arch) {
        RootFsProbe.RootFsArch.ARM64_V8A -> setOf("arm64-v8a", "arm64")
        // A 64-bit ARM device that lists armeabi-v7a can run a 32-bit rootfs.
        RootFsProbe.RootFsArch.ARMEABI_V7A -> setOf("armeabi-v7a", "armeabi")
        RootFsProbe.RootFsArch.X86_64 -> setOf("x86_64", "x86-64")
        RootFsProbe.RootFsArch.X86 -> setOf("x86", "i686")
        RootFsProbe.RootFsArch.UNKNOWN -> emptySet()
    }
}
