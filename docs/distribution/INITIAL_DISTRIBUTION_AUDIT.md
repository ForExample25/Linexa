# Linexa — Initial Distribution Audit

**Audit date:** 2026-11-15
**Auditor:** OpenCode release/distribution agent
**Verified artifact:** `app-release.apk` from the public `v0.1.0-beta` GitHub Release
**Artifact SHA-256:** `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680`

This audit is based on the actual repository, the published APK, and current
official store documentation. It does not assume features, permissions, signing
status, or store policy from the project name.

---

## 1. Repository identity — important correction

The brief named `https://github.com/ForExampleZERO/Linexa`. That repository does
not exist (GitHub API returns `404`). The actual public repository is:

- **Public source and releases:** https://github.com/ForExample25/Linexa
- **Default branch:** `main`
- **Public HEAD at audit time:** `d7e678618b59a27283a07260ab1efd05d6eb8c95`
  ("Linexa 0.1.0-beta public release")
- **No `.github/workflows` existed in the public repository at audit time.**
  The release workflow in this package is added by the distribution pull
  request and has not run yet.

**Consequence:** every distribution artifact and public URL in this package
refers to `ForExample25/Linexa`. Publishing to the repository named in the brief
is impossible until the repository name is confirmed or created by the owner.

---

## 2. Project findings

| Item | Verified value | Evidence |
|---|---|---|
| Application name | Linexa | `app_name`, APK badging |
| Package ID | `com.fem.linexa` | APK badging |
| Version name | `0.1.0-beta` | `app/build.gradle.kts`, APK badging |
| Version code | `1` | `app/build.gradle.kts`, APK badging |
| compileSdk | 34 | `app/build.gradle.kts`, APK metadata |
| targetSdk | **34** | APK badging |
| minSdk | 28 (Android 9) | APK badging |
| ABIs | `arm64-v8a`, `armeabi-v7a` | APK `lib/` |
| Build system | Gradle 8.x wrapper, AGP 8.5.0, Kotlin 2.1.20, JDK 17 | wrapper, `build.gradle.kts`, APK metadata |
| UI | Jetpack Compose (Material 3), Hilt, Room | Gradle deps |
| License | GPL-3.0-only (public release and `LICENSE` file) | `LICENSE`, README |
| Backend services | None; no accounts, no cloud sync, no analytics | source audit |
| Native code | 20 prebuilt binaries/libraries per ABI (PRoot, BusyBox, GNU tar, tmux, zstd, libs) | `app/src/main/jniLibs/` |
| Release signing | Valid APK Signature Scheme v2, RSA-3072 | `apksigner verify` |
| Signing certificate SHA-256 | `AA:AB:44:EC:CF:4D:B1:5C:88:16:BD:34:F6:08:64:D7:70:54:32:BF:4F:60:5D:52:0C:00:D5:73:EE:63:C5:20` | `apksigner` |
| ZIP alignment | Verified with `zipalign -c -v 4` | build-tools 34.0.0 |
| APK size | 57,257,408 bytes (54.60 MiB) | GitHub Release metadata |
| Debuggable / testOnly | No | `aapt2 dump xmltree` |

### Architecture and runtime

Linexa is a Linux workspace manager. It runs Linux userlands through PRoot in
app-private storage, exposes a PTY terminal, a file manager with a SAF
DocumentsProvider, a package-manager front end, a VNC/Web desktop surface, and
per-workspace DNS/hosts/mirror configuration. RootFS archives can be imported
from local storage or downloaded from official distribution mirrors. The app
does not request root.

This is relevant to store policy: the app ships interpreters/executables and can
run arbitrary guest software by design. That is the product's core function, not
an incidental capability, and must be declared consistently.

---

## 3. Verified release artifact

