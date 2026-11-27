# Root Shell and sudo

**Source locations:**
`app/src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt`,
`app/src/main/java/com/fem/linexa/service/runtime/container/root/RootExecProtocol.kt`,
`app/src/main/java/com/fem/linexa/service/runtime/container/root/RootExecBridge.kt`,
`app/src/main/java/com/fem/linexa/service/installer/GuestRootfsInitializer.kt`,
`app/src/main/java/com/fem/linexa/service/runtime/RuntimeBinder.kt`,
`app/src/main/java/com/fem/linexa/ui/screens/workspace/AppLauncherViewModel.kt`,
`app/src/main/java/com/fem/linexa/ui/screens/workspace/AppLauncherSegment.kt`

---

## 1. Purpose

Linexa offers two ways to run commands with the guest's emulated root
identity:

1. **Root Shell** — an interactive PTY launched by PRoot with `-0`.
2. **`sudo`** — a non-interactive command typed in a normal `linexa` terminal,
   executed by the app in a separate `-0` PRoot process.

This document explains the identity model, both surfaces, the mailbox
protocol behind `sudo`, its options and failure modes, and the limits of
root emulation.

---

## 2. Identity model

| Property | Normal session | Root Shell | sudo bridge |
|---|---|---|---|
| Kernel UID | Android app UID | Android app UID | Android app UID |
| PRoot identity flag | `--change-id=<uid>:<gid>` | `-0` | `-0` |
| `id -u` / `whoami` | `1000` / `linexa` | `0` / `root` | `0` / `root` |
| `HOME` | `/home/<user>` | account home | `/home/<user>` (one-shot default) |
| Filesystem owner on Android | app UID | app UID | app UID |
| Host privilege | none | none | none |

Root emulation means the process reports UID 0 and can write anywhere the app
itself can write. It does not grant Android root, SELinux exemptions, or
access to another app's data. No setuid bit or host privilege change is used.

`GuestIdentity.root(...)` produces `-0`; a normal account produces
`--change-id=<uid>:<gid>`. `GuestRootfsInitializer.ensureGuestUser`
guarantees the `linexa` account, so `id`, `whoami`, `$USER`, `$HOME`, and the
shell prompt all resolve through `/etc/passwd` to the same account.

---

## 3. Root Shell

### 3.1 Entry point

The Apps segment renders a full-width **Root shell** button beneath the
Packages/Files toolbar.

```
AppLauncherSegment (Root shell button)
  → AppLauncherViewModel.launchRootShell(title)
    → RuntimeBinder.openRootTerminal(workspaceId)
      → WorkspaceTerminalManager.openTerminal(asRoot = true)
        → PRootContainerRuntime.openTerminal(asRoot = true)
          → PRootLauncher.buildLaunchSpec(identity = GuestIdentity.root(...))
            → proot -0 …
```

The window is registered with a `TerminalWindowIdentity`
(`appId = linexa:root-shell`) so the window overlay titles it "Root shell"
from the first frame. A launch failure is reported inline under the button; a
launch that produces no PTY closes the provisional session and reports that
the terminal did not start.

### 3.2 Relationship to sudo

Root Shell is interactive: it has a real PTY, stdin, job control, and a shell
prompt. Inside a root shell the helper sees `id -u = 0` and executes commands
directly; the mailbox is not used. `sudo -i` and `sudo -s` are refused from a
user session with guidance to use Root Shell, because the mailbox has no stdin
forwarding.

---

## 4. The sudo bridge

### 4.1 Installed files

`GuestRootfsInitializer.ensureGuestPrivilegeBridge` writes two idempotent
scripts into `/usr/local/bin` with explicit mode `0755`:

| File | Role |
|---|---|
| `sudo` | Validates that the helper exists and `exec`s it. Shadows `/usr/bin/sudo` because `/usr/local/bin` precedes `/usr/bin` in the guest `PATH`. |
| `linexa-root-exec` | Option parsing, command quoting, mailbox client, output streaming, and exit-status translation. |

