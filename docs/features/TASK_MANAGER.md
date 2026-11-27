# Task Manager

**Source locations:** `app/src/main/java/com/fem/linexa/ui/screens/taskmanager/`,
`app/src/main/java/com/fem/linexa/ui/screens/terminal/SessionTaskManagerViewModel.kt`,
`app/src/main/java/com/fem/linexa/domain/resource/`,
`app/src/main/java/com/fem/linexa/data/resource/`

---

## 1. Purpose

The task manager reports device, app, and workspace resource use, lists
processes attributable to a workspace (and the processes this app can read on
the device), and terminates a selected process with confirmation. It is the
primary place where a user can see what a running Linux session is costing.

---

## 2. Scope

In scope:

- Device RAM and per-core CPU
- App PSS/CPU and process footprint
- Workspace process list, search, and per-process end-task
- Host process list for processes readable through unprivileged `/proc`
- Process attribution and termination safety
- CPU and memory measurement conventions

Out of scope:

- PRoot runtime internals: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Workspace/session lifecycle: [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md)

---

## 3. Surfaces

| Surface | Data |
|---|---|
| Task Manager screen | Device CPU/RAM, app footprint, workspace process list |
| Activity screen | Workspace roll-ups plus the host process section |
| Sessions segment | Per-session resource summary |
| Workspace performance panel | Live workspace CPU/RAM inside a running workspace |

All surfaces share the same samplers, attribution rules, and formatting
conventions; a workspace number shown in two places comes from the same
snapshot.

---

## 4. Process attribution

Workspace scope is the union of two signals from one `/proc` scan:

1. **Descendant tree.** `ProcessTree.descendantsOf(rootPid, parents)` walks
   parent PIDs iteratively and memoizes results. `CONFIG_PROC_CHILDREN` is not
   available on target kernels, so the parent map is built from `/proc/*/stat`.
   Cycles are handled with an on-walk set.
2. **RootFS path match.** `ProcessAttribution` matches the workspace's
   absolute rootfs path in `/proc/<pid>/cmdline`. This catches a terminal
   launched independently, a daemon started outside the normal process tree, a
   reparented orphan, or a `setsid`-detached process, without merging another
   workspace.

`ProcResourceMonitor.snapshot(rootPids)` performs one directory scan and reuses
the same stat rows for the process list and the aggregate. A PRoot supervisor
whose argv names the workspace rootfs is marked `isRoot`, which protects every
session supervisor in addition to the root PID passed in.

On modern Android, `/proc` is restricted by `hidepid` and SELinux. The host
list therefore reports only what the app can read: `HostProcessSnapshot`
carries `scopeLimited` and an inaccessible-PID count. The UI does not imply
that the list is the complete device process table, and the app does not use
root, Shizuku, or ADB to widen it.

---

## 5. Termination

Workspace termination sends `SIGTERM`, then verifies with three reads of
`/proc/<pid>` spaced 500 ms apart. Rows marked `isRoot` are refused with
`tm_root_protected`; this covers all PRoot supervisors for the rootfs, not
only the initial PID. Zombie state is read from `/proc/<pid>/stat`.

Host termination re-reads `/proc/<pid>/status` immediately before signalling.
It refuses unless the current UID equals Linexa's UID and the PID is not the
app itself or a recycled PID belonging to another process. `canSignal` from
the previous scan is only a presentation hint.

Processes that must never be ended by workspace bulk operations:

- The app's own process (never part of the workspace tree)
- Root supervisors (`isRoot`)
- `sshd`/`ssh`/`dropbear` (`ROOT_DAEMONS`), which keep root emulation working
- `Xvnc`, stopped through the workspace's daemon teardown
- Audio, notification, and clipboard pumps, owned by their feature supervisors
- The GPU supervisor, stopped per workspace

All polling loops are lifecycle-bound (`start`/`stop` or
`pause`/`DisposableEffect`) and cancellable. They do not spawn shell
processes.

---

## 6. Measurement conventions

### 6.1 CPU

All CPU percentages mean **percent of total device capacity**, from 0 to 100,
normalized by the online/core-capacity count from `CpuTopologyProvider`.
Per-core values are keyed by the kernel's core number, so an offline core does
not shift another core's slot. Values below 1% keep one decimal (`0.4%`); a
real zero is `0%`; pending and unavailable are distinct from zero. Formatting
is centralized in `CpuPercentFormatter`.

Process CPU uses `utime + stime` only. Reaped-child time is excluded so a row
does not double-count live children. Device CPU comes from `/proc/stat`
deltas through `CpuUsageCalculator`; the first tick is pending, and an
unreadable `/proc/stat` is reported as unavailable rather than `0%`.

### 6.2 Memory

PSS is read from `smaps_rollup` when available. If it is not readable, the
sampler falls back to `VmRSS` and marks the value approximate. Workspace rows
and the workspace aggregate come from the same snapshot, so the summary and
the list cannot disagree within a tick.

### 6.3 PID reuse

`ProcResourceMonitor`, `HostProcessScanner`, and `HostResourceSampler` compare
both PID and `starttime` before attributing a sample or sending a signal. A
recycled PID is rejected rather than attributed to the old process.

---

## 7. Limitations

- **Android child-process limit.** Android 12+ may kill an app that holds too
  many child processes (the documented phantom-process limit is 32). A desktop
  session with Xvnc, sshd, audio, notification and clipboard bridges, a GPU
  supervisor, and multiple terminals can approach that number. Settings → Access
  reports whether the platform monitor is on, off, or unknown and shows copy-only
  `adb` guidance; Linexa does not change the setting or prevent the kill.
- **Host list is partial.** Other apps are normally hidden by `hidepid` and
  SELinux. The host section reports the readable and inaccessible counts
  instead of claiming completeness.
- **No bulk end-all.** Termination is per process with a confirmation dialog.
- **No background-helper labels.** Processes that serve no session are not
  distinguished from session processes.
- **Orphan coverage depends on cmdline contents.** Rootfs-path matching catches
  a daemon whose command line contains the workspace path. A daemon that
  rewrites its argv may only be caught by the startup sweep.
- **GPU metrics are vendor-dependent.** Adreno busy/clock nodes are read when
  present. Other GPUs report the metric as unknown rather than fabricating a
  zero.

---

## 8. Related documentation

- [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md) — Activity/Sessions scopes
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime and daemon management
- [`engineering/PERFORMANCE.md`](../engineering/PERFORMANCE.md) — polling and measurement architecture
