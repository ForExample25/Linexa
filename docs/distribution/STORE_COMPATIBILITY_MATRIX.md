# Store Compatibility Matrix

**Snapshot date:** 2026-11-15
**App:** Linexa `0.1.0-beta` (`com.fem.linexa`, versionCode 1)
**Artifact:** signed APK, targetSdk 34, minSdk 28, arm64-v8a + armeabi-v7a

Status labels: `NOT_STARTED`, `INVESTIGATING`, `READY`, `WAITING_FOR_USER`,
`ACCOUNT_CREATED`, `DRAFT_CREATED`, `UPLOADED`, `SUBMITTED`, `UNDER_REVIEW`,
`APPROVED`, `PUBLISHED`, `REJECTED`, `BLOCKED`, `NOT_ELIGIBLE`.

---

## 1. Master matrix

| Platform | Official portal | Fee | Account needed | Artifact | Target-SDK gate | FOSS / policy gate | Automation | Status |
|---|---|---|---|---|---|---|---|---|
| Google Play | https://play.google.com/console/ | US$25 one-time | Play Console (personal/organisation) | **AAB** for new apps | **API 36** for new apps since 2026-08-31 | All-files declaration; FGS declarations; Data safety | Play Developer API (needs service account); Console UI | `BLOCKED` |
| Xiaomi GetApps | https://global.developer.mi.com/ | None published | Mi Developer | APK | None found in public docs | Standard review | No public submission API found | `WAITING_FOR_USER` |
| Samsung Galaxy Store | https://seller.samsungapps.com/ | None published | Samsung seller + commercial seller status | APK | Samsung-specific; 16KB requirement | 16KB page size from 2026-07-01 | Content Publish API (updates); Seller Portal UI | `WAITING_FOR_USER` |
| Huawei AppGallery | https://developer.huawei.com/consumer/en/appgallery/ | None published | HUAWEI ID + developer account | APK | Huawei-specific | Privacy policy, review | AppGallery Connect API (needs account) | `WAITING_FOR_USER` |
| Amazon Appstore | https://developer.amazon.com/apps-and-games | Free developer account | Amazon customer + developer | APK or AAB | Amazon-specific | Content policy, privacy labels | App Submission API (needs account) | `WAITING_FOR_USER` |
| Aptoide | https://www.aptoide.com/developers | None published | Aptoide Connect | APK/AAB | None found | Standard review | Connect console; no public API found | `WAITING_FOR_USER` |
| Uptodown | https://www.uptodown.com/consola-de-desarrolladores | Free | Developers Console | APK | None found | Standard review | Web console only | `WAITING_FOR_USER` |
| APKPure | https://apkpure.com/developer | None published | Developer account | APK | None found | Standard review | Web console; Cloudflare challenge | `WAITING_FOR_USER` |
| F-Droid | https://f-droid.org/ | Free | GitLab (RFP / fdroiddata MR) | Source build | None | Strict FLOSS / prebuilt-binary policy | GitLab MR, fdroidserver | `READY` (packaging) |
| IzzyOnDroid | https://apt.izzysoft.de/fdroid/ | Free | Codeberg issue | Developer-signed APK | None | FOSS, no trackers, ≤30 MB guideline | Codeberg issue | `BLOCKED` (size) |
| Accrescent | https://accrescent.app/ | None published | Accrescent Developer Console | APK set | Follows Play (API 36) | Strict security review | Developer Console | `NOT_ELIGIBLE` |
| GitHub Releases | https://github.com/ForExample25/Linexa/releases | Free | GitHub | APK | None | GPL-3.0 | GitHub API/CLI | `PUBLISHED` |
| GitHub Pages | https://forexample25.github.io/Linexa/ | Free | GitHub | Direct APK link | None | None | Git push + Pages API | `PUBLISHED` |

---

## 2. Platform notes and evidence

### 2.1 Google Play

**Official requirements checked:**

- Target API: https://support.google.com/googleplay/android-developer/answer/11926878
  - From **August 31, 2026**, new apps and updates must target **Android 16
    (API 36)**. Existing apps must target API 35 to remain available to new
    users. Extension requests were available only to existing apps and only to
    November 1, 2026.
  - The current APK targets **34**. It cannot be submitted as a new app.
- Registration and fee: https://support.google.com/googleplay/android-developer/answer/6112435
  - US$25 one-time; government ID and payment card under the legal name;
    personal or organisation account.
- New personal-account testing: https://support.google.com/googleplay/android-developer/answer/14151465
  - Closed test with **12 testers opted in continuously for 14 days**, then a
    production-access application.
- All files access: https://support.google.com/googleplay/android-developer/answer/10467955
  - Only permitted for file managers, backup/restore, antivirus, document
    management, on-device search, encryption, and device migration, or with an
    approved exception.
- AAB: new apps must be published as Android App Bundles. No AAB exists yet.

**Verdict:** `BLOCKED`. Four independent gates: targetSdk 36, AAB, account/fee,
and permission declarations. The first two are technical.

**Lowest-risk remediation:**

