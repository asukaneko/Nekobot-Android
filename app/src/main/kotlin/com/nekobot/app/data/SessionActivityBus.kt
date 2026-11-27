package com.nekobot.app.data

import com.nekobot.app.data.model.ThinkingCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 会话当前处于哪个阶段：首页模式卡据此展示「思考中 / 调用工具」等运行状态。
 *
 * 首页可能同时有多个会话在跑，所以状态按 sessionId 分别登记，而不是只保留最近一个。
 */
enum class SessionActivityPhase {
    /** 模型正在思考或流式生成正文。 */
    THINKING,

    /** 正在执行工具调用。 */
    TOOL,

    /** 挂起等待用户处理（命令授权 / 提问 / 插件安装确认）。 */
    WAITING
}

/** 单个会话的运行阶段快照。 */
data class SessionActivity(
    val sessionId: String,
    val phase: SessionActivityPhase,
    /** TOOL 阶段正在执行的工具 id；其余阶段为 null。 */
    val toolName: String? = null,
    /** 最近一次阶段变化的时间戳（毫秒），首页据此把最活跃的会话排在前面。 */
    val updatedAt: Long = 0L
)

/**
 * 全局会话运行阶段总线。
 *
 * 两个上报入口，覆盖两条通道：
 * - 本地模式：[com.nekobot.app.data.local.ai.ActivityTrackingReporter] 在流水线回调里登记；
 * - 服务器模式：[com.nekobot.app.data.remote.SocketManager] 收到流式/进度卡事件时登记。
 *
 * 状态只存在于内存：
 * - 同一阶段重复上报会被忽略（流式思考每个分片都会上报，不能每次都发新值）；
 * - 一轮生成结束时由 [com.nekobot.app.ServiceContainer.notifySessionGenerating] 清理，
 *   与「生成中会话集合」保持同一生命周期，避免残留僵死状态。
 */
object SessionActivityBus {

    private val _activities = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())

    /** sessionId → 当前阶段。 */
    val activities: StateFlow<Map<String, SessionActivity>> = _activities.asStateFlow()

    /**
     * 登记某个会话的新阶段。
     *
     * @param toolName TOOL 阶段才有意义，其它阶段会忽略。
     * @param now 阶段变化时间（毫秒），单元测试可注入固定值。
     */
    fun publish(
        sessionId: String?,
        phase: SessionActivityPhase,
        toolName: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val id = sessionId?.takeIf { it.isNotBlank() } ?: return
        val tool = toolName?.takeIf { it.isNotBlank() }
            ?.takeIf { phase == SessionActivityPhase.TOOL }
        _activities.update { current ->
            val existing = current[id]
            // 阶段没变就不发新值：思考分片会高频上报，重复写入会让首页卡片持续重组
            if (existing != null && existing.phase == phase && existing.toolName == tool) {
                current
            } else {
                current + (id to SessionActivity(id, phase, tool, now))
            }
        }
    }

    /** 模型开始思考 / 生成正文。 */
    fun onThinking(sessionId: String?) = publish(sessionId, SessionActivityPhase.THINKING)

    /** 开始执行某个工具。 */
    fun onToolStart(sessionId: String?, toolName: String) =
        publish(sessionId, SessionActivityPhase.TOOL, toolName)

    /** 工具执行完毕：下一轮又会回到思考。 */
    fun onToolDone(sessionId: String?) = publish(sessionId, SessionActivityPhase.THINKING)

    /** 挂起等待用户处理。 */
    fun onWaiting(sessionId: String?) = publish(sessionId, SessionActivityPhase.WAITING)

    /** 一轮运行结束（或会话被移除）时清除该会话的阶段。 */
    fun clear(sessionId: String?) {
        val id = sessionId?.takeIf { it.isNotBlank() } ?: return
        _activities.update { current -> if (current.containsKey(id)) current - id else current }
    }

    /** 清空全部阶段：切换模式 / 数据库档案时调用，避免上一通道的状态残留。 */
    fun clearAll() {
        _activities.value = emptyMap()
    }
}

/**
 * 服务器模式：从进度卡片推断当前阶段。
 *
 * 卡片里最后一个 running/active 步骤就是 Agent 正在做的事——是工具调用还是思考。
 * 没有进行中的步骤（例如卡片刚创建）按「思考中」处理，交给后续事件纠正。
 */
fun sessionActivityFromProgressCard(card: ThinkingCard): SessionActivityPhase {
    val step = card.steps.lastOrNull { it.status == "running" || it.status == "active" }
        ?: return SessionActivityPhase.THINKING
    return if (step.type == "tool") SessionActivityPhase.TOOL else SessionActivityPhase.THINKING
}

/** 进度卡片里正在执行的工具名；不是工具步骤时返回 null。 */
fun sessionActivityToolFromProgressCard(card: ThinkingCard): String? {
    val step = card.steps.lastOrNull { it.status == "running" || it.status == "active" }
        ?: return null
    if (step.type != "tool") return null
    return step.name?.takeIf { it.isNotBlank() }
}
