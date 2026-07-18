package com.nekobot.app.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.model.Message
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 解析 Socket.IO 中携带的消息。
 *
 * `new_message` 直接发送消息对象，而 `ai_response` 使用
 * `{"session_id": "...", "message": {...}}` 包装；两种格式都需要兼容。
 */
internal fun parseRealtimeMessagePayload(gson: Gson, raw: Any?): Message? {
    if (raw == null) return null
    val rawText = raw.toString()
    return try {
        val root = JsonParser.parseString(rawText)
        val message = if (root.isJsonObject) {
            root.asJsonObject.get("message")
                ?.takeUnless { it.isJsonNull }
                ?: root
        } else {
            root
        }
        when {
            message.isJsonObject -> gson.fromJson(message, Message::class.java)
            message.isJsonPrimitive -> Message(content = message.asString)
            else -> Message(content = message.toString())
        }
    } catch (_: Exception) {
        Message(content = rawText)
    }
}

/** AI 请求执行非白名单命令时的授权信息。 */
data class ExecConfirmationRequest(
    val requestId: String,
    val command: String,
    val mainCommand: String,
    val message: String,
    val sessionId: String
)

/**
 * Hook 触发通知：服务端在 hook 执行成功后通过 `hook_notification` 事件推送，
 * 用于在聊天界面显示成就式弹窗（参考后端 manager._notify_frontend）。
 */
data class HookNotification(
    val hookId: String? = null,
    val hookName: String = "",
    val eventType: String = "",
    val conversationId: String? = null,
    val status: String = "success",
    /** 优先取 log action.message，其次 message action.content，最后回退 hookName */
    val displayMessage: String = ""
)

/** 命令授权级别。始终授权仅对当前服务端会话和主命令名生效。 */
enum class ExecAuthorization(val wireValue: String, val approved: Boolean) {
    Reject("reject", false),
    Once("once", true),
    Always("always", true)
}

internal fun buildExecConfirmationPayload(
    requestId: String,
    authorization: ExecAuthorization,
    sessionId: String
): Map<String, Any> = mapOf(
    "request_id" to requestId,
    "approved" to authorization.approved,
    "permission" to authorization.wireValue,
    "session_id" to sessionId
)

