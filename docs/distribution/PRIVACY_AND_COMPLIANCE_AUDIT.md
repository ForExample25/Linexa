# Privacy and Compliance Audit

**Date:** 2026-11-15
**Scope:** the published `0.1.0-beta` APK and the current public source
**Method:** manifest inspection, dependency inventory, source search for network
and telemetry APIs, and a data-flow review of the download, terminal, desktop,
file, and backup paths.

---

## 1. Executive answer

Linexa has **no developer-operated backend**. It has:

- no user accounts or sign-in;
- no analytics, advertising, attribution, or crash-reporting SDK;
- no Google Play Services, Firebase, Crashlytics, Sentry, or equivalent;
- no cloud sync;
- no remote configuration;
- no collection of contacts, location, camera, microphone, SMS, call logs, or
  installed-app inventory.

Workspace metadata and files stay on the device. Network access is used only
for user-initiated Linux rootfs downloads and for the guest Linux environment's
own package/network activity. The app does contact third-party distribution
mirrors; those servers necessarily see the device's IP address and request
metadata, but the Linexa developer does not receive that data.

**Therefore the correct Google Play Data Safety answer is "no data collected or
shared"**, with the privacy policy disclosing the third-party download
destinations and their inherent request metadata.

This conclusion must be re-checked if any SDK, account feature, sync, or
telemetry is added later.

---

## 2. Verified technical evidence

### 2.1 Dependencies

`app/build.gradle.kts` contains only AndroidX, Compose, Navigation, Room, Hilt,
Kotlin serialization, SnakeYAML, zstd-jni, commons-compress, XZ, and the
vendored Apache-2.0 Termux terminal modules. There is no:

- `com.google.firebase:*`
- `com.google.android.gms:*`
- `com.crashlytics:*`
- advertising/attribution SDK
- analytics SDK

Search for Firebase/Crashlytics/analytics/Sentry/Amplitude/Mixpanel/AppsFlyer/
OneSignal/AdMob in `app/src/main/java` returned no matches.

### 2.2 Network entry points

| Entry point | Destination | Trigger | Data sent |
|---|---|---|---|
| RootFS catalog download | `dl-cdn.alpinelinux.org`, `cdimage.ubuntu.com`, `fl.us.mirror.archlinuxarm.org`, `raw.githubusercontent.com` (debuerreotype), `images.linuxcontainers.org` | User taps download for a distribution | Standard HTTPS request (IP, user agent, requested URL); optional checksum request |
| Custom RootFS mirror | User-supplied URL | User configures it | Same, to the chosen host |
| Package manager | Distribution repositories chosen by the guest/user | User runs package operations | Standard package-manager traffic from inside the guest |
| Guest network | Whatever the user runs in the terminal/desktop | User action | Controlled by the guest software |
| Desktop WebView | `http://127.0.0.1:<port>` only | User opens Web desktop mode | Loopback traffic, no remote origin |
| Help links | `https://t.me/...` | User taps Help | Opens an external app/browser; Linexa sends nothing |
| About/attribution links | Project and licence URLs | User taps a link | Opens an external browser |

No other outbound endpoint was found. The string search covers `http://` and
`https://` literals in Kotlin and XML.

### 2.3 Local data

| Data | Location | Protection |
|---|---|---|
| Workspace metadata (name, distro, settings) | Room database `linexa.db` | App sandbox |
| Desktop VNC password | Workspace attributes, AES-GCM envelope under an Android Keystore key | Encrypted at rest |
| Rootfs and workspace files | App-private files under `workspaces/` | App sandbox |
| Logs | App-private `logs/` | App sandbox; user can view/share manually |
| Preferences | App-private SharedPreferences | App sandbox |
| Exported backups | User-selected SAF destination | User controls; export warns about guest credentials and can strip them |

`backup_rules.xml` and `data_extraction_rules.xml` exclude the Room DB,
workspaces, logs, bootstrap, and settings preferences from cloud backup and
device transfer. `allowBackup` remains true for non-sensitive preferences.

### 2.4 Permissions

| Permission | Necessity | Notes |
|---|---|---|
| `INTERNET` | Core | Rootfs downloads and guest networking |
| `ACCESS_NETWORK_STATE` | Core | Host DNS discovery |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_DATA_SYNC` | Core | Long-running workspace runtime and downloads |
| `POST_NOTIFICATIONS` | Core UX | Runtime/download status; runtime prompt |
| `MANAGE_EXTERNAL_STORAGE` | **Optional** | Only for the opt-in all-files file-manager mode; SAF works without it |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | **Optional** | User-initiated, for long-running sessions |
| `WAKE_LOCK` | **Optional** | User-initiated, time-limited (60 min ceiling) |

No dangerous data permissions (location, contacts, camera, microphone, SMS,
call log, body sensors, activity recognition) are declared. No
`QUERY_ALL_PACKAGES`, no `REQUEST_INSTALL_PACKAGES`, no ad ID.

### 2.5 Desktop WebView

The WebView is restricted to `http://127.0.0.1:<port>` URLs built by the app.
There is no remote URL bar and no arbitrary-origin navigation. Cleartext is
allowed for loopback only via the network security configuration. This is a
local UI surface, not a general browser.

---

## 3. Google Play Data Safety draft

Answers below are based on the verified behaviour. The account owner must review
and submit them.

