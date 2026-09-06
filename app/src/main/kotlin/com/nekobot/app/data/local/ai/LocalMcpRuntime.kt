package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.LocalMcpServerEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private const val MCP_PROTOCOL_VERSION = "2025-11-25"
private const val MCP_CLIENT_NAME = "NekoBot Android"
private const val MCP_CLIENT_VERSION = "0.2.6"
private const val MCP_TOOL_PREFIX = "mcp"
private const val MCP_TOOL_SEPARATOR = "__"

/** HTTP 传输的 User-Agent。裸 okhttp 系 UA 可能被部分服务端/CDN 的机器人防护策略拦截（返回 404/403）。 */
private const val MCP_HTTP_USER_AGENT = "NekoBot-Android/$MCP_CLIENT_VERSION"

/** 出现在 URL 查询串中、需要在错误提示里脱敏的密钥类参数名。 */
private val MCP_SECRET_QUERY_KEYS = setOf(
    "exaapikey", "code", "token", "key", "apikey", "api_key",
    "secret", "access_token", "auth", "authorization"
)

/** MCP 原生工具描述。 */
internal data class LocalMcpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("input_schema", inputSchema.deepCopy())
    }
}

/** 与原仓库 MCPBridge 保持一致：mcp__<server uuid 前 8 位>__<原工具名>。 */
internal fun makeMcpToolName(serverId: String, toolName: String): String {
    val shortId = serverId.replace("-", "").take(8)
    return "$MCP_TOOL_PREFIX$MCP_TOOL_SEPARATOR$shortId$MCP_TOOL_SEPARATOR$toolName"
}

internal fun parseMcpToolName(fullName: String): Pair<String, String>? {
    if (!fullName.startsWith("$MCP_TOOL_PREFIX$MCP_TOOL_SEPARATOR")) return null
    val parts = fullName.split(MCP_TOOL_SEPARATOR, limit = 3)
    if (parts.size != 3 || parts[1].isBlank() || parts[2].isBlank()) return null
    return parts[1] to parts[2]
}

/**
 * 解析 Streamable HTTP 的 JSON 或 SSE 响应。
 *
 * SSE 中可以夹带通知、日志等消息，因此调用方会再按 JSON-RPC id 选择目标响应。
 */
internal fun parseMcpHttpMessages(payload: String): List<JsonObject> {
    val trimmed = payload.trim()
    if (trimmed.isEmpty()) return emptyList()

    val candidates = if (
        trimmed.lineSequence().any { line ->
            line.startsWith("data:") || line.startsWith("event:") || line.startsWith("id:")
        }
    ) {
        val events = mutableListOf<String>()
        val data = mutableListOf<String>()

        fun flushEvent() {
            if (data.isNotEmpty()) {
                events += data.joinToString("\n")
                data.clear()
            }
        }

        trimmed.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) {
                flushEvent()
            } else if (line.startsWith("data:")) {
                data += line.removePrefix("data:").trimStart()
            }
        }
        flushEvent()
        events
    } else {
        listOf(trimmed)
    }

    return candidates.flatMap { raw ->
        val element = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?: return@flatMap emptyList()
        when {
            element.isJsonObject -> listOf(element.asJsonObject)
            element.isJsonArray -> element.asJsonArray.mapNotNull {
                it.takeIf(JsonElement::isJsonObject)?.asJsonObject
            }
            else -> emptyList()
        }
    }
}

/**
 * 本地模式 MCP 连接管理器。
 *
 * 与原仓库 MCPBridge 一样，连接时完成 initialize + notifications/initialized + tools/list，
 * 并缓存工具定义供 Agent function calling 使用。
 */
