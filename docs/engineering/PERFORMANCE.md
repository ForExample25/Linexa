# Performance

**Source locations:** `app/src/main/java/com/fem/linexa/ui/screens/taskmanager/`,
`app/src/main/java/com/fem/linexa/ui/screens/desktop/`,
`app/src/main/java/com/fem/linexa/domain/resource/`,
`app/src/main/java/com/fem/linexa/data/resource/`,
`app/src/main/java/com/fem/linexa/service/runtime/`

---

## 1. Purpose

This document describes Linexa's performance architecture: what is measured,
where polling happens, how memory and CPU are attributed, and which parts of
the runtime and desktop are inherently expensive. It does not contain measured
timings; use the in-app diagnostics and Android profiling tools for those.

---

## 2. Measurement scopes

Three scopes are reported separately and never mixed:

| Scope | CPU | RAM | Owner |
|---|---|---|---|
| Device | `/proc/stat` delta | `ActivityManager.MemoryInfo` (total − available) | `HostResourceSampler` |
| Linexa app | `/proc/<own pid>/stat` delta | `Debug.getMemoryInfo` PSS | `HostResourceSampler` |
| Workspace | sum over attributable processes | PSS, else `VmRSS` (approximate) | `ProcResourceMonitor` + `WorkspaceAggregator` |

The app process and PRoot guest processes are different PIDs. Neither figure
contains the other. Device memory is used only as the denominator for the
workspace RAM bar.

CPU percentages mean percent of total device capacity, normalized by the
online core count. Per-core values are keyed by kernel core number. Values
below 1% keep one decimal; unknown is distinct from zero. Formatting is shared
through `CpuPercentFormatter`.

---

## 3. Startup and launch

### 3.1 Workspace start

```
RuntimeViewModel.bindService()
  → RuntimeService
  → WorkspaceSessionSupervisor.run()
  → launch validation
  → RuntimeLifecycleManager.requestStart()
  → WorkspaceTerminalManager.openTerminal()
  → PRootContainerRuntime.openTerminal()
```

`openTerminal` performs bootstrap self-heal, per-terminal `PROOT_TMP_DIR`
setup, guest `/tmp` repair, dpkg health check (with optional
`dpkg --configure -a`), `config.yaml` overrides, the seccomp probe/fallback,
and PTY creation. PTY creation is dispatched to the main Looper because the
terminal session captures the constructing thread's Looper.

Every launch also refreshes `/proc` overlays and the host-info feed. These are
small file writes, but they are additional I/O on the launch path.

### 3.2 Desktop start

VNC mode performs several sequential one-shot execs (Xvnc/session discovery,
authentication provisioning) and starts one or two supervised daemons. Port
and socket readiness are polled at 150 ms granularity. The steps are
sequential by design; there is no parallel preflight or cached probe.

WebView mode starts one guest web server and polls its TCP port.

---

## 4. Memory

- PSS is preferred. If `smaps_rollup` is unreadable, `VmRSS` is shown and
  marked approximate.
- `ActivityManager.MemoryInfo` describes the Android process budget before
  LMK; `/proc/meminfo` describes the Linux memory view. Both are shown
  separately.
- `memoryClass`/`largeMemoryClass` describe the app heap budget, not device
  RAM.
- Workspace storage is measured separately from device storage. Nothing merges
  the two.

---

## 5. Polling and background work

- Screens that show live metrics poll only while visible
  (`rememberIsForeground`, `start`/`pause`, `DisposableEffect`).
- Shared sampling intervals live in `ProcessPolling.INTERVAL_MS` (2 s).
  Task Manager, Activity, Sessions, Device Info, and the workspace performance
  panel use the same cadence.
- Ticks are serialized and coalesced. A slow tick does not queue another.
- Delta baselines are dropped for process trees that are no longer sampled.
- The provider layer is pull-based; its TTLs prevent duplicate collection when
  several screens ask within the same window.
- The `/proc` overlay ticker runs only while at least one workspace is running
  and stops in `RuntimeService.onDestroy`.

`collectAsState()` is used throughout the UI. `collectAsStateWithLifecycle()`
appears only in a comment because the pinned lifecycle/Compose/Navigation
versions have a documented interaction; migration requires a coordinated
dependency bump. Flows remain hot while a screen is backgrounded unless the
producer is start/stop gated, which is why the gating above matters.

