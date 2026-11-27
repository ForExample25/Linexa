# Runtime Architecture

**Source locations:** `app/src/main/java/com/fem/linexa/service/runtime/`,
`app/src/main/java/com/fem/linexa/domain/runtime/`,
`app/src/main/java/com/fem/linexa/service/installer/`

This document describes the PRoot runtime: process launch paths, PTY integration, session
lifecycle, environment construction, the guest compatibility surface, and the RootFS
installation pipeline. Feature-level behavior is covered in [`../features/`](../features/).

## 1. Module map

| Package | Role | Key classes |
|---|---|---|
| `service.runtime` | Session and process orchestration | `RuntimeService`, `RuntimeBinder`, `SessionManager`, `WorkspaceTerminalManager`, `WorkspaceSessionSupervisor`, `RuntimeLifecycleManager`, `TerminalSession`, `RuntimeTrace` |
| `service.runtime.pty` | PTY abstraction over the vendored terminal-emulator | `PtySession`, `PtySessionFactory`, `TermuxPtySession`, `TermuxPtySessionFactory`, `TermuxTerminalSessionProvider` |
| `service.runtime.container` | PRoot process construction and lifecycle | `PRootLauncher` (argv/environment builder), `PRootContainerRuntime` (sessions, exec, daemons), `ContainerRuntime`, `TmuxPersist`, root-exec bridge |
| `service.runtime.bootstrap` | Bundled-binary layout and environment | `BootstrapManager`, `BootstrapManifest`, `BusyboxAppletLayer` |
| `service.runtime.features` | Guest feature provisioners | SSH, Xvnc, notify-send, clipboard/audio bridges, feature supervisors and reconciler |
| `service.runtime.hostinfo` | Host-information bridge | `HostInfoRuntimeBridge`, `ProcSnapshotContentFactory` |
| `service.runtime.wakelock` | Wake-lock policy | `WorkspaceWakeLockManager`, `WakeLockPolicy`, `HostWakeLock`, `AndroidWakeLock` |
| `service.installer` | Staged RootFS install | `InstallCoordinator`, `StagedRootFsInstaller`, `RootfsExtractor`, `L2sLinkRewriter`, `RootfsFixupService`, `GuestRootfsInitializer`, `OciLayerUnwrapper`, `InstallRecoveryManager`, `RootFsProbe` |
| `domain.runtime` | Pure runtime models | `RuntimeState`, `TerminalState`, `CommandResult`, `FeatureIds` |
| `service.rootfs(.profile)` | Archive, metadata, and profile machinery | `RootFsDescriptor`, `CompressedArchiveStream`, `ArchiveSignalCollector`, `RootFsProfileCatalog` |

## 2. Process launch paths

| Path | Spawns | stdio | TTY | Used by |
|---|---|---|---|---|
| `openTerminal` → `TermuxPtySession` → `libtermux.so` | PRoot directly (no tmux) | PTY master/slave (`/dev/ptmx` + `/dev/pts/N`) | Yes (setsid + controlling terminal) | Workspace terminals, Root Shell, management terminal |
| `execInternal` → `runProcess` | PRoot `/bin/sh -c <command>` through `ProcessBuilder` | Pipes; stdin is a never-written pipe | No | Package Manager, File Manager, provisioners, dpkg preflight, probes |
| `startDaemon` → `launchDaemonProcess` | PRoot `/bin/sh -c 'exec <daemon>'` through `ProcessBuilder` | File-backed stdout | No | Xvnc, sshd, managed guest daemons |

tmux is vendored but is not the core PTY child. `feature.terminal.persistence` (default
off) uses `TmuxPersist` to wrap launches with an opt-in tmux session. Closing a terminal
does not stop the workspace; workspace lifecycle is entered/left explicitly.

### 2.1 Process ownership and termination

A process is identified by **PID + `/proc/<pid>/stat` start time (field 22) + UID**, not by
PID alone. Android recycles PIDs aggressively, so a signal is only safe while the start time
still matches the value captured at launch. The process group is recorded as well, but only
used when `/proc` proves the host is its leader (`pgid == pid`); the bundled busybox has no
`setsid`, so a new group cannot be imposed on every launch.

