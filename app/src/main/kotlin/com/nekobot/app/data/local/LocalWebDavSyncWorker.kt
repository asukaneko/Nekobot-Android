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
        val config = when (val result = ServiceContainer.unified.getWebDavConfig()) {
            is Resource.Success -> result.data
            is Resource.Error, is Resource.Loading -> return Result.retry()
        }
        if (config.enabled != true || config.autoIncrementalSyncEnabled != true) {
            return Result.success()
        }
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
    private const val MIN_INTERVAL_HOURS = 1L
    private const val MAX_INTERVAL_HOURS = 24L * 7L

    fun configure(
        context: Context,
        webDavEnabled: Boolean,
        autoIncrementalSyncEnabled: Boolean,
        intervalHours: Int
    ) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!webDavEnabled || !autoIncrementalSyncEnabled) {
            manager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<LocalWebDavSyncWorker>(
            intervalHours.toLong().coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS),
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
