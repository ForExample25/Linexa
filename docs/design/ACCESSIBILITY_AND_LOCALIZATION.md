# Accessibility and Localization

**Source locations:** `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fa/strings.xml`, `app/src/main/res/xml/locales_config.xml`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/fem/linexa/core/i18n/AppLocale.kt`, `app/src/main/java/com/fem/linexa/MainActivity.kt`, `app/src/main/java/com/fem/linexa/ui/screens/*`, `app/src/main/java/com/fem/linexa/ui/components/*`

---

## 1. Purpose

This document is the authoritative reference for Linexa's accessibility and localization: content descriptions, semantics/Role, Persian (FA) translation coverage, the fixed-LTR layout policy, text-level RTL rendering, and contrast status.

---

## 2. Scope

In scope:

- `contentDescription` / `semantics` / `Role` usage and gaps
- Persian translation coverage, fixed-LTR layout, and text-level RTL
- Contrast status (not measured)
- String resource conventions

Out of scope:

- Token values: [`design/DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md)
- UI/UX philosophy: [`design/UI_UX_GUIDELINES.md`](./UI_UX_GUIDELINES.md)

---

## 3. Current state

| Area | Value | Source |
|---|---|---|
| `contentDescription` references | 171 | `ui/screens/*`, `ui/components/*` |
| `contentDescription = null` sites | 58 | each is decorative or needs a description |
| Explicit `Role.*` uses | 13 of 79 clickable sites | `Role.Button`, `Role.Switch` |
| String resources (en) | 1,354 strings + 14 plurals | `res/values/strings.xml` |
| String resources (fa) | 1,354 strings + 14 plurals (100%) | `res/values-fa/strings.xml` |
| In-app language selector | System default / English / فارسی | Settings → Language, before any workspace |
| Language persistence | `linexa_settings` / `app_language` | `AppLanguage`, `SettingsRepositoryImpl` |
| Runtime language switch | Immediate; no activity recreation | `AppLocale`, `MainActivity`, `AppLocaleViewModel` |
| Layout direction | Always LTR, in every language | `AndroidManifest.xml`, `AppLocale`, root `LocalLayoutDirection` |
| `supportsRtl` | `false` — declines RTL layout mirroring, not Persian support | `AndroidManifest.xml` |
| Per-app locale declaration | `en`, `fa` | `AndroidManifest.xml`, `res/xml/locales_config.xml` |
| `stringResource` uses | 1,079 | `ui/screens/*`, `ui/components/*` |
| Hard-coded user-visible English | Technical/brand names; rare runtime fallback errors and developer report stay English | see section 5 |
| Contrast (WCAG AA) | Not measured | — |
| Dynamic color | Not implemented | no `dynamic*ColorScheme` API use |

---

## 4. Technical overview

### 4.1 Accessibility

Linexa uses `contentDescription`, `semantics`, and `Role` (`Role.Button`, `Role.Switch`) across screens and components. The current tree has **58** `contentDescription = null` sites and **13 explicit `Role.*` uses across 79 clickable sites**. A null description is appropriate for decorative content; an actionable control should carry one. The heaviest sites are `AppLauncherSegment.kt`, `FileManagerScreen.kt`, and `HavenSurfaces.kt`.

No automated accessibility or content-description coverage test exists in the current suite. TalkBack behavior requires a manual pass.

### 4.2 Localization

Linexa ships English (`values/`) and Persian (`values-fa/`) resources with **1,354 strings and 14 plurals in each**, so every English key has a Persian counterpart. `LocalizationSourceTest` fails if a key is added to one file only, if a `%1$s`/`%1$d` placeholder disappears, or if fewer than 90% of Persian resources contain Persian text.

#### Preference model

`AppLanguage` is tri-state: `SYSTEM`, `ENGLISH`, `PERSIAN`. `SYSTEM` is the fresh-install default and walks the device locale list in priority order, choosing the first of `en`/`fa`; an unsupported device language falls back to English. An explicit choice is stored under `linexa_settings` / `app_language` and is never replaced by a later system-language change. `AppLanguage.fromKey` treats missing/unknown persisted values as `SYSTEM`.

#### Applying the language

The root composition in `MainActivity` observes `AppLocaleViewModel.appLanguage` and wraps the whole tree in `CompositionLocalProvider`:

- `LocalContext` → `AppLocale.localizedContext(baseContext, appLanguage)`
- `LocalConfiguration` → the localized context's configuration (with a forced LTR layout direction)
- `LocalLayoutDirection` → `LayoutDirection.Ltr`, unconditionally

This is deliberately **not** an activity recreation and **not** `AppCompatDelegate`. A recreation would tear down the nav graph and the workspace's terminal/desktop surfaces; the wrapper shares the activity token and services while giving resources their own locale. Language changes therefore do not stop a workspace, reinstall Linux, modify workspace files, or reset settings.

The selector lives in **Settings → Language**, the first group on the screen, and is reachable before any workspace exists. Every row names its language in its own script (`English`, `فارسی`), and the selected row carries a translated check-mark description.

#### 4.2.1 Global LTR layout policy

Linexa's interface structure is **always left-to-right**, in English and in Persian. Changing the language changes resources, text, and locale-aware formatting; it never changes row order, icon sides, padding, navigation/toolbar arrangement, dialog geometry, workspace surfaces, the file manager, or any other layout structure.

The policy is enforced at every boundary where a layout direction can enter:

| Boundary | Enforcement | Effect |
|---|---|---|
| Platform | `android:supportsRtl="false"` in `AndroidManifest.xml` | The platform keeps the app's layouts LTR regardless of the locale's layout direction. The attribute is a layout declaration only; it does not remove locale resources or bidi text support. |
| Resource/config contexts | `AppLocale.localizedContext(...)` applies the locale with `setLocales(...)` and then forces `setLayoutDirection(Locale.ROOT)` | `LocalConfiguration`, platform views created from the localized context, and non-composition callers all see an LTR configuration. `locales` still resolves to `en`/`fa`. |
| Compose composition | The root `CompositionLocalProvider` provides `LocalLayoutDirection = LayoutDirection.Ltr` unconditionally | Every screen, dialog, popup, menu, toolbar, list, and nested component inherits LTR. No screen derives its direction from `appLanguage`. |

`android:supportsRtl="false"` does **not** disable Persian. `values-fa/`, the language selector, `res/xml/locales_config.xml`, and Android's per-app language screen remain in place. It only declines RTL *layout mirroring*, which this app does not use.

Layout direction is a constant of the application, not a persisted preference. Switching language only swaps resources, and a process restart re-applies the manifest flag, the LTR configuration, and the root `LocalLayoutDirection` in the same order, so the interface cannot flip after a restart.

Keep these three layers aligned if the policy ever changes. A screen must not provide its own `LocalLayoutDirection`, and code must not reintroduce a language-to-layout-direction helper such as `isRtl(language)`; the source guards in `LocalizationSourceTest` fail if either appears.

#### 4.2.2 Text-level RTL and technical content

Text direction is separate from layout direction and is intentionally **not** forced to LTR:

- Compose text styles leave `textDirection` as `TextDirection.Unspecified`. The text pipeline resolves that with Android's first-strong bidi heuristic, so Persian prose, punctuation (`، ؛ ؟`), ZWNJ compounds, and mixed Persian/English runs render in their correct RTL text direction inside the LTR layout.
- Alignment uses logical `Start`/`End`, so chrome aligns to the LTR edges while each text paragraph aligns according to its own resolved base direction.
- Terminal output, commands, paths, URLs, package names, IDs, and log/trace text are technical content and stay LTR. `HavenType.descMono`, `mono12`, `num14`, `num22`, `num26`, `num44`, `term`, and `monoBlock` pin `TextDirection.Ltr` with `TextAlign.Start`; prose/heading styles do not.
- Terminal/PTY content and the VNC surface are rendered by their own views and are not transformed by the UI language.

#### 4.2.3 Adaptive text layout for longer translations

Persian copy is often wider and taller than its English source, so the layout contracts are "grow, don't clip" for anything the user reads as prose:

| Surface | Contract |
| --- | --- |
| Page headers (`HPageHeader`) and app bars (`HTopBar`, `LinexaTopBar`) | The title block takes the flexible width (`weight(1f, fill = false)`) and wraps; trailing counts/actions keep their space. Prose subtitles wrap in full. Mono subtitles (paths, IDs) keep one ellipsized line. |
| Shared rows (`HRow`), resource cards, fact/value rows, sidebar rows | Minimum heights (`heightIn(min = …)`), never fixed text heights. Titles wrap; prose descriptions show in full. Mono descriptions (paths, sizes) keep a two-line ellipsis. |
| Buttons, segmented controls, chips, pills, badges | The design height is a minimum. Labels wrap to two lines and the control grows; segmented cells share the tallest label via `IntrinsicSize.Min`, so the sliding selection pill still fits. |
| Dialogs and sheets | `HDialog` scrolls its body inside the dialog and measures the action row outside the scroll area, so the primary button is always reachable. Material alert dialogs cap their message at a measured fraction of the available slot and scroll it. Content-heavy sheets opt into `HSheet(skipPartiallyExpanded = false)`, which makes the body scroll. |
| Technical content | Terminal output, commands, paths, IDs, log lines, and compact grid-tile labels may still ellipsize; this is a deliberate constraint, not localization truncation. |

Paragraphs and supporting copy use natural wrapping and the screen's existing vertical scroll. No screen adds horizontal scrolling for ordinary prose, and no Persian font size is reduced to make a translation fit.

#### 4.2.4 Script-aware typography (Persian)

Persian is an Arabic-script language, so two Latin-oriented typography habits are wrong for it, and `HavenType` handles both centrally rather than per screen:

- **No letter-spacing.** Tracking is added after every glyph; in a joining script it opens gaps inside words and breaks the cursive connection. Tracked styles (`eyebrow`, `section`, `brand`, `navLabel`, `tileLabel`, and the negative tracking on `h1`–`h3`) get `0` in Persian. Code that needs a one-off tracking value uses `HavenType.tracking(em)` instead of a literal `em(...)`.
- **A fixed line box.** Without an explicit line height Compose uses the font's own ascent/descent, and the system Arabic-script fallback face is much taller than the Latin one, which made labels, buttons and rows grow. Prose styles that declare no line height get `HavenScript.ARABIC_LINE_HEIGHT_EM` (1.3 em) in Persian. Styles that already declare one (`desc`, `term`, `monoBlock`) are untouched, and English metrics are unchanged.

`HavenType`'s prose styles are declared once in Latin metrics (`HavenLatinType`) and served through getters that apply this calibration. The script flag (`HavenScript`) is published once by `LinexaTheme` from the localized `LocalConfiguration`; it is not a layout direction and does not touch the fixed-LTR policy above. Font **sizes** are deliberately not scaled: a size calibration must be measured on a device before it is introduced. Technical mono styles are never calibrated. Do not set `letterSpacing` on prose with a literal; `UiUxRefinementSourceTest` fails if a UI file does.

#### Non-composition callers

ViewModels, services, notifications, and the SAF document provider cannot call `stringResource`. They use `Context.linexaString(...)` / `Context.linexaQuantityString(...)`, which read the same persisted preference at the point of use and resolve through the same localized context. Counts use plural resources, not `(s)` suffixes.

`DeviceInfoMapper` stays a pure JVM formatter: measurements and unit symbols (`GB`, `MHz`, `GHz`, `°C`, `mV`, `µA`, `mAh`, `Kbps`) remain universal, while the words around them — `cores`, `free`, `buffers`, `cached`, `reclaimable`, `present`, `enforcing`/`permissive`, on/off state, network validation, uptime units, battery source/health, and thermal severity — resolve through `DeviceInfoLabels`. The Hilt binding holds the application context and looks each label up when a poll runs, so a language switch updates Device & System on its next two-second refresh.

#### Per-app locale declaration

`res/xml/locales_config.xml` lists `en` and `fa`, and `AndroidManifest.xml` points at it with `android:localeConfig`. Android 13+ can therefore show the same languages in the system per-app language UI; API 28–32 use the in-app selector. Both paths agree because the shipped resource folders and the app's preference are the single source of truth.

#### Tests

- `app/src/test/java/com/fem/linexa/domain/settings/AppLanguageTest.kt` — tri-state persistence and fallback semantics.
- `app/src/test/java/com/fem/linexa/core/i18n/LocalizationSourceTest.kt` — key/plural parity, placeholder parity, translation coverage, selector order and all three options, root context/LTR wiring, forced-LTR configuration, manifest/locale-config agreement, LTR technical styles, prose direction left unspecified for bidi, SAF/device-info resource routing, and removal of hard-coded shell/terminal labels.
- `app/src/test/java/com/fem/linexa/ui/components/AdaptiveLayoutSourceTest.kt` — source guard for the adaptive text contract: page headers/top bars wrap localized titles, shared rows/menus/buttons/segments/chips/pills grow vertically, and dialogs scroll a long body while pinning their actions.

A native-speaker editorial pass and on-device LTR + Persian-text screenshots are outside the automated suite; the tests protect coverage and wiring, not literary quality or visual bidi rendering.

### 4.3 Contrast

Contrast of `LAVENDER` and `ALPINE_BLUE` themes against WCAG AA has not been measured. Haven's "one soft-green primary" identity and muted text derived from wallpaper (if dynamic color were added) are contrast risks. No dynamic color is currently implemented.

---

## 5. Limitations

- 58 `contentDescription = null` sites; each is either decorative or needs a description.
- The Persian catalog uses a fixed glossary (workspace/terminal/session/desktop/package-manager/import/export), Persian codepoints, ZWNJ compounds, and Persian punctuation. It has not had a line-by-line native-speaker editorial review; `LocalizationSourceTest` protects coverage and placeholders, not tone.
- Developer-facing diagnostics remain English by design: the VNC render-stats text report (`VncRenderStatsReport`) and runtime/log trace messages are copied into issue reports and refer to counter names. They are technical content, not app chrome.
- A small set of failure strings originate below the UI layer and still surface in English on rare paths: runtime fallbacks such as “The runtime is not connected yet”, “No workspace is attached”, and “Terminal session did not start”, plus domain launch/import validation and archive-security reasons. These are recovered state, not normal chrome.
- Android-supplied state values (for example a storage volume’s `mounted` state or the network transport name) are shown verbatim. They are platform identifiers; Linexa’s own labels around them are localized.
- Brand names and technical identifiers stay untranslated: Linexa, Linux, Android, RootFS, PRoot, VNC, distro names, package names, commands, paths, environment variables, and log filenames.
- No automated accessibility/contrast test. Contrast against WCAG AA has not been measured for any palette.
- An on-device LTR-layout/TalkBack pass and a visual Persian bidi review are outside the automated suite; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`design/DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md) — token values, color palettes
- [`design/UI_UX_GUIDELINES.md`](./UI_UX_GUIDELINES.md) — UI/UX principles, fixed-LTR layout with bidi text
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — test layers and coverage
