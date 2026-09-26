package com.nekobot.app.data.local.plugin

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.LocalCommandProgressReporter
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.model.AgentTodo
import com.nekobot.app.data.model.AiConfig
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.ThinkingStep
import com.nekobot.app.data.model.WorldBook
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 插件 API 调用失败；[code] 供插件 JS 分支处理（如 permission_denied）。 */
class PluginApiException(message: String, val code: String = "api_error") : IllegalStateException(message)

/**
 * 插件 AI 调用的频率与用量配额（内存态，进程重启后重置）。
 *
 * 单次调用先占一个调用名额（失败也计数，防止失败重试打爆队列），
 * 调用完成后按实际 token 记账；两项都超限时拒绝并给出可读原因。
 */
internal class PluginAiQuota(
    private val maxCallsPerMinute: Int = MAX_CALLS_PER_MINUTE,
    private val maxTokensPerHour: Int = MAX_TOKENS_PER_HOUR,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val callTimestamps = mutableMapOf<String, ArrayDeque<Long>>()
    private val tokenUsage = mutableMapOf<String, ArrayDeque<Pair<Long, Int>>>()

    @Synchronized
    fun ensureCallAllowed(pluginId: String) {
        val now = nowMillis()
        val calls = callTimestamps.getOrPut(pluginId) { ArrayDeque() }
        while (calls.isNotEmpty() && now - calls.first() >= MINUTE_MS) calls.removeFirst()
        if (calls.size >= maxCallsPerMinute) {
            throw PluginApiException(
                "AI 调用过于频繁（每分钟最多 $maxCallsPerMinute 次），请稍后再试",
                "ai_rate_limited"
            )
        }
        val tokens = tokenUsage.getOrPut(pluginId) { ArrayDeque() }
        while (tokens.isNotEmpty() && now - tokens.first().first >= HOUR_MS) tokens.removeFirst()
        val used = tokens.sumOf { it.second }
        if (used >= maxTokensPerHour) {
            throw PluginApiException(
                "插件 AI 用量已达每小时上限（$maxTokensPerHour tokens），请稍后再试",
                "ai_budget_exceeded"
            )
        }
        calls.addLast(now)
    }

    @Synchronized
    fun recordTokens(pluginId: String, tokens: Int) {
        if (tokens <= 0) return
        tokenUsage.getOrPut(pluginId) { ArrayDeque() }.addLast(nowMillis() to tokens)
    }

    companion object {
        const val MAX_CALLS_PER_MINUTE = 10
        const val MAX_TOKENS_PER_HOUR = 200_000
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
    }
}

/**
 * 插件宿主 API 的统一分派器。
 *
 * 命令运行时（`ctx.api.*`）与插件页面（`host.*`）共用同一实现：
 * 同一套权限校验、同一套网络总开关、同一套存储隔离。
 * 命令侧沿用旧 API 名称（[LEGACY_API_NAMES]），页面侧使用 `host.*` 名称。
 */
