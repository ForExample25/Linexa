# Runtime Architecture

**Status:** Current — describes the implemented PRoot runtime, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/service/runtime/`, `app/src/main/java/com/fem/linexa/domain/runtime/`, `app/src/main/java/com/fem/linexa/service/installer/`

---

## 1. Purpose

This document is the authoritative reference for Linexa's runtime: PRoot container execution, PTY terminal stack, session lifecycle, launch paths, environment construction, and the `/proc`/`/sys`/`/dev` compatibility surface presented to guests.

---

## 2. Scope

In scope:

- `ContainerRuntime` / `PRootContainerRuntime` and `PRootLauncher`
- PTY allocation via the vendored Termux `:terminal-emulator` module (`libtermux.so`)
- Session lifecycle (workspace start → terminal open → close)
- The three process launch paths (`openTerminal`, `execInternal`, `startDaemon`)
- Environment construction and guest env filtering
- Bootstrap layout, seccomp fallback, `/dev/shm`, `/run`, `/proc` snapshots
- RootFS installation pipeline (staged installer)
- Runtime self-heal and startup recovery sweep

Out of scope:

- Feature provisioners (audio, clipboard, notifications, desktop, GPU): [`features/`](../features/)
- Host system info: [`features/HOST_SYSTEM_INFO.md`](../features/HOST_SYSTEM_INFO.md)
- Data/persistence schema: [`architecture/DATA_AND_PERSISTENCE.md`](./DATA_AND_PERSISTENCE.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| PRoot container runtime (`PRootContainerRuntime`) | Implemented | `service/runtime/container/PRootContainerRuntime.kt` (~2,743 lines) |
| PRoot argv/env construction (`PRootLauncher`) | Implemented | `service/runtime/container/PRootLauncher.kt` (~644 lines) |
| Real PTY terminal stack (vendored Termux terminal-emulator) | Implemented | `service/runtime/pty/`, vendored `:terminal-emulator`/`:terminal-view` |
| Three launch paths (PTY / exec / daemon) | Implemented | `PRootContainerRuntime.kt` |
| SessionManager + WorkspaceTerminalManager | Implemented | `service/runtime/SessionManager.kt`, `WorkspaceTerminalManager.kt` |
| Staged RootFS installer | Implemented | `service/installer/StagedRootFsInstaller.kt` |
| `/proc`/`/sys`/`/dev` binds + snapshots | Implemented | `PRootLauncher.kt`, `GuestRootfsInitializer.kt` |
| tmux persistence (opt-in, default off) | Implemented | `TmuxPersist.kt`, `feature.terminal.persistence` (default off) |
| Wake lock (opt-in, 60-minute ceiling) | Implemented | `service/runtime/wakelock/` |

---

## 4. Technical overview

### 4.1 Module map

| Package | Role | Key classes |
|---|---|---|
| `service.runtime` | Session/process orchestration | `RuntimeService`, `RuntimeBinder`, `SessionManager`, `WorkspaceTerminalManager`, `WorkspaceSessionSupervisor`, `RuntimeLifecycleManager`, `TerminalSession`, `RuntimeViewModel`, `RuntimeTrace` |
| `service.runtime.pty` | PTY abstraction over the Termux terminal-emulator | `PtySession`, `PtySessionFactory`, `TermuxPtySession`, `TermuxPtySessionFactory`, `PtySessionSource`, `TermuxTerminalSessionProvider` |
| `service.runtime.container` | PRoot process construction & lifecycle | `PRootLauncher` (pure argv/env builder), `PRootContainerRuntime` (sessions/exec/daemons), `ContainerRuntime` (iface), `TmuxPersist` (dormant) |
| `service.runtime.bootstrap` | Bundled-binary layout & env | `BootstrapManager`, `BootstrapManifest`, `BusyboxAppletLayer` |
| `service.runtime.features` | Guest feature provisioners | `SshdProvisioner`, `XvncProvisioner`, `NotifySendProvisioner`, clipboard/audio bridges, `WorkspaceFeatureSupervisor`, `WorkspaceFeatureReconciler` |
| `service.runtime.hostinfo` | Host info runtime bridge | `HostInfoRuntimeBridge`, `ProcSnapshotContentFactory` |
| `service.runtime.wakelock` | Wake-lock policy | `WorkspaceWakeLockManager`, `WakeLockPolicy`, `HostWakeLock`, `AndroidWakeLock` |
| `service.installer` | Staged rootfs installer | `InstallCoordinator`, `RootFsInstaller`/`StagedRootFsInstaller`, `RootfsExtractor`, `L2sLinkRewriter`, `RootfsFixupService`, `GuestRootfsInitializer`, `OciLayerUnwrapper`, `InstallRecoveryManager`, `RootFsProbe` |
| `domain.runtime` | Pure runtime models | `RuntimeState`, `TerminalState`, `CommandResult`, `FeatureIds` |
| `service.rootfs(.profile)` | Archive/metadata/profile machinery | `RootFsDescriptor`, `CompressedArchiveStream`, `ArchiveSignalCollector`, `RootFsProfileCatalog` |

### 4.2 The three process launch paths

| Path | Spawns | stdio | TTY? | Used by |
|---|---|---|---|---|
| `openTerminal` → `TermuxPtySession` → `libtermux.so` native | PRoot directly (no tmux) | PTY master/slave (`/dev/ptmx` + `/dev/pts/N`) | **Yes** (setsid + ctty) | Workspace terminals T0/T1/…, Management terminal (host `/system/bin/sh`) |
| `execInternal` → `runProcess` | PRoot (`/bin/sh -c cmd`) via `ProcessBuilder`, `redirectErrorStream(true)`, full env replacement | pipes (stdin = never-written pipe) | **No** | Package Manager UI, File Manager, feature provisioners, dpkg preflight, probes |
| `startDaemon` → `launchDaemonProcess` | PRoot (`/bin/sh -c 'exec daemon'`) via `ProcessBuilder`, output → log file | file-backed stdout | **No** | Xvnc, sshd (`ManagedDaemon` map) |

tmux is vendored but **deliberately off the launch path**. `PRootLauncher.kt` guards against tmux as PTY child; `feature.terminal.persistence` (default off) wraps launches with `tmux new-session -A -s ...` via `TmuxPersist` for opt-in session persistence. Closing a terminal never stops the workspace; lifecycle is enter/leave only.

### 4.3 Session lifecycle

1. `RuntimeViewModel.bindService()` starts + binds `RuntimeService` (BIND_AUTO_CREATE); `WorkspaceNavGraph` triggers `startWorkspace()` (`ACTION_START`).
2. `RuntimeService.onCreate` builds `RuntimeBinder` and `WorkspaceSessionSupervisor`; `onStartCommand` → `startForegroundNotification` → `launchSession` → supervisor.
3. `WorkspaceSessionSupervisor.run()`: `WorkspaceLaunchValidator.evaluate` gate → `RuntimeLifecycleManager.requestStart()` (re-entrancy-guarded) → evicts other active workspace → `WorkspaceTerminalManager.openTerminal(...)` → `StartOutcome.Started(pty, generation)`; then awaits `awaitSessionEnd` (only `closeWorkspace` completes it — terminal exit ≠ workspace exit).
4. `PRootContainerRuntime.openTerminal()`: bootstrap self-heal, per-terminal `PROOT_TMP_DIR` scope, guest `/tmp` repair, dpkg health check + optional `dpkg --configure -a` preflight exec, config.yaml overrides, seccomp probe/fallback, then `ptySessionFactory.create(...)` **on the Main thread** (the Termux `TerminalSession` captures the constructing thread's Looper — UNVERIFIED against current source; was stated for the removed terminal-lib).

### 4.4 Environment construction

`BootstrapManager.bootstrapEnv()` = `{PATH={filesDir}/bootstrap/bin, LD_LIBRARY_PATH={filesDir}/bootstrap/lib, PROOT_LOADER={filesDir}/bootstrap/bin/loader, TERMINFO={filesDir}/terminfo (if key entry usable)}`. `PRootLauncher.buildLaunchSpec` layers guest keys on top: `HOME=/root, USER=root, TERM=xterm-256color, COLORTERM=truecolor, LANG=C.UTF-8, PATH=/usr/local/sbin:...:/bin`. `config.yaml linexa.env` overrides are filtered against `HOST_ENV_KEYS` by `applyConfigEnv`. `PROOT_GUEST_ENV_FILTER` scrubs host-only env vars (`LD_LIBRARY_PATH`, `PROOT_TMP_DIR`, `PROOT_LOADER`, `TERMINFO`, and the filter name itself) at the execve enter side; guest-authored values are preserved. The rootfs extractor deliberately does not set the filter (its bundled bionic `tar` needs host `LD_LIBRARY_PATH` to link).

### 4.5 Seccomp fallback

`resolveSeccompFallback`: one bounded probe (`/bin/sh -c "exit 0"`, 30 s) on first terminal open; on the documented marker string sets `BootstrapManager.needsNoSeccomp`, after which every launch pre-applies `PROOT_NO_SECCOMP=1`. `execInternal` retries once on the failure signature. The installer shares the same flag.

### 4.6 /proc /sys /dev

PRoot binds host `/proc`, `/sys`, `/dev` wholesale (no mount code in Linexa; Android init's mount options are inherited). Android `untrusted_app` SELinux + `hidepid=invisible` make `/proc/stat`, `/proc/loadavg`, `/proc/uptime`, `/proc/version`, `/proc/vmstat` return `EACCES`. Linexa overlays static snapshot files for these five files (`PROC_BIND_OVER_NAMES: loadavg, stat, uptime, version, vmstat`) via `PRootLauncher.procBindOvers()` at `{rootfs}/proc/.<name>`, written by `GuestRootfsInitializer.ensureProcSnapshots()`. Values are static/frozen synthetic values (product decision: "tools start" over "tools are honest"). `/dev/shm` is absent on the Android host; Linexa creates a rootfs-owned `/dev/shm` (mode 1777) via `ensureGuestDevShm()` and binds it over `/dev/shm`. `/run` is created (mode 1777) via `ensureGuestRun()` and bound over `/run`.

### 4.7 Kernel release spoof

`--kernel-release=6.1.0-linexa` (`SPOOFED_KERNEL_RELEASE`) spoofs `uname` so version-probing guest software does not see the Android kernel string. Reused for the `/proc/version` snapshot.

### 4.8 System V IPC

`--sysvipc` is emitted so guest IPC syscalls reach the Android host IPC namespace unchanged.

### 4.9 /dev/fd and stdio binds

`-b /proc/self/fd:/dev/fd`, `-b /proc/self/fd/0:/dev/stdin`, `-b /proc/self/fd/1:/dev/stdout`, `-b /proc/self/fd/2:/dev/stderr` so guest TTY detection sees the same symlink shape a real distro provides.

### 4.10 RootFS installation pipeline

1. Download/import → `StagedRootFsInstaller.analyze()` (Pass 1, JVM): SHA-256 digest, tee to `.import-cache/source-archive`, per-entry `ArchiveValidator.checkEntry` (path traversal, zip-slip, symlink escape, 8 GiB bomb cap), root-level metadata capture, profile resolution, OCI layout resolution, disk-space gate.
2. `RootfsExtractor.extract()` (Pass 2, native): `proot --link2symlink tar -xf … -C staging --delay-directory-restore --preserve-permissions --checkpoint=100`. Birthplace of install-time L2S hardlink families.
3. `L2sLinkRewriter.rewrite(staging)` (Pass 2.5): rewrites `.l2s.*` symlink targets to relative (staging→live correctness).
4. `validateExtractedRootfs` (Pass 3): profile required directories + shell candidates.
5. `runInstallSteps` (Pass 3.5): profile install steps (`ENSURE_TMP` → `ENSURE_DEV_SHM` → `ANDROID_ACCOUNT_FIXUP` → `SEED_RESOLV_CONF`); `RootfsFixupService.fixUp` + `GuestRootfsInitializer.ensureGuestTmp`.
6. `writeWorkspaceConfig` (Pass 4): captured `config.yaml`.
7. Activation: `WorkspaceFileManager.activateStagedRootfs()` — live→`rootfs.previous-<ts>`, staging→`rootfs` (single rename), restore on failure.
8. Runtime self-heal at every launch: `ensureGuestTmp`, `ensureGuestDevShm`, `ensureGuestRun`, `ensureProcSnapshots`, `ensureDpkgHealthy` (+ optional `dpkg --configure -a` preflight).

---

## 5. Mermaid: session lifecycle

```mermaid
graph TD
    subgraph UI ["Android UI (Compose)"]
        WTP[WorkspaceTerminalPanel]
        RTV[RealTerminalView]
    end

    subgraph VM ["ViewModel layer"]
        TVM[TerminalViewModel]
        RVM[RuntimeViewModel]
    end

    subgraph Service ["RuntimeService (foreground)"]
        RB[RuntimeBinder]
        WSS[WorkspaceSessionSupervisor]
        RLM[RuntimeLifecycleManager]
    end

    subgraph Container ["PRootContainerRuntime"]
        OT[openTerminal]
        BS[bootstrap self-heal]
        DH[dpkg health + tmp/shm/run/proc]
        PS[ptySessionFactory.create Main thread]
    end

    subgraph PTY ["PTY (Termux terminal-emulator)"]
        TPS[TermuxPtySession]
        NL[libtermux.so /dev/ptmx + /dev/pts/N]
    end

    subgraph Guest ["Guest rootfs (PRoot)"]
        SH[/bin/bash --login]
    end

    WTP --> TVM
    TVM --> RB
    RTV -->|attachSession| RB
    RB -->|getPtySession| WSS
    WSS -->|run()| RLM
    RLM -->|launchSession| OT
    OT --> BS
    OT --> DH
    OT --> PS
    PS --> TPS
    TPS --> NL
    NL -->|exec proot| SH
