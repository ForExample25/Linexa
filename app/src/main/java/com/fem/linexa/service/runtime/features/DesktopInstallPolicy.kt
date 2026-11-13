package com.fem.linexa.service.runtime.features

import com.fem.linexa.service.installer.DesktopEnvironmentProbe
import java.io.File

/**
 * 2026-09-02 (guided desktop installer, Phase 3): pure prerequisite logic
 * for the guided Debian/Ubuntu + APT desktop installation — JVM-testable,
 * no Android, no exec. The ViewModel (FeaturesViewModel) composes these
 * with file reads and guest execs; every decision the *user sees* is made
 * here, so the honest behaviors (never fabricate, never silently proceed)
 * are pinned in one place.
 */
object DesktopInstallPolicy {

    /**
     * The os-release `ID=` values the guided installer accepts. Scope is
     * Debian + Ubuntu + APT ONLY (phase spec §5): every other distribution
     * stays on its existing manual/preset path and is refused here with a
     * clear reason — unsupported stays unsupported.
     */
    val SUPPORTED_OS_IDS: Set<String> = setOf("debian", "ubuntu")

    /**
     * Result of the static (no-session) prerequisites: what the guided
     * installer's confirmation screen shows before any exec runs.
     *
     * @param rootfsPresent the workspace rootfs directory actually exists.
     * @param osReleaseId the rootfs's `/etc/os-release` ID, or null when
     *   unreadable/absent (a rootfs without os-release is not Debian/Ubuntu
     *   as far as this flow can prove).
     * @param aptDistro [osReleaseId] is one of [SUPPORTED_OS_IDS].
     * @param aptBinary the first APT-family binary found on disk
     *   (rootfs-relative), or null — the guided installer needs apt-get/apt.
     * @param sessionPath rootfs-relative session launcher found by the
     *   [DesktopEnvironmentProbe], or null.
     * @param desktopState the per-preset state of the selected preset.
     * @param freeBytes free bytes on the workspace's partition.
     */
    data class StaticPrerequisites(
        val rootfsPresent: Boolean,
        val osReleaseId: String?,
        val aptDistro: Boolean,
        val aptBinary: String?,
        val sessionPath: String?,
        val desktopState: DesktopEnvironmentProbe.State,
        val freeBytes: Long
    ) {
        /**
         * A hard refusal reason that can be determined WITHOUT the guest
         * (no session required), or null when the static preconditions
         * pass. A missing size quote is NOT a hard block — apt itself
         * enforces storage mid-install and the retry is safe.
         */
        fun hardBlockReason(preset: DesktopPreset): BlockReason? = when {
            !rootfsPresent -> BlockReason.NO_ROOTFS
            !aptDistro -> BlockReason.NOT_APT_DISTRO
            aptBinary == null -> BlockReason.NO_APT
            preset == DesktopPreset.NONE -> BlockReason.NO_PRESET
            else -> null
        }
    }

    enum class BlockReason {
        /** The workspace's runtime session is not running — the install execs need it. */
        NO_SESSION,

        /** The workspace has no live rootfs. */
        NO_ROOTFS,

        /** `/etc/os-release` does not identify Debian or Ubuntu. */
        NOT_APT_DISTRO,

        /** No APT binary in the rootfs's standard bin directories. */
        NO_APT,

        /** No desktop preset selected. */
        NO_PRESET,

        /**
         * The apt simulation reported an installed-size requirement that
         * exceeds the free space on the workspace partition (with a 10%
         * headroom for dpkg's transient copies).
         */
        INSUFFICIENT_STORAGE
    }

    /**
     * Reads the static prerequisites from the filesystem. Only reads files;
     * every failure degrades to the honest "absent" value instead of a
     * guess.
     */
    fun staticPrerequisites(rootfs: File, workspaceDir: File, preset: DesktopPreset): StaticPrerequisites {
        val osReleaseId = readOsReleaseId(rootfs)
        return StaticPrerequisites(
            rootfsPresent = rootfs.isDirectory,
            osReleaseId = osReleaseId,
            aptDistro = osReleaseId?.trim()?.lowercase() in SUPPORTED_OS_IDS,
            aptBinary = aptBinaryPath(rootfs),
            sessionPath = DesktopEnvironmentProbe.anySessionPresent(rootfs, preset),
            desktopState = DesktopEnvironmentProbe.probe(rootfs, preset),
            freeBytes = workspaceDir.usableSpace
        )
    }

    /**
     * The `ID=` value of the rootfs's `/etc/os-release`, or null. Pure
     * string parsing; the file read itself is the caller's (kept here for
     * the single-file convenience overload).
     */
    fun parseOsReleaseId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("ID=")) {
                val value = trimmed.substringAfter('=').trim().trim('"', '\'')
                return value.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun readOsReleaseId(rootfs: File): String? = runCatching {
        parseOsReleaseId(File(rootfs, "etc/os-release").takeIf { it.isFile }?.readText())
    }.getOrNull()

    /**
     * The first APT-family binary in the standard bin directories
     * (rootfs-relative), or null. `apt-get` first (the runtime's own
     * detection prefers it), then `apt`, then `dpkg`-family presence is
     * NOT enough — dpkg without apt cannot run these commands.
     */
    fun aptBinaryPath(rootfs: File): String? {
        val candidates = listOf("apt-get", "apt")
        for (dir in listOf("usr/bin", "usr/sbin", "bin", "sbin", "usr/local/bin")) {
            val dirFile = File(rootfs, dir)
            if (!dirFile.isDirectory) continue
            for (bin in candidates) {
                if (File(dirFile, bin).isFile) return "$dir/$bin"
            }
        }
        return null
    }

    /**
     * The storage gate for the confirmation screen. Requires space for the
     * DOWNLOAD plus the INSTALLED delta (both live on the same partition
     * during the install), with 10% headroom for dpkg's transient copies —
     * a real comparison only when apt's simulation quoted real numbers;
     * `null` (no gate) when the requirement is unknown, which the UI must
     * surface as "size unknown" rather than as a pass.
     */
    fun storageBlock(downloadBytes: Long?, installedBytes: Long?, freeBytes: Long): BlockReason? {
        val required = ((downloadBytes ?: 0L) + (installedBytes ?: 0L)).takeIf { it > 0L }
            ?: return null
        val requiredWithHeadroom = (required * 11L) / 10L
        return if (freeBytes < requiredWithHeadroom) BlockReason.INSUFFICIENT_STORAGE else null
    }
}
