package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.BuiltinTools
import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

internal val localExecutableToolIds = setOf(
    "search_news",
    "get_weather",
    "search_web",
    "browser_use",
    "get_date_time",
    "http_get",
    "exec_command",
    "file_read",
    "file_write",
    "file_edit",
    "read_image",
    "download_file",
    "send_message",
    "get_session_thinking_history",
    "understand_image",
    "workspace_create_file",
    "workspace_read_file",
    "workspace_edit_file",
    "workspace_delete_file",
    "workspace_list_files",
    "workspace_send_file",
    "workspace_parse_file",
    "workspace_extract_epub",
    "workspace_file_info"
)

internal val localSkillToolIds = setOf(
    "skill_list",
    "skill_view",
    "skill_read",
    "skill_get_info"
)

/** 将本地真正可执行的内置工具转换为 OpenAI function-calling 定义。 */
internal fun buildLocalAgentToolDefinitions(): List<Map<String, Any>> {
    val gson = Gson()
    return BuiltinTools.all
        .filter { it.enabled && it.id in localExecutableToolIds }
        .map { spec ->
            @Suppress("UNCHECKED_CAST")
            val parameters = runCatching {
                gson.fromJson(spec.parametersJson, Map::class.java) as Map<String, Any>
            }.getOrDefault(mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to spec.id,
                    "description" to spec.description,
                    "parameters" to parameters
                )
            )
        }
}

/** 与原仓库 skills_tools.py 对齐的只读 Skill 工具。 */
internal fun buildLocalSkillToolDefinitions(): List<Map<String, Any>> {
    fun definition(name: String, description: String, parameters: Map<String, Any>): Map<String, Any> =
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to parameters
            )
        )

    val emptyParameters = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>()
    )
    val skillNameParameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "skill_name" to mapOf("type" to "string", "description" to "Skill 名称或别名")
        ),
        "required" to listOf("skill_name")
    )
    return listOf(
        definition("skill_list", "列出当前已启用的 Skills 及其文件数量。", emptyParameters),
        definition("skill_view", "查看指定 Skill 的文件结构和来源。", skillNameParameters),
        definition(
            "skill_read",
            "读取指定 Skill 中的文本文件。执行技能前应先读取 SKILL.md。",
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "skill_name" to mapOf("type" to "string", "description" to "Skill 名称或别名"),
                    "file_path" to mapOf("type" to "string", "description" to "相对路径，如 SKILL.md、reference.md、scripts/main.py"),
                    "start_line" to mapOf("type" to "integer", "description" to "起始行，从 1 开始"),
                    "end_line" to mapOf("type" to "integer", "description" to "结束行，包含该行")
                ),
                "required" to listOf("skill_name", "file_path")
            )
        ),
        definition("skill_get_info", "获取本地 Skills 存储规范和统计信息。", emptyParameters)
    )
}

/**
 * Android 本地 Agent 工具执行器。
 *
 * 文件工具严格限制在当前会话工作区；命令在应用沙箱内执行，并经过高风险阻断与会话授权。
 */
