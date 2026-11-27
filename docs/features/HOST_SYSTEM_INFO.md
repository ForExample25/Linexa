# Host System Information

**Source locations:** `app/src/main/java/com/fem/linexa/domain/hostinfo/`,
`app/src/main/java/com/fem/linexa/data/hostinfo/`,
`app/src/main/java/com/fem/linexa/service/runtime/hostinfo/`,
`app/src/main/java/com/fem/linexa/ui/screens/device/`

---

## 1. Purpose

Linexa exposes Android host facts (CPU, memory, storage, battery, thermal,
network, and device identity) to its own UI and, selectively, to Linux guests.
The Android app is the single authoritative source of those facts. Guests do
not discover host information themselves; they receive only the values the
workspace asks for.

---

## 2. Scope

In scope:

- `HostSystemInfoProvider` contract and caching policy
- Snapshot and capability models
- Source-of-truth matrix
- Semantics that distinguish Linux, Android, host, and guest values
- `/proc` compatibility overlays
- `LINEXA_HOST_*` environment channel
- `/run/linexa/hostinfo.conf` and the `linexa-hostinfo` helper
- Device & System Information screen
- Privacy limits

Out of scope:

- PRoot runtime internals: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Workspace/session controls: [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md)

---

## 3. Core principle

> Linux RootFS environments must not discover Android host information
> themselves.

The app collects host facts through Android APIs and `/proc` where readable,
normalizes them into typed models, and exposes them through one provider.
Guests receive a minimal default set or an explicitly requested key set. No
guest-side script parses Android files or shells out to discover the host.

---

## 4. Package map

| Package | Contents |
|---|---|
| `com.fem.linexa.domain.hostinfo` | Categories, availability flags, typed models, provider interface, key resolver, TTL policy |
| `com.fem.linexa.data.hostinfo` | `HostInfoSources` seam, `AndroidHostInfoSources` (platform access), `AndroidHostSystemInfoProvider` (cache orchestration) |
| `com.fem.linexa.service.runtime.hostinfo` | `HostInfoRuntimeBridge`, `ProcSnapshotContentFactory`, `EnvTickSampler` |
| `com.fem.linexa.ui.screens.device` | `DeviceInfoViewModel`, `DeviceInfoMapper`, `DeviceInfoScreen` |

DI bindings (`provideHostSystemInfoProvider`, `provideHostInfoRuntimeBridge`)
are `@Singleton` in `di/AppModule.kt`.

---

## 5. Provider contract

```kotlin
interface HostSystemInfoProvider {
    suspend fun snapshot(): HostSystemInfoSnapshot
    suspend fun refreshSnapshot(): HostSystemInfoSnapshot
    suspend fun snapshotOf(categories: Set<HostInfoCategory>): HostSystemInfoSnapshot
    fun lastSnapshot(): HostSystemInfoSnapshot?
    fun invalidateAll()
}
```

Guarantees:

1. **Never throws.** Collection failures become capability flags on a complete
   snapshot.
2. **Never fabricates.** Unknown values are `null` fields; categories always
   exist.
3. **Pull-based.** Nothing polls in the background inside the provider.
4. **Selective collection.** `snapshotOf` collects only the named categories;
   the rest resolve from cache without I/O.
5. **Last-known-good survives failures.** A failed category keeps its previous
   values and reports `ERROR` if it has never succeeded; its per-category
   timestamp stops advancing so staleness is visible.

### 5.1 Refresh policy

| Category | TTL |
|---|---|
| `SYSTEM` | 30 min |
| `STORAGE` | 10 s |
| `NETWORK` | 5 s |
| `THERMAL` | 2 s |
| `BATTERY` | 2 s |
| `GPU` | 2 s |
| `MEMORY` / `SWAP` | 1 s |
| `CPU` | 600 ms |
| `PROCESSES` | 1 s |

`MIN_FORCED_REFRESH_MS = 100` rate-limits forced refreshes.

### 5.2 Snapshot model

`HostSystemInfoSnapshot` is one immutable value object per refresh:

