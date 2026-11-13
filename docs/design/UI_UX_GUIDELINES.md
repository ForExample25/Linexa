# UI/UX Guidelines

**Status:** Current — describes the implemented HAVEN UI/UX philosophy and guidelines, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/ui/theme/haven/HavenColors.kt`, `app/src/main/java/com/fem/linexa/ui/theme/haven/HavenTheme.kt`, `app/src/main/java/com/fem/linexa/ui/theme/Dimens.kt`, `Type.kt`, `ColorThemes.kt`, `Theme.kt`, `app/src/main/java/com/fem/linexa/ui/components/haven/`, `app/src/main/java/com/fem/linexa/navigation/NavGraph.kt`

---

## 1. Purpose

This document is the authoritative reference for Linexa's UI/UX guidelines: the HAVEN design philosophy, principles, navigation chrome, motion, and component usage rules.

All scattered UI/UX documentation has been consolidated here. Do not maintain competing UI/UX specifications elsewhere.

---

## 2. Scope

In scope:

- HAVEN design philosophy and principles
- Navigation chrome (bottom bar, tablet sidebar, back handling)
- Motion and transitions
- Component usage rules (cards, rows, dialogs, sheets, state UX)
- Accessibility and localization pointers (see [`design/ACCESSIBILITY_AND_LOCALIZATION.md`](./ACCESSIBILITY_AND_LOCALIZATION.md))
- Token-level values (see [`design/DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md))

Out of scope:

