package com.fem.linexa.service.runtime.container

import android.app.Application
import com.fem.linexa.domain.workspace.BindMount
import com.fem.linexa.service.runtime.bootstrap.BootstrapManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the PRoot invocation for a Workspace (§1.20).
 *
 * Single responsibility: turn "run this command inside this rootfs" into a
 * concrete argv/env pair. This class spawns nothing and performs no I/O —
 * it is pure construction, which is what makes it separately reasoned-about
 * and testable from [PRootContainerRuntime] (which actually runs the
 * result, either as a one-shot [ProcessBuilder] exec or as the command
 * behind a real PTY via [com.fem.linexa.service.runtime.pty.PtySessionFactory]).
 *
 * This does not reintroduce the multi-engine `RuntimeEngine` abstraction
 * that §1.1 rejected (Docker/VM/chroot pluggability) — it is still PRoot
 * only. It formalizes the "small internal contract" §1.1 already asked
 * for, as its own named class instead of inline argv-building duplicated
 * in two places (the old `PRootProcess.startProcess` / `execCommand`).
 */
@Singleton
class PRootLauncher @Inject constructor(
    private val application: Application,
    private val bootstrapManager: BootstrapManager
) {

    /**
     * §1.43: Path to the PRoot binary — executed directly from
     * nativeLibraryDir (apk_data_file SELinux label, allows execve on
     * Android 10+). Files in filesDir have app_data_file label which
     * blocks execve under W^X enforcement.
     */
    val binaryPath: String
        get() = File(application.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    /**
     * §1.75: the HOST working directory for a PTY child. Distinct from
     * [LaunchSpec.workingDirectory] on purpose: that value is the GUEST
     * working directory — it becomes `proot -w /root` and `HOME=/root` in
     * argv/env and only exists once the rootfs is active. The PTY
     * (terminal-library `forkpty`) chdirs its child BEFORE proot/tmux are
     * even exec'd — on the HOST, where `/root` does not exist — so passing
     * the guest path as the host cwd killed the child with
     * `chdir("/root"): No such file or directory` before anything ran.
     * This value is a real HOST directory guaranteed to exist from process
     * start (Android creates `filesDir` before Application.attachBaseContext),
     * so the forkpty chdir always succeeds; the guest still starts in its
     * own `-w /root` once proot is live. Guest argv/env are byte-identical.
     */
    val hostPtyWorkingDirectory: String
        get() = application.filesDir.absolutePath

    /** The result of building a launch: what to execute, with what args and environment. */
    data class LaunchSpec(
        val executable: String,
        val args: List<String>,
        val env: Map<String, String>,
        val workingDirectory: String
    )

    /**
     * Build the argv/env to run [command] inside [rootfsPath].
     *
     * §1.48: [prootTmpDir] is the caller-scoped `PROOT_TMP_DIR` for this
     * invocation (workspace id for sessions, UUID for one-shots — see
     * `BootstrapManager.scopedProotTmpDir`). It is *not* derived here: this
     * class is pure construction and must not perform I/O (create/delete
     * directories), so the caller owns the scratch lifecycle.
     *
     * @param command defaults to an interactive login shell; pass
     *   `listOf("/bin/sh", "-c", theCommand)` for one-shot, non-interactive
     *   execution.
     * @param extraBindMounts §1.73: per-workspace extra PRoot bind mounts
     *   (`linexa.launch.bindMounts`) appended after the built-in defaults
     *   and before the command. Additive only — the default binds can never
     *   be removed. Each typed host/guest pair becomes a single `-b
     *   host:guest` argv token; nothing here is shell-interpreted.
     * @param includeSharedStorage §1.82: whether the workspace's Shared Storage
     *   feature toggle is enabled for this launch. When false, the `/mnt/shared`
     *   bind is omitted entirely (even if the host folder exists); when true it
     *   is bound only if the folder exists, as before. Defaults to true so
     *   callers that don't resolve the toggle (diagnostic probes) keep the
     *   pre-§1.82 behavior.
     */
    fun buildLaunchSpec(
        rootfsPath: String,
        command: List<String> = listOf("/bin/bash", "--login"),
        workingDirectory: String = DEFAULT_GUEST_HOME,
        /**
         * Issue #4 (2026-09-03): the guest `HOME` — deliberately separate
         * from [workingDirectory]. They used to be one coupled value (`-w
         * /root` implied `HOME=/root`), which made it impossible to open the
         * terminal in a dedicated workspace directory while keeping HOME
         * consistent with the guest's `/etc/passwd`. The default preserves
         * the historical coupling for callers that do not resolve a guest
         * environment (one-shot execs, diagnostics, existing tests).
         */
        homeDirectory: String = workingDirectory,
        /**
         * The guest identity name exposed via `USER`/`LOGNAME`. Under the
         * emulated-root launch the honest default is `root`; interactive
         * sessions pass the resolved normal-user identity instead.
         */
        userName: String = DEFAULT_GUEST_USER,
        prootTmpDir: String,
        extraBindMounts: List<BindMount> = emptyList(),
        includeSharedStorage: Boolean = true,
        /**
         * Issue #1 (2026-09-03): the emulated identity this launch runs as.
         * Root (default — privileged operations, one-shot execs, daemons
         * that need it) keeps the historical `-0` argv. A normal-user
         * identity swaps it for `--change-id=<uid>:<gid>` (the same
         * mechanism Tiny Container uses): `id`/`whoami`/`\u` in the prompt
         * then resolve through /etc/passwd to the actual account — the
         * process identity is real at the emulation layer, not a
         * prompt-string edit. The account itself is guaranteed by
         * [com.fem.linexa.service.installer.GuestRootfsInitializer.ensureGuestUser].
         */
        identity: GuestIdentity = GuestIdentity.root(homeDirectory),
        /**
         * The login shell path for the `SHELL` env var (prompt/`echo $SHELL`
         * consistency). Defaults to the account's own shell; null falls
         * back to the account shell resolution inside this function.
         */
        shellPathForEnv: String? = null
    ): LaunchSpec {
        val args = buildList {
            add("-r"); add(rootfsPath)
            add("-w"); add(workingDirectory)
            // P0-1 fix (historical): -0 granted emulated root so package
            // managers could write /etc, /var, /usr. Issue #1 replaces the
            // UNCONDITIONAL -0 with the identity flag: root launches keep
            // -0; the normal-user session fakes the account's uid/gid via
            // --change-id (files stay kernel-owned by the app uid, so every
            // read/write the app's uid is allowed still succeeds — the
            // fake identity is about how the guest REPORTS itself).
            addAll(identity.prootIdentityArgs)
            add("--link2symlink")
            // §1.51 (Issue 6 audit): --kill-on-exit so guest processes don't
            // outlive the PTY that spawned them — a login shell that
            // backgrounds a server and then dies should not orphan it
            // keeping the session "alive".
            add("--kill-on-exit")
            // ── R-1 (Runtime Parity audit, 2026-08-26): kernel-release spoof.
            //
            // Without --kernel-release, `uname -a` inside the guest exposes
            // the Android kernel string (`6.6.89-android15-8-...`). The
            // Android kernel version is misleading for software that probes
            // kernel version to gate features (e.g., assumes `>= 6.5` =>
            // specific syscalls available). Tiny Container uses the same
            // technique with `Linux localhost 6.17.0-PRoot-Distro ...`.
            //
            // The spoofed release is a stable, distribution-neutral string
            // that signals "PRoot-on-Linux" to detection tools
            // (systemd-detect-virt, containerd) without advertising Android.
            // The major version 6.0 is conservative — every modern kernel
            // feature required by Debian trixie userspace is present.
            add("--kernel-release=${SPOOFED_KERNEL_RELEASE}")
            // ── R-2 (Runtime Parity audit, 2026-08-26): --sysvipc.
            //
            // Lets System V IPC (shmget/semget/msgget) calls in the guest
            // reach the Android host's IPC namespace unchanged. PRoot does
            // not create a user namespace (so it cannot isolate IPC anyway),
            // but explicit --sysvipc tells it not to even attempt to filter
            // IPC syscalls. Tiny Container uses this flag; without it some
            // bionic-bypass glibc binaries that call shmget(IPC_PRIVATE, ...)
            // can fail with ENOSYS under proot's default seccomp profile.
            add("--sysvipc")
            add("-b"); add("/dev")
            // F-5 (2026-08-25 audit): bind the guest's file-backed POSIX shm
            // directory over the host's /dev subtree. Android hosts have no
            // /dev/shm, so the whole-/-dev bind above would leave the guest
            // without POSIX shm; this rootfs-owned directory (created and
            // chmod 1777 by GuestRootfsInitializer.ensureGuestDevShm, mode
            // sticky+rw) is where glibc's shm_open lands — file-backed
            // (F2FS), no tmpfs mount, host /dev untouched. Empirically
            // verified against the vendored PRoot: a bind whose guest path is
            // LONGER than /dev wins the lookup, so placement after `-b /dev`
            // is what makes this the effective /dev/shm.
            // Fail-open like the shared-storage bind: a rootfs whose
            // /dev/shm could not be prepared keeps the historical view
            // instead of a bind to a missing source.
            devShmBindMount(rootfsPath)?.let { add("-b"); add(it) }
            // ── R-3 (Runtime Parity audit, 2026-08-26): /dev/fd + /dev/stdin
            // /dev/stdout /dev/stderr binds.
            //
            // Without these, `readlink /proc/self/fd/0` inside the guest
            // returns `socket:[NNNN]` or `pipe:[NNNN]` — the host-side fds
            // are not /dev paths. Programs that compare `tty` against
            // `/dev/*` (ncurses, fish, zsh, the `isatty()` + `ttyname()`
            // pair in glibc) then report "not a tty" with the host-side
            // socket/pipe as evidence. The fix binds /proc/self/fd/N to
            // /dev/stdin|stdout|stderr so the guest sees the same
            // `/dev/stdin` symlink shape that a real Linux distro provides.
            //
            // Tiny Container uses exactly this bind set. The bind source
            // /proc/self/fd is the host's (always-present) fd directory;
            // PRoot rewrites it relative to the tracee, so the symlink
            // targets stay live (each open() goes to the tracee's own fd N,
            // not the launcher's).
            add("-b"); add("/proc/self/fd:/dev/fd")
            add("-b"); add("/proc/self/fd/0:/dev/stdin")
            add("-b"); add("/proc/self/fd/1:/dev/stdout")
            add("-b"); add("/proc/self/fd/2:/dev/stderr")
            // ── R-4 (Runtime Parity audit, 2026-08-26): /dev/urandom →
            // /dev/random. Android's /dev/random is the same chardev as
            // /dev/urandom (both pull from the same CSPRNG), but some
            // software refuses to use /dev/random when it would block on a
            // real entropy-starved kernel. Tiny Container does this bind.
            add("-b"); add("/dev/urandom:/dev/random")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            // ── R-5 (Runtime Parity audit, 2026-08-26): /run bind.
            //
            // Debian/Ubuntu/Fedora userspace writes per-session runtime
            // state to /run (PID files, socket dirs, systemd runtime dirs,
            // utmp). Without a /run bind the guest's /run is whatever the
            // rootfs archive carried (often empty), so anything that
            // expects /run/user/$UID or /run/utmp fails. We bind a
            // rootfs-owned, 1777-mode directory over the guest /run path,
            // same shape as /dev/shm. GuestRootfsInitializer.ensureGuestRun
            // creates it.
            runBindMount(rootfsPath)?.let { add("-b"); add(it) }
            // ── R-6 (Runtime Parity audit, 2026-08-26): /proc bind-overs
            // for hidepid=invisible Android hosts. See ProcSnapshotInitializer
            // for what each file contains and why these are static.
            // Fail-open: a rootfs whose /proc snapshots could not be
            // prepared keeps the historical (Android-restricted) view.
            procBindOvers(rootfsPath).forEach { (guest, source) ->
                add("-b"); add("$source:$guest")
            }
            if (includeSharedStorage) {
                sharedStorageBindMount()?.let { add("-b"); add(it) }
            }
            // §1.73: per-workspace extra binds appended after the defaults,
            // before the command — matching how PRoot processes flags
            // sequentially (later binds win on a guest-path collision).
            extraBindMounts.forEach { bind ->
                add("-b"); add("${bind.host}:${bind.guest}")
            }
            addAll(command)
        }
        // §1.49 (Issue 4 fix): the env is built from the single host-side
        // source BootstrapManager.prootEnv() — every launch path (PTY,
        // exec, extraction) derives from the same function. Guest-side
        // defaults (HOME/USER/TERM/LANG/PATH) are layered on top, so the
        // config's `env:` block and workspace shells keep working.
        //
        // §1.190: `prootEnv` is layered on `guestLaunchEnv()`, the
        // guest-facing of the two env outputs BootstrapManager projects from
        // one computation. The distinction matters because PRoot passes this
        // map through to the traced guest process verbatim — see
        // `BootstrapManager.guestLaunchEnv` for what that implies for
        // `LD_LIBRARY_PATH`. Host-side helper processes that never enter a
        // guest (installer applets, tmux teardown, management terminal) use
        // `processEnv()` instead; do not swap one for the other here.
        //
        // F-8 (2026-08-25 audit): host-only variables no longer leak into
        // the guest. The vendored PRoot now carries the guest-env-filter
        // patch (proot-build/0004): every variable named in
        // PROOT_GUEST_ENV_FILTER whose value still matches the host
        // launcher's value is scrubbed from the tracee's environment at
        // execve time. The filter lists exactly the host-owned keys of
        // `prootEnv` (plus itself, so the control variable does not leak
        // either); the ROOTFS EXTRACTOR deliberately does not set it — its
        // tracee is the bundled bionic `tar`, which needs the host
        // LD_LIBRARY_PATH to link. Guest-authored values are untouched
        // (value-matched scrub, not name-matched).
        // Issue #1: `userName` predates the identity parameter; when the
        // caller left it at its default the account's own name wins, so
        // USER/LOGNAME can never disagree with the faked uid.
        val effectiveUserName = if (userName == DEFAULT_GUEST_USER) identity.name else userName
        // §1.193 (2026-11-13 cross-distro audit): fake-root (`-0`) launches
        // never use PRoot's seccomp syscall acceleration.
        //
        // PRoot's seccomp fast path intercepts `open`/`openat` and re-injects
        // some syscalls. On Android, the app process can be stopped
        // (SIGSTOP/freezer, backgrounding) in the middle of that window; when
        // the syscall is resumed, `open(O_CREAT|O_EXCL)` may run twice — the
        // first run creates the file, the guest is handed the second run's
        // `EEXIST`. The file exists, the guest believes it does not.
        // `mktemp` then retries forever (thousands of files) and pacman 7's
        // `_alpm_handle_lock` — which is an O_EXCL probe — refuses to run.
        // This is fake-root-specific: the observed corruption needs
        // fake_id0's intercepted open path (normal `--change-id` sessions
        // were unaffected across the same device runs), and it is
        // distro-neutral — any guest program using O_EXCL can hit it.
        //
        // The fix belongs here, at launch construction, not in any package
        // manager: disable the acceleration for the identity that corrupts,
        // and keep it for normal-user terminals. The performance cost is
        // confined to privileged operations (sudo one-shots, Root Shell,
        // package management, root daemons).
        //
        // PROOT_NO_SECCOMP is already in GUEST_ENV_FILTER, so this host-side
        // variable is scrubbed from the tracee's environment at execve time
        // and never becomes visible inside the guest.
        val identitySeccompEnv =
            if (identity.isRoot) BootstrapManager.NO_SECCOMP_ENV else emptyMap()
        return LaunchSpec(
            executable = binaryPath,
            args = args,
            env = bootstrapManager.prootEnv(prootTmpDir) + identitySeccompEnv + mapOf(
                "PROOT_GUEST_ENV_FILTER" to GUEST_ENV_FILTER,
                // Issue #4: HOME is the declared guest home; the process cwd
                // (-w) is the declared startup directory — usually the
                // workspace. The two are separate concerns now.
                "HOME" to homeDirectory,
                // Issue #1: USER + LOGNAME carry the emulated account so
                // `whoami`, `logname`, login-reporting tools and
                // prompt-building all resolve the same identity.
                "USER" to effectiveUserName,
                "LOGNAME" to effectiveUserName,
                // Issue #1: SHELL reflects the account's login shell so
                // `echo $SHELL`, `chsh -s` comparisons and tools that spawn
                // "the user's shell" behave like a normal Linux session.
                "SHELL" to (shellPathForEnv ?: identity.shell),
                "TERM" to "xterm-256color",
                // §26.2 (terminal audit): the terminal module's emulator is a full
                // truecolour implementation — TextStyle carries
                // CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND/_BACKGROUND and
                // TextStyle.encode has a 24-bit path — but `TERM` alone cannot
                // say so: `xterm-256color` advertises 256 indexed colours, and
                // there is no terminfo capability for 24-bit. Tools that can
                // emit truecolour (bat, delta, fastfetch themes) therefore gate
                // it on this de-facto-standard variable and silently downgrade
                // to 256 colours when it is absent, which is what the audit's
                // "incomplete colours" symptom looks like from the guest side.
                //
                // Deliberately NOT added to BootstrapManager.HOST_ENV_KEYS: it
                // is a guest-facing *capability hint*, not host infrastructure,
                // so an imported image's config.yaml may legitimately override
                // or clear it (e.g. an image whose tooling misbehaves on
                // truecolour). Host-owned keys exist to protect Linexa's own
                // proot/tmux plumbing, and this touches none of it.
                "COLORTERM" to "truecolor",
                // F-10 (2026-08-25 audit): C.UTF-8, not en_US.UTF-8. The stock
                // Debian/Ubuntu rootfs archives ship NO generated locales
                // (`locales` is not installed), so asserting en_US.UTF-8 made
                // every locale-aware guest binary fall back to C/POSIX while
                // printing "setlocale: LC_ALL: cannot change locale" warnings —
                // the audit's T-10 signature (warning-free `perl -e 'print 1'`
                // was impossible). C.UTF-8 is built into glibc ≥ 2.35 (and
                // musl ignores LANG entirely), needs no locale generation, and
                // preserves UTF-8 handling — the property the value exists
                // for. A workspace whose config.yaml sets its own LANG keeps
                // it: this is the default layer, not a HOST_ENV_KEY.
                "LANG" to "C.UTF-8",
                // Guest-side PATH (what the shell inside the rootfs sees).
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            ),
            workingDirectory = workingDirectory
        )
    }

    /**
     * §1.51 (Issue 6 audit, 2026-08-03): the deliberate bind-mount list.
     *
     * Every mount below is a host bind — PRoot exposes the *host's* live
     * filesystem so the guest sees real device nodes, kernel interfaces,
     * and the app's shared storage. Each is reasoned about here so the list
     * doesn't silently rot:
     *
     * - `/dev` — the host's full device tree. Covers the device-node edge
     *   cases distro rootfs images commonly get wrong (missing `/dev/null`,
     *   `/dev/urandom`/`/dev/random` as empty or blocked stubs); a bare
     *   rootfs archive typically ships few or no device nodes, and without
     *   this bind `/dev/urandom`-based entropy consumers (openssl, some
     *   package managers) break immediately. Tiny Container binds the same
     *   whole `/dev`.
     * - `/proc` — process/kernel interfaces; `/proc/self/...` and the
     *   pid/stat machinery are required by virtually every Linux userspace
     *   (shells, ps, package managers).
     * - `/sys` — kernel/subsystem interfaces; needed by udev-adjacent and
     *   hardware-detection tools (Arch/Fedora package hooks).
     * - shared storage (`{externalFilesDir}/shared:/mnt/shared`) — the
     *   app's user-visible shared folder, the only deliberately
     *   app-specific mount. Bound only when the directory exists so the
     *   flags stay identical across fresh installs (PRoot accepts binds to
     *   nonexistent sources, but a missing mount source makes the guest
     *   path appear empty; skipping keeps behavior predictable). §1.82:
     *   additionally gated on the workspace's Shared Storage feature toggle
     *   by the caller — when the user disables it the bind is dropped even
     *   if the directory exists, so the feature toggle is real, not just a
     *   UI preference.
     *
     * Deliberately NOT bound: `/sdcard`/emulated storage directly (the
     * app's shared folder is the sandboxed, permission-scoped surface),
     * and no per-distro extras — Alpine/Debian/Ubuntu/Arch/Fedora all boot
     * a login shell with this set, per the supported-distro list (§0).
     * Add a new bind only when a concrete on-device failure demonstrates
     * the need, and update this comment when you do.
     *
     * ── §1.190: bionic-linked guest binaries are NOT supported ───────────
     *
     * Every guest in §0 is glibc (or musl, on Alpine). A rootfs containing
     * *bionic*-linked binaries — an Android system image, a Termux prefix
     * copied into a rootfs — will not boot under this bind set, and that is
     * a deliberate non-goal, not a bug to be filed.
     *
     * Verified on-device 2026-08-23 against a scratch rootfs of bionic
     * busybox, the reasons in the order they bite:
     *
     * 1. `/system` is not bound. A bionic binary's ELF interpreter is
     *    `/system/bin/linker64`; absent that path PRoot cannot exec it at
     *    all and reports the generic `execve(...): No such file or
     *    directory` with its "interpreter was not found" hint.
     * 2. `/apex` is not bound. Binding `/system` alone is insufficient —
     *    `/system/bin/linker64` is itself a symlink to
     *    `/apex/com.android.runtime/bin/linker64`, so the interpreter is
     *    still unreachable without `/apex` too.
     * 3. `/linkerconfig` CANNOT be bound. PRoot fails with
     *    `can't sanitize binding "/linkerconfig": Permission denied` — the
     *    directory is unreadable to an unprivileged app uid. With `/system`
     *    and `/apex` bound the guest gets far enough to link, but every
     *    exec then emits `linker: Warning: failed to find generated linker
     *    configuration from "/linkerconfig/ld.config.txt"` on stderr,
     *    which corrupts a terminal session's output stream.
     * 4. The §1.190 guest-launch env carries a host `LD_LIBRARY_PATH`
     *    (see `BootstrapManager.guestLaunchEnv` for why it cannot be
     *    dropped). A bionic guest *does* consult it and then fails to
     *    resolve its own libraries.
     *
     * Supporting bionic guests means solving (3), which has no app-uid
     * solution short of shipping a synthetic `ld.config.txt`. Do not add
     * `/system` or `/apex` binds piecemeal: they expand the guest's view of
     * the host for zero benefit while (3) stands.
     */
    private fun sharedStorageBindMount(): String? {
        val sharedStorage = File(application.getExternalFilesDir(null), "shared")
        if (!sharedStorage.exists()) return null
        return "${sharedStorage.absolutePath}:/mnt/shared"
    }

    /**
     * F-5: the `-b {rootfs}/dev/shm:/dev/shm` bind source, or null when the
     * rootfs has no /dev/shm directory (yet) — see the call site in
     * [buildLaunchSpec] for why this must be a real directory and why the
     * bind is placed directly after `-b /dev`.
     */
    private fun devShmBindMount(rootfsPath: String): String? {
        val shm = File(File(rootfsPath), "dev/shm")
        if (!shm.isDirectory) return null
        return "${shm.absolutePath}:/dev/shm"
    }

    /**
     * R-5: the `-b {rootfs}/run:/run` bind source, or null when the rootfs
     * has no /run directory (yet) — [GuestRootfsInitializer.ensureGuestRun]
     * creates it. Fail-open like [devShmBindMount].
     */
    private fun runBindMount(rootfsPath: String): String? {
        val run = File(File(rootfsPath), "run")
        if (!run.isDirectory) return null
        return "${run.absolutePath}:/run"
    }

    /**
     * R-6: the list of `(guestPath, hostSource)` pairs for /proc bind-overs.
     *
     * Each pair is a single `-b hostSource:guestPath` argv token that overlays
     * a static snapshot file from the rootfs's `/proc/` over the guest's
     * Android-restricted `/proc/<name>` path. The snapshot files are produced
     * by [com.fem.linexa.service.installer.GuestRootfsInitializer.ensureProcSnapshots]
     * before the launch — only entries whose snapshot file exists are
     * returned, so a fresh or imported rootfs self-heals without binding
     * to a missing source.
     *
     * The set is the same one Tiny Container's bind-over mechanism exposes:
     * `stat`, `uptime`, `loadavg`, `version`, `vmstat`. The values are
     * static snapshots (uptime/loadavg do NOT tick) — same as Tiny
     * Container's frozen overlays. The point is to give `top`/`btop`/`vmstat`
     * a non-`EACCES` reading so they don't refuse to start; CPU bars may
     * show stale data, but that is strictly better than the audit's
     * `btop fails to start` symptom.
     */
    private fun procBindOvers(rootfsPath: String): List<Pair<String, String>> {
        val procDir = File(File(rootfsPath), "proc")
        if (!procDir.isDirectory) return emptyList()
        val flat = PROC_BIND_OVER_NAMES.mapNotNull { guestName ->
            val sourceFile = File(procDir, "." + guestName)
            if (!sourceFile.isFile) return@mapNotNull null
            "/proc/$guestName" to sourceFile.absolutePath
        }
        val nested = PROC_NET_BIND_OVERS.mapNotNull { (relativeName, guestPath) ->
            val sourceFile = File(procDir, relativeName)
            if (!sourceFile.isFile) return@mapNotNull null
            guestPath to sourceFile.absolutePath
        }
        return flat + nested
    }

    // ── tmux-backed persistent sessions (§1.20 SessionManager v2) ─────────
    //
    // Design note on *where* tmux runs: tmux wraps PRoot from the outside —
    // `tmux new-session ... <libproot.so> -r <rootfs> ... /bin/bash` — not
    // the other way around. tmux is a host-side static binary (bundled the
    // same way libproot.so is, see jniLibs/README-tmux.md), so nothing
    // inside the guest rootfs needs tmux installed; a stock Debian/Ubuntu/
    // Fedora minimal rootfs image very likely doesn't ship it.
    //
    // §1.76: tmux is NOT on the startup path anymore. The pty child used to
    // be the tmux *client*, which exits 0 right after the server accepts the
    // session, closing the pty with it (the terminal showed "[exited]").
    // Launch now execs proot directly (PTY → proot → bash --login), matching
    // tiny_container's direct-shell-in-pty shape. This block — and
    // [wrapWithTmux]/[tmuxKillServerArgs]/[TmuxPersist] — is retained intact
    // for a future explicit "persist session across app restarts" feature;
    // nothing in the launch path calls it. The `-A` semantics ("create or
    // reattach") still apply if that feature is ever wired back in: a live
    // server on the socket is attached, otherwise a fresh one is created.

    /** §1.43: Path to the tmux binary — executed from nativeLibraryDir (same SELinux reason as proot). */
    val tmuxBinaryPath: String
        get() = File(application.applicationInfo.nativeLibraryDir, "lib__bin__tmux__.so").absolutePath

    /**
     * Whether persistent sessions are available. False until a real tmux
     * binary is bundled (it is, as of §1.26) — deliberately a runtime file
     * check, not a build flag, so the app degrades to plain-shell behavior
     * rather than failing to start if the binary is ever missing/removed.
     */
    val isTmuxAvailable: Boolean
        get() = File(tmuxBinaryPath).exists()

    /** Per-workspace tmux control socket (§1.58: shared with [tmuxKillServerArgs]). */
    fun tmuxSocketPath(workspaceId: String): String {
        val dir = File(application.filesDir, "workspaces/$workspaceId")
        dir.mkdirs()
        return TmuxPersist.socketPath(dir)
    }

    /**
     * Args (without executable) to tear down the workspace's persistent tmux
     * server. §1.58: stopping a pty only kills the tmux client; this is how a
     * stop actually stops the detached server and the guest shell inside it.
     * Harmless when no server is running.
     */
    fun tmuxKillServerArgs(workspaceId: String): List<String> =
        TmuxPersist.killServerArgs(tmuxSocketPath(workspaceId))

    /**
     * Wraps [inner] (an already-built PRoot [LaunchSpec]) so that running it
     * creates-or-reattaches a persistent tmux session for [workspaceId],
     * instead of running the shell directly. Only called when
     * [isTmuxAvailable] — callers should check that first.
     *
     * Transformation spec §9 (per-terminal persistence): [terminalId] keys
     * the session name ([TmuxPersist.sessionNameFor]) — every terminal of a
     * persistence-enabled workspace gets its OWN tmux session on the
     * workspace's socket, and a re-opened terminal id reattaches to its
     * own session. The previous single shared name made the second terminal
     * reattach to the first one's session (two tabs rendering one shell).
     */
    fun wrapWithTmux(inner: LaunchSpec, workspaceId: String, terminalId: String): LaunchSpec {
        return inner.copy(
            executable = tmuxBinaryPath,
            args = TmuxPersist.newSessionArgs(
                socketPath = tmuxSocketPath(workspaceId),
                sessionName = TmuxPersist.sessionNameFor(terminalId),
                innerExecutable = inner.executable,
                innerArgs = inner.args
            )
        )
    }

    companion object {
        const val DEFAULT_GUEST_HOME = "/root"

        /** Historical (and emulated-uid-honest) guest identity name. */
        const val DEFAULT_GUEST_USER = "root"

        /** Default workspace directory name under the guest home (issue #4). */
        const val DEFAULT_WORKSPACE_DIR_NAME = "linexa"
        const val TMUX_SESSION_NAME = TmuxPersist.DEFAULT_SESSION_NAME

        /**
         * R-1 (2026-08-26 runtime parity audit): the kernel release string
         * PRoot reports for the guest when `--kernel-release` is set. Parsed
         * by glibc `uname()` into the `release` field of `struct utsname`,
         * which is what `uname -r` and `uname -a` print. The full `uname -a`
         * output is then `Linux <hostname> <release> #<version> <arch>
         * GNU/Linux` — PRoot only controls the `release` field; the kernel
         * `#version` line stays the host's. Tiny Container uses a similar
         * shape (`6.17.0-PRoot-Distro`).
         *
         * The release intentionally:
         *  - matches a real modern kernel major (6.x) so version-probing
         *    software (Node.js, glibc internals that test
         *    `/proc/sys/kernel/cap_last_cap` for feature gating, etc.) does
         *    not fall through a legacy code path.
         *  - carries a `-linexa` local version suffix so audit tools
         *    (systemd-detect-virt, the `proot` keyword in release string) can
         *    identify the runtime.
         *  - stays aarch64-relevant: stock Linux aarch64 defconfig builds for
         *    this major support every SoC feature the audit enumerated
         *    (SVE/SVE2/i8mm/bf16 — `cpuinfo` is unaffected by this spoof).
         */
        const val SPOOFED_KERNEL_RELEASE: String = "6.1.0-linexa"

        /**
         * R-6 baseline set PLUS the 2026-08-27 conditional fallbacks that
         * live as flat files in `{rootfs}/proc/` (`cpuinfo` / `meminfo` /
         * `swaps`, emitted ONLY where the native path probes unreadable —
         * on devices where passthrough is readable, nothing changes and the
         * richer native view wins). Each name is BOTH the guest path
         * (`/proc/<name>`) AND the snapshot file name (`/proc/.<name>`);
         * see [procBindOvers]. Alphabetical.
         */
        val PROC_BIND_OVER_NAMES: List<String> = listOf(
            "cpuinfo",
            "loadavg",
            "meminfo",
            "stat",
            "swaps",
            "uptime",
            "version",
            "vmstat"
        )

        /**
         * Network subtree bind-overs: `(sourceRelativeName, guestPath)`.
         * Sources live under `{rootfs}/proc/.net/` written by the runtime
         * bridge from REAL collector data (TrafficStats counters,
         * LinkProperties routes/addresses). Bound whenever present —
         * presence itself encodes "synthesized content produced this cycle".
         */
        val PROC_NET_BIND_OVERS: List<Pair<String, String>> = listOf(
            ".net/dev" to "/proc/net/dev",
            ".net/route" to "/proc/net/route",
            ".net/if_inet6" to "/proc/net/if_inet6"
        )

        /** Drift-guard union the bridge checks every write batch against. */
        val PROC_BIND_OVER_RELATIVE_PATHS: Set<String> = buildSet {
            addAll(PROC_BIND_OVER_NAMES)
            addAll(PROC_NET_BIND_OVERS.map { it.first.removePrefix(".") })
        }

        /**
         * F-8 (2026-08-25 audit): the comma-separated list of host-owned
         * environment variables the vendored PRoot (patch
         * `proot-build/0004-guest-env-filter.patch`) scrubs from the tracee's
         * environment at execve time — but ONLY when the value is still
         * byte-identical to the host launcher's value, so guest-authored
         * values of the same names keep working.
         *
         * The list is exactly the host-owned key set [BootstrapManager] can
         * project into `prootEnv` (`LD_LIBRARY_PATH`, `TERMINFO`) plus
         * PRoot's own plumbing (`PROOT_TMP_DIR`, `PROOT_LOADER`) and the
         * control variable itself, so none of them reach the guest. It must
         * be kept in sync with [BootstrapManager.HOST_ENV_KEYS] — the
         * [com.fem.linexa.test.support.ProotRuntimeTestHarness]-level test
         * `PRootLauncherGuestEnvFilterTest.coversEveryHostEnvKey` enforces
         * that mechanically.
         *
         * Pre-release audit fix (2026-09-02): `PROOT_NO_SECCOMP` (the §1.50
         * fallback flag, host-owned) used to leak into the guest `env`, and
         * `LD_PRELOAD`/`LD_AUDIT` were added to HOST_ENV_KEYS as a loader
         * -injection guard — all three are listed here now, keeping the
         * filter in lockstep with HOST_ENV_KEYS.
         */
        const val GUEST_ENV_FILTER: String =
            "LD_LIBRARY_PATH,LD_PRELOAD,LD_AUDIT,PROOT_TMP_DIR,PROOT_LOADER,PROOT_NO_SECCOMP,TERMINFO,PROOT_GUEST_ENV_FILTER"
    }
}
