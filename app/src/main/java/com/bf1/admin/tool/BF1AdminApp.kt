package com.bf1.admin.tool

import android.app.Application
import com.bf1.admin.tool.data.local.AppDatabase
import com.bf1.admin.tool.data.remote.CardToolApiService
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.data.repository.AccountRepository
import com.bf1.admin.tool.data.repository.AdminRepository
import com.bf1.admin.tool.data.session.CredentialManager
import com.bf1.admin.tool.data.session.SessionRefreshScheduler

class BF1AdminApp : Application() {
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val accountRepository: AccountRepository by lazy {
        AccountRepository(database.accountDao(), this)
    }

    /** 共享网络层实例：单一 okhttp 连接池；所有兑换经同一组底层 API。 */
    val eaApiService: EAApiService by lazy { EAApiService() }

    val cardToolApiService: CardToolApiService by lazy { CardToolApiService() }

    val adminRepository: AdminRepository by lazy {
        AdminRepository(eaApiService)
    }

    /** 单一凭证生产者：所有 remid/sid 兑换与轮换落库的唯一入口。 */
    val credentialManager: CredentialManager by lazy {
        CredentialManager(
            context = this,
            accountRepository = accountRepository,
            sessionCacheDao = database.sessionCacheDao(),
            eaApi = eaApiService,
            cardToolApi = cardToolApiService
        )
    }

    override fun onCreate() {
        super.onCreate()
        SessionRefreshScheduler.schedule(this)
    }
}