---

## 6. Process attribution

`ProcessTree.descendantsOf` walks parent PIDs from `/proc/*/stat`;
`ProcessAttribution` adds rootfs-path matching for processes outside the
parent tree. `WorkspaceAggregator` merges rows by PID so a process reachable
from two roots is counted once. Unknown CPU stays null rather than becoming
zero.

Termination uses SIGTERM plus three verification reads 500 ms apart. Root
supervisors and the app's own PID are protected.

---

## 7. Compose

- The UI is a single-activity Compose tree. Tab switches use a 90 ms crossfade.
- Haven components use tone-step surfaces and hairlines; there are no blur or
  glass effects.
- Several screens and view models are large, which makes recomposition scoping
  harder. Split state holders when a screen's state changes independently of
  the whole screen.
- Many screens still use inline `.dp` values and direct Material 3 colors.
  This is a consistency cost, not a runtime cost.
- There are no baseline profiles or macrobenchmarks in the repository.

---

## 8. Desktop and VNC

- The RFB receive loop and pointer/key writes run on `Dispatchers.IO`. The
  client owns its own coroutine scope; `disconnect()` stops it.
- Dirty regions are tracked per published generation. An acknowledgement
  retires every generation it covers, which prevents the previous
  full-frame-upload ratchet.
- Raw rectangles are decoded into the shared framebuffer and uploaded by
  `VncSurfaceView`. Uploads are coalesced with `uploadInFlight`; there is no
  Choreographer hop.
- ZRLE is available but not offered by default. On loopback, the bandwidth
  saving is small relative to decode cost; it can be enabled per workspace.
- No cursor pseudo-encoding is negotiated, so cursor updates ride the normal
  framebuffer path.
- Framebuffer geometry is fixed at start. A smaller preset (720p) reduces
  per-frame pixel work substantially on slow devices.

`VncRenderStats` exposes frame, upload, and dirty-region counters through the
diagnostics surface for measuring changes.

---

## 9. Resource lifecycle

- A foreground service keeps a running workspace alive with a notification
  that shows the workspace name, live terminal count, and status.
- The wake lock is opt-in, uses `PARTIAL_WAKE_LOCK`, and has a 60-minute
  ceiling enforced by both the platform timeout and an internal timer. It is
  never acquired automatically.
- `onTaskRemoved` keeps the foreground service for an active workspace and
  stops an idle one.
- App startup runs a recovery sweep: stale own-UID workspace processes, PRoot
  scratch scopes, dead tmux sockets, and stale GPU host servers.
- RootFS overlays are refreshed only for running workspaces.

---

## 10. Known costs

| Area | Cost | Mitigation |
|---|---|---|
| RootFS install | Extraction and validation are I/O- and CPU-heavy | Staged install, progress reporting, no activation before verification |
| Workspace start | Multiple sequential PRoot execs and daemon starts | Steps are ordered and fail-fast; no parallel preflight |
| Desktop start | Sequential probes plus 150 ms port polling | Fixed presets and provider probing avoid unnecessary installs |
| VNC Raw decode | Pixel copies per rectangle | Dirty-region generations and upload coalescing |
| VNC ZRLE | Inflate cost on loopback | Not offered by default |
| Process sampling | Reads `/proc` for each visible process | Shared 2 s cadence, one snapshot per scope |
| Large Compose screens | Broad recomposition scope | Split state holders when changing a screen |

These are structural costs. Measure before changing a transport or polling
cadence.

---

## 11. Limitations

- No macrobenchmark, baseline profile, or startup-time measurement is checked
  in.
- GPU busy/clock values depend on vendor sysfs nodes. Adreno devices may
  expose them; other GPUs report unknown.
- `/proc/stat` may be restricted on modern Android, so device CPU can be
  unavailable rather than zero.
- Release builds are not minified. R8 safety for Hilt, serialization, JNI, and
  the PRoot naming scheme has not been established.
- Performance work is hardware-dependent; emulator results are not
  representative for PRoot or VNC.

---

## 12. Related documentation

- [`features/TASK_MANAGER.md`](../features/TASK_MANAGER.md) — process sampling and termination
- [`features/DESKTOP_AND_VNC.md`](../features/DESKTOP_AND_VNC.md) — VNC client and geometry
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — launch path
- [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md) — build settings
