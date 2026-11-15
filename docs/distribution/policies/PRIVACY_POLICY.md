# Linexa Privacy Policy

**Effective date:** 2026-11-15
**Applies to:** the Linexa Android application (`com.fem.linexa`) and the
project's public download pages.

Linexa is an open-source project maintained by the Linexa project
("Linexa", "we", "us"). This policy explains what the app does with data. It is
written to match the behaviour of the published `0.1.0-beta` build.

---

## 1. Summary

- Linexa does **not** require an account.
- Linexa does **not** include analytics, advertising, crash reporting, or
  tracking SDKs.
- Workspace data stays on your device.
- The app contacts third-party Linux distribution mirrors only when you choose
  to download a distribution, or when software inside your Linux workspace
  accesses the network.
- We do not sell data. We do not receive your workspace data.

---

## 2. Data stored on your device

Linexa stores the following locally in the app's private storage:

- Workspace names, distribution metadata, and settings.
- The Linux root filesystem and files you create inside each workspace.
- Terminal sessions, command history, task/process state, and app logs.
- Preferences and, if you enable desktop mode, desktop connection settings.
- A desktop VNC password, if you set one. It is encrypted with a key held by the
  Android Keystore before being written to the workspace database.

This data is not uploaded to us. Android cloud backup and device transfer are
explicitly configured to exclude the workspace database, workspace files, logs,
bootstrap data, and settings preferences.

You can delete workspace data inside the app. Clearing the app's storage or
uninstalling the app removes the rest, subject to Android's normal backup
behaviour for non-excluded preferences.

---

## 3. Network access

Linexa requests the `INTERNET` permission. Network access happens in these
cases:

1. **Downloading a Linux distribution.** When you tap download, the app
   requests a root filesystem archive and, where available, its checksum from
   the distribution's official mirror or a catalog source. Current destinations
   include:
   - `dl-cdn.alpinelinux.org` (Alpine Linux)
   - `cdimage.ubuntu.com` (Ubuntu)
   - `fl.us.mirror.archlinuxarm.org` (Arch Linux ARM)
   - `raw.githubusercontent.com` (Debian rootfs artifacts published by the
     debuerreotype project)
   - `images.linuxcontainers.org` (Linux Containers image server, used for
     Fedora)
2. **A mirror you configure.** You can set a custom rootfs or package mirror.
   The app then contacts the host you chose.
3. **Software inside your workspace.** A Linux workspace has its own network
   stack. Package managers and programs you run can contact repositories and
   services you or they choose. This traffic is controlled by that software,
   not by Linexa.
4. **Desktop Web mode.** The in-app WebView loads `http://127.0.0.1:<port>`, a
   loopback address served by software inside your workspace. It does not load
   arbitrary remote websites.
5. **Help and licence links.** Tapping a help or attribution link opens an
   external browser or app (for example, Telegram). The destination site's
   policy applies.

When the app contacts a third-party server, that server necessarily receives
standard request information such as your IP address, user agent, and the
requested URL. Those third parties handle that information under their own
privacy policies. Linexa does not receive, log, or combine it.

---

## 4. Permissions

| Permission | Why it is used | When |
|---|---|---|
| Internet / network state | Download distributions; discover the host DNS configuration | Normal operation |
| Foreground service | Keep a workspace running while you use other apps | When you start a workspace |
| Notifications | Show workspace and download status | Android 13+ asks at runtime |
| All files access (`MANAGE_EXTERNAL_STORAGE`) | Optional file-manager access to shared storage | Only if you enable it; the app works with the Storage Access Framework without it |
| Ignore battery optimizations | Optional, to keep long sessions alive | Only if you enable it |
| Wake lock | Optional, time-limited wake lock while a workspace runs | Only if you enable it |

Linexa does not request location, contacts, camera, microphone, SMS, call logs,
or installed-app inventory. It does not request root.

---

## 5. Analytics, advertising, and third parties

Linexa contains no advertising, analytics, attribution, or crash-reporting SDK.
It does not use Google Play Services, Firebase, Crashlytics, Sentry, or similar
services. It does not create an advertising identifier or share data with data
brokers.

The app bundles third-party free-software components (for example PRoot,
BusyBox, GNU tar, tmux, zstd, and the Termux terminal modules). Their licences
and copyright notices are listed in the app under **Settings → About → Open
source licenses** and in the repository's `THIRD_PARTY_NOTICES.md`. They do not
collect data for us.

---

## 6. Children

Linexa is a general-purpose developer tool and is not directed to children. We
do not knowingly collect personal information from children. Because the app
does not collect personal information at all, no special children's data
process is used.

---

## 7. Security

Workspace data is kept in the app's sandbox. Optional secrets such as a desktop
VNC password are encrypted with Android Keystore-backed AES-GCM. Backups exclude
sensitive workspace data. No method of storage is perfectly secure; protect your
device with a screen lock and keep Android updated.

---

## 8. Your choices

- Use the app without granting optional permissions.
- Delete individual workspaces inside the app.
- Clear app storage in Android settings.
- Uninstall the app.
- Use a custom mirror or no network at all, subject to the features you choose.
- Inspect and build the source yourself: https://github.com/ForExample25/Linexa

---

## 9. Changes

If the app's data behaviour changes, this policy will be updated in the source
repository and on the public download page, with a new effective date. Material
changes will be noted in the release notes.

---

## 10. Contact

Privacy questions and data-behaviour reports:

- Issue tracker: https://github.com/ForExample25/Linexa/issues
- Security reports: use GitHub's private security advisory feature on the same
  repository.

This policy is provided for transparency and does not create rights beyond those
required by applicable law. Linexa is free software licensed under GPL-3.0-only.
