package com.nekobot.app.data.local.ai

import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.remote.ExecConfirmationRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 等待用户处理的 Agent 事件类型。 */
enum class AgentAttentionKind {
    ExecAuthorization,
    Question
}

/** 会话列表/通知用的一条等待事项摘要。 */
data class AgentAttentionItem(
    val kind: AgentAttentionKind,
    val text: String
)

/**
 * 「Agent 等待用户处理」事件中心。
 *
 * 命令授权与 ask_user_question 提问都会挂起 Agent，直到用户处理或超时。这里统一登记：
 *
 * 1. 用户不在该会话（含应用在后台）时发系统通知；
 * 2. 会话列表中标识该会话有等待处理的事项；
 * 3. 用户回到该会话、处理完成或等待超时后撤销通知与标记；
 * 4. 用户重新进入会话时，聊天界面可据此恢复出等待弹窗（见 [pendingExecConfirmation]
 *    与 [pendingAskUserQuestion]），避免"点开通知却看不到弹窗"。
 */
object AgentAttentionCenter {

    /** 等待项过期时间：与授权/提问管理器的等待超时一致（10 分钟）。 */
    private const val EXPIRY_MS = 10 * 60 * 1000L

    private sealed interface Payload {
        data class Exec(val request: ExecConfirmationRequest) : Payload
        data class Question(val request: AskUserQuestionRequest) : Payload
    }

    private class Entry(
        val id: String,
        val sessionId: String,
        val item: AgentAttentionItem,
        val payload: Payload,
        @Volatile var notified: Boolean = false
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    private val _pendingBySession = MutableStateFlow<Map<String, List<AgentAttentionItem>>>(emptyMap())

    /** 各会话当前等待用户处理的事项；会话列表据此显示提示。 */
    val pendingBySession: StateFlow<Map<String, List<AgentAttentionItem>>> =
        _pendingBySession.asStateFlow()

    // ==================== 登记 ====================

    fun registerExecAuthorization(request: ExecConfirmationRequest) {
        val text = request.command.trim().ifBlank { request.mainCommand }
        register(
            sessionId = request.sessionId,
            item = AgentAttentionItem(AgentAttentionKind.ExecAuthorization, text),
            payload = Payload.Exec(request)
        )
    }

    fun registerQuestion(request: AskUserQuestionRequest) {
        val first = request.questions.firstOrNull() ?: return
        val text = buildString {
            if (first.header.isNotBlank()) append("【${first.header}】")
            append(first.question)
            if (request.questions.size > 1) {
                localizedString(R.string.agent_attention_question_more, request.questions.size - 1)
                    ?.let { more ->
                        append('\n')
                        append(more)
                    }
            }
        }
        register(
            sessionId = request.sessionId,
            item = AgentAttentionItem(AgentAttentionKind.Question, text),
            payload = Payload.Question(request)
        )
    }

    private fun register(sessionId: String, item: AgentAttentionItem, payload: Payload) {
        if (sessionId.isBlank() || item.text.isBlank()) return
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            item = item,
            payload = payload
        )
        entries[entry.id] = entry
        publish()
        recheck()
        scheduleExpiry(entry)
    }

    // ==================== 查询 ====================

    fun pendingExecConfirmation(sessionId: String): ExecConfirmationRequest? =
        entries.values.firstNotNullOfOrNull { entry ->
            (entry.payload as? Payload.Exec)
                ?.request
                ?.takeIf { entry.sessionId == sessionId }
        }

    fun pendingAskUserQuestion(sessionId: String): AskUserQuestionRequest? =
        entries.values.firstNotNullOfOrNull { entry ->
            (entry.payload as? Payload.Question)
                ?.request
                ?.takeIf { entry.sessionId == sessionId }
        }

    // ==================== 处理完成 ====================

    fun resolveExecAuthorization(sessionId: String) {
        remove(sessionId, AgentAttentionKind.ExecAuthorization)
    }

    fun resolveQuestion(sessionId: String) {
        remove(sessionId, AgentAttentionKind.Question)
    }

