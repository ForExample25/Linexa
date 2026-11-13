package com.fem.linexa.service.runtime.features

import com.fem.linexa.domain.packagemanager.PackageManagerDetector

/**
 * §1.109 — Pure (JVM-testable) provisioning logic for the Workspace SSH
 * feature: the guest-side consumer of the `ssh` feature toggle
 * ([com.fem.linexa.data.WorkspaceContent] id `"ssh"`).
 *
 * Everything here is deterministic string work (no Android, no coroutines),
 * mirroring the [com.fem.linexa.ui.screens.desktop.XvncProvisioner] /
 * `TmuxPersist` convention, so the whole detection → package → command chain
 * can be asserted in a plain JUnit test. The suspend orchestration lives in
 * [WorkspaceFeatureSupervisor].
 *
 * Design decisions (recorded in `Linexa-Roadmap.md` §1.109):
 *
 * - **Package-manager detection reuses [PackageManagerDetector]'s pure
 *   resolver**, not XvncProvisioner's `which … | head -1` shortcut. The
 *   detector deduplicates the probe and breaks ties with `/etc/os-release`
 *   (`ID`/`ID_LIKE`), which is the correct behavior on a rootfs that carries
 *   more than one package manager. Only [PackageManagerDetector.resolve] (a
 *   pure function) is used here — the caller supplies the two probe outputs,
 *   so this object stays free of the runtime seam.
 *
 * - **Every command is a compile-time constant or built only from values
 *   this object owns** (a distro key, an int port, a guest path returned by
 *   [sshdPath]). No user-supplied string is ever interpolated into a command
 *   (§1.14 shell-hardening).
 *
 * - **sshd runs non-forking (`-D`)**, because the guest-daemon host
 *   ([com.fem.linexa.service.runtime.container.ContainerRuntime.startDaemon])
 *   makes the daemon the *initial tracee* of its own PRoot process: a
 *   self-backgrounding sshd would exit that tracee immediately and PRoot's
 *   `--kill-on-exit` would tear the whole host down. `-e` sends the log to
 *   stderr, which the daemon host redirects to
 *   `{workspace}/logs/daemon-sshd.log`.
 *
 * - **Port [SSH_PORT] (8022), not 22.** The guest has emulated root (`proot
 *   -0`) but the *host* process does not, so binding a privileged port is not
 *   reliable under PRoot; 8022 is also the Termux convention and cannot
 *   collide with a real host sshd.
 *
 * - **No authentication is weakened.** The daemon is started with the
 *   distro's own `sshd_config` — notably its default `PermitRootLogin`
 *   (`prohibit-password` on Debian/Ubuntu/Alpine), so key-based root login
 *   works and password login stays refused until the user sets a password
 *   inside the workspace themselves. Linexa deliberately does **not** inject
 *   `PermitRootLogin=yes` / `PasswordAuthentication=yes`, does not set a
 *   password, and does not store any credential — enabling the toggle must
 *   never silently publish a passwordless root shell on the network.
 */
object SshdProvisioner {

    /** The workspace feature id this provisioner consumes (`WorkspaceContent`). */
    const val FEATURE_SSH = "ssh"

    /** Stable guest-daemon name (see `ContainerRuntime.startDaemon`). */
    const val DAEMON_NAME = "sshd"

    /**
     * The port sshd listens on inside the guest. Unprivileged on purpose —
     * see the class kdoc.
     */
    const val SSH_PORT = 8022

    /** Probe that locates the package manager (delegated to [PackageManagerDetector]). */
    const val DETECT_PACKAGE_MANAGER_COMMAND = PackageManagerDetector.PROBE_COMMAND

    /** Probe that breaks ties between multiple package managers. */
    const val DETECT_OS_RELEASE_COMMAND = PackageManagerDetector.OS_RELEASE_COMMAND

    /**
     * Probe that locates the sshd binary. `sshd` lives in `/usr/sbin` on
     * every supported distro and `/usr/sbin` is frequently absent from a
     * non-login shell's `PATH`, so the absolute candidates are probed
     * explicitly rather than relying on a bare `command -v sshd`.
     * Prints the resolved absolute path on stdout, or nothing (non-zero) when
     * sshd is not installed.
     */
    const val SSHD_PROBE_COMMAND =
        "command -v sshd || command -v /usr/sbin/sshd || command -v /usr/local/sbin/sshd"

