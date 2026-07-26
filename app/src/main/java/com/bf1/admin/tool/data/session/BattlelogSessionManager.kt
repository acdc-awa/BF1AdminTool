package com.bf1.admin.tool.data.session

import android.content.Context
import com.bf1.admin.tool.data.local.SessionCacheDao
import com.bf1.admin.tool.data.local.entity.EncryptedAccount
import com.bf1.admin.tool.data.local.entity.SessionCacheEntity
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.data.repository.AccountRepository
import com.bf1.admin.tool.data.repository.AdminRepository
import com.bf1.admin.tool.util.AccountCrypto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

class BattlelogSessionManager(
    private val context: Context,
    private val accountRepository: AccountRepository,
    private val sessionCacheDao: SessionCacheDao,
    private val adminRepository: AdminRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val refreshMutex = Mutex()

    suspend fun getActiveSessionId(): String {
        val account = accountRepository.getActiveEncrypted()
            ?: throw IllegalStateException("请先登录 EA 账号")
        return getSessionId(account)
    }

    /**
     * 后台定时刷新入口（[SessionRefreshWorker]）。
     * 距上次刷新不足 [SESSION_REFRESH_INTERVAL_MS] 时跳过，避免 WorkManager
     * 提前触发或用户刚手动刷过时白跑一轮 EA 兑换。
     */
    suspend fun refreshActiveSession(): Boolean = refreshMutex.withLock {
        val account = accountRepository.getActiveEncrypted() ?: return@withLock false
        val cached = sessionCacheDao.getByAccountId(account.id)
        if (cached != null &&
            cached.remidFingerprint == remidFingerprint(account.remid) &&
            !isSessionRefreshDue(cached.refreshedAt, now())
        ) {
            return@withLock true
        }
        refreshSessionIdLocked(account)
        true
    }

    suspend fun recordSession(accountId: Long, remid: String, sessionId: String) {
        refreshMutex.withLock {
            saveSession(accountId, remid, sessionId)
        }
    }

    suspend fun invalidateActiveSession() {
        refreshMutex.withLock {
            accountRepository.getActiveEncrypted()?.let { sessionCacheDao.deleteByAccountId(it.id) }
        }
    }

    suspend fun <T> withActiveSession(block: suspend (String) -> T): T {
        try {
            return block(getActiveSessionId())
        } catch (error: Exception) {
            if (!isSessionFailure(error)) throw error
            invalidateActiveSession()
            return block(getActiveSessionId())
        }
    }

    /**
     * 读路径只要求 session 仍在 [SESSION_MAX_AGE_MS] 有效期内。
     *
     * 定期续期由 [SessionRefreshScheduler] 的 6h 周期任务负责，读路径不该重复承担 ——
     * 否则任何超过 6h 的 session 都会在 UI 线程链路上强制阻塞一轮 3 次 EA 请求。
     * 即使后台任务被 Doze 压制导致 session 偏旧，[withActiveSession] 也会在失败时
     * 清缓存重兑换一次，可以自愈。
     */
    private suspend fun getSessionId(account: EncryptedAccount): String = refreshMutex.withLock {
        val cached = sessionCacheDao.getByAccountId(account.id)
        if (cached != null &&
            cached.remidFingerprint == remidFingerprint(account.remid) &&
            isSessionUsable(cached.refreshedAt, now())
        ) {
            try {
                return@withLock AccountCrypto.decrypt(cached.encryptedSessionId, context)
            } catch (_: Exception) {
                sessionCacheDao.deleteByAccountId(account.id)
            }
        }
        refreshSessionIdLocked(account)
    }

    private suspend fun refreshSessionIdLocked(account: EncryptedAccount): String {
        val result = adminRepository.refreshSessionId(account.id, account.remid, account.sid)
        // 轮换后的 remid 直接由调用返回，无需回读 DB 猜测。
        saveSession(account.id, result.rotated.remid ?: account.remid, result.sessionId)
        return result.sessionId
    }

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
