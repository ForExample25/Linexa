# Linexa Distribution Package

**App:** Linexa `com.fem.linexa` — `0.1.0-beta` (versionCode 1)
**Verified APK SHA-256:**
`6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680`
**Public site:** https://forexample25.github.io/Linexa/
**Privacy policy:** https://forexample25.github.io/Linexa/privacy.html
**Source and releases:** https://github.com/ForExample25/Linexa

This directory contains the store listings, policies, assets, automation, and
honest status records for distributing the existing `0.1.0-beta` build. It does
not contain a new build, a new signing key, or a store account.

---

## Start here

| Document | What it answers |
|---|---|
| [`PUBLICATION_STATUS.md`](./PUBLICATION_STATUS.md) | What is actually live, what was submitted, and what is not |
| [`BLOCKED_ACTIONS.md`](./BLOCKED_ACTIONS.md) | Every blocked step and the exact owner action needed |
| [`STORE_COMPATIBILITY_MATRIX.md`](./STORE_COMPATIBILITY_MATRIX.md) | Per-store requirements, fees, target-SDK gates, and evidence |
| [`RELEASE_MANIFEST.md`](./RELEASE_MANIFEST.md) | Verified artifact facts, checksums, signature, missing AAB/CI |
| [`PRIVACY_AND_COMPLIANCE_AUDIT.md`](./PRIVACY_AND_COMPLIANCE_AUDIT.md) | Data flows, Data Safety answers, permission risks |
| [`AUTOMATION_GUIDE.md`](./AUTOMATION_GUIDE.md) | How to verify, lint, build, deploy, and report |
| [`STORE_CREDENTIAL_SETUP.md`](./STORE_CREDENTIAL_SETUP.md) | How to add CI signing secrets and store API credentials safely |
| [`INITIAL_DISTRIBUTION_AUDIT.md`](./INITIAL_DISTRIBUTION_AUDIT.md) | The initial repository/artifact audit and recommended order |

---

## Status labels

| Label | Meaning |
|---|---|
| `PUBLISHED` | The public platform URL resolves and has been verified |
| `APPROVED` | The store approved the listing, but it may not be public yet |
| `UNDER_REVIEW` | The store is reviewing a submission |
| `SUBMITTED` | The store accepted the submission and assigned an ID |
| `UPLOADED` | The artifact is uploaded but not submitted |
| `DRAFT_CREATED` | The app record/listing exists in the console |
| `ACCOUNT_CREATED` | A developer account exists |
| `READY` | Packaging is ready; no account/submission yet |
| `WAITING_FOR_USER` | An account, OTP, identity check, payment, or legal acceptance is needed |
| `INVESTIGATING` | Requirements are being checked |
| `NOT_STARTED` | No work has started |
| `BLOCKED` | A technical or policy gate cannot be cleared with the current build |
| `NOT_ELIGIBLE` | The store's current requirements exclude this build |
| `REJECTED` | The store rejected the submission; record the reason verbatim |

A submitted listing is not a published app. Do not add a store badge or a
"available on" claim until the public URL is verified.

---

## Current live channels

| Channel | Status | URL |
|---|---|---|
| GitHub Releases | `PUBLISHED` | https://github.com/ForExample25/Linexa/releases/tag/v0.1.0-beta |
| GitHub Pages | `PUBLISHED` | https://forexample25.github.io/Linexa/ |

No other store listing is live. See `PUBLICATION_STATUS.md` for the full table.

---

## Metadata

Per-store listing copy is in [`metadata/`](./metadata/):

- [`google-play.md`](./metadata/google-play.md) — `BLOCKED`
- [`samsung.md`](./metadata/samsung.md) — `WAITING_FOR_USER`
- [`xiaomi.md`](./metadata/xiaomi.md) — `WAITING_FOR_USER`
- [`huawei.md`](./metadata/huawei.md) — `WAITING_FOR_USER`
- [`amazon.md`](./metadata/amazon.md) — `WAITING_FOR_USER`
- [`aptoide.md`](./metadata/aptoide.md) — `WAITING_FOR_USER`
- [`uptodown.md`](./metadata/uptodown.md) — `WAITING_FOR_USER` (English + Spanish)
- [`apkpure.md`](./metadata/apkpure.md) — `WAITING_FOR_USER` (manual Cloudflare)
- [`fdroid.md`](./metadata/fdroid.md) — `BLOCKED`
- [`izzyondroid.md`](./metadata/izzyondroid.md) — `BLOCKED` (size)
- [`accrescent.md`](./metadata/accrescent.md) — `NOT_ELIGIBLE`
- [`github-releases.md`](./metadata/github-releases.md) — `PUBLISHED`

