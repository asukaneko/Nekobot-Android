package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.db.LocalSubagentTaskDao
import com.nekobot.app.data.local.db.LocalSubagentMessageEntity
import com.nekobot.app.data.local.db.LocalSubagentTaskEntity
import com.nekobot.app.data.local.db.LocalSubagentToolCallEntity
import com.nekobot.app.data.local.db.LocalAgentNoticeEntity
import com.nekobot.app.data.model.AgentTodo
import com.nekobot.app.data.model.ThinkingStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

/**
 * 子代理任务状态。
 *
 * [RUNNING] 与 [OUTPUTTING] 都是非终态：
 * - [RUNNING]：正在思考或执行工具调用，剩余工作量不确定；
 * - [OUTPUTTING]：工具调用已经结束，模型正在输出最终结论文本。此阶段通常只需数十秒即可完成，
 *   父 Agent 查询到该状态时不应再做长时间空等。
 */
enum class SubagentTaskStatus {
    RUNNING,
    OUTPUTTING,
    INTERRUPTED,
    SUCCEEDED,
    FAILED,
    KILLED
}

/**
 * 一个子代理任务的描述与结果快照。
 *
 * 无论前台还是后台，每个 subagent 调用都会登记一条任务记录，供
 * [SubagentTaskStore.get] 查询结果、供 UI/日志展示嵌套信息。
 */
data class SubagentTask(
    val id: String,
    val sessionId: String,
    val parentRunId: String,
    val description: String,
    val prompt: String,
    val depth: Int,
    val parentTaskId: String?,
    val status: SubagentTaskStatus,
    val result: String = "",
    val error: String? = null,
    val modelUsed: String = "",
    val toolCalls: Int = 0,
    val steps: List<ThinkingStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val todos: List<AgentTodo> = emptyList(),
    val requestKey: String? = null,
    val runInBackground: Boolean = false,
    val parentMessageId: String? = null,
    val parentToolCallId: String? = null,
    val conversation: List<Map<String, Any>> = emptyList(),
    val toolCallStates: Map<String, String> = emptyMap(),
    val executionGeneration: Int = 1
) {
    /** 前台与后台统一的 JSON 序列化，供工具返回给父模型。 */
    fun toJson(gson: Gson = Gson()): String = gson.toJson(this)

    fun toMap(gson: Gson = Gson()): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(toJson(gson), Map::class.java) as Map<String, Any>
    }

    /** 是否已结束（不再变化）。 */
    val isTerminal: Boolean
        get() = status == SubagentTaskStatus.SUCCEEDED ||
            status == SubagentTaskStatus.FAILED ||
            status == SubagentTaskStatus.KILLED

    /** 是否仍在进行（running / outputting）。 */
    val isActive: Boolean
        get() = status == SubagentTaskStatus.RUNNING || status == SubagentTaskStatus.OUTPUTTING
}

/**
 * 子代理任务登记与档案级 Room 存储。
 *
 * 用于支持后台运行：AI 调用 subagent 并设置 run_in_background=true 时，任务被登记并在
 * 独立协程执行；父 Agent 可用 subagent_get / subagent_list 查询结果（对齐 DSH 的
 * job_output / list_agents）。内存 Map 只作当前档案的查询缓存，任务正文、进度和待办由 Room 保存。
 */
object SubagentTaskStore {

    private val gson = Gson()
    private val tasks = ConcurrentHashMap<String, SubagentTask>()
    private val lock = Any()
    @Volatile private var dao: LocalSubagentTaskDao? = null

