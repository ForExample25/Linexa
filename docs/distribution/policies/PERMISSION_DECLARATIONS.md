# Permission Declarations — Draft

**Prepared:** 2026-11-15
**Applies to:** Google Play Console permission declarations and the equivalent
questions on other stores.

---

## 1. `MANAGE_EXTERNAL_STORAGE` (All files access)

**Current state:** declared in the manifest, optional at runtime, not required
for normal operation. The app already has a Storage Access Framework path.

**Recommended action:** remove the permission and the "All files access"
settings row before Play submission. A Linux workspace manager is not one of
Google's listed eligible categories (file manager, backup/restore, antivirus,
document management, on-device search, encryption, device migration), and a
declaration is likely to be rejected. Removing it does not break the app's core
workflow because SAF remains available.

If the owner chooses to keep it, the declaration would have to say:

> Linexa's file manager lets users browse, edit, import, and export files across
> a user-created Linux root filesystem and shared storage. The permission is
> requested only when the user enables all-files access in workspace settings.
> SAF is used by default and remains available when the permission is not
> granted.

This is a weaker case. Do not submit it unless the owner accepts the rejection
risk.

## 2. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

**Suggested declaration:**

> Linexa runs a user-created Linux workspace as a long-running foreground
> service (terminal sessions, package operations, and an optional desktop).
> Android may stop the workspace when the device enters Doze. The app asks the
> user to open the system battery-optimisation screen only when the user chooses
> "Disable battery optimisation" in workspace settings. It does not request the
> exemption automatically and does not use it for advertising or background
> data collection.

The request is user-initiated in `AccessSettingsGroup`. It is tied to the core
runtime function.

## 3. `FOREGROUND_SERVICE_SPECIAL_USE`

**Subtype declared:** `workspace-runtime`

**Suggested declaration:**

> Linexa starts a foreground service only when the user starts a Linux
> workspace. The service hosts the PRoot session, terminal I/O, package
> operations, and the optional desktop connection so work continues when the
> user switches apps. It stops when the user stops the workspace or the app
> tears the session down. A notification is always shown while it runs.

`DownloadService` and `ImportService` use `dataSync` for user-initiated
downloads/imports.

## 4. `POST_NOTIFICATIONS`

Used for runtime and download status. Requested at runtime on Android 13+.
No marketing notifications exist.

## 5. Permissions deliberately absent

- No location (fine/coarse/background)
- No camera, microphone, contacts, SMS, call log, body sensors
- No `QUERY_ALL_PACKAGES`
- No `REQUEST_INSTALL_PACKAGES`
- No `SYSTEM_ALERT_WINDOW`
- No accessibility service
- No VPN service
- No advertising ID permission

## 6. Store-specific notes

| Store | Declaration needed |
|---|---|
| Google Play | All-files access, battery optimisation, FGS special use, Data safety |
| Samsung | Sensitive-permission review; explain optional all-files access |
| Huawei | Permission description in AppGallery Connect; privacy policy |
| Xiaomi | Permission description in the listing; privacy policy |
| Amazon | Privacy labels and permission rationale |
| Aptoide / Uptodown / APKPure | Permission rationale if prompted |
| F-Droid | Anti-feature review; explain runtime downloads and all-files access |
| IzzyOnDroid | Sensitive-permission clarification; all-files access is highlighted |
| Accrescent | Manual review; `MANAGE_EXTERNAL_STORAGE` only allowed for file management |
