# Data and Persistence

**Source locations:** `app/src/main/java/com/fem/linexa/data/room/`,
`app/src/main/java/com/fem/linexa/domain/workspace/`,
`app/src/main/java/com/fem/linexa/service/installer/`,
`app/src/main/java/com/fem/linexa/service/rootfs/`, `app/src/main/assets/`, `app/schemas/`

This document describes the Room database, the `WorkspaceMetadata` schema, the RootFS
metadata format (`linexa.yaml`), metadata precedence, and the install/activation pipeline.
Feature behavior is covered in [`../features/`](../features/).

## 1. Room database

`LinexaDatabase` is declared with `entities = [WorkspaceMetadata::class]`, `version = 1`,
and `exportSchema = true`. The single table is `workspaces`, keyed by `WorkspaceMetadata.id`.
The exported schema is committed at
`app/schemas/com.fem.linexa.data.room.LinexaDatabase/1.json`.

Type converters live with the entity and cover `WorkspaceStatus`, string maps, boolean maps,
`DistroInfo`, `RuntimeConfig`, `TerminalSettings`, and `DesktopSettings`. The DAO exposes
`Flow` queries such as `observeAll` and `observeById`.

No migrations are registered yet: the database is built with a plain
`Room.databaseBuilder(...).build()` and `fallbackToDestructiveMigration` is not set. Every
future version bump must ship a `Migration(N-1, N)` and the exported `N.json` in the same
change. See [`ROOM_MIGRATIONS.md`](../ROOM_MIGRATIONS.md).

## 2. WorkspaceMetadata fields

| Field | Type | Default |
|---|---|---|
| `id` | String (`@PrimaryKey`) | required |
| `schemaVersion` | Int | `1` |
| `status` | `WorkspaceStatus` | required |
| `distro` | `DistroInfo` | required |
| `displayName` | String | required |
| `avatarLetter` | String | required |
| `avatarColorHex` | String | required |
| `rootfsPath` | String | required; diagnostic snapshot, not authoritative at launch |
| `createdAt` | Long | required |
| `lastLaunchedAt` | Long? | `null` |
| `runtimeConfig` | `RuntimeConfig` | required |
| `terminalSettings` | `TerminalSettings` | required |
| `desktopSettings` | `DesktopSettings?` | `null` |
| `featureToggles` | `Map<String, Boolean>` | empty map |
| `pluginConfig` | `Map<String, String>` | empty map; inert compatibility column |
| `attributes` | `Map<String, String>` | empty map |

`WorkspaceStatus` is a sealed interface with the following states:

- `Created` — the database record exists but the rootfs has not been materialized.
- `Importing(progress)` — extraction is in progress; `progress` is `0f..1f`.
- `Installed` — the rootfs exists but has not been validated.
- `Ready` — the rootfs passed validation and can be launched.
- `Broken(reason)` — corruption, interrupted import, or a failed health check.
- `Repairing` — a repair operation is running.
- `Deleting` — deletion is in progress.

## 3. Persistence boundaries

| Persisted | Runtime-only | Filesystem |
|---|---|---|
| `WorkspaceMetadata` in Room | `SessionManager` sessions, `RuntimeState`, `TerminalState`, `WorkspaceHealthRegistry`, `RuntimeTrace` | RootFS archives, install journals, logs, bootstrap files |
| Settings through `SettingsRepositoryImpl` (SharedPreferences): theme mode, color theme, terminal font and layout preferences, language, battery-optimization rows | | |

Session and terminal state reset to `Stopped` when the app process starts fresh. Workspace
identity, configuration, and status survive restarts.

Secret workspace attributes, currently the VNC password, are persisted as an AES-GCM
envelope under an AndroidKeyStore key by `WorkspaceSecretAttributes`. Legacy plaintext rows
are rewritten at app start by `VncPasswordMigrationManager`. The decrypted value exists only
in memory. See [`../features/SECURITY_AND_PERMISSIONS.md`](../features/SECURITY_AND_PERMISSIONS.md).

## 4. RootFS metadata (`linexa.yaml`)

All keys live under the single top-level `linexa:` mapping, shared with per-workspace
configuration (`features`, `env`, `launch`, `host_info`). The presentation block is spelled
`metadata:`; the older `presentation:` spelling is rejected.

The build supports schema versions 1 and 2. Schema 2 adds groups and semantic actions to
`shortcuts:`:

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
    post_install: []
    first_boot: []
  health_check:
    command: "test -x /bin/bash && test -x /usr/bin/apt-get"
  metadata:
    display_name: Debian
    description: Debian userland
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

Schema 2 adds three shortcut capabilities:

- `children:` — navigable groups.
- `action:` — a semantic tag such as `install`, `remove`, `update`, `launch`, or `check`.
- `as_root:` — marks a leaf as an elevated launcher.

A group may not declare its own `command:`, `action:`, or `as_root:`. Flat schema-1 entries
and schema-2 groups may be mixed.

## 5. Metadata precedence

When Linexa needs to identify a rootfs, the first source that yields a valid native
declaration wins. Later sources never override an earlier one.

| Priority | Source | Notes |
|---|---|---|
| 1 | Archive-carried native `.linexa.yaml` / `linexa.yaml` / `config.yaml` at the archive root | Validated fail-hard at install; persisted as `{workspace}/config.yaml` |
| 2 | Bundled version-specific `assets/rootfs/metadata/<distro>-<version>.linexa.yaml` | Resolved by `BundledRootfsMetadataProvider.forDistro(id, version)` |
| 3 | Bundled generic `assets/rootfs/metadata/<distro>.linexa.yaml` | Default for stock catalog images |
| 4 | Archive-carried legacy `.tiny.yaml` | TinyContainer compatibility; presentation and quick commands only; fail-open |
| 5 | Undeclared | Signal-based detection through `RootFsProbe` (os-release, marker files, ELF probes) |

