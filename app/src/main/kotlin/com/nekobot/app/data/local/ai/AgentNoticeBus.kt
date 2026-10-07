package com.nekobot.app.data.local.ai

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import com.nekobot.app.R

/**
 * 会话级“Agent 运行通知”队列。
 *
 * 后台子代理完成任务时，父会话可能仍在工具循环中，也可能已经空闲。两种情况都要让模型
 * 有机会知道任务已经结束（否则模型只能靠 subagent_get 轮询，或者干脆遗忘）：
 *
 * - 仍在循环中：通知在下一轮模型调用前作为消息注入（复用排队消息通道，即"插队"）；
 * - 已经空闲：通过 [onNoticePublished] 通知 LocalRepository 自动「唤醒」会话——
 *   开一轮新的 Agent 运行处理通知并向用户汇报结果（对齐 DeepSeek harness 的
 *   后台任务完成唤醒语义；可在设置中关闭）。
 *
 * 内存队列只负责当前进程分发；子代理完成事件另写 Room outbox，进程重启后可补投。
 * 任务结果本身持久化在 [SubagentTaskStore]，也可用 subagent_get 查询。
 */
internal object AgentNoticeBus {

    /** 单个会话最多保留的通知条数，避免长期空闲的会话无上限堆积。 */
    internal const val MAX_NOTICES_PER_SESSION = 20

    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<SubagentNoticeEnvelope>>()

    /**
     * 通知到达监听器：由 LocalRepository 注册。
     *
     * 会话空闲时收到通知意味着没有任何运行中的工具循环去消费它——监听器借此触发
     * 「唤醒」：为空闲会话自动开一轮新的 Agent 运行处理通知（对齐 DeepSeek harness 的
     * 后台任务完成唤醒语义）。会话仍在运行时监听器应直接返回，通知会由循环内注入
     * 通道（[com.nekobot.app.data.local.ai.LocalPipelineCallbacks.drainPendingUserMessages]）
     * 在下一轮模型调用前插队送达。
     */
    @Volatile
    internal var onNoticePublished: ((sessionId: String) -> Unit)? = null

    /** 发布一条通知；超过上限时丢弃最旧的通知。 */
    fun publish(sessionId: String, notice: String, wakeIfIdle: Boolean = true) {
        if (sessionId.isBlank() || notice.isBlank()) return
        enqueue(sessionId, SubagentNoticeEnvelope(eventId = null, content = notice))
        if (wakeIfIdle) wake(sessionId)
    }

    internal fun publishPersisted(
        sessionId: String,
        eventId: String,
        notice: String,
        wakeIfIdle: Boolean = true
    ) {
        if (sessionId.isBlank() || eventId.isBlank() || notice.isBlank()) return
        enqueue(sessionId, SubagentNoticeEnvelope(eventId, notice))
        if (wakeIfIdle) wake(sessionId)
    }

    private fun enqueue(sessionId: String, notice: SubagentNoticeEnvelope) {
        val queue = queues.computeIfAbsent(sessionId) { ConcurrentLinkedQueue() }
        val queuedNotice = notice.copy(sessionId = sessionId)
        if (queuedNotice.eventId != null && queue.any { it.eventId == queuedNotice.eventId }) return
        queue.add(queuedNotice)
        while (queue.size > MAX_NOTICES_PER_SESSION) {
            queue.poll()
        }
    }

    private fun wake(sessionId: String) {
        runCatching { onNoticePublished?.invoke(sessionId) }.onFailure { error ->
            com.nekobot.app.data.local.LocalLogger.w("AgentNoticeBus", R.string.log_notice_wake_callback_failed, error.message)
        }
    }

    /** 某会话是否还有未被消费的通知。 */
    fun hasPending(sessionId: String): Boolean =
        queues[sessionId]?.isNotEmpty() == true

    /** 取出并清空某会话的全部通知（取出即消费）。 */
    fun drain(sessionId: String): List<String> {
        val records = drainForWake(sessionId)
        acknowledge(records)
        return records.map { it.content }
    }

    /** 唤醒运行需要等模型回合成功后才确认，失败时可原样放回 outbox 队列。 */
    internal fun drainForWake(sessionId: String): List<SubagentNoticeEnvelope> {
        val queue = queues[sessionId] ?: return emptyList()
        val drained = mutableListOf<SubagentNoticeEnvelope>()
        while (true) {
            val item = queue.poll() ?: break
            drained.add(item)
        }
        return drained
    }

