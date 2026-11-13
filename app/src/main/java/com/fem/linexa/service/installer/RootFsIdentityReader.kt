package com.fem.linexa.service.installer

import java.io.File

/**
 * Reads a **live, extracted** rootfs directory into the same signals
 * [RootFsProbe] consumes (the probe itself only understands tar-entry
 * signals from the analyze pass — this bridges the gap for already-installed
 * workspaces).
 *
 * Used by the startup metadata backfill ([WorkspaceMetadataBackfillManager])
 * so workspaces that predate attribute persistence (imported rootfs, or
 * catalog installs whose row never recorded a `distroId`) get their branded
 * identity resolved from the actual files on disk — no distro-name guessing.
 * [catalogDistroId] maps the probe result to the catalog id string that
 * `DistroIcons` (and the Retry path) understands; null means "keep the
 * letter avatar".
 */
object RootFsIdentityReader {

    /**
     * Reads `etc/os-release` (or `usr/lib/os-release`) plus the legacy
     * distro marker files into a [RootFsProbe.Signals] for [RootFsProbe.detect].
     * Every read is best-effort: a missing/unreadable file contributes a null
     * signal, exactly like an absent tar entry.
     */
    fun readSignals(rootfs: File): RootFsProbe.Signals {
        return RootFsProbe.Signals(
            osRelease = readIfPresent(File(rootfs, "etc/os-release"))
                ?: readIfPresent(File(rootfs, "usr/lib/os-release")),
            alpineRelease = readIfPresent(File(rootfs, "etc/alpine-release")),
            archRelease = readIfPresent(File(rootfs, "etc/arch-release")),
            debianVersion = readIfPresent(File(rootfs, "etc/debian_version")),
            fedoraRelease = readIfPresent(File(rootfs, "etc/fedora-release"))
        )
    }

    /** The catalog id (e.g. "debian") for [rootfs], or null when unidentified. */
    fun catalogDistroId(rootfs: File): String? {
        if (!rootfs.isDirectory) return null
        val identity = RootFsProbe.detect(readSignals(rootfs))
        return catalogIdFor(identity.distroId)
    }

    /** Maps the probe's [RootFsProbe.DistroId] enum to a catalog id string, or null for OTHER. */
    fun catalogIdFor(distroId: RootFsProbe.DistroId): String? = when (distroId) {
        RootFsProbe.DistroId.DEBIAN -> "debian"
        RootFsProbe.DistroId.UBUNTU -> "ubuntu"
        RootFsProbe.DistroId.ALPINE -> "alpine"
        RootFsProbe.DistroId.ARCH -> "archlinux"
        RootFsProbe.DistroId.FEDORA -> "fedora"
        RootFsProbe.DistroId.OTHER -> null
    }

    private fun readIfPresent(file: File): String? {
        return try {
            if (file.isFile) file.readText(Charsets.UTF_8).trim() else null
        } catch (_: Exception) {
            null
        }
    }
}