| Field | Value |
|---|---|
| Release | `v0.1.0-beta` (pre-release) |
| Release URL | https://github.com/ForExample25/Linexa/releases/tag/v0.1.0-beta |
| Asset | `app-release.apk` |
| Size | 57,257,408 bytes |
| SHA-256 | `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` |
| GitHub-reported digest | `sha256:6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` (match) |
| Signature | APK Signature Scheme v2 only (no v1/v3/v4) |
| Certificate | `CN=Linexa, OU=Development, O=FemProject, C=US` |
| Certificate SHA-256 | `aaab44eccf4db15c8816bd34f60864d7705432bf4f605d520c00d573ee63c520` |
| Build workflow | **None.** No GitHub Actions run produced this APK. |
| AAB | **Missing.** No `.aab` artifact exists. |

The artifact is signed, zip-aligned, installable, and not a debug build. It can
be used for direct distribution and for stores that accept APKs.

**Provenance caveat:** because no CI workflow exists in the public repository,
the APK cannot be tied to a GitHub Actions run. The tag commit message records
the same SHA-256 and certificate fingerprint, which is useful corroboration but
is not a reproducible-build proof.

---

## 4. Store compatibility summary

Status labels follow the required vocabulary. "Eligible" means the app can
technically be submitted today with the current artifact, not that an account
exists.

| # | Platform | Tier | Current artifact accepted? | Eligible today? | Primary blocker | Status |
|---|---|---|---|---|---|---|
| 1 | Google Play | 1 | **No** — AAB required | **No** | targetSdk 34; new apps must target 36 since 2026-08-31 | `BLOCKED` |
| 2 | Xiaomi GetApps | 1 | Yes (APK) | Yes | Developer account + review | `WAITING_FOR_USER` |
| 3 | Samsung Galaxy Store | 1 | Likely (APK) | Conditional | Seller account; 16KB page support gap | `WAITING_FOR_USER` |
| 4 | Huawei AppGallery | 1 | Yes (APK) | Yes | Developer account + identity verification | `WAITING_FOR_USER` |
| 5 | Uptodown | 2 | Yes (APK) | Yes | Free developer console account | `WAITING_FOR_USER` |
| 6 | APKPure | 2 | Yes (APK) | Yes | Account; Cloudflare-protected portal | `WAITING_FOR_USER` |
| 7 | F-Droid | 2 | Built from source | Conditional | Prebuilt native binaries; GitLab submission account | `READY` (packaging) |
| 8 | Amazon Appstore | 3 | Yes (APK/AAB) | Yes | Free developer account + identity | `WAITING_FOR_USER` |
| 9 | Aptoide | 3 | Yes (APK) | Yes | Aptoide Connect account | `WAITING_FOR_USER` |
| 10 | IzzyOnDroid | 3 | Yes, but 57 MB > 30 MB guideline | Conditional | Size exception; Codeberg account | `BLOCKED` (size) |
| 11 | Accrescent | 3 | Yes, but target SDK gate | **No** | Follows Play target SDK requirement (36) | `NOT_ELIGIBLE` |
| 12 | GitHub Releases | Direct | Yes | Yes | None | `PUBLISHED` |
| 13 | GitHub Pages | Direct | N/A | Yes | None | `PUBLISHED` |

Detailed evidence and per-store notes: [`STORE_COMPATIBILITY_MATRIX.md`](./STORE_COMPATIBILITY_MATRIX.md).

---

## 5. Missing requirements

### Blocking for Google Play

1. **targetSdk 36.** Google Play requires new apps and updates to target
   Android 16 (API 36) from **2026-08-31**. The APK targets 34. There is no
   extension path for a new app, and the extension window for existing apps
   ended 2026-11-01. A toolchain upgrade is required: AGP 8.5.0 does not
   officially support `compileSdk 36`. This means changing AGP/compileSdk,
   which `AGENTS.md` currently forbids without a demonstrated need. This audit
   is that demonstrated need, but the change requires owner approval.
2. **AAB artifact.** New apps must be published as an Android App Bundle.
   `bundleRelease` is configured but has never run in CI.
3. **Play Console account.** US$25 one-time fee, identity verification, and
   (for personal accounts) 12 testers opted in for 14 continuous days before
   production access.
