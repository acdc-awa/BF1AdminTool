package com.bf1.admin.tool.data.repository

import com.bf1.admin.tool.data.local.ServerDao
import com.bf1.admin.tool.data.local.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

class ServerRepository(private val serverDao: ServerDao) {
    fun getServersByOwner(personaId: String): Flow<List<ServerEntity>> =
        serverDao.getByOwner(personaId)

    suspend fun getActiveByOwner(personaId: String): ServerEntity? =
        serverDao.getActiveByOwner(personaId)

    suspend fun addServer(serverId: String, serverName: String, ownerPersonaId: String): Long {
        return serverDao.insert(
            ServerEntity(serverId = serverId, serverName = serverName, ownerPersonaId = ownerPersonaId)
        )
    }

    suspend fun switchActive(personaId: String, id: Long) =
        serverDao.switchActive(personaId, id)

    suspend fun delete(server: ServerEntity) = serverDao.delete(server)
}
