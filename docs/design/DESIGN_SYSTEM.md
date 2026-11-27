# Design System

**Source locations:** `app/src/main/java/com/fem/linexa/ui/theme/haven/HavenColors.kt`,
`app/src/main/java/com/fem/linexa/ui/theme/haven/HavenTheme.kt`,
`app/src/main/java/com/fem/linexa/ui/theme/Dimens.kt`,
`app/src/main/java/com/fem/linexa/ui/theme/Type.kt`,
`app/src/main/java/com/fem/linexa/domain/settings/ColorTheme.kt`

---

## 1. Purpose

This document describes Linexa's design tokens: colors, typography, spacing,
radii, elevation, and the projection of HAVEN tokens onto Material 3. It is a
reference for values, not a UI specification; interaction rules live in
[`UI_UX_GUIDELINES.md`](./UI_UX_GUIDELINES.md).

---

## 2. Token chain

```
ColorTheme + dark/light
  → HavenColors (colors, terminal colors, status colors)
  → HavenDim (radii, rhythm, control sizes)
  → HavenType (typography)
  → ui/components/haven/*
  → screens
```

`HavenColors` is also projected onto Material 3 `ColorScheme` via
`toMaterialScheme()`, so legacy Material 3 components inherit approximately
the same palette. Most surfaces consume `HavenTheme.colors` directly; the
Material 3 scheme is a compatibility layer.

Spacing and shape are split between two files:

- `HavenDim` in `HavenColors.kt` is the token set Haven components actually
  use: card/sheet/button radii, screen rhythm, control heights.
- `Dimens.kt` declares `Spacing`, `ContentPadding`, `Corners`, `Elevation`,
  `IconSize`, and `TouchTarget`. `Spacing` is widely used. `Corners` and
  `Elevation` are declared but are not referenced by current UI code; Haven
  components use `HavenDim` instead. `ContentPadding`, `IconSize`, and
  `TouchTarget` have limited use.

New UI should use the Haven component layer rather than mixing the two shape
vocabularies.

---

## 3. Colors

### 3.1 Dark palette (default)

| Token | Value |
|---|---|
| `bg` | `#101113` |
| `surface` | `#17181B` |
| `surface2` | `#1E2024` |
| `border` | white at 6% |
| `text` | `#F2F3F5` |
| `text2` | `#A8ADB4` |
| `muted` | `#6C7178` |
| `primary` | `#2AD178` |
| `tint` | primary at 12% |
| `red` | `#F87171` |
| `amber` | `#FBBF24` |
| `blue` | `#5AB8F0` |
| `terminal` | `#0A0E0F` |

### 3.2 Light palette

| Token | Value |
|---|---|
| `bg` | `#F7F7F5` |
| `surface` | `#FFFFFF` |
| `surface2` | `#F0EFEC` |
| `border` | black at 7% |
| `text` | `#1A1C1E` |
| `text2` | `#5C6167` |
| `muted` | `#9AA0A6` |
| `primary` | `#0E9F5D` |
| `red` | `#D93025` |
| `amber` | `#B45309` |
| `blue` | `#3873B3` |
| `terminal` | `#0A0E0F` |

The terminal surface and terminal text colors are the same in both themes.
The terminal stays near-black, while the shortcut bar follows the theme
(`keyBg`/`keyFg` differ between dark and light).

### 3.3 Accent themes

`ColorTheme` is persisted by stable key. Unknown keys resolve to
`ALPINE_BLUE`.

| Theme | Dark accent | Light accent |
|---|---|---|
| `ALPINE_BLUE` (default) | `#2AD178` | `#0E9F5D` |
| `LAVENDER` | `#B9A7F5` | `#8B7BD8` |
| `FOREST` | `#34A853` | `#0E9F5D` |
| `SUNSET` | `#F28B54` | `#E5533C` |

Only the primary accent changes. Semantic red, amber, and blue and the
terminal ground are fixed so status meaning does not drift with the theme.

---

## 4. Typography

- `FontFamily.Default` (system sans) for chrome, labels, body text, and
  headings.