internal class LocalMcpRuntime(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : Closeable {

    companion object {
        private const val TAG = "LocalMcpRuntime"
    }

    private data class Connection(
        val serverId: String,
        val session: McpTransportSession,
        val tools: List<LocalMcpTool>,
        val protocolVersion: String,
        val serverInfo: JsonObject?
    )

    private data class InitializedSession(
        val tools: List<LocalMcpTool>,
        val protocolVersion: String,
        val serverInfo: JsonObject?
    )

    private val connections = linkedMapOf<String, Connection>()
    @Volatile
    private var activeToolSession: McpTransportSession? = null
    @Volatile
    private var activeToolRequestTag: String? = null

    @Synchronized
    fun connect(server: LocalMcpServerEntity): List<LocalMcpTool> {
        disconnect(server.id)
        val session = createSession(server)
        return try {
            val initialized = initialize(session)
            connections[server.id] = Connection(
                serverId = server.id,
                session = session,
                tools = initialized.tools,
                protocolVersion = initialized.protocolVersion,
                serverInfo = initialized.serverInfo
            )
            runCatching {
                LocalLogger.i(
                    TAG,
                    "MCP 已连接: ${server.name} (${server.transport}), ${initialized.tools.size} 个工具"
                )
            }
            initialized.tools
        } catch (error: Throwable) {
            runCatching { session.close() }
            throw error
        }
    }

    /** 使用独立临时会话验证配置，不改变正式连接状态。 */
    fun test(server: LocalMcpServerEntity): List<LocalMcpTool> {
        val session = createSession(server)
        return try {
            initialize(session).tools
        } finally {
            runCatching { session.close() }
        }
    }

    @Synchronized
    fun disconnect(serverId: String) {
        val connection = connections.remove(serverId) ?: return
        runCatching { connection.session.close() }
            .onFailure { LocalLogger.w(TAG, "断开 MCP 失败: $serverId", it) }
    }

    @Synchronized
    fun isConnected(serverId: String): Boolean = connections.containsKey(serverId)

    @Synchronized
    fun toolCount(serverId: String): Int = connections[serverId]?.tools?.size ?: 0

    @Synchronized
    fun getServerTools(serverId: String): List<LocalMcpTool> =
        connections[serverId]?.tools.orEmpty()

    @Synchronized
    fun getOpenAiToolDefinitions(serverIds: Set<String>? = null): List<Map<String, Any>> {
        return connections.values
            .filter { serverIds == null || it.serverId in serverIds }
            .flatMap { connection ->
                connection.tools.map { tool ->
                    val parameters = tool.inputSchema.deepCopy().apply {
                        if (!has("type")) addProperty("type", "object")
                    }
                    mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to makeMcpToolName(connection.serverId, tool.name),
                            "description" to tool.description,
                            "parameters" to parameters
                        )
                    )
                }
            }
    }

    /**
     * 按 Agent 中的完整工具名调用 MCP 工具。
     * 返回 Map 是为了直接接入现有 AIPipeline 的工具结果消息。
     */
    @Synchronized
    fun executeByFullName(
        fullName: String,
        arguments: Map<String, Any>,
        requestTag: String? = null
    ): Map<String, Any> {
        val parsed = parseMcpToolName(fullName)
            ?: return failure("不是有效的 MCP 工具: $fullName")
        val (serverShort, toolName) = parsed
        val connection = connections.values.firstOrNull {
            it.serverId.replace("-", "").take(8) == serverShort
        } ?: return failure("MCP 服务未连接或已断开: $fullName")

        activeToolSession = connection.session
        activeToolRequestTag = requestTag
        return try {
            val response = connection.session.request(
                method = "tools/call",
                params = JsonObject().apply {
                    addProperty("name", toolName)
                    add("arguments", Gson().toJsonTree(arguments))
                }
            )
            parseToolCallResult(response)
        } catch (error: Throwable) {
            LocalLogger.e(TAG, "MCP 工具调用失败: $fullName", error)
            failure(error.message ?: "MCP 工具调用失败")
        } finally {
            if (activeToolSession === connection.session) {
                activeToolSession = null
                activeToolRequestTag = null
            }
        }
    }

    /** 中断当前 MCP 工具请求，但保留已建立的 MCP 会话供后续继续使用。 */
    fun cancelActiveToolCall(requestTag: String? = null) {
        if (requestTag == null || activeToolRequestTag == requestTag) {
            activeToolSession?.cancelPendingRequests()
        }
    }

    @Synchronized
    override fun close() {
        cancelActiveToolCall()
        connections.keys.toList().forEach(::disconnect)
    }

    private fun createSession(server: LocalMcpServerEntity): McpTransportSession {
        return when (server.transport.lowercase()) {
            "stdio" -> {
                val command = server.command?.trim().orEmpty()
                require(command.isNotEmpty()) { "stdio 模式需要 command 参数" }
                StdioMcpTransportSession(
                    command = command,
                    args = parseStringList(server.argsJson),
                    env = parseStringMap(server.envJson)
                )
            }
            "streamable-http", "http" -> {
                val url = server.url?.trim().orEmpty()
                require(url.isNotEmpty()) { "HTTP 模式需要 url 参数" }
                HttpMcpTransportSession(
                    rawUrl = url,
                    headers = parseStringMap(server.headersJson),
                    client = httpClient
                )
            }
            else -> throw IllegalArgumentException("不支持的 MCP transport: ${server.transport}")
        }
    }

    private fun initialize(session: McpTransportSession): InitializedSession {
        val response = session.request(
            method = "initialize",
            params = JsonObject().apply {
                addProperty("protocolVersion", MCP_PROTOCOL_VERSION)
                add("capabilities", JsonObject())
                add("clientInfo", JsonObject().apply {
                    addProperty("name", MCP_CLIENT_NAME)
                    addProperty("version", MCP_CLIENT_VERSION)
                })
            }
        )
        val result = requireResult(response)
        val negotiatedVersion = result.string("protocolVersion").ifBlank { MCP_PROTOCOL_VERSION }
        session.protocolVersion = negotiatedVersion
        session.notify("notifications/initialized", null)

        val tools = mutableListOf<LocalMcpTool>()
        var cursor: String? = null
        var pageCount = 0
        do {
            val params = JsonObject().apply {
                cursor?.let { addProperty("cursor", it) }
            }
            val page = requireResult(session.request("tools/list", params))
            page.getAsJsonArray("tools")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val tool = element.asJsonObject
                val name = tool.string("name")
                if (name.isBlank()) return@forEach
                val schema = tool.get("inputSchema")
                    ?.takeIf(JsonElement::isJsonObject)
                    ?.asJsonObject
                    ?.deepCopy()
                    ?: JsonObject().apply {
                        addProperty("type", "object")
                        add("properties", JsonObject())
                    }
                tools += LocalMcpTool(
                    name = name,
                    description = tool.string("description"),
                    inputSchema = schema
                )
            }
            cursor = page.get("nextCursor")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf { it.isNotBlank() }
            pageCount++
        } while (cursor != null && pageCount < 100)

        return InitializedSession(
            tools = tools,
            protocolVersion = negotiatedVersion,
            serverInfo = result.get("serverInfo")
                ?.takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
        )
    }

    private fun parseToolCallResult(response: JsonObject): Map<String, Any> {
        val result = requireResult(response)
        val contentText = result.getAsJsonArray("content")
            ?.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull item.toString()
                val obj = item.asJsonObject
                if (obj.string("type") == "text") obj.string("text")
                else obj.toString()
            }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            .orEmpty()

        val structured = result.get("structuredContent")
            ?.takeUnless { it.isJsonNull }
        val value = when {
            structured != null -> jsonToKotlin(structured)
            contentText.isNotBlank() -> {
                val parsed = runCatching { JsonParser.parseString(contentText) }.getOrNull()
                if (parsed != null) jsonToKotlin(parsed) else contentText
            }
            else -> ""
        }

        if (result.get("isError")?.asBoolean == true) {
            return failure(
                when (value) {
                    is String -> value
                    else -> Gson().toJson(value)
                }.ifBlank { "MCP 工具返回错误" }
            )
        }

        @Suppress("UNCHECKED_CAST")
        return when (value) {
            is Map<*, *> -> (value as Map<String, Any>)
            else -> linkedMapOf("success" to true, "result" to (value ?: ""))
        }
    }

    private fun failure(message: String): Map<String, Any> =
        linkedMapOf("success" to false, "error" to message)
}

