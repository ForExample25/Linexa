# Testing Strategy

**Source locations:** `app/src/test/java/com/fem/linexa/`,
`app/src/androidTest/java/com/fem/linexa/`,
`terminal-emulator/src/test/java/com/termux/terminal/`,
`.github/workflows/ci.yml`

---

## 1. Purpose

This document describes Linexa's test layers, test doubles, structural tests,
device-test procedure, and commands. It does not record individual test runs;
results belong to CI or the relevant change description.

---

## 2. Test layers

| Layer | Location | Runs on |
|---|---|---|
| JVM unit tests | `app/src/test/` | Host JVM through Gradle |
| Robolectric/Compose tests | `app/src/test/java/com/fem/linexa/ui/tests/` and a few crash-repro classes | Host JVM; x86_64 for the Conscrypt-dependent classes |
| Instrumentation smoke tests | `app/src/androidTest/java/com/fem/linexa/smoke/` | Physical device or arm64 emulator |
| Vendored terminal tests | `terminal-emulator/src/test/` | Host JVM through the module's test task |

The app JVM suite has 376 Kotlin test files. The vendored terminal module has
20 Java test files.

### 2.1 JVM suite

The JVM suite covers domain logic, parsers, formatters, runtime command
construction, repository behavior, settings, attribution, archive validation,
and source-wiring invariants. Tests use JUnit 4, coroutines-test, and a small
set of Android/Robolectric helpers where a platform type cannot be avoided.

### 2.2 Robolectric and Compose tests

Four classes import `org.robolectric`, and four import
`androidx.compose.ui.test`. They cover UI composition and crash reproductions
that need an Android-like environment.

On aarch64 Linux hosts (including Termux), the pinned Robolectric/Conscrypt
stack has no `conscrypt_openjdk_jni-linux-aarch_64` native. The default
`testDebugUnitTest` task excludes those four classes only when
`os.arch == aarch64`. `testDebugUnitTestEnvironmentOnly` runs them by name and
is expected to fail on such a host. They are ordinary tests on x86_64 and run
in CI.

### 2.3 Instrumentation smoke tests

Six tests under `app/src/androidTest/java/com/fem/linexa/smoke/`:

| Test | Checks |
|---|---|
| `AppLaunchSmokeTest` | Cold launch reaches the home surface without a crash |
| `NavigationSmokeTest` | The three main tabs render and round-trip |
| `SettingsSmokeTest` | Settings shows its stable rows |
| `SafRootsSmokeTest` | The documents provider exposes at least one root |
| `ServiceStopSmokeTest` | Stopping a nonexistent workspace destroys the service cleanly |
| `ProotSmokeTest` | A real workspace start is attempted only on a fixture-provisioned arm64 device |

`ProotSmokeTest` is gated by ABI, by the bundled loader, and by a
`.linexa-smoke-fixture` marker in a workspace directory. It skips with a named
assumption on a device without a fixture; it does not start real workloads.

Run them with:

