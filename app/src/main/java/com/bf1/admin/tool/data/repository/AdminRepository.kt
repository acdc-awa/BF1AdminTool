package com.bf1.admin.tool.data.repository

import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.data.remote.PersonaNotFoundException
import com.bf1.admin.tool.data.remote.RotatedCookies

class AdminRepository(
    private val accountRepo: AccountRepository,
    private val api: EAApiService = EAApiService()
) {
    /**
     * 完整认证流程，返回 SessionInfo（含 persona 用于展示、rotated 用于落库）。
     * 首次登录 / 手动验证时使用。
     *
     * 注意：调用方拿到结果后需自行调用 [persistRotation] 落库轮换出的新凭证 ——
     * 此处不代劳，因为账号可能尚未入库（首次登录）。
     */
    suspend fun authenticate(remid: String, sid: String): Result<EAApiService.SessionInfo> {
        return api.authenticate(remid, sid)
    }

    suspend fun refreshSessionId(
        accountId: Long,
        remid: String,
        sid: String
    ): EAApiService.RefreshResult {
        val result = api.refreshSessionId(remid, sid)
        persistRotation(accountId, remid, sid, result.rotated)
        return result
    }

    /**
     * 把 EA 轮换出的新 cookie 落库到**明确指定**的账号。
     *
     * accountId 由调用方传入而非从共享状态推断 —— 后者会在多账号场景下把
     * 一个账号的凭证写到另一个账号头上。
     */
    suspend fun persistRotation(
        accountId: Long,
        oldRemid: String,
        oldSid: String,
        rotated: RotatedCookies
    ) {
        if (!rotated.hasAny) return
        accountRepo.updateCredentials(
            accountId,
            rotated.remid ?: oldRemid,
            rotated.sid ?: oldSid
        )
    }

    /** 按 gameId 查询服务器完整信息（响应含 serverId + gameId）。 */
    fun getFullServerDetails(sessionId: String, gameId: String) =
        api.getFullServerDetails(sessionId, gameId)

    fun getAdminList(sessionId: String, serverId: String) =
        api.getAdminList(sessionId, serverId)

    /**
     * 玩家名 → PID：EA 原生查询优先，非「玩家不存在」类失败兜底 gametools。
     * 需要当前账号的 remid/sid（getAccessToken 用）；EA 查询产生的轮换 cookie 落库。
     */
    suspend fun resolvePlayerName(playerName: String): PlayerResolveResult {
        val account = accountRepo.getActiveEncrypted() ?: throw Exception("请先登录账号")
        return try {
            val result = api.resolvePlayerNameByEAID(account.remid, account.sid, playerName)
            persistRotation(account.id, account.remid, account.sid, result.rotated)
            PlayerResolveResult(result.personaId, PlayerResolveSource.EA)
        } catch (e: PersonaNotFoundException) {
            // 查无此人：gametools 也查不到，不兜底，直接报错
            throw e
        } catch (e: Exception) {
            // 凭证过期 / insufficient_scope / 网络等：兜底 gametools
            PlayerResolveResult(
                api.resolvePlayerNameGametools(playerName),
                PlayerResolveSource.GAMETOOLS
            )
        }
    }

    fun addAdmin(sessionId: String, serverId: String, personaId: String) =
        api.addAdmin(sessionId, serverId, personaId)

    fun removeAdmin(sessionId: String, serverId: String, personaId: String) =
        api.removeAdmin(sessionId, serverId, personaId)

    fun getWelcomeMessage(sessionId: String) = api.getWelcomeMessage(sessionId)
}

enum class PlayerResolveSource { EA, GAMETOOLS }

data class PlayerResolveResult(val personaId: String, val source: PlayerResolveSource)