internal class PluginApiDispatcher(
    private val appContext: Context,
    private val storage: SharedPreferences,
    private val grants: PluginGrants?,
    private val repositoryProvider: () -> LocalRepository?,
    private val memoryProvider: () -> String,
    /** 写入全局 Agent 记忆：`(内容, 是否追加, 待替换文本) -> 写入后的字符数`。 */
    private val memoryWriter: (String, Boolean, String?) -> Int,
    private val networkAllowed: () -> Boolean,
    private val appVersion: String,
    private val apiVersion: Int = PluginManifestValidator.CURRENT_API_VERSION,
    /** 插件私有文件目录；页面文件上传与 files.* API 共用同一实例。 */
    private val fileStore: PluginFileStore = PluginFileStore(appContext)
) {
    private val gson = Gson()
    private val aiQuota = PluginAiQuota()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 一次 API 调用的上下文。
     *
     * @param sessionId 命令运行时为当前会话；页面仅当从会话上下文打开时才有值。
     * @param isPage 页面调用返回字段裁剪后的数据；命令调用保持既有原始对象（存量零回归）。
     * @param openPage 页面内切换页面（`host.ui.openPage`）；命令运行时为 null。
     * @param dialogs 原生弹窗能力（`host.ui.alert/confirm/prompt/select`）；命令运行时为 null。
     */
    data class CallContext(
        val plugin: InstalledPlugin,
        val sessionId: String?,
        val progressReporter: LocalCommandProgressReporter? = null,
        val closePage: (() -> Unit)? = null,
        val isPage: Boolean = false,
        /** 命令运行时传入当前 LocalRepository，避免依赖全局单例。 */
        val repositoryOverride: LocalRepository? = null,
        val openPage: ((pageId: String, args: String?) -> Boolean)? = null,
        val dialogs: PluginPageDialogHost? = null
    )

    suspend fun dispatch(context: CallContext, name: String, payloadJson: String): Any? {
        val payload = runCatching {
            JsonParser.parseString(payloadJson).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: JsonObject()
        val canonical = canonicalName(name)
        val permission = permissionFor(canonical)
        if (permission != null) {
            checkPluginPermission(context.plugin, permission, grants?.granted(context.plugin.id))
        }
        return when (canonical) {
            "system.info" -> systemInfo()
            "log" -> {
                android.util.Log.i(
                    "NekoPlugin",
                    "[${context.plugin.id}][${payload.string("level").take(16).ifBlank { "info" }}] " +
                        payload.string("message").take(MAX_LOG_CHARS)
                )
                true
            }
            "ui.close" -> {
                val close = context.closePage
                    ?: throw PluginApiException("当前环境不支持关闭页面", "unavailable")
                close()
                true
            }
            "ui.toast" -> {
                val message = payload.string("message").take(MAX_TOAST_CHARS)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
                true
            }
            "ui.render" -> {
                val template = payload.string("template")
                if (template.isBlank()) throw PluginApiException("ui.render 需要 template", "invalid_argument")
                val data = payload.get("data")?.let(::plainValue)
                try {
                    PluginTemplateRenderer.render(template, data)
                } catch (error: IllegalArgumentException) {
                    throw PluginApiException("模板渲染失败：${error.message ?: "语法错误"}", "invalid_argument")
                }
            }
            "ui.openPage" -> {
                val pageId = payload.string("pageId").ifBlank { payload.string("id") }.trim()
                if (pageId.isBlank()) {
                    throw PluginApiException("ui.openPage 需要 pageId", "invalid_argument")
                }
                val opener = context.openPage
                    ?: throw PluginApiException("当前环境不支持切换页面", "unavailable")
                val argsText = payload.string("args").take(MAX_LAUNCH_ARGS_CHARS).ifBlank { null }
                if (!opener(pageId, argsText)) {
                    throw PluginApiException("页面不存在或当前无法切换：$pageId", "not_found")
                }
                mapOf("pageId" to pageId, "opened" to true)
            }
            "ui.alert", "ui.confirm", "ui.prompt", "ui.select" -> showDialog(context, canonical, payload)
            "storage.get" -> {
                val key = storageKey(context.plugin.id, payload.string("key"))
                storage.getString(key, null)?.let { raw ->
                    runCatching { JsonParser.parseString(raw) }.getOrNull()
                } ?: JsonNull.INSTANCE
            }
            "storage.set" -> {
                val key = storageKey(context.plugin.id, payload.string("key"))
                val value = payload.get("value") ?: JsonNull.INSTANCE
                storage.edit().putString(key, value.toString()).apply()
                true
            }
            "storage.remove" -> {
                storage.edit().remove(storageKey(context.plugin.id, payload.string("key"))).apply()
                true
            }
            "storage.list" -> {
                val prefix = "${context.plugin.id}:"
                JsonObject().apply {
                    storage.all
                        .filterKeys { it.startsWith(prefix) }
                        .forEach { (key, raw) ->
                            if (raw is String) {
                                add(
                                    key.removePrefix(prefix),
                                    runCatching { JsonParser.parseString(raw) }.getOrDefault(JsonNull.INSTANCE)
                                )
                            }
                        }
                }
            }
            "chat.current" -> {
                val sessionId = context.sessionId?.takeIf { it.isNotBlank() }
                    ?: return JsonNull.INSTANCE
                val session = repository(context).getSession(sessionId) ?: return JsonNull.INSTANCE
                if (context.isPage) sessionSummary(session) else session
            }
            "chat.context" -> sessionContextUsage(context, payload)
            "chat.session.config" -> sessionConfig(context, payload)
            "chat.prompt.stack" -> sessionPromptStack(context, payload)
            "chat.tool.calls" -> sessionToolCalls(context, payload)
            "chat.sessions.list" -> {
                val sessions = repository(context).listSessions().take(MAX_SESSION_LIST)
                sessions.map { if (context.isPage) sessionSummary(it) else it }
            }
            "chat.sessions.get" -> {
                val id = payload.string("id").ifBlank { context.sessionId.orEmpty() }
                if (id.isBlank()) throw PluginApiException("缺少会话 id", "invalid_argument")
                repository(context).getSession(id)?.let { if (context.isPage) sessionSummary(it) else it }
                    ?: JsonNull.INSTANCE
            }
            "chat.messages.list" -> {
                val legacy = name == "get_messages"
                val defaultLimit = if (legacy) LEGACY_MESSAGE_DEFAULT_LIMIT else MESSAGE_DEFAULT_LIMIT
                val maxLimit = if (legacy) LEGACY_MESSAGE_MAX_LIMIT else MESSAGE_MAX_LIMIT
                val sessionId = payload.string("sessionId").ifBlank { context.sessionId.orEmpty() }
                if (sessionId.isBlank()) throw PluginApiException("缺少会话 id", "invalid_argument")
                val limit = resolveLimit(payload, defaultLimit, maxLimit)
                val messages = repository(context).listMessages(sessionId).takeLast(limit)
                messages.map { if (context.isPage) messageSummary(it) else it }
            }
            "chat.messages.append" -> {
                val sessionId = payload.string("sessionId").ifBlank { context.sessionId.orEmpty() }
                if (sessionId.isBlank()) {
                    throw PluginApiException("缺少会话 id（可在调用时传入 sessionId）", "invalid_argument")
                }
                val role = payload.string("role").trim().lowercase().ifBlank { "user" }
                if (role !in CHAT_WRITE_ROLES) {
                    throw PluginApiException("role 只支持 user / assistant", "invalid_argument")
                }
                val content = payload.string("content")
                if (content.isBlank()) throw PluginApiException("消息内容不能为空", "invalid_argument")
                if (content.length > MAX_CHAT_WRITE_CHARS) {
                    throw PluginApiException("消息最多 $MAX_CHAT_WRITE_CHARS 个字符", "invalid_argument")
                }
                val message = try {
                    repository(context).pluginAppendChatMessage(
                        sessionId = sessionId,
                        role = role,
                        content = content,
                        pluginId = context.plugin.id
                    )
                } catch (error: PluginApiException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    throw PluginApiException(error.message ?: "会话不存在", "not_found")
                } catch (error: Exception) {
                    throw PluginApiException("写入消息失败：${error.message ?: "未知错误"}", "chat_write_failed")
                }
                mapOf(
                    "id" to message.id,
                    "sessionId" to sessionId,
                    "role" to role,
                    "createdAt" to message.createdAt
                )
            }
            "chat.send" -> {
                val sessionId = payload.string("sessionId").ifBlank { context.sessionId.orEmpty() }
                if (sessionId.isBlank()) {
                    throw PluginApiException("缺少会话 id（可在调用时传入 sessionId）", "invalid_argument")
                }
                val content = payload.string("content")
                if (content.isBlank()) throw PluginApiException("消息内容不能为空", "invalid_argument")
                if (content.length > MAX_CHAT_WRITE_CHARS) {
                    throw PluginApiException("消息最多 $MAX_CHAT_WRITE_CHARS 个字符", "invalid_argument")
                }
                try {
                    repository(context).pluginSendChatMessage(sessionId, content, context.plugin.id)
                } catch (error: PluginApiException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    throw PluginApiException(error.message ?: "会话不存在", "not_found")
                } catch (error: Exception) {
                    throw PluginApiException(
                        "发送失败：${error.message ?: "未知错误"}",
                        "chat_send_failed"
                    )
                }
            }
            "chat.sessions.create" -> {
                val mode = payload.string("sessionMode").trim().lowercase().ifBlank { "character" }
                if (mode !in SESSION_MODES) {
                    throw PluginApiException("sessionMode 只支持 ${SESSION_MODES.joinToString("/")}", "invalid_argument")
                }
                val characterId = payload.string("characterId").takeIf { it.isNotBlank() }
                if (characterId != null && repository(context).getCharacter(characterId) == null) {
                    throw PluginApiException("角色不存在：$characterId", "not_found")
                }
                val request = CreateSessionRequest(
                    name = payload.string("name").trim().take(MAX_CHARACTER_NAME_CHARS)
                        .takeIf { it.isNotBlank() },
                    sessionMode = mode,
                    characterId = characterId,
                    characterIds = payload.getAsJsonArray("characterIds")
                        ?.mapNotNull { element ->
                            element?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        }
                        ?.take(MAX_GROUP_CHARACTERS),
                    systemPrompt = payload.string("systemPrompt").take(MAX_CHARACTER_TEXT_CHARS)
                        .takeIf { it.isNotBlank() },
                    firstMessage = payload.string("firstMessage").take(MAX_CHARACTER_TEXT_CHARS)
                        .takeIf { it.isNotBlank() },
                    scenario = payload.string("scenario").take(MAX_CHARACTER_TEXT_CHARS)
                        .takeIf { it.isNotBlank() },
                    senderName = payload.string("senderName").trim().take(MAX_CHARACTER_NAME_CHARS)
                        .takeIf { it.isNotBlank() },
                    tags = payload.getAsJsonArray("tags")
                        ?.mapNotNull { element ->
                            element?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        }
                        ?.take(MAX_CHARACTER_TAGS),
                    userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() }
                )
                val session = try {
                    repository(context).createSession(request)
                } catch (error: PluginApiException) {
                    throw error
                } catch (error: Exception) {
                    throw PluginApiException(
                        "创建会话失败：${error.message ?: "未知错误"}",
                        "session_create_failed"
                    )
                }
                mapOf(
                    "id" to session.id,
                    "name" to session.displayName,
                    "sessionMode" to session.sessionMode,
                    "characterId" to session.characterId
                )
            }
            "chat.sessions.switch" -> {
                val id = payload.string("id").ifBlank { payload.string("sessionId") }
                if (id.isBlank()) throw PluginApiException("缺少会话 id", "invalid_argument")
                val session = repository(context).getSession(id)
                    ?: throw PluginApiException("会话不存在：$id", "not_found")
                // 交给 UI 导航（NavGraph 观察 pendingSessionId 后跳转到该会话）
                ServiceContainer.setPendingSessionId(id)
                mapOf("id" to session.id, "name" to session.displayName, "switched" to true)
            }
            "characters.list" -> repository(context).listCharacters()
                .take(MAX_CHARACTER_LIST)
                .map(::characterSummary)
            "characters.get" -> {
                val id = payload.string("id")
                if (id.isBlank()) throw PluginApiException("缺少角色 id", "invalid_argument")
                repository(context).getCharacter(id)?.let(::characterDetail) ?: JsonNull.INSTANCE
            }
            "characters.create", "characters.update" -> {
                val created = canonical == "characters.create"
                val base = if (created) {
                    null
                } else {
                    val id = payload.string("id")
                    if (id.isBlank()) throw PluginApiException("缺少角色 id", "invalid_argument")
                    repository(context).getCharacter(id)
                        ?: throw PluginApiException("角色不存在：$id", "not_found")
                }
                val preset = characterPresetFromPayload(payload, base)
                val saved = try {
                    repository(context).upsertCharacter(preset)
                } catch (error: PluginApiException) {
                    throw error
                } catch (error: Exception) {
                    throw PluginApiException(
                        "写入角色卡失败：${error.message ?: "未知错误"}",
                        "characters_write_failed"
                    )
                }
                mapOf(
                    "id" to saved.id,
                    "name" to saved.displayName,
                    "updatedAt" to saved.updatedAt
                )
            }
            "worldbooks.list" -> repository(context).listWorldBooks()
                .take(MAX_WORLDBOOK_LIST)
                .map(::worldBookSummary)
            "worldbooks.get" -> {
                val id = payload.string("id")
                if (id.isBlank()) throw PluginApiException("缺少世界书 id", "invalid_argument")
                repository(context).getWorldBook(id)?.let(::worldBookDetail) ?: JsonNull.INSTANCE
            }
            "memory.read" -> {
                val content = runCatching { memoryProvider() }.getOrDefault("")
                mapOf("content" to content.take(MAX_MEMORY_CHARS), "charCount" to content.length)
            }
            "memory.write", "memory.append", "memory.edit" -> writeMemory(canonical, payload)
            "ai.complete" -> {
                requireNetworkAllowed()
                aiQuota.ensureCallAllowed(context.plugin.id)
                val messages = parseAiMessages(payload)
                val maxTokens = resolveMaxTokens(payload)
                val temperature = resolveTemperature(payload)
                val completion = try {
                    repository(context).pluginAiComplete(
                        messages = messages,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        pluginId = context.plugin.id,
                        sessionId = context.sessionId
                    )
                } catch (error: PluginApiException) {
                    throw error
                } catch (error: Exception) {
                    throw PluginApiException(
                        "AI 调用失败：${error.message ?: "未知错误"}",
                        "ai_failed"
                    )
                }
                aiQuota.recordTokens(
                    context.plugin.id,
                    completion.inputTokens + completion.outputTokens
                )
                mapOf(
                    "content" to completion.content,
                    "model" to completion.modelName,
                    "usage" to mapOf(
                        "input" to completion.inputTokens,
                        "output" to completion.outputTokens
                    )
                )
            }
            "http.get" -> performHttpRequest(payload, isPost = false)
            "http.post" -> performHttpRequest(payload, isPost = true)
            "workspace.save" -> pluginWorkspaceSave(context, payload)
            "workspace.list" -> pluginWorkspaceList(context, payload)
            "workspace.read" -> pluginWorkspaceRead(context, payload)
            "workspace.delete" -> pluginWorkspaceDelete(context, payload)
            "files.list" -> pluginFilesList(context)
            "files.read" -> pluginFilesRead(context, payload)
            "files.delete" -> pluginFilesDelete(context, payload)
            "progress.update" -> {
                // 没有关联的用户消息时（如 plugin_use 的 execute 测试、无会话上下文的页面）
                // 静默忽略：这是宿主环境的差异，不该让插件调用本身失败。
                val reporter = context.progressReporter ?: return false
                val content = payload.string("content").take(MAX_PROGRESS_CONTENT_CHARS)
                val steps = parseProgressSteps(payload.getAsJsonArray("steps"))
                val progress = payload.get("progress")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asInt }.getOrNull() }
                    ?.coerceIn(0, 100)
                    ?: 0
                reporter.update(
                    content = content,
                    progress = progress,
                    steps = steps,
                    isComplete = payload.boolean("complete", false),
                    // 插件自行决定上报节奏，宿主不再按百分比二次合并掉它的中间态。
                    force = payload.boolean("force", false)
                )
                true
            }
            else -> throw PluginApiException("未知插件 API：$name", "unknown_api")
        }
    }

    private fun repository(context: CallContext): LocalRepository =
        context.repositoryOverride
            ?: repositoryProvider()
            ?: throw PluginApiException("本地数据不可用", "unavailable")

    /**
     * `host.ui.alert/confirm/prompt/select`：转交页面宿主展示原生弹窗并等待结果。
     *
     * alert 恒为 true；confirm 返回是否确认；prompt 取消返回 null、否则返回文本；
     * select 取消返回 null、否则返回 `{index, value, label}`。
     */
    private suspend fun showDialog(context: CallContext, name: String, payload: JsonObject): Any? {
        val dialogs = context.dialogs
            ?: throw PluginApiException("当前环境不支持原生弹窗", "unavailable")
        val title = payload.string("title").take(MAX_DIALOG_TITLE_CHARS).ifBlank { context.plugin.name }
        val message = payload.string("message").take(MAX_DIALOG_MESSAGE_CHARS)
        if (name != "ui.select" && message.isBlank()) {
            throw PluginApiException("$name 需要 message", "invalid_argument")
        }
        val request = when (name) {
            "ui.alert" -> PluginPageDialog(kind = PluginPageDialog.Kind.ALERT, title = title, message = message)
            "ui.confirm" -> PluginPageDialog(kind = PluginPageDialog.Kind.CONFIRM, title = title, message = message)
            "ui.prompt" -> PluginPageDialog(
                kind = PluginPageDialog.Kind.PROMPT,
                title = title,
                message = message,
                defaultValue = payload.string("value").ifBlank { payload.string("defaultValue") }
                    .take(MAX_DIALOG_DEFAULT_CHARS)
            )
            else -> {
                val options = PluginDialogPayloads.parseOptions(payload)
                PluginPageDialog(
                    kind = PluginPageDialog.Kind.SELECT,
                    title = title,
                    message = message,
                    options = options,
                    selectedIndex = PluginDialogPayloads.resolveSelectedIndex(payload, options)
                )
            }
        }
        val result = dialogs.showDialog(request)
        return when (name) {
            "ui.alert" -> true
            "ui.confirm" -> result.confirmed
            "ui.prompt" -> if (result.confirmed) result.text else JsonNull.INSTANCE
            else -> {
                val option = request.options.getOrNull(result.selectedIndex)
                if (result.confirmed && option != null) {
                    mapOf(
                        "index" to result.selectedIndex,
                        "value" to option.value,
                        "label" to option.label
                    )
                } else {
                    JsonNull.INSTANCE
                }
            }
        }
    }

    // ---- 工作区文件：插件专属文件夹（有会话 → 会话工作区，无会话 → 共享工作区） ----

    /**
     * 保存插件生成的内容。
     *
     * 有会话上下文（命令、从会话打开的页面、消息钩子）时写入会话工作区
     * `plugins/<插件id>/`；没有会话（扩展页、桌面小组件等入口）时写入共享工作区
     * `plugins/<插件id>/`。返回值里的 `file_reference` 可直接用于聊天文件卡片标记。
     */
    private suspend fun pluginWorkspaceSave(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val path = payload.workspacePath()
        val content = payload.string("content")
        if (content.isEmpty()) throw PluginApiException("workspace.save 需要 content", "invalid_argument")
        if (content.length > MAX_WORKSPACE_SAVE_CHARS) {
            throw PluginApiException("内容最多 $MAX_WORKSPACE_SAVE_CHARS 个字符", "invalid_argument")
        }
        val result = pluginWorkspaceResult(
            repository(context).savePluginWorkspaceFile(
                sessionId = context.sessionId,
                pluginId = context.plugin.id,
                relativePath = path,
                content = content
            )
        )
        val scope = result.string("scope")
        return mapOf(
            "scope" to scope,
            "path" to result.string("path"),
            "name" to result.string("name"),
            "size" to jsonInt(result.get("size"), 0),
            "mime_type" to result.string("mime_type"),
            "file_reference" to pluginFileReference(scope, context.plugin.id, result.string("path"))
        )
    }

    private suspend fun pluginWorkspaceList(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val path = payload.string("path").trim()
        if (path.isNotEmpty()) {
            val normalized = path.replace('\\', '/').trim('/')
            if (!PluginManifestValidator.isSafeRelativePath(normalized)) {
                throw PluginApiException("路径不安全：$path", "invalid_argument")
            }
        }
        val result = pluginWorkspaceResult(
            repository(context).listPluginWorkspaceFiles(context.sessionId, context.plugin.id, path)
        )
        val scope = result.string("scope")
        val files = result.getAsJsonArray("files")?.mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            mapOf(
                "name" to file.string("name"),
                "type" to file.string("type"),
                "size" to jsonInt(file.get("size"), 0),
                "path" to file.string("path"),
                "mime_type" to file.string("mime_type"),
                "file_reference" to pluginFileReference(scope, context.plugin.id, file.string("path"))
            )
        }.orEmpty()
        return mapOf(
            "scope" to scope,
            "path" to result.string("path"),
            "files" to files
        )
    }

    private suspend fun pluginWorkspaceRead(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val path = payload.workspacePath()
        val result = pluginWorkspaceResult(
            repository(context).readPluginWorkspaceFile(
                sessionId = context.sessionId,
                pluginId = context.plugin.id,
                relativePath = path,
                maxBytes = MAX_WORKSPACE_READ_BYTES
            )
        )
        val scope = result.string("scope")
        return mapOf(
            "scope" to scope,
            "path" to result.string("path"),
            "size" to jsonInt(result.get("size"), 0),
            "truncated" to result.boolean("truncated", false),
            "content" to result.string("content"),
            "file_reference" to pluginFileReference(scope, context.plugin.id, result.string("path"))
        )
    }

    private suspend fun pluginWorkspaceDelete(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val path = payload.workspacePath()
        val result = pluginWorkspaceResult(
            repository(context).deletePluginWorkspaceFile(context.sessionId, context.plugin.id, path)
        )
        return mapOf("path" to result.string("path"), "deleted" to true)
    }

    /** 解析并校验插件文件夹内的相对路径；同时兼容 `path` 与 `name` 两种字段名。 */
    private fun JsonObject.workspacePath(): String {
        val raw = string("path").ifBlank { string("name") }.trim()
        if (raw.isBlank()) {
            throw PluginApiException("需要 path（插件文件夹内的相对路径）", "invalid_argument")
        }
        if (raw.length > MAX_WORKSPACE_PATH_CHARS) {
            throw PluginApiException("路径最多 $MAX_WORKSPACE_PATH_CHARS 个字符", "invalid_argument")
        }
        val normalized = raw.replace('\\', '/').trim('/')
        if (!PluginManifestValidator.isSafeRelativePath(normalized)) {
            throw PluginApiException("路径不安全：$raw", "invalid_argument")
        }
        return normalized
    }

    /** 工作区仓库的失败结果转成插件可见的异常（保留 code 供插件分支处理）。 */
    private fun pluginWorkspaceResult(result: JsonElement?): JsonObject {
        val obj = result?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw PluginApiException("工作区操作失败", "workspace_failed")
        if (!obj.boolean("success", false)) {
            throw PluginApiException(
                obj.string("message").ifBlank { "工作区操作失败" },
                obj.string("code").ifBlank { "workspace_failed" }
            )
        }
        return obj
    }

    /** 聊天文件卡片引用：会话工作区用相对路径，共享工作区补 `shared://` 前缀。 */
    private fun pluginFileReference(scope: String, pluginId: String, relativePath: String): String {
        val path = "plugins/$pluginId/${relativePath.trim('/')}"
        return if (scope == "shared") "shared://$path" else path
    }

    // ---- 插件私有文件：页面上传（<input type="file">）后的 files.* 访问 ----

    /**
     * 校验插件对指定权限的声明与授权，供非 API 路径（如页面文件上传）复用同一套规则。
     *
     * @throws PluginApiException 未声明或未授权时抛出。
     */
    fun requirePermission(plugin: InstalledPlugin, permission: String) {
        checkPluginPermission(plugin, permission, grants?.granted(plugin.id))
    }

    private fun pluginFilesList(context: CallContext): Map<String, Any?> {
        val entries = fileStore.list(context.plugin.id)
        return mapOf(
            "count" to entries.size,
            "total_bytes" to entries.sumOf { it.size },
            "files" to entries.map { pluginFileEntry(context.plugin.id, it) }
        )
    }

    private fun pluginFilesRead(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val name = payload.string("name").ifBlank { payload.string("path") }.trim()
        if (name.isBlank()) throw PluginApiException("files.read 需要 name", "invalid_argument")
        val encoding = payload.string("encoding").trim().lowercase(Locale.ROOT).ifBlank { "text" }
        if (encoding !in FILE_READ_ENCODINGS) {
            throw PluginApiException("encoding 只支持 text / base64", "invalid_argument")
        }
        val file = fileStore.resolve(context.plugin.id, name)
            ?: throw PluginApiException("文件不存在：$name", "not_found")
        val bytes = try {
            readFileBytes(file, MAX_FILES_READ_BYTES.toInt())
        } catch (error: Exception) {
            throw PluginApiException("读取文件失败：${error.message ?: "未知错误"}", "io_error")
        }
        val content = if (encoding == "base64") {
            Base64.getEncoder().encodeToString(bytes)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return mapOf(
            "name" to file.name,
            "size" to file.length(),
            "mime_type" to PluginAssetServer.mimeTypeFor(file.name),
            "encoding" to encoding,
            "truncated" to (file.length() > MAX_FILES_READ_BYTES),
            "content" to content,
            "url" to pluginFileUrl(context.plugin.id, file.name)
        )
    }

    private fun pluginFilesDelete(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val name = payload.string("name").ifBlank { payload.string("path") }.trim()
        if (name.isBlank()) throw PluginApiException("files.delete 需要 name", "invalid_argument")
        if (!fileStore.delete(context.plugin.id, name)) {
            throw PluginApiException("文件不存在：$name", "not_found")
        }
        return mapOf("name" to name, "deleted" to true)
    }

    private fun pluginFileEntry(pluginId: String, entry: PluginFileStore.Entry): Map<String, Any?> = mapOf(
        "name" to entry.name,
        "size" to entry.size,
        "mime_type" to PluginAssetServer.mimeTypeFor(entry.name),
        "updated_at" to entry.updatedAt,
        "url" to pluginFileUrl(pluginId, entry.name)
    )

    /** 私有文件的虚拟资源地址；页面可直接用于 `<img>`/`<audio>` 等标签。 */
    private fun pluginFileUrl(pluginId: String, name: String): String =
        PluginAssetServer.virtualFileUrl(pluginId, name)

    private fun readFileBytes(file: File, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (output.size() < limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun systemInfo(): Map<String, Any> {
        val configuration = appContext.resources.configuration
        val night = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val locale = runCatching {
            configuration.locales[0]?.toLanguageTag()
        }.getOrNull().orEmpty().ifBlank { "zh-CN" }
        return mapOf(
            "appVersion" to appVersion,
            "apiVersion" to apiVersion,
            "capabilities" to CAPABILITIES,
            "theme" to if (night) "dark" else "light",
            "locale" to locale
        )
    }

    /** 把命令侧的旧 API 名映射到统一名称；页面侧名称原样使用。 */
    private fun canonicalName(raw: String): String = when (raw) {
        "get_session" -> "chat.current"
        "get_messages" -> "chat.messages.list"
        "notify" -> "ui.toast"
        "http_get" -> "http.get"
        "http_post" -> "http.post"
        "progress" -> "progress.update"
        "storage_get" -> "storage.get"
        "storage_set" -> "storage.set"
        "storage_remove" -> "storage.remove"
        "storage_list" -> "storage.list"
        "ai_complete" -> "ai.complete"
        "ui_render" -> "ui.render"
        "append_message" -> "chat.messages.append"
        "chat_send" -> "chat.send"
        "create_session" -> "chat.sessions.create"
        "switch_session" -> "chat.sessions.switch"
        "create_character" -> "characters.create"
        "update_character" -> "characters.update"
        "memory_read" -> "memory.read"
        "memory_write" -> "memory.write"
        "memory_append" -> "memory.append"
        "memory_edit" -> "memory.edit"
        "workspace_save" -> "workspace.save"
        "workspace_list" -> "workspace.list"
        "workspace_read" -> "workspace.read"
        "workspace_delete" -> "workspace.delete"
        "files_list" -> "files.list"
        "files_read" -> "files.read"
        "files_delete" -> "files.delete"
        else -> raw
    }

    private fun permissionFor(canonical: String): String? = when (canonical) {
        "system.info", "log", "ui.close", "ui.render",
        "ui.openPage", "ui.alert", "ui.confirm", "ui.prompt", "ui.select" -> null
        "ai.complete" -> "ai.call"
        "storage.get", "storage.set", "storage.remove", "storage.list" -> "storage"
        "ui.toast" -> "notify"
        "chat.current", "chat.sessions.list", "chat.sessions.get", "chat.messages.list",
        "chat.context", "chat.session.config", "chat.prompt.stack", "chat.tool.calls" -> "chat.read"
        "chat.messages.append", "chat.send",
        "chat.sessions.create", "chat.sessions.switch" -> "chat.write"
        "characters.list", "characters.get" -> "characters.read"
        "characters.create", "characters.update" -> "characters.write"
        "worldbooks.list", "worldbooks.get" -> "worldbooks.read"
        "memory.read" -> "memory.read"
        "memory.write", "memory.append", "memory.edit" -> "memory.write"
        "workspace.save", "workspace.list", "workspace.read", "workspace.delete" -> "workspace"
        "files.list", "files.read", "files.delete" -> "files"
        "http.get", "http.post" -> "network"
        "progress.update" -> "chat.progress"
        else -> null
    }

    // ---- 字段裁剪：页面 API 不返回提示词、密钥类与运行时内部字段 ----

    private fun sessionSummary(session: Session): Map<String, Any?> = mapOf(
        "id" to session.id,
        "name" to session.displayName,
        "type" to session.type,
        "sessionMode" to session.sessionMode,
        "characterId" to session.characterId,
        "characterIds" to session.characterIds,
        "characterName" to session.characterName,
        "portrait" to session.portrait,
        "senderName" to session.senderName,
        "tags" to session.tags,
        "favorite" to session.favorite,
        "pinned" to session.pinned,
        "archived" to session.archived,
        "messageCount" to session.messageCount,
        "lastMessage" to session.lastMessage?.take(MAX_PREVIEW_CHARS),
        "createdAt" to session.createdAt,
        "updatedAt" to session.updatedAt
    )

    private fun messageSummary(message: Message): Map<String, Any?> = mapOf(
        "id" to message.id,
        "role" to message.role,
        "content" to message.content?.take(MAX_MESSAGE_CONTENT_CHARS),
        "reasoningContent" to message.reasoningContent?.take(MAX_MESSAGE_CONTENT_CHARS),
        "reasoningChars" to message.reasoningContent.orEmpty().length,
        "sender" to message.sender,
        "type" to message.type,
        "timestamp" to message.timestamp,
        "createdAt" to message.createdAt,
        "model" to message.model,
        "inputTokens" to message.inputTokens,
        "outputTokens" to message.outputTokens,
        "source" to message.source
    )

    // ---- 会话洞察：上下文占比 / 会话配置 / 提示词注入栈 / 工具调用记录 ----

    /** 解析目标会话：显式 sessionId 优先，否则使用调用上下文中的当前会话。 */
    private fun resolveSessionId(context: CallContext, payload: JsonObject): String {
        val id = payload.string("sessionId").ifBlank { context.sessionId.orEmpty() }
        if (id.isBlank()) {
            throw PluginApiException("缺少会话 id（可在调用时传入 sessionId）", "invalid_argument")
        }
        return id
    }

    private suspend fun requireSession(repository: LocalRepository, sessionId: String): Session =
        repository.getSession(sessionId)
            ?: throw PluginApiException("会话不存在：$sessionId", "not_found")

    /**
     * 会话上下文占比：用量 / 容量 / 百分比 + 各类型明细。
     *
     * 口径与聊天页圆环、上下文分析页一致（[LocalRepository.agentLiveContextUsage]）：
     * 系统提示词、工具定义、压缩摘要、历史消息与工具轨迹；`parts[].percent` 为
     * 占合计用量的比例，`usagePercent` 为占模型上下文窗口的比例。
     */
    private suspend fun sessionContextUsage(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val sessionId = resolveSessionId(context, payload)
        val repository = repository(context)
        requireSession(repository, sessionId)
        val live = repository.agentLiveContextUsage(sessionId)
        val usedTokens = live.totalTokens
        val maxTokens = activeContextLength(repository)
        return mapOf(
            "sessionId" to sessionId,
            "usedTokens" to usedTokens,
            "maxTokens" to maxTokens,
            "usagePercent" to if (maxTokens != null && maxTokens > 0) {
                roundPercent(usedTokens.toDouble() * 100.0 / maxTokens)
            } else {
                null
            },
            "parts" to live.breakdown.parts.map { part ->
                mapOf(
                    "part" to part.part.name.lowercase(Locale.ROOT),
                    "tokens" to part.tokens,
                    "count" to part.itemCount,
                    "percent" to if (usedTokens > 0) {
                        roundPercent(part.tokens.toDouble() * 100.0 / usedTokens)
                    } else {
                        0.0
                    }
                )
            },
            "run" to mapOf(
                "active" to live.hasActiveRun,
                "stage" to live.stage,
                "lastTool" to live.lastToolName,
                "completedToolCalls" to live.completedToolCalls
            )
        )
    }

    /**
     * 会话配置与功能启用情况：模式与角色绑定、剧情/继承角色/主动聊天/TTS 等开关、
     * 提示词相关配置（自定义提示词、被禁用的注入项）与 Agent 任务状态。
     */
    private suspend fun sessionConfig(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val sessionId = resolveSessionId(context, payload)
        val repository = repository(context)
        val session = requireSession(repository, sessionId)
        val todos = AgentTodo.fromJsonList(session.agentTodos)
        val customPrompts = session.customPrompts?.takeIf { it.isJsonArray }?.asJsonArray
        return mapOf(
            "sessionId" to session.id,
            "name" to session.displayName,
            "type" to session.type,
            "sessionMode" to session.sessionMode,
            "characterId" to session.characterId,
            "characterIds" to session.characterIds,
            "characterName" to session.characterName,
            "groupId" to session.groupId,
            "features" to mapOf(
                "plotMode" to (session.plotMode == true),
                "plotRealTimeSync" to (session.plotRealTimeSync == true),
                "inheritCharacter" to (session.inheritCharacter == true),
                "inheritCharacterGreeting" to (session.inheritCharacterGreeting == true),
                "proactiveChat" to jsonBooleanField(session.proactiveChat, "enabled"),
                "tts" to jsonBooleanField(session.ttsConfig, "enabled"),
                "favorite" to (session.favorite == true),
                "pinned" to (session.pinned == true),
                "archived" to (session.archived == true),
                "publicShare" to (session.isPublic == true)
            ),
            "intervals" to mapOf(
                "autoState" to session.autoStateInterval,
                "autoName" to session.autoNameInterval
            ),
            "prompt" to mapOf(
                "hasSystemPrompt" to !session.systemPrompt.isNullOrBlank(),
                "systemPromptChars" to session.systemPrompt.orEmpty().length,
                "composedSystemPromptChars" to session.composedSystemPrompt.orEmpty().length,
                "customPromptCount" to (customPrompts?.size() ?: 0),
                "customPrompts" to customPrompts?.mapNotNull { element ->
                    (element as? JsonObject)?.let { prompt ->
                        mapOf(
                            "order" to jsonInt(prompt.get("order"), 0),
                            "title" to prompt.string("title").take(MAX_PROMPT_TITLE_CHARS),
                            "chars" to prompt.string("content").length
                        )
                    }
                }.orEmpty(),
                "disabledPromptKeys" to session.disabledPromptKeys.orEmpty()
            ),
            "plot" to mapOf(
                "choiceStyle" to session.plotChoiceStyle,
                "outlineChars" to session.plotOutline.orEmpty().length
            ),
            "userPersonaChars" to session.userPersona.orEmpty().length,
            "agent" to mapOf(
                "goal" to session.agentGoal?.take(MAX_AGENT_GOAL_CHARS),
                "hasSpec" to !session.agentSpec.isNullOrBlank(),
                "specChars" to session.agentSpec.orEmpty().length,
                "todos" to todos.take(MAX_AGENT_TODOS).map { todo ->
                    mapOf(
                        "content" to todo.content.take(MAX_AGENT_TODO_CHARS),
                        "status" to todo.status,
                        "priority" to todo.priority
                    )
                }
            )
        )
    }

    /**
     * 会话的提示词注入栈（最近一轮的实际内容）。
     *
     * 每项含 key / 优先级 / 作用域 / 启用状态 / token 估算与内容（可用
     * `includeContent=false` 省略正文）；`includeComposedPrompt=true` 时附带
     * 合成后的完整系统提示词（截断）。注入栈在每轮对话后写入会话记录，
     * 因此这是最近一轮的快照，不是实时重算结果。
     */
    private suspend fun sessionPromptStack(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val sessionId = resolveSessionId(context, payload)
        val repository = repository(context)
        val session = requireSession(repository, sessionId)
        val includeContent = payload.boolean("includeContent", true)
        val includeComposedPrompt = payload.boolean("includeComposedPrompt", false)
        val rawItems = session.promptStackDebug?.takeIf { it.isJsonArray }?.asJsonArray
        val entries = mutableListOf<PromptStackEntry>()
        rawItems?.forEach { element -> (element as? JsonObject)?.let { entries += promptStackEntry(it) } }
        var budget = MAX_PROMPT_STACK_CONTENT_CHARS
        val items = entries.sortedBy { it.priority }.map { entry ->
            val content = if (includeContent && budget > 0) {
                entry.content.take(budget).also { budget -= it.length }
            } else {
                null
            }
            mapOf(
                "key" to entry.key,
                "content" to content,
                "enabled" to entry.enabled,
                "priority" to entry.priority,
                "role" to entry.role,
                "scope" to entry.scope,
                "chars" to entry.content.length,
                "tokens" to estimateLocalTextTokens(entry.content)
            )
        }
        return mapOf(
            "sessionId" to sessionId,
            "available" to (rawItems != null && rawItems.size() > 0),
            "itemCount" to (rawItems?.size() ?: 0),
            "items" to items,
            "disabledKeys" to session.disabledPromptKeys.orEmpty(),
            "composedSystemPrompt" to if (includeComposedPrompt) {
                session.composedSystemPrompt?.take(MAX_COMPOSED_PROMPT_CHARS)
            } else {
                null
            },
            "composedSystemPromptChars" to session.composedSystemPrompt.orEmpty().length
        )
    }

    /**
     * 会话工具调用记录：已完成轮次与进行中/中断轮次合并后的时序列表。
     *
     * 每条记录含 callId / 工具名 / 参数 / 结果 / 状态（done|error|pending）；
     * 参数与结果会截断，整体响应有预算上限，超出时优先保留最新记录。
     */
    private suspend fun sessionToolCalls(context: CallContext, payload: JsonObject): Map<String, Any?> {
        val sessionId = resolveSessionId(context, payload)
        val limit = resolveLimit(payload, TOOL_CALL_DEFAULT_LIMIT, TOOL_CALL_MAX_LIMIT)
        val records = repository(context).sessionToolCallRecords(sessionId)
        val selected = records.takeLast(limit)
        var budget = MAX_TOOL_CALLS_PAYLOAD_CHARS
        val bounded = mutableListOf<Map<String, Any?>>()
        for (index in selected.indices.reversed()) {
            if (budget <= 0) break
            val record = selected[index]
            val arguments = record.arguments.take(minOf(MAX_TOOL_ARGUMENT_CHARS, budget))
            budget -= arguments.length
            val result = record.result?.let { raw ->
                raw.take(minOf(MAX_TOOL_RESULT_CHARS, budget.coerceAtLeast(0))).also { budget -= it.length }
            }
            bounded += mapOf(
                "callId" to record.callId,
                "name" to record.name.take(MAX_TOOL_NAME_CHARS),
                "arguments" to arguments,
                "result" to result,
                "status" to record.status,
                "messageId" to record.messageId,
                "createdAt" to record.createdAt,
                "source" to record.source
            )
        }
        bounded.reverse()
        return mapOf(
            "sessionId" to sessionId,
            "total" to records.size,
            "returned" to bounded.size,
            "records" to bounded
        )
    }

    /** 当前激活模型的上下文窗口长度；未配置或读取失败时返回 null。 */
    private suspend fun activeContextLength(repository: LocalRepository): Int? = runCatching {
        gson.fromJson(repository.getAiConfig(), AiConfig::class.java)?.maxContextLength
    }.getOrNull()?.takeIf { it > 0 }

    private fun roundPercent(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

    private fun jsonInt(element: com.google.gson.JsonElement?, default: Int): Int =
        element?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asInt }.getOrNull() }
            ?: default

    private fun jsonBooleanField(element: com.google.gson.JsonElement?, field: String): Boolean {
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val value = obj.get(field)?.takeIf { it.isJsonPrimitive } ?: return false
        return runCatching { value.asBoolean }.getOrDefault(false)
    }

    private data class PromptStackEntry(
        val key: String,
        val content: String,
        val priority: Int,
        val role: String,
        val scope: String,
        val enabled: Boolean
    )

    private fun promptStackEntry(obj: JsonObject): PromptStackEntry = PromptStackEntry(
        key = obj.string("key").take(MAX_PROMPT_KEY_CHARS),
        content = obj.string("content"),
        priority = jsonInt(obj.get("priority"), 100),
        role = obj.string("role").take(16).ifBlank { "system" },
        scope = obj.string("scope").take(16).ifBlank { "turn" },
        enabled = obj.boolean("enabled", true)
    )

    private fun characterSummary(character: CharacterPreset): Map<String, Any?> = mapOf(
        "id" to character.id,
        "name" to character.displayName,
        "description" to character.description,
        "avatar" to character.avatar,
        "portrait" to character.portrait,
        "tags" to character.tags,
        "updatedAt" to character.updatedAt
    )

    private fun characterDetail(character: CharacterPreset): Map<String, Any?> =
        characterSummary(character) + mapOf(
            "personality" to character.personality,
            "scenario" to character.scenario,
            "firstMessage" to character.firstMessage?.take(MAX_MESSAGE_CONTENT_CHARS),
            "exampleDialogues" to character.exampleDialogues?.take(MAX_MESSAGE_CONTENT_CHARS),
            "state" to character.state
        )

    private fun worldBookSummary(book: WorldBook): Map<String, Any?> = mapOf(
        "id" to book.id,
        "name" to book.displayName,
        "description" to book.description,
        "enabled" to book.enabled,
        "characterIds" to book.characterIds,
        "entryCount" to book.resolvedEntryCount
    )

    private fun worldBookDetail(book: WorldBook): Map<String, Any?> =
        worldBookSummary(book) + mapOf(
            "entries" to book.entries.orEmpty().take(MAX_WORLDBOOK_ENTRIES).map { entry ->
                mapOf(
                    "id" to entry.id,
                    "keys" to entry.keys,
                    "content" to entry.content?.take(MAX_ENTRY_CONTENT_CHARS),
                    "comment" to entry.comment,
                    "enabled" to entry.enabled,
                    "constant" to entry.constant,
                    "position" to entry.position,
                    "insertionOrder" to entry.insertionOrder,
                    "priority" to entry.priority
                )
            }
        )

    // ---- 网络边界：与插件安装共用同一套总开关与公网 HTTPS 校验 ----

    /**
     * 插件网络请求（GET / POST）。
     *
     * 只允许公网 HTTPS；`headers` 可选，受数量/长度上限与禁用头部约束。
     * POST 的 `body` 为字符串，未显式给出 `Content-Type` 时按 application/json 发送。
     */
    private fun performHttpRequest(payload: JsonObject, isPost: Boolean): Map<String, Any?> {
        requireNetworkAllowed()
        val url = payload.string("url").trim()
        requirePublicHttpsUrl(url, "插件网络请求")
        val headers = parseHttpHeaders(
            payload.get("headers")?.let {
                if (it.isJsonObject) it.asJsonObject
                else throw PluginApiException("headers 必须是对象", "invalid_argument")
            }
        )
        val builder = Request.Builder().url(url)
        if (isPost) {
            val bodyBytes = payload.string("body").toByteArray(Charsets.UTF_8)
            if (bodyBytes.size > MAX_HTTP_BODY_BYTES) {
                throw PluginApiException("请求体超过大小限制", "request_too_large")
            }
            val contentType = headers
                .firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
                ?.second
                ?: HTTP_DEFAULT_CONTENT_TYPE
            builder.post(bodyBytes.toRequestBody(contentType.toMediaTypeOrNull()))
        } else {
            builder.get()
        }
        headers.forEach { (name, value) -> builder.header(name, value) }
        httpClient.newCall(builder.build()).execute().use { response ->
            val body = response.body?.byteStream()?.use { input ->
                readLimitedText(input, MAX_HTTP_BYTES)
            }.orEmpty()
            return mapOf("status" to response.code, "body" to body)
        }
    }

    /** 解析并校验 `headers` 对象：名称/值必须为可见 ASCII，且不能覆盖宿主接管的头部。 */
    private fun parseHttpHeaders(raw: JsonObject?): List<Pair<String, String>> {
        if (raw == null || raw.size() == 0) return emptyList()
        if (raw.size() > MAX_HTTP_HEADERS) {
            throw PluginApiException("请求头最多 $MAX_HTTP_HEADERS 项", "invalid_argument")
        }
        return raw.entrySet().map { (rawName, element) ->
            val name = rawName.trim()
            if (name.isEmpty() || name.length > MAX_HTTP_HEADER_NAME_CHARS ||
                !name.all { it.code in HTTP_HEADER_CHAR_RANGE }
            ) {
                throw PluginApiException("请求头名称无效：$rawName", "invalid_argument")
            }
            if (name.lowercase(Locale.ROOT) in HTTP_FORBIDDEN_HEADERS) {
                throw PluginApiException("不允许设置请求头：$name", "invalid_argument")
            }
            val value = element
                .takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asString }.getOrNull() }
                .orEmpty()
            if (value.length > MAX_HTTP_HEADER_VALUE_CHARS ||
                !value.all { it == '\t' || it.code in HTTP_HEADER_CHAR_RANGE }
            ) {
                throw PluginApiException("请求头 $name 的值无效", "invalid_argument")
            }
            name to value
        }
    }

    private fun requireNetworkAllowed() {
        if (!networkAllowed()) {
            throw PluginApiException(
                "Agent 网络访问已在设置中关闭，插件无法发起网络请求",
                "network_disabled"
            )
        }
    }

    private fun requirePublicHttpsUrl(raw: String, what: String) {
        val url = runCatching { raw.toHttpUrlOrNull() }.getOrNull()
            ?: throw PluginApiException("$what 地址无效", "invalid_argument")
        if (!url.isHttps) throw PluginApiException("$what 只允许 HTTPS", "invalid_argument")
        val host = url.host
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost", ignoreCase = true)) {
            throw PluginApiException("$what 不允许访问内网或本机地址：$host", "invalid_argument")
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull().orEmpty()
        if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
            throw PluginApiException("$what 不允许访问内网或本机地址：$host", "invalid_argument")
        }
    }

    private fun isBlockedAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            isUniqueLocalIpv6(address)

    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    private fun storageKey(pluginId: String, raw: String): String {
        val key = raw.trim()
        if (key.isEmpty() || key.length > MAX_STORAGE_KEY_CHARS || '\n' in key || '\r' in key) {
            throw PluginApiException("storage key 无效", "invalid_argument")
        }
        return "$pluginId:$key"
    }

    /** JSON 值 → 普通 Kotlin 值（Map/List/String/Double/Boolean/null），供模板渲染使用。 */
    private fun plainValue(element: com.google.gson.JsonElement?): Any? = when {
        element == null || element.isJsonNull -> null
        element.isJsonObject -> element.asJsonObject.entrySet()
            .associate { (key, value) -> key to plainValue(value) }
        element.isJsonArray -> element.asJsonArray.map { plainValue(it) }
        element.isJsonPrimitive -> {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asDouble
                else -> primitive.asString
            }
        }
        else -> null
    }

    /**
     * 由 payload 构造角色卡（characters.create / characters.update）。
     *
     * [base] 为 null 表示新建，否则为补丁语义：只覆盖 payload 里出现的字段。
     * 不提供删除能力，也没有 id/时间戳字段（id 由 update 的入参决定）。
     */
    private fun characterPresetFromPayload(payload: JsonObject, base: CharacterPreset?): CharacterPreset {
        val created = base == null
        val name = if (payload.has("name")) payload.string("name").trim() else base?.name
        if (created && name.isNullOrBlank()) {
            throw PluginApiException("characters.create 需要 name", "invalid_argument")
        }
        if (!name.isNullOrBlank() && name.length > MAX_CHARACTER_NAME_CHARS) {
            throw PluginApiException("角色名最多 $MAX_CHARACTER_NAME_CHARS 个字符", "invalid_argument")
        }
        fun text(key: String, current: String?): String? {
            if (!payload.has(key)) return current
            val value = payload.string(key)
            if (value.length > MAX_CHARACTER_TEXT_CHARS) {
                throw PluginApiException("$key 最多 $MAX_CHARACTER_TEXT_CHARS 个字符", "invalid_argument")
            }
            return value
        }
        val tags = if (payload.has("tags")) {
            val raw = payload.get("tags") as? JsonArray
                ?: throw PluginApiException("tags 必须是字符串数组", "invalid_argument")
            if (raw.size() > MAX_CHARACTER_TAGS) {
                throw PluginApiException("tags 最多 $MAX_CHARACTER_TAGS 个", "invalid_argument")
            }
            raw.map { element ->
                if (element == null || !element.isJsonPrimitive) {
                    throw PluginApiException("tags 每一项必须是字符串", "invalid_argument")
                }
                element.asString.take(MAX_CHARACTER_TAG_CHARS)
            }
        } else {
            base?.tags
        }
        val greetings = if (payload.has("alternateGreetings")) {
            val raw = payload.get("alternateGreetings") as? JsonArray
                ?: throw PluginApiException("alternateGreetings 必须是字符串数组", "invalid_argument")
            if (raw.size() > MAX_CHARACTER_GREETINGS) {
                throw PluginApiException("alternateGreetings 最多 $MAX_CHARACTER_GREETINGS 条", "invalid_argument")
            }
            raw.map { element ->
                if (element == null || !element.isJsonPrimitive) {
                    throw PluginApiException("alternateGreetings 每一项必须是字符串", "invalid_argument")
                }
                element.asString.take(MAX_CHARACTER_TEXT_CHARS)
            }
        } else {
            base?.alternateGreetings
        }
        val rules = if (payload.has("rules")) {
            val raw = payload.get("rules") as? JsonArray
                ?: throw PluginApiException("rules 必须是字符串数组", "invalid_argument")
            if (raw.size() > MAX_CHARACTER_RULES) {
                throw PluginApiException("rules 最多 $MAX_CHARACTER_RULES 条", "invalid_argument")
            }
            raw.map { element ->
                if (element == null || !element.isJsonPrimitive) {
                    throw PluginApiException("rules 每一项必须是字符串", "invalid_argument")
                }
                element.asString.take(MAX_CHARACTER_TEXT_CHARS)
            }
        } else {
            base?.rules
        }
        val state = if (payload.has("state")) {
            payload.get("state").takeIf { it.isJsonObject }?.asJsonObject
                ?: throw PluginApiException("state 必须是 JSON 对象", "invalid_argument")
        } else {
            base?.state
        }
        return CharacterPreset(
            id = base?.id,
            name = name?.takeIf { it.isNotBlank() } ?: base?.name,
            description = text("description", base?.description),
            avatar = text("avatar", base?.avatar),
            portrait = text("portrait", base?.portrait),
            tags = tags,
            basicInfo = text("basicInfo", base?.basicInfo),
            personality = text("personality", base?.personality),
            scenario = text("scenario", base?.scenario),
            firstMessage = text("firstMessage", base?.firstMessage),
            alternateGreetings = greetings,
            exampleDialogues = text("exampleDialogues", base?.exampleDialogues),
            responseFormat = text("responseFormat", base?.responseFormat),
            rules = rules,
            state = state,
            systemPrompt = text("systemPrompt", base?.systemPrompt),
            greeting = text("greeting", base?.greeting),
            createdAt = base?.createdAt,
            updatedAt = base?.updatedAt
        )
    }

    /**
     * 写入「全局 Agent 记忆」：write 覆盖 / append 追加 / edit 单处替换。
     *
     * 长度上限由记忆存储自身约束（超出时报错），这里只做参数校验与错误码包装。
     */
    private fun writeMemory(mode: String, payload: JsonObject): Map<String, Any> {
        // edit 的替换文本允许写成 newText（与 memoryEdit(oldText, newText) 的签名一致）
        val content = payload.string("content").ifBlank { payload.string("newText") }
        val oldText = payload.string("oldText")
        if (mode == "memory.edit") {
            if (oldText.isBlank()) throw PluginApiException("memory.edit 需要 oldText", "invalid_argument")
        } else if (content.isBlank()) {
            throw PluginApiException("写入内容不能为空", "invalid_argument")
        }
        if (content.length > MAX_MEMORY_WRITE_CHARS) {
            throw PluginApiException("内容最多 $MAX_MEMORY_WRITE_CHARS 个字符", "invalid_argument")
        }
        val charCount = try {
            memoryWriter(
                content,
                mode == "memory.append",
                oldText.takeIf { mode == "memory.edit" }
            )
        } catch (error: PluginApiException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw PluginApiException(error.message ?: "写入记忆失败", "invalid_argument")
        } catch (error: Exception) {
            throw PluginApiException("写入记忆失败：${error.message ?: "未知错误"}", "memory_write_failed")
        }
        return mapOf("charCount" to charCount)
    }

    /** 校验并裁剪插件提交的对话消息，避免不可信脚本构造超大请求。 */
    private fun parseAiMessages(payload: JsonObject): List<Map<String, Any>> {
        val raw = payload.getAsJsonArray("messages")
            ?: throw PluginApiException("ai.complete 需要 messages 数组", "invalid_argument")
        if (raw.size() == 0) {
            throw PluginApiException("messages 不能为空", "invalid_argument")
        }
        if (raw.size() > MAX_AI_MESSAGES) {
            throw PluginApiException("messages 最多 $MAX_AI_MESSAGES 条", "invalid_argument")
        }
        var totalChars = 0
        return raw.map { element ->
            val message = element as? JsonObject
                ?: throw PluginApiException("messages 每一项必须是对象", "invalid_argument")
            val role = message.string("role").trim().lowercase()
            if (role !in AI_ROLES) {
                throw PluginApiException("不支持的 role：$role（可用 system/user/assistant）", "invalid_argument")
            }
            val content = message.string("content")
            if (content.isBlank()) {
                throw PluginApiException("消息内容不能为空", "invalid_argument")
            }
            if (content.length > MAX_AI_MESSAGE_CHARS) {
                throw PluginApiException("单条消息最多 $MAX_AI_MESSAGE_CHARS 个字符", "invalid_argument")
            }
            totalChars += content.length
            mapOf("role" to role, "content" to content)
        }.also {
            if (totalChars > MAX_AI_TOTAL_CHARS) {
                throw PluginApiException("messages 总长度最多 $MAX_AI_TOTAL_CHARS 个字符", "invalid_argument")
            }
        }
    }

    /** maxTokens 默认 512，夹取到 1..2048。 */
    private fun resolveMaxTokens(payload: JsonObject): Int {
        val raw = payload.get("maxTokens")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asInt }.getOrNull() }
        return (raw ?: AI_DEFAULT_MAX_TOKENS).coerceIn(1, AI_MAX_MAX_TOKENS)
    }

    /** temperature 可选，夹取到 0..2。 */
    private fun resolveTemperature(payload: JsonObject): Double? {
        val raw = payload.get("temperature")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asDouble }.getOrNull() }
        return raw?.coerceIn(0.0, 2.0)
    }

    private fun parseProgressSteps(raw: JsonArray?): List<ThinkingStep> {
        if (raw == null) return emptyList()
        return raw.mapNotNull { element ->
            val step = element as? JsonObject ?: return@mapNotNull null
            ThinkingStep(
                type = step.string("type").take(32).ifBlank { "tool" },
                name = step.string("name").take(MAX_PROGRESS_NAME_CHARS),
                status = step.string("status").take(16).ifBlank { "running" },
                detail = step.string("detail").take(MAX_PROGRESS_DETAIL_CHARS)
            )
        }.take(MAX_PROGRESS_STEPS)
    }

    private fun readLimitedText(input: InputStream, limit: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            written += count
            if (written > limit) throw PluginApiException("响应超过大小限制", "response_too_large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }
            ?: default

    companion object {
        /** host.system.info().capabilities 的能力协商清单。 */
        val CAPABILITIES: List<String> = listOf(
            "pages",
            "storage",
            "ui.toast",
            "ui.close",
            "ui.render",
            "ui.openPage",
            "ui.alert",
            "ui.confirm",
            "ui.prompt",
            "ui.select",
            "chat.read",
            "chat.write",
            "characters.read",
            "characters.write",
            "worldbooks.read",
            "memory.read",
            "memory.write",
            "network",
            "progress",
            "workspace",
            "files",
            "ai.call",
            "chat.context",
            "chat.session.config",
            "chat.prompt.stack",
            "chat.tool.calls"
        )

        /** 命令侧 API 名（`ctx.api.*`）。 */
        val LEGACY_API_NAMES: Set<String> = setOf(
            "get_session", "get_messages", "notify", "http_get", "http_post", "progress",
            "storage_get", "storage_set", "storage_remove", "storage_list",
            "ai_complete", "ui_render", "append_message",
            "chat_send", "create_session", "switch_session",
            "memory_read", "memory_write", "memory_append", "memory_edit",
            "create_character", "update_character",
            "workspace_save", "workspace_list", "workspace_read", "workspace_delete",
            "files_list", "files_read", "files_delete",
            "chat.context", "chat.session.config", "chat.prompt.stack", "chat.tool.calls"
        )

        /** 页面侧 API 名（`host.*`）。 */
        val PAGE_API_NAMES: Set<String> = setOf(
            "system.info", "log", "ui.toast", "ui.close", "ui.render", "ui.openPage",
            "ui.alert", "ui.confirm", "ui.prompt", "ui.select",
            "storage.get", "storage.set", "storage.remove", "storage.list",
            "chat.current", "chat.sessions.list", "chat.sessions.get", "chat.messages.list",
            "chat.messages.append", "chat.send", "chat.sessions.create", "chat.sessions.switch",
            "characters.list", "characters.get", "characters.create", "characters.update",
            "worldbooks.list", "worldbooks.get",
            "memory.read", "memory.write", "memory.append", "memory.edit",
            "workspace.save", "workspace.list", "workspace.read", "workspace.delete",
            "files.list", "files.read", "files.delete",
            "http.get", "http.post", "progress.update", "ai.complete",
            "chat.context", "chat.session.config", "chat.prompt.stack", "chat.tool.calls"
        )

        /** chat.write 允许写入的消息角色。 */
        val CHAT_WRITE_ROLES: Set<String> = setOf("user", "assistant")

        /** chat.sessions.create 允许的会话模式。 */
        val SESSION_MODES: Set<String> = setOf("character", "agent", "group")

        /** 群聊会话一次可绑定的角色数上限。 */
        const val MAX_GROUP_CHARACTERS = 16

        /** AI 调用：消息条数、单条与总长度上限。 */
        const val MAX_AI_MESSAGES = 16
        const val MAX_AI_MESSAGE_CHARS = 8_000
        const val MAX_AI_TOTAL_CHARS = 24_000
        const val AI_DEFAULT_MAX_TOKENS = 512
        const val AI_MAX_MAX_TOKENS = 2_048

        /** AI 调用允许的 role。 */
        val AI_ROLES: Set<String> = setOf("system", "user", "assistant")

        /** 解析 limit 参数并夹取到 [1, max]；非数字时取默认值。 */
        fun resolveLimit(payload: JsonObject, default: Int, max: Int): Int {
            val raw = payload.get("limit")
                ?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asInt }.getOrNull() }
            return (raw ?: default).coerceIn(1, max)
        }

        const val MESSAGE_DEFAULT_LIMIT = 50
        const val MESSAGE_MAX_LIMIT = 200
        const val LEGACY_MESSAGE_DEFAULT_LIMIT = 30
        const val LEGACY_MESSAGE_MAX_LIMIT = 100
        const val MAX_SESSION_LIST = 100
        const val MAX_CHARACTER_LIST = 100
        const val MAX_WORLDBOOK_LIST = 100
        const val MAX_WORLDBOOK_ENTRIES = 200
        const val MAX_PREVIEW_CHARS = 500
        const val MAX_MESSAGE_CONTENT_CHARS = 4_000
        const val MAX_ENTRY_CONTENT_CHARS = 4_000
        const val MAX_MEMORY_CHARS = 32_000

        /** 写入记忆的单个请求上限（整体上限由记忆存储约束）。 */
        const val MAX_MEMORY_WRITE_CHARS = 32_000

        /** 单条写入消息的字符上限。 */
        const val MAX_CHAT_WRITE_CHARS = 8_000

        /** 工作区文件 API：路径长度、写入内容与读取上限。 */
        const val MAX_WORKSPACE_PATH_CHARS = 200
        const val MAX_WORKSPACE_SAVE_CHARS = 2_000_000
        const val MAX_WORKSPACE_READ_BYTES = 128L * 1024

        /** 插件私有文件 API：单次读取上限与支持的编码。 */
        const val MAX_FILES_READ_BYTES = 128L * 1024
        val FILE_READ_ENCODINGS: Set<String> = setOf("text", "base64")

        /** `host.ui.openPage` 启动参数与原生弹窗的字段上限。 */
        const val MAX_LAUNCH_ARGS_CHARS = 1_000
        const val MAX_DIALOG_MESSAGE_CHARS = PluginDialogPayloads.MAX_MESSAGE_CHARS
        const val MAX_DIALOG_TITLE_CHARS = PluginDialogPayloads.MAX_TITLE_CHARS
        const val MAX_DIALOG_DEFAULT_CHARS = PluginDialogPayloads.MAX_DEFAULT_CHARS

        /** 角色卡写入上限。 */
        const val MAX_CHARACTER_NAME_CHARS = 128
        const val MAX_CHARACTER_TEXT_CHARS = 32_000
        const val MAX_CHARACTER_TAGS = 32
        const val MAX_CHARACTER_TAG_CHARS = 64
        const val MAX_CHARACTER_GREETINGS = 16
        const val MAX_CHARACTER_RULES = 32
        const val MAX_HTTP_BYTES = 512L * 1024

        /** 插件网络请求：请求体、请求头上限与宿主接管的头部。 */
        const val MAX_HTTP_BODY_BYTES = 512L * 1024
        const val MAX_HTTP_HEADERS = 32
        const val MAX_HTTP_HEADER_NAME_CHARS = 128
        const val MAX_HTTP_HEADER_VALUE_CHARS = 4_096
        const val HTTP_DEFAULT_CONTENT_TYPE = "application/json; charset=utf-8"
        private val HTTP_HEADER_CHAR_RANGE = 0x21..0x7E
        val HTTP_FORBIDDEN_HEADERS: Set<String> = setOf(
            "host", "content-length", "connection", "transfer-encoding",
            "upgrade", "expect", "te", "trailer", "proxy-connection", "keep-alive"
        )
        const val MAX_LOG_CHARS = 500
        const val MAX_TOAST_CHARS = 500
        const val MAX_STORAGE_KEY_CHARS = 128
        const val MAX_PROGRESS_STEPS = 32
        const val MAX_PROGRESS_CONTENT_CHARS = 200
        const val MAX_PROGRESS_NAME_CHARS = 60
        const val MAX_PROGRESS_DETAIL_CHARS = 200

        /** 会话洞察 API：提示词注入栈返回上限。 */
        const val MAX_PROMPT_STACK_CONTENT_CHARS = 120_000
        const val MAX_PROMPT_KEY_CHARS = 128
        const val MAX_PROMPT_TITLE_CHARS = 120
        const val MAX_COMPOSED_PROMPT_CHARS = 8_000

        /** 会话配置 API：Agent 目标与任务列表返回上限。 */
        const val MAX_AGENT_GOAL_CHARS = 2_000
        const val MAX_AGENT_TODOS = 64
        const val MAX_AGENT_TODO_CHARS = 200

        /** 工具调用记录 API：条数与单字段上限，以及整体响应预算。 */
        const val TOOL_CALL_DEFAULT_LIMIT = 50
        const val TOOL_CALL_MAX_LIMIT = 200
        const val MAX_TOOL_ARGUMENT_CHARS = 2_000
        const val MAX_TOOL_RESULT_CHARS = 4_000
        const val MAX_TOOL_NAME_CHARS = 128
        const val MAX_TOOL_CALLS_PAYLOAD_CHARS = 120_000
    }
}
