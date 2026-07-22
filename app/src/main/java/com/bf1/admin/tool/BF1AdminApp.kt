package com.bf1.admin.tool

import android.app.Application
import com.bf1.admin.tool.data.local.AppDatabase
import com.bf1.admin.tool.data.repository.AccountRepository
import com.bf1.admin.tool.data.repository.AdminRepository
import com.bf1.admin.tool.data.session.BattlelogSessionManager
import com.bf1.admin.tool.data.session.SessionRefreshScheduler

class BF1AdminApp : Application() {
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val accountRepository: AccountRepository by lazy {
        AccountRepository(database.accountDao(), this)
    }

    val adminRepository: AdminRepository by lazy {
        AdminRepository(accountRepository)
    }

    val sessionManager: BattlelogSessionManager by lazy {
        BattlelogSessionManager(
            context = this,
            accountRepository = accountRepository,
            sessionCacheDao = database.sessionCacheDao(),
            adminRepository = adminRepository
        )
    }

    override fun onCreate() {
        super.onCreate()
        SessionRefreshScheduler.schedule(this)
    }
}
