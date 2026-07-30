package com.nekobot.app.data.local.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalLogger

/** 后台自动化统一执行入口。 */
class LocalAutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE) ?: return Result.failure()
        val targetId = inputData.getString(KEY_TARGET_ID) ?: return Result.failure()
        val profile = inputData.getString(KEY_PROFILE) ?: return Result.failure()

        if (!ServiceContainer.prefs.isLocalMode || ServiceContainer.prefs.activeDbName != profile) {
            LocalLogger.i(TAG, "忽略非当前本地 Profile 的自动化: $profile/$type/$targetId")
            return Result.success()
        }

        return try {
            val outcome = when (type) {
                LocalAutomationScheduler.TYPE_TASK ->
                    ServiceContainer.localRepository.executeScheduledTask(targetId)
                LocalAutomationScheduler.TYPE_WORKFLOW ->
                    ServiceContainer.localRepository.executeScheduledWorkflow(targetId)
                LocalAutomationScheduler.TYPE_PROACTIVE ->
                    ServiceContainer.localRepository.executeProactiveChat(targetId)
                else -> return Result.failure()
            }
            if (outcome.notify && outcome.content.isNotBlank()) {
                LocalAutomationNotifier.show(
                    context = applicationContext,
                    notificationId = (type + targetId).hashCode(),
                    title = outcome.title,
                    content = outcome.content,
                    sessionId = outcome.sessionId
                )
            }
            ServiceContainer.localRepository.onAutomationWorkerFinished(type, targetId)
            Result.success()
        } catch (error: Exception) {
            LocalLogger.e(TAG, "自动化执行失败: $type/$targetId: ${error.message}", error)
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                ServiceContainer.localRepository.onAutomationWorkerFinished(type, targetId)
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "LocalAutomationWorker"
        const val KEY_TYPE = "automation_type"
        const val KEY_TARGET_ID = "target_id"
        const val KEY_PROFILE = "profile_name"
    }
}
