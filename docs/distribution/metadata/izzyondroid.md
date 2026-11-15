# IzzyOnDroid Listing and Inclusion Request

**Status:** `BLOCKED` — the 57.3 MB APK exceeds the repository's usual 30 MB
per-APK guideline; a Codeberg account is also required.
**Repository:** https://apt.izzysoft.de/fdroid/
**Inclusion policy:** https://izzyondroid.org/docs/general/AppInclusionPolicy/
**Issue tracker:** https://codeberg.org/IzzyOnDroid/repo/issues

---

## Why this is blocked

| Requirement | Status |
|---|---|
| FLOSS code and licence | Met — GPL-3.0-only, public source |
| No ads/trackers/analytics | Met — no such SDKs |
| Release-signed APK, not debuggable | Met — v2, RSA-3072, not debuggable |
| Stable download URL | Met — GitHub Releases asset |
| Fastlane metadata | Prepared — `fastlane/metadata/android/en-US/` |
| **APK size** | **57,257,408 bytes vs the usual ≤30 MB guideline** |
| Codeberg account | Missing |
| `MANAGE_EXTERNAL_STORAGE` review | Optional permission; must be disclosed |

The size is inherent to bundling PRoot, BusyBox, tar, tmux, zstd, and the
Termux terminal modules for two ABIs. A per-ABI split would roughly halve the
download, but no split artifact exists and producing one requires a new signed
build. Do not claim the size issue is solved until such an artifact exists.

The honest request is an **exception** for a Linux runtime that cannot function
without its native payload. The maintainer may still decline; that is their
decision, not something to argue around.

---

## Proposed inclusion issue

Open at https://codeberg.org/IzzyOnDroid/repo/issues with the title:

```
Inclusion request: Linexa — Linux Workspaces (com.fem.linexa)
```

Body:

```markdown
## App

- **Name:** Linexa — Linux Workspaces
- **Package:** `com.fem.linexa`
- **Version:** `0.1.0-beta` (versionCode 1)
- **License:** GPL-3.0-only
- **Source:** https://github.com/ForExample25/Linexa
- **Issue tracker:** https://github.com/ForExample25/Linexa/issues
- **Website / privacy:** https://forexample25.github.io/Linexa/
- **Fastlane metadata:** prepared in the repository under
  `fastlane/metadata/android/en-US/` (submission PR pending)

## What it does

Linexa creates isolated Linux workspaces on Android using PRoot. It installs a
Linux root filesystem, provides a PTY terminal, a file manager, a package
manager front end, and optional VNC/local-web desktop access. No root, no
account, no analytics, no ads, no Google Play Services.

## Release

- **APK:** https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/app-release.apk
- **Size:** 57,257,408 bytes (54.60 MiB)
- **SHA-256:** `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680`
- **Signer certificate SHA-256:** `AA:AB:44:EC:CF:4D:B1:5C:88:16:BD:34:F6:08:64:D7:70:54:32:BF:4F:60:5D:52:0C:00:D5:73:EE:63:C5:20`
- **Signature:** APK Signature Scheme v2, RSA-3072, single signer
- **minSdk / targetSdk:** 28 / 34
- **ABIs:** arm64-v8a, armeabi-v7a (matching library sets)

## Size exception requested

The APK exceeds the usual 30 MB guideline because it bundles the complete
native Linux runtime for two ABIs. A future per-ABI split is planned, but the
current artifact is the verified public beta. Please consider an exception;
the alternative is to wait for a split build that does not exist yet.

## Permissions / behaviour to review

- `MANAGE_EXTERNAL_STORAGE` is **optional**; the app works through the Storage
  Access Framework without it. It is used only when the user enables all-files
  access in settings.
- The app downloads Linux root filesystems from official distribution mirrors
  only after the user taps Download. No silent updates, no
  `REQUEST_INSTALL_PACKAGES`, no self-updater.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is user-initiated for long-running
  workspaces.
- Foreground service type `specialUse` (`workspace-runtime`) hosts the Linux
  session; `dataSync` is used for user-initiated downloads/imports.
- No location, contacts, camera, microphone, SMS, call log, or advertising ID.

## Anti-features

None known. No ads, tracking, non-free network dependency, or proprietary
dependencies. The guest distributions are free software.

## Reproducible builds

Not yet. The APK was built from the tagged commit
`d7e678618b59a27283a07260ab1efd05d6eb8c95`, but no CI run or reproducible-build
comparison exists. The release page publishes the SHA-256 and certificate
fingerprint.

## Checklist

- [x] FLOSS licence and public source
- [x] Release-signed, not debuggable
- [x] No trackers/ads/analytics
- [x] Stable release URL
- [x] Fastlane metadata prepared
- [ ] APK ≤30 MB or accepted exception
- [ ] Codeberg account / issue submitted
```

---

## If the exception is declined

Do not resubmit the same request. The legitimate alternatives are:

1. Produce per-ABI release APKs through CI, each likely under 30 MB for
   `armeabi-v7a` and around/over 30 MB for `arm64-v8a`. The arm64 split may
   still exceed the guideline.
2. Reduce the bundled payload by moving optional binaries to a user-initiated
   download. That changes the product's offline behaviour and needs owner
   approval.
3. Accept that IzzyOnDroid is not a viable channel for this build.

The GitHub Release remains available regardless.

---

## After acceptance

1. IzzyOnDroid will verify the APK, signature, and metadata.
2. The repository may add the app to its index with an "RB" (reproducible build)
   status only if a reproducible build is later proven.
3. Record the public package page and repository index version in
   `../PUBLICATION_STATUS.md`.
4. Do not claim inclusion until the package appears in the IzzyOnDroid index.