private interface McpTransportSession : Closeable {
    var protocolVersion: String?
    fun request(method: String, params: JsonObject?): JsonObject
    fun notify(method: String, params: JsonObject?)
    fun cancelPendingRequests()
}

private class HttpMcpTransportSession(
    rawUrl: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient
) : McpTransportSession {

    /**
     * 规范化后的端点 URL：去除首尾空白和尾部斜杠。
     * 部分 Streamable HTTP 服务端按路径精确匹配（如 mcp.exa.ai 的 mcp-handler 只认 /mcp 与 /），
     * 尾部多一个斜杠会直接返回 404 Not Found。
     */
    private val url: String = rawUrl.trim().trimEnd('/')

    /**
     * 404 时的一次性路径修正候选：路径小写化 + 去除不可见字符。
     * 应对误输入的 /MCP 大写、零宽空格等隐藏字符——这些路径在 Cloudflare/边缘层会返回
     * 纯文本 "Not found" 的 404（无 CORS 头），但错误提示里看起来和正确 URL 几乎一样。
     */
    private val pathFallbackUrl: String? = run {
        val q = url.indexOf('?')
        val base = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        val cleaned = base.lowercase().filterNot { ch ->
            ch.isWhitespace() || ch == '\u00A0' || ch.code == 0xFEFF || ch.code in 0x200B..0x200D
        }
        if (cleaned != base) cleaned + query else null
    }

    private val nextId = AtomicLong(1)
    private var sessionId: String? = null
    @Volatile
    private var activeCall: okhttp3.Call? = null
    override var protocolVersion: String? = null

    override fun request(method: String, params: JsonObject?): JsonObject {
        val id = nextId.getAndIncrement()
        val message = jsonRpcMessage(id, method, params)
        return post(message, expectedId = id, expectResponse = true)
            ?: throw IllegalStateException("MCP 服务未返回响应: $method")
    }

    override fun notify(method: String, params: JsonObject?) {
        post(jsonRpcMessage(null, method, params), expectedId = null, expectResponse = false)
    }

    private fun post(
        message: JsonObject,
        expectedId: Long?,
        expectResponse: Boolean
    ): JsonObject? {
        val method = message.string("method")
        val request = Request.Builder()
            .url(url)
            .post(message.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocolVersion ?: MCP_PROTOCOL_VERSION)
            .header("User-Agent", MCP_HTTP_USER_AGENT)
            .apply {
                sessionId?.let { header("Mcp-Session-Id", it) }
            }
            .build()

        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                response.header("Mcp-Session-Id")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sessionId = it }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 404 && sessionId != null) {
                        // 规范（Streamable HTTP §会话管理）：带 Mcp-Session-Id 的请求收到 404 表示
                        // 会话已失效，客户端应丢弃会话 ID，用不带会话的 InitializeRequest 重新建立会话。
                        sessionId = null
                    }
                    val detail = parseMcpHttpMessages(body)
                        .firstOrNull()
                        ?.getAsJsonObject("error")
                        ?.string("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: body.take(500).ifBlank { response.message }
                    // 空响应体的 404/4xx 通常来自网络/DNS 中间层而非 MCP 服务本身（服务端会返回 JSON-RPC 错误体）
                    val hint = if (body.isBlank()) {
                        "（响应体为空——请求可能未到达 MCP 服务，而是被网络/DNS 中间层拦截；" +
                            "请确认手机网络可直连 ${url.substringBefore('?')}，或更换网络/DNS/代理后重试）"
                    } else {
                        ""
                    }
                    val shownUrl = redactUrlQuerySecrets(url)
                    LocalLogger.e(
                        "LocalMcpRuntime",
                        "MCP HTTP 请求失败 [$method] $shownUrl -> ${response.code}: $detail$hint"
                    )
                    throw IllegalStateException(
                        "MCP HTTP ${response.code} [$method] $shownUrl: $detail$hint"
                    )
                }
                if (!expectResponse || response.code == 202) return null

                val messages = parseMcpHttpMessages(body)
                return messages.firstOrNull {
                    expectedId == null || jsonRpcIdMatches(it.get("id"), expectedId)
                } ?: throw IllegalStateException("MCP HTTP 响应中缺少请求 $expectedId 的 JSON-RPC 结果")
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    override fun cancelPendingRequests() {
        activeCall?.cancel()
    }

    override fun close() {
        cancelPendingRequests()
        val activeSessionId = sessionId ?: return
        val request = Request.Builder()
            .url(url)
            .delete()
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Session-Id", activeSessionId)
            .header("MCP-Protocol-Version", protocolVersion ?: MCP_PROTOCOL_VERSION)
            .header("User-Agent", MCP_HTTP_USER_AGENT)
            .build()
        runCatching { client.newCall(request).execute().close() }
        sessionId = null
    }
}

