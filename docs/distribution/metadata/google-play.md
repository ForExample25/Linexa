# Google Play Listing

**Status:** `BLOCKED` — targetSdk 34 (Play requires 36 for new apps), no AAB,
no Play Console account, `MANAGE_EXTERNAL_STORAGE` declaration unresolved.
**Portal:** https://play.google.com/console/

---

## Store listing fields

### App name (max 30)

```
Linexa — Linux Workspaces
```

25 characters.

### Short description (max 80)

```
Run Linux workspaces on Android with a terminal, files, and desktop.
```

68 characters.

### Full description (max 4000)

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

Linexa is a tool for developers, students, and anyone who wants a Linux environment on their phone or tablet.
```

### Category and tags

| Field | Value |
|---|---|
| App category | Tools |
| Tags | Linux, terminal, developer tools, PRoot, workspace |
| Contact email | **Owner must supply** (Play requires a monitored email) |
| Website | https://forexample25.github.io/Linexa/ |
| Privacy policy | https://forexample25.github.io/Linexa/privacy.html |
| Support | https://github.com/ForExample25/Linexa/issues |

### Graphics (prepared)

| Asset | Spec | Path |
|---|---|---|
| App icon | 512×512 PNG, 32-bit | `assets/store-icon/icon-512.png` |
| Feature graphic | 1024×500 PNG | `assets/feature-graphic/feature-graphic-1024x500.png` |
| Phone screenshots | 2–8, 1080×1920 (9:16) | `assets/screenshots/phone/01-home.png` … `08-file-manager.png` |
| Tablet screenshots | Optional | **Missing** — do not fabricate |
| Promo video | Optional | None |

The original screenshots are 640×1370 (ratio 2.14:1), which exceeds Play's
2:1 limit. The prepared 1080×1920 versions pad them onto a matching background
without altering the UI.

---

## App content declarations

| Section | Draft |
|---|---|
| Privacy policy | `policies/PRIVACY_POLICY.md`; host before submitting |
| Data safety | `policies/DATA_SAFETY_ANSWERS.md` — no data collected/shared |
| Ads | No ads |
| Content rating | `policies/CONTENT_RATING_NOTES.md` — likely Everyone/PEGI 3 |
| Target audience | General users; not designed for families |
| News app | No |
| COVID-19 apps | No |
| Data deletion | No account; local data deletion only |
| Government app | No |
| Financial features | No |
| Health | No |
| Permissions | `policies/PERMISSION_DECLARATIONS.md` |

---

## Release requirements

| Requirement | Current state |
|---|---|
| targetSdk 36 | **Fails** (34). Requires AGP/compileSdk upgrade + testing |
| AAB | **Missing** |
| Version code 1 | Present |
| 64-bit support | Present (arm64-v8a) |
| Signature | Valid v2 |
| App access instructions | No login required; reviewers can install a distro and open a terminal |
| Closed test (personal accounts) | Not started; 12 testers × 14 days |
| Production access | Not applied |

### Reviewer instructions (draft)

```
No account is required.

1. Open the app. Tap "Add a workspace".
2. Choose a distribution (Alpine is the smallest) and start the download.
3. Wait for installation to finish, then open the workspace.
4. The Terminal tab opens a PTY shell. Run `uname -a` or `cat /etc/os-release`.
5. Optional: use the file manager and package manager tabs.
6. Optional desktop: Debian/Ubuntu workspaces offer a guided Xvnc installer;
   VNC mode needs Xvnc inside the guest.

The app uses PRoot, not root. It does not require Google Play Services.
```

---

## Submission sequence (after the blockers are cleared)

1. Register the Play Console account and pay the US$25 fee.
2. Create the app; package `com.fem.linexa`, default language English (US).
3. Upload the signed AAB to internal testing; verify install and review notes.
4. Complete App content: privacy policy, data safety, content rating, ads,
   target audience, permissions declarations.
5. Upload the store listing and graphics.
6. Promote to closed testing with at least 12 testers for 14 continuous days.
7. Apply for production access; answer the testing and readiness questions.
8. Roll out to production after approval.

Do not claim the app is published until the Play listing is publicly visible.
