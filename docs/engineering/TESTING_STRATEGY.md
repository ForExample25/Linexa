# Testing Strategy

**Status:** Current — describes the implemented testing approach, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/test/java/com/fem/linexa/`, `terminal-emulator/src/test/java/com/termux/terminal/`, `app/src/main/java/com/fem/linexa/service/runtime/`, `AGENTS.md`

---

## 1. Purpose

This document is the reference for Linexa's testing strategy: test environment classification, test suites, test doubles, traceability, and verification state.

---

## 2. Scope

In scope:

- Unit test suite structure (JVM, Robolectric, device tests)
- Test doubles (`FakeProot`, `Recording*`, harnesses)
- ArchUnit import boundary test
- Source-text wiring tests (`*SourceTest` / `*WiringTest` / `*GuardTest`)
- Audit preservation pins
- Traceability matrix (implemented features ↔ tests)
- Suite size and verification state
- Device test gaps (no `androidTest` source set)

Out of scope:

- Build/test execution environment: [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| JVM unit test suite | Implemented | `app/src/test/java/com/fem/linexa/` (366 Kotlin test files) |
| Robolectric/Compose UI tests | 4 files import `org.robolectric`; 4 import `androidx.compose.ui.test` | `ui/tests/` plus crash-repro classes under `ui/screens/` |
| `androidTest` instrumentation suite | Absent | 0 files |
| Test doubles (`FakeProot`, `RecordingWakeLock`, `RecordingAudioSink`, `RecordingHostClipboard`, `ProotRuntimeTestHarness`) | Implemented | `test/support/`, `features/` |
| ArchUnit import boundary (`ImportBoundaryTest`) | Implemented | `architecture/ImportBoundaryTest.kt` |
| Source-text wiring tests | 48 files named `*SourceTest` / `*WiringTest` / `*GuardTest`; 62 files read production source text | `ui/`, `architecture/`, `core/` test packages |
| Audit preservation pins (`AuditPreservationPinsTest`) | Implemented | `architecture/AuditPreservationPinsTest.kt` |
| Terminal-emulator upstream JVM suite | Implemented | 20 Java test files under `terminal-emulator/src/test/` |
| Device tests | **Absent from automation** — no `androidTest` source set | manual device verification only |

---

## 4. Technical overview

### 4.1 Test environment classification

Tests run in three environments:

| Environment | Source set | Can run |
|---|---|---|
| JVM unit tests | `app/src/test/` | host JVM (via Gradle), requires KSP + a working SQLite native |
| Robolectric/Compose | `app/src/test/java/com/fem/linexa/ui/tests/` plus four crash-repro classes | x86_64 hosts; the four Robolectric classes need Conscrypt natives that the pinned stack does not provide for aarch64 Linux |
| Instrumentation | `app/src/androidTest/` | does not exist |

On an aarch64 Linux host (including Termux), the four Robolectric classes fail at class-load with `UnsatisfiedLinkError: no conscrypt_openjdk_jni-linux-aarch_64`. The default `testDebugUnitTest` task therefore excludes those four classes only when `os.arch` is `aarch64`. The `testDebugUnitTestEnvironmentOnly` task runs them by name and is expected to be red on such a host; they are ordinary default-task tests on x86_64.

### 4.2 Test doubles

- `FakeProot` — fake `ContainerRuntime` for session/exec/daemon paths
- `RecordingWakeLock`, `RecordingAudioSink`, `RecordingHostClipboard` — narrow-interface fakes
- `ProotRuntimeTestHarness` — harness for runtime tests
- `ThreadRecordingLogSink`, `L2sExtraction`, `TerminfoTestFixture` — focused support fixtures
- Recording fakes substituted at `AppModule` bindings via Hilt test support

### 4.3 ArchUnit import boundary

`ImportBoundaryTest` enforces that `domain` and `ui` do not import `service.runtime.container` internals, with **zero frozen exceptions**. This test is the enforcement point for layering discipline.

### 4.4 Source-text wiring tests

48 files are named `*SourceTest` / `*WiringTest` / `*GuardTest`, and 62 test files in total read production source text. They pin wiring (for example theme wiring and component composition) that behaviour tests cannot observe directly. These tests are brittle under renames and moves, so they are reserved for true invariants; behaviour tests are preferred elsewhere. A source assertion should be replaced with a behavioural one whenever the behaviour can be observed.

### 4.5 Audit preservation pins

`AuditPreservationPinsTest` pins audited runtime behaviour: real PTY architecture, `/proc` + `/sys` + `/dev` binds, `--kill-on-exit`, `-0` fake root, `--link2symlink`, L2S rewriter relative targets, archive validation, and vendored patches 0001–0004.

### 4.6 Suite size

Measured on the current tree:

- Main Kotlin: 111,234 lines / 435 files
- Test Kotlin: 80,516 lines / 366 files
- Executed `:app:testDebugUnitTest` suite: 4,079 tests, 1 skipped
- Vendored terminal-emulator module: 20 Java test files, 150 tests (separate `:terminal-emulator:testDebugUnitTest` task)

`@Test` annotations and executed tests differ slightly because some annotations live in support code or are conditionally skipped.

### 4.7 Traceability matrix (implemented features → tests)

| Feature area | Tests cover |
|---|---|
| Terminal PTY architecture | `AuditPreservationPinsTest`, vendored terminal-emulator suite |
| `/proc`/`/sys`/`/dev` binds | `AuditPreservationPinsTest` |
| Fake-root `-0` | `PRootRuntimeTestHarness` |
| `--link2symlink` L2S relative targets | `AuditPreservationPinsTest`, `L2sInstallLifecycleTest`, `L2sLinkRewriterTest` |
| Archive validation | `RealArchiveValidatorSymlinkTest`, `ArchiveEntryPath` tests |
| RootFS metadata precedence | `RootFsMetadataTest`, installer scan tests |
| Room schema/export | `RoomSchemaPolicyTest`, `RoomSchemaFiles`, `RoomSchemaRuntimeHashTest` |
| Theme wiring (`darkTheme`/`colorTheme` explicit) | `SettingsWiringSourceTest` |
| Process poller cadence and lifecycle | `ProcessPollerConsistencyTest`, `DeviceInfoPollerLifecycleTest`, `SessionTaskManagerPollerLifecycleTest` |
| Attribution/license catalog | `AttributionCatalogTest`, `LicenseFileTest`, `AboutContentTest` |

### 4.8 Running the suites

```bash
./gradlew :app:testDebugUnitTest                 # app JVM suite
./gradlew :terminal-emulator:testDebugUnitTest   # vendored terminal suite
./gradlew :app:verifyUnitTestReport              # zero-test / failing-suite report gate
```

On an aarch64 host, `testDebugUnitTest` excludes the four Conscrypt-dependent Robolectric classes described in §4.1. On x86_64 they run as part of the default task.

### 4.9 Verification state

- The app JVM suite passes on this tree: **4,079 tests, 1 skipped, 0 failures**.
- The vendored terminal-emulator suite passes: **150 tests, 0 failures**.
- The four environment-only Robolectric classes are excluded on aarch64 and are expected to fail there; they are not counted in the passing total above.
- No instrumentation tests exist. Device behaviour (install, launch, terminal I/O, stop, force-stop/relaunch, teardown, desktop, RootFS import) must be verified manually on a physical device or emulator before a store release.
- `verifyUnitTestReport` fails on zero tests or any failing suite; there is no allowlist of known failures.

---

## 5. Limitations and known issues

- Source-text tests are brittle under refactors (48 named files; 62 files read source); keep them only for true invariants.
- No instrumentation tests exist; the device lane is manual and is not part of CI.
- Robolectric/Compose tests do not run on the aarch64 Termux JVM because of missing Conscrypt natives; they are ordinary tests on x86_64.
- No fakes for large subsystems (`PRootContainerRuntime`, `GuestRootfsInitializer`, `FileManagerScreen`, `DesktopViewModel`, `BootstrapManager`); splitting them is a refactor candidate.
- Test execution on Termux may require the Bionic SQLite native override described in [`BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md).

---

## 6. Related documentation

- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — ArchUnit import boundary
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — runtime internals pinned by tests
- [`engineering/BUILD_AND_DEVELOPMENT.md`](./BUILD_AND_DEVELOPMENT.md) — test execution commands
