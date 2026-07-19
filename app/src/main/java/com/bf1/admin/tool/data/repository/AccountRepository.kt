package com.bf1.admin.tool.data.repository

import android.content.Context
import com.bf1.admin.tool.data.local.AccountDao
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.EncryptedAccount
import com.bf1.admin.tool.util.AccountCrypto
import kotlinx.coroutines.flow.Flow

class AccountRepository(private val accountDao: AccountDao, private val context: Context) {
    val allAccounts: Flow<List<AccountEntity>> = accountDao.getAll()

    suspend fun getActive(): AccountEntity? = accountDao.getActive()

    suspend fun getActiveEncrypted(): EncryptedAccount? {
        return accountDao.getActive()?.toDecrypted()
    }

    suspend fun addOrUpdateAccount(name: String, personaId: String, remid: String, sid: String): Long {
        val encRemid = AccountCrypto.encrypt(remid, context)
        val encSid = AccountCrypto.encrypt(sid, context)
        val existing = accountDao.getByPersonaId(personaId)
        return if (existing != null) {
            accountDao.update(existing.copy(name = name, remid = encRemid, sid = encSid))
            existing.id
        } else {
            accountDao.insert(
                AccountEntity(name = name, personaId = personaId, remid = encRemid, sid = encSid)
            )
        }
    }

    suspend fun switchActive(id: Long) = accountDao.switchActive(id)

    suspend fun delete(account: AccountEntity) = accountDao.delete(account)

    suspend fun getDecryptedById(id: Long): EncryptedAccount? {
        return accountDao.getById(id)?.toDecrypted()
    }

    suspend fun updateCredentials(id: Long, remid: String, sid: String) {
        val existing = accountDao.getById(id) ?: return
        val encRemid = AccountCrypto.encrypt(remid, context)
        val encSid = AccountCrypto.encrypt(sid, context)
        accountDao.update(existing.copy(remid = encRemid, sid = encSid))
    }

    private fun AccountEntity.toDecrypted(): EncryptedAccount {
        return EncryptedAccount(
            id = id,
            name = name,
            personaId = personaId,
            remid = AccountCrypto.decrypt(remid, context),
            sid = AccountCrypto.decrypt(sid, context),
            isActive = isActive
        )
    }
}
