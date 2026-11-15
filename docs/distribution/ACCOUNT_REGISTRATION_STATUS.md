# Account Registration Status

**Date:** 2026-11-15
**Rule applied:** no account was created with invented identity, email, phone,
address, tax, or business data. No paid account was purchased.

---

## 1. What the automation session can and cannot do

| Capability | Finding |
|---|---|
| GitHub CLI authentication | Authenticated as `ForExample25` (active), with `repo` + `workflow` scopes. `ForExampleZERO` and `astellar2412-art` are also stored in `gh` but inactive. |
| Browser automation | A Chrome DevTools session is available (navigate/evaluate/screenshot). |
| Google Play Console session | **Not authenticated.** `play.google.com/console/` redirects to the public marketing page. |
| Xiaomi developer session | **Not authenticated.** The portal redirects to `account.xiaomi.com` sign-in. |
| Samsung Seller Portal session | **Not authenticated.** Public docs show a Sign in link only. |
| Huawei developer session | **Not authenticated.** Release docs render empty without a HUAWEI ID session. |
| Amazon developer session | **Not authenticated.** Public portal shows "Sign in". |
| Aptoide / Uptodown / APKPure sessions | None. APKPure additionally presents a Cloudflare challenge. |
| Play Developer API / Huawei AGC API / Samsung API credentials | None. No service-account JSON, no OAuth tokens. |
| Signing secrets in GitHub Actions | None. No repository secrets for the release keystore. |

Therefore **zero developer accounts were created**. Every account field below
remains `WAITING_FOR_USER`. This is the honest state; any claim of registration
would require the owner's email, OTP, identity document, and/or payment card.

---

## 2. Account status by platform

| Platform | Account exists? | Status | Fee | Verification | Exact action required |
|---|---|---|---|---|---|
| Google Play | Unknown | `WAITING_FOR_USER` | US$25 one-time | Google account, government ID, payment card, address, phone, Android-device verification (personal accounts) | Sign in at https://play.google.com/console/ and complete registration. Then run the 12-tester/14-day closed test. |
| Android Developer Verification | Unknown | `WAITING_FOR_USER` | Free hobbyist tier; standard verification terms apply | Government ID for public distribution | Register at https://developer.android.com/developer-verification when the flow is available in your region; required for public sideload trust from 2026-09-30 (select regions) and 2027 globally. |
| Xiaomi GetApps | Unknown | `WAITING_FOR_USER` | None published | Email/phone OTP, identity as requested | Create a Mi Developer account at https://global.developer.mi.com/ |
| Samsung Galaxy Store | Unknown | `WAITING_FOR_USER` | None published | Samsung account, commercial seller application, business/tax details | Register at https://seller.samsungapps.com/ and apply for commercial seller status. |
| Huawei AppGallery | Unknown | `WAITING_FOR_USER` | None published | HUAWEI ID, identity verification | Register at https://developer.huawei.com/consumer/en/appgallery/ |
| Amazon Appstore | Unknown | `WAITING_FOR_USER` | Free developer account | Amazon customer account, identity/tax details as requested | Create at https://developer.amazon.com/apps-and-games |
| Aptoide | Unknown | `WAITING_FOR_USER` | None published | Email/developer details | Create an Aptoide Connect account at https://www.aptoide.com/developers |
| Uptodown | Unknown | `WAITING_FOR_USER` | Free | Email, acceptance of developer terms | Register at https://www.uptodown.com/consola-de-desarrolladores |
| APKPure | Unknown | `WAITING_FOR_USER` | None published | Email, manual verification | Open https://apkpure.com/developer in a normal browser and complete the Cloudflare check yourself. |
| F-Droid | No GitLab account | `WAITING_FOR_USER` | Free | GitLab account | Create a GitLab account, then file an RFP or a fdroiddata merge request. |
| IzzyOnDroid | No Codeberg account | `WAITING_FOR_USER` | Free | Codeberg account | Create a Codeberg account and open an inclusion issue (after the size decision). |
| Accrescent | Unknown | `NOT_ELIGIBLE` | None published | Developer Console | Deferred until targetSdk 36; https://accrescent.app/ |

---

## 3. GitHub Actions signing secrets

The repository has **no** release workflow and **no** signing secrets. The
release key exists only locally at `keystore/linexa-release.jks` and is
gitignored. Its passwords are in the gitignored `local.properties`.

To let CI build signed artifacts, the owner must add these repository secrets
(never commit them):

| Secret | Contents |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of `keystore/linexa-release.jks` |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

Commands and rotation guidance are in
[`STORE_CREDENTIAL_SETUP.md`](./STORE_CREDENTIAL_SETUP.md). The agent did not
read, print, encode, or upload the keystore or its passwords.

---

## 4. What happens after the owner completes each registration

| Platform | Immediate next step by the agent/owner |
|---|---|
| Google Play | Create app record, upload AAB (after targetSdk 36 fix), complete Data safety/content rating/permissions declarations, closed test, production access |
| Xiaomi | Create listing, upload `app-release.apk`, submit for review |
| Samsung | Create listing, upload APK, submit; confirm 16KB requirement status |
| Huawei | Create app in AppGallery Connect, upload APK, submit |
| Amazon | Create app, upload APK, complete privacy labels, submit |
| Aptoide | Create app in Connect, upload APK, submit |
| Uptodown | Create app, upload APK, submit |
| APKPure | Create app, upload APK, submit |
| F-Droid | Submit the prepared Fastlane metadata and build recipe; respond to reviewer questions |
| IzzyOnDroid | Open inclusion issue with release URL, Fastlane metadata, and size-exception request |

The metadata and assets for each of these steps are already prepared under
`docs/distribution/metadata/` and `docs/distribution/assets/`.