/** 解析服务端 `exec_confirm_request` 事件。缺少 request_id 时拒绝创建请求。 */
internal fun parseExecConfirmationPayload(raw: Any?): ExecConfirmationRequest? {
    if (raw == null) return null
    return try {
        val root = JsonParser.parseString(raw.toString())
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val requestId = obj.get("request_id")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            .orEmpty()
        if (requestId.isBlank()) return null
        ExecConfirmationRequest(
            requestId = requestId,
            command = obj.get("command")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            mainCommand = obj.get("main_command")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                .orEmpty(),
            message = obj.get("message")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            sessionId = obj.get("session_id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        )
    } catch (_: Exception) {
        null
    }
}

/** Socket.IO 连接状态 */
enum class SocketState { Disconnected, Connecting, Connected, Error }

/**
 * 实时消息事件：AI 回复推送或流式分片。
 */
sealed class RealtimeEvent {
    /** 新消息推送（完整 AI 回复或用户消息回显） */
    data class NewMessage(val message: Message) : RealtimeEvent()
    /** AI 流式开始 */
    data class StreamStart(val sessionId: String?) : RealtimeEvent()
    /** AI 流式分片 */
    data class StreamChunk(val chunk: String) : RealtimeEvent()
    /** AI 流式结束（通常已生成完整消息，可刷新列表） */
    data class StreamEnd(val sessionId: String?) : RealtimeEvent()
    /** 非流式完整 AI 响应 */
    data class AiResponse(val message: Message?) : RealtimeEvent()
    /** 消息被过滤 */
    data class Filtered(val message: String?) : RealtimeEvent()
    /** 剧情选项推送（AI 回复完成后服务端推送新选项） */
    data class PlotChoices(val choices: com.google.gson.JsonElement) : RealtimeEvent()
    /** 错误 */
    data class Error(val message: String) : RealtimeEvent()
    /** 本地模式 AI 流式结束时的 token 用量（input/output/total） */
    data class Usage(val inputTokens: Int, val outputTokens: Int, val model: String? = null) : RealtimeEvent()
    /** AI 请求执行非白名单命令，等待用户授权。 */
    data class ExecConfirmationRequired(val request: ExecConfirmationRequest) : RealtimeEvent()
    /** 命令授权结果已由服务端接收。 */
    data class ExecConfirmationResolved(val sessionId: String?, val approved: Boolean) : RealtimeEvent()
    /**
     * 进度卡片更新（agent 模式专用）。
     * - 远程模式：服务端通过 new_message 事件推送 type=thinking_card 的 Message，转换后发出
     * - 本地模式：LocalPipelineCallbacks 的 ProgressReporter 回调直接构造发出
     */
    data class ThinkingCardUpdate(val card: com.nekobot.app.data.model.ThinkingCard) : RealtimeEvent()
    /**
     * Hook 触发通知（成就式弹窗）。
     * - 远程模式：服务端通过 `hook_notification` Socket.IO 事件推送
     * - 本地模式：HookExecutor 在 hook 执行成功后构造发出
     */
    data class HookNotificationEvent(val notification: HookNotification) : RealtimeEvent()
}

/**
 * Socket.IO 客户端：负责实时通信。
 * NekoBot 后端通过 Socket.IO 推送 AI 流式分片与完整消息，
 * 客户端必须 join_session 加入会话 room 才能收到对应会话的消息事件。
 */
class SocketManager(private val prefs: PrefsManager) {

    private val gson = Gson()
    private var socket: Socket? = null

    private val _state = MutableStateFlow(SocketState.Disconnected)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    /** 当前已 join 的会话 id */
    private var joinedSessionId: String? = null

    private val baseUrl: String get() = prefs.serverUrl

    /** 建立连接（若已连接则忽略）。 */
    fun connect() {
        if (socket?.connected() == true) return
        val token = prefs.token ?: run {
            _state.value = SocketState.Error
            return
        }
        _state.value = SocketState.Connecting
        try {
            // 与 Web 端 nbot-shared.js 保持一致：
            //   path: '/socket.io', transports: ['websocket','polling'], auth: { token }
            val uri = baseUrl.trimEnd('/')
            val options = IO.Options.builder()
                .setPath("/socket.io")
                .setTransports(arrayOf(WebSocket.NAME, "polling"))
                .setAuth(mapOf("token" to token))
                .setTimeout(45000)
                .setForceNew(true)
                .setReconnection(true)
                .setReconnectionDelay(1000)
                .setReconnectionDelayMax(10000)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .build()
            val s = IO.socket(uri, options)
            registerListeners(s)
            socket = s
            s.connect()
        } catch (e: Exception) {
            _state.value = SocketState.Error
        }
    }

    private fun registerListeners(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            android.util.Log.i("NekoSocket", "Connected to $baseUrl")
            _state.value = SocketState.Connected
            // 重连后自动重新加入会话 room
            joinedSessionId?.let { joinSession(it) }
        }
        s.on(Socket.EVENT_DISCONNECT) {
            android.util.Log.w("NekoSocket", "Disconnected")
            _state.value = SocketState.Disconnected
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val msg = args.firstOrNull()?.toString() ?: "unknown"
            android.util.Log.e("NekoSocket", "Connect error: $msg")
            _state.value = SocketState.Error
        }

        // 新消息推送
        s.on("new_message") { args -> handleNewMessage(args) }
        // 非流式完整 AI 响应
        s.on("ai_response") { args -> handleAiResponse(args) }
        // 流式
        s.on("ai_stream_start") { args -> handleStreamStart(args) }
        s.on("ai_stream_chunk") { args -> handleStreamChunk(args) }
        s.on("ai_stream_end") { args -> handleStreamEnd(args) }
        // 消息被过滤
        s.on("message_filtered") { args -> handleFiltered(args) }
        // 剧情选项推送
        s.on("plot_choices") { args -> handlePlotChoices(args) }
        // Agent 非白名单命令授权
        s.on("exec_confirm_request") { args -> handleExecConfirmationRequest(args) }
        s.on("exec_confirm_result") { args -> handleExecConfirmationResult(args) }
        // Hook 触发通知（聊天界面成就式弹窗）
        s.on("hook_notification") { args -> handleHookNotification(args) }
        // 通用错误
        s.on("error") { args ->
            val msg = args.firstOrNull()?.toString() ?: "Socket 错误"
            _events.tryEmit(RealtimeEvent.Error(msg))
        }
    }

    /** 加入会话 room，接收该会话的实时消息推送。 */
    fun joinSession(sessionId: String) {
        joinedSessionId = sessionId
        val s = socket
        if (s == null || !s.connected()) {
            // 未连接则先连接，connect 成功后会自动 join
            android.util.Log.w("NekoSocket", "joinSession called but socket not connected, will connect first")
            connect()
            return
        }
        android.util.Log.i("NekoSocket", "Joining room: $sessionId")
        s.emit("join_session", JSONObject(mapOf("session_id" to sessionId)))
    }

    /** 离开会话 room。 */
    fun leaveSession(sessionId: String) {
        if (joinedSessionId == sessionId) joinedSessionId = null
        // 服务端根据当前 Socket SID 查找 room，leave_session 处理器不接收参数。
        socket?.emit("leave_session")
    }

    /**
     * 通过 Socket.IO 发送消息（主聊天入口），触发 AI 生成。
     * 这是 Web 端的聊天方式，服务端会推送 ai_stream_* / new_message。
     */
    fun sendMessage(sessionId: String, content: String) {
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("message", content)
            put("content", content)
        }
        socket?.emit("send_message", payload)
    }

    /**
     * 回传命令授权结果。
     *
     * @return Socket 已连接且事件已提交时返回 true。
     */
    fun respondToExecConfirmation(
        requestId: String,
        authorization: ExecAuthorization,
        sessionId: String
    ): Boolean {
        val s = socket?.takeIf { it.connected() } ?: return false
        val payload = JSONObject(
            buildExecConfirmationPayload(requestId, authorization, sessionId)
        )
        s.emit("confirm_exec", payload)
        return true
    }

    /** 断开连接。 */
    fun disconnect() {
        joinedSessionId = null
        socket?.disconnect()
        socket = null
        _state.value = SocketState.Disconnected
    }

    // ============== 事件解析 ==============

    private fun parseMessage(raw: Any?): Message? {
        return parseRealtimeMessagePayload(gson, raw)
    }

    private fun handleNewMessage(args: Array<Any>) {
        val msg = parseMessage(args.firstOrNull()) ?: return
        // 服务端推送的 thinking_card 消息单独路由到 ThinkingCardUpdate 事件，
        // 避免被当作普通 NewMessage 渲染到聊天列表
        if (msg.isThinkingCard) {
            val card = com.nekobot.app.data.model.ThinkingCard(
                id = msg.id ?: java.util.UUID.randomUUID().toString(),
                content = msg.content.orEmpty(),
                steps = msg.steps ?: emptyList(),
                isComplete = msg.isComplete == true,
                isAgent = msg.isAgent == true,
                timestamp = com.nekobot.app.data.local.LocalRepository.nowIsoStatic(),
                parentMessageId = msg.parentMessageId
            )
            _events.tryEmit(RealtimeEvent.ThinkingCardUpdate(card))
            return
        }
        _events.tryEmit(RealtimeEvent.NewMessage(msg))
    }

    private fun handleAiResponse(args: Array<Any>) {
        val msg = parseMessage(args.firstOrNull())
        _events.tryEmit(RealtimeEvent.AiResponse(msg))
    }

    private fun handleStreamStart(args: Array<Any>) {
        val sid = extractSessionId(args.firstOrNull())
        _events.tryEmit(RealtimeEvent.StreamStart(sid))
    }

    private fun handleStreamChunk(args: Array<Any>) {
        val chunk = extractChunk(args.firstOrNull()) ?: return
        _events.tryEmit(RealtimeEvent.StreamChunk(chunk))
    }

    private fun handleStreamEnd(args: Array<Any>) {
        val sid = extractSessionId(args.firstOrNull())
        _events.tryEmit(RealtimeEvent.StreamEnd(sid))
    }

    private fun handleFiltered(args: Array<Any>) {
        val msg = args.firstOrNull()?.toString()
        _events.tryEmit(RealtimeEvent.Filtered(msg))
    }

    private fun handlePlotChoices(args: Array<Any>) {
        val raw = args.firstOrNull() ?: return
        val el = try { JsonParser.parseString(raw.toString()) } catch (_: Exception) { return }
        _events.tryEmit(RealtimeEvent.PlotChoices(el))
    }

    private fun handleExecConfirmationRequest(args: Array<Any>) {
        val request = parseExecConfirmationPayload(args.firstOrNull()) ?: return
        _events.tryEmit(RealtimeEvent.ExecConfirmationRequired(request))
    }

    private fun handleExecConfirmationResult(args: Array<Any>) {
        val raw = args.firstOrNull() ?: return
        try {
            val root = JsonParser.parseString(raw.toString())
            if (!root.isJsonObject) return
            val obj = root.asJsonObject
            val sessionId = obj.get("session_id")
                ?.takeUnless { it.isJsonNull }
                ?.asString
            val approved = obj.get("approved")
                ?.takeUnless { it.isJsonNull }
                ?.asBoolean == true
            _events.tryEmit(RealtimeEvent.ExecConfirmationResolved(sessionId, approved))
        } catch (_: Exception) {
            // 无效结果事件不应影响聊天状态
        }
    }

    /** 解析服务端 `hook_notification` 事件，转换为 HookNotification 推送到 UI。 */
    private fun handleHookNotification(args: Array<Any>) {
        val raw = args.firstOrNull() ?: return
        try {
            val root = JsonParser.parseString(raw.toString())
            if (!root.isJsonObject) return
            val obj = root.asJsonObject
            val notif = HookNotification(
                hookId = obj.get("hook_id")?.takeUnless { it.isJsonNull }?.asString,
                hookName = obj.get("hook_name")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                eventType = obj.get("event_type")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                conversationId = obj.get("conversation_id")?.takeUnless { it.isJsonNull }?.asString,
                status = obj.get("status")?.takeUnless { it.isJsonNull }?.asString ?: "success",
                displayMessage = obj.get("display_message")?.takeUnless { it.isJsonNull }?.asString
                    ?: obj.get("message")?.takeUnless { it.isJsonNull }?.asString
                    ?: obj.get("hook_name")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            )
            _events.tryEmit(RealtimeEvent.HookNotificationEvent(notif))
        } catch (_: Exception) {
            // hook 通知解析失败不应影响聊天流程
        }
    }

    private fun extractSessionId(raw: Any?): String? {
        if (raw == null) return null
        return try {
            val obj = JSONObject(raw.toString())
            val sid = obj.optString("session_id", "")
            if (sid.isNotEmpty()) sid else obj.optString("sessionId", "").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractChunk(raw: Any?): String? {
        if (raw == null) return null
        return try {
            val s = raw.toString()
            val el = JsonParser.parseString(s)
            when {
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    obj.get("chunk")?.asString
                        ?: obj.get("content")?.asString
                        ?: obj.get("text")?.asString
                        ?: obj.get("delta")?.asString
                        ?: s
                }
                el.isJsonPrimitive -> el.asString
                else -> s
            }
        } catch (e: Exception) {
            raw.toString()
        }
    }
}
