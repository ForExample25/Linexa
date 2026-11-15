# Blocked Actions and Required Owner Decisions

**Date:** 2026-11-15
**Rule:** a blocked action is paused only for the specific step that needs a
CAPTCHA, OTP, identity check, payment, legal declaration, or account
authorization. Independent work continues.

The agent did not create any store account, accept any developer agreement, pay
any fee, or submit any listing. This file records exactly why, and what the
owner must do to unblock each step.

---

## 1. Identity, payment, and legal gates

| # | Blocked action | Why it is blocked | Exact owner action | What the agent can do after |
|---|---|---|---|---|
| B1 | Create a Google Play Console account | Requires a Google account, legal name, address, phone, government ID, and US$25 payment | Register at https://play.google.com/console/ with your own identity and payment method | Create the app record, upload an AAB, fill Data safety/content rating/permissions, prepare the closed test |
| B2 | Accept store developer agreements | Legal acceptance must be made by the owner | Read and accept each store's agreement in its console | Enter listing data and submit builds |
| B3 | Provide a monitored support email | Play and other stores require a public contact address; the agent must not invent one | Decide which address to publish (a project address or a monitored inbox) | Put it in every listing and the privacy policy |
| B4 | Complete tax/banking details (Amazon, Samsung commercial seller, others if requested) | Tax identity and bank details are owner-only | Complete the store's tax interview/banking form | Continue with the listing once the account is active |
| B5 | Content-rating legal certification | The questionnaire is a legal declaration by the developer | Review `policies/CONTENT_RATING_NOTES.md` and submit the answers yourself | Fix any listing text that conflicts with the assigned rating |
| B6 | Export-compliance declaration | Apps with encryption (the optional VNC password uses AES-GCM) may trigger export questions | Answer the store's export questionnaire accurately; get legal advice if unsure | Record the answer and keep it consistent across stores |
| B7 | Android Developer Verification | Public sideload trust requires identity verification in participating regions from 2026-09-30, expanding in 2027 | Register at https://developer.android.com/developer-verification when available in your region | Add the verification status to the release/site docs |

---

## 2. Google Play technical gates

| # | Blocked action | Why it is blocked | Exact owner action | What the agent can do after |
|---|---|---|---|---|
| B8 | Submit to Google Play | New apps must target **API 36** (since 2026-08-31); the APK targets 34 | Approve an AGP/compileSdk upgrade and a test plan; this is a code/toolchain change | Prepare the upgrade branch, migration notes, and CI matrix |
| B9 | Upload a Google Play build | New apps require an **AAB**; only an APK exists | Approve CI `bundleRelease` and provide signing secrets | Add the AAB to the release workflow and verify it |
| B10 | Keep `MANAGE_EXTERNAL_STORAGE` on Play | Google restricts all-files access to specific app categories; Linexa is a Linux runtime | Decide: remove the permission (recommended; SAF already works) or attempt a declaration | Remove the manifest permission/settings row or draft the declaration |
| B11 | Complete the 12-tester / 14-day closed test | New personal Play accounts must run a closed test before production | Recruit 12 testers, opt them in, and keep them opted in for 14 continuous days | Prepare the tester instructions and feedback form |
| B12 | Apply for Play production access | Requires the closed test and answers about readiness | Submit the production-access application as the account owner | Track the review and fix issues |

**Recommendation:** do not start the Play clock until B8, B9, and B10 are
resolved. A 34-target build cannot be submitted as a new app, and the
all-files declaration is likely to be rejected for this category.

---

## 3. Other store gates

| # | Store | Blocked action | Why it is blocked | Exact owner action |
|---|---|---|---|---|
| B13 | Samsung Galaxy Store | Create seller account / submit | Samsung account + commercial seller status; business/tax details | Register at https://seller.samsungapps.com/ and apply for commercial seller status |
| B14 | Samsung Galaxy Store | Pass 16KB page-size review | `libtermux.so` arm64 LOAD segments are 0x1000-aligned; Samsung requires 16KB support for new/updated Android 15+ apps from 2026-07-01 | Approve a `libtermux.so` rebuild with 16KB alignment, or confirm the exact scope with Seller Portal |
| B15 | Huawei AppGallery | Create developer account / submit | HUAWEI ID + identity verification | Register at https://developer.huawei.com/consumer/en/appgallery/ |
| B16 | Xiaomi GetApps | Create developer account / submit | Mi Developer account + email/phone OTP | Register at https://global.developer.mi.com/ |
| B17 | Amazon Appstore | Create developer account / submit | Amazon customer account + free developer account + identity/tax details | Register at https://developer.amazon.com/apps-and-games |
| B18 | Aptoide | Create Connect account / submit | Account + publisher review | Register at https://connect.aptoide.com/ |
| B19 | Uptodown | Create Developers Console account / submit | Account + acceptance of developer terms | Register at https://www.uptodown.dev/sign-up |
| B20 | APKPure | Open the developer portal | Cloudflare challenge and HTTP 403 to non-browser clients | Open https://apkpure.com/developer in a normal browser and complete the challenge yourself |
| B21 | F-Droid | Submit to fdroiddata | Release build gate requires signing credentials; prebuilt native binaries are not automatically trusted; no GitLab account | Decide on the build-gate approach and prebuilt-binary provenance; create a GitLab account |
| B22 | IzzyOnDroid | Open inclusion issue | 57.3 MB APK vs the usual 30 MB guideline; no Codeberg account | Create a Codeberg account and decide whether to request a size exception or wait for an ABI split |
| B23 | Accrescent | Apply | targetSdk 34 vs Play-derived 36; alpha store; account availability unverified | Approve targetSdk 36 and remove/justify all-files access first |