```
cpu        HostCpuInfo         gpu        HostGpuInfo
memory     HostMemoryInfo      swap       HostSwapInfo
storage    HostStorageInfo     device     HostDeviceInfo
battery    HostBatteryInfo     thermal    HostThermalInfo
network    HostNetworkInfo     processes  HostProcessInfo

capabilities           Map<HostInfoCategory, HostInfoAvailability>
collectedAtPerCategory Map<HostInfoCategory, Long>
collectedAtEpochMs     Long
```

Fields are nullable; categories are not. `null` means unknown. Refresh
produces new instances.

`HostInfoAvailability` distinguishes `AVAILABLE`, `UNAVAILABLE`,
`PERMISSION_DENIED`, `UNSUPPORTED`, `RESTRICTED`, and `ERROR`.

---

## 6. Source of truth

| Category | Field | Source |
|---|---|---|
| CPU | logical/configured cores | `Runtime.availableProcessors()` and topology |
| CPU | usage | `/proc/stat` deltas; restricted on many Android kernels |
| CPU | per-core frequency/online state | `/sys/devices/system/cpu/` |
| GPU | renderer, vendor, busy, clock, temperature | `/sys/class/kgsl`, DRM, and vendor nodes when readable |
| Memory | total/available/used | `/proc/meminfo` |
| Memory | Android total/available | `ActivityManager.MemoryInfo` |
| Swap | total/free/used, zram | `/proc/meminfo`, `/proc/swaps` |
| Storage | primary volume | `StatFs(filesDir)` and volume metadata |
| System | Android release, SDK, security patch | `Build.VERSION.*` |
| System | kernel release/version | `Os.uname()` |
| Battery | percent, state, health, source | sticky `ACTION_BATTERY_CHANGED` |
| Thermal | status, severity, headroom | `PowerManager` (API 29/30+) |
| Network | transports, validated, metered, interfaces | `ConnectivityManager` |
| Processes | self PID, name, RSS/PSS, CPU ticks | `/proc/self/{stat,status,smaps_rollup}` |

All values pass through `AndroidHostInfoSources`, the single platform
touchpoint. A value that cannot be read is reported as unavailable or
restricted, never guessed.

---

## 7. Semantics

1. **Linux memory and Android memory are different views.** `MemAvailable`
   from `/proc/meminfo` and `ActivityManager.availMem` answer different
   questions. Both are shown separately in the Device screen and are never
   merged.
2. **Host uptime and guest uptime are different.** Host uptime comes from
   `SystemClock.elapsedRealtime`. Guests see a host uptime overlay, plus their
   own workspace-relative uptime in the host-info feed when a session runs.
3. **Kernel identity is spoofed for the guest.** Guests see
   `6.1.0-linexa`; the real host kernel is reported only in the app UI and the
   host-info feed.
4. **Device storage and workspace usage are different.** `HostStorageInfo`
   reports device capacity. Workspace bytes are reported by
   `WorkspaceFileManager`/`WorkspaceStorage`. Nothing merges them.

---

## 8. `/proc` compatibility overlays

Android denies app-UID access to several `/proc` entries. PRoot binds
generated files over them so guest tools do not fail with `EACCES`:

| Guest path | Generated when |
|---|---|
| `/proc/stat` | Always |
| `/proc/uptime` | Always |
| `/proc/loadavg` | Always |
| `/proc/version` | Always (spoofed `6.1.0-linexa`) |
| `/proc/vmstat` | Always |
| `/proc/meminfo` | Host `/proc/meminfo` is not readable |
| `/proc/swaps` | Host `/proc/swaps` is not readable |
| `/proc/cpuinfo` | Host `/proc/cpuinfo` is not readable |
| `/proc/net/dev` | Host `/proc/net/dev` is not readable |
| `/proc/net/route` | Host `/proc/net/route` is not readable |
| `/proc/net/if_inet6` | Host `/proc/net/if_inet6` is not readable and the device has IPv6 |

At install time, `GuestRootfsInitializer.ensureProcSnapshots` writes baseline
snapshots for `stat`, `uptime`, `loadavg`, `version`, and `vmstat` as hidden
files under `{rootfs}/proc/`. Before every terminal/exec launch,
`PRootContainerRuntime` refreshes them through `HostInfoRuntimeBridge`.
While any workspace is running, `RuntimeService` drives a 2-second ticker that
refreshes the overlays for every running workspace. The ticker stops in
`onDestroy`.

