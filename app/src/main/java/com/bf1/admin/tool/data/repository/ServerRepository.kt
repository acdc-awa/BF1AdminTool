package com.bf1.admin.tool.data.repository

import com.bf1.admin.tool.data.local.ServerDao
import com.bf1.admin.tool.data.local.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

class ServerRepository(private val serverDao: ServerDao) {
    fun getServersByOwner(personaId: String): Flow<List<ServerEntity>> =
        serverDao.getByOwner(personaId)

    suspend fun getServersByOwnerOnce(personaId: String): List<ServerEntity> =
        serverDao.getByOwnerOnce(personaId)

    suspend fun getActiveByOwner(personaId: String): ServerEntity? =
        serverDao.getActiveByOwner(personaId)

    suspend fun updateMetadata(
        id: Long,
        serverId: String,
        serverName: String,
        gameId: String?,
        guid: String?
    ) = serverDao.updateMetadata(id, serverId, serverName, gameId, guid)

    /**
     * 添加或去重更新服务器：按 (ownerPersonaId, guid) 优先匹配，其次按 (ownerPersonaId, serverId) 匹配。
     * 返回 Pair(行 id, 是否为更新已有记录)。
     */
    suspend fun addOrUpdateServer(
        serverId: String,
        serverName: String,
        ownerPersonaId: String,
        gameId: String? = null,
        guid: String? = null
    ): Pair<Long, Boolean> {
        val normalizedGuid = guid?.takeIf { it.isNotBlank() }
        val existing = normalizedGuid?.let { serverDao.findByOwnerAndGuid(ownerPersonaId, it) }
            ?: serverId.takeIf { it.isNotBlank() }?.let { serverDao.findByOwnerAndServerId(ownerPersonaId, it) }
        return if (existing != null) {
            serverDao.updateMetadata(
                id = existing.id,
                serverId = serverId.ifBlank { existing.serverId },
                serverName = serverName.ifBlank { existing.serverName },
                gameId = gameId ?: existing.gameId,
                guid = normalizedGuid ?: existing.guid
            )
            existing.id to true
        } else {
            val newId = serverDao.insert(
                ServerEntity(
                    serverId = serverId,
                    serverName = serverName,
                    ownerPersonaId = ownerPersonaId,
                    gameId = gameId,
                    guid = normalizedGuid
                )
            )
            newId to false
        }
    }

    suspend fun switchActive(personaId: String, id: Long) =
        serverDao.switchActive(personaId, id)

    suspend fun delete(server: ServerEntity) = serverDao.delete(server)
}