1. Decide whether to upgrade AGP + compileSdk to 36 (required).
2. Remove `MANAGE_EXTERNAL_STORAGE` from the manifest and the optional settings
   row, or prepare a file-management declaration. The app already falls back to
   SAF, so removal does not break core functionality.
3. Build `bundleRelease` in CI with the existing release key.
4. Register, pay, verify, run the 12-tester closed test.

### 2.2 Xiaomi GetApps

- Portal: https://global.developer.mi.com/ (login via Xiaomi Account).
- Registration is free; no publication fee is published.
- The global portal is an SPA; its FAQ and docs redirect unauthenticated users
  to `account.xiaomi.com`. Account creation needs an email/phone and OTP.
- Artifact: APK.
- No target-SDK gate is documented on the public global portal. Confirm at
  upload.
- Review is manual; metadata is entered in the console.

**Verdict:** `WAITING_FOR_USER` — account creation and OTP.

### 2.3 Samsung Galaxy Store

- Seller Portal: https://seller.samsungapps.com/
- Public overview: https://developer.samsung.com/galaxy-store
- Account flow: Samsung account → Seller Portal registration → commercial seller
  status. No fee is listed on the public overview.
- Public notice on the overview (checked 2026-11-15):
  - **16KB page size:** "Starting July 1, 2026, Galaxy Store is requiring all
    new and updated Android apps (Android 15 and higher) to support 16KB page
    size." The exact scope (target SDK vs device OS) must be confirmed in Seller
    Portal.
  - **Developer verification:** Samsung's notice says Google will require
    developer verification for Android distribution in Brazil, Singapore,
    Indonesia, and Thailand from September 2026, expanding globally in 2027.
- Galaxy Store Content Publish API exists for app data and updates, but it
  requires seller credentials.

**Technical finding:** all arm64 libraries are 16KB-aligned **except**
`libtermux.so` (JNI bridge, 4KB LOAD alignment). `libzstd-jni` is 64KB-aligned.
`libproot.so` and the other arm64 binaries are 0x4000-aligned. This is a real
risk for Samsung's requirement and for Android 15+ 16KB devices.

**Verdict:** `WAITING_FOR_USER`, conditional on the 16KB fix and seller
approval.

### 2.4 Huawei AppGallery

- Portal: https://developer.huawei.com/consumer/en/appgallery/
- Release documentation: https://developer.huawei.com/consumer/en/doc/app/agc-help-releaseapp-0000001146718717
  (requires JavaScript / a HUAWEI ID session).
- Registration is free; no publication fee is published.
- APK upload through AppGallery Connect; review is manual.
- Huawei requires a privacy policy URL and accurate app permissions.
- No GMS dependency exists in Linexa, which is favourable for AppGallery.

**Verdict:** `WAITING_FOR_USER` — HUAWEI ID, developer verification.

### 2.5 Amazon Appstore

- Portal: https://developer.amazon.com/apps-and-games
- Submission docs: https://developer.amazon.com/docs/app-submission/understanding-submission.html
- "Create a free developer account" is stated on the portal.
- Amazon accepts APKs and app bundles; maximum binary size 2.5 GB.
- Amazon **removes the developer signature and re-signs** the app with an
  Amazon account signature. It also wraps the binary for analytics/policy
  enforcement.
- 64-bit support is required for modern Fire devices; the APK ships both ABIs.
- APK must be zip-aligned; verified.
- Amazon may request IP/licence documentation for third-party components. The
  project has `THIRD_PARTY_NOTICES.md` and a written source offer, which helps.

**Verdict:** `WAITING_FOR_USER` — free account and identity/tax details.

### 2.6 Aptoide

- Portal: https://www.aptoide.com/developers
- Aptoide Connect is free to join; the public page describes one submission
  reaching Aptoide's stores plus partner stores/OEMs (50M+ MAU claimed).
- No publication fee is published.
- Apps without in-app purchases need no SDK integration.
- Review is manual; Aptoide is an established alternative store and a
  participant in the Android alternative-store ecosystem.

**Verdict:** `WAITING_FOR_USER` — Connect account.

### 2.7 Uptodown

- Developers Console: https://www.uptodown.com/consola-de-desarrolladores
- Official page states registration and publication are **totally free**
  ("Tanto el registro como la publicación de apps son totalmente gratuitos").
- Developers can submit a new app or claim authorship of an existing listing.
- Developer terms: https://en.uptodown.com/developers-zone/services
  (updated November 2026; governed by Spanish law).
- Console is Spanish-first; the terms are available in English.
- Manual review/QA before publication.

**Verdict:** `WAITING_FOR_USER` — free account and acceptance of the developer
agreement.

### 2.8 APKPure

- Portal: https://apkpure.com/developer (HTTP 403 to non-browser clients;
  Cloudflare "Just a moment..." challenge in a browser).
- Free developer console; manual review.
- **Do not attempt to bypass Cloudflare.** A human must complete the challenge
  in a normal browser session.

**Verdict:** `WAITING_FOR_USER` — manual browser session.

### 2.9 F-Droid

