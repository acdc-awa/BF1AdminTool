package com.bf1.admin.tool.data.session

import android.content.Context
import com.bf1.admin.tool.data.local.SessionCacheDao
import com.bf1.admin.tool.data.local.entity.EncryptedAccount
import com.bf1.admin.tool.data.local.entity.SessionCacheEntity
import com.bf1.admin.tool.data.remote.CardToolApiService
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.data.remote.PersonaNotFoundException
import com.bf1.admin.tool.data.remote.RotatedCookies
import com.bf1.admin.tool.data.remote.mergeRotatedPersist
import com.bf1.admin.tool.data.repository.AccountRepository
import com.bf1.admin.tool.util.AccountCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 单一凭证生产者：所有「用 remid/sid 换新凭证」的兑换（EA 认证、session 续期、
 * Blaze authCode、查 PID）全部收口到本类。组件只消费成品（sessionId / authCode /
 * personaId），绝不自己发起兑换或落库轮换。
 *
 * 正确性模型：
 * - 按 accountId 一把互斥锁，锁只在单次兑换期间持有（秒级），绝不允许锁住
 *   卡服数小时的主循环 —— 卡服 run 在兑换瞬间才与后台 Worker 串行。
 * - 锁内兑换前重读 DB 最新凭证（不用锁外快照），保证兑换基于最新值。
 * - 轮换落库只在锁内发生，且为增量合并（[mergeRotatedPersist]：只覆盖确实
 *   轮换出的字段，没轮换不写库），消除 read-modify-write 互相覆盖。
 */