4. **All files access declaration.** `MANAGE_EXTERNAL_STORAGE` is declared.
   Google restricts it to file managers, backup/restore, antivirus, document
   management, on-device search, encryption, and device migration. Linexa is a
   Linux runtime, not primarily a file manager. The app already works without
   the permission via SAF, so the lowest-risk fix is to remove the declaration
   rather than argue for an exception.
5. **Foreground service declaration.** `FOREGROUND_SERVICE_SPECIAL_USE`
   requires a Play Console declaration describing the use case
   (`workspace-runtime`).
6. **Battery optimisation declaration.** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   is policy-restricted; the long-running workspace runtime is the justification,
   but the declaration must be accurate and tied to core functionality.
7. **Data safety, content rating, privacy policy URL, store listing, support
   contact.** Prepared in this package; the legal confirmations must be made by
   the account owner.

### Blocking for other stores

| Platform | Missing item |
|---|---|
| Samsung | Seller account + commercial seller status; 16KB page-size support for Android 15+ apps (from 2026-07-01). `libtermux.so` (arm64) is 4KB-aligned. |
| Huawei | AppGallery developer account, identity verification, store listing, privacy policy URL. |
| Xiaomi | Mi Developer account (email/phone), app listing, review. |
| Amazon | Amazon customer + developer account, tax/identity details as requested, listing assets. |
| Aptoide | Aptoide Connect account, app record, review. |
| Uptodown | Developers Console account, app record, review. |
| APKPure | Developer account; portal is behind a Cloudflare challenge (manual only). |
| F-Droid | GitLab account and RFP/fdroiddata merge request; decision on prebuilt binaries; Fastlane metadata. |
| IzzyOnDroid | Codeberg account and inclusion request; 30 MB per-APK guideline vs 57 MB artifact (exception or ABI split). |
| Accrescent | targetSdk 36; otherwise meets signature and ABI rules. |

### Release infrastructure gaps

- No committed CI or release workflow in the public repo.
- No signing secrets in GitHub Actions, so a signed AAB/APK cannot be produced
  by CI today.
- No AAB.
- No Fastlane metadata in the repository (prepared here).
- No privacy policy URL (prepared here; Pages deployment pending).
- No public support email (GitHub Issues can serve as support, but Play requires
  an email address in the listing).

---

## 6. Required assets

