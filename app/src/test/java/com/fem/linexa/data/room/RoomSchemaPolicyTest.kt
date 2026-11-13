package com.fem.linexa.data.room

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the Room migration policy in `docs/ROOM_MIGRATIONS.md`:
 *
 * every `@Database(version = N)` bump ships `Migration(N-1, N)` and the exported `N.json`.
 *
 * Plain JVM. It reads the sources and the exported schema files; the runtime counterpart
 * ([RoomSchemaRuntimeHashTest]) checks the committed JSON against what Room actually
 * generates. Every failure message says what to do next.
 */
class RoomSchemaPolicyTest {

    private val docHint = "See docs/ROOM_MIGRATIONS.md."

    private val declaredVersion get() = RoomSchemaFiles.declaredVersion()

    // ── the declaration ────────────────────────────────────────────────────

    @Test
    fun theDatabaseDeclaresAVersionAndExportsItsSchema() {
        assertTrue("version must be a positive integer, was $declaredVersion", declaredVersion >= 1)
        assertTrue(
            "@Database must keep exportSchema = true — without it no N.json is written and migrations " +
                "cannot be built against a real artifact. $docHint",
            Regex("""\bexportSchema\s*=\s*true\b""").containsMatchIn(RoomSchemaFiles.databaseAnnotationBody())
        )
    }

    // ── the files ──────────────────────────────────────────────────────────

    @Test
    fun everyVersionFromOneToTheDeclaredOneHasASchemaFileAndNoneIsNewer() {
        val present = RoomSchemaFiles.files().keys
        val expected = (1..declaredVersion).toSet()
        assertEquals(
            "Schema files in ${RoomSchemaFiles.SCHEMA_DIR} must be exactly 1.json..$declaredVersion.json " +
                "(missing: ${expected - present}, unexpected: ${present - expected}). Build once after " +
                "bumping the version so KSP writes the new file, and commit it; delete a file left behind " +
                "by a reverted bump. $docHint",
            expected,
            present
        )
    }

    @Test
    fun eachSchemaFileIsInternallyConsistentWithItsName() {
        for ((version, file) in RoomSchemaFiles.files()) {
            val schema = RoomSchemaFiles.parse(file)
            assertTrue("${file.name}: formatVersion must be >= 1", schema.formatVersion >= 1)
            assertEquals("${file.name}: database.version must equal the file name", version, schema.version)
            assertTrue(
                "${file.name}: identityHash must be a 32-char hex Room hash, was '${schema.identityHash}'",
                Regex("[0-9a-f]{32}").matches(schema.identityHash)
            )
            assertTrue(
                "${file.name}: the room_master_table setup query must carry the same identityHash " +
                    "(hand-edited JSON?). Regenerate it by building; never edit it. $docHint",
                schema.setupQueries.any { it.contains("room_master_table") && it.contains(schema.identityHash) }
            )
            assertTrue("${file.name}: must contain the 'workspaces' entity", schema.entity("workspaces") != null)
        }
    }

    @Test
    fun theCurrentSchemaColumnsMatchTheEntityFields() {
        val schema = RoomSchemaFiles.parse(
            RoomSchemaFiles.files()[declaredVersion]
                ?: error("No $declaredVersion.json — see everyVersionFromOneToTheDeclaredOneHasASchemaFileAndNoneIsNewer")
        )
        // Room maps every non-@Ignore property of the entity to a column named after it
        // (WorkspaceMetadata uses no @ColumnInfo). @Ignore has class retention and cannot be seen
        // here; the entity has none, and the runtime-hash test would catch one.
        val entityFields = Class.forName("com.fem.linexa.domain.workspace.WorkspaceMetadata")
            .declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()
        val columns = schema.columnNames("workspaces")
        assertEquals(
            "$declaredVersion.json columns differ from WorkspaceMetadata's fields " +
                "(only in entity: ${entityFields - columns}, only in schema: ${columns - entityFields}). " +
                "If the entity changed, bump the version and add Migration(N-1, N) instead of regenerating " +
                "a released schema file. $docHint",
            entityFields,
            columns
        )
    }

    // ── released schemas are frozen ────────────────────────────────────────

    @Test
    fun releasedSchemasAreFrozen() {
        // Add `N to "<identityHash>"` when version N SHIPS. Never change an existing entry:
        // that means an entity change overwrote a released schema file instead of bumping.
        val frozen = FROZEN_HASHES
        val files = RoomSchemaFiles.files()
        for ((version, hash) in frozen) {
            val actual = RoomSchemaFiles.parse(files[version] ?: error("$version.json is missing")).identityHash
            assertEquals(
                "Released schema $version.json changed (frozen $hash, now $actual). A shipped schema is " +
                    "immutable: revert the file, bump @Database(version) and add Migration($version, ${version + 1}) " +
                    "with the new ${version + 1}.json. $docHint",
                hash,
                actual
            )
        }
        val unfrozenBelowCurrent = (1 until declaredVersion).filter { it !in frozen }
        assertTrue(
            "Versions $unfrozenBelowCurrent are below the declared version $declaredVersion but not in " +
                "FROZEN_HASHES. Add `<version> to \"<identityHash>\"` for each (it shipped). $docHint",
            unfrozenBelowCurrent.isEmpty()
        )
    }

    // ── migrations ─────────────────────────────────────────────────────────

    @Test
    fun everyStepHasAMigrationAndTheyAreRegistered() {
        val migrations = mainSources()
            .flatMap { (_, text) ->
                Regex("""\bMigration\(\s*(\d+)\s*,\s*(\d+)\s*\)""").findAll(text)
                    .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            }
            .toSet()
        val missing = (1 until declaredVersion).filter { (it to it + 1) !in migrations }
        assertTrue(
            "Missing Migration(k, k+1) for k=$missing (declared version $declaredVersion). " +
                "Every bump ships Migration(N-1, N). $docHint",
            missing.isEmpty()
        )
        if (declaredVersion > 1) {
            val appModule = mainSources().firstOrNull { (file, _) -> file.name == "AppModule.kt" }?.second.orEmpty()
            assertTrue(
                "Migrations exist but AppModule.provideDatabase never calls addMigrations(...). $docHint",
                appModule.contains("addMigrations(")
            )
        }
    }

    @Test
    fun destructiveFallbackIsNeverUsed() {
        val offenders = mainSources()
            .filter { (_, text) -> text.contains("fallbackToDestructiveMigration") }
            .map { (file, _) -> file.name }
        assertTrue(
            "fallbackToDestructiveMigration* would delete users' workspaces on a missing migration; " +
                "found in $offenders. Write the Migration instead. $docHint",
            offenders.isEmpty()
        )
    }

    private fun mainSources(): List<Pair<File, String>> =
        File(RoomSchemaFiles.moduleRoot, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to RoomSchemaFiles.stripComments(it.readText()) }
            .toList()

    private companion object {
        /** Released schema versions → identity hash. Append only; see the doc. */
        val FROZEN_HASHES: Map<Int, String> = mapOf(
            1 to "5f108a1896ca5db7293d41fe48493452"
        )
    }
}