---

## 4. Release infrastructure gates

| # | Blocked action | Why it is blocked | Exact owner action | What the agent can do after |
|---|---|---|---|---|
| B24 | Build a signed APK/AAB in CI | No GitHub Actions signing secrets exist | Add the four secrets in `STORE_CREDENTIAL_SETUP.md` | Run the release workflow and verify the artifacts |
| B25 | Publish a new version | No new version code or build exists | Decide the next version and approve the build | Bump versionCode/versionName, run CI, update release notes |
| B26 | Reproducible-build proof | No deterministic build comparison exists | Approve a reproducible-build job and toolchain pinning | Add the job and compare hashes |
| B27 | Per-ABI APKs | No split build exists | Approve CI split configuration | Produce arm64-v8a/armeabi-v7a APKs and update listings |
| B28 | 16KB-compatible build | `libtermux.so` must be rebuilt | Approve the JNI rebuild | Verify all arm64 LOAD alignments and update Samsung/Play artifacts |

---

## 5. Evidence and asset gates

| # | Missing item | Why it is missing | Exact owner action |
|---|---|---|---|
| B29 | Tablet screenshots | No tablet UI capture exists; fabricating one would be dishonest | Capture real tablet screenshots on a tablet/emulator, or leave the optional field empty |
| B30 | Runtime QA on a physical device | The agent performed static artifact verification only | Install the APK on a supported device and test install, workspace creation, terminal, download, file manager, and desktop |
| B31 | Store-specific review notes / demo account | No account exists; the app needs no login | Use the reviewer instructions in `metadata/google-play.md`; no demo account is needed |
| B32 | Trademark/name clearance | No legal search was performed | Decide whether to clear the "Linexa" name in your markets; not a store blocker today |
| B33 | Privacy policy legal review | The policy is accurate to the build but is not legal advice | Have counsel review it if you distribute in regulated markets or at scale |

---

## 6. What is not blocked

These steps were completed and do not need owner intervention:

- Repository/artifact audit and checksum/signature verification.
- Store compatibility matrix and current-requirement research.
- Listing copy for Google Play, Samsung, Xiaomi, Huawei, Amazon, Aptoide,
  Uptodown, APKPure, F-Droid, IzzyOnDroid, Accrescent, and GitHub Releases.
- Privacy policy, Data safety draft, permission declarations, content-rating
  notes.
- Store icons, feature graphic, normalized phone screenshots, Amazon landscape
  screenshots.
- Fastlane metadata tree.
- Release workflow (inert until secrets are added).
- `tools/store-publisher` verification/lint/packet tooling.
- GitHub Pages site and public privacy URL.
- GitHub Release notes/checksum asset update.

---

## 7. Priority order for the owner

1. **Decide the Google Play path.** targetSdk 36 + AAB + remove all-files access
   is the highest-leverage work; it also unblocks Accrescent and improves
   Samsung/Play 16KB readiness.
2. **Add CI signing secrets.** This unblocks signed APK/AAB builds and every
   future release.
3. **Create the free store accounts** (Amazon, Aptoide, Uptodown, Xiaomi,
   Huawei) and submit the already-prepared listings.
4. **Fix `libtermux.so` 16KB alignment** before Samsung submission.
5. **Decide F-Droid/IzzyOnDroid** based on the build-gate, prebuilt-binary, and
   size constraints. These are the most expensive channels to satisfy.
6. **Do not wait for Google Play** to distribute on GitHub, Amazon, Aptoide,
   Uptodown, Xiaomi, or Huawei.

Every store submission must be verified by the store before it is described as
published. A submitted listing is not a published app.
