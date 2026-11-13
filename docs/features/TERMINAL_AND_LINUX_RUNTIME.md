# Terminal and Linux Runtime

**Status:** Current — describes the implemented terminal/Linux runtime feature, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/service/runtime/pty/`, `app/src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt`, `app/src/main/java/com/fem/linexa/service/runtime/container/PRootLauncher.kt`, `app/src/main/java/com/fem/linexa/ui/screens/terminal/`, `terminal-emulator/`, `terminal-view/`

---

## 1. Purpose

This document is the feature-level reference for Linexa's terminal and Linux runtime: real PTY terminal stack, terminal UI, extra keys, pinch-to-zoom, clipboard, management terminal, and the PRoot launch path that hosts the guest shell.

Deep PRoot internals (argv construction, environment, `/proc`/`/sys`/`/dev` binds, installer) are covered in [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md).

---

## 2. Scope

In scope:

- PTY allocation via the vendored Termux `:terminal-emulator` module (`libtermux.so`, `termux.c` `/dev/ptmx` + `fork`/`setsid`)
- Terminal UI (`RealTerminalView`, `TerminalViewModel`, `TerminalsViewModel`, `ManagementTerminalViewModel`)
- Extra keys row, pinch-to-zoom, clipboard
- Management terminal (host `/system/bin/sh`)
- The three launch paths (PTY terminal, one-shot exec, daemon)
- tmux as optional persistence backend (not on launch path)

Out of scope:

- PRoot argv/env internals: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Workspace/session lifecycle: [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md)
- Desktop/VNC: [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Real PTY (`/dev/ptmx` + `/dev/pts/N`, setsid, ctty) | Implemented | `service/runtime/pty/TermuxPtySession.kt`, `terminal-emulator/src/main/jni/termux.c` |
| Terminal UI with full ANSI/cursor rendering | Implemented | `ui/screens/terminal/RealTerminalView.kt`, `:terminal-view` |
| ExtraKeysView modifier-key row | Implemented | `ui/screens/terminal/` (library built-in key set) |
| Pinch-to-zoom font (persisted) | Implemented | `ui/screens/terminal/` |
| Terminal copy → Android clipboard | Implemented | `ui/screens/terminal/` |
| Workspace terminal desktop (independent PTY-backed windows) | Implemented | `domain/terminal/TerminalWindowManager.kt`, `ui/screens/terminal/WorkspaceTerminalPanel.kt` |
| Recent Terminals overview (responsive live-preview grid, IME-independent cards) | Implemented | `ui/screens/terminal/TerminalSessionOverview.kt`, `domain/terminal/TerminalOverviewMetrics.kt` |
| App Launcher opens a titled terminal window per app | Implemented | `ui/screens/workspace/AppLauncherViewModel.kt`, `domain/terminal/TerminalWindowIdentityRegistry.kt` |
| Management terminal (host `/system/bin/sh -l`) | Implemented | `ui/screens/terminal/ManagementTerminalViewModel.kt` |
| One-shot exec path (pipes, no TTY) | Implemented | `PRootContainerRuntime.execInternal()` |
| tmux persistence (opt-in, default off) | Implemented | `TmuxPersist.kt`, `feature.terminal.persistence` |
| `FakeData` removed (2026-07-26) | Implemented | `data/Models.kt` populated from real repository data |

---

## 4. Technical overview

### 4.1 PTY allocation

`TermuxPtySession` constructs `com.termux.terminal.TerminalSession(executable, cwd, argv, env, callback)`; `argv[0]` is the executable itself. The first `session.updateSize(columns, rows)` sets geometry and **starts the child**. The native implementation is the vendored Termux `terminal-emulator` (`src/main/jni/termux.c`, loaded via `System.loadLibrary("termux")` in `JNI.java`; provenance in `terminal-emulator/PROVENANCE.md`). The step list below was written for the removed `terminal-lib` and has been spot-checked only for `/dev/ptmx` open, `setsid()` and `ptsname`/`grantpt` against `termux.c`; the remaining steps are `UNVERIFIED` against the current file:

- `open("/dev/ptmx", O_RDWR | O_CLOEXEC)` — master
- `grantpt`/`unlockpt`/`ptsname_r` — slave = `/dev/pts/N`
- `fork()`; child `setsid()`, opens slave, `dup2`s slave onto fds 0/1/2, closes other fds, `clearenv()`/`putenv()`, `chdir(host cwd)`, `execvp(cmd, argv)`

Conclusions verified from source:

- **A real PTY is allocated** — `/dev/ptmx` master + `/dev/pts/N` slave.
- **The child receives a controlling terminal** (`setsid()` + slave open without `O_NOCTTY`).
- **stdin/stdout/stderr are the PTY slave** — not pipes, not sockets, not `/dev/null`.
- **Window size propagates via `ioctl(TIOCSWINSZ)`** (also on resize via `JNI.setPtyWindowSize`), delivering SIGWINCH.
- Signals: control keys converted by the emulator and delivered by the pty line discipline (ISIG); job control (Ctrl+Z/`jobs`) verified working at the PTY layer.

### 4.2 Where "not a tty" comes from

| Session type | stdio | `tty` says |
|---|---|---|
| Workspace terminal (TerminalView) | PTY slave | a real tty |
| Management terminal | PTY slave | a real tty |
| One-shot `execInternal` (Package Manager UI, probes, preflights) | pipes; stdin = never-written pipe | "not a tty" (expected) |

No Linexa code connects stdin to `/dev/null` or stdio to sockets. The one-shot exec bridge genuinely lacks a TTY by design; `DEBIAN_FRONTEND=noninteractive` is layered in to prevent debconf stalls.

### 4.3 Input/output flow

- **Input:** `TerminalView` (library) handles keys, writes to the attached `com.termux.terminal.TerminalSession` (obtained through `TermuxTerminalSessionProvider`; the old `LibTerminalSession`/`TerminalLibSession` escape hatch no longer exists) → pty master → line discipline → guest. The app's `headlessViewClient` returns `false` from key handlers to keep library-default handling.
- **Output:** library reader thread → main-thread Handler → `TerminalEmulator.append` → `notifyScreenUpdate` → `TermuxPtySession` callback → `PtyEvent.ScreenUpdated` → `RealTerminalView` repaint.
- **Resize:** library-owned (`TerminalView` measures and calls its own `updateSize`); initial size is always 80×24 until first layout.

### 4.4 Terminal UI

- `RealTerminalView` (`ui/screens/terminal/RealTerminalView.kt`) — Compose hosting the vendored Termux `terminal-view`; `ExtraKeysView` modifier-key row with the library's full built-in key set (Ctrl/Alt/Esc/Tab/arrows/Fn/PgUp-PgDn/Home/End); nothing beyond that customized.
- `TerminalViewModel` / `TerminalsViewModel` — per-terminal view models.
- `ManagementTerminalViewModel` — uses the same PTY factory but execs host `/system/bin/sh -l` with bootstrap `PATH` (a deliberate SELinux W^X workaround: `filesDir` binaries can't be exec'd directly on Android 10+).
- Pinch-to-zoom adjusts the font (persisted); Terminal copy writes to the Android clipboard.

#### 4.4.1 Workspace terminal desktop

The workspace terminal is a desktop-style window manager over the existing PTY
runtime, not a single mutable terminal surface:

- **One window per terminal.** `TerminalWindowManager` (pure reducer) derives a
  `TerminalDesktopState` from the runtime's terminal list: one `TerminalWindow`
  per live terminal, each with its own `TerminalWindowUiState` (`CREATED`,
  `ACTIVE`, `MINIMIZED`, `MAXIMIZED`, `RESTORED`, `CLOSING`, `CLOSED`) and its
  own process `TerminalState`. UI state is presentation only: minimizing,
  maximizing, or focusing another window never changes whether the child
  process is alive.
- **One PTY per window.** `TerminalViewModel` attaches the single rendered
  `TerminalView` to the focused window and clears the previous window's pty
  while a newly focused terminal is still resolving, so a window can never
  display another session's output. Switching windows re-attaches, never
  re-creates, the pty. Minimizing hides a window but leaves its pty, process,
  and output untouched.
- **Window chrome.** Each window title bar carries an identity icon, title,
  live-process indicator, and Close / Minimize / Recents actions;
  Close asks for confirmation while the process is live. The workspace-level
  bar carries only workspace identity, Recent Terminals, and New Terminal.
- **Recent Terminals overview.** `TerminalSessionOverview` renders a responsive
  recent-apps grid. Every card previews that window's own visible screen
  (`PtySession.previewText`), with its title, icon, process state, foreground
  process (`PtySession.foregroundProcessName` via `/proc`), and close action.
  Cards use a fixed `0.72` aspect ratio (`TerminalOverviewMetrics`); portrait
  phones get two equal columns, wider screens add columns, and narrow screens
  fall back to one. Width comes from the stable screen width and height from the
  ratio, so IME visibility never resizes a card. The grid scroll state is hoisted
  so it survives leaving and re-entering the overview. The old persisted
  horizontal/vertical layout preference and its two arrangements are removed —
  there is one grid.
- **Titles and app identity.** Windows fall back from catalog app identity to
  the terminal's OSC title to a stable `Terminal Tn` label.
  `TerminalWindowIdentityRegistry` records `(workspaceId, terminalId)` app
  identity and exposes a revision `StateFlow`, so a late-recorded identity
  updates the already-visible window and overview card.
- **App Launcher integration.** `AppLauncherViewModel.launch` opens a fresh
  workspace terminal (or root terminal) per app, records the app identity
  before awaiting the pty, and writes the catalog launch command once the pty
  exists. A per-app in-flight job ignores rapid double taps while different
  apps open independent windows; a launch whose pty never resolves closes the
  session it opened and reports failure instead of leaking an orphan shell.

### 4.5 Launch paths (summary)

| Path | stdio | TTY? | Used by |
|---|---|---|---|
| `openTerminal` → PTY | PTY slave | Yes | Workspace terminals, Management terminal |
| `execInternal` → pipes | pipes | No | Package Manager UI, File Manager, provisioners, dpkg preflight |
| `startDaemon` → file-backed stdout | file | No | Xvnc, sshd daemons |

Full PRoot argv/env construction and `/proc`/`/sys`/`/dev` handling are in [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md).

---

## 5. Limitations and known issues

- The one-shot exec path has no PTY by design; interactive debconf prompts would stall until the operation timeout (mitigated by `DEBIAN_FRONTEND=noninteractive`).
- Initial terminal size is always 80×24 until first layout.
- `PtySession.resize` seam exists with no callers; `PtySession.write()` is used by the app/shortcut launcher paths and by terminal input, not by a line-oriented bridge.
- Terminal I/O over the real PTY under real guest shells still needs on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime, launch paths, environment, `/proc`/`/sys`/`/dev`
- [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md) — tmux persistence, session lifecycle
- [`features/DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md) — desktop daemons launched via `startDaemon`
- [`terminal-emulator/PROVENANCE.md`](../../terminal-emulator/PROVENANCE.md) — terminal-emulator module provenance
- [`terminal-view/PROVENANCE.md`](../../terminal-view/PROVENANCE.md) — terminal-view module provenance
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
