# Agent build instructions

This project uses the machine's centralized Android toolchain.

- Canonical root: `~/Tools` in Termux, `/mnt/termux/Tools` inside Ubuntu proot.
- Load it before building: `. ~/Tools/env/toolchain.sh` (Termux) or
  `. /mnt/termux/Tools/env/toolchain.sh` (Ubuntu). New shells do this
  automatically.
- The environment file provides `JAVA_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`,
  `GRADLE_USER_HOME`, and the toolchain `PATH` entries.
- Do not download or create project-local JDK, Android SDK, NDK, or CMake
  installations.
- Keep this project's Gradle wrapper and all AGP/Kotlin/SDK version settings
  unchanged.
- Room schema changes: every `@Database(version = N)` bump ships `Migration(N-1, N)` and the
  exported `N.json`; see [`docs/ROOM_MIGRATIONS.md`](docs/ROOM_MIGRATIONS.md).
- `local.properties` is machine-specific and must not be committed.
- Build commands and verification: [`docs/engineering/BUILD_AND_DEVELOPMENT.md`](docs/engineering/BUILD_AND_DEVELOPMENT.md).
- Authoritative technical documentation index: [`docs/README.md`](docs/README.md).
- Architecture, features, design, engineering, planning, analysis, and decision documents live under `docs/` — see `docs/README.md` for the full index.
