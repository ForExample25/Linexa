# APKPure Listing

**Status:** `WAITING_FOR_USER` — developer account; the portal is protected by
a Cloudflare challenge and must be completed by a human in a normal browser.
**Portal:** https://apkpure.com/developer
**Main site:** https://apkpure.com/

---

## Important access note

The APKPure developer portal returned **HTTP 403** to non-browser clients and a
Cloudflare "Just a moment..." challenge in a browser during the audit.

- Do **not** attempt to bypass the challenge, use a solver, rotate proxies, or
  scrape the console.
- The owner must open the portal in a normal browser, complete the challenge,
  and sign in.
- After that, the listing can be entered manually with the fields below.

---

## Listing fields

| Field | Value |
|---|---|
| App name | `Linexa — Linux Workspaces` |
| Package | `com.fem.linexa` |
| Category | Tools |
| Price | Free |
| Privacy policy | https://forexample25.github.io/Linexa/privacy.html |
| Support | https://github.com/ForExample25/Linexa/issues |
| Website | https://forexample25.github.io/Linexa/ |
| Developer | Linexa project |

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

### Tags

```
Linux, terminal, PRoot, workspace, developer tools
```

---

## Graphics

| Asset | Spec | Path |
|---|---|---|
| Icon | 512×512 PNG | `assets/store-icon/icon-512.png` |
| Feature graphic | 1024×500 PNG | `assets/feature-graphic/feature-graphic-1024x500.png` |
| Screenshots | 1080×1920, 8 images | `assets/screenshots/phone/` |

APKPure's developer console may request additional resolutions. Use the
prepared phone set first; if it rejects portrait images, use the Amazon
landscape set. Do not invent tablet screenshots.

---

## Technical data

| Field | Value |
|---|---|
| Artifact | `app-release.apk` |
| Size | 57,257,408 bytes |
| SHA-256 | `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` |
| Version | `0.1.0-beta` (1) |
| minSdk / targetSdk | 28 / 34 |
| ABIs | arm64-v8a, armeabi-v7a |
| Signature | v2, valid |
| Debuggable | No |

APKPure mirrors APKs; an official developer account lets the owner claim the
package and publish the canonical build. After publication, verify that the
APKPure-hosted APK's SHA-256 matches the GitHub Release asset. If APKPure wraps
or re-signs the binary, record the new certificate fingerprint and do not
present it as the original signing key.

---

## Submission steps

1. In a normal browser, open https://apkpure.com/developer and complete the
   Cloudflare challenge manually.
2. Create/sign in to the developer account with the owner's email; verify it.
3. Claim the package `com.fem.linexa` or create a new app record. Provide proof
   of ownership if asked (public repository, release URL, signing certificate
   fingerprint).
4. Upload `app-release.apk`.
5. Enter the listing text, category, tags, privacy URL, support URL, icon,
   feature graphic, and screenshots.
6. Complete the content/permission questions.
7. Submit for review; record the submission ID and any rejection text.
8. After approval, verify the public page and the hosted APK hash.

Do not automate the Cloudflare challenge and do not claim publication until the
public APKPure page resolves.