private class StdioMcpTransportSession(
    command: String,
    args: List<String>,
    env: Map<String, String>
) : McpTransportSession {

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val writeLock = Any()
    private val process: Process
    private val writer: BufferedWriter
    @Volatile
    private var closed = false
    override var protocolVersion: String? = null

    init {
        val processBuilder = ProcessBuilder(listOf(command) + args)
        if (env.isNotEmpty()) processBuilder.environment().putAll(env)
        process = processBuilder.start()
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

        thread(name = "local-mcp-stdio-reader", isDaemon = true) {
            val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            try {
                while (!closed) {
                    val line = reader.readLine() ?: break
                    val message = runCatching { JsonParser.parseString(line) }
                        .getOrNull()
                        ?.takeIf(JsonElement::isJsonObject)
                        ?.asJsonObject
                        ?: continue
                    val id = jsonRpcIdKey(message.get("id")) ?: continue
                    pending.remove(id)?.complete(message)
                }
            } catch (error: Throwable) {
                if (!closed) completePendingExceptionally(error)
            } finally {
                if (!closed) {
                    completePendingExceptionally(
                        IllegalStateException("MCP stdio 进程已退出，exit=${runCatching { process.exitValue() }.getOrNull()}")
                    )
                }
            }
        }

        thread(name = "local-mcp-stdio-stderr", isDaemon = true) {
            val stderr = BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8))
            runCatching {
                while (!closed) {
                    val line = stderr.readLine() ?: break
                    android.util.Log.d("LocalMcpStdio", "MCP stderr emitted (${line.length} characters)")
                }
            }
        }
    }

    override fun request(method: String, params: JsonObject?): JsonObject {
        check(!closed) { "MCP stdio 已关闭" }
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id.toString()] = future
        return try {
            write(jsonRpcMessage(id, method, params))
            future.get(90, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            pending.remove(id.toString())
            val cause = error.cause ?: error
            throw IllegalStateException(cause.message ?: "MCP stdio 请求失败: $method", cause)
        }
    }

    override fun notify(method: String, params: JsonObject?) {
        check(!closed) { "MCP stdio 已关闭" }
        write(jsonRpcMessage(null, method, params))
    }

    override fun cancelPendingRequests() {
        completePendingExceptionally(IllegalStateException("MCP 请求已停止"))
    }

    private fun write(message: JsonObject) {
        synchronized(writeLock) {
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPendingRequests()
        runCatching { writer.close() }
        runCatching { process.destroy() }
        if (runCatching { process.isAlive }.getOrDefault(false)) {
            runCatching { process.destroyForcibly() }
        }
    }

    private fun completePendingExceptionally(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
}

private fun jsonRpcMessage(id: Long?, method: String, params: JsonObject?): JsonObject =
    JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        id?.let { addProperty("id", it) }
        addProperty("method", method)
        params?.let { add("params", it) }
    }

