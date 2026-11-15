# Huawei AppGallery Listing

**Status:** `WAITING_FOR_USER` — HUAWEI ID + developer verification.
**Portal:** https://developer.huawei.com/consumer/en/appgallery/
**Release docs:** https://developer.huawei.com/consumer/en/doc/app/agc-help-releaseapp-0000001146718717

---

## Listing fields

| Field | Value |
|---|---|
| App name | `Linexa — Linux Workspaces` |
| Category | Tools |
| Price | Free |
| Privacy policy | https://forexample25.github.io/Linexa/privacy.html |
| Support | https://github.com/ForExample25/Linexa/issues |
| Website | https://forexample25.github.io/Linexa/ |
| Age rating | 3+ / Everyone (confirm in AppGallery Connect) |

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
| App icon | 216×216 PNG (also 512×512) | `assets/store-icon/icon-216.png`, `icon-512.png` |
| Screenshots | 1080×1920 | `assets/screenshots/phone/` |
| Feature graphic | 1024×500 | `assets/feature-graphic/feature-graphic-1024x500.png` |

---

## Technical data

| Field | Value |
|---|---|
| Package | `com.fem.linexa` |
| Version | `0.1.0-beta` (1) |
| Artifact | signed APK |
| minSdk / targetSdk | 28 / 34 |
| ABIs | arm64-v8a, armeabi-v7a |
| GMS dependency | None |
| HMS dependency | None |

AppGallery accepts APKs through AppGallery Connect. Confirm current AAB support
and any target-SDK requirement in the console at upload time. The app does not
depend on Google Play Services, which is favourable for Huawei devices.

---

## Submission steps

1. Sign in with a HUAWEI ID and register as a developer.
2. Complete identity verification.
3. Create the app in AppGallery Connect; select "App" and the Android platform.
4. Enter the listing, upload the APK, icon, screenshots, and privacy policy URL.
5. Complete the content rating, permissions, and data-safety questions.
6. Submit for review; record the review ID and any rejection text.

No fee is published for app registration.
