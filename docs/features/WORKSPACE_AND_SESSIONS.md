# Workspaces and Sessions

**Status:** Current — describes the implemented workspace/session model, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/service/runtime/SessionManager.kt`, `WorkspaceTerminalManager.kt`, `RuntimeService.kt`, `app/src/main/java/com/fem/linexa/domain/runtime/RuntimeState.kt`, `TerminalState.kt`, `app/src/main/java/com/fem/linexa/ui/screens/activity/`, `app/src/main/java/com/fem/linexa/ui/screens/workspace/SessionsSegment.kt`, `SessionsViewModel.kt`

---

## 1. Purpose

This document is the authoritative reference for Linexa's workspace and session model: workspace lifecycle, terminal sessions, tmux persistence, wake-lock policy, post-process-death recovery, and the Activity/Sessions measurement scopes.

---

## 2. Scope

In scope:

- Workspace lifecycle states and persistence
- Session and terminal state machines
- Terminal allocation and numbering
- tmux session persistence (opt-in, default off)
- Wake lock (opt-in, 60-minute ceiling)
- `onTaskRemoved` policy
- Startup recovery sweep (orphan reaping)
- Activity and Sessions measurement scopes

Out of scope:

- PRoot runtime internals: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Desktop/VNC: [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md)
- File manager: [`features/FILE_MANAGER.md`](./FILE_MANAGER.md)
- Task manager: [`features/TASK_MANAGER.md`](./TASK_MANAGER.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Workspace lifecycle (Created→Importing→Installed→Ready→Broken→Repairing→Deleting) | Implemented | `domain/workspace/WorkspaceStatus.kt` |
| SessionManager (one RuntimeSession per workspace) | Implemented | `service/runtime/SessionManager.kt` |
| WorkspaceTerminalManager (per-terminal TerminalState) | Implemented | `service/runtime/WorkspaceTerminalManager.kt` |
| RuntimeState / TerminalState sealed models | Implemented | `domain/runtime/RuntimeState.kt`, `TerminalState.kt` |
| tmux persistence opt-in (default off) | Implemented | `TmuxPersist.kt`, `feature.terminal.persistence` |
| Wake lock PARTIAL_WAKE_LOCK 60-min ceiling | Implemented | `service/runtime/wakelock/` |
| onTaskRemoved policy | Implemented | `service/runtime/RuntimeService.kt` |
| Startup recovery sweep (orphan reaping) | Implemented | `PRootContainerRuntime.kt` startup sweep |
| Activity/Sessions measurement scopes | Implemented | `ActivityViewModel`, `SessionsViewModel`, `WorkspaceAggregator`, `TerminalNumbering` |

---

## 4. Technical overview

### 4.1 Workspace lifecycle

A workspace persists as `WorkspaceMetadata` in Room with status `Created`, `Importing(progress)`, `Installed`, `Ready`, `Broken(reason)`, `Repairing`, or `Deleting`. Multi-workspace persistence is Linexa's core differentiator.

### 4.2 Session and terminal state

`SessionManager` (`@Singleton`) tracks one `RuntimeSession` per workspace with `RuntimeState` (Stopped/Starting/Running/Stopping/Error) and health. `RuntimeState.Running` carries `pid`, `since`, and `health` (Healthy/Degraded). State is ephemeral — lives only inside SessionManager while the app process is alive, resets to `Stopped` on fresh process start.

`WorkspaceTerminalManager` owns per-terminal `TerminalState` (Starting/Running/Stopping/Stopped/Error), independent from workspace-level `RuntimeState`. `TerminalState.Error` carries `reason` and `exitCode` (negated signal number; `-9` = SIGKILL). A workspace can be `Running` while one terminal is `Error`; a terminal `Error` never drives the workspace to `RuntimeState.Error`.

### 4.3 Terminal allocation and numbering

Terminal IDs are allocated via a monotonic counter per workspace (`nextTerminalCounters`), first = `DEFAULT_TERMINAL_ID`, never reused after close within a workspace session. `TerminalNumbering` provides gap-free `T1..TN` visible labels used by the terminal bar and Sessions tab; navigation still uses internal ids.

### 4.4 tmux session persistence (opt-in, default off)

`feature.terminal.persistence` reattaches closed terminals to a tmux server inside the workspace while the runtime lives: a closed terminal reopens with its state. App-process death still ends the guest — tmux persists only in-memory within the running container. The tmux binary is vendored in `jniLibs/` (`lib__bin__tmux__.so`). tmux runs **outside** proot, on the host: `tmux -S <sock> new-session -A -s <name> <inner>...`. tmux is NOT on the critical startup path; the pty executes proot directly. `wrapWithTmux`/`TmuxPersist` retained for the persistence backend. `PRootContainerRuntime` refuses to run tmux as the core PTY child.

### 4.5 Wake lock (opt-in, hard ceiling)

The runtime notification carries an opt-in **"Keep awake"** action: a `PARTIAL_WAKE_LOCK` with a hard **60-minute** ceiling (`WakeLockPolicy.MAX_HOLD_MS`), enforced twice — by the platform timeout and by a generation-guarded internal timer. The action's label shows the time left; tapping it again releases it. Stopping, leaving, or deleting the Workspace releases it too. It is never taken automatically — a running Workspace on its own holds no lock. `WorkspaceWakeLockManager` + `WakeLockPolicy` manage acquire/replace/release decisions.

### 4.6 onTaskRemoved (swipe away)

- Workspace **active**: the foreground service keeps running; the runtime survives the swipe.
- Workspace **idle**: the service stops itself.

### 4.7 After process death

Daemon liveness is in-memory, so after the app process is killed, the next app start's **startup recovery sweep** kills every own-uid process still referencing a workspace rootfs (the same /proc-scan contract), releases stale PRoot scratch scopes, cleans dead tmux sockets, and kills stale GPU host servers. So a fresh start never collides with "address already in use" from an orphaned server. There is **no reattach path** after app-process death — runtime state resets and orphaned workspace processes are reaped. A terminal dying inside a live app process does **not** stop the workspace.

### 4.8 Activity and Sessions measurement scopes

| Scope | CPU | RAM | Owner |
|---|---|---|---|
| Device | `/proc/stat` delta | `ActivityManager.MemoryInfo` (total − available) | `HostResourceSampler` |
| Linexa app | `/proc/<own pid>/stat` delta | `Debug.getMemoryInfo` PSS of the app pid | `HostResourceSampler` |
| Workspace | sum over the whole workspace (roots + descendants + rootfs-path matches) | `Pss` (else `VmRSS`, marked approximate) summed over those members | `ResourceMonitor.snapshot` + `WorkspaceAggregator` |
| Android host (Activity) | per-pid delta over every readable `/proc` entry | PSS/RSS per readable pid | `HostProcessScanner` |

The app process and the PRoot processes are different pids, so neither figure contains the other. Device figures enter a workspace number only as the RAM-bar denominator. The Activity host list is bounded by Android's `/proc` visibility; the card reports readable and inaccessible counts rather than claiming all device processes.

**Decisions:**
- Device CPU is usually unavailable (SELinux-denied on Android 8+); `DeviceCpu.readable` distinguishes *unavailable* from *measuring*. The UI says "Not exposed by this Android version" instead of a bare dash. No substitute metric is invented.
- One roll-up (`WorkspaceAggregator`, pure, unit-tested) is used by every screen. Pids reachable from two roots are counted once; unknown CPU stays null; one `VmRSS` fallback marks the whole RAM sum approximate.
- All CPU percentages are shares of total device capacity, normalised by online core capacity. Rows use `utime + stime` only. Per-core values are keyed by kernel core number.
- Whole-workspace membership adds rootfs-path matching to the ppid walk, so independent terminals, detached daemons, and orphans belong to the selected workspace only. Every PRoot supervisor for that rootfs is marked protected.
- Rows and aggregate come from one `snapshot`; CPU state is keyed by `(pid, starttime)` so PID reuse starts pending instead of inheriting a dead process's counters.
- Consistent session numbers: `TerminalNumbering` gives gap-free `T1..TN` labels.
- Ended sessions keep a duration frozen at the moment the UI first saw them end, are never sampled, and offer Details / Restart / Remove — no "Open".
- Polling: one loop per screen, only while the screen is STARTED (`rememberIsForeground`), single monitored workspace on Activity, ticks serialised and coalesced (<750 ms apart).
- Storage removed from both screens (File Manager owns it). `SessionsViewModel` no longer depends on `WorkspaceFileManager`.

New files introduced: `domain/resource/WorkspaceAggregator.kt`, `domain/runtime/TerminalNumbering.kt`, `ui/components/Foreground.kt`, `ui/components/haven/HavenResource.kt`. Rewritten: `ActivityViewModel`, `ActivityScreen` (body), `SessionsViewModel`, `SessionsSegment`. Edited: `HostResourceSampler`, `strings.xml`.

---

## 5. Limitations and known issues

- Session/terminal state is runtime-only; no persistence across process death. No new Room entities for sessions.
- Device CPU reads are SELinux-denied on many devices; reported as unavailable, never guessed.
- `/proc/environ`-based environment-marker attribution is **not implemented** (INVESTIGATE only). Current attribution is the union of the ppid tree and `/proc/<pid>/cmdline` rootfs-path matches; it is expected to include reparented daemons and orphans, but real PRoot argv shapes are device-unverified.
- Android `/proc` visibility limits the Activity host list. The UI discloses readable/inaccessible counts; it never claims a full device view or uses root/Shizuku/ADB.
- Real-device validation is pending for `/proc/stat` readability, `smaps_rollup`/`last_core`, and SIGTERM under the app's SELinux domain.
- Core flows still need on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime, session launch, PTY
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — Room, WorkspaceMetadata, status lifecycle
- [`features/TERMINAL_AND_LINUX_RUNTIME.md`](./TERMINAL_AND_LINUX_RUNTIME.md) — PRoot, exec/daemon paths
- [`features/TASK_MANAGER.md`](./TASK_MANAGER.md) — process attribution, termination
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
- [`decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md) — sessions Keep/Fix/Refactor decision
