package com.bf1.admin.tool.data.repository

import com.bf1.admin.tool.data.remote.EAApiService

/**
 * 管理员操作门面：只消费现成的 sessionId，不含任何凭证兑换与轮换落库
 * （统一由 CredentialManager 生产，见 data.session.CredentialManager）。
 */
class AdminRepository(
    private val api: EAApiService = EAApiService()
) {
    /** 按 gameId 查询服务器完整信息（响应含 serverId + gameId）。 */
    fun getFullServerDetails(sessionId: String, gameId: String) =
        api.getFullServerDetails(sessionId, gameId)

    fun getAdminList(sessionId: String, serverId: String) =
        api.getAdminList(sessionId, serverId)

    fun addAdmin(sessionId: String, serverId: String, personaId: String): String =
        api.addAdmin(sessionId, serverId, personaId)

    fun removeAdmin(sessionId: String, serverId: String, personaId: String): String =
        api.removeAdmin(sessionId, serverId, personaId)

    fun getWelcomeMessage(sessionId: String) = api.getWelcomeMessage(sessionId)
}
