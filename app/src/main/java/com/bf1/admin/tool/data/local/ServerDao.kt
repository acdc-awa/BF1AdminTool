package com.bf1.admin.tool.data.local

import androidx.room.*
import com.bf1.admin.tool.data.local.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers WHERE ownerPersonaId = :personaId ORDER BY isActive DESC, serverName ASC")
    fun getByOwner(personaId: String): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE ownerPersonaId = :personaId AND isActive = 1 LIMIT 1")
    suspend fun getActiveByOwner(personaId: String): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerEntity): Long

    @Delete
    suspend fun delete(server: ServerEntity)

    @Query("UPDATE servers SET isActive = 0 WHERE ownerPersonaId = :personaId")
    suspend fun deactivateAllByOwner(personaId: String)

    @Query("UPDATE servers SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    @Transaction
    suspend fun switchActive(personaId: String, id: Long) {
        deactivateAllByOwner(personaId)
        activate(id)
    }
}