Files are compared byte-for-byte before writing, written through a temporary
file and renamed, and never follow a symlink. Deleting them restores the
distribution's own `sudo`.

### 4.2 Why not a setuid binary

A setuid-root `sudo` requires the kernel to honor the setuid bit. Under PRoot
the kernel sees the app's UID for every tracee, and PRoot's fake-ID emulation
rejects `setuid(0)` from an emulated non-root identity with `EPERM`. A setuid
binary would either do nothing or lie about the result. The bridge uses the
mechanism the runtime already owns: a separate `-0` PRoot process.

### 4.3 Mailbox layout

The app owns a liveness directory inside the rootfs:

```
<rootfs>/run/linexa-root/
  bridge       first line: "linexa-root-bridge v1"
  heartbeat    timestamp refreshed every 2 s by the app poller
```

A request is a directory under the guest's already-1777 `/tmp` (host path
`<rootfs>/tmp`), so no new writable directory or permission relaxation is
needed:

```
/tmp/.linexa-root-req.<random>/
  token     guest → host: the current bridge session token from the marker
  cmd       guest → host: exact command string (UTF-8, no NUL)
  claimed   host → guest: created atomically before execution
  out       host → guest: append-only streamed stdout+stderr
  status    host → guest: final exit code, written atomically last
```

### 4.4 Request sequence

1. The helper verifies the bridge marker and protocol banner, then reads the
   `token=` line the bridge generated for this session.
2. It creates the request directory with `mktemp -d`, `umask 077`.
3. It writes `token`, then `cmd`, then waits up to 15 s for `claimed`.
4. `RootExecBridge` polls `<rootfs>/tmp` at 500 ms in steady state (100 ms for
   a short burst after a request was served; F-025).
5. It rejects symlinked request directories, commands larger than 64 KiB, and
   requests whose token does not match the current session.
6. It claims the request with an atomic `createNewFile()` before reading the
   command or executing anything, so a bridge restart or a second bridge
   cannot replay a request that may already have mutated the guest.
7. It runs the command through `PRootContainerRuntime.execInternal(...)`, the
   same root-emulated one-shot path used by the Package Manager, with the
   normal operation timeout, `DEBIAN_FRONTEND=noninteractive`, dpkg health
   recovery, and the per-workspace package-operation queue.
8. It appends every output line to `out`, flushes, then atomically renames a
   temporary `status` file into place.
9. The helper streams `out` with `tail -c +N`, waits for `status`, drains the
   final bytes, and exits with the command's code.

`status` is authoritative. If it is absent, the command is still running or
the app died; the heartbeat distinguishes those cases.

### 4.4.1 Threat model

The guest and the app run as the **same Android uid**, and PRoot does not
create a kernel privilege boundary. The mailbox is therefore not an
authorization boundary against a hostile process inside the workspace: such a
process can read the marker token, the helper script and the app's own files.
What the mailbox does guarantee is:

- only the app executes the command, in a separate `-0` PRoot process;
- a request is claimed atomically before execution (no replay);
- the request directory must be a real child of `<rootfs>/tmp` (no symlink
  escape), the command is bounded, and a token from the current session is
  required (no stale-session request);
- the control directory is 0700 and the marker/heartbeat are 0600, keeping the
  mailbox out of group/other reach.