Fastlane metadata (consumed by F-Droid/IzzyOnDroid-style tooling and Fastlane
`supply`):

```
fastlane/metadata/android/en-US/
```

## Policies

- [`policies/PRIVACY_POLICY.md`](./policies/PRIVACY_POLICY.md) — published at
  the Pages privacy URL.
- [`policies/DATA_SAFETY_ANSWERS.md`](./policies/DATA_SAFETY_ANSWERS.md) — Play
  Data Safety draft.
- [`policies/PERMISSION_DECLARATIONS.md`](./policies/PERMISSION_DECLARATIONS.md)
  — all-files, battery, and foreground-service declarations.
- [`policies/CONTENT_RATING_NOTES.md`](./policies/CONTENT_RATING_NOTES.md) —
  IARC/content-rating answers and expected ratings.

## Assets

```
assets/
├── store-icon/icon-512.png, icon-216.png, icon-114.png
├── feature-graphic/feature-graphic-1024x500.png
└── screenshots/
    ├── phone/01-home.png … 08-file-manager.png        (1080×1920)
    └── amazon-landscape/01-home-1920x1080.png …       (1920×1080)
```

The phone screenshots were normalized from 640×1370 to 1080×1920 because the
originals exceed Play's 2:1 aspect-ratio limit. The UI is padded, not altered.
The Amazon set places the same unmodified screens on a branded landscape
canvas because Amazon's documented sizes are landscape.

## Site

The static source for the public site is in [`site/`](./site/). It is deployed
from an orphan `gh-pages` branch; `main` is not modified by the deployment.
See [`site/README.md`](./site/README.md).

## Tooling

```bash
python3 tools/store-publisher/publisher.py status
python3 tools/store-publisher/publisher.py lint
python3 tools/store-publisher/publisher.py verify --require-tools
python3 tools/store-publisher/publisher.py verify-release
python3 tools/store-publisher/publisher.py packet --store amazon
python3 tools/store-publisher/publisher.py report
```

The tool refuses manual store submissions rather than faking success. It never
handles a keystore or a store password.

---

## What is done vs. blocked

**Done:**

- Repository and artifact audit; public release verification.
- Store requirement research with current official citations.
- Listings for 12 channels, privacy policy, Data Safety, permissions,
  content-rating draft.
- Icons, feature graphic, phone screenshots, Amazon landscape screenshots.
- Fastlane metadata.
- GitHub Pages site and public privacy URL.
- `SHA256SUMS` asset on the existing GitHub Release.
- Obtainium instructions for zero-account update tracking.
- Release workflow (inert until four signing secrets are added).
- Verification/lint/packet/report tooling.
- Distribution package proposed in [PR #1](https://github.com/ForExample25/Linexa/pull/1); open, not merged.

**Blocked (owner action):**

- All store accounts (OTP, identity, payment, agreements).
- Google Play targetSdk 36 + AAB + all-files decision.
- Samsung 16KB `libtermux.so` alignment.
- F-Droid build gate/prebuilt provenance.
- IzzyOnDroid size exception or ABI splits.
- Accrescent targetSdk 36.
- CI signing secrets.

See `BLOCKED_ACTIONS.md` for the exact unblock steps.

---

## Safety rules

- Do not commit `local.properties`, the keystore, passwords, or tokens.
- Do not create accounts with invented identity, address, tax, or payment data.
- Do not bypass CAPTCHA, OTP, authentication, or rate limits.
- Do not claim a store submission or publication without the platform's
  confirmation.
- Do not replace the published APK with a build signed by a different key.
- Do not add fake store badges, ratings, or screenshots.
