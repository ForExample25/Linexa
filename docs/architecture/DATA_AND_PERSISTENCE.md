# Data and Persistence

**Status:** Current — describes the implemented persistence model, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/data/room/`, `app/src/main/java/com/fem/linexa/domain/workspace/`, `app/src/main/java/com/fem/linexa/service/installer/`, `app/src/main/java/com/fem/linexa/service/rootfs/`, `app/src/main/assets/`, `app/schemas/`

---

## 1. Purpose

This document is the authoritative reference for Linexa's data and persistence: the Room database, `WorkspaceMetadata` schema, the RootFS metadata schema (`linexa.yaml`), metadata precedence, and the install pipeline.

---

## 2. Scope

In scope:

- Room database (`LinexaDatabase`, `WorkspaceMetadata`, schema version 1, exported schema)
- `WorkspaceMetadata` fields and status lifecycle
- RootFS metadata schema (`linexa.yaml`, schema versions 1 and 2)
- Metadata precedence ladder (archive → bundled version-specific → bundled generic → legacy `.tiny.yaml` → undeclared probe)
- Bundled version matching (`BundledRootfsMetadataProvider`)
- Install pipeline and activation
- Runtime-only state boundaries (what is persisted vs. what resets on process death)

Out of scope:

- Feature runtime behavior: [`features/`](../features/)
- Build/toolchain: [`engineering/BUILD_AND_DEVELOPMENT.md`](../engineering/BUILD_AND_DEVELOPMENT.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| Room `LinexaDatabase`, version 1, one entity | Implemented | `data/room/LinexaDatabase.kt` |
| Exported schema committed (`1.json`) | Implemented | `app/schemas/com.fem.linexa.data.room.LinexaDatabase/1.json` |
| No Room migrations registered | Implemented | `di/AppModule.kt` plain `.build()` |
| `WorkspaceMetadata` sealed status enum | Implemented | `domain/workspace/WorkspaceStatus.kt` |
| RootFS metadata schema `linexa.yaml` v1+v2 | Implemented | `service/rootfs/`, `ROOTFS_METADATA_SCHEMA.md` content verified against source |
| Staged installer with recovery journal | Implemented | `service/installer/StagedRootFsInstaller.kt`, `InstallRecoveryManager` |
| Session/terminal state runtime-only | Implemented | `SessionManager.kt`, `RuntimeState.kt`, `TerminalState.kt` |

---

## 4. Technical overview

### 4.1 Room database

`LinexaDatabase` — `@Database(entities = [WorkspaceMetadata::class], version = 1, exportSchema = true)`, single entity `WorkspaceMetadata`, table `workspaces`, `@PrimaryKey id`. Converters: `WorkspaceStatusConverter`, `MapConverter`, `BooleanMapConverter`, `DistroInfoConverter`, `RuntimeConfigConverter`, `TerminalSettingsConverter`, `DesktopSettingsConverter`. DAO exposes `Flow` queries (`observeAll`, `observeById`). Schema JSON committed at `app/schemas/com.fem.linexa.data.room.LinexaDatabase/1.json`. No migrations registered; plain `Room.databaseBuilder(...).build()`. `fallbackToDestructiveMigration` not set.

### 4.2 WorkspaceMetadata fields

| Field | Type | Default |
|---|---|---|
| `id` | String (@PrimaryKey) | — |
| `schemaVersion` | Int | 1 |
| `status` | WorkspaceStatus | — |
| `distro` | DistroInfo | — |
| `displayName` | String | — |
| `avatarLetter` | String | — |
| `avatarColorHex` | String | — |
| `rootfsPath` | String | — |
| `createdAt` | Long | — |
| `lastLaunchedAt` | Long? | null |
| `runtimeConfig` | RuntimeConfig | — |
| `terminalSettings` | TerminalSettings | — |
| `desktopSettings` | DesktopSettings? | null |
| `featureToggles` | Map<String, Boolean> | emptyMap |
| `pluginConfig` | Map<String, String> | emptyMap |
| `attributes` | Map<String, String> | emptyMap |

`WorkspaceStatus`: `Created`, `Importing(progress)`, `Installed`, `Ready`, `Broken(reason)`, `Repairing`, `Deleting`.

### 4.3 Persistence boundaries

| Persisted | Runtime-only | Filesystem |
|---|---|---|
| `WorkspaceMetadata` (Room) — all workspace config | `SessionManager` sessions, `RuntimeState`, `TerminalState`, `WorkspaceHealthRegistry`, `RuntimeTrace` | RootFS archives, install journals, logs, bootstrap symlinks |
| Settings via `SettingsRepositoryImpl` (SharedPreferences): theme mode, color theme, terminal font, battery optimization rows | | |

Session and terminal state reset to `Stopped` on fresh process start. Secret workspace attributes (currently the VNC password) are persisted as an AES-GCM envelope under an `AndroidKeyStore` key by `data/repository/WorkspaceSecretAttributes.kt`; legacy plaintext rows are rewritten at app start by `service/workspace/VncPasswordMigrationManager.kt`. The decrypted value exists only in memory — see [`features/SECURITY_AND_PERMISSIONS.md`](../features/SECURITY_AND_PERMISSIONS.md).

### 4.4 RootFS metadata schema (`linexa.yaml`)

All keys live under the single top-level `linexa:` mapping, shared with per-workspace configuration (`features`, `env`, `launch`, `host_info`). The presentation block is spelled `metadata:` (`presentation:` is rejected).

Schema version 2 (required; build supports 1 and 2):

```yaml
linexa:
  schema_version: 2
  distro:
    id: debian
    name: Debian
    version: "12"
    codename: bookworm
  architecture: arm64
  rootfs:
    type: base
    compressed_format: tar.xz
  desktop:
    enabled: false
    type: xfce
    session: startxfce4
  package_manager:
    type: apt
  environment:
    shell: /bin/bash
    shell_args: ["--login"]
    user: linexa
    home: /home/linexa
    variables:
      LINEXA_WORKSPACE: /home/linexa
  workspace:
    directory: /home/linexa
  shell_config:
    aliases:
      la: "ls -A"
      ll: "ls -alF"
  init:
    post_install: [...]
    first_boot: [...]
  health_check:
    command: "test -x /bin/bash && test -x /usr/bin/apt-get"
  metadata:
    display_name: Debian
    description: …
    preview: opt/linexa/preview.png
  shortcuts:
    - id: software-management
      label: Software Management
      children:
        - id: chromium-install
          label: Install
          action: install
          command: "apt-get update && apt-get install -y chromium"
    - id: root-shell
      label: Root shell
      as_root: true
