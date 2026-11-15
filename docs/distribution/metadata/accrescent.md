# Accrescent Listing

**Status:** `NOT_ELIGIBLE` — targetSdk 34 fails Accrescent's Play-derived
target-SDK requirement (36), and the store is still in alpha.
**Site:** https://accrescent.app/
**Requirements:** https://accrescent.app/docs/guide/appendix/requirements/
**Developer console:** https://console.accrescent.app/

---

## Eligibility review

Accrescent follows Google Play's target-SDK requirements without extension
requests and removes non-compliant apps rather than merely hiding them. The
current `0.1.0-beta` APK targets **34**, so it is not eligible.

| Requirement | Current state |
|---|---|
| Target SDK follows Play (currently 36) | **Fails** — 34 |
| No debug certificate | Met |
| Single signer | Met |
| APK Signature Scheme v2/v3 | Met — v2 |
| No `REQUEST_INSTALL_PACKAGES` self-updater | Met — permission absent |
| Native ABI parity | Met — arm64-v8a and armeabi-v7a sets |
| ≤1 GiB | Met — 57.3 MB |
| `MANAGE_EXTERNAL_STORAGE` only for file-management use cases | **Risk** — optional, but Linexa is a Linux runtime, not primarily a file manager |
| Active maintenance | Beta release; confirm the console's activity threshold |
| Developer account | Missing; console access/alpha availability not verified |

Accrescent's alpha status also means the developer console may be limited or
invite-only. Do not assume an account can be created on demand.

---

## Listing fields (for later)

| Field | Value |
|---|---|
| App name | `Linexa — Linux Workspaces` |
| Package | `com.fem.linexa` |
| Category | Tools |
| Price | Free |
| License | GPL-3.0-only |
| Privacy policy | https://forexample25.github.io/Linexa/privacy.html |
| Source | https://github.com/ForExample25/Linexa |
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
| Feature graphic | 1024×500 PNG | `assets/feature-graphic/feature-graphic-1024x500.png` |
| Screenshots | 1080×1920, 8 images | `assets/screenshots/phone/` |

Accrescent's upload form controls the final asset requirements. Use the
unmodified prepared assets; do not add store badges or fake ratings.

---

## What must change before applying

1. **Target SDK 36.** This requires an AGP/compileSdk upgrade and testing.
   `AGENTS.md` currently says to keep the Gradle/AGP/SDK versions unchanged;
   the owner must explicitly approve the upgrade. It is not a metadata change.
2. **All-files access.** Remove `MANAGE_EXTERNAL_STORAGE` and the optional
   settings path, or be prepared to justify it as a file-management feature.
   Accrescent manually reviews this permission and may reject the app.
3. **Signing.** Keep the existing release key. Do not create a new one.
4. **Build artifact.** Produce a signed APK from CI and publish its SHA-256.
5. **Account.** Apply through the developer console; complete any identity
   verification. The alpha program may not be open.

No targetSdk 36 build exists today. Do not submit the 34 build and hope for a
waiver; Accrescent's policy says it removes non-compliant apps.

---

## After eligibility is resolved

1. Create the app in the Accrescent Developer Console.
2. Upload the signed APK; confirm the single-signer, v2/v3, and ABI checks.
3. Enter the listing and privacy URL.
4. Complete the security/privacy review; explain the PRoot runtime and the
   optional all-files access.
5. Submit; record the review result.
6. Do not claim publication until the public Accrescent page resolves.