    internal fun acknowledge(notices: Collection<SubagentNoticeEnvelope>, runId: String? = null) {
        notices.mapNotNull { it.eventId }.distinct().forEach { SubagentTaskStore.markNoticeDelivered(it, runId) }
    }

    internal fun requeue(notices: Collection<SubagentNoticeEnvelope>, wakeIfIdle: Boolean = false) {
        notices.forEach { notice ->
            val sessionId = notice.sessionId ?: return@forEach
            enqueue(sessionId, notice)
        }
        if (wakeIfIdle) notices.mapNotNull { it.sessionId }.distinct().forEach(::wake)
    }

    /** 仓库重建后唤醒已从 Room 重放的 pending outbox。 */
    internal fun wakePending() {
        queues.filterValues { it.isNotEmpty() }.keys.forEach(::wake)
    }

    internal fun clearAll() {
        queues.clear()
    }

    /** 清空某会话的通知（会话删除/测试用）。 */
    fun clear(sessionId: String) {
        queues.remove(sessionId)
    }
}

internal data class SubagentNoticeEnvelope(
    val eventId: String?,
    val content: String,
    val sessionId: String? = null
)

/**
 * 把一批后台任务通知组装成唤醒运行的「用户消息」。
 *
 * 唤醒运行与会话内插队注入不同：它没有正在进行的父任务上下文，因此把通知持久化为
 * 一条真正的用户消息（带说明头部），让模型在全新的运行里明确知道自己被唤醒的原因，
 * 并据此向用户汇报结果。后续轮次的上下文里也能看到这条通知。
 */
internal fun buildAgentWakeUpMessage(notices: List<String>): String {
    val body = notices.filter { it.isNotBlank() }.joinToString(separator = "\n\n") { it.trim() }
    if (body.isBlank()) return ""
    return buildString {
        appendLine("[后台任务通知 · 自动唤醒]")
        appendLine(body)
        append(
            "（本条消息由系统在会话空闲时自动发送：后台任务的通知到达且没有正在进行的任务。" +
                "请基于上述通知向用户简明汇报任务结果；如任务失败请说明原因。" +
                "不要重复执行或重复委派该任务。）"
        )
    }.trim()
}

/**
 * 后台子代理并发闸门。
 *
 * 后台任务在独立协程里运行、不随父会话生成结束而结束；没有上限时模型一次委派十几条
 * 后台任务会把模型与网络配额同时打满，用户也看不到进度。超限时直接拒绝并给出可执行的
 * 建议（等已有任务完成，或改用前台执行）。
 */
internal object SubagentConcurrency {

    /** 用户未自定义时的默认上限，与 PrefsManager 的默认值保持一致。 */
    private const val DEFAULT_MAX_BACKGROUND_RUNS = 3

    /** 当前正在运行的后台子代理数量。 */
    private val running = java.util.concurrent.atomic.AtomicInteger(0)

    /** 当前允许的最大后台子代理数（Agent 设置中可调，1-10），实时读取偏好。 */
    val maxBackgroundRuns: Int
        get() = runCatching { com.nekobot.app.ServiceContainer.prefs.subagentMaxBackgroundRuns }
            .getOrDefault(DEFAULT_MAX_BACKGROUND_RUNS)

    fun tryAcquire(): Boolean = synchronized(this) {
        if (running.get() >= maxBackgroundRuns) {
            false
        } else {
            running.incrementAndGet()
            true
        }
    }

    fun release() {
        running.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    internal fun availablePermits(): Int = (maxBackgroundRuns - running.get()).coerceAtLeast(0)
}

/** 工具上下文里传递“当前子代理任务 id”的键，用于按任务树计算嵌套深度。 */
internal const val SUBAGENT_TASK_CONTEXT_KEY = "_subagent_task_id"
internal const val AGENT_TOOL_CALL_ID_CONTEXT_KEY = "_agent_tool_call_id"

/**
 * 后台子代理运行句柄表。
 *
 * 后台子代理是独立协程，光有任务记录（[SubagentTaskStore]）无法终止它；
 * 这里登记 taskId → Job，供 `subagent_pause` / `subagent_kill` 精确取消单个任务。
 */
internal object SubagentRunRegistry {

    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun register(taskId: String, job: kotlinx.coroutines.Job) {
        jobs[taskId] = job
    }

