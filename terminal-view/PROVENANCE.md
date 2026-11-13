# Provenance — :terminal-view

- **Upstream:** https://github.com/termux/termux-app
- **Tag:** `v0.118.3`
- **Exact commit:** `5b657c6adf4304e5198951ce815fe0205dcac29c`
- **License:** Apache-2.0 (named by the same upstream LICENSE.md exception as
  its `terminal-emulator` sibling — the Jackpal Android-Terminal-Emulator
  lineage).
- **Vendored:** 2026-08-29, replacing the GPL-3.0-only
  `com.github.tiny-computer:terminal-lib` (and its JitPack repository),
  which were removed in the same change.

## What was copied

| Upstream path (terminal-view module) | Here |
|---|---|
| `src/main/java/com/termux/view/**` (`TerminalView`, renderer, gesture recognizer, `TerminalViewClient`, text-selection handles/controllers) | `src/main/java/com/termux/view/` — verbatim |

## Local changes (kept minimal, recorded here)

- Added this repo's Gradle build file (`build.gradle.kts`): plain Java
  Android library, `minSdk 28` (matching `:terminal-emulator` and the app's
  floor), Java 17 toolchain, `androidx.annotation` +
  `api(project(":terminal-emulator"))` dependencies.
- Added `consumer-rules.pro` (empty placeholder, upstream parity).
- License/provenance records: `LICENSE` (Apache-2.0 verbatim), `NOTICE`, this file.

Nothing else: the view sources are byte-identical to the pinned commit.
Bumping the vendored sources without updating the commit hash above is
caught by `TerminalStackLicenseGuardTest`.
