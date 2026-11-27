# Desktop and VNC

**Source locations:** `app/src/main/java/com/fem/linexa/ui/screens/desktop/`,
`app/src/main/java/com/fem/linexa/service/runtime/desktop/`,
`app/src/main/java/com/fem/linexa/service/runtime/features/DesktopRunMode.kt`,
`app/src/main/java/com/fem/linexa/service/runtime/features/GuiBackend.kt`

---

## 1. Purpose

A workspace can present a graphical session in two ways: an embedded VNC
viewer connected to a guest X server, or an in-app WebView pointed at a guest
web server. This document describes those run modes, the transport backends
behind VNC mode, desktop presets, authentication, geometry, and input.

PRoot daemon launching is covered in
[`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md).

---

## 2. Scope

In scope:

- Run modes: VNC and WebView
- Backends for VNC mode: X11 and VNC
- Desktop presets: XFCE, LXQt, Openbox
- Embedded RFB 3.8 client: CopyRect, Raw, optional ZRLE, DesktopSize, VNC-Auth
- VNC password provisioning and loopback binding
- Geometry, DPI, input, and text entry
- Known limitations

Out of scope:

- PRoot daemon startup: [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- Password storage and backup rules: [`features/SECURITY_AND_PERMISSIONS.md`](./SECURITY_AND_PERMISSIONS.md)

---

## 3. Run modes

The run mode is a per-workspace setting (`feature.desktop.mode`, persisted in
workspace attributes) selected in **Features → Desktop**. It is resolved
fail-open: an unknown or absent value becomes `VNC`.

### 3.1 VNC mode

1. The workspace runtime must be running; the desktop runs inside the
   workspace's PRoot container.
2. A display number is allocated per workspace (`X11SessionManager`), and a
   loopback RFB port is allocated per workspace (`DesktopPortRegistry`,
   preferring 5901 and scanning forward).
3. The desktop enable flow generates a VNC password if none exists, unless
   the user has explicitly allowed an unauthenticated desktop. A password is
   provisioned inside the guest with `vncpasswd -f` before the server starts;
   a provisioning failure prevents an unauthenticated start. The secret is
   written to the guest process's stdin, never into the command line, so it is
   not readable from `/proc/<pid>/cmdline` while provisioning runs.
4. A start with no password fails closed unless the user enabled
   **Allow desktop without a VNC password** in the Desktop settings. Loopback
   is not treated as an authentication boundary: Android does not isolate
   apps on `127.0.0.1`.
5. The app waits for the real X socket or RFB port to answer before reporting
   the desktop as running.
6. The framebuffer is streamed by the in-app RFB 3.8 client.

### 3.2 WebView mode

WebView mode runs a user-configured guest command (webtop, noVNC,
code-server, a static HTTP server, or any other web service) as a supervised
daemon, waits for its TCP port, and renders the result in a fullscreen in-app
`WebView`.

- Cleartext HTTP is permitted only for `127.0.0.1` and `localhost`
  (`network_security_config.xml`).
- The WebView pins navigation to the loopback origin it was opened with.
  File and content access are disabled, and the WebView is destroyed when the
  panel leaves the composition.
- "Share to LAN" copies the URL string; the guest server itself decides what
  interface it binds.
- The server is **user-supplied**. Linexa pins the WebView to its loopback
  origin, but adds no authentication, TLS, or access-control layer of its own:
  anything that can reach the chosen port can use whatever the server exposes.
  Prefer a server with its own authentication, keep it on loopback unless you
  intentionally use Share to LAN, and only expose it on networks you trust.

WebView mode needs no X client in the guest. VNC mode requires an X server and
a session; the desktop presets install them.

---

## 4. Backends for VNC mode

VNC mode selects a backend with `GuiBackend` (`feature.desktop.backend`),
defaulting to `X11`.

### 4.1 X11 backend

Two providers implement X11:

1. **Termux:X11** (preferred when available). The guest runs the
   `termux-x11` helper as a supervised daemon. The helper connects to the
   Termux:X11 Android app and creates the display socket inside the rootfs.
   Linexa watches that socket host-side and launches the viewer app.
   Availability is probed: both the Android app and the guest helper must be
   present.
2. **Xvnc** (compatibility path). The guest's own `Xvnc` serves the display,
   and Linexa's embedded RFB viewer renders it. This path is used when the
   Termux:X11 app or helper is unavailable.

X11 readiness is detected from the Unix socket at
`{rootfs}/tmp/.X11-unix/X<n>`, so polling costs one host-side `stat` rather
than a PRoot launch. A failure produces an explicit `X11Failed` state; the
screen offers the VNC backend as a fallback rather than switching silently.

### 4.2 VNC backend

The VNC backend is the direct RFB path: start Xvnc, wait for the RFB port,
and stream the framebuffer. It shares display/port allocation and daemon
teardown with the X11 path.

Both backends stop daemons by their own `(workspace, name)` key. No
host-global `pkill` is used, because PRoot does not isolate PIDs between
workspaces.

---

## 5. Presets

Installable presets — **XFCE**, **LXQt**, and **Openbox** — are provisioned
through the guest's package manager. Presence is detected before install, so
an existing preset is skipped. After installation, the declared session
launcher is probed and used for subsequent starts.

Preset installation runs through the streaming exec path with the package
operation budget and the per-workspace package queue.

---

## 6. RFB client

`VncClient` implements RFB 3.8 with:

- **Encodings:** CopyRect and Raw by default; ZRLE is available and offered
  when the workspace opts in (`feature.desktop.vnc.zrle`). A server that sends
  ZRLE unasked is still decoded, because the decoder is present.
- **DesktopSize pseudo-encoding:** a guest resolution change updates the
  client framebuffer and viewer geometry for the rest of the session.
- **VNC authentication:** when a password is configured, the client performs
  the RFB challenge/response handshake.
- **Bounded allocations:** framebuffer dimensions, desktop names, and ZRLE
  inflate output are bounded so a malicious or broken server cannot exhaust
  memory.

The receive loop and pointer/key writes run on `Dispatchers.IO` from the
start. `connect()` performs one explicit hop from the composition's Main
scope. `disconnect()` is the only thing that stops the client's coroutines;
`DesktopScreen` calls it from a `DisposableEffect`.

Dirty-region uploads are tracked per published generation. An acknowledgement
retires every generation it covers, so a late acknowledgement cannot leave a
stale box to grow into a full-screen upload. Over-reporting is deliberate:
uploading a superset of changed pixels is safe, uploading a subset is not.

No cursor pseudo-encoding is negotiated. The remote cursor is part of the
framebuffer, so cursor latency follows frame latency. A relative-cursor input
mode exists for pointer capture in the viewer.

---

## 7. Authentication and binding

- Passwords are stored as an AES-GCM envelope in the workspace attributes,
  with an `unreadable` marker if the Keystore cannot decrypt. An unreadable
  password blocks desktop start rather than silently running without
  authentication.
- Enabling the desktop generates a password when none is stored and no
  no-authentication consent exists (`passwordToGenerateOnEnable`). The
  settings panel shows the generated value and the Generate button.
- The guest passwd file is `~/.vnc/linexa-vnc-passwd`, mode 600.
- `-SecurityTypes VncAuth -rfbauth <file>` is used when a password exists.
  Otherwise the server runs with `-SecurityTypes None` **only** when
  `feature.desktop.vnc.allowUnauthenticated` is explicitly `true`; the
  default is a fail-closed refusal before any process starts.
- `-localhost yes` is added when the guest's Xvnc supports it (TigerVNC 1.11
  and later). On older Xvnc the flag is omitted, and the server's default
  binding applies. Loopback is defence in depth, never authentication.
- The capability is re-probed with the full combined discovery command after an
  Xvnc auto-install, so the `-localhost` decision describes the installed
  server rather than the pre-install absence. If the optional capability
  section is missing or empty, the start path spends one bounded retry
  (`timeout 5 Xvnc -help`); a probe whose framing is missing is reported as a
  retryable error and installs nothing.
- After the listener exists, the guest's socket table is classified as
  `Verified` (a loopback listener was actually seen), `Violation` (a routable
  listener; the server is stopped), or `Unverified` (blank output or no
  listener for the port). `Unverified` never counts as proof: without a
  password and without the consent flag the server is stopped. The tool set
  is feature-detected (`ss`, `netstat`, BusyBox `netstat`).

The full security model, including storage and export exposure, is in
[`features/SECURITY_AND_PERMISSIONS.md`](./SECURITY_AND_PERMISSIONS.md).

---

## 8. Geometry and input

`DesktopGeometry` derives a landscape framebuffer from the device display at
the panel's own resolution, with a 4K (3840×2160) degenerate-display guard,
aspect preservation, and even-dimension rounding. A fixed preset (720p, 900p,
or 1080p) can be selected for performance or legibility. DPI is scaled from the
device density and framebuffer size so guest fonts render at a sensible
physical size.

The framebuffer geometry is fixed when Xvnc starts. A guest-side resolution
change announced through DesktopSize is followed by the client, but there is
no client-driven resize request.

Input handling supports:

- Direct touch as pointer movement and clicks
- Touchpad-style relative movement
- Gestures for scroll/wheel
- Hardware and on-screen keyboard input, including composed text
- Pinch-to-zoom of the viewer

---

## 9. Limitations

- **No cursor pseudo-encoding.** The cursor cannot be separated from frame
  latency.
- **No client-driven resize.** Geometry is chosen at start; a guest resize is
  followed only when the server announces it.
- **Loopback-only is conditional.** On Xvnc versions without `-localhost`, the
  server's default bind behavior applies. An unauthenticated server is refused
  by default for exactly this reason; the user must explicitly accept that any
  app on the device can reach the desktop.
- **Bind verification needs a tool.** The post-start check depends on `ss`,
  `netstat`, or BusyBox `netstat` inside the guest. When none exists the bind
  is `Unverified`, and an unauthenticated start is stopped (rather than
  assumed safe).
- **Plaintext password in the guest.** While the desktop runs, the password
  exists in `~/.vnc/linexa-vnc-passwd` and in memory. A RootFS export asks the
  user first and removes that file by default (together with SSH private keys
  and GnuPG); choosing "export as-is" packs it verbatim.
- **Termux:X11 depends on an external app.** If the app or helper is missing,
  the X11 backend falls back to the Xvnc provider; neither is bundled.
- **WebView content is user-supplied.** The app restricts the origin and
  disables file access, but the guest web server's own authentication and
  behavior are outside Linexa's control.

---

## 10. Related documentation

- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot daemon launching
- [`features/SECURITY_AND_PERMISSIONS.md`](./SECURITY_AND_PERMISSIONS.md) — VNC password and backup rules
- [`features/NETWORKING.md`](./NETWORKING.md) — loopback and guest networking
- [`engineering/PERFORMANCE.md`](../engineering/PERFORMANCE.md) — rendering and input cost
