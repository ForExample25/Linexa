# Root Shell and sudo

**Status:** Implemented — PRoot root-emulated execution bridge and Apps → Root
shell entry point. Device verification across the full supported matrix is
still incomplete; see [`../engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

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

Linexa has two genuinely different ways to obtain root-emulated execution in a
workspace:

1. **Root Shell** — an interactive terminal whose process really is launched by
   PRoot with `-0` (emulated root).
2. **`sudo`** — a non-interactive command typed in a normal `linexa` terminal
   that must run in a *separate* root-emulated PRoot process.

The second one is the hard case. A PRoot guest has no kernel privilege
transition: every process runs under the Android app's real uid, and PRoot's
fake-id layer answers `setuid(0)` from an emulated non-root identity with
`EPERM`. A real `/usr/bin/sudo` therefore refuses to start in a user session
(`effective uid is not 0`) no matter how `/etc/sudoers` is written. The old
Linexa bridge worked around that by running the command in the caller's
identity and printing a warning — which is exactly why
`sudo apt install -y htop` failed with
`dpkg: error: requested operation requires superuser privilege`.

This document describes the replacement: a mailbox protocol through which the
guest `sudo` helper asks the app to run one non-interactive command in the
same root-emulated one-shot context used by Root Shell, the Package Manager and
system repair.

## 2. Identity model (what is real and what is emulated)

| Property | Normal session | Root Shell | Bridge command |
|---|---|---|---|
| Kernel uid | Android app uid | Android app uid | Android app uid |
| PRoot identity flag | `--change-id=<uid>:<gid>` | `-0` | `-0` |
| `id -u` / `whoami` | `1000` / `linexa` | `0` / `root` | `0` / `root` |
| `HOME` | `/home/<user>` | account home | `/home/<user>` (one-shot default) |
| Filesystem owner on Android | app uid | app uid | app uid |
| Host privilege | none | none | none |

The only honest claim is the emulated one: a root-emulated process reports uid
0 and can write anywhere the app itself can write; it does **not** gain Android
root, SELinux exemptions, or another app's data. No setuid bit, no fake sudo
timestamp, and no host privilege change is used anywhere in this feature.

`GuestIdentity.root(...)` produces `-0`; a normal account produces
`--change-id=<uid>:<gid>`. The account itself (`linexa`) is guaranteed by
`GuestRootfsInitializer.ensureGuestUser`, so `id`, `whoami`, `$USER`, `$HOME`
and the shell prompt all resolve through `/etc/passwd` to the same account.

## 3. Root Shell

### 3.1 Entry point

The Apps segment renders a full-width **Root shell** button directly beneath
the Packages / Files toolbar. It is separate from the metadata `root-shell`
card because that card is no longer part of the rendered Apps grid.

Flow:

```
AppLauncherSegment (Root shell button)
  → AppLauncherViewModel.launchRootShell(title)
    → RuntimeBinder.openRootTerminal(workspaceId)
      → WorkspaceTerminalManager.openTerminal(asRoot = true)
        → PRootContainerRuntime.openTerminal(asRoot = true)
          → PRootLauncher.buildLaunchSpec(identity = GuestIdentity.root(...))
            → proot -0 …
```

The window is registered with a `TerminalWindowIdentity` (`appId =
linexa:root-shell`, terminal icon) so the desktop/window overlay titles it
“Root shell” from the first frame.

Failure is reported inline under the button (no silent log-only failure). A
launch that cannot produce a pty closes the provisional session and reports
“Terminal session did not start”.

### 3.2 Relationship to `sudo`

Root Shell is the interactive, cancellable surface: it has a real PTY, stdin,
job control and a shell prompt. In a Root Shell the helper detects
`id -u = 0` and executes the command directly; the mailbox is not involved.
`sudo -i` / `sudo -s` are refused from a user session with guidance to use
Root Shell, because the mailbox has no stdin forwarding.

## 4. The guest `sudo` bridge

### 4.1 Installed files

`GuestRootfsInitializer.ensureGuestPrivilegeBridge` writes two idempotent
scripts into `/usr/local/bin` with explicit `0755`:

| File | Role |
|---|---|
| `sudo` | Thin wrapper that validates the helper exists and `exec`s it. Shadows `/usr/bin/sudo` because `/usr/local/bin` precedes `/usr/bin` in the guest `PATH`. |
| `linexa-root-exec` | Option parser, command quoting, mailbox client, output streamer and exit-status translator. |

The files are compared byte-for-byte before writing, written through a
temporary file and renamed, and never followed through a symlink. Deleting
them exposes the distribution's own `sudo` again.

### 4.2 Why not a setuid binary

A setuid-root `sudo` requires the kernel to honour the setuid bit. Under PRoot
the kernel sees the app's uid for every tracee; PRoot's fake-id emulation
rejects `setuid(0)` from an emulated non-root identity with `EPERM`. Shipping a
fake setuid binary would either do nothing (truthful failure) or lie about the
result (treat the command as root when it is not). Linexa chooses the real
mechanism it already owns: a separate `-0` PRoot process.

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
  cmd       guest → host: exact command string (UTF-8, no NUL)
  claimed   host → guest: created atomically before execution
  out       host → guest: append-only streamed stdout+stderr
  status    host → guest: final exit code, written atomically last
```

### 4.4 Request sequence

1. The helper verifies the bridge marker and protocol banner.
2. It creates the request directory with `mktemp -d`, `umask 077`.
3. It writes `cmd`, then waits up to 15 s for `claimed`.
4. The app's `RootExecBridge` polls `<rootfs>/tmp` every 100 ms.
5. It rejects symlinked request directories and commands larger than 64 KiB.
6. It claims the request with an atomic `createNewFile()` **before** reading
   the command or executing anything, so a bridge restart or a second bridge
   can never replay a request that may already have mutated the guest.
7. It runs the command through
   `PRootContainerRuntime.execInternal(...)` — the same root-emulated one-shot
   path as Root Shell and the Package Manager — with the normal operation
   timeout, `DEBIAN_FRONTEND=noninteractive`, dpkg health recovery and the
   per-workspace package-operation queue.
8. It appends every output line to `out`, flushes, then atomically renames a
   temporary `status` file into place.
9. The helper streams `out` with `tail -c +N`, waits for `status`, drains the
   final bytes and exits with the command's code.

`status` is authoritative: if it is absent the command is still running (or
the app died — see heartbeat below).

### 4.5 Quoting

`linexa-root-exec` quotes each argv element with the POSIX single-quote rule.
Safe shell words (`[A-Za-z0-9_./:=+@%,^-]+`) stay **bare**; everything else is
wrapped in `'…'` with embedded quotes escaped as `'\''`.

The safe-word exception is load-bearing: the runtime classifies package
operations by whitespace tokens. `sudo apt install -y htop` must produce
`apt install -y htop` (not `'apt' 'install' …`) so
`PackageOperationClassifier` routes it through the package queue and dpkg
recovery paths. The helper also prefixes the caller's `pwd` (`cd -- <dir> &&
…`) unless `-D <dir>` was supplied, preserving real `sudo`'s working-directory
behavior.

### 4.6 Heartbeat and failure handling

The app rewrites `heartbeat` every 2 s while the bridge is alive. A guest that
has been claimed but sees no heartbeat change for 30 s prints:

```
linexa: the root bridge stopped responding (the Linexa app may have been restarted)
linexa: open Apps → Root shell to run privileged commands, or restart the workspace and retry
```

and exits `125`. The marker check covers a workspace that was closed while the
request was in flight. The 15 s claim timeout covers a stopped/missing bridge
without waiting forever.

Exit codes:

| Code | Meaning |
|---|---|
| `0–255` | The root-emulated command's own exit code |
| `124` | The app-side operation timeout (`timeout(1)` convention) |
| `125` | Bridge/protocol/unavailable failure (`sudo` convention) |

### 4.7 Supported option surface

| Option | Behavior |
|---|---|
| `-E`, `--preserve-env` | Accepted no-op; the environment is already the user's and the privileged context is the root-emulated one. |
| `-u root`, `-g root` | Accepted; the command runs root-emulated. |
| `-u <other>`, `-g <other>` | Rejected with an explanation; PRoot can only offer the emulated root context. |
| `-i`, `--login`, `-s`, `--shell` | Rejected from a user session with guidance to Apps → Root shell. Already-root sessions open a login shell. |
| `-k`, `-K`, `-v` | Accepted no-ops (there is no timestamp cache). |
| `-D`, `--chdir` | Changes the command's working directory (also supported through the bridge). |
| `-p`, `-C`, `-T`, `-r`, `-t` | Their arguments are consumed; behavior is not emulated. |
| `-l`, `-h`, `-V` | Local help/list/version output. |
| Unknown option | Explicit error plus the Root Shell alternative. |
| `--` | Ends option parsing. |

`sudo` with no arguments in a normal session prints usage and exits 1; in a
Root Shell it opens the login shell.

### 4.8 Rootfs permission envelope (cross-distro)

Real rootfs archives do not all use the same directory modes. Arch Linux ARM
restores `/` and `/proc` as `0555`; Fedora 44 restores `/` and `/usr/bin` as
`0555`. PRoot's fake uid 0 does not change the kernel's DAC check: every guest
process is still the Android app uid at the syscall boundary, so a
`0555` directory owned by that uid rejects a new entry. Package managers work
around this internally (RPM chmods a directory while extracting and restores
it), but Linexa's own provisioning writes hit `EACCES` unless the app widens
the exact directory for the write.

`GuestRootfsInitializer` and `HostInfoRuntimeBridge` now share
`RootfsWriteWindow` (`core/filesystem/RootfsWriteWindow.kt`):

| Location | Repair | Lifetime |
|---|---|---|
| rootfs `/` | owner `rwx` added when the archive restored it without owner-write | permanent, logged once per repair; root-emulated processes need a root-writable `/`, as on a real system |
| `/proc` snapshots | owner-write added around the `.stat`/`.uptime`/… writes | restored in `finally`; restore failure is a hard failure |
| `/proc` host-info overlays | same window around `.meminfo`/`.net/*` writes | restored in `finally`; shared `RootfsWriteLock` with the installer |
| symlinked `/proc` | refused: never followed, never widened, never written through | skipped |

The window is deliberately narrow: it touches only the named directory, never
recurses, never adds group/other write bits, and refuses a symbolic link. It is
not a `chmod 777` and does not grant anything beyond the app uid's existing
access. The `/proc` window restores the archive's exact permission set; the `/`
repair is permanent because a root-emulated guest is expected to create entries
in `/` (the legacy home marker, for example) exactly as real root would.

Regression coverage: `RootfsWriteWindowTest` (writable unchanged, read-only
restore, symlink refusal, missing directory refusal),
`GuestRootfsInitializerTest.readOnlyRootfsRootIsRepairedEvenWhenTmpIsAlreadyHealthy`,
`GuestRootfsInitializerRunAndProcTest.ensureProcSnapshotsWidensAndRestoresAReadOnlyProcDirectory`
and the new `ensureProcSnapshotsRefusesASymlinkedProcAndNeverTouchesItsTarget`,
and `HostInfoRuntimeBridgeTest`'s read-only `/proc` + `.net` overlay case.

### 4.9 Fake-root seccomp policy

PRoot's seccomp acceleration is a performance feature, but on Android it can
run an intercepted `open(O_CREAT|O_EXCL)` twice if the app process is stopped
(background freezer, `SIGSTOP`) between interception and resume. The first run
creates the file; the guest receives `EEXIST` from the second. `mktemp` retries,
and pacman 7's `db.lck` probe treats the false `EEXIST` as a live lock.

The fix is at the launch boundary, not in a package manager:

- every fake-root (`-0`) launch — `sudo` one-shots, Root Shell, the in-app
  package path and root daemons — carries `PROOT_NO_SECCOMP=1`;
- normal `--change-id=1000:1000` terminals keep PRoot's acceleration;
- the one-time seccomp probe strips the variable before probing, so it still
  tests the kernel rather than its own workaround;
- `PROOT_NO_SECCOMP` is in the guest environment filter, so it is scrubbed from
  the tracee and is not visible to guest programs.

This is a performance trade-off confined to privileged operations. It is
distro-neutral because the corruption is in PRoot's fake-root interception, not
in a package manager. Regression tests: `PRootLauncherRootSeccompTest`,
`PRootExecSeccompRetryTest`, `PRootOpenSessionSeccompProbeTest` and
`PRootLauncherGuestEnvFilterTest`.

### 4.10 Arch pacman 7 sandbox

pacman 7 enables an `alpm`/Landlock sandbox and switches to an `alpm` user.
PRoot does not implement a Landlock domain, so the stock Arch configuration
fails before it reaches a repository:

```
error: restricting filesystem access failed because Landlock is not supported by the kernel!
error: switching to sandbox user 'alpm' failed!
```

The supported workaround is the distribution's own configuration surface, for
example a pacman config containing:

```ini
DownloadUser = root
DisableSandboxFilesystem
DisableSandboxSyscalls
```

Linexa does not rewrite pacman's stock config and does not special-case pacman
in the shared bridge. The opt-out is the workspace's distro-level choice, just
as it would be on a real Arch system whose kernel lacks Landlock.

## 5. Lifecycle

| Event | Bridge action |
|---|---|
| First terminal (normal or Root Shell) opens | `start()`: create marker + heartbeat, sweep abandoned requests, start polling. Idempotent. |
| Last terminal of the workspace closes | `stop()`: stop accepting requests, delete marker/heartbeat. Claimed handlers keep running and still write `status`. |
| Workspace stops (`closeWorkspaceSessions`) | Same stop, before the ptys are destroyed. |
| App process dies | Marker remains stale but heartbeat stops; a waiting guest fails after 30 s with guidance. |
| Workspace rootfs path changes (re-import) | The stale bridge is stopped and recreated against the new path. |
| App restart | A new bridge is created on the next terminal open; unclaimed stale requests are swept after 1 h, claimed abandoned ones after 24 h. |

`RootExecBridge` is deliberately Android-free (`java.io`/`java.nio` +
coroutines), so its claim/stream/status state machine is covered by JVM tests.

## 6. Security properties and limits

What the bridge does **not** do:

- It does not change the caller's uid, environment, cwd or process identity.
- It does not bypass Android's uid/SELinux model or grant host root.
- It does not read commands from a location outside the workspace's own
  `/tmp`; symlinked request directories are rejected and the command file must
  be a regular, non-symlink file.
- It does not add world-writable directories: guest `/tmp` and `/run` are
  already `1777`/workspace-owned and are provisioned by
  `GuestRootfsInitializer`.
- It does not accept NUL bytes or commands over 64 KiB.
- It does not silently fall back to executing a command as the caller when the
  bridge is unavailable.

Limits users can observe:

| Limit | Consequence | Alternative |
|---|---|---|
| No stdin/TTY forwarding | Interactive programs (`passwd`, editors, debconf prompts) cannot be answered; `apt install` needs `-y`. A program that waits for protocol input, such as `dpkg-preconfigure --apt`, hangs because the pipe never closes. | Apps → Root shell; use noninteractive package operations |
| No Ctrl-C propagation | The shell helper can be interrupted, but the root-emulated command may continue until its own timeout. | Root shell, where job control and signals are real |
| Runtime hardlink families | A hardlink family created after extraction (for example by a package manager) can get an absolute host-path symlink target from PRoot. Emulated root (`-0`) resolves it; UID 1000 gets `ENOENT`, so normal-user execution of that member fails. | `sudo <command>` or Root shell until a live-rootfs repair lands |
| No sudo timestamp/password cache | `sudo -v`/`-k` are accepted no-ops; every command runs directly. The workspace is single-user anyway. | — |
| Root is emulated | A workspace “root” has exactly the app's filesystem access, not Android root. | — |
| Workspace-level trust | Any process in the workspace that can execute `/usr/local/bin/sudo` can run root-emulated commands. This is inherent to PRoot (the app uid already owns the rootfs). | — |

## 7. Regression tests

| Test | Coverage |
|---|---|
| `RootExecProtocolTest` | Safe-word quoting, single-quote escaping, argv joining, exit-code normalization, status parsing, request-name prefix, marker banner. |
| `RootExecBridgeTest` | End-to-end claim → execute → stream → status on a fake rootfs; replay rejection; symlink rejection; oversized-command rejection; heartbeat advance/removal; stop. |
| `GuestRootfsInitializerUserTest` | Both scripts provisioned and executable; wrapper delegates to the helper; helper routes through the mailbox; interactive/non-root options rejected with guidance; safe-word quoting; heartbeat check; byte-idempotent re-provisioning. |
| `RootfsWriteWindowTest` | Read-only directory widened then restored; writable directory untouched; symlink refused; missing directory refused. |
| `GuestRootfsInitializerTest` | Read-only rootfs `/` repaired even when `/tmp` is already healthy. |
| `GuestRootfsInitializerRunAndProcTest` | Read-only `/proc` snapshots succeed and restore the exact mode; symlinked `/proc` is skipped without touching its target. |
| `HostInfoRuntimeBridgeTest` | Read-only `/proc` and `/proc/.net` overlays are written and the archive modes restored. |
| `PRootLauncherRootSeccompTest` | Root launches add `PROOT_NO_SECCOMP=1`; normal-user launches do not; probe and guest-env filtering contracts. |
| `PRootExecSeccompRetryTest` | One-shot execs use the root-only no-seccomp policy and preserve the retry contract. |
| `PRootOpenSessionSeccompProbeTest` | Probe strips `PROOT_NO_SECCOMP`; no leak into the guest; fallback still works. |
| `PRootLauncherGuestEnvFilterTest` | Host-owned env keys, including `PROOT_NO_SECCOMP`, are scrubbed from the tracee. |
| Shell simulation (device-lane evidence) | Extracted helper driven against a fake host poller: simple output, spaces/quotes, bare `apt` argv, interactive refusal, non-root refusal, cwd preservation, dead-bridge timeout. |

Targeted JVM tests cover the bridge protocol, seccomp policy, environment
filtering, and the helper's argument parsing. On-device verification is still
required for the full distribution matrix (effective UID, package
install/remove, persistence, Root Shell UI, and unavailable-bridge errors).

## 8. Device verification

Device verification is tracked outside the public source tree. Before
claiming a distribution as supported, run the package-manager and root-shell
checks described in [`../engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

## 9. Related documentation

- [`TERMINAL_AND_LINUX_RUNTIME.md`](./TERMINAL_AND_LINUX_RUNTIME.md) — PRoot
  launch paths, PTY, exec/daemon identity.
- [`SECURITY_AND_PERMISSIONS.md`](./SECURITY_AND_PERMISSIONS.md) — security
  boundaries and the same-uid PRoot model.
- [`../decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md) — root-shell
  decision record.
