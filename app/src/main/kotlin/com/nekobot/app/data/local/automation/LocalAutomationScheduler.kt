package com.nekobot.app.data.local.automation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nekobot.app.data.local.db.LocalTaskEntity
import com.nekobot.app.data.local.db.LocalWorkflowEntity
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** WorkManager 调度外壳。业务执行与数据库状态更新仍由 LocalRepository 负责。 */
class LocalAutomationScheduler(
    context: Context,
    private val profileName: String
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val profileKey = profileName.hashCode().toUInt().toString(16)
    private val profileTag = "local_automation_profile_$profileKey"

    fun scheduleTask(
        task: LocalTaskEntity,
        preferredDueAt: Instant? = null,
        replaceExisting: Boolean = true,
        appendAfterCurrent: Boolean = false,
        now: Instant = Instant.now()
    ): Instant? {
        val uniqueName = uniqueName(TYPE_TASK, task.id)
        if (!task.enabled) {
            workManager.cancelUniqueWork(uniqueName)
            return null
        }
        val next = preferredDueAt ?: LocalScheduleCalculator.nextRun(task.trigger, task.configJson)
        enqueue(uniqueName, TYPE_TASK, task.id, next, now, replaceExisting, appendAfterCurrent)
        return next
    }

    fun scheduleWorkflow(
        workflow: LocalWorkflowEntity,
        preferredDueAt: Instant? = null,
        replaceExisting: Boolean = true,
        appendAfterCurrent: Boolean = false,
        now: Instant = Instant.now()
    ): Instant? {
        val uniqueName = uniqueName(TYPE_WORKFLOW, workflow.id)
        if (!workflow.enabled || !workflow.trigger.equals("cron", ignoreCase = true)) {
            workManager.cancelUniqueWork(uniqueName)
            return null
        }
        val next = preferredDueAt ?: LocalScheduleCalculator.nextRun(workflow.trigger, workflow.configJson)
        enqueue(uniqueName, TYPE_WORKFLOW, workflow.id, next, now, replaceExisting, appendAfterCurrent)
        return next
    }

    fun scheduleProactive(
        sessionId: String,
        dueAt: Instant?,
        replaceExisting: Boolean = true,
        appendAfterCurrent: Boolean = false,
        now: Instant = Instant.now()
    ) {
        val uniqueName = uniqueName(TYPE_PROACTIVE, sessionId)
        if (dueAt == null) {
            workManager.cancelUniqueWork(uniqueName)
            return
        }
        enqueue(
            uniqueName,
            TYPE_PROACTIVE,
            sessionId,
            dueAt,
            now,
            replaceExisting,
            appendAfterCurrent
        )
    }

    fun cancelTask(id: String) = workManager.cancelUniqueWork(uniqueName(TYPE_TASK, id))

    fun cancelWorkflow(id: String) = workManager.cancelUniqueWork(uniqueName(TYPE_WORKFLOW, id))

    fun cancelProactive(sessionId: String) =
        workManager.cancelUniqueWork(uniqueName(TYPE_PROACTIVE, sessionId))

    fun cancelProfile() = workManager.cancelAllWorkByTag(profileTag)

    private fun enqueue(
        uniqueName: String,
        type: String,
        targetId: String,
        dueAt: Instant?,
        now: Instant,
        replaceExisting: Boolean,
        appendAfterCurrent: Boolean
    ) {
        if (dueAt == null) {
            workManager.cancelUniqueWork(uniqueName)
            return
        }
        val delayMillis = Duration.between(now, dueAt).toMillis().coerceAtLeast(0L)
        val data = Data.Builder()
            .putString(LocalAutomationWorker.KEY_TYPE, type)
            .putString(LocalAutomationWorker.KEY_TARGET_ID, targetId)
            .putString(LocalAutomationWorker.KEY_PROFILE, profileName)
            .build()
        val request = OneTimeWorkRequestBuilder<LocalAutomationWorker>()
            .setInputData(data)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(profileTag)
            .build()
        workManager.enqueueUniqueWork(
            uniqueName,
            when {
                appendAfterCurrent -> ExistingWorkPolicy.APPEND_OR_REPLACE
                replaceExisting -> ExistingWorkPolicy.REPLACE
                else -> ExistingWorkPolicy.KEEP
            },
            request
        )
    }

    private fun uniqueName(type: String, id: String): String =
        "local_automation_${profileKey}_${type}_${id.hashCode().toUInt().toString(16)}"

    companion object {
        const val TYPE_TASK = "task"
        const val TYPE_WORKFLOW = "workflow"
        const val TYPE_PROACTIVE = "proactive"
    }
}
