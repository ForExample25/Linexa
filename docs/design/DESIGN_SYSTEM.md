# Design System

**Status:** Current — describes the implemented HAVEN design tokens, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/ui/theme/haven/HavenColors.kt`, `app/src/main/java/com/fem/linexa/ui/theme/haven/HavenTheme.kt`, `app/src/main/java/com/fem/linexa/ui/theme/Dimens.kt`, `Type.kt`, `ColorThemes.kt`, `Theme.kt`, `app/src/main/res/values/`, `app/src/main/res/values-fa/`, `app/src/main/res/values-night/`

---

## 1. Purpose

This document is the authoritative reference for Linexa's design tokens: colors, typography, spacing, radii, elevation, and the HAVEN token chain projected onto M3 `ColorScheme`.

All token documentation is consolidated here. Do not maintain competing token specifications elsewhere.

---

## 2. Scope

In scope:

- `HavenColors` token class and `toMaterialScheme()` projection onto M3
- Color palettes (dark/light, accents)
- Typography (`FontFamily.Default` chrome, `FontFamily.Monospace` technical)
- Spacing (4-dp grid `Spacing` object)
- Radii (`Corners` object in `Dimens.kt`)
- `ColorTheme` enum (ALPINE_BLUE default, LAVENDER, FOREST, SUNSET)
- Elevation (`Elevation` object, tint over shadow)

Out of scope:

- UI/UX philosophy and principles: [`design/UI_UX_GUIDELINES.md`](./UI_UX_GUIDELINES.md)
- Accessibility & localization: [`design/ACCESSIBILITY_AND_LOCALIZATION.md`](./ACCESSIBILITY_AND_LOCALIZATION.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| `HavenColors` token class | Implemented | `ui/theme/haven/HavenColors.kt` |
| `HavenTheme` theme accessor | Implemented | `ui/theme/haven/HavenTheme.kt` |
| `toMaterialScheme()` projection | Implemented | `HavenColors.kt` |
| Dark default palette | Implemented | `#101113/#17181B/#1E2024`, primary `#2AD178` |
| Light palette (first-class, not inversion) | Implemented | `#F7F7F5`, primary `#0E9F5D` |
| `ColorTheme` enum (ALPINE_BLUE, LAVENDER, FOREST, SUNSET) | Implemented | `domain/settings/ColorTheme.kt` |
| Terminal always dark in both themes | Implemented | `HavenTerminal` |
| `Dimens.kt` `Spacing` (4-dp grid) | Implemented | `ui/theme/Dimens.kt` |
| `Dimens.kt` `Corners` (radii) | Declared; **0 external uses** | `ui/theme/Dimens.kt` |
| `Dimens.kt` `Elevation`, `IconSize`, `TouchTarget`, `ContentPadding` | Declared; `Elevation`/`IconSize`/`ContentPadding` have 0 external uses | `ui/theme/Dimens.kt`; `Spacing` is the only adopted token set (66 uses) |
| `Type.kt` typography (`Stat*` styles) | Implemented (some unused) | `ui/theme/Type.kt`; `StatTypography` is unreferenced |
| 861 raw `.dp` literals | Drift present | `Dimens.kt` KDoc rule not fully followed |
| 28 raw `Color(0x…)` literals outside `ui/theme` (22 in `DistroIcons.kt`) | Acceptable (brand colors) | `ui/screens/*` |
| `MaterialTheme.colorScheme` 91 refs vs 208 direct Haven accessor uses | Two access paths | most surfaces consume HAVEN directly; legacy M3 components use the projection |
| Dynamic color (0 uses of `dynamic*ColorScheme`) | Not implemented | — |

**Note:** Some older notes referenced `HavenDim.kt` and `HavenType.kt` as separate token files. Those files do **not** exist in the current source. Radii live in `Dimens.kt` (`Corners`), typography in `Type.kt`, and the theme accessor in `HavenTheme.kt`. The `HavenDim`/`HavenType` names appear only in KDoc comments and are not defined as code objects. The declared `Corners`/`Elevation`/`IconSize`/`ContentPadding` objects are largely bypassed by raw literals; one token layer must be chosen before migration.

---

## 4. Technical overview

### 4.1 Token chain

```
theme → HavenTheme.colors (HavenColors) → ui/components/haven/* → screens
                                      ↳ projected onto M3 ColorScheme via HavenColors.toMaterialScheme()
```

Spacing, radii, elevation, icons and touch targets are **declared** in `Dimens.kt` (`Spacing`, `Corners`, `Elevation`, `IconSize`, `TouchTarget`, `ContentPadding`), but only `Spacing` is widely adopted; the others are bypassed by raw literals. Typography comes from `Type.kt`.

### 4.2 Colors

`HavenColors` defines: `bg`, `surface`, `surface2`, `border`, `text`, `text2`, `muted`, `primary`, `tint`, `red`, `amber`, `blue` + tints, `terminal`.

Dark default:
- `bg #101113`, `surface #17181B`, `surface2 #1E2024`, primary `#2AD178`

Light (first-class, not inversion):
- `bg #F7F7F5`, primary `#0E9F5D`

`ColorTheme` enum: `ALPINE_BLUE` (default), `LAVENDER`, `FOREST`, `SUNSET`, persisted by stable key. Unknown keys fall back to default (safe downgrade).

Semantic `red/amber/blue` and the terminal ground are fixed — status colors must not drift with wallpaper.

### 4.3 Typography

- System sans (`FontFamily.Default`) for chrome
- `FontFamily.Monospace` for every technical surface via `asMono()`
- Four extra `Stat*` styles for metrics (`ui/theme/Type.kt`)

### 4.4 Spacing

4-dp `Spacing` grid object (`ui/theme/Dimens.kt`): `xxs` 2dp, `xs` 4dp, `sm` 8dp, `md` 12dp, `lg` 16dp, `xl` 20dp, `xxl` 24dp, `xxxl` 32dp, `huge` 48dp.

### 4.5 Radii

`Corners` object in `ui/theme/Dimens.kt`: `none` 0dp, `xs` 6dp (chips/pills), `sm` 10dp (buttons/inputs), `md` 14dp (cards), `lg` 18dp (sheets/dialogs), `xl` 22dp (hero cards), `xxl` 28dp (full-bleed sheets).

### 4.6 Elevation

`Elevation` object in `ui/theme/Dimens.kt`: `none` 0dp, `raised` 1dp, `card` 2dp, `floating` 4dp, `modal` 8dp. Linexa prefers surface tint over shadow.

---

## 5. Limitations and known issues

- 637 inline `.dp` literals in `ui/screens/*` despite the file-level rule that shared components and screens MUST pull spacing from `Dimens.kt`. Drift, not a bug.
- No dynamic color (0 uses of `dynamic*ColorScheme`).
- Contrast of palettes against WCAG AA not measured.
- theming still needs on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`design/UI_UX_GUIDELINES.md`](./UI_UX_GUIDELINES.md) — UI/UX philosophy, principles, navigation chrome
- [`design/ACCESSIBILITY_AND_LOCALIZATION.md`](./ACCESSIBILITY_AND_LOCALIZATION.md) — accessibility, localization
- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — package layering
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