Every daemon, one-shot exec, and native extraction captures its host identity immediately
after `ProcessBuilder.start()` (`captureProcessIdentity()`). The identity is **never
re-derived at stop time**: by then the child may be reaped and its PID reused, and
`/proc/<pid>/stat` would describe a stranger. The one exception is the terminal pty child's
process group: the child calls `setsid()` just after `fork()`, so the launch-time read can
race it. `closeTerminal` re-reads the group from the same PID **only while the captured start
time still matches**, which fixes the race without weakening the identity pin.

Closing a terminal is therefore a component teardown, not just a pty kill:

1. Remove the session from the map (no new attach can find it).
2. Run `ProcessTreeTerminator` over the pty child's captured identity — PRoot ignores
   SIGHUP/SIGTERM, and SIGKILLing only the tracer orphans its tracees to init.
3. `destroy()` the pty session, wait up to `TERMINAL_EXIT_GRACE_MS` for the exit, and release
   the terminal's `PROOT_TMP_DIR` scope **only if** termination was confirmed. An unconfirmed
   survivor keeps its scratch tree; the next open of that scope reclaims it.

Component termination (`ProcessTreeTerminator`, used by daemon stop, exec timeout, and the
extraction timeout) works in this order:

1. Snapshot the own-UID `/proc` table **before** signalling. PRoot tracees are children of
   the PRoot host; after the host dies they reparent to init and the PPID chain is gone.
2. Build the target set from the snapshot: the rooted host, its live descendants, and — when
   the host is a group leader — every live member of that group. This covers a member that
   double-forked and left the PPID chain while remaining in the group.
3. Refuse incomplete identities and foreign UIDs. Send SIGTERM to each still-identical
   member, wait a bounded grace window, re-snapshot descendants of the survivors, then send
   SIGKILL and verify every target is gone (row vanished, start time changed, or zombie).
4. Report `confirmed` only when no survivor remains. An unconfirmed result is logged and the
   caller must not treat the tree as dead.

No component operation performs a workspace-wide cmdline sweep. `killRootfsProcesses()` is
retained only for two whole-workspace operations:

- `closeWorkspaceSessions()` — final teardown after every owned component has already been
  stopped; catches genuine leftovers of that rootfs.
- `startupRecoverySweep()` — a fresh app process owns no live sessions, so own-UID processes
  still referencing a workspace rootfs path are orphans of a previous app process.

A dead daemon host contributes no targets, so stopping it sends **zero signals**; if the host
identity was never captured, termination degrades to the recorded `Process` object only and
logs that guest-tree containment was unavailable. It never falls back to the workspace sweep.
`stopAllDaemons()` removes all requested daemons first and runs one termination pass for the
whole batch, so `N` daemons do not cause `N` `/proc` scans.

## 3. Session lifecycle

1. `RuntimeViewModel.bindService()` starts and binds `RuntimeService` with
   `BIND_AUTO_CREATE`. The workspace graph requests `startWorkspace()`.
2. `RuntimeService.onCreate` builds `RuntimeBinder` and `WorkspaceSessionSupervisor`.
   `onStartCommand` starts the foreground notification and launches the session.
3. `WorkspaceSessionSupervisor.run()` evaluates `WorkspaceLaunchValidator`, requests a
   start through `RuntimeLifecycleManager`, evicts any other active workspace, and calls
   `WorkspaceTerminalManager.openTerminal(...)`.
4. `PRootContainerRuntime.openTerminal()` performs bootstrap self-healing, per-terminal
   `PROOT_TMP_DIR` setup, guest `/tmp` repair, dpkg health checks and optional
   `dpkg --configure -a`, config overrides, the seccomp probe, and PTY creation.
5. PTY creation is dispatched to the main-Looper dispatcher. The vendored
   `TerminalSession` captures the constructing thread's Looper and routes emulator writes
   through it, so construction must not happen on an arbitrary worker thread.
6. The supervisor waits for session end. Only `closeWorkspace` completes that wait; a
   terminal exiting does not itself stop the workspace.

### 3.1 Terminal exit statuses and 127 diagnosis

