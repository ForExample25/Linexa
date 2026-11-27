package com.fem.linexa.domain.packagemanager

import com.fem.linexa.core.logging.Logger
import com.fem.linexa.domain.runtime.CommandExecutor

/**
 * §1.92 — Automatic package-manager detection.
 *
 * The probe is a single shell loop over the supported managers using
 * `command -v` — a POSIX shell builtin that BusyBox/Alpine images are
 * guaranteed to have, unlike the external `which` binary. When several
 * managers exist in one workspace (e.g. dnf + apt), the choice is resolved by
 * deduplicating the probe output and preferring the canonical manager for the
 * distro advertised by `/etc/os-release` — never by `head -1` order.
 *
 * All decision logic lives in the pure functions below (JVM-testable);
 * [detect] is the thin suspend wrapper that runs the two probes through the
 * workspace's [CommandExecutor] (the domain port `RuntimeBinder` implements,
 * §9A).
 */
object PackageManagerDetector {

    const val KEY_APT = "apt"
    const val KEY_PACMAN = "pacman"
    const val KEY_APK = "apk"
    const val KEY_DNF = "dnf"
    const val KEY_ZYPPER = "zypper"

    /**
     * Canonical probe: one `command -v` per supported manager, in one exec.
     *
     * A loop is required, not stylistic: POSIX defines `command -v` for a
     * single name, and dash (Debian/Ubuntu's `/bin/sh`) ignores extra
     * arguments instead of printing each hit. `which` is not used because
     * minimal Alpine/BusyBox images may not ship it (finding 036).
     */
    const val PROBE_COMMAND =
        "for linexa_pm in apt-get pacman apk dnf zypper; do command -v \"\$linexa_pm\"; done 2>/dev/null"

    /** Distro identity probe, used to break ties between multiple managers. */
    const val OS_RELEASE_COMMAND = "grep -E '^(ID|ID_LIKE)=' /etc/os-release 2>/dev/null"

    /** Priority when multiple managers exist and os-release gives no hint. */
    private val DEFAULT_PRIORITY = listOf(KEY_APT, KEY_PACMAN, KEY_APK, KEY_DNF, KEY_ZYPPER)

    /** All supported keys, in probe priority order. */
    val supportedKeys: List<String> get() = DEFAULT_PRIORITY

    /** Probe-order lookup that maps each basename to its canonical key. */
    private val PROBE_ORDER = listOf("apt-get" to KEY_APT, "pacman" to KEY_PACMAN, "apk" to KEY_APK, "dnf" to KEY_DNF, "zypper" to KEY_ZYPPER)

    /**
     * The guest binary a provider key drives — the inverse of [PROBE_ORDER].
     * §1.118: needed so a *declared* `linexa.package_manager.type` can be
     * confirmed with one targeted `command -v` instead of the full five-way
     * probe plus os-release tie-break.
     */
    fun binaryFor(key: String): String? =
        PROBE_ORDER.firstOrNull { (_, k) -> k == key.lowercase() }?.first

    /**
     * A single-manager presence probe. §1.118: a declaration states which
     * manager the image ships, and this confirms it is really there before the
     * UI commits to that provider — a wrong declaration then degrades to the
     * ordinary [PROBE_COMMAND] path instead of leaving the Package Manager
     * screen pointed at a manager the guest doesn't have.
     */
    fun verifyCommandFor(key: String): String? =
        binaryFor(key)?.let { "command -v $it 2>/dev/null" }

