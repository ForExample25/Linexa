# Code Conventions

**Status:** Current — describes the implemented code conventions, naming, layering rules, and documentation conventions, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/`, `di/AppModule.kt`, `navigation/NavGraph.kt`, `AGENTS.md`, `architecture/APPLICATION_ARCHITECTURE.md`

---

## 1. Purpose

This document defines Linexa's code conventions: naming, package layering, dependency direction, error handling philosophy, Flow/coroutine usage, and documentation conventions.

---

## 2. Scope

In scope:

- Naming conventions (Workspace*, Haven*, *Provisioner, *Supervisor)
- Layering rules (ArchUnit boundary)
- Error handling (fail-open, sealed types, total functions)
- Flow/coroutine conventions (start/stop-gated samplers, collectAsState, no GlobalScope)
- `runBlocking` sites (justified)
- Documentation conventions (inline KDoc decision records, source references)

Out of scope:

- Token values: [`design/DESIGN_SYSTEM.md`](../design/DESIGN_SYSTEM.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Naming (`Workspace*`, `Haven*`, `*Provisioner`, `*Supervisor`) | Implemented | source tree |
| ArchUnit import boundary (domain/ui no service.runtime.container) | Implemented | `ImportBoundaryTest.kt` |
| Fail-open error handling | Implemented | sealed result/state types |
| Total-function parsers (`GuiBackend.fromId`, `DesktopRunMode.fromId`) | Implemented | documented |
| Samplers start/stop gated | Implemented | `TaskManagerViewModel` KDoc |
| `collectAsState()` 83× / `collectAsStateWithLifecycle` 1× (comment) | Documented workaround | `app/build.gradle.kts` |
| `runBlocking` at 3 production sites | Justified | `RuntimeService.kt:606`, `RootfsFixupService.kt:126`, `LinexaDocumentsProvider.kt:445` |
| `GlobalScope` | Absent | verified absence |
| Inline KDoc decision records | Present | `defaultConfig` history comments, feature docs |
| `@Suppress` / `//noinspection` | Minimal | source tree |
| AGP/Kotlin/SDK frozen | Enforced | `AGENTS.md` |

---

## 4. Technical overview

### 4.1 Naming conventions

| Pattern | Meaning | Example |
|---|---|---|
| `Workspace*` | Workspace domain/service models | `WorkspaceRepository`, `WorkspaceMetadata`, `WorkspaceStatus` |
| `Haven*` | HAVEN UI components/theme | `HavenColors`, `HavenChrome` |
| `*Provisioner` | Guest feature provisioner | `SshdProvisioner`, `XvncProvisioner`, `AudioBridgeProvisioner` |
| `*Supervisor` | Workspace feature supervisor | `WorkspaceFeatureSupervisor`, `WorkspaceSessionSupervisor` |
| `Terminal*Terminal*` | PTY sessions | `TerminalSession`, `TermuxPtySession` |
| `Real*` / `Recording*` | Implementations vs fakes | `RealTerminalView`, `RecordingWakeLock` |
| `Fake*` | Test doubles | `FakeProot` |

### 4.2 Layering rules (enforced by ArchUnit)

`domain` and `ui` must not import `service.runtime.container` internals. `domain` depends only on `core`. `data` and `service` depend on `domain`. `ui` depends on `domain`+`data`+`service` through DI only. The `ImportBoundaryTest` ArchUnit rule runs with zero frozen exceptions.

### 4.3 Error handling philosophy

- Sealed result/state types: `InstallResult`, `CommandResult`, `WorkspaceStatus.Broken(reason)`.
- Typed UI states: `HEmpty`/`HLoading`/`HError`, "retry keeps you in place".
- Fail-open: failures return empty/default values and are surfaced in UI states; never silent truncation (e.g., empty-file handling writes truly empty files).
- Documented `runBlocking` sites: `RuntimeService.kt:606`, `RootfsFixupService.kt:126`, `LinexaDocumentsProvider.kt:445`.
- No `GlobalScope`.

### 4.4 Flow / coroutine conventions

- Samplers are explicitly start/stop gated: `TaskManagerViewModel` KDoc — "Polling runs ONLY while `start` has been called."
- `collectAsState()` used 83×; `collectAsStateWithLifecycle()` appears once, in a comment — documented crash workaround on pinned toolchain (lifecycle 2.8.1 + Compose UI 1.6.x + Nav 2.7.7).
- Polling ticks serialised and coalesced (<750 ms apart).
- One `CoroutineScope(SupervisorJob() + Dispatchers.Default)` process-wide scope is NOT used (Linexa uses structured concurrency per component).

### 4.5 Documentation conventions

- Inline KDoc decision records are encouraged (e.g., Gradle version-code history, backup rationale, flags history).
- Feature decisions recorded in `docs/decisions/ADR_REGISTER.md`.
- Source-code references use actual repository paths; line numbers only where verified.
- Historical implementation narratives belong in commit messages or local release records, not in source comments or the public docs tree.

---

## 6. Limitations and known issues

- `Spacing` and `Corners` both live in `Dimens.kt` and serve different purposes (grid vs radii); they are complementary, not competing.
- `AppModule.kt` is a 580-line grab-bag; splitting is a navigation-only refactor.
- `LinexaButton`/`LinexaTopBar` legacy components have 0–1 call sites.
- Source-text wiring tests brittle under refactors.

---

## 7. Related documentation

- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — package layering
- [`decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md) — Keep/Fix/Refactor decisions
- [`design/DESIGN_SYSTEM.md`](../design/DESIGN_SYSTEM.md) — token values
- [`engineering/TESTING_STRATEGY.md`](./TESTING_STRATEGY.md) — verification status
