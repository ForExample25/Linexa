# UI/UX Guidelines

**Source locations:** `app/src/main/java/com/fem/linexa/ui/theme/haven/`,
`app/src/main/java/com/fem/linexa/ui/theme/Dimens.kt`,
`app/src/main/java/com/fem/linexa/ui/theme/Type.kt`,
`app/src/main/java/com/fem/linexa/ui/components/haven/`,
`app/src/main/java/com/fem/linexa/navigation/NavGraph.kt`

---

## 1. Purpose

This is the reference for Linexa's UI philosophy and interaction rules: the
HAVEN visual language, navigation chrome, motion, component usage, and the
principles that keep screens consistent.

Token values (colors, type sizes, spacing, radii) are in
[`DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md). Accessibility and localization are in
[`ACCESSIBILITY_AND_LOCALIZATION.md`](./ACCESSIBILITY_AND_LOCALIZATION.md).

---

## 2. Design philosophy

HAVEN is "minimal, spacious, premium": tone-step surfaces instead of borders,
one soft-green primary per screen, monospaced data at readable sizes, and
hairline separators. The app is dark-first with a terminal-style aesthetic,
and the light palette is a first-class design rather than an inversion.

### 2.1 Principles

1. **Single token chain.** Theme definition → Haven tokens → components →
   screens. Screens and shared components should not hard-code colors,
   radii, or text styles.
2. **Explicit theming.** The app passes `darkTheme` and `colorTheme`
   explicitly; theme state is not inferred from an ambient default.
3. **Data density with clarity.** Metrics use monospaced type, clear labels,
   and consistent units. Dense is not the same as cramped.
4. **Honest unknowns.** An unavailable value renders as a dash or an
   explanatory label, never as `0` or a fabricated number.
5. **Elevation by tint.** Surfaces separate by tone steps and hairlines rather
   than shadows.
6. **LTR-fixed, bidi-aware.** The application chrome is left-to-right in every
   language, including Persian. Text keeps its native bidi direction, so Persian
   prose and punctuation render RTL inside the LTR interface; technical content
   stays LTR.

---

## 3. Navigation chrome

### 3.1 Structure

- **Phone:** bottom bar with three permanent tabs: Workspaces, Activity,
  Settings.
- **Tablet (≥840 dp width):** persistent 238 dp `HavenSidebar` in place of the
  bottom bar.
- **Terminal:** an overlay, not a navigation route. `TerminalDeepLinkBus`
  opens it from whatever screen is active.
- **Workspace detail:** nested routes for features, files, packages, desktop,
  and settings.

Tab state uses `saveState`/`restoreState`, so returning to a tab restores its
scroll position and nested destination. Back behavior follows the real
navigation stack; there are no "jump home" shortcuts.

### 3.2 Predictive back and insets

Predictive back is enabled app-wide. The app draws edge-to-edge and applies
system-bar and navigation-bar insets at the screen level. Bottom-anchored
controls (for example the file manager's create button) sit above the
navigation bar rather than inside scrolling content.

---

## 4. Motion

- Tab switches use a short simultaneous crossfade (90 ms).
- Install progress uses the custom Canvas `BlobProgressIndicator`; there is no
  dependency on Material 3 Expressive or wavy-progress APIs.
- Lists and panels use `animate*`/`AnimatedVisibility` for state changes that
  benefit from continuity, not for decoration.
- No blur or glass effects; no `haze` dependency.
- Motion respects the system animator-duration scale where the platform
  supplies it.

---

## 5. Component rules

| Component family | Use | Notes |
|---|---|---|
| `WorkspaceCard`, `ShortcutCard`, `StatusPill`, `StatusDot`, `MiniSparkline`, `MetricCard` | Workspace and technical data | Dense layouts with explicit units |
| Haven cards and rows | Standard content surfaces | Prefer over custom `Surface` compositions |
| `HEmpty`, `HLoading`, `HError` | Empty, loading, and error states | Do not hand-roll per-screen variants |
| `HavenResource` (`HResourceCard`, `HResourceMetric`, `HMiniStat`, `HEmptyRail`, `ResourceText`) | Resource meters | Shared formatting and unavailable states |
| `HDialog` / Haven sheets | Dialogs and bottom sheets | 24 dp sheet radius |
| `LinexaAlertDialog` | Destructive confirmation | Close all, delete workspace |
| `LinexaButton`, `LinexaCard`, `LinexaTopBar` | Legacy components | Avoid in new UI; use Haven equivalents |

Guidelines:

- A destructive action names the object and consequence, and uses the error
  color for its confirm action.
- A row that opens a destination shows a chevron; a row that toggles shows a
  switch. Do not mix the affordances.
- Metric cards show a unit, a timestamp or freshness cue, and a dash for
  unknown values.
- Long lists use `LazyColumn`; do not nest scrollable containers of the same
  axis.
- A screen with no content still explains why it is empty and what the user
  can do next.

---

## 6. Typography and spacing

- System sans for chrome and body text.
- Monospace for terminal output, commands, paths, IDs, metrics, and other
  technical values.
- The 4-dp `Spacing` grid is the shared spacing vocabulary. New layouts should
  use it rather than introducing arbitrary values, though existing screens
  still contain inline `.dp` values.
- Card, sheet, and dialog radii come from `HavenDim`; see
  [`DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md).

---

## 7. State and feedback

- Loading states preserve the previous content where possible instead of
  replacing the screen with a spinner.
- Errors keep the user in place and offer a retry or a concrete next step.
- Operation results are reported only after the underlying process exits.
  Partial success is not shown as success.
- Long-running operations show progress and remain cancellable where the
  underlying operation supports cancellation.
- Snackbars and inline messages are transient; persistent problems belong in
  the screen's state, not only in a toast.

---

## 8. Limitations

- No `@Preview` composables in the main source set; UI iteration relies on a
  running app or Robolectric tests.
- No height-class or short-landscape layout variant. Screens are designed for
  portrait phones and width-class tablets.
- No dynamic color. Palettes are fixed per theme.
- Some legacy components and direct Material 3 usages remain; new UI should use
  the Haven layer.
- Contrast of every palette against WCAG AA has not been measured.

---

## 9. Related documentation

- [`design/DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md) — token values
- [`design/ACCESSIBILITY_AND_LOCALIZATION.md`](./ACCESSIBILITY_AND_LOCALIZATION.md) — semantics, fixed-LTR layout, Persian text direction, translation
- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — navigation and package layering