| Asset | Status | Notes |
|---|---|---|
| App icon 512×512 | **Ready** | `assets/store-icon/icon-512.png` |
| App icon 216×216 / 114×114 | **Ready** | Huawei / Amazon variants |
| Feature graphic 1024×500 | **Ready** | `assets/feature-graphic/feature-graphic-1024x500.png` |
| Phone screenshots ×8 | **Ready** | Normalised to 1080×1920, 9:16 (originals are 640×1370, which exceeds Play's 2:1 limit) |
| Tablet screenshots | **Missing** | No tablet UI capture exists; must not be fabricated |
| Short description | **Ready** | 79 characters |
| Full description | **Ready** | English, accurate to current build |
| Privacy policy | **Ready** | Hosted on the public Pages site |
| Release notes | **Ready** | `fastlane/.../changelogs/1.txt` |
| Promo video | Not required | None |
| Content rating answers | Prepared | Legal submission by owner |

---

## 7. Registration requirements and fees

| Platform | Fee | Account type | Identity / payment needed |
|---|---|---|---|
| Google Play | US$25 one-time | Personal or organisation | Government ID, payment card, address, phone; 12-tester requirement for new personal accounts |
| Samsung Galaxy Store | No fee published | Seller account + commercial seller status | Samsung account, seller/business details, tax info as requested |
| Xiaomi GetApps | No fee published | Mi Developer account | Email/phone, identity verification as requested |
| Huawei AppGallery | No fee published | HUAWEI ID + developer account | Identity verification |
| Amazon Appstore | Free developer account | Amazon customer + developer account | Identity/tax details as requested |
| Aptoide | No fee published | Aptoide Connect account | Email, developer details |
| Uptodown | Free ("registro y publicación totalmente gratuitos") | Developers Console account | Email, acceptance of developer terms |
| APKPure | No fee published | Developer account | Email; manual verification |
| F-Droid | Free | GitLab account | Email; no payment |
| IzzyOnDroid | Free | Codeberg account | Email; no payment |
| Accrescent | No fee published | Developer Console account | Email; currently alpha |

Fees and requirements change. Each registration page must be re-checked at the
moment of signup; this table is the 2026-11-15 snapshot.

---

## 8. Rejection risks (ranked)

1. **Target SDK 34 (Google Play, Accrescent).** Hard policy failure for a new
   app. Must be fixed before submission.
2. **All files access (Google Play, Accrescent).** The app is not a file manager;
   a declaration is likely to be rejected. Removing the optional permission is
   the safer path.
3. **Arbitrary code execution (Google Play, Samsung, Huawei, Amazon).** A Linux
   runtime can execute downloaded guest software. This is legitimate and is the
   app's core function, but reviewers may ask for clarification. The listing and
   privacy policy must describe it plainly.
4. **16KB page size (Samsung, future Google Play requirement).** `libtermux.so`
   is 4KB-aligned on arm64. Samsung requires 16KB support for new/updated apps
   from 2026-07-01. Rebuilding the JNI library with 16KB alignment is required.
5. **Prebuilt native binaries (F-Droid, IzzyOnDroid).** F-Droid only accepts
   prebuilt FLOSS binaries from trusted sources; the custom PRoot/BusyBox/tar
   binaries are built by the project. F-Droid may require a source build recipe.
6. **APK size (IzzyOnDroid).** 57 MB exceeds the ~30 MB per-APK guideline.
7. **Missing AAB (Google Play).** Cannot submit an APK as a new app.
8. **No CI provenance.** Stores generally accept developer-signed APKs, but the
   project's own release process is not reproducible or auditable yet.
9. **License inconsistency.** Public README and commit message say
   `GPL-3.0-only`; the local working tree says `GPL-3.0-or-later`. Stores and
   F-Droid will read the repository. Pick one and make every notice match.
10. **Uptodown / APKPure / Huawei portals.** These are manual, localized, or
    bot-protected. Do not automate around Cloudflare or login challenges.

---

## 9. Recommended publication order

1. **GitHub Releases** — already live; keep as the canonical signed artifact.
2. **GitHub Pages** — public download page, checksums, privacy policy. Low risk,
   immediate, and it supplies the privacy-policy URL every store needs.
3. **Uptodown** — free, open developer console, accepts APKs, no target-SDK gate
   documented. Fastest independent store.
4. **APKPure** — free, large reach, manual upload; Cloudflare means a human must
   complete the session.
5. **Aptoide Connect** — free, one submission reaches Aptoide plus partner
   stores; no target-SDK gate documented.
6. **Amazon Appstore** — free account, APK/AAB accepted, re-signs the app.
7. **Xiaomi GetApps** — free, global, APK.
8. **Huawei AppGallery** — free, global, APK; identity verification.
9. **Samsung Galaxy Store** — free, but fix 16KB alignment first.
10. **F-Droid** — after native-build/prebuilt-binary decision; high effort, high
    trust value.
11. **IzzyOnDroid** — after an ABI split or size exception.
12. **Google Play** — last, because it requires targetSdk 36, an AAB, a $25
    account, permission cleanup, and 14 days of closed testing.
13. **Accrescent** — after targetSdk 36; alpha store, low immediate reach.

This order maximizes publicly visible listings per unit of owner effort and
defers the two changes that require code/toolchain decisions.

---

## 10. What was deliberately not changed

- No application source, Gradle version, AGP version, compileSdk, targetSdk,
  manifest permission, package ID, or signing configuration was modified.
- No signing key was created, exported, or uploaded.
- No account was registered with invented identity data.
- No store submission was claimed.
- No existing artwork was overwritten; new derivative assets are additive under
  `docs/distribution/assets/`.

The uncommitted working tree in the local checkout was left untouched.
