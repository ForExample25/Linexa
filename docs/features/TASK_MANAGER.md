# Task Manager

**Status:** Current — describes the implemented task/process manager, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/ui/screens/taskmanager/`, `app/src/main/java/com/fem/linexa/domain/resource/ProcessTree.kt`, `app/src/main/java/com/fem/linexa/data/resource/ProcResourceMonitor.kt`, `app/src/main/java/com/fem/linexa/ui/screens/terminal/SessionTaskManagerViewModel.kt`

---

## 1. Purpose

This document is the feature-level reference for Linexa's task manager: device/app/workspace resource monitoring, per-process sampling, process termination, and process attribution.

---

## 2. Scope

In scope:

- Task manager screen and ViewModel (device RAM/CPU per-core, app PSS, workspace process list, search, end-task with confirm)
- Activity host-process section: every process readable through unprivileged `/proc`, with explicit scope limitation disclosure
- Whole-workspace attribution: ppid descendants plus rootfs-path matches, so independent terminals, detached daemons, and orphans appear
- Termination behavior (SIGTERM then verification; workspace supervisors and the app's own PID protected)
- Phantom-process limit awareness (not implemented)
- Background-helper labels (not implemented)

Out of scope:

- PRoot runtime: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Workspace/session lifecycle: [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Task manager screen + VM (device/app/workspace scopes) | Implemented | `ui/screens/taskmanager/TaskManagerScreen.kt`, `TaskManagerViewModel.kt` |
| Per-process PSS, per-core CPU | Implemented | `TaskManagerViewModel.kt`, `ProcMetricParsers.kt` |
| Host process list (unprivileged `/proc`) | Implemented | `data/resource/HostProcessScanner.kt`, `ActivityScreen.kt` |
| Per-core CPU uses kernel core numbers | Implemented | `CpuStat.kt` (`Map<Int, Float?>`), `TaskManagerScreen.kt` |
| Shared CPU convention (`% of total device capacity`, 0–100, sub-1% one decimal) | Implemented | `core/format/CpuPercentFormatter.kt`, `CpuStat.kt` |
| End-process with confirm dialog | Implemented | `TaskManagerScreen.kt` (`endTarget`), `ActivityScreen.kt` (`hostEndTarget`) |
| Root/supervisor protection | Implemented | `isRoot` refuses; snapshot marks every PRoot supervisor for the workspace |
| App PID protection | Implemented | workspace rows cannot contain the app; host scanner marks `isSelf` |
| Host termination UID recheck | Implemented | `HostProcessScanner.terminate` re-reads `/proc/<pid>/status` |
| PID-reuse rejection (`pid`, `starttime`) | Implemented | `ProcResourceMonitor`, `HostProcessScanner`, `HostResourceSampler` |
| Zombie detection (`isZombie` reads `/proc/$pid/stat`) | Implemented | `TaskManagerViewModel.kt` |
| ROOT_DAEMONS (sshd/ssh/dropbear) | Implemented | `PRootContainerRuntime.kt` companion |
| Whole-workspace attribution (ppid + rootfs path) | Implemented | `domain/resource/ProcessAttribution.kt`, `ProcResourceMonitor.snapshot` |
| Phantom-process-limit handling | Not implemented | no `max_phantom_processes` handling in source |
| Background-helper labels | Not implemented | no helper identification in source |
| Bulk "End all" operations | Not implemented | per-process confirm only |

Verified locally: `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, and targeted JVM tests for domain attribution/parsers/formatting plus `HostProcessScannerTest` are green. Real-device `/proc` visibility and SELinux behavior remain unverified.

---

## 4. Technical overview

### 4.1 Process attribution

Workspace scope is the union of two signals in one `/proc` snapshot:

1. `ProcessTree.descendantsOf(rootPid, parents)` — the normal ppid tree. Iterative and memoized because `CONFIG_PROC_CHILDREN` is absent on target kernels. Cycle handling uses an on-walk set; depth is bounded by `parents.size`.
2. `ProcessAttribution` rootfs-path matching — `/proc/<pid>/cmdline` contains the workspace's absolute `…/rootfs` path. This is the same rule `PRootContainerRuntime.killRootfsProcesses()` uses at teardown and is what includes an independently launched terminal, a daemon started outside the root tree, a reparented orphan, or a `setsid`-detached process without merging another workspace.