```

Schema 2 adds exactly three things to `shortcuts:`: `children:` (navigable groups), `action:` (semantic tag: install/remove/update/launch/check), `as_root:` (elevation launcher). Groups with their own `command:`/`action:`/`as_root:` are rejected. Flat schema-1 entries mix freely with groups.

### 4.5 Metadata precedence

When Linexa needs to know what a rootfs is, sources are consulted in this exact order. The FIRST source that yields a valid native declaration wins; later sources never override it.

| Priority | Source | Notes |
|---|---|---|
| 1 | Archive-carried native `.linexa.yaml` / `linexa.yaml` / `config.yaml` (archive root) | Validated fail-hard at install; persisted verbatim as `{workspace}/config.yaml` |
| 2 | Bundled version-specific `assets/rootfs/metadata/<distro>-<version>.linexa.yaml` | Resolved by `BundledRootfsMetadataProvider.forDistro(id, version)` |
| 3 | Bundled generic `assets/rootfs/metadata/<distro>.linexa.yaml` | Default for stock catalog images |
| 4 | Archive-carried legacy `.tiny.yaml` | TinyContainer compatibility; presentation + quick-commands ONLY; fail-open |
| 5 | Undeclared | Signal-based detection (`RootFsProbe`): os-release, marker files, ELF probes |

The installer applies the bundled profile only when the archive carried no **native** declaration (`effectiveCaptured == null` gate — a `.tiny.yaml`-only archive receives the bundled profile). The selected source is logged and recorded on `RootFsDescriptor.source` and `InstallSummary.metadataSource`.

### 4.6 Bundled version matching

`forDistro(distroId, version)` tries: exact `<distro>-<version>.linexa.yaml`; progressively shorter dot-prefixes (`22.04.5` → `22.04` → `22`); `<distro>.linexa.yaml`. First candidate that exists wins. Bundled profiles shipped today: `debian.linexa.yaml`, `ubuntu.linexa.yaml` (generic; version/codename deliberately omitted).

### 4.7 Validation rules (fail-hard at install)

- Paths (`home`, `workspace.directory`, `shell`): absolute, no whitespace/NUL, no `..` segments; workspace must not be `/`.
- `shell_args`: single argv tokens (no whitespace/NUL), ≤ 8 entries.
- `variables`: POSIX name shape, single-line scalar values, ≤ 24 entries.
- `aliases`: identifier names, single-line expansions, ≤ 40 entries.
- `init` / `health_check` / `shortcuts`: single-line, length-capped commands.
- Everything structural rejected at import with the exact YAML path named.

### 4.8 How the runtime consumes the declaration

- **Shell** — `config.yaml launch.shell` (user override) → declared `environment.shell` (verified to exist) → probe `/bin/bash` → `/bin/sh`.
- **Home / USER / startup directory** — `GuestEnvironmentResolver` resolves `environment.home` (default `/root`), `environment.user` (default `root`), `workspace.directory` (default `<home>/linexa`). Workspace created at install and self-healed at every launch (`ensureGuestWorkspace`), seeded with a README; if creation fails the launch falls back to HOME.
- **Declared env** — `environment.variables` merges before the user's config `env` (config wins) and under the host-key guard (`PATH`, `HOME`, `LD_LIBRARY_PATH`, … can never be overridden).
- **Aliases** — `shell_config.aliases` merge over `GuestRootfsInitializer.DEFAULT_SHELL_ALIASES` into `/etc/profile.d/00-linexa-shell.sh`. Login shells only; user's own `~/.bashrc` always wins.
- **First boot** — `init.post_install` + `init.first_boot` run once per rootfs before the first terminal (marker `{rootfs}/.linexa/first-boot.done`; bounded attempts). Failure never blocks the terminal.
- **Health check** — after activation runs `health_check.command` inside the rootfs (`InstallCoordinator.verifyRootfs`). Non-zero exit marks the workspace **Broken** with command, exit code, output tail as diagnostic.
- **Identity** — a valid declaration outranks probe detection; version mismatches vs. detection surfaced as warnings.

---

## 5. Install pipeline

```
DownloadService / ImportService → StagedRootFsInstaller
  Pass 1 (JVM)  : analyze() — SHA-256, ArchiveValidator, profile resolution, OCI layout
  Pass 2 (native): RootfsExtractor.extract() — proot --link2symlink tar -xf … -C staging
  Pass 2.5      : L2sLinkRewriter.rewrite(staging) — .l2s.* targets → relative
  Pass 3        : validateExtractedRootfs(profile required dirs/shell)
  Pass 3.5      : runInstallSteps(profile plan) — ENSURE_TMP → ENSURE_DEV_SHM → ANDROID_ACCOUNT_FIXUP → SEED_RESOLV_CONF
  Pass 4        : writeWorkspaceConfig (captured config.yaml)
  Activation    : WorkspaceFileManager.activateStagedRootfs() — live→previous-<ts>, staging→rootfs (rename)