The PTY reports one status per terminal. `PtySession.awaitExit` returns
`waitpid(2)`'s value: a normal exit is `WEXITSTATUS` in `0..255`, a signal death is
`-WTERMSIG`. `domain/runtime/TerminalExitDescription` is the single table that turns that
number into the reason and hint the panel shows; the raw code is always preserved. An
unobtainable status is `null` and is rendered as "unavailable" — it is never coerced to
`-1`, which the table would read as a real SIGHUP.

| Status | Meaning | Hint |
|---|---|---|
| `126` | Command found but not executable | Check the executable bit and the shebang interpreter. |
| `127` | Command or shell not found | Check `launch.shell` / `environment.shell` and that the command exists in the image. |
| `137` | Exited **as if** killed by SIGKILL | A guest process may have been killed; Android may have reclaimed memory. |
| `139` | Exited **as if** killed by SIGSEGV | A guest binary or interpreter crashed; check the image and the command. |
| `-9` | Kernel wait status: killed by SIGKILL | Android may have reclaimed the terminal. Also gates the SIGKILL battery notice. |
| `-11` / `-15` | Kernel wait status: SIGSEGV / SIGTERM | Named directly. |
| other | Generic "exited unexpectedly", raw code kept | Open the session diagnostics. |

`137`/`139` are **hedged**: they are the shell convention `128 + signal`, but a guest can
also `exit 137` itself, and an OOM kill arrives as `137` rather than `-9`. Only a negative
wait status is kernel evidence of a signal. The terminal's SIGKILL battery notice is
therefore shown only for the direct `-9` status, never for `137`.

A **live** pty with no output is a separate case from an exit. `TerminalViewModel` starts a
first-output watchdog when it resolves the session; if no output arrives within eight
seconds and the pty is still alive, the panel overlays a non-blocking "No output yet" banner
with Diagnostics and Restart. The terminal view stays mounted, the pty is never destroyed,
and a prompt captured before the screen attached counts as output. A child that actually
exits takes the normal `TerminalState.Error` path and its error card instead.

To diagnose a `127`:

1. Run `command -v <name>` in the guest. `127` is the shell's "not found" status, so a typo
   or a package that is not installed is the first check. (`command -v` is used throughout
   the runtime; `which` is not assumed to exist.)
2. If the command exists, check the launch shell. A `launch.shell` or `environment.shell`
   that is missing or not executable makes every command exit `127`. The profile validator
   rejects such a path and falls through to the next source before launch, so a persisted
   bad override is logged rather than silently used.
3. Use **Diagnostics** on the terminal error card. It reports the terminal id, raw status
   (or `unavailable`), reason, hint, bounded tail of the session output, and the workspace
   runtime trace.
4. If the status is `137`, check logcat for `lmkd`/Android memory pressure before assuming
   the guest's own command was killed.

## 4. Environment construction

`BootstrapManager.bootstrapEnv()` supplies the host-side bootstrap layout:

```
PATH={filesDir}/bootstrap/bin
LD_LIBRARY_PATH={filesDir}/bootstrap/lib
PROOT_LOADER={filesDir}/bootstrap/bin/loader
TERMINFO={filesDir}/terminfo   (when usable)
```

`PRootLauncher.buildLaunchSpec` layers the guest environment on top. An
interactive terminal resolves its identity from the workspace's RootFS metadata and
`/etc/passwd`: the default account is `linexa` with `HOME=/home/linexa`, and the
launch uses `--change-id=<uid>:<gid>` so `id`, `whoami`, and the prompt reflect that
account. If no usable account exists, the launch falls back to root emulation.
Privileged one-shot operations, root daemons, and the Root shell use `-0` with
`HOME=/root` and `USER=root`. Every launch sets `TERM=xterm-256color`,
`COLORTERM=truecolor`, `LANG=C.UTF-8`, `SHELL`, and a guest `PATH`. Per-workspace
`config.yaml` values are filtered against the host-key allowlist by `applyConfigEnv`.