```

---

## 6. Dependencies and interactions

- `PRootContainerRuntime` ← `PRootLauncher`, `PtySessionFactory`, `WorkspaceFileManager`, `WorkspaceConfigParser`, `BootstrapManager`, `RuntimeTrace`, `GuestRootfsInitializer`, `WorkspaceRepository`, `WorkspaceRootFsMetadata`.
- `StagedRootFsInstaller` ← `WorkspaceFileManager`, `ArchiveValidator`, `BootstrapManager`, `RootfsExtractor`, `L2sLinkRewriter`, `RootfsFixupService`, `GuestRootfsInitializer`, `OciLayerUnwrapper`, `RootFsProfileCatalog`.
- `TermuxPtySessionFactory` is bound as the default `PtySessionFactory` (`AppModule`).
- The consumers of Termux terminal-emulator internals are `TermuxPtySession`/`TermuxTerminalSessionProvider` and the terminal UI (`RealTerminalView`) (enforced socially by `ImportBoundaryTest` and `PtySession`'s library-agnostic interface).

---

## 7. Limitations and known issues

- Core runtime flows still need on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).
- PRoot rebuilt pairs (arm64-v8a + armeabi-v7a) rebuilt with patches 0001–0004 have not been executed on-device; x86_64 real-binary verification only.
- `/proc` snapshot values are static/frozen; `uptime`/`loadavg` do not tick.
- `/dev/shm`, `/run`, `/proc` snapshots are self-healed at every launch; imported/older workspaces get them on next launch.
- The one-shot exec path has no TTY by design; `DEBIAN_FRONTEND=noninteractive` is layered in to prevent debconf stalls.
- Host `PATH`/`LD_LIBRARY_PATH` leak into the guest is partially mitigated by `PROOT_GUEST_ENV_FILTER` (value-matched scrub); `PATH` is overridden by the guest layer.

---

## 8. Related documentation

- [`architecture/SYSTEM_ARCHITECTURE.md`](./SYSTEM_ARCHITECTURE.md) — system architecture, module layout
- [`architecture/APPLICATION_ARCHITECTURE.md`](./APPLICATION_ARCHITECTURE.md) — package layering and boundary rules
- [`architecture/DATA_AND_PERSISTENCE.md`](./DATA_AND_PERSISTENCE.md) — Room, schema, RootFS metadata (`linexa.yaml`)
- [`features/HOST_SYSTEM_INFO.md`](../features/HOST_SYSTEM_INFO.md) — host info subsystem, selective pass-through, `/proc` overlays
- [`features/TERMINAL_AND_LINUX_RUNTIME.md`](../features/TERMINAL_AND_LINUX_RUNTIME.md) — (this document is the runtime reference)
- [`features/DESKTOP_AND_VNC.md`](../features/DESKTOP_AND_VNC.md) — desktop daemons (Xvnc, sshd) launched via `startDaemon`
- [`decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md) — runtime Keep/Fix/Refactor decisions
- [`engineering/PERFORMANCE.md`](../engineering/PERFORMANCE.md) — runtime performance considerations