The installer applies a bundled profile only when the archive carried no native declaration.
A `.tiny.yaml`-only archive therefore receives the bundled profile. The selected source is
recorded on `RootFsDescriptor.source` and in `InstallSummary.metadataSource`.

## 6. Bundled version matching

`forDistro(distroId, version)` tries the following candidates in order:

1. exact `<distro>-<version>.linexa.yaml`;
2. progressively shorter dot-prefixes (`22.04.5` → `22.04` → `22`);
3. `<distro>.linexa.yaml`.

The first existing candidate wins. Bundled profiles are shipped for Alpine,
Arch Linux, Debian, Fedora, and Ubuntu under `assets/rootfs/metadata/`.

## 7. Validation rules

Structural validation fails at import, naming the exact YAML path:

- Paths (`home`, `workspace.directory`, `shell`) must be absolute, contain no whitespace or
  NUL, contain no `..` segments, and the workspace directory must not be `/`.
- `shell_args` entries are single argv tokens with no whitespace or NUL, at most 8 entries.
- `variables` names use POSIX name shape, values are single-line scalars, at most 24 entries.
- `aliases` names are identifiers, expansions are single-line, at most 40 entries.
- `init`, `health_check`, and `shortcuts` commands are single-line, length-capped, and never
  shell-interpreted by the parser.

## 8. How the runtime consumes the declaration

- **Shell** — `config.yaml launch.shell` (user override) → declared `environment.shell`
  (verified to exist) → probe `/bin/bash` → `/bin/sh`.
- **Home, user, startup directory** — `GuestEnvironmentResolver` resolves
  `environment.home` (default `/home/linexa`) and `environment.user` (default
  `linexa`). Interactive terminals start in the resolved home, so the prompt reads
  `~`. The account and home are created and self-healed before launch; if creation
  fails, launch falls back to `HOME`. A `workspace.directory` declaration is still
  parsed but no longer changes the startup directory.
- **Declared environment** — `environment.variables` merges before the user's `config.yaml`
  `env` (config wins) and is filtered by the host-key guard. `PATH`, `HOME`,
  `LD_LIBRARY_PATH`, and similar host-owned keys cannot be overridden.
- **Aliases** — `shell_config.aliases` merge over the built-in aliases into
  `/etc/profile.d/00-linexa-shell.sh`. They apply to login shells only; the user's own
  `~/.bashrc` always wins.
- **First boot** — `init.post_install` and `init.first_boot` run once per rootfs before the
  first terminal, guarded by `{rootfs}/.linexa/first-boot.done` and bounded attempts.
  Failure does not block the terminal.
- **Health check** — after activation, `health_check.command` runs inside the rootfs. A
  non-zero exit marks the workspace `Broken` with the command, exit code, and output tail as
  diagnostics.
- **Identity** — a valid declaration outranks probe detection. Version mismatches against
  detection are surfaced as warnings.

## 9. Install and activation pipeline

```
DownloadService / ImportService
  -> StagedRootFsInstaller
       Pass 1 (JVM)    analyze(): SHA-256, archive validation, profile resolution, OCI layout
       Pass 2 (native) RootfsExtractor.extract(): proot --link2symlink tar -xf ... -C staging
       Pass 2.5        L2sLinkRewriter.rewrite(staging): relative .l2s.* targets
       Pass 3          validateExtractedRootfs(): required directories and shell
       Pass 3.5        runInstallSteps(): ENSURE_TMP, ENSURE_DEV_SHM,
                       ANDROID_ACCOUNT_FIXUP, SEED_RESOLV_CONF
       Pass 4          writeWorkspaceConfig(): captured config.yaml
  -> WorkspaceFileManager.activateStagedRootfs()
       live -> rootfs.previous-<timestamp>; staging -> rootfs; restore on failure
```

`InstallRecoveryManager` handles a crash between phases at application startup. Runtime
self-healing before every session, exec, or daemon launch repairs `/tmp`, `/dev/shm`,
`/run`, `/proc` snapshots, and dpkg state.

## 10. Behavior notes

- The `rootfsPath` column is diagnostic. Production launch paths derive the rootfs location
  from the workspace id through `WorkspaceFileManager.getRootfsPath(id)`. A startup backfill
  repairs rows whose stored value disagrees with the derived path.
- `pluginConfig` is an inert compatibility column retained because removing it would require
  a Room migration.
- Backup rules exclude the database as defence in depth even though the VNC secret is
  encrypted at rest.
- An explicit RootFS export asks about guest credential material. The default (`REMOVE_CREDENTIALS`)
  omits the VNC passwd file, SSH private keys, and GnuPG; the explicit "export as-is" choice
  includes the full guest filesystem, including the VNC password file while a desktop is
  provisioned.

## 11. Related documentation

- [`SYSTEM_ARCHITECTURE.md`](./SYSTEM_ARCHITECTURE.md) — system overview and modules
- [`RUNTIME_ARCHITECTURE.md`](./RUNTIME_ARCHITECTURE.md) — PRoot runtime and installer interaction
- [`../features/SECURITY_AND_PERMISSIONS.md`](../features/SECURITY_AND_PERMISSIONS.md) — VNC password and backup rules
- [`ROOM_MIGRATIONS.md`](../ROOM_MIGRATIONS.md) — Room schema and migration policy