    /**
     * Run the detection probes inside the workspace and return the resolved
     * provider key, or null when no supported package manager is present.
     * The two probes are sequential and cheap (`command -v` loop + one grep).
     *
     * §1.118: [declaredType] short-circuits detection when the rootfs declared
     * its package manager. The declaration is *confirmed* with a single
     * targeted `command -v` rather than trusted outright — the whole benefit is
     * skipping the five-way probe and the os-release tie-break, and a
     * declaration that turns out to be wrong must not leave the UI pointed at
     * a manager the guest doesn't ship. An unconfirmed declaration falls
     * through to the normal probe path with a warning.
     */
    suspend fun detect(runtimeBinder: CommandExecutor, workspaceId: String, declaredType: String? = null): String? {
        if (declaredType != null) {
            val key = declaredType.lowercase()
            val verify = verifyCommandFor(key)
            if (verify != null && runtimeBinder.execCommand(workspaceId, verify).exitCode == 0) {
                return key
            }
            Logger.w(
                "PackageManagerDetector",
                "§1.118 $workspaceId declares package_manager.type '$declaredType' but it is not present " +
                    "in the guest — falling back to the full probe"
            )
        }
        val probeResult = runtimeBinder.execCommand(workspaceId, PROBE_COMMAND)
        val osRelease = runtimeBinder.execCommand(workspaceId, OS_RELEASE_COMMAND).output
        return resolve(probeResult.output, osRelease)
    }

    /**
     * Pure decision: given the raw path probe output and the raw os-release
     * output, return the canonical provider key or null.
     */
    fun resolve(whichOutput: String, osReleaseOutput: String): String? {
        val candidates = parseWhichOutput(whichOutput)
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val osId = parseOsReleaseId(osReleaseOutput)
        val osIdLikes = parseOsReleaseIdLike(osReleaseOutput)
        return preferKey(candidates, osId, osIdLikes)
    }

    /**
     * Parse the `command -v` probe output into the list of found keys,
     * preserving probe order. Each line is a full path like `/usr/bin/apt-get`;
     * only the basename matters.
     */
    fun parseWhichOutput(output: String): List<String> =
        output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val basename = line.substringAfterLast('/')
                PROBE_ORDER.firstOrNull { (needle, _) -> basename == needle || basename.startsWith("$needle-") }
                    ?.second
            }
            .distinct()

    /** Parse the `ID=` line of /etc/os-release, stripping optional quotes. */
    fun parseOsReleaseId(output: String): String? =
        output.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ID=") }
            ?.removePrefix("ID=")
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }

    /** Parse the `ID_LIKE=` line (space-separated distro ids), stripping quotes. */
    fun parseOsReleaseIdLike(output: String): List<String> =
        output.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ID_LIKE=") }
            ?.removePrefix("ID_LIKE=")
            ?.trim('"')
            ?.split(' ')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /**
     * Resolve the canonical manager among [candidates] when several are
     * present. Prefers the distro's own manager (os-release `ID` then
     * `ID_LIKE`), falling back to [DEFAULT_PRIORITY] order.
     */
    fun preferKey(candidates: List<String>, osId: String?, osIdLikes: List<String>): String? {
        osId?.let { id ->
            distroIdToPm(id)?.let { key ->
                if (key in candidates) return key
            }
        }
        osIdLikes.forEach { id ->
            distroIdToPm(id)?.let { key ->
                if (key in candidates) return key
            }
        }
        return DEFAULT_PRIORITY.firstOrNull { it in candidates }
    }

    /** Map a distro id (os-release `ID`/`ID_LIKE`) to its canonical package manager. */
    fun distroIdToPm(id: String): String? = when (id.lowercase()) {
        "debian", "ubuntu", "kali", "raspbian", "linuxmint", "pop", "elementary", "devuan", "mx", "deepin" -> KEY_APT
        "arch", "manjaro", "endeavouros", "garuda", "arcolinux", "artix" -> KEY_PACMAN
        "alpine", "postmarketos" -> KEY_APK
        "fedora", "rhel", "centos", "rocky", "almalinux", "amazon", "ol", "cloudlinux" -> KEY_DNF
        "opensuse", "opensuse-leap", "opensuse-tumbleweed", "sle", "sles", "sled" -> KEY_ZYPPER
        else -> null
    }
}