The interactive shell precedence is `launch.shell` → `environment.shell` → probe.
Both declared values are validated by `GuestShellValidator` against the rootfs before
use: the path must be absolute, resolve without escaping the rootfs (symlinks are
followed inside it, with bounded hops), and name a regular executable file. A script's
shebang interpreter, or an ELF binary's `PT_INTERP` loader, must also exist and be
executable inside the rootfs. A rejected declaration is logged with its reason and falls
through to the next source; it never reaches `exec`.

`PROOT_GUEST_ENV_FILTER` scrubs host-only variables at the exec boundary, including
`LD_LIBRARY_PATH`, `PROOT_TMP_DIR`, `PROOT_LOADER`, `TERMINFO`, and the filter variable
itself. Guest-authored values are preserved. The rootfs extractor deliberately does not set
the filter because its bundled bionic `tar` needs the host library path.

## 5. Seccomp fallback

On the first terminal open, `resolveSeccompFallback` runs one bounded probe
(`/bin/sh -c "exit 0"`, 30 s). If the known seccomp failure marker is observed,
`BootstrapManager.needsNoSeccomp` is set and every subsequent launch pre-applies
`PROOT_NO_SECCOMP=1`. `execInternal` also retries once when it sees the failure signature.
The installer uses the same flag. Root-emulated launches always set the flag, as described
in [`../features/ROOT_SHELL_AND_SUDO.md`](../features/ROOT_SHELL_AND_SUDO.md).

## 6. Guest compatibility surface

PRoot binds the host `/proc`, `/sys`, and `/dev` into the guest. Android's
`untrusted_app` SELinux domain and `hidepid` restrictions make several `/proc` files
unreadable or incomplete, so Linexa overlays selected files with a stable guest-facing view.

- Baseline snapshot files are written by
  `GuestRootfsInitializer.ensureProcSnapshots()` under `{rootfs}/proc/.<name>`.
- At every launch, `HostInfoRuntimeBridge.refreshProcOverlays()` rewrites those files from
  the host-information provider. While a workspace is running, `RuntimeService` drives a
  2-second overlay ticker for running workspaces.
- `PRootLauncher.procBindOvers()` binds each present snapshot over the corresponding guest
  path. The set includes `cpuinfo`, `loadavg`, `meminfo`, `stat`, `swaps`, `uptime`,
  `version`, and `vmstat`, plus the `/proc/net` files `dev`, `route`, and `if_inet6`.
- If a snapshot cannot be written, the bind is skipped and the guest sees the underlying
  PRoot/Android view. Baseline synthetic values are the fallback when the host cannot
  provide a real value.

Values that Android exposes are used directly; values that are genuinely unavailable are
reported as unavailable rather than fabricated as zero. `/dev/shm` is created inside the
rootfs with mode `1777` and bound over `/dev/shm`; `/run` is created with mode `1777` and
bound over `/run`.

The kernel release is spoofed with `--kernel-release=6.1.0-linexa` so guest software does
not see the Android kernel string. `--sysvipc` is emitted so guest IPC syscalls use the host
IPC namespace. `/proc/self/fd` is bound to `/dev/fd`, `/dev/stdin`, `/dev/stdout`, and
`/dev/stderr` so guest TTY detection sees the expected symlink shape.

## 7. RootFS installation pipeline

```
DownloadService / ImportService -> StagedRootFsInstaller
  Pass 1 (JVM)   analyze(): SHA-256, archive validation, profile resolution, OCI layout
  Pass 2 (native) RootfsExtractor.extract(): proot --link2symlink tar -xf ... -C staging
  Pass 2.5       L2sLinkRewriter.rewrite(staging): relative .l2s.* targets
  Pass 3         validateExtractedRootfs(): profile-required directories and shell
  Pass 3.5       runInstallSteps(): ENSURE_TMP, ENSURE_DEV_SHM, ANDROID_ACCOUNT_FIXUP,
                 SEED_RESOLV_CONF; RootfsFixupService and GuestRootfsInitializer
  Pass 4         writeWorkspaceConfig(): captured config.yaml
  Activation     WorkspaceFileManager.activateStagedRootfs(): live -> rootfs.previous-<ts>,
                 staging -> rootfs, with restore on failure
```

`InstallRecoveryManager` handles crashes between phases at application startup. Before every
session, exec, or daemon launch, the runtime self-heals the guest `/tmp`, `/dev/shm`,
`/run`, `/proc` snapshots, and dpkg state.