| Question | Answer | Rationale |
|---|---|---|
| Does your app collect or share any required user data types? | **No** | No developer-operated collection; no SDK collection |
| Data types collected | None | — |
| Data types shared | None | — |
| Is data encrypted in transit? | Not applicable | No collected data |
| Can users request data deletion? | Not applicable | Data is local; users can delete workspaces or clear app data/uninstall |
| Does the app use third-party code/SDKs that collect data? | **No** | No analytics/ads/crash SDKs |
| Is data processed ephemerally? | Not applicable | — |
| Family policy / ads | No ads; not directed to children | Tool for general users |

**Disclosure note for the policy:** contacting a distribution mirror or a
package repository necessarily exposes the device's IP address and request
headers to that third party. This is not data collection by Linexa, but it must
be described accurately.

---

## 4. Permission declarations required by Google Play

### 4.1 `MANAGE_EXTERNAL_STORAGE`

The permission is declared but **not required for normal operation**. The app
already supports SAF. Google's permitted-use list does not include "Linux
workspace manager". Submitting a declaration as a file manager is likely to be
rejected.

**Recommendation (requires owner approval, no code changed):** remove the
permission from `AndroidManifest.xml` and remove the "All files access" settings
row, leaving SAF as the only external-storage path. This eliminates the
declaration and a major rejection risk without losing a core feature.

If the owner prefers to keep it, the declaration must argue that managing files
across a Linux rootfs is file management and that SAF is insufficient. That is
a weaker case and may still fail.

### 4.2 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

The app uses this only when the user opts in to keep a long-running workspace
alive. Play policy requires the request to be tied to core functionality and
not used to evade Doze for non-essential work. The workspace runtime is the core
function, so the declaration is defensible. The in-app flow must remain
user-initiated (it is).

### 4.3 `FOREGROUND_SERVICE_SPECIAL_USE`

The manifest declares the subtype `workspace-runtime`. Play Console requires a
short description of the special use. Suggested text:

> Linexa runs a user-created Linux workspace (terminal, package manager, and
> optional desktop) in a foreground service so it continues while the user
> switches apps. The service starts only when the user starts a workspace and
> stops when the workspace stops.

### 4.4 `POST_NOTIFICATIONS`

Runtime permission on Android 13+; used for workspace and download notifications.

---

## 5. Content rating notes

The app is a developer tool. It contains no violence, sexual content, gambling,
drugs, or user-generated content feed. Users can run arbitrary Linux software
and reach the internet from the guest, which is the product's purpose.

Likely IARC outcome: **Everyone / PEGI 3**, but the questionnaire must be
answered truthfully by the owner. Do not pre-select answers about "unrestricted
internet access" without checking how the questionnaire treats a terminal that
can access the network.

Target audience: general users, not children. Do not opt into "Designed for
Families".

---

## 6. Third-party services and licences

The app bundles GPL/LGPL/Apache/MIT components, including PRoot (GPL-2.0-or-later),
BusyBox (GPL-2.0-only), GNU tar (GPL-3.0-or-later), tmux, zstd, and the Termux
terminal modules (Apache-2.0). `THIRD_PARTY_NOTICES.md` contains the inventory
and a written offer for corresponding source. The app shows the same inventory
under **Settings → About → Open source licenses**.

Store listings must not misrepresent the app as entirely self-authored. The
source repository and notices satisfy the GPL's source-offer requirement for
the published APK.

**Licence consistency:** the public release notes state **GPL-3.0-only**; copies
published before 2026-11-14 were offered as GPL-3.0-or-later. The local
uncommitted README says GPL-3.0-or-later. The public release is authoritative.
If the local README is merged as-is, it will contradict the release notes and
the About screen; align it before the next release.

---

## 7. Privacy policy hosting

A truthful policy is provided at:

- `docs/distribution/policies/PRIVACY_POLICY.md`
- `https://forexample25.github.io/Linexa/privacy.html` (GitHub Pages)

It uses GitHub Issues as the contact channel. Google Play requires a developer
contact **email address** in the store listing; the owner must supply one. No
email address was invented.

---

## 8. Compliance gaps

| Gap | Severity | Action |
|---|---|---|
| targetSdk 34 vs Play requirement 36 | Blocking (Play, Accrescent) | Toolchain upgrade; owner decision |
| `MANAGE_EXTERNAL_STORAGE` declaration | High (Play) | Remove or justify |
| AAB missing | Blocking (Play) | CI `bundleRelease` |
| No privacy policy URL until Pages deploy | High (all stores) | Deploy Pages (done in this work) |
| No support email | Medium (Play) | Owner supplies |
| Licence wording inconsistency | Medium | Align README with GPL-3.0-only |
| Arch Linux rootfs has no SHA-256 (TLS only) | Low/Medium | Disclose in policy; consider GPG or a checksum source |
| No CI provenance | Medium | Release workflow + secrets |
| 16KB `libtermux.so` | Medium (Samsung) | Rebuild with 16KB alignment |

---

## 9. Privacy policy text

The full policy is maintained in
[`policies/PRIVACY_POLICY.md`](./policies/PRIVACY_POLICY.md). Key statements:

- Linexa does not require an account.
- Linexa does not collect analytics, advertising identifiers, or crash reports.
- Workspace data stays on the device.
- Downloads contact third-party mirrors; their policies apply to those requests.
- The app requests optional permissions only when the user enables the related
  feature.
- Users can delete all app data by deleting workspaces, clearing app storage, or
  uninstalling.
- Contact is via the public issue tracker.
