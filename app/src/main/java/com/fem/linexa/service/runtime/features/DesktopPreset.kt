package com.fem.linexa.service.runtime.features

/**
 * §16 — Distribution-aware desktop presets offered for the VNC run mode.
 *
 * The desktop probe (`ui/screens/desktop/XvncProvisioner`-style `which`
 * checks) only answers "is an X client present?"; when it is not, these
 * presets name *what to install* so a user can go from a headless rootfs
 * to a working desktop without editing files by hand. The package names
 * deliberately differ per package-manager key (the same reason
 * `XvncProvisioner.installCommand` maps per key): there is no single
 * "xfce" package name across Debian, Alpine, Arch, Fedora and openSUSE —
 * a distro-agnostic list would install nothing, or the wrong thing.
 *
 * Everything here is pure command-string construction in the
 * `TmuxPersist`/`XvncProvisioner` convention (JVM-testable, no Android,
 * no exec): the caller runs the returned command through the same
 * streaming exec channel as the Xvnc auto-install, which is what makes it
 * cancellable and observable; and the probe-before-install pattern
 * ([presentCheckCommand] first, install only on absence) is what makes it
 * idempotent.
 *
 * Honesty note: the package names below are declarative constants per
 * distro family — correct as of writing, but not validated on a live
 * device in this environment. They must be device-validated per distro
 * before being presented as guaranteed, exactly like every other
 * guest-side package name in this project.
 */
