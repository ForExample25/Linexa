# Room migration policy

`LinexaDatabase` (`app/src/main/java/com/fem/linexa/data/room/LinexaDatabase.kt`) holds
every workspace's metadata. A user's database outlives any single app build, so a schema
change that is not migrated either crashes at open or — worse — silently wipes workspaces.

## The rule

**Every `@Database(version = N)` bump ships, in the same change:**

1. a `Migration(N-1, N)` that upgrades an existing database in place, registered with
   `.addMigrations(...)` where the database is built (`di/AppModule.kt`, `provideDatabase`);
2. the exported schema file `app/schemas/com.fem.linexa.data.room.LinexaDatabase/N.json`.

Corollaries, all enforced by `RoomSchemaPolicyTest`:

- **No destructive fallback.** `fallbackToDestructiveMigration*` is never allowed: it would
  delete the user's workspaces on any missing migration.
- **Released schema files are frozen.** A committed `N.json` for a version that has shipped is
  never edited or regenerated. If an entity change makes the build rewrite it, the change
  needs a version bump instead of an overwritten file.
- **No gaps.** `1.json … N.json` all exist, and no file is newer than the declared version.

## How to bump the version

1. Change the entity (`domain/workspace/WorkspaceMetadata.kt` or whatever the entity is).
2. Increase `version` in `@Database`.
3. Write `Migration(N-1, N)` (SQL `ALTER TABLE …` / table rebuild as needed) and add it to
   `addMigrations(...)` in `provideDatabase`.
4. Build once. KSP writes `app/schemas/…/N.json` (the `room.schemaLocation` argument in
   `app/build.gradle.kts`). Commit that file.
5. Run `./gradlew :app:testDebugUnitTest --tests "com.fem.linexa.data.room.*"`.
6. When the version **ships**, add `N to "<identityHash from N.json>"` to `FROZEN_HASHES` in
   `RoomSchemaPolicyTest`. From then on its file must never change. (Until release the current
   version's file may still be regenerated while the entity settles.)

## What the tests check

`RoomSchemaPolicyTest` (plain JVM): declared version vs. schema files; each file's internal
consistency (its `version` equals its name, its hash matches the `room_master_table` row);
the current file's columns vs. the entity's fields; frozen hashes of released versions;
`Migration(k, k+1)` for every step plus `addMigrations(`; no destructive fallback.

`RoomSchemaRuntimeHashTest` (Robolectric): opens a real in-memory `LinexaDatabase` and checks
that Room's generated identity hash and version equal the committed JSON — the only check
that the JSON is what Room would generate right now.

## Known gap

Nothing here *executes* a migration against sample data to prove rows survive. That needs
`androidx.room:room-testing` (`MigrationTestHelper`), which is not a dependency. Until it is
added, a `Migration` must be reviewed by hand and exercised on a device with an old database.
