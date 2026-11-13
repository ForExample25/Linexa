# Troubleshooting

**Status:** Current — describes common Linexa issues, verification gaps, and on-device checks, verified against source where possible.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

---

## 1. Purpose

This document helps developers diagnose and fix common Linexa issues, distinguishing verified behaviors from unverified ones.

---

## 2. Scope

In scope:

- Build/test environment issues
- Workspace start failures
- Terminal/PTY issues
- Desktop/VNC issues
- File manager save issues
- Performance symptoms
- Security/permission symptoms

Out of scope:

- Build configuration: [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md)

---

## 3. Build/test environment

| Symptom | Likely cause | Fix |
|---|---|---|
| `UnsatisfiedLinkError: libm.so.6 not found` (KSP) | aarch64 Bionic JVM (Termux) — glibc-linked SQLite | Set `JAVA_TOOL_OPTIONS` to a Bionic-compatible SQLite native, or use x86_64 CI; see [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md) §4.7 |
| Release build fails: keystore/credentials missing | signing config reads `local.properties` | Provide `signing.*` properties; keystore at `keystore/linexa-release.jks` |
| Test suite blocked in Termux | same as first row | same fix |

---

## 4. Workspace start

| Symptom | Likely cause | Fix |
|---|---|---|
| `proot` exits 255 silently | unpatched loader/readlinkat regression (§1.55 static-musl) | ensure patched termux-lineage proot 5.1.107.89 + patches 0001–0004 are vendored |
| Stale tmux socket on startup | dead workspace dir carries `.tmux.sock` | startup sweep cleans it; `tmux -S <sock> kill-server` |
| `dpkg` preflight stalls | debconf interactive prompts | `DEBIAN_FRONTEND=noninteractive` layered in |
| Seccomp fallback not applied | probe marker not seen | one-shot probe on first terminal open sets `PROOT_NO_SECCOMP=1` |
| Workspace status `Broken` after import | `health_check.command` non-zero | check command output tail in diagnostic; archive may be missing shell |
| Can't launch on x86_64 emulator | no x86_64 `libproot.so` | arm64-v8a / armeabi-v7a only |

---

## 5. Terminal / PTY

| Symptom | Likely cause | Fix |
|---|---|---|
| One-shot exec reports "not a tty" | pipes stdio by design | expected for non-interactive paths |
| Initial size 80×24 until first layout | vendored Termux terminal-emulator default (wording updated 2026-11-11; behavior UNVERIFIED) | no caller-driven initial resize |
| `runBlocking` on Main thread | justified (looper capture) | do not call PTY factory off-Main |

---

## 6. Desktop / VNC

| Symptom | Likely cause | Fix |
|---|---|---|
| Frame updates lag under sustained load | dirty-region acknowledge degrades to full-frame uploads | known bottleneck (`VncDirtyRegion.kt`) |
| Cursor lag equals frame latency | no cursor pseudo-encoding | by design |
| Letterbox after landscape | geometry measured before fullscreen | fixed geometry per session |
| Desktop won't start unauthenticated | `vncpasswd` provisioning failed | fail-closed; fix provisioning or clear password |
| Keyboard/mouse input competes with frame delivery | pointer writes on Main scope | known bottleneck |

VNC regressions are tracked in the project's issue history; reproduce with the device lane in [`TESTING_STRATEGY.md`](./TESTING_STRATEGY.md) before changing the transport.

---

## 7. File manager

| Symptom | Likely cause | Fix |
|---|---|---|
| Save appears to succeed but file unchanged | write failure reported after process exit | verify exact bytes written; guest heredoc exit code decides Result |
| Writes > ~128 KiB fail with E2BIG | single-argv heredoc via `sh -c` | guest-write size ceiling; loud failure, no silent truncation |
| Empty file saved as 1 byte `\n` | fixed — empty heredoc body now truly empty | current code writes empty correctly |

---

## 8. Performance

| Symptom | Likely cause | Fix |
|---|---|---|
| Device CPU reads null (SELinux denied) | `/proc/stat` unreadable on Android 8+ | UI reports "Not exposed"; do not substitute |
| RAM reads approximate | `VmRSS` fallback flagged | `Pss` preferred; RSS marked approximate |
| VNC frame rate coupled to Main thread | receive loop on Main dispatcher | known bottleneck — no timing measured |

---

## 9. Security / permissions

| Symptom | Likely cause | Fix |
|---|---|---|
| VNC password reported as plaintext | stale manifest/backup XML comments | Persisted form is AES-GCM/Keystore (`WorkspaceSecretAttributes`); legacy rows migrate at startup. Comments still need a source-only correction |
| Backup exclusion only mitigates cloud leakage | `allowBackup="true"` scoped | at-rest DB secret is encrypted; guest passwd file and explicit exports remain exposure paths |
| Loopback-only conditional on Xvnc ≥ 1.11 | `-localhost` flag omitted on old Xvnc | upgrade guest Xvnc or accept exposure |

---

## 10. On-device checklist

Core flows still need on-device verification; the symptom/fix mappings above are code-derived unless a device test says otherwise. See [`TESTING_STRATEGY.md`](./TESTING_STRATEGY.md).

---

## 11. Related documentation

- [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md) — build commands, toolchain
- [`features/WORKSPACE_AND_SESSIONS.md`](../features/WORKSPACE_AND_SESSIONS.md) — session lifecycle
- [`features/TERMINAL_AND_LINUX_RUNTIME.md`](../features/TERMINAL_AND_LINUX_RUNTIME.md) — PTY
- [`features/DESKTOP_AND_VNC.md`](../features/DESKTOP_AND_VNC.md) — desktop/VNC
- [`features/FILE_MANAGER.md`](../features/FILE_MANAGER.md) — save pipeline
- [`engineering/TESTING_STRATEGY.md`](./TESTING_STRATEGY.md) — verification status