class CredentialManager(
    private val context: Context,
    private val accountRepository: AccountRepository,
    private val sessionCacheDao: SessionCacheDao,
    private val eaApi: EAApiService,
    private val cardToolApi: CardToolApiService,
    private val now: () -> Long = System::currentTimeMillis
) {
    // 同一账号的兑换串行化；不同账号互不阻塞。账号数量个位数，无需清理。
    private val accountLocks = ConcurrentHashMap<Long, Mutex>()

    // ═══════════════════════════════════════════════════
    // 锁辅助
    // ═══════════════════════════════════════════════════

    private fun lockFor(accountId: Long): Mutex = accountLocks.getOrPut(accountId) { Mutex() }

    private suspend fun <T> withAccountLock(accountId: Long, block: suspend () -> T): T =
        lockFor(accountId).withLock { block() }

    // ═══════════════════════════════════════════════════
    // 网关 sessionId（管理 + 卡服共用，缓存化）
    // ═══════════════════════════════════════════════════

    /**
     * 当前活跃账号的网关 sessionId。
     * 缓存命中（12h 有效期内 + remid 指纹一致）直接返回，否则锁内兑换并落库。
     */
    suspend fun getActiveSessionId(): String {
        val account = accountRepository.getActiveEncrypted()
            ?: throw IllegalStateException("请先登录 EA 账号")
        return withAccountLock(account.id) {
            // 锁内重读最新凭证，避免用锁外快照兑换
            val latest = accountRepository.getDecryptedById(account.id)
                ?: throw IllegalStateException("请先登录 EA 账号")
            cachedOrRefreshLocked(latest)
        }
    }

    /**
     * 后台定时刷新入口（[SessionRefreshWorker]）。
     * 距上次刷新不足 [SESSION_REFRESH_INTERVAL_MS] 时跳过，避免 WorkManager
     * 提前触发或用户刚手动刷过时白跑一轮 EA 兑换。
     */
    suspend fun refreshActiveSession(): Boolean {
        val account = accountRepository.getActiveEncrypted() ?: return false
        return withAccountLock(account.id) {
            val latest = accountRepository.getDecryptedById(account.id) ?: return@withAccountLock false
            val cached = sessionCacheDao.getByAccountId(account.id)
            if (cached != null &&
                cached.remidFingerprint == remidFingerprint(latest.remid) &&
                !isSessionRefreshDue(cached.refreshedAt, now())
            ) {
                return@withAccountLock true
            }
            refreshSessionIdLocked(latest)
            true
        }
    }

    /**
     * 业务调用入口：把 block 包进一个可用 sessionId 里执行。
     * 失败（session 失效类）时失效缓存重试一次；[EAApiService.CredentialsExpiredException]
     * 不重试（凭证过期需要用户重新登录）。block 在锁外执行，可长时间运行。
     */
    suspend fun <T> withActiveSession(block: suspend (String) -> T): T {
        try {
            return block(getActiveSessionId())
        } catch (error: Exception) {
            if (!isSessionFailure(error)) throw error
            invalidateActiveSession()
            return block(getActiveSessionId())
        }
    }

    /** 强制失效当前活跃账号的 session 缓存（卡服 -32501/-32504 后重取用）。 */
    suspend fun invalidateActiveSession() {
        val account = accountRepository.getActiveEncrypted() ?: return
        withAccountLock(account.id) {
            sessionCacheDao.deleteByAccountId(account.id)
        }
    }

    /** 首次登录成功后由登录页调用：把新换的 session 落库（账号须已建档）。 */
    suspend fun recordSession(accountId: Long, remid: String, sessionId: String) {
        withAccountLock(accountId) {
            saveSession(accountId, remid, sessionId)
        }
    }

    // ═══════════════════════════════════════════════════
    // EA 认证 / 兑换（单一生产者入口）
    // ═══════════════════════════════════════════════════

    /**
     * 完整认证流程（remid/sid → access_token → persona → auth_code → sessionId）。
     *
     * - [accountId] 为 null（首次登录）：只兑换不落库，返回 [EAApiService.SessionInfo]；
     *   账号尚未建档，由登录页用 `session.rotated.remid ?: remid` 建档后再 [recordSession]。
     * - [accountId] 非空（设置页保存凭证）：锁内「兑换 → 落库（轮换值 ?: 用户输入）
     *   → 记 session」原子完成，**先验证后保存** —— 验证失败不覆盖现有有效凭证。
     *
     * 锁内要调 suspend 落库，故手写 try/catch 返回 Result（runCatching 的 lambda 非 suspend）。
     */
    suspend fun authenticate(
        remid: String,
        sid: String,
        accountId: Long? = null
    ): Result<EAApiService.SessionInfo> {
        return try {
            val session = eaApi.authenticate(remid, sid).getOrThrow()
            if (accountId != null) {
                withAccountLock(accountId) {
                    val effectiveRemid = session.rotated.remid ?: remid
                    val effectiveSid = session.rotated.sid ?: sid
                    accountRepository.updateCredentials(accountId, effectiveRemid, effectiveSid)
                    saveSession(accountId, effectiveRemid, session.sessionId)
                }
            }
            Result.success(session)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 玩家名 → PID。
     *
     * 优先级：
     * 1. Juno refresh_token 静默换 access_token → gateway personas（无需 cookie）
     * 2. ORIGIN_JS_SDK cookie 换 access_token → 403 兜底 Juno cookie
     * 3. gametools 公共 API 兜底
     *
     * EA 原生查询产生的轮换 cookie 在锁内落库。
     */
    suspend fun resolvePlayerName(playerName: String): PlayerResolveResult {
        val account = accountRepository.getActiveEncrypted() ?: throw Exception("请先登录账号")
        return withAccountLock(account.id) {
            val latest = accountRepository.getDecryptedById(account.id) ?: throw Exception("请先登录账号")

            // 1) Juno refresh_token 静默路径（优先：完整 scope，无需 cookie）
            if (latest.junoRefreshToken != null) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val tokenResult = eaApi.refreshJunoAccessToken(latest.junoRefreshToken)
                        // refresh_token rotation：锁内落库新 refresh_token
                        accountRepository.saveJunoRefreshToken(latest.id, tokenResult.refreshToken)
                        eaApi.resolvePlayerNameByJunoToken(tokenResult.accessToken, playerName)
                    }
                    return@withAccountLock PlayerResolveResult(result.personaId, PlayerResolveSource.EA)
                } catch (e: PersonaNotFoundException) {
                    throw e
                } catch (e: EAApiService.CredentialsExpiredException) {
                    // refresh_token 本身过期，清除后降级到 cookie 路径
                    accountRepository.saveJunoRefreshToken(latest.id, null)
                    // fall through to cookie path
                } catch (_: Exception) {
                    // refresh_token 路径失败（网络/令牌失效）：不清除 refresh_token，
                    // 降级到 cookie 路径，下次 refresh_token 可能恢复
                }
            }

            // 2) ORIGIN cookie → Juno cookie 路径（现有逻辑）
            try {
                val result = withContext(Dispatchers.IO) {
                    eaApi.resolvePlayerNameByEAID(latest.remid, latest.sid, playerName)
                }
                persistRotated(latest.id, result.rotated)
                return@withAccountLock PlayerResolveResult(result.personaId, PlayerResolveSource.EA)
            } catch (e: PersonaNotFoundException) {
                throw e
            } catch (e: Exception) {
                try {
                    (e as? EAApiService.EaPidQueryException)?.let { persistRotated(latest.id, it.rotated) }
                } catch (_: Exception) {}
                // 3) gametools 兜底
                PlayerResolveResult(
                    withContext(Dispatchers.IO) { eaApi.resolvePlayerNameGametools(playerName) },
                    PlayerResolveSource.GAMETOOLS
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // Juno WebView 登录 + refresh_token 管理
    // ═══════════════════════════════════════════════════

    /** 生成 Juno PKCE 授权 URL（WebView 登录第一步）。 */
    fun buildJunoAuthUrl(): EAApiService.JunoAuthParams = eaApi.buildJunoAuthUrl()

    /**
     * 用 Juno authorization_code 换 access_token + refresh_token。
     * 不落库 —— 调用方拿到 tokenResult 后自行决定 refresh_token 落库时机。
     */
    fun exchangeJunoCode(code: String, codeVerifier: String): EAApiService.JunoTokenResult =
        eaApi.exchangeJunoCode(code, codeVerifier)

    /**
     * Juno WebView 登录专用认证：用 Juno access_token + remid/sid 换取
     * persona + sessionId。绕过 ORIGIN_JS_SDK（Juno cookie 对该 client 不可用）。
     */
    suspend fun authenticateWithJunoToken(
        accessToken: String,
        remid: String,
        sid: String,
        accountId: Long? = null
    ): Result<EAApiService.SessionInfo> {
        return try {
            val session = eaApi.authenticateWithJunoToken(accessToken, remid, sid).getOrThrow()
            if (accountId != null) {
                withAccountLock(accountId) {
                    val effectiveRemid = session.rotated.remid ?: remid
                    val effectiveSid = session.rotated.sid ?: sid
                    accountRepository.updateCredentials(accountId, effectiveRemid, effectiveSid)
                    saveSession(accountId, effectiveRemid, session.sessionId)
                }
            }
            Result.success(session)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 直接保存 Juno refresh_token 到账号（不回兑 code）。
     * [refreshToken] 来自 [exchangeJunoCode] 返回的 [EAApiService.JunoTokenResult.refreshToken]。
     */
    suspend fun saveJunoRefreshToken(accountId: Long, refreshToken: String) {
        withAccountLock(accountId) {
            accountRepository.saveJunoRefreshToken(accountId, refreshToken)
        }
    }

    /**
     * WebView 登录完成后调用：用 code 换 access_token + refresh_token，播种到账号。
     * [accountId] 来自 [AccountRepository.addOrUpdateAccount] 的返回值。
     */
    suspend fun onJunoLoginComplete(accountId: Long, code: String, codeVerifier: String) {
        withAccountLock(accountId) {
            val tokenResult = withContext(Dispatchers.IO) {
                eaApi.exchangeJunoCode(code, codeVerifier)
            }
            accountRepository.saveJunoRefreshToken(accountId, tokenResult.refreshToken)
        }
    }

    /**
     * 换取一次性 Blaze authCode（卡服 Blaze 登录用）。锁内兑换 + 轮换落库。
     * authCode 单次有效、不落库 —— 每次 Blaze 登录都必须现取。
     */
    suspend fun acquireBlazeAuthCode(): String {
        val account = accountRepository.getActiveEncrypted()
            ?: throw IllegalStateException("请先登录 EA 账号")
        return withAccountLock(account.id) {
            val latest = accountRepository.getDecryptedById(account.id)
                ?: throw IllegalStateException("请先登录 EA 账号")
            val result = cardToolApi.getBlazeAuthCode(latest.remid, latest.sid).getOrThrow()
            persistRotated(latest.id, result.rotated)
            result.authCode
        }
    }

    // ═══════════════════════════════════════════════════
    // 私有：兑换与落库（调用方必须在账号锁内）
    // ═══════════════════════════════════════════════════

    /**
     * 读路径只要求 session 仍在 [SESSION_MAX_AGE_MS] 有效期内。
     * 定期续期由 [refreshActiveSession] 的 6h 周期任务负责，读路径不该重复承担 ——
     * 否则任何超过 6h 的 session 都会在业务链路上强制阻塞一轮 3 次 EA 请求。
     * 即使后台任务被 Doze 压制导致 session 偏旧，[withActiveSession] 也会在失败时
     * 清缓存重兑换一次，可以自愈。
     */
    private suspend fun cachedOrRefreshLocked(account: EncryptedAccount): String {
        val cached = sessionCacheDao.getByAccountId(account.id)
        if (cached != null &&
            cached.remidFingerprint == remidFingerprint(account.remid) &&
            isSessionUsable(cached.refreshedAt, now())
        ) {
            try {
                return AccountCrypto.decrypt(cached.encryptedSessionId, context)
            } catch (_: Exception) {
                sessionCacheDao.deleteByAccountId(account.id)
            }
        }
        return refreshSessionIdLocked(account)
    }

    private suspend fun refreshSessionIdLocked(account: EncryptedAccount): String {
        val result = eaApi.refreshSessionId(account.remid, account.sid)
        // 轮换后的 remid 直接由调用返回，无需回读 DB 猜测。
        persistRotated(account.id, result.rotated)
        saveSession(account.id, result.rotated.remid ?: account.remid, result.sessionId)
        return result.sessionId
    }

    /** 增量落库：只覆盖本次确实轮换出的字段；没轮换不写库。调用方须在账号锁内。 */
    private suspend fun persistRotated(accountId: Long, rotated: RotatedCookies) {
        if (!rotated.hasAny) return
        val existing = accountRepository.getDecryptedById(accountId) ?: return
        val (newRemid, newSid) = mergeRotatedPersist(existing.remid, existing.sid, rotated)
        if (newRemid == existing.remid && newSid == existing.sid) return
        accountRepository.updateCredentials(accountId, newRemid, newSid)
    }

    /** 直接 upsert session 缓存，不额外加锁（调用方须在账号锁内，防 Mutex 不可重入死锁）。 */
    private suspend fun saveSession(accountId: Long, remid: String, sessionId: String) {
        sessionCacheDao.upsert(
            SessionCacheEntity(
                accountId = accountId,
                encryptedSessionId = AccountCrypto.encrypt(sessionId, context),
                remidFingerprint = remidFingerprint(remid),
                refreshedAt = now()
            )
        )
    }

    private fun isSessionFailure(error: Exception): Boolean {
        if (error is EAApiService.CredentialsExpiredException) return false
        val message = error.message?.lowercase() ?: return false
        return message.contains("session") || message.contains("auth") ||
            message.contains("401") || message.contains("403")
    }

    private fun remidFingerprint(remid: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(remid.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** 玩家名解析结果：来源标注（EA 原生 / gametools 兜底）。 */
enum class PlayerResolveSource { EA, GAMETOOLS }

data class PlayerResolveResult(val personaId: String, val source: PlayerResolveSource)
