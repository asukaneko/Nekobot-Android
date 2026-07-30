package com.nekobot.app.data.local

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.WebDavBackupRequest
import com.nekobot.app.data.repository.Resource
import java.util.concurrent.TimeUnit

class LocalWebDavSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!ServiceContainer.prefs.isLocalMode) return Result.success()
        return when (
            ServiceContainer.unified.webDavIncrementalSync(
                WebDavBackupRequest()
            )
        ) {
            is Resource.Success -> Result.success()
            is Resource.Error -> Result.retry()
            is Resource.Loading -> Result.retry()
        }
    }
}

object LocalWebDavSyncScheduler {
    private const val UNIQUE_WORK = "local_webdav_incremental_sync"

    fun configure(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            manager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<LocalWebDavSyncWorker>(
            6,
            TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        manager.enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
