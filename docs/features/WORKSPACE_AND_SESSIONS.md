# Workspaces and Sessions

**Source locations:** `app/src/main/java/com/fem/linexa/service/runtime/SessionManager.kt`,
`WorkspaceTerminalManager.kt`, `RuntimeService.kt`,
`app/src/main/java/com/fem/linexa/domain/runtime/RuntimeState.kt`, `TerminalState.kt`,
`app/src/main/java/com/fem/linexa/ui/screens/activity/`,
`app/src/main/java/com/fem/linexa/ui/screens/workspace/SessionsSegment.kt`,
`SessionsViewModel.kt`

This document describes the workspace and session model: lifecycle states, terminal
sessions, optional tmux persistence, wake-lock policy, process-death recovery, and the
resource-measurement scopes used by the Activity and Sessions screens. PRoot internals are
covered in [`../architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md).

## 1. Workspace lifecycle

A workspace is persisted as `WorkspaceMetadata` in Room with one of the following statuses:

`Created` → `Importing(progress)` → `Installed` → `Ready`, with `Broken(reason)`,
`Repairing`, and `Deleting` as side states. Multiple workspaces can coexist; each has its
own rootfs, configuration, feature toggles, and terminal sessions.

## 2. Session and terminal state

`SessionManager` is a singleton that tracks one `RuntimeSession` per workspace. Its
`RuntimeState` is `Stopped`, `Starting`, `Running`, `Stopping`, or `Error`.
`RuntimeState.Running` carries the process id, start time, and health (`Healthy` or
`Degraded`). Session state is ephemeral and resets to `Stopped` on a fresh app process.

`WorkspaceTerminalManager` owns a separate `TerminalState` per terminal: `Starting`,
`Running`, `Stopping`, `Stopped`, or `Error`. `TerminalState.Error` carries a reason and an
exit code; a negated signal number such as `-9` means `SIGKILL`. A terminal can be in
`Error` while its workspace remains `Running`. A terminal exit does not stop the
workspace.

## 3. Terminal allocation and numbering

Terminal ids are allocated from a monotonic per-workspace counter. The first terminal uses
`DEFAULT_TERMINAL_ID`, and ids are not reused after a terminal closes within the same
workspace session.

`TerminalNumbering` produces gap-free visible labels `T1..TN` for the terminal bar and the
Sessions screen. Navigation and runtime bookkeeping continue to use the internal ids.

## 4. Optional tmux persistence

`feature.terminal.persistence` is opt-in and disabled by default. When enabled, a closed
terminal can reattach to a tmux server running inside the workspace while the runtime is
alive. App-process death still ends the guest; tmux persists only in memory within the
running container.

The tmux binary is vendored in `jniLibs/`. tmux runs outside PRoot on the host, using
`tmux -S <socket> new-session -A -s <name> <inner command>`. It is not on the critical
startup path: the PTY executes PRoot directly, and `PRootContainerRuntime` refuses to run
tmux as the core PTY child. `TmuxPersist` and `wrapWithTmux` provide the optional
persistence backend.

## 5. Wake lock

The runtime notification exposes an optional **Keep awake** action. It acquires a
`PARTIAL_WAKE_LOCK` with a hard 60-minute ceiling (`WakeLockPolicy.MAX_HOLD_MS`). The limit
is enforced by the platform timeout and by a generation-guarded internal timer. The action
label shows the remaining time; tapping it again releases the lock. Stopping, leaving, or
deleting the workspace releases it as well.

The lock is never acquired automatically. A running workspace with no user action holds no
wake lock.

## 6. Task removal behavior

- If a workspace is active, the foreground service keeps running and the runtime survives
  the user swiping the task away.
- If no workspace is active, the service stops itself.

## 7. Process-death recovery

Daemon liveness is tracked in memory. After the app process is killed, the next app start
runs a startup recovery sweep that:

- kills own-uid processes whose command line references a workspace rootfs;
- releases stale PRoot scratch directories;
- removes dead tmux sockets;
- stops stale GPU host servers.

This prevents a fresh start from colliding with "address already in use" or a stale lock.
There is no reattach path after process death: runtime state resets and orphaned workspace
processes are reaped. A terminal dying inside a live app process does not stop the
workspace.

## 8. Activity and Sessions measurement scopes

| Scope | CPU | RAM | Owner |
|---|---|---|---|
| Device | `/proc/stat` delta | `ActivityManager.MemoryInfo` (total minus available) | `HostResourceSampler` |
| Linexa app | `/proc/<own pid>/stat` delta | `Debug.getMemoryInfo` PSS of the app process | `HostResourceSampler` |
| Workspace | sum over the whole workspace (roots, descendants, and rootfs-path matches) | PSS, else `VmRSS` marked approximate, summed over members | `ResourceMonitor.snapshot` + `WorkspaceAggregator` |
| Android host (Activity) | per-pid delta over every readable `/proc` entry | PSS/RSS per readable pid | `HostProcessScanner` |

The app process and PRoot processes are different pids, so neither figure contains the
other. Device figures enter a workspace number only as the RAM-bar denominator. The
Activity host list is bounded by Android's `/proc` visibility; the UI reports readable and
inaccessible counts rather than claiming a complete device view.

Conventions shared by the screens:

- Device CPU may be unavailable because Android denies `/proc/stat`. `DeviceCpu.readable`
  distinguishes unavailable from measuring, and the UI says so instead of showing a bare
  dash. No substitute metric is invented.
- `WorkspaceAggregator` is the single pure roll-up used by every screen. A pid reachable
  from two roots is counted once; unknown CPU stays `null`; a `VmRSS` fallback marks the
  whole RAM sum approximate.
- CPU percentages are shares of total device capacity, normalized by online core capacity.
  Rows use `utime + stime` only. Per-core values are keyed by kernel core number.
- Workspace membership combines the ppid walk with rootfs-path matching, so independent
  terminals, detached daemons, and orphans are attributed to the selected workspace.
- Rows and the aggregate come from one snapshot. CPU state is keyed by `(pid, starttime)`
  so pid reuse starts pending rather than inheriting a dead process's counters.
- Ended sessions keep their duration frozen at the moment the UI first observes the end,
  are never sampled, and offer Details / Restart / Remove instead of Open.
- Polling runs once per screen, only while the screen is started (`rememberIsForeground`).
  Activity monitors one selected workspace; ticks are serialized and coalesced at the
  shared 2 s cadence.

## 9. Behavior notes and limits

- Session and terminal state are runtime-only. No Room entity persists them across process
  death.
- Device CPU is frequently unavailable on modern Android and is reported as unavailable.
- Workspace attribution uses the ppid tree and `/proc/<pid>/cmdline` rootfs-path matching.
  Reparented daemons and orphans are included when their command line exposes the rootfs
  path; the startup sweep remains the fallback.
- Android `/proc` visibility limits the Activity host list. Linexa does not use root,
  Shizuku, or ADB to widen it.
- Storage figures are owned by the File Manager. The Activity and Sessions screens do not
  display a second storage total.

## 10. Related documentation

- [`../architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime, session launch, PTY
- [`../architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — Room, workspace status lifecycle
- [`TERMINAL_AND_LINUX_RUNTIME.md`](./TERMINAL_AND_LINUX_RUNTIME.md) — terminal UI and launch paths
- [`TASK_MANAGER.md`](./TASK_MANAGER.md) — process attribution and termination
- [`../engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — test layers and device checks
