# Linexa — Linux Workspace Manager for Android

Linexa is a Jetpack Compose Android app that runs real Linux distributions in
isolated workspaces via PRoot, with a real PTY terminal and a staged,
verified RootFS importer.

**Current release:** `0.1.0-beta` (`versionCode` 1). This is a beta: interfaces,
guest support, and device behaviour may change between releases. See
[`docs/ROOM_MIGRATIONS.md`](./docs/ROOM_MIGRATIONS.md) before changing the
database schema.

Copyright (C) 2026 ForExample25. Licensed under **GPL-3.0-or-later** — see
[`LICENSE`](./LICENSE) and the [License](#license) section below for the
project's own grant and the separate licenses of the bundled components.

## What it does

- **Workspaces** — import a RootFS archive and run a Linux userland in an
  isolated PRoot session. The importer validates the archive, stages the
  extraction, and activates the workspace only after verification.
- **Real terminal** — a persistent PTY per workspace with full ANSI/cursor
  rendering, scrollback, text selection, copy/paste, pinch-to-zoom, and an
  extra-keys row. The terminal stack is the vendored Apache-2.0 Termux
  `terminal-emulator` and `terminal-view` modules.
- **Desktop access** — per-workspace VNC mode (built-in RFB 3.8 client) or
  Web mode (an in-app WebView pointed at a guest web server). The desktop
  backend is selected per workspace; VNC mode requires an `Xvnc` binary in
  the guest.
- **Workspace tools** — file manager with editor and SAF document provider,
  package manager with a per-workspace operation queue, process/task manager,
  per-workspace DNS/hosts/mirror settings, structured commands, and shortcuts.
- **Local-first data** — workspace metadata lives in Room; configuration
  files, exports, and backups stay on the device unless the user shares them.

## Requirements

- Android Studio Koala or newer, or a command-line Android SDK.
- JDK 17 (a full JDK, not a JRE — AGP's `JdkImageTransform` needs `jlink`).
- Android SDK platform 34 and build-tools 34.0.0.
- A device or emulator running Android 9+ (`minSdk 28`).

The Gradle wrapper is committed. Dependency resolution needs normal internet
access to Google's Maven repository and Maven Central on the first build.

## Build and test

```bash
# Point the build at your SDK (local.properties is gitignored):
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:assembleRelease        # release APK (requires signing config)
```

Release builds read `signing.storeFile`, `signing.storePassword`,
`signing.keyAlias`, and `signing.keyPassword` from `local.properties`. The
release task fails loudly when the signing material is missing; it does not
silently produce an unsigned APK. Never commit `local.properties` or the
keystore.

This source tree does **not** contain a CI workflow. Build and test results
are therefore not independently verifiable from a CI badge; run the commands
above on your machine. On-device testing is still required for runtime,
terminal, desktop, and RootFS-import behaviour.

## Native components

The app ships prebuilt native programs in `app/src/main/jniLibs/`: PRoot,
BusyBox, GNU tar, Zstandard, tmux, and the shared libraries they link against.
The importer uses the bundled GNU tar under PRoot for extraction after a
JVM-side format/validation pass (`commons-compress` + `zstd-jni`). No native
binary is downloaded at runtime for the core runtime; the binaries are part
of the APK.

- Exact versions, per-ABI SHA-256 values, and dependency closure:
  [`app/src/main/jniLibs/BINARY-MANIFEST.md`](./app/src/main/jniLibs/BINARY-MANIFEST.md).
- PRoot rebuild recipe and Linexa patches 0001–0004:
  [`app/src/main/jniLibs/proot-build/BUILD-NOTES.md`](./app/src/main/jniLibs/proot-build/BUILD-NOTES.md).
- Termux terminal module provenance:
  [`terminal-emulator/PROVENANCE.md`](./terminal-emulator/PROVENANCE.md) and
  [`terminal-view/PROVENANCE.md`](./terminal-view/PROVENANCE.md).

The complete third-party inventory — versions, licenses, copyright notices,
and the written offer for corresponding source — is
[`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md). The same list is shown
in the app at **Settings → About → Open source licenses**, with the verbatim
license texts.

## Project layout

| Path | Contents |
|---|---|
| `app/` | Android application, Compose UI, Room, runtime services, tests |
| `terminal-emulator/` | Vendored Termux terminal emulator + PTY JNI (Apache-2.0) |
| `terminal-view/` | Vendored Termux terminal View (Apache-2.0) |
| `docs/architecture/` | System, runtime, and data/persistence architecture |
| `docs/features/` | Workspace, terminal, desktop, networking, file/task manager |
| `docs/engineering/` | Build, testing, conventions, performance, troubleshooting |
| `docs/design/` | HAVEN design system, UI/UX, accessibility |
| `docs/decisions/` | Architectural decision register |

`docs/README.md` is the documentation index. Internal audit reports, planning
ledgers, dated evidence, and historical snapshots are maintained outside the
published source tree and are not part of this release.

## License

Copyright (C) 2026 ForExample25

Linexa is free software: you can redistribute it and/or modify it under the
terms of the **GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version** (`GPL-3.0-or-later`).

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

The full license text is in [`LICENSE`](./LICENSE) — a verbatim copy of
<https://www.gnu.org/licenses/gpl-3.0.txt>, unmodified.

**Why GPL-3.0-or-later.** The license was compelled by the code Linexa was
built from when the choice was made: the terminal emulator Linexa linked
against ([`terminal-lib`](https://github.com/tiny-computer/terminal-lib)) was
`GPL-3.0-only`, which made a GPLv3-compatible license binding rather than
optional. On **2026-08-29 that dependency was removed** — the terminal stack
is now the vendored **Apache-2.0 Termux terminal modules** (termux/termux-app
`terminal-emulator`/`terminal-view` @ v0.118.3, see
`terminal-emulator/PROVENANCE.md`) — but Linexa remains GPL-3.0-or-later: the
grant was already published, and the still-bundled copyleft runtime (PRoot
`GPL-2.0-or-later`, BusyBox `GPL-2.0-only`, GNU tar `GPL-3.0-or-later`)
means a combined distribution is **effectively** GPLv3 regardless. Relicensing
a work back out of the GPL is not something its own history can undo.

Linexa's GPL-3.0-or-later covers Linexa's own source, not the vendored
programs. Each third-party component remains under its **own** license; the
full inventory is in [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md).
GPL/LGPL binaries are conveyed in the APK with a written offer for their
complete corresponding source, reproduced in that file and in the app's
About screen.

## Security

- No signing credentials, keystores, or API keys are committed. `local.properties`
  and `keystore/` are gitignored and must stay that way.
- The app does not request root; PRoot provides a user-space guest environment.
- To report a vulnerability, open a private security advisory or a minimal
  issue on the project's GitHub repository. Do not include secrets or
  personal data in a public report.

## Documentation

- [`docs/README.md`](./docs/README.md) — documentation index.
- [`docs/architecture/SYSTEM_ARCHITECTURE.md`](./docs/architecture/SYSTEM_ARCHITECTURE.md) — system overview.
- [`docs/engineering/BUILD_AND_DEVELOPMENT.md`](./docs/engineering/BUILD_AND_DEVELOPMENT.md) — toolchain and build details.
- [`docs/engineering/TESTING_STRATEGY.md`](./docs/engineering/TESTING_STRATEGY.md) — test layers and device matrix.
- [`docs/ROOM_MIGRATIONS.md`](./docs/ROOM_MIGRATIONS.md) — Room schema/migration policy.

## Contributing

Keep the architecture, tests, and attribution catalog consistent with the
code. Before opening a change:

1. Run `./gradlew :app:testDebugUnitTest`.
2. If you add or remove a native binary or dependency, update
   `AttributionCatalog.kt`, `THIRD_PARTY_NOTICES.md`, and
   `app/src/main/jniLibs/BINARY-MANIFEST.md`.
3. If you change a Room schema, follow `docs/ROOM_MIGRATIONS.md` and commit
   the exported schema JSON.
4. Never commit `local.properties`, `keystore/`, credentials, or build output.