- Inclusion policy: https://f-droid.org/en/docs/Inclusion_Policy/
- Submission guide: https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/
- Requirements relevant to Linexa:
  - FLOSS licence and public source: **met** (GPL-3.0).
  - No proprietary trackers/analytics/GMS: **met** (none).
  - Buildable from source with a 100% FLOSS toolchain: **not yet proven**.
  - Prebuilt binaries: only from trusted sources (Debian, trusted Maven, SDKs).
    The project ships custom-build PRoot/BusyBox/tar/tmux/zstd binaries. The
    PRoot build recipe exists (`app/src/main/jniLibs/proot-build/BUILD-NOTES.md`),
    but the other binaries have no integrated source build. F-Droid reviewers
    may reject or require a build recipe.
  - Runtime downloads of executable binaries need explicit opt-in consent:
    Linexa downloads RootFS archives only after a user action; this is
    discloseable and consistent with the policy, but the consent UX must be
    clear.
  - Fastlane metadata (short/full description, icon, screenshots, changelog):
    prepared in this package.
  - Unique application ID: met.
- F-Droid builds and signs the app with its own key; users would need to
  uninstall the GitHub-signed build to switch. Reproducible builds are optional
  but recommended.

**Verdict:** `READY` for packaging; `WAITING_FOR_USER` for the GitLab account
and the prebuilt-binary decision.

### 2.10 IzzyOnDroid

- Repository: https://apt.izzysoft.de/fdroid/
- Inclusion policy: https://izzyondroid.org/docs/general/AppInclusionPolicy/
- Key rules:
  - FLOSS code and licence: **met**.
  - No trackers/ads/analytics: **met**.
  - Release-signed APK, not debuggable/testOnly: **met**.
  - Fastlane metadata in the repo: **prepared**.
  - APK must be downloadable from the project with a stable URL: **met**
    (GitHub Releases).
  - **Size:** usually up to **30 MB per APK**; exceptions are possible.
    Linexa's APK is **57.3 MB**, so an exception or per-ABI split is required.
  - Self-updaters require explicit opt-in: Linexa has no self-updater.
- Submission via the Codeberg issue tracker (the GitLab repo is archived).

**Verdict:** `BLOCKED` on size until an exception is granted or a per-ABI APK
is produced. A per-ABI split would also help F-Droid and reduce download size.

### 2.11 Accrescent

- Requirements: https://accrescent.app/docs/guide/appendix/requirements/
- Accrescent follows Google Play's target SDK requirements, without extension
  requests. It removes non-compliant apps rather than merely hiding them.
- Other checks: no debug certificate, single signer, v2/v3 signature, no
  `REQUEST_INSTALL_PACKAGES` self-updater, native ABI parity, ≤1 GiB.
- `MANAGE_EXTERNAL_STORAGE` is allowed only for file-management use cases and is
  manually reviewed.
- Linexa meets signature/ABI/size rules, but **targetSdk 34 fails** the current
  Play-derived requirement (36). The store is also in alpha.

**Verdict:** `NOT_ELIGIBLE` until targetSdk 36.

### 2.12 GitHub Releases and GitHub Pages

- Release: https://github.com/ForExample25/Linexa/releases/tag/v0.1.0-beta
- Pages: https://forexample25.github.io/Linexa/ (deployment recorded in
  `PUBLICATION_STATUS.md`)
- Direct APK distribution is immediate and free. It is the canonical source for
  checksums and the signing certificate.
- Google's **Android developer verification** program begins user protections
  on **2026-09-30** for participating stores in select regions and expands
  globally in 2027. Public sideload distribution may require developer
  registration/verification; a free hobbyist tier exists for sharing with up to
  20 devices, but public distribution at scale needs the standard verification
  path. See https://developer.android.com/developer-verification.

---

## 3. Cross-store policy risks

| Risk | Affected stores | Mitigation |
|---|---|---|
| Target SDK 34 | Play, Accrescent | Upgrade to 36; requires AGP/toolchain change |
| All files access | Play, Accrescent, IzzyOnDroid | Remove optional permission; SAF already works |
| Running downloaded guest software | Play, Samsung, Huawei, Amazon, Aptoide, Uptodown | Describe as core function; explicit user action; checksum verification |
| 16KB page size | Samsung, Play (once target ≥35) | Rebuild `libtermux.so` with 16KB alignment |
| Prebuilt binaries | F-Droid, IzzyOnDroid | Source-build recipe or reviewer exception |
| APK >30 MB | IzzyOnDroid | ABI split or exception |
| No AAB | Play | CI `bundleRelease` |
| No reproducible build | F-Droid (optional), trust | CI release workflow + checksums |
| License inconsistency (`only` vs `or-later`) | F-Droid, all stores | Choose one and align README, LICENSE notice, About screen, commit history note |

---

## 4. Verification commands used

```bash
apksigner verify --print-certs --verbose app-release.apk
aapt2 dump badging app-release.apk
aapt2 dump xmltree app-release.apk --file AndroidManifest.xml
zipalign -c -v 4 app-release.apk
sha256sum app-release.apk
readelf -lW lib/arm64-v8a/*.so   # LOAD alignment
```

Toolchain: Android build-tools 34.0.0 from the machine's central SDK. No Gradle
build was run for this audit.