    /**
     * Resolves the package-manager key from the two probe outputs, or null
     * when the guest has no supported package manager. Thin, pure delegation
     * to [PackageManagerDetector.resolve] so the runtime, the Package Manager
     * UI and this feature can never disagree about a workspace's manager.
     */
    fun packageManagerKey(whichOutput: String, osReleaseOutput: String): String? =
        PackageManagerDetector.resolve(whichOutput, osReleaseOutput)

    /**
     * The distro's OpenSSH *server* package for [packageManagerKey], or null
     * for an unsupported key. Alpine/Arch/openSUSE ship the server in the
     * `openssh` package (which also carries `ssh-keygen`, required by
     * [PREPARE_COMMAND]); Debian-likes and Fedora-likes split it out as
     * `openssh-server`.
     */
    fun packageFor(packageManagerKey: String): String? = when (packageManagerKey) {
        PackageManagerDetector.KEY_APT -> "openssh-server"
        PackageManagerDetector.KEY_APK -> "openssh"
        PackageManagerDetector.KEY_PACMAN -> "openssh"
        PackageManagerDetector.KEY_DNF -> "openssh-server"
        PackageManagerDetector.KEY_ZYPPER -> "openssh"
        else -> null
    }

    /**
     * The one-shot install command that brings sshd into the guest for
     * [packageManagerKey], or null for an unsupported key. Includes the index
     * refresh where the manager requires one (apt/pacman), so a fresh
     * workspace with stale package lists still resolves the package.
     */
    fun installCommand(packageManagerKey: String): String? {
        val pkg = packageFor(packageManagerKey) ?: return null
        return when (packageManagerKey) {
            PackageManagerDetector.KEY_APT -> "apt-get update && apt-get install -y $pkg"
            PackageManagerDetector.KEY_APK -> "apk add --no-cache $pkg"
            PackageManagerDetector.KEY_PACMAN -> "pacman -Sy --noconfirm $pkg"
            PackageManagerDetector.KEY_DNF -> "dnf install -y $pkg"
            PackageManagerDetector.KEY_ZYPPER -> "zypper --non-interactive install -y $pkg"
            else -> null
        }
    }

    /**
     * Everything sshd needs *before* it will start, in one idempotent
     * command:
     *
     * - `/run/sshd` (Debian/Ubuntu/Fedora) and `/var/empty` (Alpine/Arch) are
     *   the privilege-separation directories sshd `chroot`s into; sshd exits
     *   with "Missing privilege separation directory" when its own one is
     *   absent, and a freshly extracted rootfs has neither (both live on
     *   tmpfs on a real system).
     * - `ssh-keygen -A` generates only the *missing* host keys, so it is safe
     *   to re-run on every enable and never rotates existing keys (which
     *   would break a client's known_hosts).
     *
     * Deliberately does NOT touch `sshd_config`: the distro's own
     * authentication policy is what applies (see the class kdoc).
     */
    const val PREPARE_COMMAND =
        "mkdir -p /run/sshd /var/empty && chmod 0755 /run/sshd /var/empty && ssh-keygen -A"

    /**
     * The daemon command handed to
     * [com.fem.linexa.service.runtime.container.ContainerRuntime.startDaemon].
     *
     * @param sshdPath absolute guest path resolved by [SSHD_PROBE_COMMAND].
     * @param port the listen port (defaults to [SSH_PORT]).
     */
    fun daemonCommand(sshdPath: String, port: Int = SSH_PORT): String =
        "$sshdPath -D -e -p $port"

    /**
     * Normalizes [SSHD_PROBE_COMMAND]'s stdout into a single absolute guest
     * path, or null when the probe found nothing usable. `command -v` prints
     * one path per successful branch and the command is `||`-chained, so a
     * hit is the first non-blank absolute line; a relative or empty answer is
     * rejected rather than passed to [daemonCommand] (the daemon host would
     * resolve it against the guest's `PATH`, which is exactly the ambiguity
     * the absolute-candidate probe exists to remove).
     */
    fun parseSshdPath(probeOutput: String): String? =
        probeOutput.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") && !it.contains(' ') }
}