- Exact token values (colors, radii, spacing): [`design/DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md)
- Build/toolchain: [`engineering/BUILD_AND_DEVELOPMENT.md`](../engineering/BUILD_AND_DEVELOPMENT.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| HAVEN design system (HavenColors) | Implemented | `ui/theme/haven/` |
| Single token chain (theme → tokens → components → screens) | Implemented | `Theme.kt` KDoc, `SettingsWiringSourceTest` |
| Bottom bar (phone) / HavenSidebar (≥840dp tablet) | Implemented | `navigation/NavGraph.kt` |
| Edge-to-edge + predictive back app-wide | Implemented | `MainActivity.kt`, `AndroidManifest.xml` |
| 54 animate*/AnimatedVisibility/Crossfade sites | Implemented | `ui/screens/*`, `ui/components/*` |
| Shared empty/loading/error states (`HEmpty`/`HLoading`/`HError`) | Implemented | `ui/components/haven/HavenStates.kt` |
| 152 `contentDescription` references, 52 null; 9 explicit `Role.*` across 40 clickables | Partially implemented | `ui/screens/*`, `ui/components/*` |
| Persian (197 of 1,117 strings, 17.6%; 920 missing) | Partially implemented | `res/values-fa/`, `supportsRtl="true"` |
| `@Preview` | Not implemented | 0 found; `ui-tooling-preview` dependency present |
| Height/landscape-short handling | Not implemented | 0 hits for `screenHeightDp`/`WindowSizeClass` |
| Dynamic color | Not implemented | 0 uses of `dynamic*ColorScheme` |

---

## 4. Technical overview

### 4.1 Design philosophy

HAVEN: "minimal, spacious, premium" — tone-step surfaces instead of borders, one soft-green primary per screen, monospaced data at readable sizes, hairline separators. Dark-first, terminal-style aesthetic.

### 4.2 Principles

1. **Single token chain:** theme definition → Haven tokens → components → screens. Nothing below tokens may hard-code a color, radius, or text style.
2. **Explicit theming:** `LinexaApp` passes `darkTheme` and `colorTheme` explicitly (pinned by `SettingsWiringSourceTest`).
3. **Data density & technical clarity:** monospaced data, tone-step surfaces, scoped metrics with explicit "unknown" rendering (never 0).
4. **Fail-open honesty:** unknown values render as dashes; no fabricated metrics.
5. **No shadows/borders:** elevation is tint over shadow.
6. **RTL-capable:** `supportsRtl="true"` declared; Persian partial.

### 4.3 Navigation chrome

- **Phone:** bottom bar with three permanent tabs (workspaces / activity / settings).
- **Tablet (≥840dp width):** persistent 238 dp `HavenSidebar`.
- Terminal is an overlay, not a route (`TerminalDeepLinkBus`).
- Predictive back enabled app-wide (`enableOnBackInvokedCallback="true"`); back hierarchy is truthful — no "jump home" shortcuts.
- 90 ms crossfade tuned from a documented jank analysis (`NavGraph.kt`).
- Tab state `saveState`/`restoreState`; real back-stack entries.

### 4.4 Motion

- 54 `animate*/AnimatedVisibility/Crossfade/InfiniteTransition` sites; custom `BlobProgressIndicator` (Canvas) in install flow.
- Tab switch: 90 ms simultaneous fade.
- No blur/glass (by design); no `haze` dependency.
- No Material 3 Expressive / wavy progress (would require Material3 1.5.0-beta01, conflicting with pinned BOM 2024.06.00).

### 4.5 Component usage rules

| Component | Use | Avoid |
|---|---|---|
| Haven cards/rows (`WorkspaceCard`, `ShortcutCard`, `StatusPill`, `StatusDot`, `MiniSparkline`, `MetricCard`) | Dense technical data | — |
| `LinexaAlertDialog` | Destructive confirmation (Close all, Delete workspace) | — |
| Haven sheets (24 dp radius) | Sheets/dialogs | — |
| `HEmpty`/`HLoading`/`HError` | Empty/loading/error states | Ad-hoc per-screen text |
| `HavenResource` (`HResourceCard`, `HResourceMetric`, `HMiniStat`, `HEmptyRail`, `ResourceText`) | Resource meters | — |
| `LinexaButton` / `LinexaCard` | **Legacy — 0 call sites apart from definitions** | Use Haven equivalents |
| `LinexaTopBar` | 1 call site (`GlobalAppBar.kt`) | — |
| `LinexaAlertDialog` | 4 call sites vs `HDialog` 20 | Prefer `HDialog` |
| Material3 `Button` | 17 direct calls outside Haven | Prefer Haven buttons |

### 4.6 Dimension and radii vocabulary

`Dimens.kt` holds `Spacing` (4-dp grid) and `Corners` (radii) in a single file. `Spacing` is widely referenced across screens; `Corners` defines card `md` 14dp and sheet/dialog `lg` 18dp radii. There is no separate `HavenDim` token file in the current source.

### 4.7 M3 scheme consumption

`MaterialTheme.colorScheme` is referenced 91 times vs 208 direct Haven-color/theme uses — most surfaces consume HAVEN directly, while legacy M3 components inherit the projected scheme. Two colour access paths coexist, so changing a token means checking both.

### 4.8 Accessibility & localization (pointers)

- 152 `contentDescription` references; 52 are `null` and need decorative/actionable classification.
- 9 explicit `Role.*` uses across 40 clickables; semantics coverage is thin.
- `stringResource` 933×; `values-fa` 197 of 1,117 (17.6%, 920 missing); RTL declared and untested on device.
- Hard-coded English remains in a few surfaces.
- See [`design/ACCESSIBILITY_AND_LOCALIZATION.md`](./ACCESSIBILITY_AND_LOCALIZATION.md) for full detail.

---

## 5. Limitations and known issues

- No `@Preview` in main sources (0 found), although `ui-tooling-preview` is a dependency.
- No height/landscape-short handling (0 hits for `screenHeightDp`, `WindowSizeClass`).
- No dynamic color (0 uses of `dynamic*ColorScheme`).
- Persian coverage partial (17.6%, 920 missing keys); mixed-language UI for Persian-locale users.
- Contrast of `LAVENDER` and `ALPINE_BLUE` themes against WCAG AA not measured.
- UI flows still need on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`design/DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md) — token values (colors, typography, spacing, radii)
- [`design/ACCESSIBILITY_AND_LOCALIZATION.md`](./ACCESSIBILITY_AND_LOCALIZATION.md) — accessibility, content descriptions, Persian, RTL
- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — navigation structure
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
