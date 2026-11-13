# Build and Development

**Status:** Current — describes the implemented build configuration and development workflow, verified against source.

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
| `isMinifyEnabled = false` (release) | Implemented | `app/build.gradle.kts` |
| Signing from `local.properties`, fail-loud | Implemented | `app/build.gradle.kts` `signingConfigs` |
| CI workflow file | Absent | no `.github/workflows/` in the repository |
| Lint configuration / gate | Absent | no `lint` block, `lint.xml`, or baseline |

---

## 4. Technical overview

### 4.1 Toolchain

Load the machine's centralized Android toolchain before building:

```bash
. ~/Tools/env/toolchain.sh                 # Termux
. /mnt/termux/Tools/env/toolchain.sh       # Ubuntu proot
```

The environment file provides `JAVA_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, and toolchain `PATH` entries.

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

### 4.4 Signing

Release signing reads four properties from `local.properties` (gitignored): `signing.storeFile`, `signing.storePassword`, `signing.keyAlias`, and `signing.keyPassword`. The release build fails loudly when the credentials or the keystore file are missing; it does not silently produce an unsigned release APK.

`local.properties` and the keystore are machine-local secrets. Do not commit either, and back up the release keystore separately: losing it makes future in-place updates signed with that key impossible.

### 4.5 Build commands

```bash
# 1. JDK 17+ (a full JDK — AGP's JdkImageTransform needs jlink; a JRE fails)
# 2. Point the build at your SDK in local.properties (gitignored):
#        sdk.dir=/path/to/android-sdk
# 3. Build:
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK (needs signing config)
./gradlew :app:testDebugUnitTest      # JVM unit-test suite
./gradlew :app:lintDebug              # report-only; no lint gate is configured
```

Record the exact command and full result on every verification run. Do not report a partial or filtered run as a full-suite pass.

### 4.6 CI status

The repository contains no `.github/` directory and no CI workflow. CI is therefore not part of this release; run the commands above locally or on your own runner. No lint gate is configured either, so `lintDebug` is report-only.

### 4.7 Termux/Bionic SQLite override

On an aarch64 Bionic JVM (Termux), a plain build can fail at KSP worker initialization with `UnsatisfiedLinkError: libm.so.6 not found`, because the default SQLite JDBC native is glibc-linked. This is an environment issue, not a project defect.

If that failure occurs, point the JVM at a Bionic-compatible SQLite native before Gradle:

```bash
export HOME=/data/data/com.termux/files/home
export TOOLS_ROOT=$HOME/Tools
. "$TOOLS_ROOT/env/toolchain.sh"
export JAVA_TOOL_OPTIONS='-Dorg.sqlite.lib.path=/path/to/bionic-sqlite -Dorg.sqlite.lib.name=libsqlitejdbc.so'

./gradlew :app:compileDebugKotlin --offline
./gradlew :app:compileDebugUnitTestKotlin --offline
./gradlew :app:testDebugUnitTest --offline     # optional --tests filter
```

The path is machine-specific; do not check it into the project or replace the centralized toolchain. Without a Bionic SQLite native, use a glibc Linux host.

---

## 5. Limitations and known issues

- No CI workflow file; CI state is not verifiable from this repository.
- No lint configuration or gate; `lintDebug` is report-only and currently noisy.
- No instrumentation (`androidTest`) source set; device behavior is verified manually — see [`TESTING_STRATEGY.md`](./TESTING_STRATEGY.md).
- `isMinifyEnabled = false` for release. Whether shrinking is safe for the PRoot/JNI stack has not been established.
- Target SDK 34. Store-policy target requirements are external, and the toolchain is frozen for this release.
- No version catalog; dependency guard tests key on coordinates in `build.gradle.kts`. Adopting a version catalog is a mechanical refactor that needs explicit sign-off.

---

## 6. Related documentation

- [`AGENTS.md`](../../AGENTS.md) — build environment instructions, toolchain constraints
- [`architecture/SYSTEM_ARCHITECTURE.md`](../architecture/SYSTEM_ARCHITECTURE.md) — module layout
- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — Room schema, migrations
- [`engineering/TESTING_STRATEGY.md`](./TESTING_STRATEGY.md) — test layers and commands
- [`app/src/main/jniLibs/BINARY-MANIFEST.md`](../../app/src/main/jniLibs/BINARY-MANIFEST.md) — native binary provenance
- [`app/src/main/jniLibs/proot-build/BUILD-NOTES.md`](../../app/src/main/jniLibs/proot-build/BUILD-NOTES.md) — PRoot rebuild notes