    fun unregister(taskId: String) {
        jobs.remove(taskId)
    }

    fun isRunning(taskId: String): Boolean = jobs[taskId]?.isActive == true

    /** @return 是否确实取消了正在运行的任务。 */
    fun cancel(taskId: String): Boolean {
        val job = jobs.remove(taskId) ?: return false
        job.cancel()
        return true
    }

    /** 暂停任务时等待其 finally 完成，确保并发额度和后台服务槽位已释放。 */
    suspend fun cancelAndJoin(taskId: String): Boolean {
        val job = jobs.remove(taskId) ?: return false
        job.cancel()
        job.join()
        return true
    }

    /** 仅取消指定档案/会话中的任务句柄。 */
    fun cancelAndJoin(taskIds: Set<String>) {
        if (taskIds.isEmpty()) return
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val active = taskIds.mapNotNull(jobs::get)
            active.forEach { it.cancel() }
            active.forEach { it.join() }
        }
        taskIds.forEach(jobs::remove)
    }
}

/**
 * 子代理任务级通知队列（进程内分发缓存）。
 *
 * 嵌套场景下，后台子代理自己也可以委派后台子任务。子任务完成时，若它的父任务
 * （也是子代理）仍在运行，通知投递到这里，由父任务工具循环在下一轮模型调用前
 * 注入上下文（与主会话的 [AgentNoticeBus] 插队通道同语义，只是按任务 id 隔离）。
 * 父任务已结束的通知由 [forwardUndeliveredSubagentTaskNotices] 向上转发或回落到会话级。
 */
internal object SubagentTaskNoticeBus {

    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<SubagentNoticeEnvelope>>()
    private val inFlight = ConcurrentHashMap<String, ConcurrentLinkedQueue<SubagentNoticeEnvelope>>()

    /** 发布一条发给指定子代理任务的通知。 */
    fun publish(taskId: String, notice: String) {
        if (taskId.isBlank() || notice.isBlank()) return
        enqueue(taskId, SubagentNoticeEnvelope(eventId = null, content = notice))
    }

    internal fun publishPersisted(taskId: String, eventId: String, notice: String) {
        if (taskId.isBlank() || eventId.isBlank() || notice.isBlank()) return
        enqueue(taskId, SubagentNoticeEnvelope(eventId = eventId, content = notice))
    }

    private fun enqueue(taskId: String, notice: SubagentNoticeEnvelope) {
        val queue = queues.computeIfAbsent(taskId) { ConcurrentLinkedQueue() }
        if (notice.eventId != null && queue.any { it.eventId == notice.eventId }) return
        queue.add(notice)
    }

    fun hasPending(taskId: String): Boolean = queues[taskId]?.isNotEmpty() == true

    /** 取出并清空某任务的通知（父任务循环每轮模型调用前消费）。 */
    fun drain(taskId: String): List<String> {
        val queue = queues[taskId] ?: return emptyList()
        val records = mutableListOf<SubagentNoticeEnvelope>()
        while (true) {
            val item = queue.poll() ?: break
            records.add(item)
        }
        if (records.isNotEmpty()) {
            inFlight.computeIfAbsent(taskId) { ConcurrentLinkedQueue() }.addAll(records)
        }
        return records.map { it.content }
    }

    /** 父任务已成功保存并完成一轮处理后确认通知。 */
    fun acknowledgeConsumed(taskId: String) {
        inFlight.remove(taskId)?.mapNotNull { it.eventId }?.distinct()
            ?.forEach { SubagentTaskStore.markNoticeDelivered(it, taskId) }
    }

    /** 父任务结束时将尚未确认的通知连同队列中未取出的通知一起向上转发。 */
    internal fun drainRecords(taskId: String): List<SubagentNoticeEnvelope> {
        val drained = mutableListOf<SubagentNoticeEnvelope>()
        listOfNotNull(queues.remove(taskId), inFlight.remove(taskId)).forEach { queue ->
            while (true) {
                val item = queue.poll() ?: break
                drained.add(item)
            }
        }
        return drained
    }

