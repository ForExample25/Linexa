# Code Conventions

**Source locations:** `app/src/main/java/com/fem/linexa/`,
`app/src/test/java/com/fem/linexa/architecture/ImportBoundaryTest.kt`,
`app/src/main/java/com/fem/linexa/di/AppModule.kt`,
`AGENTS.md`

---

## 1. Purpose

This document defines Linexa's naming, package layering, dependency direction,
error handling, coroutine use, and documentation conventions.

---

## 2. Naming

| Pattern | Meaning | Examples |
|---|---|---|
| `Workspace*` | Workspace domain/service models | `WorkspaceRepository`, `WorkspaceMetadata`, `WorkspaceStatus` |
| `Haven*` | HAVEN UI components and theme | `HavenColors`, `HavenChrome`, `HavenSidebar` |
| `*Provisioner` | Guest feature provisioner | `SshdProvisioner`, `XvncProvisioner`, `AudioBridgeProvisioner` |
| `*Supervisor` | Workspace feature supervisor | `WorkspaceFeatureSupervisor`, `WorkspaceSessionSupervisor` |
| `Terminal*` | PTY/session types | `TerminalSession`, `TermuxPtySession` |
| `Real*` | Production implementation of a testable seam | `RealTerminalView` |
| `Recording*` | Test double that records calls | `RecordingWakeLock` |
| `Fake*` | Test double with scripted behavior | `FakeProot` |

Names should describe the role, not the implementation detail. A class that
implements a domain port usually keeps the port name when there is only one
implementation (`WorkspaceFileManagerImpl` is the exception because the
service-level `WorkspaceFileManager` already owns that name).

---

## 3. Package layering

| Package | Depends on | Responsibility |
|---|---|---|
| `core` | Platform and small utilities | Logging, formatting, filesystem windows, security helpers |
| `domain` | `core` | Models, ports, pure logic, use cases |
| `data` | `domain` | Room, preferences, Android-backed implementations of domain ports |
| `service` | `domain`, `data`, `core` | Runtime, install, download, workspace supervisors, foreground service |
| `ui` | `domain`, `data`, `service` through DI | Compose screens, view models, navigation |
| `di` | All layers | Hilt bindings |

`ImportBoundaryTest` enforces:

1. `domain` does not depend on `service.runtime.container`.
2. `ui` does not depend on concrete container implementations
   (`PRootContainerRuntime`, `PRootLauncher`, `TmuxPersist`); it may hold the
   `ContainerRuntime` interface.
3. `domain` does not depend on `service` except for a small documented set of
   exceptions.
4. `domain` does not depend on `android.*` except for documented platform value
   types.

The test also has a source-level backstop for constants that the Kotlin
compiler inlines and ArchUnit cannot see. Add a rule or an explicit exception
when a dependency is genuinely required; do not bypass the test with an
unrelated import.

---

## 4. Error handling

- Use sealed result/state types for operations that can fail:
  `InstallResult`, `CommandResult`, `WorkspaceStatus.Broken(reason)`,
  `FileOpResult`.
- Preserve the underlying failure message when it is actionable. A guest
  command's stderr is usually better than a generic string.
- Fail-open is for UI state, not for data integrity. A missing metric renders
  as unknown; a failed write is reported as a failure, never as success.
- Do not truncate silently. If a value cannot be represented, fail with a
  reason.
- Report success only after the underlying process has exited and its exit code
  has been checked.

---

## 5. Coroutines and Flow

- Use structured concurrency. Do not use `GlobalScope`.
- Samplers and pollers are explicitly start/stop gated. A ViewModel starts
  polling when its screen becomes visible and stops when it leaves.
- UI collection uses `collectAsState()` today. `collectAsStateWithLifecycle()`
  appears only in a comment because of the pinned Compose BOM/lifecycle/navigation
  version interaction.
- Polling loops serialize and coalesce ticks where a shared sampler exists.
  Shared intervals live in `ProcessPolling.INTERVAL_MS`.
- Prefer `StateFlow` for screen state and `SharedFlow`/channels for one-shot
  events.
- `runBlocking` is limited to a few bounded call sites:
  `LinexaDocumentsProvider` (a binder-thread query that needs a repository
  first value) and `RootfsFixupService` (bounded mirror selection during
  install). Do not add it to a UI path.
- PTY creation must happen on a thread with a Looper; production dispatches it
  to the main Looper through `SessionCreationDispatcher`.

---

## 6. UI conventions

- Use the Haven component layer and tokens for new UI. `HavenDim` is the active
  shape/rhythm vocabulary; `Dimens.Spacing` is the spacing grid.
- Every actionable icon needs a content description or an explicit decorative
  classification. `contentDescription = null` is correct only for decoration.
- Technical content (commands, paths, IDs, logs) stays LTR and monospaced.
- Unknown values render as a dash or an explanatory label, never as zero.
- Destructive actions require confirmation that names the object and the
  consequence.

---

## 7. Documentation conventions

- Public docs live under `docs/` and are indexed in `docs/README.md`.
- Each document is a technical reference: what the code does, where it lives,
  and what limits apply.
- Use real repository paths. Avoid line numbers that drift; name the class or
  function instead.
- ADRs record decisions and rationale in
  [`decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md).
- KDoc explains contracts, invariants, and non-obvious platform behavior. It
  should not narrate a sequence of past fixes.
- Keep `docs/README.md` and root `README.md` links valid when adding or moving
  documents.

---

## 8. Dependency and build conventions

- Do not change the Gradle wrapper, AGP, Kotlin, or SDK versions for feature
  work; they are frozen by `AGENTS.md`.
- Do not add JitPack or a new repository without an explicit decision.
- Native binaries are vendored under `app/src/main/jniLibs/` with entries in
  `BINARY-MANIFEST.md` and `THIRD_PARTY_NOTICES.md`.
- Room schema changes follow [`ROOM_MIGRATIONS.md`](../ROOM_MIGRATIONS.md).

---

## 9. Related documentation

- [`architecture/APPLICATION_ARCHITECTURE.md`](../architecture/APPLICATION_ARCHITECTURE.md) — package layering
- [`decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md) — architectural decisions
- [`design/DESIGN_SYSTEM.md`](../design/DESIGN_SYSTEM.md) — tokens
- [`engineering/TESTING_STRATEGY.md`](./TESTING_STRATEGY.md) — test layers
