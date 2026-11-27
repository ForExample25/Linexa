# Build and Development

**Status:** Current — describes the implemented build configuration and development workflow, verified against source for `0.2.0`.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/`, `AGENTS.md`, `app/src/main/AndroidManifest.xml`, `app/src/main/jniLibs/`

---

## 1. Purpose

This document is the reference for Linexa's build configuration, toolchain, SDK settings, jniLibs, signing, and development workflow.

---

## 2. Scope

In scope:

- Toolchain versions (Gradle, JDK, Kotlin, AGP, Compose BOM, Hilt, Room, KSP, Navigation)
- SDK settings (compileSdk, targetSdk, minSdk, ABI filters)
- Gradle modules (`:app`, `:terminal-emulator`, `:terminal-view`)
- jniLibs packaging (`lib__*__.so` naming, `useLegacyPackaging`)
- Signing (`local.properties`, fail-loud behavior)
- Build commands
- Toolchain constraints

Out of scope:

- Runtime architecture: [`architecture/`](../architecture/)
- Feature documentation: [`features/`](../features/)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Gradle wrapper 8.9 | Implemented | `gradle/wrapper/gradle-wrapper.properties` |
| JDK 17 | Implemented | `build.gradle.kts` |
| Kotlin 2.1.20 | Implemented | `build.gradle.kts` |
| AGP 8.5.0 | Implemented | `build.gradle.kts` |
| Compose BOM 2024.06.00 | Implemented | `app/build.gradle.kts` |
| Hilt 2.56.2 via KSP | Implemented | `app/build.gradle.kts` |
| Room 2.7.2 | Implemented | `app/build.gradle.kts` |
| Navigation 2.7.7 | Implemented | `app/build.gradle.kts` |
| compileSdk/targetSdk 34, minSdk 28 | Implemented | `app/build.gradle.kts` |
| JVM target 17 | Implemented | `app/build.gradle.kts` |
| ABI filters arm64-v8a + armeabi-v7a | Implemented | `app/build.gradle.kts` ndk block |
| R8 code shrinking, optimization, resource shrinking (release) | Implemented | `app/build.gradle.kts` `isMinifyEnabled`/`isShrinkResources` |
| Pinned per-release `versionCode`, `BuildConfig.IS_BETA` | Implemented | `app/build.gradle.kts` |
| Signing from `local.properties`, fail-loud | Implemented | `app/build.gradle.kts` `signingConfigs` |
| APK signing-certificate continuity check | Implemented | `ReleaseSigningIdentity`, `ApkValidator` |
| CI workflow (`assembleDebug`, unit tests, lint) | Implemented | `.github/workflows/ci.yml` |
| Release workflow (tag build, manual publish) | Implemented | `.github/workflows/release.yml` |
| Lint gate | Implemented | `app/build.gradle.kts` `lint` block |

---

## 4. Technical overview

### 4.1 Toolchain

Use a full JDK 17 and an Android SDK with platform 34 and build-tools 34.0.0.
Point Gradle at the SDK with `sdk.dir` in `local.properties` or with
`ANDROID_HOME`/`ANDROID_SDK_ROOT`. The committed Gradle wrapper downloads the
pinned Gradle distribution.

| Item | Version | Source |
|---|---|---|
| Gradle wrapper | 8.9 | `gradle/wrapper/gradle-wrapper.properties` |
| Java/JDK | 17 | toolchain |
| Kotlin | 2.1.20 | `build.gradle.kts` |
| AGP | 8.5.0 | `build.gradle.kts` |
| Compose BOM | 2024.06.00 | `app/build.gradle.kts` |
| Hilt | 2.56.2 | `app/build.gradle.kts` |
| Room | 2.7.2 | `app/build.gradle.kts` |
| KSP | 2.1.20-2.0.1 | `build.gradle.kts` |
| compileSdk/targetSdk | 34 | `app/build.gradle.kts` |
| minSdk | 28 | `app/build.gradle.kts` |
| JVM target | 17 | `app/build.gradle.kts` |

Toolchain and AGP/Kotlin/SDK version settings are **frozen by `AGENTS.md`** and must not be changed for feature work. JitPack is not used; repositories are `google()` and `mavenCentral()` only.

### 4.2 Gradle modules

`settings.gradle.kts` declares three modules: `:app`, `:terminal-emulator`, `:terminal-view`. The two terminal modules are vendored Apache-2.0 Termux libraries (see `terminal-emulator/PROVENANCE.md`, `terminal-view/PROVENANCE.md`).

### 4.3 jniLibs packaging

Native binaries (PRoot, BusyBox, GNU tar, Zstandard, tmux, loader, dependency libraries) are vendored in `app/src/main/jniLibs/` for `arm64-v8a` and `armeabi-v7a` only. The `lib__*__.so` naming is required because AGP auto-packages only `lib*.so` jniLibs entries; `BootstrapManager` re-maps them to friendly names under `{filesDir}/bootstrap/{bin,lib}` at runtime. The `packaging { jniLibs }` block in `app/build.gradle.kts` sets `useLegacyPackaging = true`.

No x86_64 `libproot.so` exists; x86_64 emulators cannot launch workspaces (install-time refusal via `abiFilters`).

### 4.4 Versioning and signing

`versionName` and `versionCode` are pinned per release in `app/build.gradle.kts`.
`0.2.0` ships `versionCode = 1002`, strictly greater than the published
`0.1.0-beta` value `1`. The value can be overridden for a CI hotfix with
`LINEXA_VERSION_CODE` or `-Plinexa.versionCode=…`, but the artifact and the
`ReleaseNotesCatalog` entry must agree. `BuildConfig.IS_BETA` records whether
the release is a beta; `0.2.0` sets it to `true` because the version name has
no `-beta` suffix.

Release signing reads four properties from `local.properties` (gitignored): `signing.storeFile`, `signing.storePassword`, `signing.keyAlias`, and `signing.keyPassword`. The release build fails loudly when the credentials or the keystore file are missing; it does not silently produce an unsigned release APK.

`local.properties` and the keystore are machine-local secrets. Do not commit either, and back up the release keystore separately: losing it makes future in-place updates signed with that key impossible. The update checker verifies that a downloaded APK is signed by the existing release certificate before handing it to Android's installer; changing the key would break both in-place updates and that check.

### 4.5 Build commands

```bash
# 1. JDK 17+ (a full JDK — AGP's JdkImageTransform needs jlink; a JRE fails)
# 2. Point the build at your SDK in local.properties (gitignored):
#        sdk.dir=/path/to/android-sdk
# 3. Build:
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # signed release APK
./gradlew :app:testDebugUnitTest      # JVM unit-test suite
./gradlew :app:verifyUnitTestReport   # zero-test / failing-suite gate
./gradlew :app:lintDebug              # lint gate (errors fail the build)
```

Release builds run R8 code shrinking, optimization, and resource shrinking.
The keep rules are static-verified by `TerminalJniShrinkerRuleTest`; a
physical-device release smoke is part of the publication checklist.

Record the exact command and full result on every verification run. Do not report a partial or filtered run as a full-suite pass.

### 4.6 CI and release automation

`.github/workflows/ci.yml` runs on pushes to `main` and on pull requests. It
sets up Temurin JDK 17 and Android SDK 34, runs
`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`, and
uploads the lint report, unit-test report, and debug APK.

`.github/workflows/release.yml` builds and verifies a tagged release but does
not publish automatically. A manual `workflow_dispatch` with `publish: true`
publishes the curated GitHub release, so the signed APK and checksum can be
attached deliberately. Signing material is supplied only through repository
secrets and decoded into the runner's temporary directory; it is never
committed.

### 4.7 Termux/Bionic hosts

On an aarch64 Bionic JVM (Termux), the JVM test suite can fail at KSP worker
initialization with `UnsatisfiedLinkError: libm.so.6 not found`, because the
default SQLite JDBC native is glibc-linked. This is an environment issue, not a
project defect. Use a glibc Linux host or a Bionic-compatible SQLite JDBC
native when running the suite on Termux. Do not commit machine-specific native
paths.

---

## 5. Limitations and known issues

- CI does not run instrumentation tests or a release build; those are verified
  locally or on the publication runner.
- Instrumentation smoke tests live in `app/src/androidTest/`; they require a
  connected device or emulator.
- Target SDK 34. Store-policy target requirements are external, and the
  toolchain is frozen for this release.
- No version catalog; dependency guard tests key on coordinates in
  `build.gradle.kts`. Adopting a version catalog is a mechanical refactor that
  needs explicit sign-off.
- Robolectric/Compose tests cannot run on an aarch64 Termux JVM because of
  missing Conscrypt natives; CI covers them.

---

## 6. Related documentation

- [`AGENTS.md`](../../AGENTS.md) — build environment instructions, toolchain constraints
- [`architecture/SYSTEM_ARCHITECTURE.md`](../architecture/SYSTEM_ARCHITECTURE.md) — module layout
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — Room schema, migrations
- [`engineering/TESTING_STRATEGY.md`](./TESTING_STRATEGY.md) — test layers and commands
- [`app/src/main/jniLibs/BINARY-MANIFEST.md`](../../app/src/main/jniLibs/BINARY-MANIFEST.md) — native binary provenance
- [`third_party/proot/BUILD-NOTES.md`](../../third_party/proot/BUILD-NOTES.md) — PRoot rebuild notes