    /** 将当前档案的 Room 存储绑定到任务目录，并把失去运行句柄的任务恢复为中断态。 */
    fun bind(persistence: LocalSubagentTaskDao) = synchronized(lock) {
        dao = persistence
        tasks.clear()
        runBlocking(Dispatchers.IO) { persistence.listAll() }.forEach { entity ->
            var task = entity.toTask()
            task = task.copy(
                conversation = runBlocking(Dispatchers.IO) { persistence.listMessages(task.id) }.mapNotNull { message ->
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        gson.fromJson(message.payloadJson, Map::class.java) as? Map<String, Any>
                    }.getOrNull()
                }
            )
            if (task.status == SubagentTaskStatus.RUNNING || task.status == SubagentTaskStatus.OUTPUTTING) {
                task = task.copy(status = SubagentTaskStatus.INTERRUPTED)
                persistence.upsert(task.toEntity())
            }
            tasks[task.id] = task
        }
        runBlocking(Dispatchers.IO) { persistence.listPendingNotices() }.forEach { notice ->
            val parent = notice.parentTaskId?.let(tasks::get)
            val alreadyPersistedInParent = parent?.conversation?.any { message ->
                message["role"] == "user" && message["content"]?.toString() == notice.payload
            } == true
            if (alreadyPersistedInParent) {
                markNoticeDelivered(notice.noticeId, parent?.id)
            } else if (parent != null && parent.isActive) {
                SubagentTaskNoticeBus.publishPersisted(parent.id, notice.noticeId, notice.payload)
            } else {
                if (notice.parentTaskId != null) {
                    runBlocking(Dispatchers.IO) { persistence.retargetPendingNotice(notice.noticeId, null) }
                }
                AgentNoticeBus.publishPersisted(notice.sessionId, notice.noticeId, notice.payload, wakeIfIdle = false)
            }
        }
    }

    /** 标记任务中断、等待档案后台任务退出，再解除旧档案的写入绑定。 */
    fun closeRepository() {
        val taskIds = tasks.keys.toList()
        val sessionIds = tasks.values.map { it.sessionId }.distinct()
        synchronized(lock) {
            tasks.values.filter { it.isActive }.forEach { current ->
                val interrupted = current.copy(status = SubagentTaskStatus.INTERRUPTED)
                tasks[current.id] = interrupted
                runCatching { persist(interrupted) }
            }
        }
        SubagentRunRegistry.cancelAndJoin(taskIds.toSet())
        SubagentTaskNoticeBus.clearTasks(taskIds)
        sessionIds.forEach(AgentNoticeBus::clear)
        synchronized(lock) { dao = null }
    }

    /** 登记一个新任务，返回任务记录。 */
    fun register(
        sessionId: String,
        parentRunId: String,
        description: String,
        prompt: String,
        depth: Int,
        parentTaskId: String?,
        runInBackground: Boolean = false,
        requestKey: String? = null,
        parentMessageId: String? = null,
        parentToolCallId: String? = null
    ): SubagentTask {
        val now = System.currentTimeMillis()
        val task = SubagentTask(
            id = "sub_" + UUID.randomUUID().toString().replace("-", "").take(16),
            sessionId = sessionId,
            parentRunId = parentRunId,
            description = description,
            prompt = prompt,
            depth = depth,
            parentTaskId = parentTaskId,
            status = SubagentTaskStatus.RUNNING,
            createdAt = now,
            startedAt = now,
            requestKey = requestKey,
            runInBackground = runInBackground,
            parentMessageId = parentMessageId,
            parentToolCallId = parentToolCallId
        )
        try {
            persist(task)
            tasks[task.id] = task
        } catch (error: Exception) {
            tasks.remove(task.id)
            throw error
        }
        return task
    }

    /** 更新任务的进行时状态。 */
    fun update(
        id: String,
        status: SubagentTaskStatus? = null,
        result: String? = null,
        error: String? = null,
        modelUsed: String? = null,
        toolCalls: Int? = null,
        steps: List<ThinkingStep>? = null
    ) = synchronized(lock) {
        val current = tasks[id] ?: return
        if (current.status == SubagentTaskStatus.INTERRUPTED && status != SubagentTaskStatus.INTERRUPTED) return
        val terminal = current.isTerminal
        val next = current.copy(
            status = status ?: current.status,
            result = result ?: current.result,
            error = error ?: current.error,
            modelUsed = modelUsed ?: current.modelUsed,
            toolCalls = toolCalls ?: current.toolCalls,
            steps = steps ?: current.steps,
            finishedAt = if (!terminal && (status == SubagentTaskStatus.SUCCEEDED ||
                    status == SubagentTaskStatus.FAILED ||
                    status == SubagentTaskStatus.KILLED)) {
                System.currentTimeMillis()
            } else {
                current.finishedAt
            }
        )
        persist(next)
        tasks[id] = next
    }

    /** 在停止后台协程前先持久化中断状态，阻止迟到回调覆盖暂停结果。 */
    fun interruptIfActive(id: String, reason: String): SubagentTask? = synchronized(lock) {
        val current = tasks[id] ?: return@synchronized null
        if (!current.isActive) return@synchronized null
        val pausedSteps = current.steps.map { step ->
            if (step.status == "active" || step.status == "running") {
                step.copy(status = "error", detail = listOfNotNull(step.detail, reason).joinToString("；"))
            } else {
                step
            }
        }
        val interrupted = current.copy(
            status = SubagentTaskStatus.INTERRUPTED,
            error = reason,
            steps = pausedSteps
        )
        persist(interrupted)
        tasks[id] = interrupted
        interrupted
    }

    /** 只允许一个恢复调用接管中断任务；失败时不修改原状态。 */
    fun beginResume(id: String): SubagentTask? = synchronized(lock) {
        val current = tasks[id] ?: return@synchronized null
        if (current.status != SubagentTaskStatus.INTERRUPTED) return@synchronized null
        val resumed = current.copy(
            status = SubagentTaskStatus.RUNNING,
            error = null,
            startedAt = System.currentTimeMillis(),
            finishedAt = null,
            executionGeneration = current.executionGeneration + 1
        )
        persist(resumed)
        tasks[id] = resumed
        resumed
    }

    /** 在外部工具真正执行前标记调用；若进程此后退出，恢复器会先让 AI 核查结果。 */
    fun markToolCallRunning(id: String, callId: String) = synchronized(lock) {
        if (callId.isBlank()) return@synchronized
        val current = tasks[id] ?: return@synchronized
        if (current.status == SubagentTaskStatus.INTERRUPTED) return@synchronized
        val next = current.copy(toolCallStates = current.toolCallStates + (callId to "running"))
        val currentDao = dao
        if (currentDao != null) {
            val prior = io { currentDao.getToolCall(id, current.executionGeneration, callId) }
            val metadata = findToolCallMetadata(current.conversation, callId)
            val now = System.currentTimeMillis()
            val call = (prior ?: LocalSubagentToolCallEntity(
                taskId = id,
                executionGeneration = current.executionGeneration,
                callId = callId,
                assistantSequence = metadata?.first ?: current.conversation.size.toLong(),
                resultSequence = null,
                toolName = metadata?.second.orEmpty(),
                argumentsSummary = metadata?.third.orEmpty(),
                status = "prepared",
                startedAt = null,
                finishedAt = null,
                recoveryDecision = null
            )).copy(status = "running", startedAt = prior?.startedAt ?: now)
            io { currentDao.persistToolCallCheckpoint(next.toEntity(), call) }
        } else {
            persist(next)
        }
        tasks[id] = next
    }

    /** 按序持久化模型协议消息，并记录 assistant/tool-call 与对应结果的恢复状态。 */
    fun appendConversationMessage(id: String, message: Map<String, Any>) = synchronized(lock) {
        val current = tasks[id] ?: return@synchronized
        if (current.status == SubagentTaskStatus.INTERRUPTED) {
            throw kotlinx.coroutines.CancellationException("子代理已中断")
        }
        val nextConversation = current.conversation + message.toMap()
        val sequence = nextConversation.size.toLong() - 1L
        val nextStates = current.toolCallStates.toMutableMap()
        val callUpdates = mutableListOf<LocalSubagentToolCallEntity>()
        if (message["role"] == "assistant") {
            val calls = message["tool_calls"] as? List<*> ?: emptyList<Any>()
            calls.forEach { rawCall ->
                val call = rawCall as? Map<*, *> ?: return@forEach
                val callId = call["id"]?.toString()?.takeIf(String::isNotBlank) ?: return@forEach
                nextStates.putIfAbsent(callId, "prepared")
                val function = call["function"] as? Map<*, *>
                val name = function?.get("name")?.toString().orEmpty()
                val rawArguments = function?.get("arguments")
                val arguments = when (rawArguments) {
                    is String -> rawArguments
                    null -> "{}"
                    else -> gson.toJson(rawArguments)
                }.take(2_000)
                callUpdates += LocalSubagentToolCallEntity(
                    taskId = id,
                    executionGeneration = current.executionGeneration,
                    callId = callId,
                    assistantSequence = sequence,
                    resultSequence = null,
                    toolName = name,
                    argumentsSummary = arguments,
                    status = "prepared",
                    startedAt = null,
                    finishedAt = null,
                    recoveryDecision = null
                )
            }
        } else if (message["role"] == "tool") {
            val callId = message["tool_call_id"]?.toString()?.takeIf(String::isNotBlank)
            if (callId != null) {
                val recoveryDecision = recoveryDecision(message["content"])
                val callStatus = when (recoveryDecision) {
                    "result_unknown" -> "unknown"
                    "not_started" -> "not_started"
                    else -> "completed"
                }
                nextStates[callId] = callStatus
                val currentDao = dao
                val prior = currentDao?.let { db ->
                    io { db.getLatestToolCall(id, callId) }
                }
                val metadata = findToolCallMetadata(current.conversation, callId)
                callUpdates += (prior ?: LocalSubagentToolCallEntity(
                    taskId = id,
                    executionGeneration = current.executionGeneration,
                    callId = callId,
                    assistantSequence = metadata?.first ?: sequence,
                    resultSequence = null,
                    toolName = message["name"]?.toString() ?: metadata?.second.orEmpty(),
                    argumentsSummary = metadata?.third.orEmpty(),
                    status = "prepared",
                    startedAt = null,
                    finishedAt = null,
                    recoveryDecision = null
                )).copy(
                    status = callStatus,
                    resultSequence = sequence,
                    finishedAt = System.currentTimeMillis(),
                    recoveryDecision = recoveryDecision
                )
            }
        }

        val next = current.copy(conversation = nextConversation, toolCallStates = nextStates)
        val currentDao = dao
        if (currentDao != null) {
            io {
                currentDao.persistMessageCheckpoint(
                    task = next.toEntity(),
                    message = LocalSubagentMessageEntity(
                        taskId = id,
                        sequence = sequence,
                        payloadJson = gson.toJson(message),
                        createdAt = System.currentTimeMillis()
                    ),
                    toolCalls = callUpdates
                )
            }
        } else {
            persist(next)
        }
        tasks[id] = next
        if (message["role"] == "user") {
            // 通知用户消息与 outbox 状态在消息落库后确认；进程若在此前退出，启动时可安全补投。
            SubagentTaskNoticeBus.acknowledgeConsumed(id)
        }
    }

    /**
     * 使恢复上下文中的 assistant tool-call 全部有对应 tool 消息。
     * 未开始的调用明确标为未执行；已开始但没有结果的调用标为结果未知，交给 AI 核查。
     */
    fun prepareConversationForResume(id: String): List<Map<String, Any>> = synchronized(lock) {
        val task = tasks[id] ?: return@synchronized emptyList()
        val completedIds = task.conversation.asSequence()
            .filter { it["role"] == "tool" }
            .mapNotNull { it["tool_call_id"]?.toString()?.takeIf(String::isNotBlank) }
            .toSet()
        val unfinishedCalls = task.conversation.asSequence()
            .filter { it["role"] == "assistant" }
            .flatMap { (it["tool_calls"] as? List<*>)?.asSequence() ?: emptySequence() }
            .mapNotNull { raw ->
                val call = raw as? Map<*, *> ?: return@mapNotNull null
                val idValue = call["id"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (idValue in completedIds) return@mapNotNull null
                val function = call["function"] as? Map<*, *>
                idValue to (function?.get("name")?.toString().orEmpty())
            }
            .toList()

        unfinishedCalls.forEach { (callId, toolName) ->
            val wasRunning = task.toolCallStates[callId] == "running"
            val recoveryContent = gson.toJson(
                mapOf(
                    "success" to false,
                    "recovery" to if (wasRunning) "result_unknown" else "not_started",
                    "message" to if (wasRunning) {
                        "应用在执行该工具时中断，操作结果未知。不要直接重放；先使用只读工具核查当前状态，再决定如何继续。"
                    } else {
                        "应用在开始执行该工具前中断。此调用没有产生副作用，请根据原任务与已有上下文重新判断是否需要调用。"
                    }
                )
            )
            appendConversationMessage(
                id,
                mapOf(
                    "role" to "tool",
                    "tool_call_id" to callId,
                    "name" to toolName,
                    "content" to recoveryContent
                )
            )
        }
        tasks[id]?.conversation.orEmpty()
    }

    /** 在指定任务上追加一个进度步骤。 */
    fun appendStep(id: String, step: ThinkingStep) = synchronized(lock) {
        val current = tasks[id] ?: return
        if (current.status == SubagentTaskStatus.INTERRUPTED) return
        val next = current.copy(steps = current.steps + step)
        persist(next)
        tasks[id] = next
    }

    /**
     * 标记任务进入「正在输出最终结果」阶段（RUNNING → OUTPUTTING）。
     *
     * 只在任务仍为 RUNNING 时生效：已结束或被终止的任务不受影响。
     * @return 状态是否真的发生变化（供 UI 去重，只推一次卡片）。
     */
    fun markOutputting(id: String): Boolean = synchronized(lock) {
        val current = tasks[id] ?: return false
        if (current.status != SubagentTaskStatus.RUNNING) return@synchronized false
        val next = current.copy(status = SubagentTaskStatus.OUTPUTTING)
        persist(next)
        tasks[id] = next
        true
    }

    /** 从「输出中」回到「执行中」（模型本轮又开始调用工具）。只在 OUTPUTTING 时生效。 */
    fun markRunning(id: String): Boolean = synchronized(lock) {
        val current = tasks[id] ?: return false
        if (current.status != SubagentTaskStatus.OUTPUTTING) return@synchronized false
        val next = current.copy(status = SubagentTaskStatus.RUNNING)
        persist(next)
        tasks[id] = next
        true
    }

    fun get(id: String): SubagentTask? = tasks[id]

    fun findByRequestKey(sessionId: String, requestKey: String): SubagentTask? =
        tasks.values.firstOrNull { it.sessionId == sessionId && it.requestKey == requestKey }

    /** 查询某个会话下的任务（避免跨会话泄露）。 */
    fun listForSession(sessionId: String): List<SubagentTask> =
        tasks.values.filter { it.sessionId == sessionId }.sortedByDescending { it.createdAt }

    /** 返回可用于恢复核查的工具调用摘要，不携带工具结果正文。 */
    fun toolCallSnapshots(taskId: String): List<Map<String, Any?>> {
        val persisted = dao?.let { currentDao ->
            runCatching { io { currentDao.listToolCalls(taskId) } }.getOrNull()
        }
        if (!persisted.isNullOrEmpty()) {
            return persisted.takeLast(100).map { call ->
                buildMap {
                    put("execution_generation", call.executionGeneration)
                    put("call_id", call.callId)
                    put("tool", call.toolName)
                    put("arguments", call.argumentsSummary)
                    put("status", call.status)
                    call.startedAt?.let { put("started_at", it) }
                    call.finishedAt?.let { put("finished_at", it) }
                    call.recoveryDecision?.let { put("recovery_decision", it) }
                }
            }
        }

        val task = tasks[taskId] ?: return emptyList()
        return task.conversation.asSequence()
            .filter { it["role"] == "assistant" }
            .flatMap { it["tool_calls"] as? List<*> ?: emptyList<Any>() }
            .mapNotNull { raw ->
                val call = raw as? Map<*, *> ?: return@mapNotNull null
                val function = call["function"] as? Map<*, *>
                val callId = call["id"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val rawArguments = function?.get("arguments")
                val arguments = when (rawArguments) {
                    is String -> rawArguments
                    null -> "{}"
                    else -> gson.toJson(rawArguments)
                }
                buildMap<String, Any?> {
                    put("call_id", callId)
                    put("tool", function?.get("name")?.toString().orEmpty())
                    put("arguments", arguments.take(2_000))
                    put("status", task.toolCallStates[callId] ?: "prepared")
                    task.toolCallStates[callId]?.takeIf { it == "unknown" }?.let {
                        put("recovery_decision", "result_unknown")
                    }
                }
            }
            .toList()
            .takeLast(100)
    }

    fun all(): List<SubagentTask> = tasks.values.sortedByDescending { it.createdAt }

    /** 保存完成 outbox 后再投递到当前父任务或会话队列；notice id 按执行代次幂等。 */
    fun publishCompletionNotice(task: SubagentTask, parentTaskId: String?) {
        val noticeId = "subagent:${task.id}:${task.executionGeneration}"
        val payload = buildSubagentCompletionNotice(task, addressedToParentTask = parentTaskId != null)
        val currentDao = dao
        if (currentDao == null) {
            if (parentTaskId != null) SubagentTaskNoticeBus.publish(parentTaskId, payload)
            else AgentNoticeBus.publish(task.sessionId, payload)
            return
        }
        val inserted = io {
            currentDao.insertNoticeIfAbsent(
                LocalAgentNoticeEntity(
                    noticeId = noticeId,
                    taskId = task.id,
                    sessionId = task.sessionId,
                    parentTaskId = parentTaskId,
                    executionGeneration = task.executionGeneration,
                    payload = payload,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        if (inserted < 0L) return
        if (parentTaskId != null) {
            SubagentTaskNoticeBus.publishPersisted(parentTaskId, noticeId, payload)
        } else {
            AgentNoticeBus.publishPersisted(task.sessionId, noticeId, payload)
        }
    }

    /** 队列消费后确认 outbox；未消费记录会在档案重新绑定时补投。 */
    internal fun markNoticeDelivered(noticeId: String, runId: String? = null) {
        val currentDao = dao ?: return
        io { currentDao.markNoticeDelivered(noticeId, System.currentTimeMillis(), runId) }
    }

    internal fun retargetPendingNotice(noticeId: String, parentTaskId: String?) {
        val currentDao = dao ?: return
        io { currentDao.retargetPendingNotice(noticeId, parentTaskId) }
    }

    /** 档案绑定完成并注册唤醒回调后，触发已恢复的会话通知。 */
    internal fun wakePendingNotices() {
        AgentNoticeBus.wakePending()
    }

    /** 清理指定会话下的任务记录（会话删除时调用）。 */
    fun clearSession(sessionId: String) {
        val removedIds = tasks.keys.filter { tasks[it]?.sessionId == sessionId }
        SubagentRunRegistry.cancelAndJoin(removedIds.toSet())
        removedIds.forEach(tasks::remove)
        io { dao?.deleteForSession(sessionId) }
        SubagentTodoStore.clearTasks(removedIds.toSet())
        SubagentTaskNoticeBus.clearTasks(removedIds.toSet())
    }

    fun size(): Int = tasks.size

    /** 供序列化 / 调试返回任务列表。 */
    fun toJsonList(gson: Gson = this.gson, sessionId: String? = null): String {
        val list = if (sessionId == null) all() else listForSession(sessionId)
        return gson.toJson(list)
    }

    internal fun setTodos(taskId: String, todos: List<AgentTodo>) = synchronized(lock) {
        val current = tasks[taskId] ?: return@synchronized
        val next = current.copy(todos = todos)
        persist(next)
        tasks[taskId] = next
    }

    private fun persist(task: SubagentTask) {
        val currentDao = dao ?: return
        io { currentDao.upsert(task.toEntity()) }
    }

    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun recoveryDecision(rawContent: Any?): String? = runCatching {
        when (rawContent) {
            is Map<*, *> -> rawContent["recovery"]?.toString()
            is String -> JsonParser.parseString(rawContent).asJsonObject.get("recovery")?.asString
            else -> null
        }
    }.getOrNull()

    private fun findToolCallMetadata(
        conversation: List<Map<String, Any>>,
        callId: String
    ): Triple<Long, String, String>? {
        conversation.forEachIndexed { sequence, message ->
            if (message["role"] != "assistant") return@forEachIndexed
            val calls = message["tool_calls"] as? List<*> ?: return@forEachIndexed
            calls.forEach { rawCall ->
                val call = rawCall as? Map<*, *> ?: return@forEach
                if (call["id"]?.toString() != callId) return@forEach
                val function = call["function"] as? Map<*, *>
                val rawArguments = function?.get("arguments")
                val arguments = when (rawArguments) {
                    is String -> rawArguments
                    null -> "{}"
                    else -> gson.toJson(rawArguments)
                }
                return Triple(sequence.toLong(), function?.get("name")?.toString().orEmpty(), arguments.take(2_000))
            }
        }
        return null
    }

    private fun SubagentTask.toEntity() = LocalSubagentTaskEntity(
        taskId = id,
        sessionId = sessionId,
        rootRunId = parentRunId,
        parentTaskId = parentTaskId,
        parentMessageId = parentMessageId,
        parentToolCallId = parentToolCallId,
        requestKey = requestKey,
        description = description,
        prompt = prompt,
        runInBackground = runInBackground,
        depth = depth,
        status = status.name.lowercase(),
        stage = status.name.lowercase(),
        executionGeneration = executionGeneration,
        completedToolCalls = toolCalls,
        checkpointSequence = conversation.size.toLong(),
        todosJson = gson.toJson(todos),
        toolCallStatesJson = gson.toJson(toolCallStates),
        result = result,
        error = error,
        modelUsed = modelUsed,
        toolCalls = toolCalls,
        stepsJson = gson.toJson(steps),
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
        updatedAt = System.currentTimeMillis(),
        sourceDeviceId = null
    )

    private fun LocalSubagentTaskEntity.toTask(): SubagentTask {
        val todoType = object : TypeToken<List<AgentTodo>>() {}.type
        val stepType = object : TypeToken<List<ThinkingStep>>() {}.type
        val callStatesType = object : TypeToken<Map<String, String>>() {}.type
        return SubagentTask(
            id = taskId,
            sessionId = sessionId,
            parentRunId = rootRunId,
            description = description,
            prompt = prompt,
            depth = depth,
            parentTaskId = parentTaskId,
            status = runCatching { SubagentTaskStatus.valueOf(status.uppercase()) }
                .getOrDefault(SubagentTaskStatus.INTERRUPTED),
            result = result,
            error = error,
            modelUsed = modelUsed,
            toolCalls = toolCalls,
            steps = runCatching { gson.fromJson<List<ThinkingStep>>(stepsJson, stepType) }.getOrDefault(emptyList()),
            createdAt = createdAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            todos = runCatching { gson.fromJson<List<AgentTodo>>(todosJson, todoType) }.getOrDefault(emptyList()),
            requestKey = requestKey,
            runInBackground = runInBackground,
            parentMessageId = parentMessageId,
            parentToolCallId = parentToolCallId,
            executionGeneration = executionGeneration,
            toolCallStates = runCatching { gson.fromJson<Map<String, String>>(toolCallStatesJson, callStatesType) }
                .getOrDefault(emptyMap())
        )
    }
}

/**
 * 子代理的独立任务清单存储（进程内单例）。
 *
 * 子代理调用 todo_write / todo_read 时读写这里，按子代理任务 id 隔离；
 * 与主会话的任务列表（local_sessions.agent_todos）完全无关，也不推送任何 UI 事件，
 * 因此子代理的任务既不会覆盖主会话的清单，也不会出现在输入框上方的任务面板里。
 * 与 [SubagentTaskStore] 共用 Room 任务行，进程重启后仍可查询；会话清理时一并移除。
 */
object SubagentTodoStore {

    private val todosByTask = ConcurrentHashMap<String, List<com.nekobot.app.data.model.AgentTodo>>()

    /** 读取某个子代理任务的任务清单（无记录时返回空列表）。 */
    fun get(taskId: String): List<com.nekobot.app.data.model.AgentTodo> =
        SubagentTaskStore.get(taskId)?.todos ?: todosByTask[taskId] ?: emptyList()

    /** 全量写入某个子代理任务的任务清单。 */
    fun set(taskId: String, todos: List<com.nekobot.app.data.model.AgentTodo>) {
        if (SubagentTaskStore.get(taskId) != null) {
            SubagentTaskStore.setTodos(taskId, todos)
        }
        todosByTask[taskId] = todos
    }

    /** 移除一批子代理任务的任务清单（任务记录被清理时调用）。 */
    fun clearTasks(taskIds: Collection<String>) {
        if (taskIds.isEmpty()) return
        taskIds.forEach(todosByTask::remove)
    }
}
