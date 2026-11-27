# Linexa Documentation Index

Linexa is a Jetpack Compose Android app that runs Linux distributions in
isolated workspaces via PRoot, with a real PTY terminal and a staged RootFS
importer.

This index covers the documentation published with the `0.2.0` source
release. Internal audit reports, planning ledgers, dated evidence archives,
and historical snapshots are maintained separately and are not part of the
public source tree.

## Start here

1. [`../README.md`](../README.md) — project overview, build steps, licensing.
2. [`architecture/SYSTEM_ARCHITECTURE.md`](./architecture/SYSTEM_ARCHITECTURE.md) — layers and subsystem boundaries.
3. [`engineering/BUILD_AND_DEVELOPMENT.md`](./engineering/BUILD_AND_DEVELOPMENT.md) — toolchain and build commands.
4. [`engineering/TESTING_STRATEGY.md`](./engineering/TESTING_STRATEGY.md) — test layers and device verification.
5. [`ROOM_MIGRATIONS.md`](./ROOM_MIGRATIONS.md) — Room schema and migration policy.

## Architecture

- [`architecture/SYSTEM_ARCHITECTURE.md`](./architecture/SYSTEM_ARCHITECTURE.md) — system overview, package layering, entry points, DI, navigation.
- [`architecture/APPLICATION_ARCHITECTURE.md`](./architecture/APPLICATION_ARCHITECTURE.md) — package structure and import boundaries.
- [`architecture/RUNTIME_ARCHITECTURE.md`](./architecture/RUNTIME_ARCHITECTURE.md) — PRoot runtime, PTY terminal stack, session lifecycle, launch paths.
- [`architecture/DATA_AND_PERSISTENCE.md`](./architecture/DATA_AND_PERSISTENCE.md) — Room database, workspace metadata, RootFS metadata, install pipeline.

## Features

- [`features/WORKSPACE_AND_SESSIONS.md`](./features/WORKSPACE_AND_SESSIONS.md) — workspace lifecycle, sessions, terminals.
- [`features/TERMINAL_AND_LINUX_RUNTIME.md`](./features/TERMINAL_AND_LINUX_RUNTIME.md) — PRoot integration, PTY allocation, bootstrap, `/proc`, `/sys`, `/dev`.
- [`features/FILE_MANAGER.md`](./features/FILE_MANAGER.md) — file manager, save pipeline, editor, SAF provider.
- [`features/TASK_MANAGER.md`](./features/TASK_MANAGER.md) — process listing, attribution, and termination safety.
- [`features/DESKTOP_AND_VNC.md`](./features/DESKTOP_AND_VNC.md) — desktop run modes, VNC client, geometry, authentication.
- [`features/NETWORKING.md`](./features/NETWORKING.md) — per-workspace DNS, `/etc/hosts`, package mirrors, RootFS download mirror.
- [`features/SECURITY_AND_PERMISSIONS.md`](./features/SECURITY_AND_PERMISSIONS.md) — security boundaries, permissions, backup rules.
- [`features/ROOT_SHELL_AND_SUDO.md`](./features/ROOT_SHELL_AND_SUDO.md) — root shell entry point and guest `sudo` bridge.
- [`features/HOST_SYSTEM_INFO.md`](./features/HOST_SYSTEM_INFO.md) — host facts and selective guest pass-through.

## Design

- [`design/UI_UX_GUIDELINES.md`](./design/UI_UX_GUIDELINES.md) — HAVEN design philosophy, navigation, motion.
- [`design/DESIGN_SYSTEM.md`](./design/DESIGN_SYSTEM.md) — colors, tokens, typography, spacing, radii.
- [`design/ACCESSIBILITY_AND_LOCALIZATION.md`](./design/ACCESSIBILITY_AND_LOCALIZATION.md) — accessibility semantics and RTL/localization.

## Engineering

- [`engineering/BUILD_AND_DEVELOPMENT.md`](./engineering/BUILD_AND_DEVELOPMENT.md) — toolchain, SDK, Gradle, native binaries, signing.
- [`engineering/TESTING_STRATEGY.md`](./engineering/TESTING_STRATEGY.md) — unit, integration, Robolectric, and device testing.
- [`engineering/CODE_CONVENTIONS.md`](./engineering/CODE_CONVENTIONS.md) — naming, layering, failure philosophy, documentation style.
- [`engineering/PERFORMANCE.md`](./engineering/PERFORMANCE.md) — startup, memory, CPU, Compose performance.
- [`engineering/TROUBLESHOOTING.md`](./engineering/TROUBLESHOOTING.md) — common build/runtime issues.
- [`engineering/DEVICE_COMPATIBILITY.md`](./engineering/DEVICE_COMPATIBILITY.md) — tested devices, ABIs, and Android versions.
- [`engineering/DOCUMENTATION_GUIDELINES.md`](./engineering/DOCUMENTATION_GUIDELINES.md) — how these documents are written and maintained.

## Decisions

- [`decisions/ADR_REGISTER.md`](./decisions/ADR_REGISTER.md) — architectural decision register.

## Provenance records

These live next to the code they describe:

- [`../terminal-emulator/PROVENANCE.md`](../terminal-emulator/PROVENANCE.md) — vendored Termux terminal-emulator module.
- [`../terminal-view/PROVENANCE.md`](../terminal-view/PROVENANCE.md) — vendored Termux terminal-view module.
- [`../app/src/main/jniLibs/BINARY-MANIFEST.md`](../app/src/main/jniLibs/BINARY-MANIFEST.md) — native binaries, versions, hashes, and licenses.
- [`../third_party/proot/BUILD-NOTES.md`](../third_party/proot/BUILD-NOTES.md) — PRoot source, patches, and build flags.
- [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) — complete third-party licenses and corresponding-source offer.