- `CodeFontFamily` / `FontFamily.Monospace` for terminal content, commands,
  paths, IDs, logs, and technical metrics. Use the `asMono()` extension on a
  text style rather than setting the family ad hoc.
- `StatTypography` defines the larger numeric styles used by metric cards.
- Technical monospace styles pin `TextDirection.Ltr` and `TextAlign.Start`, so
  paths and commands stay readable under every locale. The app layout is always
  LTR; prose/heading styles leave the text direction unspecified so Persian
  text still renders with its native RTL bidi direction.

---

## 5. Spacing

`Spacing` in `Dimens.kt` is a 4-dp grid:

| Token | Value |
|---|---|
| `xxs` | 2 dp |
| `xs` | 4 dp |
| `sm` | 8 dp |
| `md` | 12 dp |
| `lg` | 16 dp |
| `xl` | 20 dp |
| `xxl` | 24 dp |
| `xxxl` | 32 dp |
| `huge` | 48 dp |

`HavenDim` also defines the screen rhythm used by Haven components:

| Token | Value |
|---|---|
| `screenH` | 20 dp |
| `hdrH` | 24 dp |
| `hdrV` | 8 dp |
| `cardPad` | 20 dp |
| `cardGap` | 12 dp |
| `sectionTop` | 26 dp |
| `hairline` | 1 dp |
| `headerActionGap` | 10 dp (gap between adjacent header action circles, everywhere) |
| `cardRowH` / `cardRowV` / `cardRowCompactV` / `cardBlockV` | 20 / 14 / 12 / 16 dp (rows and action blocks inside a `tight` card) |

---

## 6. Radii and controls

Haven component radii (`HavenDim`):

| Token | Value | Use |
|---|---|---|
| `rCard`, `rTile` | 20 dp | Cards and tiles |
| `rBtn`, `rField` | 14 dp | Buttons and fields |
| `rSheet`, `rDialog` | 24 dp | Sheets and dialogs |
| `rMenu` | 16 dp | Menus |
| `rRic` | 12 dp | Icon containers |
| `rBadge` | 9 dp | Badges |

Control heights: `hbtn` 40 dp, `ibtn` 34 dp with a 40 dp hit target,
`ibtnSm` 28 dp, `ric` 38 dp, and `rowMin` 62 dp.

`Dimens.Corners` declares a separate 6/10/14/18/22/28 dp scale. It is not
currently used by the UI; treat `HavenDim` as the active vocabulary and do not
introduce a third scale.

---

## 7. Elevation

`Dimens.Elevation` declares `none` 0 dp, `raised` 1 dp, `card` 2 dp,
`floating` 4 dp, and `modal` 8 dp. It has no current call sites: HAVEN
expresses depth with tone steps and hairline borders, and uses a shadow only
for a modal that needs separation from a transparent surface.

---

## 8. Material 3 projection

`HavenColors.toMaterialScheme()` maps the HAVEN palette onto
`ColorScheme`:

- background/surface/surfaceVariant from `bg`/`surface`/`surface2`
- primary/onPrimary from `primary`/`onPrimary`
- error from `red`
- outline from `border`
- text colors from `text`/`text2`/`muted`

Legacy Material 3 components use this projection. New components should use
`HavenTheme.colors` so they follow the token layer exactly.

---

## 9. Limitations

- Two shape vocabularies coexist: `HavenDim` (used) and `Dimens.Corners`
  (declared, unused). `HavenDim` is the active scale.
- Many screens still contain inline `.dp` values and direct Material 3
  colors; tokens are the target, not a fully enforced rule.
- No dynamic color. Palettes are fixed and do not follow the system wallpaper.
- Contrast ratios for the accent themes have not been measured against WCAG
  AA.

---

## 10. Related documentation

- [`design/UI_UX_GUIDELINES.md`](./UI_UX_GUIDELINES.md) — philosophy and component rules
- [`design/ACCESSIBILITY_AND_LOCALIZATION.md`](./ACCESSIBILITY_AND_LOCALIZATION.md) — fixed-LTR layout, Persian text direction, and semantics
- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — package layering
