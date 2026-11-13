# Host System Information

**Status:** Current — describes the implemented host system information subsystem, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/domain/hostinfo/`, `app/src/main/java/com/fem/linexa/data/hostinfo/`, `app/src/main/java/com/fem/linexa/service/runtime/hostinfo/`, `app/src/main/java/com/fem/linexa/ui/screens/performance/`, `app/src/main/java/com/fem/linexa/ui/screens/device/`

---

## 1. Purpose

This document is the feature-level reference for Linexa's host system information subsystem: what the Android app is authoritative for, how a RootFS or UI consumes it, which values remain RootFS-local, and every platform restriction that shapes those answers.

---

## 2. Scope

In scope:

- Core principle: Android app is single authoritative source of host facts; RootFS environments must not discover Android host info themselves
- Provider contract (`HostSystemInfoProvider`, selective snapshot, TTL policy)
- Snapshot model and capability model (`HostInfoAvailability`)
- Source-of-truth matrix (what is collected, from where)
- Semantics guards (Linux vs Android available, host uptime vs guest view, device storage vs workspace usage)
- Runtime bridge & Linux compatibility (`/proc` overlays, `LINEXA_HOST_*` env channel, `/run/linexa/hostinfo.conf`, `linexa-hostinfo`)
- Terminal Workspace / UI integration (Performance dashboard, Device & System Information screen)
- Security & privacy considerations
- Testing strategy and verification state

Out of scope:

- PRoot runtime internals: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Feature toggles: [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| `HostSystemInfoProvider` interface + `AndroidHostSystemInfoProvider` | Implemented | `domain/hostinfo/`, `data/hostinfo/` |
| Selective snapshot (`snapshotOf(categories)`) | Implemented | provider contract |
| TTL-driven refresh policy (per-category TTLs) | Implemented | `HostInfoRefreshPolicy` |
| Capability model (`AVAILABLE/UNAVAILABLE/PERMISSION_DENIED/UNSUPPORTED/RESTRICTED/ERROR`) | Implemented | `HostInfoAvailability` |
| Source-of-truth matrix (CPU, memory, swap, storage, device, battery, thermal, network, processes) | Implemented | `AndroidHostInfoSources` |
| `/proc` overlay bind-overs (stat, uptime, loadavg, version, vmstat) | Implemented | `HostInfoRuntimeBridge`, `ProcSnapshotContentFactory` |
| `LINEXA_HOST_*` env channel (selective, import-validated) | Implemented | `WorkspaceConfigParserHostInfoTest` |
| `/run/linexa/hostinfo.conf` + `linexa-hostinfo` channel | Implemented | `GuestRootfsInitializer.ensureGuestHostInfo` |
| Performance dashboard (`PerformanceViewModel`, `PerformanceDashboardMapper`) | Implemented | `ui/screens/performance/` |
| Device & System Information screen | Implemented | `ui/screens/device/` |
| Read-only overlay enforcement (0444) | Implemented | `ReadOnlyOverlayFile`, `HostInfoRuntimeBridgeTest` |
| Device-verified host/guest parity | Not executed | no device available; UNVERIFIED-by-design |

---

## 4. Technical overview

### 4.1 Core principle

> **Linux RootFS environments must NOT discover Android host information themselves.**

The Android application is the single, authoritative source of host-level facts. RootFS environments and UI surfaces **request** only the facts they need from Linexa; they never parse Android files, never shell out to discover the host, and never depend on any specific distro behavior.

### 4.2 Package map

| Package | Contents |
|---|---|
| `com.fem.linexa.domain.hostinfo` | Capability enum(s), category enums, typed snapshot models, `HostSystemInfoProvider` interface, `HostInfoKey` + pure resolver, TTL policy table |
| `com.fem.linexa.data.hostinfo` | `HostInfoSources` seam, `AndroidHostInfoSources` (single platform touchpoint), `AndroidHostSystemInfoProvider` (cache orchestration) |
| `com.fem.linexa.service.runtime.hostinfo` | `HostInfoRuntimeBridge`, `ProcSnapshotContentFactory` |
| `ui/screens/performance/` | `PerformanceViewModel` + mapper — Performance dashboard consumes ONLY provider-typed data |

DI wiring in `di/AppModule.kt` (`provideHostSystemInfoProvider`, `provideHostInfoRuntimeBridge`), both `@Singleton`.

### 4.3 Provider contract

```kotlin
interface HostSystemInfoProvider {
    suspend fun snapshot(): HostSystemInfoSnapshot          // TTL-driven refresh
    suspend fun refreshSnapshot(): HostSystemInfoSnapshot   // forced re-collect
    suspend fun snapshotOf(categories): HostSystemInfoSnapshot // SELECTIVE
    fun lastSnapshot(): HostSystemInfoSnapshot?             // cache-only peek
    fun invalidateAll()
}
```

Guarantees (all asserted by tests):

1. **Never throws.** A collection failure becomes a capability flag on a complete snapshot.
2. **Never fabricates.** Unknown values are `null` fields inside complete models; categories always exist.
3. **TTL-driven refresh** — pull-based only; nothing polls in the background.
4. **Selective path** (`snapshotOf`) collects ONLY named categories; unnamed ones ride cache with zero I/O.
5. **Last-known-good survives failures**, its per-category stamp stops moving so staleness is visible; a never-succeeded category reports `ERROR` instead of empty-guessing.

### 4.4 Refresh policy

| Category | TTL | Rationale |
|---|---|---|
| SYSTEM | 30 min | identity strings are static for a boot cycle |
| STORAGE | 10 s | capacity answers gate real decisions |
| NETWORK | 5 s | posture changes at human timescales |
| THERMAL | 2 s | status moves slowly |
| BATTERY | 2 s | thermal mirrors battery temp |
| MEMORY / SWAP | 1 s | fast tier |
| CPU | 600 ms | delta floor; shorter windows read mostly zero |

`MIN_FORCED_REFRESH_MS = 100` rate-guards forced refreshes.

### 4.5 Snapshot model

`HostSystemInfoSnapshot` — one immutable value object per refresh:

```
cpu       : HostCpuInfo         memory   : HostMemoryInfo
swap      : HostSwapInfo        storage  : HostStorageInfo
device    : HostDeviceInfo      battery  : HostBatteryInfo
thermal   : HostThermalInfo     network  : HostNetworkInfo
processes : HostProcessInfo
capabilities          : Map<HostInfoCategory, HostInfoAvailability>
collectedAtPerCategory: Map<HostInfoCategory, Long>
collectedAtEpochMs    : Long
```

Encoding rules:
- Fields are nullable; categories never are. `null == unknown`.
- Data classes are immutable value objects — refresh produces new instances.

### 4.6 Source-of-truth matrix (summary)

All values measured through `AndroidHostInfoSources`; none fabricated. Key entries:

| Category | Field(s) | Source |
|---|---|---|
| CPU | logicalCores | `_SC_NPROCESSORS_CONF` → `availableProcessors()` |
| CPU | overallUsagePercent | `/proc/stat` deltas (RESTRICTED on many modern Android kernels → null) |
| Memory | memTotal/memAvailable/usedLinux | `/proc/meminfo` |
| Memory | androidTotal/androidAvail | `ActivityManager.MemoryInfo` |
| Swap | total/free/used | meminfo SwapTotal/SwapFree |
| Storage | primary volume | `StatFs(filesDir)` + parent volume metadata |
| System | androidRelease/sdkInt/… | `Build.VERSION.*` |
| System | kernelRelease/kernelVersion | `Os.uname()` (NOT `/proc/version`) |
| Battery | percent/state/… | sticky `ACTION_BATTERY_CHANGED` |
| Thermal | status/severity/headroom | `PowerManager` (API 29+/30+) |
| Network | transports/validated/metered | `ConnectivityManager` caps |
| Processes | self pid/name/RSS/PSS/cpu ticks | `/proc/self/{stat,status,smaps_rollup}` |

### 4.7 Semantics guards

1. **Linux available ≠ Android available.** `MemAvailable` (meminfo) vs `ActivityManager.availMem`. Both surface separately; the Performance dashboard prefers Linux semantics while marking low-memory state from Android's answer.
2. **Host uptime ≠ guest view.** Host uptime (`elapsedRealtime`) is authoritative; guests see it via `/proc/uptime` overlay. Kernel release: guests ALWAYS see the spoofed `6.1.0-linexa`; real host release travels only as env/host-diagnostic values.
3. **Device storage ≠ workspace usage.** `HostStorageInfo` answers device capacity; rootfs/workspace bytes stay with `WorkspaceFileManager` (`WorkspaceStorage`). Nothing merges them.

### 4.8 Runtime bridge & Linux compatibility

**/proc compatibility layer:** PRootLauncher binds `-b {rootfs}/proc/.<name>:/proc/<name>`. `GuestRootfsInitializer.ensureProcSnapshots` is the install-time baseline writer. At every launch site, `PRootContainerRuntime.refreshHostProcOverlays(...)` calls the bridge, which pulls ONE selective snapshot (SYSTEM+CPU) and rewrites all five files atomically (temp→rename). While sessions run, `RuntimeService` drives `HostInfoRuntimeBridge.startOverlayTicker(sessionStates)` (30 s cadence, Running-only, stopped in `onDestroy`).

Content mapping:
| File | Content source |
|---|---|
| `.uptime` | REAL seconds (host `elapsedRealtime`) |
| `.stat` | REAL core count, REAL cumulative tick totals when host stat readable |
| `.loadavg` | REAL three averages when reported |
| `.version` | ALWAYS the spoofed `6.1.0-linexa` line |
| `.vmstat` | legacy minimal set unchanged |

**DO-NOT-LIE boundary:** Only the five files above are overlaid, and only where the guest CANNOT read host truth anyway. Everything else under guest `/proc` and ALL mounts/process management/signals/networking remain the existing runtime behavior — untouched.

### 4.9 Environment channel — `LINEXA_HOST_*`

A workspace names facts in `config.yaml` (validated at import time):

```yaml
linexa:
  host_info:
    enable: true
    keys:
      - CPU_CORES
      - MEMORY_TOTAL_KB
