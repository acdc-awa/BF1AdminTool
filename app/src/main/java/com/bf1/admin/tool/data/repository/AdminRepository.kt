package com.bf1.admin.tool.data.repository

import com.bf1.admin.tool.data.remote.EAApiService

class AdminRepository(private val api: EAApiService = EAApiService()) {
    suspend fun authenticate(remid: String, sid: String) = api.authenticate(remid, sid)

    fun getServerDetails(sessionId: String, serverId: String) =
        api.getServerDetails(sessionId, serverId)

    fun getAdminList(sessionId: String, serverId: String) =
        api.getAdminList(sessionId, serverId)

    fun resolvePlayerName(playerName: String) = api.resolvePlayerName(playerName)

    fun addAdmin(sessionId: String, serverId: String, personaId: String) =
        api.addAdmin(sessionId, serverId, personaId)

    fun removeAdmin(sessionId: String, serverId: String, personaId: String) =
        api.removeAdmin(sessionId, serverId, personaId)

    fun verifyToken(remid: String, sid: String) = api.verifyToken(remid, sid)

    fun getWelcomeMessage(sessionId: String) = api.getWelcomeMessage(sessionId)
}
