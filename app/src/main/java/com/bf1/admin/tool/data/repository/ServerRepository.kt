package com.bf1.admin.tool.data.repository

import com.bf1.admin.tool.data.local.ServerDao
import com.bf1.admin.tool.data.local.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

class ServerRepository(private val serverDao: ServerDao) {
    fun getServersByOwner(personaId: String): Flow<List<ServerEntity>> =
        serverDao.getByOwner(personaId)

    suspend fun getActiveByOwner(personaId: String): ServerEntity? =
        serverDao.getActiveByOwner(personaId)

    suspend fun addServer(
        serverId: String,
        serverName: String,
        ownerPersonaId: String,
        gameId: String? = null
    ): Long {
        return serverDao.insert(
            ServerEntity(
                serverId = serverId,
                serverName = serverName,
                ownerPersonaId = ownerPersonaId,
                gameId = gameId
            )
        )
    }

    suspend fun switchActive(personaId: String, id: Long) =
        serverDao.switchActive(personaId, id)

    /** 回填/更新服务器 gameId（14 位 GUID）。 */
    suspend fun updateGameId(id: Long, gameId: String?) = serverDao.updateGameId(id, gameId)

    suspend fun delete(server: ServerEntity) = serverDao.delete(server)
}
