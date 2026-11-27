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
                if (RootfsFiles.isFile(rootfs, dirFile, pm)) return true
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
        // The session half: any installable preset's launcher present, OR any
        // other family the runtime can launch (MATE, LXDE, KDE Plasma) found
        // by the cached, symlink-safe assessment — so a rootfs that shipped
        // with such a desktop is recognised instead of reported as "none".
        val presetPresent = DesktopPreset.entries
            .filter { it != DesktopPreset.NONE }
            .any { DesktopEnvironmentProbe.anySessionPresent(rootfs, it) != null }
        return presetPresent ||
            DesktopDetectionCache.assess(rootfs).readiness != DesktopReadiness.NONE
    }

    /**
     * The four-way readiness of [rootfs]'s desktop (ready / partial / missing
     * runtime dependencies / none), cached and invalidated on rootfs change.
     */
    fun assessDesktop(rootfs: File): DesktopAssessment = DesktopDetectionCache.assess(rootfs)

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
                if (RootfsFiles.isFile(rootfs, dirFile, bin)) return "$dir/$bin"
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

/**
 * Host-side file checks that resolve a rootfs's symlinks **inside** the rootfs.
 *
 * A guest's absolute symlink (`/usr/bin/x-session-manager` →
 * `/etc/alternatives/x-session-manager`) points at the guest's own `/`, but
 * `File.isFile` resolves it against the HOST filesystem, where the target does
 * not exist — so a perfectly installed launcher was reported missing. The final
 * path component is resolved here by re-rooting absolute targets into [root]
 * (bounded hops, no escape above [root]).
 */
internal object RootfsFiles {
    private const val MAX_HOPS = 8

    fun isFile(root: File, dir: File, name: String): Boolean = resolvesToFile(root, File(dir, name))

    fun resolvesToFile(root: File, candidate: File): Boolean {
        var current = candidate
        repeat(MAX_HOPS) {
            val path = current.toPath()
            if (!java.nio.file.Files.isSymbolicLink(path)) return current.isFile
            val target = runCatching { java.nio.file.Files.readSymbolicLink(path) }.getOrNull()
                ?: return false
            current = if (target.isAbsolute) {
                File(root, target.toString().trimStart('/'))
            } else {
                File(current.parentFile ?: root, target.toString())
            }
        }
        return false
    }
}

/**
 * Four-way readiness of a rootfs's desktop, from a **targeted** set of path
 * checks (a handful of stats — never a filesystem walk).
 */
enum class DesktopReadiness {
    /** Session launcher, window manager and X server are all present. */
    READY,

    /** A launcher exists but no window manager of its family: an interrupted or incomplete install. */
    PARTIAL,

    /** Launcher and window manager exist, but the X server (Xvnc) or XKB data is missing. */
    MISSING_RUNTIME_DEPENDENCIES,

    /** No desktop session launcher of any known family. */
    NONE
}

/** What [DesktopAssessment.assess] found. [missing] names components, never guesses. */
data class DesktopAssessment(
    val readiness: DesktopReadiness,
    /** Display name of the detected desktop family, or null when [readiness] is [DesktopReadiness.NONE]. */
    val environment: String?,
    val missing: List<String>
)

/**
 * Desktop capability assessment. Families here are **detection-only**: the
 * installable set stays [DesktopPreset] (XFCE/LXQt/Openbox), but a rootfs that
 * already ships MATE, LXDE or KDE Plasma is recognised instead of reported as
 * "no desktop".
 */
object DesktopAssessor {
    internal data class Family(
        val name: String,
        val sessionBinaries: List<String>,
        val windowManagers: List<String>
    )

    internal val FAMILIES = listOf(
        Family("XFCE", listOf("startxfce4", "xfce4-session"), listOf("xfwm4")),
        Family("LXQt", listOf("startlxqt", "lxqt-session"), listOf("openbox", "kwin_x11", "xfwm4", "marco")),
        Family("LXDE", listOf("startlxde", "lxsession"), listOf("openbox")),
        Family("MATE", listOf("mate-session", "mate-session-manager"), listOf("marco", "metacity", "xfwm4")),
        Family("KDE Plasma", listOf("startplasma-x11", "startkde"), listOf("kwin_x11")),
        Family("Openbox", listOf("openbox-session"), listOf("openbox"))
    )

    private val BIN_DIRS = listOf("usr/bin", "usr/sbin", "bin", "sbin", "usr/local/bin")
    private val XVNC_NAMES = listOf("Xvnc")
    private const val XKB_RULES = "usr/share/X11/xkb/rules"

    private fun find(rootfs: File, names: List<String>): String? {
        for (dir in BIN_DIRS) {
            val dirFile = File(rootfs, dir)
            if (!dirFile.isDirectory) continue
            for (n in names) if (RootfsFiles.isFile(rootfs, dirFile, n)) return n
        }
        return null
    }

    /** Pure file-system assessment; see [DesktopDetectionCache] for the cached entry point. */
    fun assess(rootfs: File): DesktopAssessment {
        if (!rootfs.isDirectory) return DesktopAssessment(DesktopReadiness.NONE, null, emptyList())
        val family = FAMILIES.firstOrNull { find(rootfs, it.sessionBinaries) != null }
            ?: return DesktopAssessment(DesktopReadiness.NONE, null, emptyList())
        val missing = ArrayList<String>()
        val hasWm = find(rootfs, family.windowManagers) != null
        if (!hasWm) missing += "window manager (${family.windowManagers.first()})"
        val hasX = find(rootfs, XVNC_NAMES) != null
        if (!hasX) missing += "X server (Xvnc)"
        if (!File(rootfs, XKB_RULES).isDirectory) missing += "XKB data"
        val readiness = when {
            !hasWm -> DesktopReadiness.PARTIAL
            missing.isNotEmpty() -> DesktopReadiness.MISSING_RUNTIME_DEPENDENCIES
            else -> DesktopReadiness.READY
        }
        return DesktopAssessment(readiness, family.name, missing)
    }
}

/**
 * Caches [DesktopAssessor.assess] per rootfs and invalidates it when the rootfs
 * changes. The fingerprint is a few stats of the files every package manager
 * rewrites on install/removal (dpkg status, apk db, pacman local db) plus the
 * `usr/bin` directory — so an `apt install` done from the terminal, or a
 * guided install, invalidates the entry without any polling and without
 * walking the tree.
 */
object DesktopDetectionCache {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Pair<String, DesktopAssessment>>()

    internal fun fingerprint(rootfs: File): String {
        fun stat(rel: String): String {
            val f = File(rootfs, rel)
            return if (f.exists()) "${f.lastModified()}:${f.length()}" else "-"
        }
        return listOf(
            "var/lib/dpkg/status", "lib/apk/db/installed", "var/lib/pacman/local",
            "usr/bin", "usr/sbin", "bin"
        ).joinToString("|") { stat(it) }
    }

    fun assess(rootfs: File): DesktopAssessment {
        val key = rootfs.absolutePath
        val fp = fingerprint(rootfs)
        entries[key]?.let { (cachedFp, cached) -> if (cachedFp == fp) return cached }
        val fresh = DesktopAssessor.assess(rootfs)
        entries[key] = fp to fresh
        return fresh
    }

    fun invalidate(rootfs: File) {
        entries.remove(rootfs.absolutePath)
    }
}
