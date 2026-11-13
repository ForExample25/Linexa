package com.fem.linexa.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.fem.linexa.domain.workspace.BooleanMapConverter
import com.fem.linexa.domain.workspace.MapConverter
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import com.fem.linexa.domain.workspace.WorkspaceStatusConverter

/**
 * Linexa's Room database.
 *
 * **Migration policy:** every version bump ships a `Migration(N-1, N)` registered in
 * `AppModule.provideDatabase` *and* the exported `app/schemas/…/N.json`; released schema
 * files are frozen and destructive fallback is forbidden. Full rule, bump checklist and
 * the tests that enforce it: `docs/ROOM_MIGRATIONS.md` (`RoomSchemaPolicyTest`).
 */
@Database(
    entities = [WorkspaceMetadata::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    WorkspaceStatusConverter::class,
    MapConverter::class,
    BooleanMapConverter::class
)
abstract class LinexaDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
}