    /** 清理一批任务的通知（任务记录被清理时调用）。 */
    fun clearTasks(taskIds: Collection<String>) {
        if (taskIds.isEmpty()) return
        taskIds.forEach {
            queues.remove(it)
            inFlight.remove(it)
        }
    }

    fun clearAll() {
        queues.clear()
        inFlight.clear()
    }
}

/**
 * 组装子代理任务完成通知文本。
 *
 * @param addressedToParentTask true=发给仍在运行的父任务（也是子代理）；
 *   false=发给主会话（循环内注入或唤醒空闲会话）。
 */
internal fun buildSubagentCompletionNotice(task: SubagentTask, addressedToParentTask: Boolean): String {
    val latest = SubagentTaskStore.get(task.id) ?: task
    val status = latest.status.name.lowercase()
    val body = if (latest.status == SubagentTaskStatus.SUCCEEDED) {
        val summary = latest.result.trim().take(2_000)
        if (summary.isBlank()) "任务已完成，但没有返回内容。" else "结果摘要：\n$summary"
    } else {
        "失败原因：${latest.error?.take(500)?.takeIf { it.isNotBlank() } ?: "未知错误"}"
    }
    val headline = if (addressedToParentTask) {
        "[系统通知] 你委派的后台子任务已结束：${latest.description}（task_id=${latest.id}，状态=$status）"
    } else {
        val hierarchyNote = if (latest.parentTaskId != null) {
            // 嵌套孤儿：父任务已结束，结果由主会话接手处理。
            "。注意：该任务由子代理（task_id=${latest.parentTaskId}）委派，其父任务已结束，结果由你接手处理"
        } else {
            ""
        }
        "[系统通知] 后台子代理任务已结束：${latest.description}（task_id=${latest.id}，状态=$status）$hierarchyNote"
    }
    return "$headline\n$body\n" +
        "如需完整结果可用 subagent_get(task_id=${latest.id}) 读取，不要重复委派同一任务。"
}

/**
 * 子代理任务结束后的通知路由（前台/后台、嵌套层级共用）。
 *
 * - 父任务存在且仍在运行：投递到父任务的通知队列，由父任务工具循环插队消费；
 * - 父任务不存在或已结束：发布到会话级 [AgentNoticeBus]——主会话运行中则下一轮
 *   注入，空闲则触发唤醒（对齐 DeepSeek harness 的后台任务完成唤醒语义）。
 */
internal fun routeSubagentCompletionNotice(sessionId: String, task: SubagentTask) {
    val parent = task.parentTaskId?.let { SubagentTaskStore.get(it) }
    val parentTaskId = parent?.id?.takeIf { parent.isActive }
    SubagentTaskStore.publishCompletionNotice(task.copy(sessionId = sessionId), parentTaskId)
}

/**
 * 任务进入终态后，把它仍未消费的子任务完成通知向上转发。
 *
 * 竞态兜底：子任务完成时父任务还在运行，但父任务可能在消费前就结束了。此时这些
 * 通知不能再留在死任务的队列里，按同样的规则继续向上（父任务活跃→父任务队列；
 * 否则→会话级），保证嵌套结果最终总能到达主会话。
 */
internal fun forwardUndeliveredSubagentTaskNotices(task: SubagentTask) {
    val leftovers = SubagentTaskNoticeBus.drainRecords(task.id)
    if (leftovers.isEmpty()) return
    val parent = task.parentTaskId?.let { SubagentTaskStore.get(it) }
    val parentTaskId = parent?.id?.takeIf { parent.isActive }
    leftovers.forEach { notice ->
        if (notice.eventId != null) {
            SubagentTaskStore.retargetPendingNotice(notice.eventId, parentTaskId)
            if (parentTaskId != null) {
                SubagentTaskNoticeBus.publishPersisted(parentTaskId, notice.eventId, notice.content)
            } else {
                AgentNoticeBus.publishPersisted(task.sessionId, notice.eventId, notice.content)
            }
        } else if (parentTaskId != null) {
            SubagentTaskNoticeBus.publish(parentTaskId, notice.content)
        } else {
            AgentNoticeBus.publish(task.sessionId, notice.content)
        }
    }
}
