# Aptoide Listing

**Status:** `WAITING_FOR_USER` — Aptoide Connect account and publisher review.
**Portal:** https://www.aptoide.com/developers
**Console:** https://connect.aptoide.com/
**Docs:** https://docs.connect.aptoide.com/docs/about-us

---

## Listing fields

| Field | Value |
|---|---|
| App name | `Linexa — Linux Workspaces` |
| Package | `com.fem.linexa` |
| Category | Tools |
| Price | Free |
| In-app purchases | None |
| Privacy policy | https://forexample25.github.io/Linexa/privacy.html |
| Support | https://github.com/ForExample25/Linexa/issues |
| Website | https://forexample25.github.io/Linexa/ |
| Content rating | Everyone / PEGI 3 (confirm in the console) |

### Short description

```
Run Linux workspaces on Android with a terminal, files, and desktop.
```

68 characters.

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

### Tags

```
Linux, terminal, PRoot, workspace, developer tools, rootfs
```

---

## Graphics

| Asset | Spec | Path |
|---|---|---|
| Icon | 512×512 PNG | `assets/store-icon/icon-512.png` |
| Feature graphic | 1024×500 PNG | `assets/feature-graphic/feature-graphic-1024x500.png` |
| Screenshots | 1080×1920, 8 images | `assets/screenshots/phone/` |
| Promo video | None | Optional |

Aptoide's public console does not publish a strict screenshot-size table.
The 9:16 phone set is the safest default. If the upload form rejects portrait
images, use the Amazon landscape set as a fallback and keep the UI unmodified.

---

## Technical review notes

| Field | Value |
|---|---|
| Artifact | `app-release.apk` |
| Size | 57,257,408 bytes |
| SHA-256 | `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` |
| Version | `0.1.0-beta` (1) |
| minSdk / targetSdk | 28 / 34 |
| ABIs | arm64-v8a, armeabi-v7a |
| Signature | APK Signature Scheme v2, valid |
| Debuggable | No |

Aptoide accepts APKs and, depending on the publisher flow, app bundles. Upload
the verified APK; do not generate a new signing key.

### Policy risk to disclose honestly

Linexa downloads Linux root filesystems from public mirrors when the user taps
Download, and can execute software inside the guest. This is the product's core
function, not a hidden updater:

- No self-update mechanism.
- No `REQUEST_INSTALL_PACKAGES`.
- No silent executable download.
- Every rootfs download is user-initiated and goes to an official distribution
  mirror or a mirror the user configured.
- Checksums are verified where the catalog provides them.

If Aptoide review questions the runtime-download behaviour, point to
`policies/PERMISSION_DECLARATIONS.md` and this section. Do not describe the app
as a "downloader" or "emulator"; it is a Linux workspace manager that uses
PRoot.

---

## Submission steps

1. Create an Aptoide Connect account at https://connect.aptoide.com/ using the
   owner's email. Verify the email.
2. Complete any publisher/identity fields the console requests.
3. Add the app: package `com.fem.linexa`, name `Linexa — Linux Workspaces`.
4. Upload `app-release.apk`; wait for the console's package/version scan.
5. Enter the listing text, category, tags, privacy URL, and support URL.
6. Upload the icon, feature graphic, and screenshots.
7. Complete the content rating and permission questions.
8. Submit for review; record the submission ID and any rejection text.
9. After approval, verify the public Aptoide app page and record its URL in
   `../PUBLICATION_STATUS.md`.

Aptoide may distribute the same submission to partner stores/OEMs. That is an
additional reach benefit, but do not claim partner-store publication until it
is visible; the partner's own review may apply.

Do not claim publication until the public Aptoide page resolves.