internal class LocalAgentToolExecutor(
    private val sessionId: String,
    workspaceRoot: File?,
    private val authorizationManager: LocalExecAuthorizationManager,
    private val onConfirmationRequired: (ExecConfirmationRequest) -> Unit,
    private val thinkingHistoryProvider: (Int) -> List<Map<String, Any>>,
    /** 视觉识别函数：传入 imageUrl（http URL 或 data URI）和问题，返回描述文本（非 suspend） */
    private val visionDescriber: ((String, String) -> String)? = null,
    private val generationController: LocalGenerationController = LocalGenerationController(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()
    private val workspace = workspaceRoot?.canonicalFile

    fun execute(toolName: String, args: Map<String, Any>): Map<String, Any> {
        if (generationController.isStopped) return stoppedFailure()
        return try {
            when (toolName) {
                "search_news" -> searchNews(args)
                "get_weather" -> getWeather(args)
                "search_web" -> searchWeb(args)
                "get_date_time" -> getDateTime(args)
                "http_get" -> httpGet(args)
                "exec_command" -> execCommand(args)
                "file_read" -> readWorkspaceFile(args)
                "file_write" -> writeLinuxWorkspaceFile(args)
                "file_edit" -> editLinuxWorkspaceFile(args)
                "read_image" -> understandImage(
                    args + ("image_url" to args.workspacePath())
                )
                "download_file" -> downloadFile(args)
                "send_message" -> sendMessage(args)
                "get_session_thinking_history" -> thinkingHistory(args)
                "understand_image" -> understandImage(args)
                "workspace_create_file", "workspace_edit_file" -> writeWorkspaceFile(args)
                "workspace_read_file", "workspace_parse_file" -> readWorkspaceFile(args)
                "workspace_delete_file" -> deleteWorkspaceFile(args)
                "workspace_list_files" -> listWorkspaceFiles(args)
                "workspace_send_file" -> sendWorkspaceFile(args)
                "workspace_extract_epub" -> extractWorkspaceEpub(args)
                "workspace_file_info" -> workspaceFileInfo(args)
                else -> failure("本地模式不支持工具: $toolName")
            }
        } catch (e: Exception) {
            if (generationController.isStopped) stoppedFailure()
            else failure(e.message ?: "工具执行失败")
        }
    }

    private fun getDateTime(args: Map<String, Any>): Map<String, Any> {
        val requestedZone = args.string("timezone")
        val now = runCatching {
            if (requestedZone.isBlank()) ZonedDateTime.now()
            else ZonedDateTime.now(java.time.ZoneId.of(requestedZone))
        }.getOrElse { return failure("无效时区: $requestedZone") }
        val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return success(
            "date" to now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            "time" to now.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            "weekday" to weekdays[now.dayOfWeek.value - 1],
            "timezone" to now.zone.id,
            "timestamp" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )
    }

    private fun getWeather(args: Map<String, Any>): Map<String, Any> {
        val city = args.string("city").ifBlank { "北京" }
        val encoded = URLEncoder.encode(city, StandardCharsets.UTF_8.name())
        val raw = fetchText("https://wttr.in/$encoded?format=j1")
        @Suppress("UNCHECKED_CAST")
        val data = gson.fromJson(raw, Map::class.java) as? Map<String, Any>
            ?: return failure("天气响应格式错误")
        val current = (data["current_condition"] as? List<*>)
            ?.firstOrNull() as? Map<*, *>
            ?: return failure("未获取到天气信息")
        val description = ((current["lang_zh"] as? List<*>)?.firstOrNull() as? Map<*, *>)
            ?.get("value")
            ?: ((current["weatherDesc"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("value")
            ?: ""
        return success(
            "city" to city,
            "temperature_c" to (current["temp_C"] ?: ""),
            "feels_like_c" to (current["FeelsLikeC"] ?: ""),
            "description" to description,
            "humidity" to (current["humidity"] ?: ""),
            "wind_kmph" to (current["windspeedKmph"] ?: "")
        )
    }

    private fun searchWeb(args: Map<String, Any>): Map<String, Any> {
        val query = args.string("query")
        if (query.isBlank()) return failure("搜索词不能为空")
        // 优先使用 Exa MCP（匿名搜索，无需 API Key）；失败时回退到搜狗搜索
        val exaResult = runCatching { searchWebViaExa(query) }
        val result = exaResult.getOrNull()
        if (result != null && (result["success"] as? Boolean) == true) return result
        // Exa 失败时回退到搜狗，并在 content 中附加失败提示
        val fallback = searchWebViaSogou(query)
        val hint = exaResult.exceptionOrNull()?.message?.take(200) ?: (result?.get("error") as? String)
        if (hint != null) {
            val merged = fallback.toMutableMap()
            merged["content"] = "（Exa 搜索失败，已回退到搜狗：$hint）\n\n" + fallback["content"]
            return merged
        }
        return fallback
    }

    /** 通过 Exa MCP Streamable HTTP 端点调用 web_search_exa 工具（匿名搜索，无需 API Key）。 */
    private fun searchWebViaExa(query: String): Map<String, Any> {
        val baseUrl = "https://mcp.exa.ai/mcp"
        val jsonMediaType = "application/json".toMediaType()

        // 1. Initialize 请求，建立 MCP 会话
        val initPayload = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"nekobot-android","version":"1.0"}}}
        """.trimIndent().toRequestBody(jsonMediaType)
        val initRequest = Request.Builder()
            .url(baseUrl)
            .post(initPayload)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .build()
        var sessionId: String? = null
        val initResult = withHttpResponse(initRequest) { response ->
            // 从响应头获取 Mcp-Session-Id（若存在）
            sessionId = response.header("Mcp-Session-Id")
            parseMcpResponse(response.body?.string().orEmpty())
        }
        val initError = initResult?.get("error")
        if (initError != null) {
            return failure("Exa MCP 初始化失败: $initError")
        }

        // 2. 发送 initialized 通知（按规范需要，但即使省略通常仍可工作）
        runCatching {
            val notifPayload = """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.trimIndent().toRequestBody(jsonMediaType)
            val notifRequest = Request.Builder()
                .url(baseUrl)
                .post(notifPayload)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
                .build()
            withHttpResponse(notifRequest) { /* 忽略响应 */ }
        }

        // 3. 调用 web_search_exa 工具
        val callPayload = gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to 2,
            "method" to "tools/call",
            "params" to mapOf(
                "name" to "web_search_exa",
                "arguments" to mapOf(
                    "query" to query,
                    "numResults" to 5
                )
            )
        ))
        val callRequest = Request.Builder()
            .url(baseUrl)
            .post(callPayload.toRequestBody(jsonMediaType))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .build()
        val callResult = withHttpResponse(callRequest) { response ->
            parseMcpResponse(response.body?.string().orEmpty())
        } ?: return failure("Exa MCP 响应解析失败")

        val callError = callResult.get("error")
        if (callError != null) {
            return failure("Exa MCP 工具调用失败: $callError")
        }

        @Suppress("UNCHECKED_CAST")
        val resultObj = callResult["result"] as? Map<String, Any>
            ?: return failure("Exa MCP 返回结果格式异常")
        @Suppress("UNCHECKED_CAST")
        val contentList = resultObj["content"] as? List<Map<String, Any>>
            ?: return failure("Exa MCP 返回内容为空")

        // 提取文本内容
        val text = contentList.joinToString("\n\n") { item ->
            (item["text"] as? String).orEmpty()
        }.take(12000)

        return success(
            "query" to query,
            "source" to "exa",
            "content" to text,
            "result_count" to contentList.size
        )
    }

    /** 解析 MCP 响应（支持纯 JSON 和 SSE event stream 两种格式）。 */
    private fun parseMcpResponse(raw: String): Map<String, Any>? {
        if (raw.isBlank()) return null
        // 尝试直接解析为 JSON
        runCatching {
            val parsed = gson.fromJson(raw, Map::class.java) as? Map<String, Any>
            if (parsed != null) return parsed
        }
        // 尝试解析 SSE 格式：每行 "data: {...}"
        val dataLines = raw.split("\n")
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("data:")) trimmed.substring(5).trim().takeIf { it.isNotEmpty() }
                else null
            }
        for (line in dataLines) {
            runCatching {
                val parsed = gson.fromJson(line, Map::class.java) as? Map<String, Any>
                if (parsed != null) return parsed
            }
        }
        return null
    }

    private fun searchWebViaSogou(query: String): Map<String, Any> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://www.sogou.com/web?query=$encoded"
        val text = stripMarkup(fetchText(url)).take(12000)
        return success("query" to query, "source_url" to url, "source" to "sogou", "content" to text)
    }

    private fun searchNews(args: Map<String, Any>): Map<String, Any> {
        val query = args.string("query").ifBlank { "热点新闻" }
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://news.google.com/rss/search?q=$encoded&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
        val text = stripMarkup(fetchText(url)).take(12000)
        return success("query" to query, "source_url" to url, "content" to text)
    }

    private fun httpGet(args: Map<String, Any>): Map<String, Any> {
        val url = args.string("url")
        if (url.isBlank()) return failure("URL 不能为空")
        val requestBuilder = Request.Builder().url(url).get()
        @Suppress("UNCHECKED_CAST")
        (args["headers"] as? Map<String, Any>)?.forEach { (name, value) ->
            requestBuilder.header(name, value.toString())
        }
        return withHttpResponse(requestBuilder.build()) { response ->
            val content = response.body?.string().orEmpty().take(20000)
            mapOf(
                "success" to response.isSuccessful,
                "status" to response.code,
                "url" to response.request.url.toString(),
                "content" to content
            )
        }
    }

    private fun execCommand(args: Map<String, Any>): Map<String, Any> {
        val command = args.string("command")
        val timeoutSeconds = args.int("timeout", 30).coerceIn(1, 600)
        val policy = evaluateLocalCommand(command)
        policy.blockedReason?.let {
            return failure(it, "command" to command, "main_command" to policy.mainCommand)
        }
        val activeWorkspace = workspace ?: return failure("本地工作区不可用")
        activeWorkspace.mkdirs()

        var authorization = "whitelist"
        if (policy.requiresAuthorization) {
            when (
                authorizationManager.requestAuthorization(
                    sessionId = sessionId,
                    command = command,
                    mainCommand = policy.mainCommand,
                    onRequest = onConfirmationRequired
                )
            ) {
                ExecAuthorization.Reject -> {
                    if (generationController.isStopped) return stoppedFailure()
                    return failure(
                        "用户拒绝执行命令",
                        "command" to command,
                        "main_command" to policy.mainCommand
                    )
                }
                ExecAuthorization.Once -> authorization = if (authorizationManager.isYoloEnabled(sessionId)) "yolo" else "once"
                ExecAuthorization.Always -> authorization = "always"
            }
        }
        if (generationController.isStopped) return stoppedFailure()

        val context = ServiceContainer.appContext
            ?: return failure("应用上下文不可用，无法启动 Linux 沙盒")
        val result = runCatching {
            LocalLinuxSandboxCoordinator.execute(
                context = context,
                sessionId = sessionId,
                workspace = activeWorkspace,
                command = command,
                timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong()),
                shouldStop = { generationController.isStopped },
            )
        }.getOrElse { error ->
            return failure(
                error.message ?: "Linux 沙盒命令执行失败",
                "command" to command,
                "main_command" to policy.mainCommand,
                "environment" to "alpine-linux",
                "working_directory" to "/workspace",
            )
        }

        if (result.stopped || generationController.isStopped) {
            return stoppedFailure(
                "command" to command,
                "main_command" to policy.mainCommand,
                "output" to result.output,
                "environment" to "alpine-linux",
                "working_directory" to "/workspace",
            )
        }
        return mapOf(
            "success" to (!result.timedOut && result.exitCode == 0),
            "command" to command,
            "main_command" to policy.mainCommand,
            "authorization" to authorization,
            "environment" to "Alpine Linux 3.21.3 (PRoot, arm64-v8a)",
            "working_directory" to "/workspace",
            "workspace_path" to activeWorkspace.absolutePath,
            "exit_code" to result.exitCode,
            "timed_out" to result.timedOut,
            "duration_ms" to result.durationMs,
            "output" to result.output,
        )
    }

    private fun downloadFile(args: Map<String, Any>): Map<String, Any> {
        val url = args.string("url")
        if (url.isBlank()) return failure("URL 不能为空")
        val requestedPath = args.string("save_path")
        val defaultName = runCatching {
            File(URI(url).path).name.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "download_${System.currentTimeMillis()}"
        val target = resolveWorkspacePath(requestedPath.ifBlank { defaultName })
            ?: return failure("保存路径超出会话工作区")
        target.parentFile?.mkdirs()
        withHttpResponse(Request.Builder().url(url).get().build()) { response ->
            if (!response.isSuccessful) return failure("下载失败: HTTP ${response.code}")
            val body = response.body ?: return failure("下载响应为空")
            val contentLength = body.contentLength()
            if (contentLength > 50L * 1024 * 1024) return failure("文件超过 50MB 限制")
            target.outputStream().use { output -> body.byteStream().copyTo(output) }
        }
        return success(
            "path" to relativeWorkspacePath(target),
            "absolute_path" to target.canonicalPath,
            "size" to target.length(),
            "_file_path" to target.canonicalPath,
            "_file_name" to target.name
        )
    }

    private fun sendMessage(args: Map<String, Any>): Map<String, Any> {
        val content = args.string("content")
        if (content.isBlank()) return failure("消息内容不能为空")
        return success("_send_message" to content)
    }

    private fun thinkingHistory(args: Map<String, Any>): Map<String, Any> {
        val limit = args.int("limit", 10).coerceIn(1, 50)
        return success("history" to thinkingHistoryProvider(limit))
    }

    /**
     * 图片理解工具：调用 vision 模型识别图片内容。
     *
     * image_url 支持以下形式：
     * - http/https URL：直接传给 vision API
     * - data URI：直接传给 vision API
     * - 工作区相对路径（如 "photo.png"）：解析为绝对路径后转 base64 data URI
     * - 工作区绝对路径：转 base64 data URI（仅允许工作区内文件）
     */
    private fun understandImage(args: Map<String, Any>): Map<String, Any> {
        val imageUrl = args.string("image_url")
        if (imageUrl.isBlank()) return failure("image_url 不能为空")
        val question = args.string("question").ifBlank { "请描述这张图片的内容。" }
        val describer = visionDescriber ?: run {
            android.util.Log.w("LocalAgentTool", "understand_image: visionDescriber 为 null（未注入视觉识别回调）")
            return failure("视觉识别运行时不可用（未注入视觉识别回调）")
        }

        // 先尝试解析图片路径
        val resolvedUrl = resolveImageUrlForVision(imageUrl)
        if (resolvedUrl == null) {
            android.util.Log.w("LocalAgentTool", "understand_image: 无法解析图片路径: $imageUrl")
            // 列出工作区中可用的图片文件，帮助 AI 修正参数
            val available = listWorkspaceImages()
            val hint = if (available.isEmpty()) {
                "工作区中没有可用图片"
            } else {
                "工作区可用图片: ${available.joinToString(", ")}"
            }
            return failure("无法解析图片路径: $imageUrl（仅支持工作区内文件或 http URL）。$hint")
        }

        android.util.Log.i("LocalAgentTool", "understand_image: 开始识别 | input=${imageUrl.take(100)} | resolved=${if (resolvedUrl.startsWith("data:")) "data:${resolvedUrl.length}字符" else resolvedUrl.take(100)} | question=${question.take(60)}")

        return try {
            val desc = describer.invoke(resolvedUrl, question).trim()
            android.util.Log.i("LocalAgentTool", "understand_image: 识别完成 | 结果长度=${desc.length} | 含失败标记=${desc.contains(com.nekobot.app.data.local.VISION_FAILURE_MARKER)}")
            // 检查是否为失败标记文本，若是则返回失败
            if (desc.isBlank()) {
                failure("视觉模型返回了空描述")
            } else if (desc.contains(com.nekobot.app.data.local.VISION_FAILURE_MARKER)) {
                failure("视觉模型返回失败: $desc")
            } else {
                success(
                    "description" to desc,
                    "image_url" to imageUrl,
                    "resolved_url_kind" to when {
                        resolvedUrl.startsWith("data:") -> "data_uri"
                        resolvedUrl.startsWith("http") -> "http_url"
                        else -> "file"
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalAgentTool", "understand_image: 异常: ${e.message}", e)
            failure("视觉识别失败: ${e.message}")
        }
    }

    /** 列出工作区中可用的图片文件名（用于错误提示）。 */
    private fun listWorkspaceImages(): List<String> {
        val root = workspace ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val imageExt = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg")
        return root.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in imageExt }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /** 将图片输入解析为 vision API 可接受的 URL（http URL 或 data URI）。 */
    private fun resolveImageUrlForVision(input: String): String? {
        // http/https URL 或 data URI 直接返回
        if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("data:")) {
            return input
        }
        // 尝试作为工作区路径解析（支持相对路径、绝对路径、仅文件名）
        val target = resolveWorkspacePath(input, allowRoot = false)
            ?: resolveWorkspacePath(input.substringAfterLast('/'), allowRoot = false)
            ?: resolveWorkspacePath(input.substringAfterLast('\\'), allowRoot = false)
            ?: return null
        if (!target.isFile) return null
        return fileToDataUri(target)
    }

    /** 读取图片文件并转为 base64 data URI。 */
    private fun fileToDataUri(file: File): String? {
        return try {
            if (file.length() > 20L * 1024 * 1024) return null  // 20MB 限制
            val mime = when (file.extension.lowercase(Locale.ROOT)) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                else -> "image/jpeg"
            }
            val base64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            "data:$mime;base64,$base64"
        } catch (e: Exception) { null }
    }

    private fun writeWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.workspacePath())
            ?: return failure("路径为空或超出会话工作区")
        target.parentFile?.mkdirs()
        target.writeText(args.string("content"), Charsets.UTF_8)
        return success(
            "path" to relativeWorkspacePath(target),
            "absolute_path" to target.canonicalPath,
            "size" to target.length()
        )
    }

    private fun writeLinuxWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.workspacePath())
            ?: return failure("路径为空或超出 /workspace")
        if (!args.containsKey("content")) return failure("file_write 缺少 content")
        target.parentFile?.mkdirs()
        val content = args.string("content")
        if (args.boolean("append")) {
            target.appendText(content, Charsets.UTF_8)
        } else {
            target.writeText(content, Charsets.UTF_8)
        }
        return success(
            "path" to "/workspace/${relativeWorkspacePath(target)}",
            "absolute_path" to target.canonicalPath,
            "size" to target.length(),
            "appended" to args.boolean("append")
        )
    }

    private fun editLinuxWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.workspacePath())
            ?: return failure("路径为空或超出 /workspace")
        if (!target.isFile) return failure("文件不存在")
        if (!args.containsKey("old_string")) return failure("file_edit 缺少 old_string")
        if (!args.containsKey("new_string")) return failure("file_edit 缺少 new_string")
        val oldString = args.string("old_string")
        if (oldString.isEmpty()) return failure("old_string 不能为空")
        val newString = args.string("new_string")
        val original = target.readText(Charsets.UTF_8)
        val occurrences = Regex(Regex.escape(oldString)).findAll(original).count()
        if (occurrences == 0) return failure("未找到要替换的原文本")
        val replaceAll = args.boolean("replace_all")
        if (!replaceAll && occurrences > 1) {
            return failure("原文本出现 $occurrences 次；请提供更精确的 old_string 或设置 replace_all=true")
        }
        val updated = if (replaceAll) {
            original.replace(oldString, newString)
        } else {
            original.replaceFirst(oldString, newString)
        }
        target.writeText(updated, Charsets.UTF_8)
        return success(
            "path" to "/workspace/${relativeWorkspacePath(target)}",
            "absolute_path" to target.canonicalPath,
            "replacements" to if (replaceAll) occurrences else 1,
            "size" to target.length()
        )
    }

    private fun readWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.workspacePath())
            ?: return failure("路径为空或超出会话工作区")
        if (!target.isFile) return failure("文件不存在")

        // 参数：max_chars 默认 30000，<=0 视为不限制
        val maxChars = args.int("max_chars", 30000).coerceIn(0, 80_000)
        // 行号参数：1-based，含两端；未指定时覆盖整个文件
        val startLine = args.int("start_line", 1).coerceAtLeast(1)
        val endLine = args.int("end_line", 0) // 0 或负数 → 读到末尾

        // 一次性读全文（UTF-8），按需切片
        val fullText = target.readText(Charsets.UTF_8)
        val totalChars = fullText.length
        val totalLines: Int

        // 统一按行切片：用 \r?\n 切分，每行保留行尾换行符（末行除外）
        val rawLines: List<String> = fullText.split(Regex("\\r?\\n"))
        // 若原文以换行结尾，split 会产生末尾空串，丢弃以保持"行数=换行数+1"的语义
        val allLines: List<String> = when {
            fullText.isEmpty() -> emptyList()
            rawLines.isNotEmpty() && rawLines.last().isEmpty() ->
                rawLines.subList(0, rawLines.size - 1).map { it + "\n" }
            else -> rawLines.mapIndexed { i, line ->
                if (i < rawLines.lastIndex) line + "\n" else line
            }
        }
        totalLines = allLines.size

        // 选取行范围（1-based，含两端；未指定则覆盖整个文件）
        val from = (startLine - 1).coerceIn(0, allLines.size)
        val to = when {
            endLine <= 0 -> allLines.size
            else -> endLine.coerceIn(startLine, allLines.size)
        }
        val sliced: String = if (from >= to) "" else allLines.subList(from, to).joinToString("")

        // 应用字符上限截断
        val truncated: Boolean
        val content: String = if (maxChars > 0 && sliced.length > maxChars) {
            truncated = true
            sliced.take(maxChars)
        } else {
            truncated = false
            sliced
        }

        return success(
            "path" to relativeWorkspacePath(target),
            "absolute_path" to target.canonicalPath,
            "content" to content,
            "truncated" to truncated,
            "total_chars" to totalChars,
            "total_lines" to totalLines,
            "start_line" to startLine,
            "end_line" to if (endLine <= 0) totalLines else endLine.coerceAtMost(totalLines)
        )
    }

    private fun deleteWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.workspacePath())
            ?: return failure("路径为空或超出会话工作区")
        if (!target.exists()) return failure("文件不存在")
        if (target.isDirectory && target.listFiles()?.isNotEmpty() == true) {
            return failure("不允许删除非空目录")
        }
        val relativePath = relativeWorkspacePath(target)
        val absolutePath = target.canonicalPath
        return if (target.delete()) {
            success("path" to relativePath, "absolute_path" to absolutePath)
        }
        else failure("删除失败")
    }

    private fun listWorkspaceFiles(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.workspacePath(), allowRoot = true)
            ?: return failure("路径超出会话工作区")
        if (!target.exists()) target.mkdirs()
        if (!target.isDirectory) return failure("目标不是目录")
        val files = target.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            ?.take(200)
            ?.map {
                mapOf(
                    "name" to it.name,
                    "path" to relativeWorkspacePath(it),
                    "absolute_path" to it.canonicalPath,
                    "directory" to it.isDirectory,
                    "size" to if (it.isFile) it.length() else 0L
                )
            }
            .orEmpty()
        return success(
            "path" to relativeWorkspacePath(target),
            "absolute_path" to target.canonicalPath,
            "files" to files
        )
    }

    private fun sendWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.workspacePath())
            ?: return failure("路径为空或超出会话工作区")
        if (!target.isFile) return failure("文件不存在")
        return success(
            "path" to relativeWorkspacePath(target),
            "absolute_path" to target.canonicalPath,
            "_file_path" to target.canonicalPath,
            "_file_name" to target.name
        )
    }

    private fun extractWorkspaceEpub(args: Map<String, Any>): Map<String, Any> {
        val source = resolveWorkspacePath(args.workspacePath())
            ?: return failure("路径为空或超出会话工作区")
        if (!source.isFile) return failure("EPUB 文件不存在")
        if (!source.extension.equals("epub", ignoreCase = true)) {
            return failure("输入文件必须是 EPUB 格式")
        }

        val defaultOutputPath = relativeWorkspacePath(
            File(source.parentFile, "${source.nameWithoutExtension}.txt")
        )
        val outputPath = args.string("output_path").ifBlank { defaultOutputPath }
        val output = resolveWorkspacePath(outputPath)
            ?: return failure("输出路径为空或超出会话工作区")
        if (!output.extension.equals("txt", ignoreCase = true)) {
            return failure("输出文件必须使用 .txt 扩展名")
        }

        val result = EpubTextExtractor.extract(source, output)
        return success(
            "source_path" to relativeWorkspacePath(source),
            "source_absolute_path" to source.canonicalPath,
            "path" to relativeWorkspacePath(output),
            "absolute_path" to output.canonicalPath,
            "size" to output.length(),
            "chapter_count" to result.chapterCount,
            "character_count" to result.characterCount
        )
    }

    private fun workspaceFileInfo(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.workspacePath())
            ?: return failure("路径为空或超出会话工作区")
        if (!target.exists()) return failure("文件不存在")
        return success(
            "path" to relativeWorkspacePath(target),
            "absolute_path" to target.canonicalPath,
            "name" to target.name,
            "directory" to target.isDirectory,
            "size" to if (target.isFile) target.length() else 0L,
            "last_modified" to target.lastModified()
        )
    }

    private fun resolveWorkspacePath(path: String, allowRoot: Boolean = false): File? {
        val root = workspace ?: return null
        root.mkdirs()
        val normalizedPath = path.trim()
            .replace('\\', '/')
            .removePrefix("/workspace/")
            .let { value -> if (value == "/workspace") "" else value }
        if (normalizedPath.isBlank()) return root.takeIf { allowRoot }
        val target = File(root, normalizedPath).canonicalFile
        val rootPath = root.path
        return target.takeIf {
            it.path == rootPath || it.path.startsWith(rootPath + File.separator)
        }
    }

    private fun relativeWorkspacePath(file: File): String {
        val root = workspace ?: return file.name
        return file.relativeTo(root).path.replace(File.separatorChar, '/').ifBlank { "." }
    }

    private fun fetchText(url: String): String {
        return withHttpResponse(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 NekoBot-Android")
                .get()
                .build()
        ) { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private inline fun <T> withHttpResponse(
        request: Request,
        block: (okhttp3.Response) -> T
    ): T {
        val call = generationController.track(httpClient.newCall(request))
        return try {
            call.execute().use(block)
        } finally {
            generationController.release(call)
        }
    }

    private fun stripMarkup(raw: String): String = raw
        .replace(Regex("""(?is)<(script|style).*?>.*?</\1>"""), " ")
        .replace(Regex("""(?s)<[^>]+>"""), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun Map<String, Any>.string(key: String): String = this[key]?.toString().orEmpty()

    /** 兼容原仓库与不同模型常用的 filename/file_path 参数名。 */
    private fun Map<String, Any>.workspacePath(): String =
        string("path").ifBlank { string("filename") }.ifBlank { string("file_path") }

    private fun Map<String, Any>.int(key: String, default: Int): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

    private fun Map<String, Any>.boolean(key: String, default: Boolean = false): Boolean =
        when (val value = this[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> default
        }

    private fun success(vararg values: Pair<String, Any>): Map<String, Any> =
        buildMap {
            put("success", true)
            values.forEach { (key, value) -> put(key, value) }
        }

    private fun failure(message: String, vararg values: Pair<String, Any>): Map<String, Any> =
        buildMap {
            put("success", false)
            put("error", message)
            values.forEach { (key, value) -> put(key, value) }
        }

    private fun stoppedFailure(vararg values: Pair<String, Any>): Map<String, Any> =
        failure("生成已停止", "stopped" to true, *values)
}
