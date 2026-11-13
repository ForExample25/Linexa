# Desktop and VNC

**Status:** Current — describes the implemented desktop/VNC feature, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/ui/screens/desktop/`, `app/src/main/java/com/fem/linexa/service/runtime/features/DesktopRunMode.kt`, `GuiBackend.kt`, `XvncProvisioner.kt`, `DesktopViewModel.kt`, `DesktopGeometry.kt`

---

## 1. Purpose

This document is the feature-level reference for Linexa's desktop/VNC feature: run modes (VNC, WebView), backends (X11, VNC), presets (XFCE/LXQt/Openbox), the hand-written RFB client, VNC authentication, geometry, and input handling.

Deep PRoot daemon launching is covered in [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md).

---

## 2. Scope

In scope:

- Desktop run modes (VNC, WebView) and backends (X11, VNC)
- Desktop presets (XFCE, LXQt, Openbox)
- VNC client (RFB 3.8, CopyRect + Raw, optional ZRLE, VNC-Auth)
- VNC authentication (`-rfbauth`, passwd file, loopback binding)
- Desktop geometry and input (touch, gestures, wheel)
- Web mode (guest web server + WebView, loopback-only cleartext)
- Known regressions/contributors (receive loop on Main dispatcher, dirty-region acknowledge degradation)

Out of scope:

- PRoot daemon startup (`startDaemon`): [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md)
- VNC password security model: [`features/SECURITY_AND_PERMISSIONS.md`](./SECURITY_AND_PERMISSIONS.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| DesktopRunMode enum (VNC, WEBVIEW) | Implemented | `service/runtime/features/DesktopRunMode.kt` |
| GuiBackend enum (X11, VNC) | Implemented | `service/runtime/features/GuiBackend.kt` |
| VNC mode with hand-written RFB client | Implemented | `ui/screens/desktop/VncClient.kt`, `VncZrle.kt`, `VncZlibStream.kt` |
| WebView mode (guest web server + WebView) | Implemented | `ui/screens/desktop/DesktopWebPanel.kt` |
| Desktop presets (XFCE/LXQt/Openbox) | Implemented | `DesktopPreset.kt` |
| Xvnc provisioning via PackageManagerDetector | Implemented | `XvncProvisioner.kt` |
| VNC auth (`-rfbauth`, `vncpasswd -f`, passwd file chmod 600) | Implemented | `DesktopVncAuthProvisioner.kt` |
| Loopback binding `-localhost yes` (TigerVNC ≥ 1.11) | Implemented, conditional | `DesktopGeometry.kt` |
| `DesktopSize` pseudo-encoding / resize | Not implemented (fixed for session) | verified absence in source |
| Cursor pseudo-encoding (239) | Not implemented | verified absence in source |

---

## 4. Technical overview

### 4.1 Run modes

Each workspace selects its desktop run mode in **Features → Desktop**. The mode is a per-workspace setting, persisted with the workspace.

**VNC mode:**
1. The workspace session must be Running (the desktop runs inside the workspace's PRoot container).
2. A loopback port is allocated per workspace (preferring 5901, scanning forward) by `DesktopPortRegistry`, and a display number by `X11SessionManager`.
3. If the user configured a VNC password, it is provisioned inside the guest via `vncpasswd -f` before the server starts. If provisioning fails, the desktop is **not** started unauthenticated — the start fails with an explanatory message (fail-closed). Xvnc always binds `-localhost yes` (when supported), so the server is reachable only from the device itself.
4. The app waits for the real VNC port to answer before claiming `Running`.
5. The framebuffer is streamed by the in-app RFB 3.8 client (`VncClient`/`VncSurfaceView`): CopyRect + Raw encodings, optional ZRLE, optional VNC-Auth. Wire-controlled allocations (framebuffer size, desktop name, ZRLE inflate output) are bounded; a mid-session guest resolution change is handled via the DesktopSize pseudo-encoding.

**Web mode:**
Runs a user-configured guest web server (webtop, noVNC, code-server, …) and renders it in a fullscreen in-app WebView:
- Cleartext HTTP is allowed for loopback origins only (`network_security_config.xml`).
- The WebView pins navigation to the exact loopback origin it was opened with; file/content access and file-URL access are explicitly disabled; the WebView is destroyed when the panel goes away.
- Share-to-LAN exposes the URL string (not the service) to other devices on the network — the guest server itself decides what it binds.

Web mode needs no X client inside the guest; VNC mode requires an `Xvnc` binary in the guest rootfs (the desktop presets install TigerVNC).

### 4.2 Backends

The desktop screen supports two transport backends, selected per workspace:

- **X11 (preferred)** — the guest session runs against an X server made reachable to the Android-side viewer (Termux:X11 helper app when installed, or an in-workspace Xvnc provider). The backend is probed at start; a probe failure falls back explicitly.
- **VNC (fallback)** — the RFB streaming path described above.

Both backends share the per-workspace port/display allocation (`DesktopPortRegistry`, `X11SessionManager`), stop their daemons with a SIGTERM→SIGKILL escalation plus a guest-tree sweep, and clear stale sockets before allocation.

### 4.3 Presets

Installable desktop presets — **XFCE**, **LXQt**, **Openbox** — are provisioned through the guest's own package manager (the same command the Package Manager screen would run, executed via the streaming exec path with the 1800 s operation budget). Preset presence is detected before install; an already-present preset is skipped. After installation the workspace's declared session launcher is probed and used for subsequent starts.

### 4.4 VNC client architecture

`VncClient` is constructed in `DesktopScreen.kt` with `rememberCoroutineScope()`, whose dispatcher is `AndroidUiDispatcher.Main`. The RFB receive loop runs in that scope; each message body reads on `Dispatchers.IO` via `withContext`. Every RFB message therefore costs two dispatches and one Main-thread turn. Pointer/key/wheel writes also `scope.launch { withContext(Dispatchers.IO) { … } }`.

The Raw decode path allocates three arrays per rectangle (`ByteArray(w*h*4)`, `IntArray(w*h)`, blit into framebuffer) and copies each pixel twice before it reaches a bitmap. ZRLE decoder is kept but deliberately unadvertised on loopback (`SetEncodings` advertises only COPY_RECT + RAW).

The dirty-region acknowledge/publish handshake degrades to full-frame uploads under sustained load: `VncDirtyRegion.acknowledge` clears the union only when the acknowledged generation is still the most recently published one; under a sustained update stream the union grows toward full-screen.

No cursor pseudo-encoding is negotiated; cursor latency equals framebuffer latency.

### 4.5 Geometry

`xServerCommand()` reads `Application.resources.displayMetrics` at `startDesktop` time — before the Desktop requests landscape and hides the system bars. Any discrepancy between the metrics observed then and the fullscreen landscape window becomes a permanent letterbox via the viewer's contain-scale. The framebuffer geometry is fixed for the session; no client-driven resize path exists.

### 4.6 Known regression findings

| Finding | Class | Summary |
|---|---|---|
| RFB receive loop driven by Compose UI dispatcher | HIGH-CONFIDENCE CONTRIBUTOR | Two dispatches + one Main turn per message; rate coupled to Main-thread availability |
| Dirty-region acknowledge degrades to full-frame uploads | HIGH-CONFIDENCE CONTRIBUTOR | Union grows under sustained load; ghosting |
| Frame geometry measured before fullscreen-landscape | POSSIBLE CONTRIBUTOR | Letterbox if metrics differ from final window |
| `tcpNoDelay = true` | Harmless, near-irrelevant | Delay is before the socket, not in it |
| Choreographer hop removed | Correct | Removes up to one display frame per update |

---

## 5. Limitations and known issues

- No cursor pseudo-encoding; cursor cannot be separated from frame latency.
- Framebuffer geometry immutable after Xvnc start; no resize support.
- VNC password encrypted at rest (AES-GCM/Keystore, legacy rows migrated at startup), but plaintext in guest `~/.vnc/linexa-vnc-passwd` while running and in a RootFS export; loopback-only on Xvnc versions supporting `-localhost`, and on older Xvnc the loopback restriction may be omitted — security exposure.
- `pacman -Sy <pkg>` (partial upgrade, unsupported on Arch) issued in five provisioning paths (Xvnc install, sshd, XFCE/LXQt/Openbox presets).
- desktop run modes still needs on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — PRoot daemon launching (`startDaemon`)
- [`features/SECURITY_AND_PERMISSIONS.md`](./SECURITY_AND_PERMISSIONS.md) — VNC password, backup rules, security model
- [`features/NETWORKING.md`](./NETWORKING.md) — network configuration relevant to VNC loopback
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