```

At every launch, the resolved key set goes to `snapshotOf(categoriesFor(keys))` — collecting ONLY relevant categories — then through the pure `HostInfoKeyResolver`. Output rules:
- Names are `LINEXA_HOST_` + enum name, e.g. `LINEXA_HOST_CPU_CORES=8`.
- **Unknown facts emit NO variable** (not empty, not -1, not 0).
- Rendering is deterministic (dot decimals, lowercase booleans).

Defaults (minimal-on-purpose): `CPU_CORES`, `MEMORY_TOTAL_KB`, `MEMORY_AVAILABLE_KB`, `SWAP_TOTAL_KB`, `SWAP_FREE_KB`, `STORAGE_FREE_BYTES`, `UPTIME_SECONDS`, `ANDROID_VERSION`, `ANDROID_SDK_INT`, `DEVICE_MODEL`, `DEVICE_MANUFACTURER`, `MACHINE_ARCH`.

The vendored proot's env-filter patch (0004) scrubs exactly the HOST-owned bootstrap keys (`LD_LIBRARY_PATH`, `PROOT_*`, …); additive `LINEXA_HOST_*` keys pass through untouched.

### 4.10 Terminal Workspace / UI integration

`PerformanceScreen` now has a producer and a destination:
- `PerformanceViewModel` (@HiltViewModel) feeds EVERY metric on the dashboard from `HostSystemInfoProvider.snapshot()`.
- New `DeviceCard` renders ANDROID version/API, HOST kernel, device model+arch, battery %/charging, thermal severity, network type.
- Route `performance` in `NavGraph`; reached via **Global Settings → Performance**.
- Mapping lives in pure `PerformanceDashboardMapper` (JVM-testable), obeying the unknown≠zero rule.

**Device & System Information screen** (route `device-info`): reached via **Global Settings → Device & system information**. Data path identical to the RootFS bridge — one authoritative provider. Every mapper row carries a `known` flag; withheld fields render as an explicit muted dash. Two memory views (Linux semantics vs Android semantics) render as two labeled blocks — never merged.

---

## 5. Security & privacy considerations

- Least exposure by default: guests receive the minimal default key set; everything else is opt-in per workspace, import-validated.
- No position data: Wi-Fi identity/SSID/BSSID never collected (location data), independent of whether a grant exists.
- Known-restricted facts never fake availability: RESTRICTED is exported honestly rather than disguised as 0/empty.
- Overlay files land INSIDE the workspace directory (app-private storage, `app_data_file` label).
- No new permissions, no root, no services: the subsystem adds ZERO manifest entries, ZERO processes, ZERO notification channels.

---

## 6. Testing strategy and verification state

JVM unit tests cover: snapshot contract, selective resolution, cache/TTL/selectivity/failure, overlay factory, bridge files/env, config selection, regression (existing suites incl. Proot harness users).

Runtime validation against actual device values still needs on-device execution. Until then, treat device-parity claims as design-doc claims, not verified facts. See [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 7. Limitations and known issues

- `/proc/stat`, `/proc/loadavg`, `/proc/uptime`, `/proc/version`, `/proc/vmstat` reads from host are SELinux-denied on untrusted_app; overlays carry static synthetic values.
- Other apps' processes: permanently RESTRICTED (hidepid); no API path.
- Battery design capacity, CPU/GPU/skin zone temperatures: permanent known-unknowns.
- Wi-Fi SSID/BSSID: never collected (location data by policy).
- Device/guest parity unverified on hardware.

---

## 8. Related documentation

- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot `/proc` binds, snapshot bind-overs
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — `config.yaml` `linexa.host_info` schema
- [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md) — Activity/Sessions measurement scopes
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
