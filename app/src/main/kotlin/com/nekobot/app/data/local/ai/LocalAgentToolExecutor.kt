package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.BuiltinTools
import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import okhttp3.OkHttpClient
import okhttp3.Request
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
    "get_date_time",
    "http_get",
    "exec_command",
    "download_file",
    "send_message",
    "get_session_thinking_history",
    "workspace_create_file",
    "workspace_read_file",
    "workspace_edit_file",
    "workspace_delete_file",
    "workspace_list_files",
    "workspace_send_file",
    "workspace_parse_file",
    "workspace_file_info"
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
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()
    private val workspace = workspaceRoot?.canonicalFile

    fun execute(toolName: String, args: Map<String, Any>): Map<String, Any> = try {
        when (toolName) {
            "search_news" -> searchNews(args)
            "get_weather" -> getWeather(args)
            "search_web" -> searchWeb(args)
            "get_date_time" -> getDateTime(args)
            "http_get" -> httpGet(args)
            "exec_command" -> execCommand(args)
            "download_file" -> downloadFile(args)
            "send_message" -> sendMessage(args)
            "get_session_thinking_history" -> thinkingHistory(args)
            "workspace_create_file", "workspace_edit_file" -> writeWorkspaceFile(args)
            "workspace_read_file", "workspace_parse_file" -> readWorkspaceFile(args)
            "workspace_delete_file" -> deleteWorkspaceFile(args)
            "workspace_list_files" -> listWorkspaceFiles(args)
            "workspace_send_file" -> sendWorkspaceFile(args)
            "workspace_file_info" -> workspaceFileInfo(args)
            else -> failure("本地模式不支持工具: $toolName")
        }
    } catch (e: Exception) {
        failure(e.message ?: "工具执行失败")
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
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://www.sogou.com/web?query=$encoded"
        val text = stripMarkup(fetchText(url)).take(12000)
        return success("query" to query, "source_url" to url, "content" to text)
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
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val content = response.body?.string().orEmpty().take(20000)
            return mapOf(
                "success" to response.isSuccessful,
                "status" to response.code,
                "url" to response.request.url.toString(),
                "content" to content
            )
        }
    }

    private fun execCommand(args: Map<String, Any>): Map<String, Any> {
        val command = args.string("command")
        val timeoutSeconds = args.int("timeout", 30).coerceIn(1, 120)
        val policy = evaluateLocalCommand(command)
        policy.blockedReason?.let {
            return failure(it, "command" to command, "main_command" to policy.mainCommand)
        }
        if (workspace == null) return failure("本地工作区不可用")
        workspace.mkdirs()

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

        val process = ProcessBuilder("sh", "-c", command)
            .directory(workspace)
            .redirectErrorStream(true)
            .start()
        val output = StringBuffer()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (output.length >= 20000) break
                    output.appendLine(line)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        reader.join(1000)
        return mapOf(
            "success" to (completed && process.exitValue() == 0),
            "command" to command,
            "main_command" to policy.mainCommand,
            "authorization" to authorization,
            "exit_code" to if (completed) process.exitValue() else -1,
            "timed_out" to !completed,
            "output" to output.toString().take(20000)
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
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return failure("下载失败: HTTP ${response.code}")
            val body = response.body ?: return failure("下载响应为空")
            val contentLength = body.contentLength()
            if (contentLength > 50L * 1024 * 1024) return failure("文件超过 50MB 限制")
            target.outputStream().use { output -> body.byteStream().copyTo(output) }
        }
        return success(
            "path" to relativeWorkspacePath(target),
            "size" to target.length(),
            "_file_path" to target.absolutePath,
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

    private fun writeWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.string("path"))
            ?: return failure("路径为空或超出会话工作区")
        target.parentFile?.mkdirs()
        target.writeText(args.string("content"), Charsets.UTF_8)
        return success("path" to relativeWorkspacePath(target), "size" to target.length())
    }

    private fun readWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.string("path"))
            ?: return failure("路径为空或超出会话工作区")
        if (!target.isFile) return failure("文件不存在")
        return success(
            "path" to relativeWorkspacePath(target),
            "content" to target.readText(Charsets.UTF_8).take(30000)
        )
    }

    private fun deleteWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.string("path"))
            ?: return failure("路径为空或超出会话工作区")
        if (!target.exists()) return failure("文件不存在")
        if (target.isDirectory && target.listFiles()?.isNotEmpty() == true) {
            return failure("不允许删除非空目录")
        }
        return if (target.delete()) success("path" to relativeWorkspacePath(target))
        else failure("删除失败")
    }

    private fun listWorkspaceFiles(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.string("path"), allowRoot = true)
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
                    "directory" to it.isDirectory,
                    "size" to if (it.isFile) it.length() else 0L
                )
            }
            .orEmpty()
        return success("path" to relativeWorkspacePath(target), "files" to files)
    }

    private fun sendWorkspaceFile(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.string("path"))
            ?: return failure("路径为空或超出会话工作区")
        if (!target.isFile) return failure("文件不存在")
        return success(
            "path" to relativeWorkspacePath(target),
            "_file_path" to target.absolutePath,
            "_file_name" to target.name
        )
    }

    private fun workspaceFileInfo(args: Map<String, Any>): Map<String, Any> {
        val target = resolveWorkspacePath(args.string("path"))
            ?: return failure("路径为空或超出会话工作区")
        if (!target.exists()) return failure("文件不存在")
        return success(
            "path" to relativeWorkspacePath(target),
            "name" to target.name,
            "directory" to target.isDirectory,
            "size" to if (target.isFile) target.length() else 0L,
            "last_modified" to target.lastModified()
        )
    }

    private fun resolveWorkspacePath(path: String, allowRoot: Boolean = false): File? {
        val root = workspace ?: return null
        root.mkdirs()
        if (path.isBlank()) return root.takeIf { allowRoot }
        val target = File(root, path).canonicalFile
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
        httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 NekoBot-Android")
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
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

    private fun Map<String, Any>.int(key: String, default: Int): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

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
}
