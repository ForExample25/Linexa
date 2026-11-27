# Troubleshooting

This document lists common build, runtime, terminal, desktop, file manager,
and security symptoms with their likely causes and remedies. It describes
current behavior.

---

## 1. Build and test environment

| Symptom | Likely cause | Remedy |
|---|---|---|
| `UnsatisfiedLinkError: libm.so.6 not found` during KSP | aarch64 Bionic JVM (Termux) using a glibc-linked SQLite native | Set `JAVA_TOOL_OPTIONS` to a Bionic-compatible SQLite native, or build on a glibc host/CI. See [`BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md) section 9 |
| Release build fails with a signing error | Missing `signing.*` properties or keystore | Provide all four signing properties in `local.properties`; the build fails rather than producing an unsigned release |
| `testDebugUnitTest` is red on aarch64 only for four Robolectric classes | No Conscrypt native for `linux-aarch_64` | Expected on aarch64; the default task excludes them. Run on x86_64 or CI for those classes |
| Gradle cannot resolve dependencies | Offline mode or missing network access | Build online on the first run; the wrapper and repositories are committed |
| `lintDebug` fails with `[LintError] Unexpected failure during lint analysis` | AGP 8.5's lint cannot read Kotlin 2.1 metadata; the Compose StateFlow detector crashes | The detector is disabled in `app/build.gradle.kts`; re-enable it with the AGP/Kotlin upgrade (F-032) |

---

## 2. Workspace start

| Symptom | Likely cause | Remedy |
|---|---|---|
| `proot` exits 255 with no guest output | Wrong or unpatched PRoot/loader combination | Use the vendored PRoot and loader from `jniLibs`; do not substitute a system binary. See `third_party/proot/BUILD-NOTES.md` |
| Stale tmux socket after a crash | Dead workspace directory still has `.tmux.sock` | The startup sweep removes it; `tmux -S <sock> kill-server` clears a live stale server |
| `dpkg` preflight stalls | Debconf prompts in a noninteractive one-shot | Ensure `DEBIAN_FRONTEND=noninteractive` is present (the runtime adds it) |
| Seccomp-related `EEXIST` or lock errors under fake root | PRoot seccomp replay when the app is stopped mid-syscall | Fake-root launches set `PROOT_NO_SECCOMP=1`; normal terminals keep acceleration |
| Workspace shows `Broken` after import | `health_check.command` exited non-zero | Inspect the diagnostic tail; the archive may lack the declared shell or session binary |
| Workspace cannot launch on an x86_64 emulator | No x86_64 `libproot.so` is bundled | Use an arm64 device or arm64 emulator |
| RootFS extraction fails for a valid archive | Missing owner-write on `/` or `/proc` in the archive | The runtime adds owner-write where needed: the `/` repair is permanent, while `/proc` write windows are restored; check the install diagnostic for a permission error |
| App killed while many guest processes run | Android phantom-process limit | Reduce concurrent terminals/daemons; Settings → Access shows the platform monitor state but Linexa does not prevent the limit |

---

## 3. Terminal and PTY

| Symptom | Likely cause | Remedy |
|---|---|---|
| Terminal opens at 80×24 until the first layout | The terminal view has no initial size from the caller | Resize after the first layout; this is a display default, not a PTY failure |
| One-shot exec reports "not a tty" | One-shot commands use pipes, not a PTY | Expected for non-interactive paths; use a terminal for interactive programs |
| `TerminalSession` creation throws a Looper error | PTY creation ran off a Looper thread | Keep PTY creation on the main Looper through `SessionCreationDispatcher`; do not call the factory from an IO thread |
| Terminal session exits immediately | Guest shell/session binary missing or the workspace is broken | Check the workspace diagnostic and the declared session command |

---

## 4. Desktop and VNC

| Symptom | Likely cause | Remedy |
|---|---|---|
| Desktop reports `X11Failed` | Termux:X11 app/helper missing, socket timeout, or Xvnc start failure | Read the failure text; the screen offers the VNC backend as an explicit fallback |
| Desktop will not start after restoring a backup | VNC password ciphertext cannot be decrypted | Set a new VNC password in Features → Desktop; the stored value is intentionally kept rather than discarded |
| Desktop refuses to start unauthenticated | `vncpasswd` provisioning failed while a password is configured | Fix the guest/Xvnc installation or clear the password; the app fails closed by design |
| VNC is reachable beyond the device | Guest Xvnc is older than 1.11 and does not support `-localhost` | Configure a VNC password, upgrade Xvnc, or accept the exposure knowingly |
| Cursor lags behind the pointer | No cursor pseudo-encoding; cursor updates are part of the framebuffer | Use the relative-pointer input mode where appropriate |
| Frame rate is low on a high-resolution display | Raw decode and upload cost at high resolution | Select the 720p or 900p preset, or enable ZRLE for the workspace |
| Letterboxing after rotation | Framebuffer geometry was chosen before the final window size | Restart the desktop after the device settles in landscape, or use a matching preset |
| Pointer or keyboard input seems delayed | Input writes and frame delivery share the client scope | Check `VncRenderStats`; input is on IO, but a saturated decode/upload path still delays display |
| WebView mode shows a blank page | Guest web server not listening on the expected loopback port | Check the guest command and the daemon log; WebView is pinned to the origin it opened |
| WebView mode cannot load `http://` | Network security config or a non-loopback origin | Cleartext is allowed only for `127.0.0.1`/`localhost`; use HTTPS elsewhere |

---

## 5. File manager

| Symptom | Likely cause | Remedy |
|---|---|---|
| Save reports failure and the file is unchanged | Guest heredoc command exited non-zero | Read the guest message in the UI; check free space and permissions |
| Writes over roughly 128 KiB fail with `E2BIG` | One argv element carries the heredoc; the kernel limits a single argument | Use a smaller file, split the content, or edit inside the guest with a native tool |
| Binary file opens as garbled text | Editability is based on file type/name, not content sniffing | Use the read-only viewer or a guest tool; binary content is not editable |
| Import rejects a file | Import is text-only, capped at 512 KiB, and rejects NUL bytes | Use the SAF provider or a guest copy for binary/large files |
| SAF create/delete/rename is refused | Workspace runtime is Starting, Running, or Stopping | Stop the workspace, then retry the mutation |
| UI shows a dash or empty list after a runtime error | The guest command failed and returned a failure result | Check the workspace runtime; the list is not silently replaced with an empty success |

---

## 6. Performance and metrics

| Symptom | Likely cause | Remedy |
|---|---|---|
| Device CPU shows "not exposed" | `/proc/stat` is restricted by Android/SELinux | No substitute metric is invented; use app/workspace CPU where available |
| Workspace RAM is marked approximate | PSS was unreadable and `VmRSS` was used | Expected fallback; check `/proc` access |
| Workspace CPU looks lower than expected | Process CPU excludes reaped-child time and is normalized to device capacity | Compare with the process rows in Task Manager; both use the same convention |
| GPU values are unknown | Vendor sysfs nodes are absent or unreadable | Adreno may expose busy/clock; other GPUs report unknown rather than zero |
| Overlay-backed guest counters look stale | `/proc` overlays refresh every 2 s while a workspace runs | Guest tools that sample faster than that see repeated values; use the host-info env/feed for on-demand facts |
| Battery use is high with a workspace running | Guest processes, Xvnc, and daemons continue running | Stop the workspace or unused daemons; enable the opt-in wake lock only when needed |

---

## 7. Security and permissions

| Symptom | Likely cause | Remedy |
|---|---|---|
| A database inspection shows the VNC password as ciphertext | Password is stored as an AES-GCM envelope | Expected; the repository decrypts on read. Do not add a plaintext fallback |
| A restored install cannot start the desktop | Keystore key is device-bound and cannot decrypt the restored ciphertext | Set the VNC password again; the unreadable marker is intentional |
| Shared storage is not visible | No SAF tree grant | Grant a folder through the file manager's Android storage entry |
| Backup does not restore workspaces or settings | Backup rules exclude the database, workspaces, logs, bootstrap, and settings | Expected; use a RootFS export for workspace content |
| Guest root can write files the user cannot | Root-emulated processes run with `-0`; files created by them may be owned by the app but restricted by mode | Use `sudo`/Root Shell for those paths, or fix ownership inside the guest |

---

## 8. Root shell and sudo

| Symptom | Likely cause | Remedy |
|---|---|---|
| `sudo` exits 125 immediately | Bridge is not running or the marker is missing | Open a terminal in the workspace to start the bridge, or use Apps → Root shell |
| `sudo` exits 125 after ~30 s | App process died or the bridge heartbeat stopped | Restart the workspace; unclaimed requests are swept |
| `sudo` rejects an option | Option is unsupported or requests another user | Use the documented options; for an interactive shell use Root shell |
| `sudo apt install` hangs on a prompt | The bridge has no stdin/TTY forwarding | Use `-y` and noninteractive package operations, or run it in Root shell |
| pacman fails with a Landlock/sandbox error | pacman 7 enables a sandbox PRoot does not implement | Configure `DownloadUser = root`, `DisableSandboxFilesystem`, `DisableSandboxSyscalls` in the guest's pacman config |
| `sudo` cannot find a file that root can see | Hardlink family resolved to an absolute host path | Run the command through Root shell or repair the rootfs |

---

## 9. Diagnostics

- Workspace diagnostics show install phases, runtime trace events, daemon
  logs, and failure reasons.
- The Device & System Information screen shows host capabilities and why a
  category is unavailable.
- `VncRenderStats` exposes frame and upload counters for desktop work.
- Android `logcat` is still the source of truth for crashes, ANRs, and service
  lifecycle problems.

For device-test steps and the safety checklist, see
[`TESTING_STRATEGY.md`](./TESTING_STRATEGY.md) section 6.

---

## 10. Related documentation

- [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md) — toolchain and build commands
- [`features/WORKSPACE_AND_SESSIONS.md`](../features/WORKSPACE_AND_SESSIONS.md) — session lifecycle
- [`features/TERMINAL_AND_LINUX_RUNTIME.md`](../features/TERMINAL_AND_LINUX_RUNTIME.md) — PTY and runtime
- [`features/DESKTOP_AND_VNC.md`](../features/DESKTOP_AND_VNC.md) — desktop modes
- [`features/FILE_MANAGER.md`](../features/FILE_MANAGER.md) — save pipeline
- [`features/ROOT_SHELL_AND_SUDO.md`](../features/ROOT_SHELL_AND_SUDO.md) — sudo bridge
