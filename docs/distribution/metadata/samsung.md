# Samsung Galaxy Store Listing

**Status:** `WAITING_FOR_USER` — seller account/commercial seller status; 16KB
page-size risk.
**Portal:** https://seller.samsungapps.com/
**Public overview:** https://developer.samsung.com/galaxy-store

---

## Listing fields

| Field | Value |
|---|---|
| App name | `Linexa — Linux Workspaces` |
| Category | Utility / Tools |
| Age rating | Everyone (confirm in the seller questionnaire) |
| Privacy policy | https://forexample25.github.io/Linexa/privacy.html |
| Support | https://github.com/ForExample25/Linexa/issues |
| Website | https://forexample25.github.io/Linexa/ |
| Price | Free |
| In-app purchases | None |

### Short description

```
Run Linux workspaces on Android with a terminal, files, and desktop.
```

### Full description

```
Linexa brings Linux environments to Android — without root.

Create isolated workspaces, install a Linux root filesystem, open a full PTY terminal, manage files and packages, and connect to a graphical desktop. Each workspace keeps its own files, sessions, and settings.

WHAT YOU CAN DO
• Workspaces — run multiple isolated Linux environments and switch between them.
• RootFS installer — import a .tar.gz, .tar.xz, or .tar.zst archive, or download Alpine, Ubuntu, Arch Linux ARM, Debian, or Fedora from the built-in catalog.
• Real terminal — a persistent PTY with ANSI/cursor rendering, scrollback, text selection, copy/paste, pinch-to-zoom, and an extra-keys row.
• Desktop — connect through the built-in VNC client or a WebView pointed at a guest web server. VNC mode requires Xvnc in the guest; Debian and Ubuntu have a guided installer.
• Package manager — search, install, update, and remove packages through apt, pacman, apk, dnf, or zypper.
• File manager — browse, copy, rename, delete, import, and edit files, with syntax highlighting and a Storage Access Framework provider.
• Sessions and tasks — keep multiple terminal sessions, background long-running work, and inspect or stop workspace processes.
• Networking — per-workspace DNS, /etc/hosts, package mirrors, and a rootfs mirror override. Checksums still come from the official source.
• Local-first — workspace metadata lives in Room on your device. No account, no cloud dependency.

GOOD TO KNOW
• Beta software: interfaces, supported guests, and device behaviour may change.
• Android 9 or newer, on arm64-v8a or armeabi-v7a devices.
• Root is not required. PRoot provides a user-space guest environment.
• Linexa is free software under GPL-3.0. Third-party components keep their own licences; see Settings → About → Open source licenses.
• Source and issue tracker: github.com/ForExample25/Linexa
```

### Graphics

| Asset | Spec | Path |
|---|---|---|
| Icon | 512×512 PNG | `assets/store-icon/icon-512.png` |
| Feature graphic | 1024×500 PNG | `assets/feature-graphic/feature-graphic-1024x500.png` |
| Screenshots | 1080×1920, 2–8 | `assets/screenshots/phone/` |
| Promo video | Optional | None |

---

## Technical review notes

- Upload format: signed APK (`app-release.apk`). Samsung's public materials do
  not document an AAB-only requirement; verify the upload control at submission.
- Package: `com.fem.linexa`, versionCode 1, versionName `0.1.0-beta`.
- targetSdk 34; minSdk 28.
- Native ABIs: arm64-v8a, armeabi-v7a.

### 16KB page-size risk

Samsung's public Galaxy Store overview says: "Starting July 1, 2026, Galaxy
Store is requiring all new and updated Android apps (Android 15 and higher) to
support 16KB page size."

Verified with `readelf -lW`:

- `libproot.so`, BusyBox, tar, tmux, zstd, and their shared libraries are
  0x4000-aligned on arm64.
- **`libtermux.so` is 0x1000-aligned.**
- `libzstd-jni` is 0x10000-aligned.

`libtermux.so` must be rebuilt with 16KB-compatible LOAD alignment before
submission if Samsung applies the requirement to this app. Confirm the precise
scope (target SDK vs device OS) in Seller Portal. Rebuilding the JNI library is
a code change that requires owner approval.

### Permissions to explain

- Optional all-files access for the file manager (SAF also works).
- Optional battery-optimisation exemption for long-running workspaces.
- Foreground service for the workspace runtime.
- Notifications for runtime/download status.
- No location, contacts, camera, microphone, or ads permissions.

---

## Submission steps

1. Sign in with a Samsung account at https://seller.samsungapps.com/.
2. Register as a seller and apply for commercial seller status; provide the
   requested business/tax information.
3. Create the app, enter the listing fields above, upload the APK, add the
   graphics.
4. Complete the age rating and permission declarations.
5. Submit for review; record the review ID and any rejection text.
6. Do not claim publication until a public Galaxy Store URL exists.
