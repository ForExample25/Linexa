# Publication Status

**Last verified:** 2026-11-15
**App:** Linexa `com.fem.linexa` — `0.1.0-beta` (versionCode 1)
**Verified artifact SHA-256:**
`6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680`

This file records only states that have been verified. A submitted listing is
not a published app. No store account was created, no developer agreement was
accepted, and no store submission was made by the automation session.

---

## 1. Live channels

| Channel | Status | Public URL | Evidence |
|---|---|---|---|
| GitHub Releases | `PUBLISHED` | https://github.com/ForExample25/Linexa/releases/tag/v0.1.0-beta | Public release API returns the tag, asset `app-release.apk` (57,257,408 bytes), digest `sha256:6ce2d019…293680`, and `SHA256SUMS`; both assets downloaded and checked locally |
| GitHub Pages | `PUBLISHED` | https://forexample25.github.io/Linexa/ | Pages API reports `status: built`, branch `gh-pages`, commit `324e2182ca5e54fd94b7d96d82ff51f028d8e342`, build `1370692299`; `curl` returns HTTP 200 for index, privacy, release JSON, icon, and screenshots |

The Pages site is the public privacy-policy host:

- https://forexample25.github.io/Linexa/privacy.html

The direct APK URL is:

- https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/app-release.apk

The checksum asset is:

- https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/SHA256SUMS

---

## 2. Store status

| Platform | Status | Submitted? | Account? | Primary blocker |
|---|---|---|---|---|
| Google Play | `BLOCKED` | No | No | targetSdk 34 vs API 36; no AAB; no US$25 account; `MANAGE_EXTERNAL_STORAGE` declaration risk |
| Samsung Galaxy Store | `WAITING_FOR_USER` | No | No | Seller + commercial seller account; `libtermux.so` 16KB alignment |
| Xiaomi GetApps | `WAITING_FOR_USER` | No | No | Mi Developer account + OTP |
| Huawei AppGallery | `WAITING_FOR_USER` | No | No | HUAWEI ID + identity verification |
| Amazon Appstore | `WAITING_FOR_USER` | No | No | Free developer account + identity/tax details; Amazon re-signs |
| Aptoide | `WAITING_FOR_USER` | No | No | Aptoide Connect account + review |
| Uptodown | `WAITING_FOR_USER` | No | No | Free Developers Console account + developer terms |
| APKPure | `WAITING_FOR_USER` | No | No | Developer account; Cloudflare challenge requires manual browser |
| F-Droid | `BLOCKED` | No | No | Release build gate needs signing credentials; prebuilt native binaries; GitLab account |
| IzzyOnDroid | `BLOCKED` | No | No | 57.3 MB APK vs usual 30 MB guideline; Codeberg account |
| Accrescent | `NOT_ELIGIBLE` | No | No | targetSdk 34 vs Play-derived 36; alpha store |
| GitHub Releases | `PUBLISHED` | Yes (existing) | GitHub | None |
| GitHub Pages | `PUBLISHED` | Yes (this pass) | GitHub | None |

**There are no `SUBMITTED`, `UNDER_REVIEW`, or `APPROVED` store entries.** Any
future entry must include the portal's submission ID and public URL before the
status changes.

---

## 3. What this distribution pass actually completed

| Item | Result |
|---|---|
| Repository/artifact audit | Completed; public repo and release identified, APK downloaded and inspected |
| APK integrity | SHA-256, size, v2 signature, certificate, zipalign, package, SDK, and ABIs verified |
| Store requirement research | Current official requirements recorded in `STORE_COMPATIBILITY_MATRIX.md` |
| Listing metadata | Prepared for 12 channels under `metadata/` |
| Privacy policy | Published at the Pages URL above |
| Data safety / permissions / content rating | Drafted under `policies/` for owner submission |
| Store assets | Prepared: icons, feature graphic, 8 phone screenshots, 8 Amazon landscape screenshots |
| Fastlane metadata | Added under `fastlane/metadata/android/en-US/` |
| Release workflow | Added at `.github/workflows/release.yml`; inert until four signing secrets are added |
| Store publisher tooling | Added under `tools/store-publisher/`; verifies, lints, checks the release, builds manual packets |
| GitHub Pages | Deployed from orphan `gh-pages`; public privacy URL verified |
| GitHub Release | Existing release verified; `SHA256SUMS` uploaded; install, verification, distribution, and privacy notes added |
| Store accounts | **None created** |
| Store submissions | **None made** |

---

## 4. Verification log

```text
$ gh api repos/ForExample25/Linexa/pages
status: built
html_url: https://forexample25.github.io/Linexa/
source.branch: gh-pages
source.path: /
commit: 324e2182ca5e54fd94b7d96d82ff51f028d8e342

$ curl -fsS -o /dev/null -w '%{http_code}' https://forexample25.github.io/Linexa/
200

$ curl -fsS https://forexample25.github.io/Linexa/ | grep -F 0.1.0-beta
0.1.0-beta

$ curl -fsS https://forexample25.github.io/Linexa/privacy.html | grep -F 'Effective date'
Effective date

$ curl -fsS https://forexample25.github.io/Linexa/release.json | jq -r .sha256
6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680

$ sha256sum app-release.apk
6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680  app-release.apk

$ sha256sum -c SHA256SUMS
app-release.apk: OK

$ aapt2 dump badging app-release.apk
package: name='com.fem.linexa' versionCode='1' versionName='0.1.0-beta'
minSdkVersion:'28'
targetSdkVersion:'34'

$ apksigner verify --print-certs app-release.apk
Signer #1 certificate DN: CN=Linexa, OU=Development, O=FemProject, C=US
Signer #1 certificate SHA-256 digest: aaab44eccf4db15c8816bd34f60864d7705432bf4f605d520c00d573ee63c520

$ zipalign -c -v 4 app-release.apk | tail -1
Verification successful

$ curl -fsS -o /dev/null -w '%{http_code}' \
  https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/SHA256SUMS
200
```

The APK certificate is
`AA:AB:44:EC:CF:4D:B1:5C:88:16:BD:34:F6:08:64:D7:70:54:32:BF:4F:60:5D:52:0C:00:D5:73:EE:63:C5:20`
(RSA-3072, APK Signature Scheme v2, single signer).

---

## 5. What is not published

- No Google Play listing.
- No Samsung Galaxy Store listing.
- No Xiaomi GetApps listing.
- No Huawei AppGallery listing.
- No Amazon Appstore listing.
- No Aptoide listing.
- No Uptodown listing.
- No APKPure listing.
- No F-Droid package.
- No IzzyOnDroid package.
- No Accrescent listing.
- No store badge or "available on" claim appears on the Pages site for any of
  these channels.

The Pages site explicitly labels these channels as **Not published** and links
to this file. Do not add a store badge until the store's public page resolves
and is recorded here.

---

## 6. Next owner actions

1. Decide the Google Play path: targetSdk 36, AAB, and remove or justify
   `MANAGE_EXTERNAL_STORAGE`.
2. Add the four signing secrets and run the release workflow.
3. Create the free store accounts and submit the prepared listings.
4. Fix `libtermux.so` 16KB alignment before Samsung.
5. Decide whether F-Droid/IzzyOnDroid are worth the build-recipe and size work.

Detailed blockers and exact actions: [`BLOCKED_ACTIONS.md`](./BLOCKED_ACTIONS.md).
Per-store requirements: [`STORE_COMPATIBILITY_MATRIX.md`](./STORE_COMPATIBILITY_MATRIX.md).
Credential setup: [`STORE_CREDENTIAL_SETUP.md`](./STORE_CREDENTIAL_SETUP.md).
