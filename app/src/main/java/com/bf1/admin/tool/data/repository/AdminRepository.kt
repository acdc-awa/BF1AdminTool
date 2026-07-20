package com.bf1.admin.tool.data.repository

import com.bf1.admin.tool.data.remote.EAApiService

class AdminRepository(
    private val accountRepo: AccountRepository,
    private val api: EAApiService = EAApiService()
) {
    // 追踪最近一次认证的账号，用于挂起 Cookie 更新时定位
    private var lastAuthAccountId: Long = -1
    private var lastAuthRemid: String = ""
    private var lastAuthSid: String = ""

    /**
     * 获取有效的 sessionId，优先使用内部缓存（2h TTL）。
     * 缓存未过期时零网络请求直接返回。
     */
    suspend fun ensureSessionId(accountId: Long, remid: String, sid: String): String {
        lastAuthAccountId = accountId
        lastAuthRemid = remid
        lastAuthSid = sid
        val sessionId = api.ensureSessionId(remid, sid)
        // 消费 EA 服务器可能下发的 Cookie 更新
        applyPendingCookieUpdates()
        return sessionId
    }

    /**
     * 完整认证流程，返回 SessionInfo（含 persona 用于展示）。
     * 首次登录 / 手动验证时使用。
     */
    suspend fun authenticate(remid: String, sid: String): Result<EAApiService.SessionInfo> {
        return api.authenticate(remid, sid)
    }

    private suspend fun applyPendingCookieUpdates() {
        val newRemid = api.pendingNewRemid
        val newSid = api.pendingNewSid
        if (newRemid != null || newSid != null) {
            api.pendingNewRemid = null
            api.pendingNewSid = null
            val id = lastAuthAccountId
            if (id > 0) {
                val r = newRemid ?: lastAuthRemid
                val s = newSid ?: lastAuthSid
                accountRepo.updateCredentials(id, r, s)
                if (newRemid != null) lastAuthRemid = newRemid
                if (newSid != null) lastAuthSid = newSid
            }
        }
    }

    fun getServerDetails(sessionId: String, serverId: String) =
        api.getServerDetails(sessionId, serverId)

    fun getAdminList(sessionId: String, serverId: String) =
        api.getAdminList(sessionId, serverId)

    fun resolvePlayerName(playerName: String) = api.resolvePlayerName(playerName)

    fun addAdmin(sessionId: String, serverId: String, personaId: String) =
        api.addAdmin(sessionId, serverId, personaId)

    fun removeAdmin(sessionId: String, serverId: String, personaId: String) =
        api.removeAdmin(sessionId, serverId, personaId)

    fun getWelcomeMessage(sessionId: String) = api.getWelcomeMessage(sessionId)
}