```

Runtime self-heal before every session/exec/daemon: `ensureGuestTmp`, `ensureGuestDevShm`, `ensureGuestRun`, `ensureProcSnapshots`, `ensureDpkgHealthy` (+ optional `dpkg --configure -a` preflight). `InstallRecoveryManager` handles crash-between-phases at app startup.

---

## 6. Limitations and known issues

- No Room migrations: first schema bump without a migration crashes existing installs on open. Migration policy in [`ROOM_MIGRATIONS.md`](../ROOM_MIGRATIONS.md).
- `WorkspaceMetadata.attributes` holds the VNC password encrypted (AES-GCM/Keystore); backup rules still exclude the DB as defence in depth. Legacy plaintext rows are migrated on startup. See [`features/SECURITY_AND_PERMISSIONS.md`](../features/SECURITY_AND_PERMISSIONS.md).
- Full RootFS install flows still need device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 7. Related documentation

- [`architecture/SYSTEM_ARCHITECTURE.md`](./SYSTEM_ARCHITECTURE.md) — system architecture
- [`architecture/RUNTIME_ARCHITECTURE.md`](./RUNTIME_ARCHITECTURE.md) — PRoot runtime, installer interaction
- [`features/SECURITY_AND_PERMISSIONS.md`](../features/SECURITY_AND_PERMISSIONS.md) — VNC password, backup rules
- [`ROOM_MIGRATIONS.md`](../ROOM_MIGRATIONS.md) — migration policy and exported schemas
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