enum class DesktopPreset(
    /** The stable persisted id. Never renamed — it is stored in settings. */
    val id: String,

    /** Human-facing name shown in the picker. */
    val displayName: String
) {
    /** No desktop install; the workspace stays as-is (headless or manual). */
    NONE("none", "None"),

    /** Full XFCE 4 desktop — the heavyweight but complete default. */
    XFCE("xfce", "XFCE"),

    /** LXQt desktop — the lightweight middle ground. */
    LXQT("lxqt", "LXQt"),

    /** Bare Openbox window manager — the minimal option. */
    OPENBOX("openbox", "Openbox");

    companion object {

        /**
         * Canonical package-manager keys. Mirrored locally (instead of
         * importing `domain.packagemanager.PackageManagerDetector` or the
         * UI-layer provisioner) so `service.runtime.features` depends on
         * neither the domain PM screen nor `ui.*` — the import boundary
         * keeps container/service code free of UI dependencies.
         */
        private const val KEY_APT = "apt"
        private const val KEY_APK = "apk"
        private const val KEY_PACMAN = "pacman"
        private const val KEY_DNF = "dnf"
        private const val KEY_ZYPPER = "zypper"

        /**
         * 2026-09-02 (guided desktop installer, Phase 3): the APT package
         * list per preset — the SINGLE source for both the [installCommand]
         * apt branch and [simulateCommand]. One list per family, so the
         * guided flow's `apt-get -s` size quote can never name packages the
         * real install would not (and vice versa); `DesktopPresetTest` pins
         * the command built from this list.
         *
         * Package names per family remain declarative constants (see the
         * class KDoc's honesty note): correct as of writing, not validated
         * on a live device in this environment.
         */
        fun aptPackageNames(preset: DesktopPreset): List<String> = when (preset) {
            XFCE -> listOf("xfce4", "xfce4-terminal", "dbus-x11")
            LXQT -> listOf("lxqt-core", "lxqt-terminal", "dbus-x11")
            OPENBOX -> listOf("openbox", "xterm", "dbus-x11")
            NONE -> emptyList()
        }

        /**
         * 2026-09-02 (guided desktop installer, Phase 3): the read-only
         * simulation of the apt install (`apt-get -s install …`) used to
         * obtain a REAL size quote from the guest's own APT metadata before
         * the user confirms. Null when [preset] is [NONE] or [packageManagerKey]
         * is not apt — the guided installer is Debian/Ubuntu + APT only, and
         * null, never a guess, is what an unsupported combination yields
         * (same contract as [installCommand]).
         */
        fun simulateCommand(preset: DesktopPreset, packageManagerKey: String): String? {
            if (preset == NONE) return null
            if (packageManagerKey.trim().lowercase() != KEY_APT) return null
            return "apt-get -s install ${aptPackageNames(preset).joinToString(" ")}"
        }

        /**
         * Fail-open resolution of a persisted preset id: anything unknown —
         * including null and blank — degrades to [NONE] rather than
         * installing a desktop the user never asked for, the same stance
         * [DesktopRunMode.fromId] takes for run modes. Ids are matched
         * case-insensitively after trimming, so a persisted "XFCE" or
         * " xfce " still resolves.
         */
        fun fromId(raw: String?): DesktopPreset =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: NONE

        /**
         * The one-line presence probe for [preset]: a single-line
         * `command -v … || command -v …` chain covering the session
         * launcher and the session binary of the desktop family, or null
         * for [NONE] (nothing to probe). Run this first; only a non-zero
         * exit justifies an [installCommand] run — that is the whole
         * idempotency contract.
         */
        fun presentCheckCommand(preset: DesktopPreset): String? = when (preset) {
            XFCE -> "command -v startxfce4 >/dev/null 2>&1 || command -v xfce4-session >/dev/null 2>&1"
            // §E fix: the LXQt probe covers both LXQt session launchers
            // (startlxqt, lxqt-session) before falling back to LXDE's
            // lxsession — the previous probe could miss an install that
            // shipped lxqt-session only.
            LXQT -> "command -v startlxqt >/dev/null 2>&1 || command -v lxqt-session >/dev/null 2>&1 || command -v lxsession >/dev/null 2>&1"
            OPENBOX -> "command -v openbox-session >/dev/null 2>&1 || command -v openbox >/dev/null 2>&1"
            NONE -> null
        }

        /**
         * 2026-09-02 (desktop prerequisites): the session launcher basenames
         * [presentCheckCommand] probes, as plain names — the single source
         * of truth for static, host-side filesystem checks of the same
         * question (`DesktopEnvironmentProbe` runs these against the
         * rootfs's standard binary directories instead of asking the guest).
         * Every name here appears in this preset's [presentCheckCommand];
         * `DesktopPresetTest` pins that parity so the two can never drift.
         */
        fun sessionBinaryNames(preset: DesktopPreset): List<String> = when (preset) {
            XFCE -> listOf("startxfce4", "xfce4-session")
            LXQT -> listOf("startlxqt", "lxqt-session", "lxsession")
            OPENBOX -> listOf("openbox-session", "openbox")
            NONE -> emptyList()
        }

        /**
         * The one-shot install command for [preset] through the guest's
         * [packageManagerKey] (same canonical keys as
         * `PackageManagerDetector`), or null when [preset] is [NONE] or
         * the key is unknown — null, never a guess, because a wrong
         * package-manager command on a guest can leave its repo state
         * half-written. Every list includes `dbus-x11`: without a session
         * bus the desktop either fails to start or degrades (no
         * notifications, no mounts), which reads to a user as "the
         * desktop is broken" rather than "dbus is missing".
         *
         * apt additionally refreshes its package index first and installs
         * with `DEBIAN_FRONTEND=noninteractive` so the streaming exec sees
         * progress instead of a debconf prompt hanging on no tty.
         */
        fun installCommand(preset: DesktopPreset, packageManagerKey: String): String? {
            val key = packageManagerKey.trim().lowercase()
            return when (preset) {
                XFCE -> when (key) {
                    // 2026-09-02: built from [aptPackageNames] so the guided
                    // installer's size simulation and the real install can
                    // never disagree about what is being installed.
                    KEY_APT -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                        aptPackageNames(XFCE).joinToString(" ")
                    KEY_PACMAN -> "pacman -Sy --noconfirm --needed xfce4 xfce4-terminal dbus-x11"
                    KEY_APK -> "apk update && apk add xfce4 xfce4-terminal dbus-x11"
                    KEY_DNF -> "dnf install -y @xfce-desktop-environment xfce4-terminal dbus-x11"
                    KEY_ZYPPER -> "zypper --non-interactive install -y -t pattern xfce xfce4-terminal dbus-x11"
                    else -> null
                }
                LXQT -> when (key) {
                    // lxqt-core on current Debian/Ubuntu (the metapackage on
                    // older releases was plain "lxqt" — device-validate if
                    // oldstable support ever matters). Built from
                    // [aptPackageNames] — see the XFCE note above.
                    KEY_APT -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                        aptPackageNames(LXQT).joinToString(" ")
                    KEY_PACMAN -> "pacman -Sy --noconfirm --needed lxqt lxqt-terminal dbus-x11"
                    KEY_APK -> "apk update && apk add lxqt-core lxqt-terminal dbus-x11"
                    KEY_DNF -> "dnf install -y @lxqt-desktop-environment lxqt-terminal dbus-x11"
                    KEY_ZYPPER -> "zypper --non-interactive install -y -t pattern lxqt lxqt-terminal dbus-x11"
                    else -> null
                }
                OPENBOX -> when (key) {
                    KEY_APT -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                        aptPackageNames(OPENBOX).joinToString(" ")
                    KEY_PACMAN -> "pacman -Sy --noconfirm --needed openbox xterm dbus-x11"
                    KEY_APK -> "apk update && apk add openbox xterm dbus-x11"
                    KEY_DNF -> "dnf install -y openbox xterm dbus-x11"
                    KEY_ZYPPER -> "zypper --non-interactive install -y openbox xterm dbus-x11"
                    else -> null
                }
                NONE -> null
            }
        }
    }
}
