# Architectural Decision Register

**Status:** Current — Keep / Fix / Refactor / Redesign / Rebuild / Investigate register for every subsystem.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Date:** 2026-11-07

---

## 1. Purpose

This register records architectural decisions for Linexa's subsystems. Each entry states the decision, the subsystem, and the rationale. Decisions marked "historical" describe the reasoning behind shipped behavior; they are not proposals to change it.

Entries are not implementation specifications; they explain *why* a particular approach was chosen.

---

## 2. Decision register

| ID | Subsystem | Decision | Rationale | Status |
|---|---|---|---|---|
| D-01 | Container runtime | PRoot only (no chroot/Droidspaces) | Root-dependent backends add security surface and test matrix; PRoot seam makes others possible later | Keep |
| D-02 | PTY terminal | Vendored Apache-2.0 Termux terminal-emulator/view (`5b657c6`) | Replaced GPL-3.0 `terminal-lib` JitPack; real PTY via `forkpty`-equivalent native code | Keep |
| D-03 | DI | Hilt via KSP | Kapt removed after documented Kotlin 2.1.20 crash; enables fakes (`FakeProot`, `Recording*`) | Keep |
| D-04 | Persistence | Room, single entity `WorkspaceMetadata`, version 1, exported schema | Multi-workspace product needs typed, versioned persistence; schema exported for migration policy | Keep (migration policy pending — D-10) |
| D-05 | Navigation | Navigation Compose single-activity host, 3 tabs, terminal as overlay | Truthful back-stack; no "jump home" shortcuts; tablet sidebar ≥840dp | Keep |
| D-06 | Desktop transport | Own RFB/VNC client (ZRLE) + WebView mode; no forked X server | Self-containment — no NDK X server, no GPL fork (Termux:X11 `lorie`) | Keep |
| D-07 | Process attribution | ppid walk (`ProcessTree.descendantsOf`) | `CONFIG_PROC_CHILDREN` absent on target kernels; correct for descendants | Keep (orphan daemons unverified — D-14) |
| D-08 | Wake lock | Opt-in `PARTIAL_WAKE_LOCK`, 60-min ceiling | Safer than untimed lifetime lock; never taken automatically | Keep |
| D-09 | Error handling | Fail-open, sealed result/state types, total-function parsers | Failures surface in UI states; no silent truncation | Keep |
| D-10 | Room migrations | None registered yet | Migration policy planned; first schema bump without migration crashes existing installs | Fix (planned) |
| D-11 | VNC password | AES-GCM/AndroidKeyStore envelope in Room `attributes`; legacy plaintext migrated at startup; backup excluded | Implemented; fail-closed unreadable marker; in-memory/guest-file/export exposure remains | **Keep** |
| D-12 | Phantom-process limit | Detection + guidance, copy-only, never executed | Android 12+ may kill >32 child processes; no prevention yet | Keep (prevention open) |
| D-13 | Collect-as-state | `collectAsState()` 83×, `collectAsStateWithLifecycle` 1× (comment) | Documented crash workaround on pinned toolchain; migration requires deliberate toolchain bump | Keep (deferred) |
| D-14 | Orphan daemon attribution | Investigate marker-based attribution | ppid walk may miss reparented daemons (ppid=1); needs device test with `setsid`/double-fork | Investigate |
| D-15 | Dimension vocabulary | `Corners` and `Spacing` both in `Dimens.kt`; only `Spacing` is adopted | `Corners`/`ContentPadding`/`Elevation`/`IconSize` have 0 external uses; two token layers coexist and need one decision | Fix (planned) |
| D-16 | Native binaries | Vendored jniLibs arm64-v8a + armeabi-v7a only; `lib__*__.so` naming | AGP auto-packages only `lib*.so`; `BootstrapManager` re-maps at runtime | Keep (no x86_64 support) |
| D-17 | Proot version | termux-lineage 5.1.107.89 + Linexa patches 0001–0004 | static-musl v5.3.0 invalidated (SIGSEGV/assert-crash); rebuilt with patches | Keep |
| D-18 | `collectAsStateWithLifecycle` migration | Deferred | Requires approved bump of Compose BOM/lifecycle/navigation together | Keep (conditional) |
| D-19 | App store | Not adopted | High complexity; distro-specific catalogs; Flathub without bubblewrap is a custom hack | Rejected |
| D-20 | Per-app Android windows | Not adopted | NDK-built X server + GPL fork; conflicts with Linexa-native choice | Rejected |
| D-21 | GPU variants (Turnip/Zink) | Not adopted | Adreno-only; Linexa verified Mali via VirGL; kgsl node absent | Rejected |
| D-22 | chroot/Droidspaces backends | Not adopted | Root-dependent; new security surface; larger test matrix | Rejected |
| D-23 | USB/Bluetooth HID/Cast | Not adopted | Out of scope; new permissions + hardware testing | Rejected |
| D-24 | Guest `sudo` / Root Shell | Mailbox → app `RootExecBridge` → separate `-0` PRoot one-shot; interactive root is a real `-0` PTY (Apps → Root shell) | PRoot cannot honour setuid from an emulated user identity (`setuid(0)` → `EPERM`); running the command as the caller was a silent lie that broke dpkg. A separate root-emulated process is the genuine elevation the runtime already owns. No host privilege, no fake setuid, no session-wide identity switch. | Keep |

---

## 3. Decision lifecycle

- **Keep** — decision stands; no change planned.
- **Fix** — change planned in a future release; the decision entry records the intended direction.
- **Refactor** — navigation/internal-only change, no behavior change.
- **Redesign** — significant behavioral change; not yet scheduled.
- **Rebuild** — replace subsystem; not scheduled.
- **Investigate** — needs evidence (usually device testing) before deciding.

---

## 4. Related documentation

- [`engineering/CODE_CONVENTIONS.md`](../engineering/CODE_CONVENTIONS.md) — layering and naming
- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — ArchUnit boundary
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
