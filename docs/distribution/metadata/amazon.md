# Amazon Appstore Listing

**Status:** `WAITING_FOR_USER` — free Amazon developer account, identity/tax
details, and a signed-in submission session.
**Portal:** https://developer.amazon.com/apps-and-games
**Submission docs:** https://developer.amazon.com/docs/app-submission/understanding-submission.html
**Details form:** https://developer.amazon.com/docs/app-submission/appstore-details.html

---

## Listing fields

| Field | Value |
|---|---|
| Display title | `Linexa — Linux Workspaces` |
| Package | `com.fem.linexa` |
| Category | Tools / Utilities |
| Price | Free |
| In-app purchases | None |
| Privacy policy | https://forexample25.github.io/Linexa/privacy.html |
| Support | https://github.com/ForExample25/Linexa/issues |
| Website | https://forexample25.github.io/Linexa/ |
| Content rating | Everyone (confirm in Amazon's questionnaire) |

### Short description (max 1,200 English characters / 2,000 bytes)

```
Run Linux workspaces on Android with a terminal, files, and desktop.
```

68 characters. Amazon displays roughly the first 200 characters on Fire TV;
the important information is first.

### Long description (max 4,000 characters)

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

### Product feature bullets (3–5)

```
Multiple isolated Linux workspaces
Full PTY terminal with extra keys
RootFS installer and package manager
VNC and local web desktop modes
No root, no account, no analytics
```

### Keywords

```
Linux, terminal, PRoot, workspace, developer tools, Alpine, Ubuntu, Debian, Arch, Fedora
```

---

## Graphics

Amazon requires **landscape** tablet screenshots. The phone screenshots used
for Play/Samsung/Huawei/Xiaomi are portrait 1080×1920 and are not accepted by
Amazon's documented size list (800×480, 1024×600, 1280×720, 1280×800,
1920×1080, 1920×1200, 2560×1600). Prepared Amazon variants are at:

`assets/screenshots/amazon-landscape/01-home-1920x1080.png` … `08-file-manager-1920x1080.png`

They place the unmodified app screen on a branded 1920×1080 canvas; no UI is
invented or altered.

| Asset | Spec | Path |
|---|---|---|
| Small icon | 114×114 PNG | `assets/store-icon/icon-114.png` |
| Large icon | 512×512 PNG | `assets/store-icon/icon-512.png` |
| Screenshots | 8 × 1920×1080 PNG | `assets/screenshots/amazon-landscape/` |
| Promotional image | 1024×500 PNG | `assets/feature-graphic/feature-graphic-1024x500.png` |
| Video | None | Not required |

---

## Technical review notes

- Upload `app-release.apk` (v2-signed, zipaligned, 57,257,408 bytes).
- Amazon **removes the developer signature and re-signs** the app with an
  Amazon account signature, and adds an Amazon wrapper for analytics and policy
  enforcement. This is documented in "Amazon app signatures" and "Amazon's code
  wrapper".
- The APK ships matching `arm64-v8a` and `armeabi-v7a` library sets (22 native
  files each), which satisfies Amazon's 64-bit parity guidance.
- Binary limit is 2.5 GB; 57 MB is well under it. Amazon warns above 50 MB but
  does not reject.
- No Amazon Appstore SDK, DRM, or IAP integration. Select "No" for automatic
  DRM unless the owner decides otherwise.
- `targetSdk 34` is accepted by Amazon's documented upload flow; confirm at
  upload if the console adds a new gate.

### Privacy labels

Amazon requires Appstore Privacy Labels. Draft:

| Question | Answer |
|---|---|
| Does the app collect personal information? | No |
| Does the app share personal information? | No |
| Does the app use analytics? | No |
| Does the app display ads? | No |
| Does the app allow account creation? | No |
| Does the app use location? | No |
| Does the app use the advertising ID? | No |
| Data linked to the user | None |
| Data not linked to the user | None |

The app downloads Linux root filesystems from public mirrors at the user's
request. That request exposes the user's IP address to the mirror, as any
download does; Linexa does not receive or store it. Disclose the download
behaviour if the form has a free-text field.

### IP / third-party software

The APK bundles PRoot, BusyBox, GNU tar, tmux, zstd, and Termux terminal
modules under free-software licences. `THIRD_PARTY_NOTICES.md` contains the
notices and a corresponding-source offer. If Amazon requests IP documentation,
provide the licence texts and the source URLs from that file. Do not claim
ownership of third-party components.

---

## Submission steps

1. Sign in with the owner's Amazon customer account at
   https://developer.amazon.com/apps-and-games and create the free developer
   account.
2. Complete identity/tax information if the console requests it. Do not invent
   a legal name or address.
3. Create the app; package `com.fem.linexa`, title `Linexa — Linux Workspaces`.
4. Upload `app-release.apk`; confirm the console accepts the target SDK and the
   hybrid 32/64-bit library set.
5. Select the free price, category, and content rating.
6. Enter the descriptions, bullets, keywords, icons, landscape screenshots, and
   promotional image.
7. Complete the Appstore Privacy Labels.
8. Add reviewer instructions from `metadata/google-play.md` (the same workflow
   applies; no account is required).
9. Submit and record the submission ID and any rejection text verbatim.

Do not claim publication until a public Amazon Appstore product URL exists.
