# Performance

**Status:** Current — describes the implemented performance architecture and known considerations, verified against source where possible.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/ui/screens/taskmanager/`, `app/src/main/java/com/fem/linexa/ui/screens/desktop/`, `app/src/main/java/com/fem/linexa/domain/resource/`, `app/src/main/java/com/fem/linexa/data/resource/`, `app/src/main/java/com/fem/linexa/service/runtime/container/PRootLauncher.kt`, `app/src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt`

---

## 1. Purpose

This document is the authoritative reference for Linexa's performance architecture: startup/initialization, memory management, CPU and background processing, process management, Compose performance, resource lifecycle, known bottlenecks, and verified optimizations.

---

## 2. Scope

In scope:

- Performance architecture and measurement scopes
- Startup and initialization
- Memory management (PSS/RSS, ActivityManager view)
- CPU and background processing (polling discipline, coalescing)
- Process management (ppid walk, attribution, termination)
- Compose performance (recomposition scoping, inline `.dp` literals, large composables)
- Resource lifecycle (wake lock 60-min ceiling, foreground service)
- Known bottlenecks (VNC receive loop on Main dispatcher, dirty-region degradation, Raw decode allocations)
- Verified optimizations (Choreographer hop removal, sampling gated by `start()/stop()`)
- Potential future improvements (clearly distinguished from implemented)

Out of scope:

- Build performance: [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Measurement scopes (device/app/workspace, null≠0) | Implemented | `HostResourceSampler`, `WorkspaceAggregator` |
| Polling gated by `rememberIsForeground` | Implemented | `ui/components/Foreground.kt` |
| Ticks coalesced <750 ms | Implemented | `SessionsViewModel` |
| Samplers start/stop gated | Implemented | `TaskManagerViewModel` KDoc |
| PSS preferred, RSS flagged approximate | Implemented | `ProcMetricParsers.kt` |
| Wake lock opt-in, 60-min ceiling | Implemented | `service/runtime/wakelock/` |
| Foreground service lifecycle | Implemented | `RuntimeService.kt` |
| Choreographer hop removed from VNC upload | Implemented | `VncSurfaceView.kt` |
| VNC receive loop on Main dispatcher | Known bottleneck | `VncClient.kt`, `DesktopScreen.kt` |
| Dirty-region acknowledge degrades to full-frame | Known bottleneck | `VncDirtyRegion.kt` |
| Raw decode: 3 buffers, 3 passes per rect | Known bottleneck | `VncPixels.kt`, `VncClient.kt` |
| 637 inline `.dp` literals in `ui/screens/*` | Drift | `Dimens.kt` KDoc |
| Large composables (>1,000 lines) | Present | `WorkspaceScreen.kt` (1,313), `SessionsSegment.kt` (1,197), `FileManagerScreen.kt` (1,687), `DesktopViewModel.kt` (1,611) |
| No version catalog | Present | `build.gradle.kts` |
| `isMinifyEnabled = false` | Present | `build.gradle.kts` |
| Device-verified performance numbers | Not executed | no device; UNVERIFIED |

---

## 4. Technical overview

### 4.1 Performance architecture

Linexa measures resources across three scopes that are never mixed:

| Scope | CPU | RAM | Owner |
|---|---|---|---|
| Device | `/proc/stat` delta | `ActivityManager.MemoryInfo` (total − available) | `HostResourceSampler` |
| Linexa app | `/proc/<own pid>/stat` delta | `Debug.getMemoryInfo` PSS | `HostResourceSampler` |
| Workspace | sum over process tree | `Pss` (else `VmRSS`, approximate) | `ResourceMonitor` + `WorkspaceAggregator` |

The app process and PRoot processes are different pids, so neither figure contains the other. Device figures enter a workspace number only as the RAM-bar denominator.

### 4.2 Startup and initialization

- Workspace start: `RuntimeViewModel.bindService()` → `RuntimeService` → `WorkspaceSessionSupervisor.run()` → launch validator → `RuntimeLifecycleManager.requestStart()` → `WorkspaceTerminalManager.openTerminal()` → `PRootContainerRuntime.openTerminal()`.
- `openTerminal` performs: bootstrap self-heal, per-terminal `PROOT_TMP_DIR` scope, guest `/tmp` repair, dpkg health check (+ optional `dpkg --configure -a` preflight exec), config.yaml overrides, seccomp probe/fallback, then `ptySessionFactory.create()` on the Main thread.
- Desktop start: four sequential one-shot PRoot execs + two PRoot daemon hosts, plus 150 ms polling granularity. Nothing parallelised, cached, or short-circuited. (Known bottleneck — no timing measured.)

### 4.3 Memory management

- PSS preferred over RSS; RSS flagged "approximate".
- `ActivityManager.MemoryInfo` gives the Android view (process budget before LMK); `/proc/meminfo` gives the Linux view. Both surface separately, never merged.
- `memoryClass`/`largeMemoryClassMb` describes APP heap budget, not device RAM.
- Workspace RAM stays with `WorkspaceFileManager` (`WorkspaceStorage`); nothing merges device storage with workspace usage.

### 4.4 CPU and background processing

- Polling loops run only while the screen is STARTED (`rememberIsForeground`); ticks serialised and coalesced (<750 ms apart); delta baselines dropped for trees no longer sampled.
- `DeviceCpu.readable` distinguishes *unavailable* (SELinux-denied `/proc/stat` on Android 8+) from *measuring*; UI says "Not exposed by this Android version" instead of a dash. No substitute metric invented.
- `collectAsState()` used 83× vs `collectAsStateWithLifecycle` 1× (a comment) — documented crash workaround on the pinned toolchain (lifecycle 2.8.1 + Compose UI 1.6 + Nav 2.7.7). Flows stay hot while backgrounded unless producers are start/stop gated. `lifecycle-runtime-compose:2.8.1` is already a dependency; migration to lifecycle-aware collection requires no new dependency but is deferred pending a deliberate toolchain bump. Six `/proc` pollers at 2 s are manually gated; a missed pause is the battery risk.

### 4.5 Process management

- Attribution via ppid walk (`ProcessTree.descendantsOf`); correct for descendants; orphaned daemons (ppid=1) coverage unverified (INVESTIGATE only).
- `WorkspaceAggregator` roll-up is pure and unit-tested; pids reachable from two roots counted once; unknown CPU stays null.
- Termination: SIGTERM + 3×500 ms verification; root protection; app PID protection; `ROOT_DAEMONS` (sshd/ssh/dropbear) preserved.
- Wake lock: opt-in `PARTIAL_WAKE_LOCK`, 60-minute ceiling, enforced twice (platform timeout + generation-guarded internal timer). Never taken automatically.

### 4.6 Compose performance

- 54 `animate*/AnimatedVisibility/Crossfade/InfiniteTransition` sites; tab switch 90 ms simultaneous fade.
- `MaterialTheme.colorScheme` referenced 146× vs 7 direct Haven-color accessor uses.
- 637 inline `.dp` literals in `ui/screens/*` (drift, not a bug).
- Large composables hamper recomposition scoping: `WorkspaceScreen.kt` (1,313 lines), `SessionsSegment.kt` (1,197), `FileManagerScreen.kt` (1,687), `DesktopViewModel.kt` (1,611).
- Pixel copy on `VncFrameUpload` worker thread (not Main); only the swap is on Main.

### 4.7 Resource lifecycle

- Foreground-service notification (workspace name + live terminal count + status) while workspace entered; opt-in wake lock action.
- `onTaskRemoved`: active workspace keeps foreground service; idle service stops.
- Startup recovery sweep on app start: kills stale own-uid workspace processes, releases PRoot scratch scopes, cleans dead tmux sockets, kills stale GPU host servers.
- Overlay ticker: 30 s cadence, Running-only, stopped in `onDestroy`; app-idle cost zero.

### 4.8 Known bottlenecks

| Bottleneck | Location | Status |
|---|---|---|
| VNC receive loop on Main dispatcher (2 dispatches + 1 Main turn per message) | `VncClient.kt`, `DesktopScreen.kt` | Known, not fixed |
| Dirty-region acknowledge degrades to full-frame uploads under load | `VncDirtyRegion.kt` | Known, not fixed |
| Raw decode: 3 intermediate buffers, 3 pixel passes per rect | `VncPixels.kt`, `VncClient.kt` | Known, not fixed |
| Pointer writes dispatched through Main scope (input competes with frame delivery) | `VncClient.kt` | Known, not fixed |
| Desktop start: 4 sequential one-shot execs + 2 daemons, 150 ms polling | `DesktopViewModel.kt`, `PRootContainerRuntime.kt` | Known, not fixed |

### 4.9 Verified optimizations

| Optimization | Location | Effect |
|---|---|---|
| Choreographer hop removed from VNC upload path | `VncSurfaceView.kt` | Removes up to one display frame per update |
| Sampling gated by `start()/stop()` | `TaskManagerViewModel` | Flows not hot while backgrounded (for this VM) |
| Ticks coalesced <750 ms | `SessionsViewModel` | Prevents shrink of CPU delta window |
| `uploadInFlight` coalescing | `VncSurfaceView.kt` | Independent frame coalescing |
| Fail-open parsers (total functions) | `GuiBackend.fromId`, `DesktopRunMode.fromId` | No exception overhead on invalid input |

### 4.10 Potential future improvements (NOT implemented)

Clearly distinguished from existing functionality:

- Per-exec setup cost measurement on desktop start path
- PRoot syscall overhead (lstat) measurement
- Raw-decode CPU/GC optimization (single-buffer decode)
- UI collection cost profiling while backgrounded
- `collectAsStateWithLifecycle` migration (requires deliberate toolchain bump)
- Version catalog adoption (mechanical, no version change)
- R8/minify for release (risky: Hilt, serialization, JNI, proot naming — needs device test)

None of the above is committed to as a fix. Measure first (T-601), then decide.

---

## 5. Limitations and known issues

- No performance number in the archive was measured on a device. All bottleneck claims are code-level critical paths, not measured timings.
- GPU metrics honesty: Adreno shows real `gpubusy` value; other GPUs show "unknown" (never fabricated 0) — unverified on device.
- core performance flows still need on-device verification; see [`engineering/TESTING_STRATEGY.md`](./TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`features/WORKSPACE_AND_SESSIONS.md`](../features/WORKSPACE_AND_SESSIONS.md) — Activity/Sessions measurement scopes
- [`features/DESKTOP_AND_VNC.md`](../features/DESKTOP_AND_VNC.md) — VNC client performance characteristics
- [`features/TASK_MANAGER.md`](../features/TASK_MANAGER.md) — process sampling
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime, startup path
- [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md) — build commands, minify decision
- [`engineering/TESTING_STRATEGY.md`](./TESTING_STRATEGY.md) — verification status