Writes are atomic (temporary file then rename) and mode `0444`. The runtime
never writes through a symlinked `/proc`; such a rootfs is skipped.

Only these entries are overlaid. Mounts, process management, signals, and
networking continue to behave as the runtime normally does.

---

## 9. `LINEXA_HOST_*` environment

A workspace can request host facts in `config.yaml`:

```yaml
linexa:
  host_info:
    enable: true
    keys:
      - CPU_CORES
      - MEMORY_TOTAL_KB
```

At launch, the key set is mapped to categories, collected through
`snapshotOf`, and resolved by `HostInfoKeyResolver`. Rules:

- Names are `LINEXA_HOST_` plus the key, for example
  `LINEXA_HOST_CPU_CORES=8`.
- Unknown facts emit no variable at all (not empty, `-1`, or `0`).
- Rendering is deterministic: dot decimals and lowercase booleans.
- The imported key list is validated; an unknown key does not grant access to
  another category.

The default set is intentionally small: CPU cores and affinity/online counts,
GPU model/vendor, memory and swap totals, storage free bytes, uptime, Android
version and SDK, device model/manufacturer, machine architecture, and network
type/interface. Additional keys are opt-in per workspace.

The vendored PRoot environment filter removes host-owned bootstrap variables
(`LD_LIBRARY_PATH`, `PROOT_*`, and similar); additive `LINEXA_HOST_*` keys pass
through.

---

## 10. Host-info feed and helper

Every overlay cycle also writes `/run/linexa/hostinfo.conf` inside the
workspace. The file contains the resolved key/value set, a generation
timestamp, and — while a session is running — the workspace's own uptime
labeled as `uptime.workspace.seconds`.

`/usr/local/bin/linexa-hostinfo` is a read-only POSIX-sh viewer for that file.
The guest can inspect host facts without parsing Android internals or relying
on a particular distribution's tooling.

---

## 11. Device & System Information screen

Route: `device-info`, reached from **Settings → Device & system information**.

`DeviceInfoViewModel` polls the provider every 2 seconds while the screen is
visible (`ProcessPolling.INTERVAL_MS`), and offers a manual refresh that calls
`refreshSnapshot()`. The screen renders cards for:

- Device
- Android
- Kernel (host and runtime)
- CPU, including per-core frequency and usage
- GPU
- Memory and swap, with Linux and Android memory as separate blocks
- Storage, battery, thermal, and network
- App/self footprint
- Capability matrix, with the reason a category is unavailable

`DeviceInfoMapper` is a pure JVM formatter. Every row carries a `known` flag;
unknown values render as an explicit dash rather than a zero. Unit symbols and
measurements remain universal, while surrounding labels resolve through
`DeviceInfoLabels` so language changes apply on the next refresh.

There is no separate performance dashboard route; the Device screen is the
single host-information destination. Workspace-level performance remains in
the workspace panel and Activity/Sessions surfaces.

---

## 12. Privacy and security

- Least exposure by default: guests receive the minimal key set; more is
  opt-in per workspace and validated at import.
- No location data: Wi-Fi SSID/BSSID are never collected.
- Restricted facts are reported as restricted, not disguised as zero or empty.
- Overlay files live inside the app-private workspace directory.
- The subsystem adds no manifest permissions, processes, or notification
  channels.

---

## 13. Limitations

- **Restricted `/proc` entries.** On modern Android, `/proc/stat`,
  `/proc/loadavg`, `/proc/uptime`, `/proc/version`, and `/proc/vmstat` are
  denied to app-UID processes. The overlays carry collected or synthetic
  values where the host cannot be read.
- **Other apps' processes are hidden.** `hidepid` and SELinux make a complete
  device process list unavailable.
- **Some hardware facts have no public API.** Battery design capacity,
  CPU/GPU/skin temperatures, and some GPU counters are permanently unknown on
  many devices.
- **No Wi-Fi identity.** SSID/BSSID are excluded by policy because they are
  location data.
- **Guest parity depends on the guest.** Overlay files and environment
  variables are provided, but a distribution's tools may read different
  sources or cache values at startup.

---

## 14. Related documentation

- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot `/proc` binds
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — `config.yaml` `linexa.host_info`
- [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md) — Activity/Sessions scopes
- [`features/TASK_MANAGER.md`](./TASK_MANAGER.md) — process sampling
