package com.bf1.admin.tool

import android.app.Application
import com.bf1.admin.tool.data.local.AppDatabase

class BF1AdminApp : Application() {
    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
