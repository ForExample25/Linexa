# Accessibility and Localization

**Status:** Current — describes the implemented accessibility and localization, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/res/values/strings.xml`, `app/src/main/res/values-fa/strings.xml`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/fem/linexa/ui/screens/*`, `app/src/main/java/com/fem/linexa/ui/components/*`

---

## 1. Purpose

This document is the authoritative reference for Linexa's accessibility and localization: content descriptions, semantics/Role, Persian (FA) translation coverage, RTL support, and contrast status.

---

## 2. Scope

In scope:

- `contentDescription` / `semantics` / `Role` usage and gaps
- Persian translation coverage and RTL
- Contrast status (not measured)
- String resource conventions

Out of scope:

- Token values: [`design/DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md)
- UI/UX philosophy: [`design/UI_UX_GUIDELINES.md`](./UI_UX_GUIDELINES.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| `contentDescription` references | 152 | `ui/screens/*`, `ui/components/*` |
| `contentDescription = null` sites | 52 | each needs decorative/actionable classification |
| Explicit `Role.*` uses | 9 of 40 clickables | `Role.Button`, `Role.Switch`; semantics coverage is thin |
| String resources (en) | 1,117 | `res/values/strings.xml` |
| String resources (fa) | 197 (17.6%) | `res/values-fa/strings.xml`; 920 English keys missing |
| `supportsRtl` | Declared | `AndroidManifest.xml` |
| `stringResource` uses | 933 | `ui/screens/*` |
| Hard-coded user-visible English | Found (not zero) | e.g. `HavenTerminal.kt`, `ActivityScreen.kt`, clipboard labels |
| Contrast (WCAG AA) | Not measured | — |
| Dynamic color contrast risk | Not implemented | 0 uses of `dynamic*ColorScheme` |

---

## 4. Technical overview

### 4.1 Accessibility

Linexa uses `contentDescription`, `semantics`, and `Role` (`Role.Button`, `Role.Switch`) across screens and components. The current tree has **52** `contentDescription = null` sites and only **9 explicit `Role.*` uses across 40 clickables**. The null descriptions are not yet classified; each is either decorative (correct) or an unlabeled actionable control (defect). The heaviest sites are `AppLauncherSegment.kt`, `FileManagerScreen.kt`, and `HavenSurfaces.kt`.

No automated accessibility test or content-description coverage test exists in the current suite. A semantics/TalkBack pass is still required.

### 4.2 Localization

Linexa uses `stringResource` (933 uses) with `values/` (English, 1,117 strings) and `values-fa/` (Persian, 197 strings, 17.6%). `supportsRtl="true"` is declared in the manifest. Persian falls back to English for the **920** missing keys, so a Persian-locale user sees a mixed-language UI. There is no `locales_config.xml` and no `setApplicationLocales` call, so the in-app language follows the system only. Some user-visible English is still hard-coded.

Translation coverage by domain is incomplete: 0% coverage in `sessions`, `install`, `haven`, `tm`, `pkg`, `fm`, `perf` domains.

No parity test for string completeness exists.

### 4.3 Contrast

Contrast of `LAVENDER` and `ALPINE_BLUE` themes against WCAG AA has not been measured. Haven's "one soft-green primary" identity and muted text derived from wallpaper (if dynamic color were added) are contrast risks. No dynamic color is currently implemented, so this is a future risk only.

---

## 5. Limitations and known issues

- 52 null `contentDescription` sites are unclassified; each needs decorative/actionable classification.
- Persian coverage 17.6% (197/1,117); 920 missing keys.
- Hard-coded user-visible English remains, so translation alone cannot complete localization.
- No string-completeness parity test and no literal-string guard.
- No automated accessibility/contrast test.
- Contrast against WCAG AA not measured for any palette.
- accessibility/localization still needs on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`design/DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md) — token values, color palettes
- [`design/UI_UX_GUIDELINES.md`](./UI_UX_GUIDELINES.md) — UI/UX principles, RTL capability
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