`ProcResourceMonitor.snapshot(rootPids)` performs one directory scan, reuses the same stat rows for the list and the aggregate, and marks every PRoot supervisor whose argv names the workspace rootfs as `isRoot`. That protects session supervisors in addition to the originally passed root pid.

On modern Android `/proc` is `hidepid`/SELinux-constrained, so the Activity host list is explicitly **not** a complete device process list: `HostProcessSnapshot.scopeLimited` and the inaccessible-pid count disclose that. The app never claims root, Shizuku, or ADB-backed visibility.

### 4.2 Termination behavior

Workspace `terminate()` sends SIGTERM then verifies via 3×500 ms re-reads of `/proc/<pid>`. Any row marked `isRoot` is refused with `tm_root_protected`; the marker covers all PRoot supervisors for the workspace rootfs, not only the initial pid. Zombie detection reads `/proc/$pid/stat`. `ROOT_DAEMONS` = `{"sshd", "ssh", "dropbear"}` — run as root in PRootContainerRuntime, kept for ROOT emulation (privileged ports, host keys, `/run/sshd`).

Host `terminate()` re-reads `/proc/<pid>/status` immediately before signalling and refuses unless the current UID equals Linexa's UID and the pid is not the app itself. `canSignal` from the previous scan is only a presentation hint. This also rejects a recycled pid that now belongs to another app.

All polling loops are lifecycle-bound (`start`/`stop` or `pause`/`DisposableEffect`) and cancellable; no shell process is created.

### 4.3 Processes that must never be terminated by bulk operations

- App's own process — never appears in workspace tree by construction
- Root processes (`isRoot`) — refused by `terminate()` with `tm_root_protected`
- sshd/ssh/dropbear (`ROOT_DAEMONS`) — keep ROOT emulation
- Xvnc — workspace-owned daemon; stopped via `stopAllDaemons` on workspace stop
- Audio/notification/clipboard pumps — feature supervisors, workspace-owned
- GPU supervisor — host GPU server; stopped via `gpuSupervisor.stopFor`

### 4.4 Measurement

All CPU figures use one convention: **% of total device capacity**, 0–100, normalised by the online/core-capacity count from `CpuTopologyProvider`. Per-core values are keyed by kernel core number so an offline core cannot shift another core's slot. Values below 1% keep one decimal (`0.4%`); a genuine zero is `0%`; pending and unavailable remain distinct from zero. Formatting lives in `CpuPercentFormatter` and is shared by Activity, Sessions, the workspace performance panel, and Task Manager.

Process CPU is `utime + stime` only (reaped-child time excluded, so rows do not double-count live children). The denominator is online device capacity rather than a single core. Device CPU is derived from `/proc/stat` deltas through `CpuUsageCalculator`; the first tick is pending, and an unreadable `/proc/stat` is reported as unavailable rather than `0%`.

Memory uses PSS from `smaps_rollup` when readable and falls back to `VmRSS` (marked approximate) otherwise. Workspace rows and the aggregate come from the same snapshot, so the card cannot disagree with its process list within a tick.

Task Manager samples device RAM/CPU, app PSS/CPU, and the whole active workspace. The Activity/Sessions tabs use the same shared samplers and `WorkspaceAggregator` roll-up — see [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md).

---

## 5. Limitations and known issues

- **No phantom-process-limit handling.** Android 12+ can kill an app holding >32 child processes; a desktop session (Xvnc + sshd + audio pipe + notification bridge + clipboard bridge + GPU supervisor + multiple terminals) can plausibly exceed it. Linexa has no detection or guidance. Not implemented; requires device validation of the actual threshold.
- **No background-helper labels.** No identification of processes that serve no session.
- **No bulk "End all" operations.** Per-process confirm only (`endTarget` / `hostEndTarget`).
- Orphans are included by rootfs-path matching while the session is alive, but whether every PRoot daemon form exposes the path in `cmdline` is device-unverified. Startup sweep remains the fallback.
- **Host list is not the whole device.** Android `hidepid`/SELinux normally hides other apps; the UI reports the readable and inaccessible counts instead of implying completeness.
- **Real-device `/proc` validation pending.** Namespace visibility, `/proc/stat` readability, per-core `last_core` availability, `smaps_rollup` permissions, and SIGTERM behavior under Android SELinux have not been exercised on hardware.
- GPU metric honesty (Adreno real vs other "unknown") still needs device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md) — Activity/Sessions measurement scopes
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime, daemon management
- [`decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md) — task manager Keep/Fix/Refactor decision
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
