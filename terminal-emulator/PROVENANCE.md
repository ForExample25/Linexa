# Provenance — :terminal-emulator

- **Upstream:** https://github.com/termux/termux-app
- **Tag:** `v0.118.3`
- **Exact commit:** `5b657c6adf4304e5198951ce815fe0205dcac29c`
- **License:** Apache-2.0 (the upstream tree's LICENSE.md names
  `terminal-emulator` as the Apache-2.0 component of its GPLv3 tree —
  the Jackpal Android-Terminal-Emulator lineage).
- **Vendored:** 2026-08-29, replacing the GPL-3.0-only
  `com.github.tiny-computer:terminal-lib` (and its JitPack repository),
  which were removed in the same change.

## What was copied

| Upstream path (terminal-emulator module) | Here |
|---|---|
| `src/main/java/com/termux/terminal/**` (emulator, PTY/JNI interface, session, styles, key handling) | `src/main/java/com/termux/terminal/` — verbatim |
| `src/main/jni/**` (`termux.c`, Android.mk — the `forkpty` JNI layer) | `src/main/jni/` — verbatim |
| `src/main/jniLibs/**` (prebuilt `libtermux.so` for arm64-v8a + armeabi-v7a) | `src/main/jniLibs/` — verbatim |
| `src/test/java/com/termux/terminal/**` (upstream JVM emulator suite) | `src/test/java/com/termux/terminal/` — verbatim, and still run by this project's checks |

## Local changes (kept minimal, recorded here)

- Added this repo's Gradle build file (`build.gradle.kts`): plain Java
  Android library, `minSdk 28` (the app's floor since the real-PTY phase;
  upstream's own floor is 24), Java 17 toolchain, `androidx.annotation`
  dependency, JUnit test dependency.
- Added `consumer-rules.pro` (empty placeholder, upstream parity).
- License/provenance records: `LICENSE` (Apache-2.0 verbatim), `NOTICE`, this file.

Nothing else: the emulator sources, the JNI layer and the test suite are
byte-identical to the pinned commit. Bumping the vendored sources without
updating the commit hash above is caught by
`TerminalStackLicenseGuardTest`.

## Prebuilt binary pin (F-045 / R-11)

`src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libtermux.so` are prebuilt and
pinned by `src/main/jniLibs/SHA256SUMS`:

```
e53837c8475f9540e84ac3c310ed5c2f110ea5183a0ac5eb5d183d49f3e5e6fc  arm64-v8a/libtermux.so
1a3336d97e85b14aa6bce7aff4c05777d9a67acb37e735c974117ad5d640123b  armeabi-v7a/libtermux.so
```

`PrebuiltLibraryPinTest` recomputes both hashes on every `:terminal-emulator`
test run, so a silently replaced binary fails the build. To rebuild from the
vendored `src/main/jni/termux.c` and `Android.mk`, run
`terminal-emulator/scripts/rebuild-libtermux.sh` (requires an Android NDK) and update the hashes
above in the same commit.
