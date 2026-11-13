package com.fem.linexa.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fem.linexa.domain.workspace.WorkspaceMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorkspaceMetadata>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    fun observeById(id: String): Flow<WorkspaceMetadata?>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workspace: WorkspaceMetadata)

    @Update
    suspend fun update(workspace: WorkspaceMetadata)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE workspaces SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}
