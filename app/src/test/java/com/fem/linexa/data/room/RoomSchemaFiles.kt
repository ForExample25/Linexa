package com.fem.linexa.data.room

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test support for the Room migration policy (`docs/ROOM_MIGRATIONS.md`): finds the
 * module's sources and the exported schema files, and reads them. Shared by
 * [RoomSchemaPolicyTest] and [RoomSchemaRuntimeHashTest]. Plain JVM — no Android classes.
 */
internal object RoomSchemaFiles {

    const val DB_SOURCE = "src/main/java/com/fem/linexa/data/room/LinexaDatabase.kt"
    const val SCHEMA_DIR = "schemas/com.fem.linexa.data.room.LinexaDatabase"

    /** The `app/` module directory, found the same way the existing source-pin tests do. */
    val moduleRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, DB_SOURCE).exists()) return@lazy dir
            dir = dir.parentFile
        }
        File(".")
    }

    val schemaDir: File get() = File(moduleRoot, SCHEMA_DIR)

    /** One parsed `N.json`. */
    class Schema(val file: File, root: JsonObject) {
        val formatVersion: Int = root.getValue("formatVersion").jsonPrimitive.int
        private val db = root.getValue("database").jsonObject
        val version: Int = db.getValue("version").jsonPrimitive.int
        val identityHash: String = db.getValue("identityHash").jsonPrimitive.content
        val entities: List<JsonObject> = db.getValue("entities").jsonArray.map { it.jsonObject }
        val setupQueries: List<String> = db.getValue("setupQueries").jsonArray.map { it.jsonPrimitive.content }

        fun entity(tableName: String): JsonObject? =
            entities.firstOrNull { it.getValue("tableName").jsonPrimitive.content == tableName }

        fun columnNames(tableName: String): Set<String> =
            entity(tableName)?.getValue("fields")?.jsonArray
                ?.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
                ?.toSet()
                ?: emptySet()
    }

    /** `N → file` for every `N.json` in the schema directory. */
    fun files(): Map<Int, File> =
        schemaDir.listFiles()
            .orEmpty()
            .mapNotNull { f -> Regex("""(\d+)\.json""").matchEntire(f.name)?.let { it.groupValues[1].toInt() to f } }
            .toMap()

    fun parse(file: File): Schema = Schema(file, Json.parseToJsonElement(file.readText()).jsonObject)

    /** Source with block and line comments removed, so a KDoc cannot satisfy or confuse a check. */
    fun stripComments(source: String): String =
        source.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    /** The `version = N` declared in `@Database(...)`. */
    fun declaredVersion(): Int = Regex("""\bversion\s*=\s*(\d+)""")
        .find(databaseAnnotationBody())
        ?.groupValues?.get(1)?.toInt()
        ?: error("Could not read `version = N` from the @Database annotation in $DB_SOURCE")

    fun databaseAnnotationBody(): String {
        val source = stripComments(File(moduleRoot, DB_SOURCE).readText())
        return Regex("""@Database\s*\(([\s\S]*?)\)\s*(?:@TypeConverters|abstract|class)""")
            .find(source)?.groupValues?.get(1)
            ?: error("Could not find the @Database annotation in $DB_SOURCE")
    }
}
