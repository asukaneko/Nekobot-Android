package com.nekobot.app.data.local.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Agent 悬浮窗状态总线。
 *
 * Agent 运行期间，进度上报器把「当前工具名 + 思考/中间文本」写入这里；
 * AgentForegroundService 收集该状态并驱动悬浮窗展示（仅在应用处于后台时显示）。
 * 悬浮窗自身带 FLAG_SECURE，不进入任何截图。
 */
object AgentOverlayBus {

    /** 悬浮窗展示所需的最小状态。 */
    data class State(
        /** 强制刷新序号（设置项变更时递增）。 */
        val revision: Long = 0L,
        /** 当前 Agent 会话 id；null 表示尚无任何 Agent 工具活动。 */
        val sessionId: String? = null,
        /** 最近一次 Android 工具 id。 */
        val toolName: String? = null,
        /** 工具是否正在执行。 */
        val toolRunning: Boolean = false,
        /** 思考 / 中间文本（累积，仅保留末尾若干字符）。 */
        val text: String = "",
        /** 本轮 Agent 是否仍在运行。 */
        val active: Boolean = false,
        val updatedAt: Long = 0L
    )

    private const val MAX_TEXT_CHARS = 4000

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun isAndroidTool(toolName: String): Boolean = toolName.startsWith("android_")

    private inline fun mutateFor(sessionId: String, transform: (State) -> State) {
        if (sessionId.isBlank()) return
        _state.update { current ->
            val base = if (current.sessionId == sessionId) current else State(sessionId = sessionId)
            transform(base).copy(updatedAt = System.currentTimeMillis())
        }
    }

    /** 思考分片：仅在悬浮窗已激活（使用过 Android 工具）后才有展示意义，这里始终记录尾部内容。 */
    fun onThinking(sessionId: String, chunk: String) {
        if (chunk.isEmpty()) return
        mutateFor(sessionId) { base ->
            base.copy(text = (base.text + chunk).takeLast(MAX_TEXT_CHARS))
        }
    }

    /** 中间回复正文：与思考共用同一段展示文本。 */
    fun onIntermediateContent(sessionId: String, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        mutateFor(sessionId) { base ->
            val joined = if (base.text.isBlank()) text else base.text + "\n" + text
            base.copy(text = joined.takeLast(MAX_TEXT_CHARS))
        }
    }

    /** Android 工具开始：激活悬浮窗。 */
    fun onToolStart(sessionId: String, toolName: String) {
        if (!isAndroidTool(toolName)) return
        mutateFor(sessionId) { base ->
            base.copy(toolName = toolName, toolRunning = true, active = true)
        }
    }

    /** Android 工具结束。 */
    fun onToolDone(sessionId: String, toolName: String) {
        if (!isAndroidTool(toolName)) return
        mutateFor(sessionId) { base ->
            if (base.toolName == toolName) base.copy(toolRunning = false) else base
        }
    }

    /** 本轮 Agent 结束（含会话被停止）：悬浮窗不再展示。 */
    fun onRunFinished(sessionId: String) {
        mutateFor(sessionId) { base -> base.copy(toolRunning = false, active = false) }
    }

    /** 设置项等外部变更后强制刷新一次。 */
    fun refresh() {
        _state.update { it.copy(revision = it.revision + 1) }
    }

    /** 停止全部任务时清空状态。 */
    fun clear() {
        _state.value = State()
    }
}