```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## 3. Test doubles and harnesses

| Double | Purpose |
|---|---|
| `FakeProot` | Fake `ContainerRuntime` for session, exec, and daemon paths |
| `RecordingWakeLock` | Captures wake-lock acquisition and release |
| `RecordingAudioSink` | Captures audio bridge output |
| `RecordingHostClipboard` | Captures clipboard bridge operations |
| `ProotRuntimeTestHarness` | Shared runtime launch/teardown fixture |
| `ThreadRecordingLogSink` | Captures log lines with thread identity |
| `L2sExtraction` | Builds link2symlink fixtures |
| `TerminfoTestFixture` | Terminfo fixtures for terminal tests |

Recording fakes are substituted at `AppModule` bindings through Hilt test
support where a test needs the real object graph.

---

## 4. Structural tests

### 4.1 Import boundary

`ImportBoundaryTest` enforces that `domain` and `ui` do not import
`service.runtime.container` internals. This is the enforcement point for
package layering.

### 4.2 Source-wiring tests

Several test files are named `*SourceTest`, `*WiringTest`, or `*GuardTest`, and
others read production source text. They pin wiring that behavior tests cannot
observe directly, such as theme propagation, navigation registration, and
provider bindings.

These tests are brittle under renames and moves. Use them only for invariants
that cannot be expressed behaviorally, and replace a source assertion with a
behavior test when the behavior is observable.

### 4.3 Runtime invariant pins

`AuditPreservationPinsTest` pins structural runtime properties: real PTY
architecture, `/proc` + `/sys` + `/dev` binds, `--kill-on-exit`, `-0` fake
root, `--link2symlink`, L2S relative targets, archive validation, and the
vendored PRoot patches. The assertions describe current runtime requirements.

### 4.4 Coverage map

| Area | Tests |
|---|---|
| Terminal PTY architecture | `AuditPreservationPinsTest`, vendored terminal suite |
| `/proc`/`/sys`/`/dev` binds | `AuditPreservationPinsTest` |
| Fake-root `-0` | `PRootRuntimeTestHarness` |
| `--link2symlink` and L2S targets | `AuditPreservationPinsTest`, `L2sInstallLifecycleTest`, `L2sLinkRewriterTest` |
| Archive validation | `RealArchiveValidatorSymlinkTest`, `ArchiveEntryPath` tests |
| RootFS metadata precedence | `RootFsMetadataTest`, installer scan tests |
| Room schema/export | `RoomSchemaPolicyTest`, `RoomSchemaFiles`, `RoomSchemaRuntimeHashTest` |
| Theme wiring | `SettingsWiringSourceTest` |
| Poller cadence and lifecycle | `ProcessPollerConsistencyTest`, `DeviceInfoPollerLifecycleTest`, `SessionTaskManagerPollerLifecycleTest` |
| Attribution and license catalog | `AttributionCatalogTest`, `LicenseFileTest`, `AboutContentTest` |

---

## 5. Commands

```bash
./gradlew :app:testDebugUnitTest                 # app JVM suite
./gradlew :terminal-emulator:testDebugUnitTest   # vendored terminal suite
./gradlew :app:verifyUnitTestReport              # zero-test / failing-suite gate
./gradlew :app:connectedDebugAndroidTest         # instrumentation smoke tests
```

`verifyUnitTestReport` fails when the results directory has no `TEST-*.xml`
files or when any suite reports failures.

CI runs `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` on an
x86_64 Ubuntu runner. It does not run instrumentation tests or the terminal
module's suite. The release workflow additionally runs `:app:lintRelease`,
the full JVM suite, and `:app:testReleaseUnitTest --tests
'*ReleaseNotesCatalogTest'` before signing, so the running build's changelog
entry is checked against the release `versionCode` (F-044; the check is
skipped on debug builds, whose derived number moves with every commit).

---

## 6. Device test procedure

Runtime behavior that depends on Android, SELinux, PRoot, or a real kernel
must be verified on hardware. JVM tests and builds are not device evidence.

### 6.1 Matrix

At minimum, cover:

| Row | Target | Purpose |
|---|---|---|
| D1 | API 28–29, arm64 physical device | Oldest supported `minSdk`; legacy SELinux/linker behavior |
| D2 | API 33–34, arm64 physical device | Current `targetSdk`; `nativeLibraryDir` exec and foreground-service rules |
| D3 | Current OEM Android release, arm64 | OEM kernel/SELinux upper bound |
| D4 (optional) | Another OEM on API 34–36 | Kernel/SELinux differences |
| D5 (optional) | 32-bit `armeabi-v7a` device | ABI fallback |

A row passes only after the exact APK under test is installed and the
checklist is recorded. Do not infer a pass from another row or from a JVM
test.

### 6.2 Prerequisites

1. A physical arm64 device (or a validated arm64 emulator) with USB or
   wireless debugging authorized.
2. `adb` from `$ANDROID_HOME/platform-tools`; verify with `adb devices -l`.
3. The debug APK from the tree under test, with its SHA-256 recorded.
4. Enough free storage for the RootFS profile and extraction headroom.
5. A dedicated test device or an explicitly approved install. Do not run
   destructive steps on a production device.

### 6.3 Capture

```bash
adb devices -l
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.security_patch
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.product.model
adb shell getprop ro.product.manufacturer
adb shell getenforce
adb shell df -h /data

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb logcat -v threadtime > device-logcat.txt
```

Run the checklist, then capture the final log:

```bash
adb logcat -d -v threadtime > device-logcat-final.txt
```

Record app start, install transitions, account fixup, extraction, activation,
terminal open/close, workspace stop, and any crash or ANR. Do not capture
secrets, VNC passwords, SSH keys, or personal paths in shared evidence.

### 6.4 Checklist

| Check | Expected | Result |
|---|---|---|
| APK install | `Success`; installed hash matches the local APK | PASS / FAIL / NOT RUN |
| App launch | Main screen renders | PASS / FAIL / NOT RUN |
| Fresh RootFS install | Reaches `Ready` with no fixup failure | PASS / FAIL / NOT RUN |
| Terminal open | PTY opens and a prompt appears | PASS / FAIL / NOT RUN |
| Terminal I/O | `echo ok`, `id`, and `pwd` produce correct output | PASS / FAIL / NOT RUN |
| Workspace stop | No guest descendant remains; port and lock are free | PASS / FAIL / NOT RUN |
| App kill / relaunch | Startup sweep cleans stale state | PASS / FAIL / NOT RUN |
| Teardown | No crash or ANR; app remains usable | PASS / FAIL / NOT RUN |
| File save round-trip | Edited bytes match on reopen | PASS / FAIL / NOT RUN |
| Desktop start/stop | VNC or WebView reaches Running and stops cleanly | PASS / FAIL / NOT RUN |

`NOT RUN` is a valid result. Never report it as a pass.

### 6.5 Safety

- Use a dedicated test device or a cloned/secondary user profile.
- Never replace an installed RootFS or delete a user's workspace as part of a
  smoke test.
- Treat `pm clear`, `adb uninstall`, and recursive `rm` as destructive; obtain
  owner approval first.
- Do not execute untrusted archive contents on the host. Archive fixtures must
  be synthetic and isolated.
- Re-run the affected rows after a PRoot rebuild, an SDK/ABI change, an
  install/extraction/validator change, a runtime lifecycle change, or a new
  bundled profile.

---

## 7. Limitations

- Source-text tests are brittle under refactors. Keep them only for true
  invariants.
- Instrumentation tests are smoke tests, not full device coverage. Deep
  install, runtime, and desktop behavior is covered by the manual procedure.
- The PRoot instrumentation row skips without a provisioned fixture.
- Robolectric/Compose tests cannot run on the aarch64 Termux JVM; CI covers
  them.
- No fakes for large subsystems (`PRootContainerRuntime`,
  `GuestRootfsInitializer`, `FileManagerScreen`, `DesktopViewModel`,
  `BootstrapManager`); those paths have no unit-level substitute.
- Test execution on Termux may require the Bionic SQLite override described in
  [`BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md).

---

## 8. Related documentation

- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — import boundary
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — runtime behavior pinned by tests
- [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md) — build and test commands