/** 错误提示里展示完整 URL，但对查询串中的密钥类参数值脱敏（如 ?exaApiKey=xxx / ?code=xxx）。 */
private fun redactUrlQuerySecrets(raw: String): String {
    val qIndex = raw.indexOf('?')
    if (qIndex < 0) return raw
    val base = raw.substring(0, qIndex)
    val query = raw.substring(qIndex + 1)
    if (query.isBlank()) return raw
    val redacted = query.split('&').joinToString("&") { pair ->
        val eq = pair.indexOf('=')
        if (eq > 0) {
            val key = pair.substring(0, eq).lowercase()
            val value = pair.substring(eq + 1)
            if (value.isNotBlank() && key in MCP_SECRET_QUERY_KEYS) "$key=***" else pair
        } else {
            pair
        }
    }
    return "$base?$redacted"
}

private fun requireResult(response: JsonObject): JsonObject {
    response.get("error")
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject
        ?.let { error ->
            val code = error.get("code")?.asInt
            val message = error.string("message").ifBlank { "未知 JSON-RPC 错误" }
            val data = error.get("data")?.toString()?.takeIf { it.isNotBlank() }
            throw IllegalStateException(
                buildString {
                    append("MCP JSON-RPC")
                    code?.let { append(" $it") }
                    append(": ")
                    append(message)
                    data?.let { append(" ($it)") }
                }
            )
        }
    return response.get("result")
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject
        ?: throw IllegalStateException("MCP JSON-RPC 响应缺少 result")
}

private fun JsonObject.string(name: String): String =
    get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        .orEmpty()

private fun jsonRpcIdMatches(element: JsonElement?, expected: Long): Boolean =
    jsonRpcIdKey(element) == expected.toString()

private fun jsonRpcIdKey(element: JsonElement?): String? {
    if (element == null || !element.isJsonPrimitive) return null
    val value = element.asJsonPrimitive
    return when {
        value.isNumber -> runCatching { value.asLong.toString() }.getOrNull()
        value.isString -> value.asString
        else -> null
    }
}

private fun parseStringList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        JsonParser.parseString(raw).asJsonArray.mapNotNull {
            it.takeIf(JsonElement::isJsonPrimitive)?.asString
        }
    }.getOrDefault(emptyList())
}

private fun parseStringMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        JsonParser.parseString(raw).asJsonObject.entrySet().mapNotNull { (key, value) ->
            value.takeIf(JsonElement::isJsonPrimitive)?.asString?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}

private fun jsonToKotlin(element: JsonElement): Any? =
    Gson().fromJson(element, Any::class.java)
