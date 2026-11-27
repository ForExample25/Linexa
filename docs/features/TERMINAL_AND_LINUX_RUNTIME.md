# Terminal and Linux Runtime

**Source locations:** `app/src/main/java/com/fem/linexa/service/runtime/pty/`,
`app/src/main/java/com/fem/linexa/service/runtime/container/PRootContainerRuntime.kt`,
`app/src/main/java/com/fem/linexa/service/runtime/container/PRootLauncher.kt`,
`app/src/main/java/com/fem/linexa/ui/screens/terminal/`, `terminal-emulator/`,
`terminal-view/`

This document describes the terminal and Linux-runtime feature: the real PTY stack,
terminal UI, extra keys, pinch-to-zoom, clipboard, the management terminal, and the PRoot
launch path that hosts a guest shell. Deep PRoot argv/environment and `/proc` behavior is
covered in [`../architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md).

## 1. PTY allocation

`TermuxPtySession` constructs the vendored
`com.termux.terminal.TerminalSession(executable, cwd, argv, env, transcriptRows, callback)`.
The Kotlin layer prepends the executable to the argument list so `argv[0]` is the program
path, as `execvp` expects. The first `updateSize(columns, rows, ...)` call creates the
emulator and starts the child process.

The native implementation is the vendored Termux `terminal-emulator` module
(`src/main/jni/termux.c`, loaded through `System.loadLibrary("termux")`). It performs:

1. `open("/dev/ptmx", O_RDWR | O_CLOEXEC)` for the master.
2. `grantpt`, `unlockpt`, and `ptsname_r` to obtain `/dev/pts/N`.
3. UTF-8 mode and flow-control setup on the master, then `ioctl(TIOCSWINSZ)` for the initial
   window size.
4. `fork()`. The child closes the master, calls `setsid()`, opens the slave, duplicates it
   onto fds 0/1/2, closes other descriptors, clears and installs the supplied environment,
   changes to the host working directory, and calls `execvp`.
5. The parent returns the master fd and child pid to Java.

This gives the guest a real controlling terminal, PTY-backed stdin/stdout/stderr, window-size
propagation through `TIOCSWINSZ`, and normal line-discipline signal handling.

## 2. Input and output flow

- **Input:** the vendored `TerminalView` handles key events and writes them to the attached
  `TerminalSession` obtained through `TermuxTerminalSessionProvider`. The app's
  `headlessViewClient` returns `false` from key handlers so the library keeps its default
  behavior. Input travels through the PTY master, line discipline, and guest process.
- **Output:** the library reader thread reads the PTY and posts to the main-Looper handler.
  The emulator appends the bytes, emits `onTextChanged`, and `TermuxPtySession` publishes
  `PtyEvent.ScreenUpdated`. `RealTerminalView` repaints from the emulator state.
- **Resize:** the vendored view measures itself and calls its own size update. The
  `PtySession.resize` seam is available for non-view callers and delegates to the same
  `TerminalSession.updateSize`.

## 3. Terminal UI

- `RealTerminalView` hosts the vendored Termux `terminal-view`. It supports the library's
  ANSI/cursor rendering, scrollback, selection, and the `ExtraKeysView` modifier row
  (Ctrl/Alt/Esc/Tab/arrows/Fn/PgUp-PgDn/Home/End).
- `TerminalViewModel` and `TerminalsViewModel` own per-terminal view state.
- Pinch-to-zoom adjusts the terminal font and persists the preference.
- Terminal copy writes to the Android clipboard; OSC 52 clipboard requests flow through
  `PtyEvent.ClipboardText`.
- `ManagementTerminalViewModel` uses the same PTY factory but executes the host
  `/system/bin/sh -l` with the bootstrap `PATH`. This is a deliberate SELinux/W^X
  workaround: binaries under `filesDir` cannot be executed directly on Android 10+, while
  `/system/bin/sh` can.

### 3.1 Workspace terminal desktop

The workspace terminal is a desktop-style window manager over the PTY runtime:

- **One window per terminal.** `TerminalWindowManager` derives a `TerminalDesktopState` with
  one `TerminalWindow` per live terminal. Each window has a UI state (`CREATED`, `ACTIVE`,
  `MINIMIZED`, `MAXIMIZED`, `RESTORED`, `CLOSING`, `CLOSED`) and its own process
  `TerminalState`. Minimizing, maximizing, or focusing another window does not change
  whether the child process is alive.
- **One PTY per window.** `TerminalViewModel` attaches the rendered `TerminalView` to the
  focused window and detaches the previous window's PTY while a new one resolves. Switching
  windows re-attaches an existing PTY rather than creating a new process.
- **Window chrome.** Each window title bar carries an identity icon, title, live-process
  indicator, and Close / Minimize / Recents actions. Close asks for confirmation while the
  process is live. The workspace-level bar carries workspace identity, Recent Terminals,
  and New Terminal.
- **Recent Terminals.** `TerminalSessionOverview` renders a responsive grid of live
  previews. Each card shows the window's visible screen (`PtySession.previewText`), title,
  icon, process state, foreground process name, and close action. Cards use a fixed aspect
  ratio from `TerminalOverviewMetrics`; width comes from the stable screen width, so IME
  visibility does not resize the grid. Scroll state is hoisted so it survives leaving and
  re-entering the overview.
- **Titles and identity.** Windows fall back from catalog app identity to the terminal's
  OSC title to a stable `Terminal Tn` label. `TerminalWindowIdentityRegistry` records
  `(workspaceId, terminalId)` app identity and exposes a revision `StateFlow`, so a late
  identity update reaches an already-visible window and card.
- **App Launcher integration.** `AppLauncherViewModel.launch` opens a fresh workspace
  terminal or root terminal per app, records the app identity before awaiting the PTY, and
  writes the catalog launch command once the PTY exists. A per-app in-flight job ignores
  rapid double taps; a launch whose PTY never resolves closes the provisional session and
  reports failure instead of leaking an orphan shell.

## 4. Launch paths

| Path | stdio | TTY | Used by |
|---|---|---|---|
| `openTerminal` → PTY | PTY slave | Yes | Workspace terminals, Root Shell, management terminal |
| `execInternal` → pipes | pipes; stdin never written | No | Package Manager, File Manager, provisioners, dpkg preflight, probes |
| `startDaemon` → file-backed stdout | file | No | Xvnc, sshd, managed guest daemons |

The one-shot exec path genuinely lacks a TTY by design. `DEBIAN_FRONTEND=noninteractive` is
layered in to prevent debconf stalls. No Linexa code connects stdin to `/dev/null` or stdio
to sockets.

## 5. tmux persistence

tmux is optional and off the critical launch path. The PTY child is PRoot directly. With
`feature.terminal.persistence` enabled, `TmuxPersist` wraps launches in a tmux session so a
closed terminal can reattach while the workspace runtime is alive. App-process death ends
the guest; tmux persists only within the running container.

## 6. Behavior notes and limits

- The one-shot exec path cannot answer interactive prompts; use the Root Shell for those.
- The initial terminal size is set at fork time and corrected after the view's first layout.
- tmux persistence is opt-in and does not survive app-process death.
- Terminal output, commands, paths, and identifiers remain left-to-right in both languages.
  The app chrome is always LTR as well; Persian text outside the terminal still uses RTL bidi
  rendering (see [`../design/ACCESSIBILITY_AND_LOCALIZATION.md`](../design/ACCESSIBILITY_AND_LOCALIZATION.md) §4.2.1).
- A non-zero exit is described by `TerminalExitDescription`; `126`/`127`/`137`/`139` are
  conventional meanings with a next step, not proven causes. The error card offers Restart,
  Diagnostics, and Copy error. See
  [`../architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) §3.1
  for the status table and the `127` procedure.
- A terminal that stays **live** but prints nothing for eight seconds shows a non-blocking
  "No output yet" banner with Diagnostics and Restart. The banner sits over the terminal
  view; it never replaces it, and the watchdog never destroys the PTY. Any output (including
  a prompt that arrived before the screen attached) clears it.
- A package command typed into a terminal goes through the PTY, not the Package Manager
  queue. Native apt/dpkg locking remains the authority; if a UI operation fails because
  another process holds the lock, the Package Manager names the reported holder and asks the
  user to wait. It never signals that process.

## 7. Related documentation

- [`../architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime, launch paths, environment, `/proc`
- [`WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md) — workspace lifecycle, tmux persistence
- [`DESKTOP_AND_VNC.md`](./DESKTOP_AND_VNC.md) — desktop daemons launched through `startDaemon`
- [`ROOT_SHELL_AND_SUDO.md`](./ROOT_SHELL_AND_SUDO.md) — root-emulated terminal and bridge
- [`../../terminal-emulator/PROVENANCE.md`](../../terminal-emulator/PROVENANCE.md) — terminal-emulator provenance
- [`../../terminal-view/PROVENANCE.md`](../../terminal-view/PROVENANCE.md) — terminal-view provenance
