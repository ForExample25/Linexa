# Release Manifest — Linexa 0.1.0-beta

**Manifest generated:** 2026-11-15
**Verified by:** downloading the public asset and inspecting it with Android
build-tools 34.0.0 (`apksigner`, `aapt2`, `zipalign`) and `readelf`.

---

## 1. Current official artifact

| Field | Value |
|---|---|
| Application name | Linexa |
| Package ID | `com.fem.linexa` |
| Version name | `0.1.0-beta` |
| Version code | `1` |
| Git commit | `d7e678618b59a27283a07260ab1efd05d6eb8c95` |
| Git tag | `v0.1.0-beta` (annotated tag object `c30bd47d66ed0e49c95987d149bf066f87ecb424`) |
| Artifact type | Signed APK (release) |
| Artifact file | `app-release.apk` |
| Size | 57,257,408 bytes (54.60 MiB) |
| SHA-256 | `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` |
| GitHub digest | `sha256:6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` (matches local hash) |
| Signing status | Valid; APK Signature Scheme v2; single signer |
| Signer DN | `CN=Linexa, OU=Development, O=FemProject, C=US` |
| Signer certificate SHA-256 | `AA:AB:44:EC:CF:4D:B1:5C:88:16:BD:34:F6:08:64:D7:70:54:32:BF:4F:60:5D:52:0C:00:D5:73:EE:63:C5:20` |
| Signer key | RSA 3072-bit |
| ZIP alignment | Verified (`zipalign -c -v 4`) |
| Debuggable | No |
| testOnly | No |
| compileSdk | 34 |
| targetSdk | 34 |
| minSdk | 28 (Android 9) |
| ABIs | `arm64-v8a`, `armeabi-v7a` (22 native files each) |
| Build workflow URL | **None** — the public repo had no GitHub Actions workflow when this APK was produced. `.github/workflows/release.yml` is added by the distribution pull request and has not run. |
| GitHub Release URL | https://github.com/ForExample25/Linexa/releases/tag/v0.1.0-beta |
| Direct download URL | https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/app-release.apk |
| Checksum URL | https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/SHA256SUMS |
| License | GPL-3.0-only (public release notes; copies before 2026-11-14 were offered as GPL-3.0-or-later) |

### Integrity verification

```bash
curl -L -o app-release.apk \
  https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/app-release.apk
curl -L -O \
  https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/SHA256SUMS
sha256sum -c SHA256SUMS
# app-release.apk: OK

apksigner verify --print-certs app-release.apk
# Verifies
# Verified using v2 scheme: true
# Certificate SHA-256: aaab44ec...63c520
```

### Declared permissions (from the APK manifest)

| Permission | Purpose | Runtime/optional |
|---|---|---|
| `INTERNET` | RootFS downloads, guest networking | Install-time |
| `ACCESS_NETWORK_STATE` | DNS discovery from the host network | Install-time |
| `FOREGROUND_SERVICE` | Workspace runtime | Install-time |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Workspace runtime type (`workspace-runtime`) | Install-time |
| `FOREGROUND_SERVICE_DATA_SYNC` | Import/export/download | Install-time |
| `POST_NOTIFICATIONS` | Runtime/download notifications | Runtime (Android 13+) |
| `MANAGE_EXTERNAL_STORAGE` | Opt-in all-files access for the file manager | Special app access, user-initiated |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Opt-in long-session battery exemption | Special app access, user-initiated |
| `WAKE_LOCK` | Opt-in, time-limited runtime wake lock | Install-time |
| `com.fem.linexa.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX internal receiver protection | Install-time, signature-level |

No location, camera, microphone, contacts, SMS, call log, ads-ID, or
`QUERY_ALL_PACKAGES` permission is declared.

### Native-code notes

- All arm64 binaries are 16KB-page aligned except `libtermux.so`, whose LOAD
  segments are 0x1000-aligned. `libzstd-jni` is 0x10000-aligned.
- The 32-bit (`armeabi-v7a`) libraries are 4KB-aligned, which is expected for
  32-bit Android.
- This is a known prerequisite for Samsung's 16KB requirement and for any
  future Play targetSdk ≥35 submission.

---

## 2. Missing release artifacts

| Artifact | Status | Why it matters |
|---|---|---|
| Android App Bundle (`.aab`) | **Missing** | Required for a new Google Play app; also useful for Aptoide/Amazon where bundles are accepted |
| Per-ABI APKs | **Missing** | Would reduce the 57 MB download and help IzzyOnDroid's 30 MB guideline |
| CI-produced artifact | **Missing** | No GitHub Actions release workflow; provenance is manual |
| Mapping file | Not applicable | `isMinifyEnabled = false` |
| Reproducible-build proof | **Missing** | No deterministic build comparison |

**Do not** regenerate the signing key to produce these. The existing key is
valid, the APK is already published under it, and Android will reject updates
signed by a different key.

---

## 3. Release history

| Version | Code | Date | Channel | Status | Notes |
|---|---|---|---|---|---|
| `0.1.0-beta` | 1 | 2026-11-14 | GitHub Releases | `PUBLISHED` | First public beta; signed APK only |
| `0.1.0-beta` | 1 | — | All stores | `NOT_STARTED` | No store submission has been made |

---

## 4. Required next release (proposed, not built)

A store-ready build should:

1. Be built by GitHub Actions from a tagged commit.
2. Produce both `app-release.apk` and `app-release.aab`.
3. Use the existing `keystore/linexa-release.jks` through GitHub Actions
   secrets — never commit the keystore or passwords.
4. Publish SHA-256 checksums and the certificate fingerprint in the release.
5. Optionally produce `arm64-v8a` and `armeabi-v7a` APK splits.
6. Include Fastlane metadata and a changelog for versionCode 1.

The proposed workflow is at `.github/workflows/release.yml` in this package.
It is inert until the owner commits it and adds the four signing secrets.
