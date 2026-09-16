package com.nekobot.app.data.local.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.service.AgentForegroundService

/**
 * 后台自动化统一执行入口。
 *
 * 真正后台执行的关键点（与主动聊天对齐）：
 * 1. 执行期间占用前台服务槽位 —— WorkManager 在应用处于后台/进程刚从冷启动恢复时拉起本 Worker，
 *    AI 生成长时间运行会被系统按后台优先级杀掉，必须抬到前台服务 + PARTIAL_WAKE_LOCK 才稳。
 * 2. 结果语义区分 —— “跳过”（未启用、未到点、配置不合法）算成功并照常续排，
 *    只有真实执行异常才重试，避免跳过被当成失败而中断链路。
 */
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

        // 抬到前台服务执行，避免长耗时 AI 生成被系统回收；finally 中必定释放。
        AgentForegroundService.acquireAutomation(applicationContext)
        return try {
            val outcome = when (type) {
                LocalAutomationScheduler.TYPE_TASK ->
                    ServiceContainer.localRepository.executeScheduledTask(targetId)
                LocalAutomationScheduler.TYPE_WORKFLOW ->
                    ServiceContainer.localRepository.executeScheduledWorkflow(targetId)
                LocalAutomationScheduler.TYPE_PROACTIVE ->
                    ServiceContainer.localRepository.executeProactiveChat(targetId)
                LocalAutomationScheduler.TYPE_LIFE_SIM ->
                    ServiceContainer.localRepository.executeLifeSim(targetId)
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
            // 无论本次是真正执行还是“未到点/已禁用”的跳过，都要续排下一次，定时链路才不会断。
            ServiceContainer.localRepository.onAutomationWorkerFinished(type, targetId)
            Result.success()
        } catch (error: Exception) {
            LocalLogger.e(TAG, "自动化执行失败: $type/$targetId: ${error.message}", error)
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                // 重试耗尽：记录失败状态并续排下一次，不让任务从此静默消失。
                ServiceContainer.localRepository.onAutomationFailed(type, targetId, error.message)
                ServiceContainer.localRepository.onAutomationWorkerFinished(type, targetId)
                Result.failure()
            }
        } finally {
            AgentForegroundService.releaseAutomation(applicationContext)
        }
    }

    companion object {
        private const val TAG = "LocalAutomationWorker"
        const val KEY_TYPE = "automation_type"
        const val KEY_TARGET_ID = "target_id"
        const val KEY_PROFILE = "profile_name"
        private const val MAX_RETRY_ATTEMPTS = 2
    }
}