The honest boundary is the app sandbox plus the user's decision to enable the
workspace. A hard boundary would need a different uid or a kernel primitive
that PRoot cannot provide; see ADR-25 in
[`../decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md).

### 4.5 Quoting

`linexa-root-exec` quotes each argv element with the POSIX single-quote rule.
Safe shell words (`[A-Za-z0-9_./:=+@%,^-]+`) stay bare; everything else is
wrapped in `'…'` with embedded quotes escaped as `'\''`.

The safe-word exception is required because the runtime classifies package
operations by whitespace tokens. `sudo apt install -y htop` must produce
`apt install -y htop` so `PackageOperationClassifier` routes it through the
package queue and dpkg recovery. The helper also prefixes the caller's `pwd`
(`cd -- <dir> && …`) unless `-D <dir>` was supplied.

### 4.6 Heartbeat and failure handling

The app rewrites `heartbeat` every 2 s while the bridge is alive. A guest that
has been claimed but sees no heartbeat change for 30 s prints:

```
linexa: the root bridge stopped responding (the Linexa app may have been restarted)
linexa: open Apps → Root shell to run privileged commands, or restart the workspace and retry
```

and exits `125`. The marker check covers a workspace closed while a request
was in flight. The 15 s claim timeout covers a stopped or missing bridge.

Exit codes:

| Code | Meaning |
|---|---|
| `0–255` | The root-emulated command's own exit code |
| `124` | App-side operation timeout (`timeout(1)` convention) |
| `125` | Bridge, protocol, or unavailable failure (`sudo` convention) |

### 4.7 Supported options

| Option | Behavior |
|---|---|
| `-E`, `--preserve-env` | Accepted no-op; the environment is already the user's. |
| `-u root`, `-g root` | Accepted; the command runs root-emulated. |
| `-u <other>`, `-g <other>` | Rejected; PRoot only offers the emulated root context. |
| `-i`, `--login`, `-s`, `--shell` | Rejected from a user session with guidance to Root Shell. Already-root sessions open a login shell. |
| `-k`, `-K`, `-v` | Accepted no-ops; there is no timestamp cache. |
| `-D`, `--chdir` | Changes the command's working directory. |
| `-p`, `-C`, `-T`, `-r`, `-t` | Arguments consumed; behavior not emulated. |
| `-l`, `-h`, `-V` | Local help/list/version output. |
| Unknown option | Explicit error plus the Root Shell alternative. |
| `--` | Ends option parsing. |

`sudo` with no arguments in a normal session prints usage and exits 1; in a
Root Shell it opens the login shell.

---

## 5. Rootfs permission envelope

RootFS archives do not all use the same directory modes. Arch Linux ARM
restores `/` and `/proc` as `0555`; Fedora restores `/` and `/usr/bin` as
`0555`. PRoot's fake UID 0 does not change the kernel's DAC check: every
guest process is still the Android app UID at the syscall boundary, so a
`0555` directory owned by that UID rejects a new entry.

`GuestRootfsInitializer` and `HostInfoRuntimeBridge` share
`RootfsWriteWindow` (`core/filesystem/RootfsWriteWindow.kt`):

| Location | Repair | Lifetime |
|---|---|---|
| RootFS `/` | Add owner `rwx` when the archive restored it without owner-write | Permanent, logged once per repair; root-emulated processes need a root-writable `/` |
| `/proc` snapshots | Add owner-write around overlay writes | Restored in `finally`; restore failure is a hard failure |
| `/proc` host-info overlays | Same window around `.meminfo` and `.net/*` writes | Restored in `finally`; shares `RootfsWriteLock` with the installer |
| Symlinked `/proc` | Refused; never followed, widened, or written through | Skipped |

The window is narrow: it touches only the named directory, never recurses,
never adds group or other write bits, and refuses a symbolic link. It is not
a `chmod 777` and grants nothing beyond the app UID's existing access.

---

## 6. Fake-root seccomp policy

PRoot's seccomp acceleration can run an intercepted
`open(O_CREAT|O_EXCL)` twice if the app process is stopped between
interception and resume. The first run creates the file; the guest receives
`EEXIST` from the second. `mktemp` retries, but pacman's `db.lck` probe treats
the false `EEXIST` as a live lock.

Every fake-root (`-0`) launch — sudo one-shots, Root Shell, the in-app package
path, and root daemons — carries `PROOT_NO_SECCOMP=1`. Normal
`--change-id=1000:1000` terminals keep PRoot's acceleration. The one-time
seccomp probe strips the variable before probing, so it still tests the
kernel rather than its own workaround. `PROOT_NO_SECCOMP` is in the guest
environment filter and is not visible to guest programs.

This is a performance trade-off confined to privileged operations and is
distro-neutral because the corruption is in PRoot's fake-root interception.

---

## 7. Arch pacman sandbox

pacman 7 enables an `alpm`/Landlock sandbox and switches to an `alpm` user.
PRoot does not implement a Landlock domain, so the stock Arch configuration
fails before it reaches a repository:

```
error: restricting filesystem access failed because Landlock is not supported by the kernel!
error: switching to sandbox user 'alpm' failed!
```

The supported workaround is the distribution's own configuration surface, for
example:

```ini
DownloadUser = root
DisableSandboxFilesystem
DisableSandboxSyscalls
```

Linexa does not rewrite pacman's stock configuration and does not special-case
pacman in the shared bridge. The opt-out belongs to the workspace's
distribution configuration, as it would on a real Arch system whose kernel
lacks Landlock.

---

## 8. Lifecycle

| Event | Bridge action |
|---|---|
| First terminal (normal or Root Shell) opens | `start()`: create marker and heartbeat, sweep abandoned requests, start polling. Idempotent. |
| Last terminal of the workspace closes | `stop()`: stop accepting requests, delete marker and heartbeat. Claimed handlers keep running and still write `status`. |
| Workspace stops | Same stop, before PTYs are destroyed. |
| App process dies | Marker remains stale but heartbeat stops; a waiting guest fails after 30 s with guidance. |
| Workspace rootfs path changes | The stale bridge is stopped and recreated against the new path. |
| App restart | A new bridge is created on the next terminal open; unclaimed stale requests are swept after 1 h, claimed abandoned ones after 24 h. |

`RootExecBridge` is Android-free (`java.io`/`java.nio` and coroutines), so its
claim, stream, and status state machine is covered by JVM tests.

---

## 9. Security properties and limits

The bridge does **not**:

- Change the caller's UID, environment, working directory, or process
  identity.
- Bypass Android's UID/SELinux model or grant host root.
- Read commands outside the workspace's own `/tmp`. Symlinked request
  directories are rejected, and `cmd` must be a regular, non-symlink file.
- Add world-writable directories. Guest `/tmp` and `/run` are already
  `1777`/workspace-owned.
- Accept NUL bytes or commands over 64 KiB.
- Fall back to executing a command as the caller when the bridge is
  unavailable.

Observable limits:

| Limit | Consequence | Alternative |
|---|---|---|
| No stdin/TTY forwarding | Interactive programs (`passwd`, editors, debconf prompts) cannot be answered; package installs need `-y`. | Root Shell; use noninteractive package operations |
| No Ctrl-C propagation | The helper can be interrupted, but the root-emulated command may continue until its timeout. | Root Shell, where job control is real |
| Runtime hardlink families | A hardlink family created after extraction can get an absolute host-path symlink target from PRoot. Emulated root resolves it; UID 1000 gets `ENOENT`. | `sudo <command>` or Root Shell until the rootfs is repaired |
| No sudo timestamp or password cache | `sudo -v`/`-k` are no-ops; every command runs directly. | — |
| Root is emulated | A workspace "root" has exactly the app's filesystem access. | — |
| Workspace-level trust | Any process that can execute `/usr/local/bin/sudo` can run root-emulated commands. | — |

---

## 10. Related documentation

- [`features/TERMINAL_AND_LINUX_RUNTIME.md`](./TERMINAL_AND_LINUX_RUNTIME.md) — PRoot launch paths, PTY, exec identities
- [`features/SECURITY_AND_PERMISSIONS.md`](./SECURITY_AND_PERMISSIONS.md) — security boundaries and the same-UID model
- [`decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md) — root-shell decision record
