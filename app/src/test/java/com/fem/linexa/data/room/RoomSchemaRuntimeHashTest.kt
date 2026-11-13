package com.fem.linexa.data.room

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one check that a committed `N.json` is what Room generates *now*: open a real
 * in-memory [LinexaDatabase] and compare the identity hash and version Room's generated
 * implementation wrote against the schema file for that version.
 *
 * [RoomSchemaPolicyTest] proves the files are consistent with each other and with the
 * source; this proves they are consistent with the generated code. See
 * `docs/ROOM_MIGRATIONS.md`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RoomSchemaRuntimeHashTest {

    @Test
    fun theGeneratedDatabaseMatchesTheCommittedSchemaForItsVersion() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, LinexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            val runtimeVersion = sqlite.version
            val runtimeHash = sqlite.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { c ->
                assertTrue("room_master_table has no identity row", c.moveToFirst())
                c.getString(0)
            }

            assertEquals(
                "Room's generated version differs from @Database(version) in source — stale build?",
                RoomSchemaFiles.declaredVersion(),
                runtimeVersion
            )
            val file = RoomSchemaFiles.files()[runtimeVersion]
                ?: error("No $runtimeVersion.json for the generated database version $runtimeVersion")
            assertEquals(
                "Committed $runtimeVersion.json does not match the schema Room generates from the " +
                    "current entities. If the entity changed after this version shipped, bump the version " +
                    "and add a Migration; if it is still unreleased, rebuild and commit the regenerated " +
                    "file. See docs/ROOM_MIGRATIONS.md.",
                RoomSchemaFiles.parse(file).identityHash,
                runtimeHash
            )
        } finally {
            db.close()
        }
    }
}
