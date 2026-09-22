package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.model.ThinkingStep
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
    val finishedAt: Long? = null
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
        get() = !isTerminal
}

/**
 * 子代理任务登记/结果存储（进程内单例）。
 *
 * 用于支持后台运行：AI 调用 subagent 并设置 run_in_background=true 时，任务被登记并在
 * 独立协程执行；父 Agent 可用 subagent_get / subagent_list 查询结果（对齐 DSH 的
 * job_output / list_agents）。所有字段线程安全，进程重启后丢弃（后台结果本身由父会话
 * 的检查点承担一部分，此处仅作为会话内的临时结果缓存）。
 */
object SubagentTaskStore {

    private val gson = Gson()
    private val tasks = ConcurrentHashMap<String, SubagentTask>()

    /** 登记一个新任务，返回任务记录。 */
    fun register(
        sessionId: String,
        parentRunId: String,
        description: String,
        prompt: String,
        depth: Int,
        parentTaskId: String?
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
            startedAt = now
        )
        tasks[task.id] = task
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
    ) {
        val current = tasks[id] ?: return
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
        tasks[id] = next
    }

    /** 在指定任务上追加一个进度步骤。 */
    fun appendStep(id: String, step: ThinkingStep) {
        val current = tasks[id] ?: return
        tasks[id] = current.copy(steps = current.steps + step)
    }

    /**
     * 标记任务进入「正在输出最终结果」阶段（RUNNING → OUTPUTTING）。
     *
     * 只在任务仍为 RUNNING 时生效：已结束或被终止的任务不受影响。
     * @return 状态是否真的发生变化（供 UI 去重，只推一次卡片）。
     */
    fun markOutputting(id: String): Boolean {
        val current = tasks[id] ?: return false
        if (current.status != SubagentTaskStatus.RUNNING) return false
        tasks[id] = current.copy(status = SubagentTaskStatus.OUTPUTTING)
        return true
    }

    /** 从「输出中」回到「执行中」（模型本轮又开始调用工具）。只在 OUTPUTTING 时生效。 */
    fun markRunning(id: String): Boolean {
        val current = tasks[id] ?: return false
        if (current.status != SubagentTaskStatus.OUTPUTTING) return false
        tasks[id] = current.copy(status = SubagentTaskStatus.RUNNING)
        return true
    }

    fun get(id: String): SubagentTask? = tasks[id]

    /** 查询某个会话下的任务（避免跨会话泄露）。 */
    fun listForSession(sessionId: String): List<SubagentTask> =
        tasks.values.filter { it.sessionId == sessionId }.sortedByDescending { it.createdAt }

    fun all(): List<SubagentTask> = tasks.values.sortedByDescending { it.createdAt }

    /** 清理指定会话下的任务记录（会话删除时调用）。 */
    fun clearSession(sessionId: String) {
        tasks.keys.filter { tasks[it]?.sessionId == sessionId }.forEach(tasks::remove)
    }

    fun size(): Int = tasks.size

    /** 供序列化 / 调试返回任务列表。 */
    fun toJsonList(gson: Gson = this.gson, sessionId: String? = null): String {
        val list = if (sessionId == null) all() else listForSession(sessionId)
        return gson.toJson(list)
    }
}