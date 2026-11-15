# Xiaomi GetApps Listing

**Status:** `WAITING_FOR_USER` — Mi Developer account + OTP.
**Portal:** https://global.developer.mi.com/
**Account:** Xiaomi Account (`account.xiaomi.com`)

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
| Screenshots | 1080×1920 | `assets/screenshots/phone/` |
| Feature graphic | Optional | `assets/feature-graphic/feature-graphic-1024x500.png` |

---

## Technical data

| Field | Value |
|---|---|
| Package | `com.fem.linexa` |
| Version | `0.1.0-beta` (1) |
| Artifact | `app-release.apk` |
| Size | 57,257,408 bytes |
| SHA-256 | `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` |
| minSdk / targetSdk | 28 / 34 |
| ABIs | arm64-v8a, armeabi-v7a |
| Signature | v2, valid |

No target-SDK gate is documented on the global portal for third-party APKs.
Confirm during upload. No GMS dependency.

---

## Submission steps

1. Create/sign in to a Mi Developer account with the owner's email/phone.
2. Complete any identity verification requested by Xiaomi.
3. Create the app, enter the listing, upload the APK.
4. Upload the icon and screenshots; set category, price, privacy URL.
5. Submit for review.
6. Record the review result and any rejection reason verbatim.

Do not create the account with borrowed or invented identity data.
