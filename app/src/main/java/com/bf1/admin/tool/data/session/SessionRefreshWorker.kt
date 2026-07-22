package com.bf1.admin.tool.data.session

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.data.remote.EAApiService
import java.io.IOException

class SessionRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        (applicationContext as BF1AdminApp).sessionManager.refreshActiveSession()
        Result.success()
    } catch (_: EAApiService.CredentialsExpiredException) {
        Result.success()
    } catch (_: IOException) {
        Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }
}
