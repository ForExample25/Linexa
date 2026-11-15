# Content Rating Notes

**Prepared:** 2026-11-15
**App:** Linexa `com.fem.linexa` (`0.1.0-beta`, versionCode 1)
**Purpose:** answer the IARC/Google Play/Samsung/Huawei/Xiaomi/Amazon content
questionnaires consistently and honestly. The account owner makes the legal
submission; this is a draft, not a rating.

---

## 1. What the app contains

Linexa is a developer tool. It contains:

- A workspace dashboard, settings, and a file manager.
- A terminal emulator and a Linux package-manager front end.
- A VNC client and a WebView that loads **only** `http://127.0.0.1:<port>`
  from the user's own workspace.
- No built-in social feed, chat, comments, user profiles, or user-to-user
  content sharing.
- No advertising, in-app purchases, gambling, or loot boxes.
- No camera, microphone, location, contacts, or SMS features.

The app can run arbitrary software inside a user-created Linux workspace. That
software can include browsers, games, media players, or anything else the user
installs. That is user-controlled content, not content supplied by Linexa.

---

## 2. Recommended questionnaire answers

| Question | Answer | Notes |
|---|---|---|
| Violence | No | No violent content supplied by the app |
| Blood / gore | No | — |
| Sexual content / nudity | No | — |
| Profanity / crude humour | No | — |
| Alcohol, tobacco, drugs | No | The guest OS may contain package names, but the app supplies no such content |
| Gambling | No | No gambling mechanics or real-money gambling |
| Horror / fear | No | — |
| Discrimination / hate | No | — |
| User-generated content | No built-in UGC | The app has no social features; guest software is user-controlled |
| Users can interact online | No built-in interaction | A terminal and guest software can access the network; disclose if the form treats this as interaction |
| Shares location | No | No location permission |
| Shares personal information | No | No account, no collection |
| Digital purchases | No | No IAP |
| Advertising | No | No ad SDK |
| Unrestricted internet access | **Disclose** | The app is not a browser; its WebView is loopback-only. Guest software and the terminal can access the internet under the user's control. If the questionnaire treats that capability as unrestricted access, answer yes and explain. |
| User can download/execute code | **Yes** | RootFS downloads and package installs are core, user-initiated functions; no silent updates |
| User can install other Android apps | No | `REQUEST_INSTALL_PACKAGES` is absent |
| Personal information collected | No | See `DATA_SAFETY_ANSWERS.md` |
| Account creation | No | — |
| Age assurance | No | The app has no age-gated content or purchases |

### Suggested free-text explanation

> Linexa is a Linux workspace manager for developers. It does not supply
> violent, sexual, gambling, or social content. Users can install a Linux
> distribution and run software inside it; that software is controlled by the
> user and may access the internet. The app itself has no browser for arbitrary
> websites: its WebView loads only the user's local workspace on 127.0.0.1.
> RootFS downloads happen only after an explicit user action and are verified
> where the catalog provides a checksum.

---

## 3. Expected rating

There are two defensible outcomes:

| Scenario | Likely rating |
|---|---|
| The questionnaire treats the app as a tool with no supplied content and no built-in unrestricted browser | **Everyone / PEGI 3** |
| The questionnaire treats terminal/guest networking as unrestricted internet access, or treats user-installed software as UGC | **Everyone 10+ / PEGI 12** (or the platform's equivalent) |

Do not argue for a lower rating than the platform assigns. The honest position
is: no content of our own, but a powerful user-controlled runtime. If a store
asks for a higher rating because of that capability, accept it and explain the
product accurately.

F-Droid does not use IARC ratings. IzzyOnDroid and Accrescent do their own
policy review and do not require an IARC certificate.

---

## 4. Store-specific notes

| Store | Rating system | Draft answer |
|---|---|---|
| Google Play | IARC questionnaire | Complete the questionnaire with the table above; expect Everyone or Everyone 10+ |
| Samsung Galaxy Store | Samsung age rating | Everyone; confirm the questionnaire's internet/UGC questions |
| Huawei AppGallery | Huawei age rating | 3+ or the platform's equivalent; confirm at upload |
| Xiaomi GetApps | Xiaomi rating | Everyone; confirm at upload |
| Amazon Appstore | Amazon content rating | Everyone; complete the privacy labels separately |
| Aptoide | Aptoide/IARC-style | Everyone/PEGI 3; confirm at upload |
| Uptodown | Uptodown review | No IARC certificate published; describe the app accurately |
| APKPure | APKPure review | No IARC certificate published; describe the app accurately |
| F-Droid | No age rating | Anti-feature review only |
| IzzyOnDroid | No age rating | Policy review |
| Accrescent | No age rating | Security/privacy review |

---

## 5. Legal confirmations the owner must make

The agent cannot truthfully answer these on the owner's behalf:

- [ ] I am the owner or authorised representative of the app.
- [ ] The app does not contain content that violates the store's content
      policy.
- [ ] The privacy policy is accurate for the published build.
- [ ] The app's permissions and data-safety answers are complete.
- [ ] I accept the store's developer agreement and any age-rating
      certification.
- [ ] I will respond to user reports and store review requests.

Do not submit a rating questionnaire with invented answers. A wrong declaration
can cause removal and account penalties.
