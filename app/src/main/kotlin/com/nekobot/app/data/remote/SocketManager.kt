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
    /** 错误 */
    data class Error(val message: String) : RealtimeEvent()
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
        socket?.emit("leave_session", sessionId)
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

    /** 断开连接。 */
    fun disconnect() {
        joinedSessionId = null
        socket?.disconnect()
        socket = null
        _state.value = SocketState.Disconnected
    }

    // ============== 事件解析 ==============

    private fun parseMessage(raw: Any?): Message? {
        if (raw == null) return null
        val jsonStr = raw.toString()
        return try {
            val el = JsonParser.parseString(jsonStr)
            if (el.isJsonObject) gson.fromJson(el, Message::class.java)
            else Message(content = jsonStr)
        } catch (e: Exception) {
            Message(content = jsonStr)
        }
    }

    private fun handleNewMessage(args: Array<Any>) {
        val msg = parseMessage(args.firstOrNull()) ?: return
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