    /** 停止生成/会话关闭：清掉该会话全部等待项与通知。 */
    fun clearSession(sessionId: String) {
        val targets = entries.values.filter { it.sessionId == sessionId }
        if (targets.isEmpty()) return
        targets.forEach { entries.remove(it.id) }
        publish()
        cancelNotification(sessionId, AgentAttentionKind.ExecAuthorization)
        cancelNotification(sessionId, AgentAttentionKind.Question)
    }

    private fun remove(sessionId: String, kind: AgentAttentionKind) {
        val targets = entries.values.filter { it.sessionId == sessionId && it.item.kind == kind }
        if (targets.isEmpty()) return
        targets.forEach { entries.remove(it.id) }
        publish()
        val remaining = entries.values.filter { it.sessionId == sessionId && it.item.kind == kind }
        if (remaining.isEmpty()) {
            cancelNotification(sessionId, kind)
        } else {
            // 仍有同类等待项：重新提醒一次，内容已随新的等待项更新。
            remaining.forEach { it.notified = false }
            recheck()
        }
    }

    // ==================== 提醒 ====================

    /**
     * 用户已回到该会话：撤销通知并重置提醒标记，之后离开会话仍会再次提醒。
     */
    fun onSessionVisible(sessionId: String) {
        if (sessionId.isBlank()) return
        entries.values.filter { it.sessionId == sessionId }.forEach { it.notified = false }
        cancelNotification(sessionId, AgentAttentionKind.ExecAuthorization)
        cancelNotification(sessionId, AgentAttentionKind.Question)
    }

    /**
     * 重新评估所有等待项是否需要提醒。
     *
     * 在登记新等待项、切换可见会话、前后台切换时调用；用 [Entry.notified] 去重。
     */
    fun recheck() {
        val context = ServiceContainer.appContext ?: return
        entries.values.forEach { entry ->
            if (entry.notified || !shouldNotify(entry.sessionId)) return@forEach
            entry.notified = showNotification(context, entry)
        }
    }

    /** 用户不在该会话时提醒（应用在后台，或在应用内但停在别的页面/会话）。 */
    private fun shouldNotify(sessionId: String): Boolean =
        !ServiceContainer.isAppForeground || ServiceContainer.activeChatSessionId != sessionId

    private fun showNotification(
        context: android.content.Context,
        entry: Entry
    ): Boolean = when (entry.item.kind) {
        AgentAttentionKind.ExecAuthorization -> AgentAttentionNotifier.notifyExecAuthorization(
            context = context,
            sessionId = entry.sessionId,
            command = entry.item.text
        )
        AgentAttentionKind.Question -> AgentAttentionNotifier.notifyQuestion(
            context = context,
            sessionId = entry.sessionId,
            questionText = entry.item.text
        )
    }

    private fun cancelNotification(sessionId: String, kind: AgentAttentionKind) {
        val context = ServiceContainer.appContext ?: return
        runCatching {
            when (kind) {
                AgentAttentionKind.ExecAuthorization ->
                    AgentAttentionNotifier.cancelExecAuthorization(context, sessionId)
                AgentAttentionKind.Question ->
                    AgentAttentionNotifier.cancelQuestion(context, sessionId)
            }
        }
    }

    private fun scheduleExpiry(entry: Entry) {
        ServiceContainer.applicationScope.launch {
            delay(EXPIRY_MS)
            if (entries.remove(entry.id) != null) {
                publish()
                val remaining = entries.values.any {
                    it.sessionId == entry.sessionId && it.item.kind == entry.item.kind
                }
                if (!remaining) cancelNotification(entry.sessionId, entry.item.kind)
            }
        }
    }

    private fun publish() {
        _pendingBySession.value = entries.values
            .groupBy { it.sessionId }
            .mapValues { (_, list) -> list.map { it.item } }
    }

    private fun localizedString(resId: Int, vararg args: Any): String? {
        val context = ServiceContainer.localizedContext ?: return null
        return runCatching { context.getString(resId, *args) }.getOrNull()
    }
}
