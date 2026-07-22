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

    suspend fun refreshActiveSession(): Boolean {
        val account = accountRepository.getActiveEncrypted() ?: return false
        refreshSessionId(account)
        return true
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

    private suspend fun getSessionId(account: EncryptedAccount): String = refreshMutex.withLock {
        val cached = sessionCacheDao.getByAccountId(account.id)
        if (cached != null &&
            cached.remidFingerprint == remidFingerprint(account.remid) &&
            isSessionUsable(cached.refreshedAt, now()) &&
            !isSessionRefreshDue(cached.refreshedAt, now())
        ) {
            try {
                return@withLock AccountCrypto.decrypt(cached.encryptedSessionId, context)
            } catch (_: Exception) {
                sessionCacheDao.deleteByAccountId(account.id)
            }
        }
        refreshSessionIdLocked(account)
    }

    private suspend fun refreshSessionId(account: EncryptedAccount): String = refreshMutex.withLock {
        refreshSessionIdLocked(account)
    }

    private suspend fun refreshSessionIdLocked(account: EncryptedAccount): String {
        val sessionId = adminRepository.refreshSessionId(account.id, account.remid, account.sid)
        val refreshedAccount = accountRepository.getDecryptedById(account.id) ?: account
        saveSession(account.id, refreshedAccount.remid, sessionId)
        return sessionId
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
