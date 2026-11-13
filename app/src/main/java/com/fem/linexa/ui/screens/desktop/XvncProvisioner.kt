package com.fem.linexa.ui.screens.desktop

/**
 * §1.85 — Pure (JVM-testable) Xvnc provisioning logic for audit item 4.5.
 *
 * When `which Xvnc` fails in a workspace, the Desktop feature auto-installs
 * Xvnc through the guest's package manager instead of only showing the
 * `desktop_xvnc_missing` error string. Everything here is deterministic
 * string work (no Android/Application needed), mirroring the `TmuxPersist`/
 * `XKeysyms` convention, so the whole detection → command mapping can be
 * asserted in a plain JUnit test.
 *
 * Design notes:
 * - Detection reuses the same `which …` probe as
 *   [com.fem.linexa.domain.packagemanager.PackageManagerDetector] (its
 *   [com.fem.linexa.domain.packagemanager.PackageManagerDetector.PROBE_COMMAND]),
 *   so a workspace's real package manager (apt/apk/pacman/dnf/zypper)
 *   decides the package name and install command — a distro can't get the
 *   wrong one.
 * - Every command is fully typed here (constant per distro, never user
 *   input), so nothing reaches `exec` unvalidated (§1.14 shell-hardening:
 *   no string is ever built from a user-supplied value).
 * - The package names are the distro's actual Xvnc-providing package:
 *   Debian/Ubuntu `tigervnc-standalone-server`, Alpine `tigervnc-server`,
 *   Arch `tigervnc`, Fedora `tigervnc-server`, openSUSE `tigervnc`.
 */
object XvncProvisioner {

    /**
     * The probe run inside the guest to find its package manager. Mirrors
     * [com.fem.linexa.domain.packagemanager.PackageManagerDetector]'s
     * detection so the runtime and the installer agree on which package
     * manager a workspace uses.
     */
    const val DETECT_PACKAGE_MANAGER_COMMAND = "which apt-get apk pacman dnf zypper 2>/dev/null | head -1"

    /**
     * Maps the package-manager probe's stdout to a canonical key, or null
     * when no supported package manager was found. Accepts the full `which`
     * output (e.g. `/usr/bin/apt-get`) — only the basename is compared, so
     * both a bare and an absolute path resolve.
     */
    fun packageManagerKey(whichOutput: String): String? {
        val basename = whichOutput.trim().substringAfterLast('/')
        return when {
            basename.contains("apt-get") -> KEY_APT
            basename.contains("apk") -> KEY_APK
            basename.contains("pacman") -> KEY_PACMAN
            basename.contains("dnf") -> KEY_DNF
            basename.contains("zypper") -> KEY_ZYPPER
            else -> null
        }
    }

    /**
     * The one-shot install command that brings Xvnc into the guest for the
     * given package-manager [key] (see [packageManagerKey]), or null for an
     * unsupported key. Includes the package manager's index refresh where
     * required (apt/pacman), so a fresh workspace with stale package lists
     * still resolves the package.
     */
    fun installCommand(packageManagerKey: String): String? = when (packageManagerKey) {
        KEY_APT -> "apt-get update && apt-get install -y tigervnc-standalone-server"
        KEY_APK -> "apk add --no-cache tigervnc-server"
        KEY_PACMAN -> "pacman -Sy --noconfirm tigervnc"
        KEY_DNF -> "dnf install -y tigervnc-server"
        KEY_ZYPPER -> "zypper --non-interactive install -y tigervnc"
        else -> null
    }

    /** Canonical package-manager keys. */
    const val KEY_APT = "apt"
    const val KEY_APK = "apk"
    const val KEY_PACMAN = "pacman"
    const val KEY_DNF = "dnf"
    const val KEY_ZYPPER = "zypper"
}
