# Agent and contributor build instructions

Linexa builds with the committed Gradle wrapper and a standard Android
toolchain. Public contributors should use Android Studio or a command-line
Android SDK with JDK 17, Android SDK platform 34, and build-tools 34.0.0.

- Keep the Gradle wrapper and all AGP/Kotlin/SDK version settings unchanged
  for feature work.
- Do not commit `local.properties`, signing keystores, passwords, tokens, or
  other credentials. `local.properties` is machine-specific.
- Build commands and verification: [`docs/engineering/BUILD_AND_DEVELOPMENT.md`](docs/engineering/BUILD_AND_DEVELOPMENT.md).
- Room schema changes: every `@Database(version = N)` bump ships
  `Migration(N-1, N)` and the exported `N.json`; see
  [`docs/ROOM_MIGRATIONS.md`](docs/ROOM_MIGRATIONS.md).
- Authoritative technical documentation index: [`docs/README.md`](docs/README.md).