## 8. Session lifecycle diagram

```mermaid
graph TD
    subgraph UI["Compose UI"]
        WTP[Workspace terminal panel]
        RTV[RealTerminalView]
    end

    subgraph VM["View models"]
        TVM[TerminalViewModel]
        RVM[RuntimeViewModel]
    end

    subgraph Service["RuntimeService"]
        RB[RuntimeBinder]
        WSS[WorkspaceSessionSupervisor]
        RLM[RuntimeLifecycleManager]
    end

    subgraph Container["PRootContainerRuntime"]
        OT[openTerminal]
        BS[Bootstrap self-heal]
        DH[tmp, shm, run, proc, dpkg checks]
        PS[PTY creation on main Looper]
    end

    subgraph PTY["PTY"]
        TPS[TermuxPtySession]
        NL[libtermux.so: /dev/ptmx + /dev/pts/N]
    end

    subgraph Guest["Guest rootfs"]
        SH[/bin/bash --login]
    end

    WTP --> TVM
    TVM --> RB
    RTV -->|attachSession| RB
    RB -->|getPtySession| WSS
    WSS --> RLM
    RLM -->|launchSession| OT
    OT --> BS
    OT --> DH
    OT --> PS
    PS --> TPS
    TPS --> NL
    NL -->|exec proot| SH
```

## 9. Dependencies and interactions

- `PRootContainerRuntime` depends on `PRootLauncher`, `PtySessionFactory`,
  `WorkspaceFileManager`, `WorkspaceConfigParser`, `BootstrapManager`, `RuntimeTrace`,
  `GuestRootfsInitializer`, `WorkspaceRepository`, and `WorkspaceRootFsMetadata`.
- `StagedRootFsInstaller` depends on `WorkspaceFileManager`, `ArchiveValidator`,
  `BootstrapManager`, `RootfsExtractor`, `L2sLinkRewriter`, `RootfsFixupService`,
  `GuestRootfsInitializer`, `OciLayerUnwrapper`, and `RootFsProfileCatalog`.
- `TermuxPtySessionFactory` is the default `PtySessionFactory`. The terminal-emulator
  internals are consumed by `TermuxPtySession`, `TermuxTerminalSessionProvider`, and the
  terminal view.

## 10. Runtime characteristics and limits

- The one-shot exec path has no TTY by design. `DEBIAN_FRONTEND=noninteractive` is layered
  in to prevent debconf stalls.
- Android `/proc` visibility limits what the guest and the host process list can observe.
  Linexa overlays the files it can answer and leaves the rest to PRoot.
- After app-process death there is no reattach path. Runtime state resets and the startup
  recovery sweep reaps orphaned workspace processes. Per-component stops never use that
  sweep; they terminate their launch's recorded process tree (see §2.1).
- x86_64 hosts are not supported because no x86_64 PRoot binary is packaged.
- The guest `/proc` overlay is a compatibility view, not a full kernel emulation. Tools that
  depend on kernel-specific counters may see reduced detail when Android denies the source.

## 11. Related documentation

- [`SYSTEM_ARCHITECTURE.md`](./SYSTEM_ARCHITECTURE.md) — modules, entry points, native packaging
- [`APPLICATION_ARCHITECTURE.md`](./APPLICATION_ARCHITECTURE.md) — package layering and DI
- [`DATA_AND_PERSISTENCE.md`](./DATA_AND_PERSISTENCE.md) — Room, RootFS metadata, install pipeline
- [`../features/TERMINAL_AND_LINUX_RUNTIME.md`](../features/TERMINAL_AND_LINUX_RUNTIME.md) — terminal UI and PTY feature behavior
- [`../features/HOST_SYSTEM_INFO.md`](../features/HOST_SYSTEM_INFO.md) — host facts and guest pass-through
- [`../features/ROOT_SHELL_AND_SUDO.md`](../features/ROOT_SHELL_AND_SUDO.md) — root-emulated execution
- [`app/src/main/jniLibs/BINARY-MANIFEST.md`](../../app/src/main/jniLibs/BINARY-MANIFEST.md) — native binary provenance
