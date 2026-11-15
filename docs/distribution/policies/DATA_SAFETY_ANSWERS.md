# Google Play Data Safety — Draft Answers

**App:** Linexa `com.fem.linexa`
**Version:** 0.1.0-beta (versionCode 1)
**Prepared:** 2026-11-15

Submit these answers in Play Console → App content → Data safety. They are
based on the verified behaviour in
[`../PRIVACY_AND_COMPLIANCE_AUDIT.md`](../PRIVACY_AND_COMPLIANCE_AUDIT.md).

---

## Section 1 — Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (no collection) |
| Do you provide a way for users to request that their data be deleted? | Not applicable (data is local; users can delete workspaces, clear app data, or uninstall) |

## Section 2 — Data types

No data types selected. Specifically, the app does **not** collect or share:

- Personal info (name, email, address, phone, user IDs)
- Financial info
- Health and fitness
- Messages
- Photos and videos
- Audio files
- Files and docs
- Calendar
- Contacts
- App activity (interactions, search history, installed apps, other actions)
- Web browsing
- App info and performance (crash logs, diagnostics, other performance data)
- Device or other IDs
- Location

## Section 3 — Third-party SDKs

No third-party SDK collects data. The app contains no Firebase, Google Play
Services, Crashlytics, advertising, attribution, or analytics SDK. The
dependencies are AndroidX/Compose/Room/Hilt, Kotlin libraries, SnakeYAML,
zstd-jni, commons-compress, XZ, and the vendored Apache-2.0 Termux terminal
modules.

## Section 4 — Notes for the reviewer

The app downloads Linux root filesystems from public distribution mirrors when
the user requests one. The request necessarily exposes the user's IP address to
that mirror, exactly as a browser download would. Linexa does not receive or
store that request data. The privacy policy discloses the destinations.

The app can run arbitrary software inside a user-created Linux workspace. That
software may contact the network under the user's control; Linexa does not
instrument or report that traffic.

## Section 5 — Changes that would require a new Data safety submission

- Adding analytics, crash reporting, ads, accounts, sync, or remote config.
- Adding a developer-operated server.
- Uploading logs or diagnostics automatically.
- Adding a third-party SDK that collects device IDs or usage data.
