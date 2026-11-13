package com.fem.linexa.service.installer

import com.fem.linexa.service.runtime.features.DesktopPreset
import java.io.File

/**
 * Deterministic desktop-capability source for a **live, extracted** rootfs.
 *
 * The Desktop (VNC) feature auto-provisions Xvnc through the guest's package
 * manager ([XvncProvisioner] / [PackageManagerDetector]'s `which apt-get apk
 * pacman dnf zypper` probe). So a workspace is honestly desktop-capable iff
 * its rootfs actually contains one of those package-manager binaries — the
 * same binaries the runtime detection looks for, checked statically on disk.
 * No distro-name matching: a Debian rootfs without apt is as incapable as any
 * other rootfs without a package manager.
 *
 * This feeds the persisted `attributes["desktop"]` capability flag (see
 * [WorkspaceCapabilities]), written at install/import time and backfilled at
 * startup ([WorkspaceMetadataBackfillManager]) for workspaces that predate it.
 *
 * 2026-09-02 (desktop prerequisites, Phase 2): the package-manager probe is
 * necessary but no longer sufficient — Xvnc auto-install proves nothing
 * about whether a *desktop session* exists. [DesktopEnvironmentProbe]
 * (below) checks the filesystem for an actual desktop session launcher, and
 * [probeDesktopEnvironment] exposes the per-environment detail the guided
 * installer's screens need (installed vs ready). The coarse
 * [ATTR_DESKTOP] flag the UI reads keeps its meaning via
 * [hasDesktopCapability].
 */
object RootFsCapabilityProbe {

    /** Basenames of the package managers the runtime's Xvnc auto-install supports. */
    val SUPPORTED_PACKAGE_MANAGERS: Set<String> = setOf("apt-get", "apk", "pacman", "dnf", "zypper")

    /**
     * True when [rootfs] contains a supported package-manager binary under any
     * of the standard binary directories. Pure (File I/O only) — JVM-testable.
     */
    fun hasSupportedPackageManager(rootfs: File): Boolean {
        if (!rootfs.isDirectory) return false
        val binDirs = listOf("usr/bin", "usr/sbin", "bin", "sbin", "usr/local/bin")
        for (dir in binDirs) {
            val dirFile = File(rootfs, dir)
            if (!dirFile.isDirectory) continue
            for (pm in SUPPORTED_PACKAGE_MANAGERS) {
                if (File(dirFile, pm).isFile) return true
            }
        }
        return false
    }

    /**
     * 2026-09-02 (Phase 2): the persisted coarse capability flag for the
     * Desktop button. `desktop=true` now records "a supported package
     * manager AND at least one desktop session launcher exist in the
     * rootfs" — user intent (the feature toggle) and this capability flag
     * are resolved together by `WorkspaceCapabilities.desktopEnabled`, so
     * the button never lights up from a toggle alone. Existing rows whose
     * flag predates the desktop-session half are re-derived by the startup
     * backfill, which recomputes the attribute from the live rootfs.
     */
    fun hasDesktopCapability(rootfs: File): Boolean {
        // The package-manager half (Xvnc/X11 dependency chain) is mandatory.
        if (!hasSupportedPackageManager(rootfs)) return false
        // The session half: any supported preset's launcher present.
        return DesktopPreset.entries
            .filter { it != DesktopPreset.NONE }
            .any { DesktopEnvironmentProbe.anySessionPresent(rootfs, it) != null }
    }

    /**
     * 2026-09-02 (Phase 2/3): the per-desktop-environment state of [rootfs],
     * for the guided installer and the Features screen's immediate re-check.
     * Checks the filesystem directly — never a cached attribute — so the
     * answer reflects manual installs, interrupted installs and removals.
     */
    fun probeDesktopEnvironment(rootfs: File, preset: DesktopPreset): DesktopEnvironmentProbe.State =
        DesktopEnvironmentProbe.probe(rootfs, preset)
}

/**
 * 2026-09-02 (desktop prerequisites, Phase 2): filesystem probe for an
 * actual **desktop session** in a rootfs.
 *
 * Source of truth is [DesktopPreset.sessionBinaryNames] — exactly the
 * launcher binaries the runtime's `DesktopPreset.presentCheckCommand`
 * probes live via `command -v`. This probe answers the same question
 * statically on disk, in the standard binary locations, so no second
 * package/binary list is introduced and the two can never drift (the
 * parity is pinned in `DesktopPresetTest`).
 *
 * "Present" (a launcher binary exists) and "ready" (the launcher exists
 * AND the workspace has the package manager the Xvnc/X11 dependency chain
 * auto-install needs) are deliberately distinguished: a rootfs can contain
 * a session binary while missing the package manager that the desktop's
 * own auto-provisioning requires, and the two states lead to different
 * guidance. It is a static check, not a full runtime-readiness guarantee —
 * deeper breakage (broken alternatives symlinks, missing XKB data) is only
 * observable inside the running guest and stays out of scope here; that
 * limitation is documented rather than overengineered.
 */
object DesktopEnvironmentProbe {

    /**
     * Coarse per-preset state, consumed by the Features screen and the
     * guided installer's selection list.
     */
    enum class State {
        /** At least one launcher binary of this family exists in the rootfs. */
        INSTALLED,

        /**
         * [INSTALLED] plus the package-manager dependency the desktop's own
         * provisioning chain needs — the strongest static guarantee.
         */
        READY,

        /** No launcher binary of this family exists. */
        NOT_INSTALLED
    }

    /**
     * The standard binary directories, in the order
     * [RootFsCapabilityProbe] scans. Symlinked entries (usr-merged trees)
     * resolve through [File.isFile], which follows links.
     */
    private val BIN_DIRS = listOf(
        "usr/bin", "usr/sbin", "bin", "sbin", "usr/local/bin"
    )

    /**
     * Returns the first session binary of [preset] found in [rootfs]'s
     * standard binary directories, or null when none exists ([NONE] never
     * matches anything — it names no session binaries). The returned
     * rootfs-relative path is what the caller may report ("found
     * `startxfce4`") — a genuine fact from the filesystem, never a guess.
     */
    fun anySessionPresent(rootfs: File, preset: DesktopPreset): String? {
        val candidates = DesktopPreset.sessionBinaryNames(preset)
        if (candidates.isEmpty() || !rootfs.isDirectory) return null
        for (dir in BIN_DIRS) {
            val dirFile = File(rootfs, dir)
            if (!dirFile.isDirectory) continue
            for (bin in candidates) {
                if (File(dirFile, bin).isFile) return "$dir/$bin"
            }
        }
        return null
    }

    /**
     * The [State] of [preset] in [rootfs]: [State.NOT_INSTALLED] when no
     * launcher exists, [State.INSTALLED] when one exists without the
     * package-manager dependency, [State.READY] when both hold.
     */
    fun probe(rootfs: File, preset: DesktopPreset): State {
        if (anySessionPresent(rootfs, preset) == null) return State.NOT_INSTALLED
        return if (RootFsCapabilityProbe.hasSupportedPackageManager(rootfs)) State.READY
        else State.INSTALLED
    }
}
