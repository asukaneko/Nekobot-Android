package com.nekobot.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.local.NbotConfigImporter
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.SkillPackageDownloader
import com.nekobot.app.data.local.validateSkillNameValue
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.model.ApiResult
import com.nekobot.app.data.model.ApiKey
import com.nekobot.app.data.model.ApiKeyRequest
import com.nekobot.app.data.model.AiConfig
import com.nekobot.app.data.model.BindCharacterRequest
import com.nekobot.app.data.model.Channel
import com.nekobot.app.data.model.ChannelPreset
import com.nekobot.app.data.model.ChannelRequest
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.ConfigExportRequest
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.Hook
import com.nekobot.app.data.model.HookExecutionLog
import com.nekobot.app.data.model.HookRequest
import com.nekobot.app.data.model.KnowledgeDocument
import com.nekobot.app.data.model.KnowledgeDocumentRequest
import com.nekobot.app.data.model.KnowledgeSearchRequest
import com.nekobot.app.data.model.KnowledgeSearchResult
import com.nekobot.app.data.model.KnowledgeStats
import com.nekobot.app.data.model.LoginToken
import com.nekobot.app.data.model.LoginTokenRequest
import com.nekobot.app.data.model.LoginTokenResponse
import com.nekobot.app.data.model.McpServer
import com.nekobot.app.data.model.McpServerRequest
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.MessageFavoriteRequest
import com.nekobot.app.data.model.MessageFilterConfig
import com.nekobot.app.data.model.MessageFilterRule
import com.nekobot.app.data.model.MessageFilterRuleRequest
import com.nekobot.app.data.model.PlotBranchRequest
import com.nekobot.app.data.model.PlotChoiceData
import com.nekobot.app.data.model.PlotEdgeData
import com.nekobot.app.data.model.PlotGraphData
import com.nekobot.app.data.model.PlotNodeData
import com.nekobot.app.data.model.PlotRollbackRequest
import com.nekobot.app.data.model.PlotSelectRequest
import com.nekobot.app.data.model.PlotSwitchRequest
import com.nekobot.app.data.model.PlotToggleRequest
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.Skill
import com.nekobot.app.data.model.SkillInstallRequest
import com.nekobot.app.data.model.SkillRequest
import com.nekobot.app.data.model.SttTranscribeResponse
import com.nekobot.app.data.model.SwitchStateRequest
import com.nekobot.app.data.model.SwitchToggleResponse
import com.nekobot.app.data.model.TaskItem
import com.nekobot.app.data.model.TaskRequest
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.data.model.Tool
import com.nekobot.app.data.model.ToolRequest
import com.nekobot.app.data.model.TtsPreviewRequest
import com.nekobot.app.data.model.TtsPreviewResponse
import com.nekobot.app.data.model.TtsVoice
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.data.model.WebDavBackupRequest
import com.nekobot.app.data.model.WebDavConfig
import com.nekobot.app.data.model.WebDavTestRequest
import com.nekobot.app.data.model.Workflow
import com.nekobot.app.data.model.WorkflowRequest
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookEntry
import com.nekobot.app.data.model.WorldBookEntryRequest
import com.nekobot.app.data.model.WorldBookRequest
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 统一仓库：根据 [PrefsManager.appMode] 分发到本地或远程实现。
 *
 * UI 层只调用本类，无需感知当前模式。
 * 服务器模式直接转发到 [NekobotRepository]；本地模式转发到 [LocalRepository]。
 *
 * 注意：本地模式不支持所有远程接口（如 tokenStats/logs/gateway 等），
 * 这些方法在本地模式下会返回空结果。
 */
class UnifiedRepository(
    private val prefs: PrefsManager,
    private val remote: NekobotRepository,
    private val local: LocalRepository,
    private val appContext: android.content.Context? = null
) {
    private val gson = Gson()
    private val isLocal: Boolean get() = prefs.isLocalMode
    private val skillPackageDownloader = SkillPackageDownloader()

    // ==================== 认证 ====================

    suspend fun login(username: String, password: String): Resource<*> =
        if (isLocal) Resource.Success(Unit) else remote.login(username, password)

    suspend fun logout(): Resource<Unit> =
        if (isLocal) Resource.Success(Unit) else remote.logout()

    fun isLoggedIn(): Boolean = prefs.isLoggedIn

    fun logoutLocal() = prefs.clearAuth()

    // ==================== 会话 ====================

    suspend fun listSessions(): Resource<List<Session>> =
        if (isLocal) Resource.Success(local.listSessions()) else remote.listSessions()

    suspend fun createSession(req: CreateSessionRequest): Resource<Session> =
        if (isLocal) Resource.Success(local.createSession(req)) else remote.createSession(req)

    suspend fun getSession(id: String): Resource<Session> =
        if (isLocal) {
            local.getSession(id)?.let { Resource.Success(it) } ?: Resource.Error("会话不存在")
        } else remote.getSession(id)

    suspend fun updateSession(id: String, req: UpdateSessionRequest): Resource<Session> =
        if (isLocal) {
            // 本地模式：proactive_chat / tts_config 以 JSON 字符串形式存入 Room
            local.updateSession(
                id = id,
                name = req.name,
                systemPrompt = req.systemPrompt,
                favorite = req.favorite,
                tags = req.tags,
                plotMode = req.plotMode,
                plotRealTimeSync = req.plotRealTimeSync,
                plotChoiceStyle = req.plotChoiceStyle,
                plotOutline = req.plotOutline,
                userPersona = req.userPersona,
                characterIds = req.characterIds,
                autoStateInterval = req.autoStateInterval,
                disabledPromptKeys = req.disabledPromptKeys,
                isPublic = req.isPublic,
                proactiveChat = req.proactiveChat?.toString(),
                ttsConfig = req.ttsConfig?.toString(),
                shareConfig = req.shareConfig?.toString(),
                archived = req.archived
            )
            local.getSession(id)?.let { Resource.Success(it) } ?: Resource.Error("会话不存在")
        } else remote.updateSession(id, req)

    suspend fun deleteSession(id: String): Resource<Unit> =
        if (isLocal) { local.deleteSession(id); Resource.Success(Unit) } else remote.deleteSession(id)

    suspend fun listMessages(id: String): Resource<List<Message>> =
        if (isLocal) Resource.Success(local.listMessages(id)) else remote.listMessages(id)

    suspend fun addMessage(id: String, content: String): Resource<Message> =
        if (isLocal) Resource.Success(local.addMessage(id, "user", content)) else remote.addMessage(id, content)

    suspend fun deleteMessage(id: String, messageId: String): Resource<Unit> =
        if (isLocal) { local.deleteMessage(id, messageId); Resource.Success(Unit) } else remote.deleteMessage(id, messageId)

    suspend fun clearMessages(id: String): Resource<Unit> =
        if (isLocal) { local.clearMessages(id); Resource.Success(Unit) } else remote.clearMessages(id)

    /**
     * 本地模式：返回 Flow<RealtimeEvent>（流式聊天）；
     * 服务器模式：返回 null（调用方走 Socket）。
     *
     * @param attachments 附件列表（每项含 type/name/path 等，本地模式用于图片视觉识别）
     */
    suspend fun chatStream(id: String, message: String, attachments: List<Map<String, Any>> = emptyList()): Flow<RealtimeEvent>? {
        if (!isLocal) return null
        val model = local.getActiveModel() ?: return null
        // 启用角色运行时的会话走 Pipeline，否则走旧流程
        return local.chatWithPipeline(id, message, model, attachments)
    }

    /**
     * 本地模式：直接走旧流程（不启用角色运行时）；
     * 服务器模式：返回 null（调用方走 Socket）。
     */
    suspend fun chatStreamLegacy(id: String, message: String): Flow<RealtimeEvent>? {
        if (!isLocal) return null
        val model = local.getActiveModel() ?: return null
        return local.chat(id, message, model)
    }

    /** 服务器模式：走 HTTP chat 接口（同时 Socket 推送流式分片）。 */
    suspend fun chat(
        id: String,
        message: String,
        attachments: List<Map<String, Any>> = emptyList()
    ): Resource<ApiResult> =
        if (isLocal) Resource.Success(local.apiResultOk()) else remote.chat(id, message, attachments)

    /**
     * 重新生成。本地模式返回 Flow，服务器模式返回 null（走 HTTP + Socket）。
     */
    suspend fun regenerateStream(id: String, messageId: String?): Flow<RealtimeEvent>? {
        if (!isLocal) return null
        val model = local.getActiveModel() ?: return null
        return local.regenerate(id, messageId, model)
    }

    suspend fun regenerate(id: String, messageId: String? = null): Resource<ApiResult> =
        if (isLocal) Resource.Success(local.apiResultOk()) else remote.regenerate(id, messageId)

    suspend fun stopGeneration(id: String): Resource<ApiResult> {
        return if (isLocal) {
            local.stopGeneration(id)
            Resource.Success(local.apiResultOk())
        } else remote.stopGeneration(id)
    }

    /** 本地 Agent 命令授权；服务器模式由 SocketManager 处理。 */
    fun respondToLocalExecConfirmation(
        requestId: String,
        sessionId: String,
        authorization: com.nekobot.app.data.remote.ExecAuthorization
    ): Boolean = isLocal && local.respondToExecConfirmation(
        requestId = requestId,
        sessionId = sessionId,
        authorization = authorization
    )

    // ==================== 会话 Fork / 压缩 ====================

    suspend fun forkSession(id: String, messageId: String): Resource<JsonElement> =
        if (isLocal) {
            local.forkSession(id, messageId)?.let {
                Resource.Success(JsonParser.parseString("{\"new_session_id\":\"$it\"}"))
            } ?: Resource.Error("分叉失败")
        } else remote.forkSession(id, messageId)

    suspend fun compressContext(id: String): Resource<JsonElement> {
        if (!isLocal) return remote.compressContext(id)
        val model = local.getActiveModel() ?: return Resource.Error("未配置 AI 模型")
        val ok = local.compressContext(id, model)
        return if (ok) {
            // 读取 archive_session_id 回写响应，供 ChatScreen 写回 session 状态
            val archiveId = local.getSession(id)?.archiveSessionId
            val payload = if (archiveId != null) {
                "{\"success\":true,\"archive_session_id\":\"$archiveId\"}"
            } else {
                "{\"success\":true}"
            }
            Resource.Success(JsonParser.parseString(payload))
        } else Resource.Error("压缩失败")
    }

    /** 从归档会话提取 N 轮对话回到当前会话。 */
    suspend fun restoreFromArchive(id: String, turns: Int): Resource<JsonElement> {
        if (!isLocal) return remote.restoreFromArchive(id, turns)
        val ok = local.restoreFromArchive(id, turns)
        return if (ok) Resource.Success(JsonParser.parseString("{\"success\":true,\"turns\":$turns}"))
        else Resource.Error("提取归档失败：未找到归档会话或无可提取的对话轮")
    }

    /** 本地模式查看归档会话和消息（远程模式由 UI 自行调用 getSession + listMessages）。 */
    suspend fun viewArchive(id: String): Pair<Session?, List<Message>>? =
        if (isLocal) local.viewArchive(id) else null

    // ==================== 提示词栈 / 自定义提示词 ====================

    /** 获取会话的自定义提示词列表 */
    suspend fun getCustomPrompts(id: String): Resource<JsonElement> =
        if (isLocal) {
            val raw = local.getCustomPromptsRaw(id)
            val arrStr = if (raw.isNullOrBlank()) "[]" else raw
            Resource.Success(JsonParser.parseString("{\"success\":true,\"custom_prompts\":$arrStr}"))
        } else remote.getCustomPrompts(id)

    /** 全量更新会话的自定义提示词列表 */
    suspend fun updateCustomPrompts(id: String, customPrompts: List<Map<String, Any>>): Resource<JsonElement> =
        if (isLocal) {
            val arr = gson.toJsonTree(customPrompts).toString()
            local.updateCustomPrompts(id, arr)
            Resource.Success(JsonParser.parseString("{\"success\":true,\"custom_prompts\":$arr}"))
        } else remote.updateCustomPrompts(id, customPrompts)

    // ==================== 角色卡 ====================

    suspend fun listCharacters(): Resource<List<CharacterPreset>> =
        if (isLocal) Resource.Success(local.listCharacters()) else remote.listCharacters()

    suspend fun getCharacter(id: String): Resource<CharacterPreset> =
        if (isLocal) {
            local.getCharacter(id)?.let { Resource.Success(it) } ?: Resource.Error("角色不存在")
        } else remote.getCharacter(id)

    suspend fun createCharacter(req: JsonElement): Resource<CharacterPreset> =
        if (isLocal) {
            val preset = gson.fromJson(req, CharacterPreset::class.java)
            Resource.Success(local.upsertCharacter(preset))
        } else remote.createCharacter(req)

    suspend fun updateCharacter(id: String, req: JsonElement): Resource<CharacterPreset> =
        if (isLocal) {
            val preset = gson.fromJson(req, CharacterPreset::class.java).copy(id = id)
            val result = local.upsertCharacter(preset)
            // 广播角色卡变化：会话列表/聊天页订阅后重新加载，同步立绘
            ServiceContainer.notifyCharacterChanged(id)
            Resource.Success(result)
        } else {
            val result = remote.updateCharacter(id, req)
            if (result is Resource.Success) {
                ServiceContainer.notifyCharacterChanged(id)
            }
            result
        }

    suspend fun deleteCharacter(id: String): Resource<Unit> =
        if (isLocal) { local.deleteCharacter(id); Resource.Success(Unit) } else remote.deleteCharacter(id)

    /**
     * AI 生成角色卡：根据自然语言描述生成完整角色卡。
     * - 本地模式：使用激活的本地 AI 模型，未持久化（调用方需再调用 createCharacter 保存）
     * - 服务器模式：调用 /api/personality/ai-generate，未持久化
     * @return 生成的 CharacterPreset
     */
    suspend fun aiGenerateCharacter(description: String): Resource<CharacterPreset> =
        if (isLocal) {
            try {
                Resource.Success(local.aiGenerateCharacter(description))
            } catch (e: Exception) {
                Resource.Error(e.message ?: "AI 生成失败")
            }
        } else remote.aiGenerateCharacter(description)

    // ==================== 世界书 ====================

    suspend fun listWorldBooks(): Resource<List<WorldBook>> =
        if (isLocal) Resource.Success(local.listWorldBooks()) else remote.listWorldBooks()

    suspend fun getWorldBook(id: String): Resource<WorldBook> =
        if (isLocal) {
            local.getWorldBook(id)?.let { Resource.Success(it) } ?: Resource.Error("世界书不存在")
        } else remote.getWorldBook(id)

    suspend fun createWorldBook(req: WorldBookRequest): Resource<WorldBook> =
        if (isLocal) {
            val book = WorldBook(
                name = req.name,
                description = req.description,
                characterIds = req.characterIds,
                enabled = req.enabled ?: true
            )
            Resource.Success(local.upsertWorldBook(book))
        } else remote.createWorldBook(req)

    suspend fun updateWorldBook(id: String, req: WorldBookRequest): Resource<WorldBook> =
        if (isLocal) {
            val existing = local.getWorldBook(id) ?: WorldBook(id = id)
            val book = existing.copy(
                name = req.name,
                description = req.description,
                characterIds = req.characterIds,
                enabled = req.enabled ?: existing.enabled
            )
            Resource.Success(local.upsertWorldBook(book))
        } else remote.updateWorldBook(id, req)

    suspend fun deleteWorldBook(id: String): Resource<Unit> =
        if (isLocal) { local.deleteWorldBook(id); Resource.Success(Unit) } else remote.deleteWorldBook(id)

    suspend fun listEntries(bookId: String): Resource<List<WorldBookEntry>> =
        if (isLocal) Resource.Success(local.listEntries(bookId)) else remote.listEntries(bookId)

    suspend fun createEntry(bookId: String, req: WorldBookEntryRequest): Resource<WorldBookEntry> =
        if (isLocal) {
            val entry = WorldBookEntry(
                keys = req.keys,
                content = req.content,
                comment = req.comment,
                enabled = req.enabled ?: true,
                constant = req.constant ?: false,
                selective = req.selective ?: false,
                insertionOrder = req.insertionOrder,
                priority = req.priority,
                position = req.position,
                caseSensitive = req.caseSensitive
            )
            Resource.Success(local.upsertEntry(bookId, entry))
        } else remote.createEntry(bookId, req)

    suspend fun updateEntry(bookId: String, entryId: String, req: WorldBookEntryRequest): Resource<WorldBookEntry> =
        if (isLocal) {
            val entry = WorldBookEntry(
                id = entryId,
                keys = req.keys,
                content = req.content,
                comment = req.comment,
                enabled = req.enabled ?: true,
                constant = req.constant ?: false,
                selective = req.selective ?: false,
                insertionOrder = req.insertionOrder,
                priority = req.priority,
                position = req.position,
                caseSensitive = req.caseSensitive
            )
            Resource.Success(local.upsertEntry(bookId, entry))
        } else remote.updateEntry(bookId, entryId, req)

    suspend fun deleteEntry(bookId: String, entryId: String): Resource<Unit> =
        if (isLocal) { local.deleteEntry(entryId); Resource.Success(Unit) } else remote.deleteEntry(bookId, entryId)

    /**
     * AI 批量生成世界书条目：根据绑定角色与主题生成 5-10 个条目并立即持久化。
     * - 本地模式：使用激活的本地 AI 模型，直接写入 Room
     * - 服务器模式：调用 /api/world-books/{id}/ai-generate，由后端持久化
     * @return 生成的条目列表
     */
    suspend fun aiGenerateWorldBookEntries(bookId: String, topic: String?): Resource<List<WorldBookEntry>> =
        if (isLocal) {
            try {
                Resource.Success(local.aiGenerateWorldBookEntries(bookId, topic))
            } catch (e: Exception) {
                Resource.Error(e.message ?: "AI 生成失败")
            }
        } else {
            when (val res = remote.aiGenerateWorldBookEntries(bookId, topic)) {
                is Resource.Success -> {
                    val arr = res.data?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?: JsonArray()
                    val entries = arr.map { gson.fromJson(it, WorldBookEntry::class.java) }
                    Resource.Success(entries)
                }
                is Resource.Error -> res
                is Resource.Loading -> res
            }
        }

    // ==================== 本地模式独有：AI 模型管理 ====================

    fun observeLocalSessions(): Flow<List<LocalSessionEntity>>? =
        if (isLocal) local.observeSessions() else null

    fun observeLocalMessages(sessionId: String): Flow<List<LocalMessageEntity>>? =
        if (isLocal) local.observeMessages(sessionId) else null

    fun observeLocalAiModels(): Flow<List<LocalAiModelEntity>>? =
        if (isLocal) local.observeAiModels() else null

    fun observeLocalActiveModel(): Flow<LocalAiModelEntity?>? =
        if (isLocal) local.observeActiveModel() else null

    suspend fun upsertLocalAiModel(model: LocalAiModelEntity) {
        if (isLocal) local.upsertAiModel(model)
    }

    suspend fun setActiveLocalModel(id: String) {
        if (isLocal) {
            // 按 purpose 设置激活：取消同 purpose 其他模型，激活本模型
            val model = local.listAiModels().firstOrNull { it.id == id }
            if (model != null) {
                local.setActiveModelForPurpose(id, model.purpose)
            } else {
                local.setActiveModel(id)
            }
        }
    }

    suspend fun deleteLocalAiModel(id: String) {
        if (isLocal) local.deleteAiModel(id)
    }

    suspend fun getActiveLocalModel(): LocalAiModelEntity? =
        if (isLocal) local.getActiveModel() else null

    /**
     * 获取当前激活聊天模型的上下文窗口长度（max_context_length），
     * 用于聊天界面上下文圆环进度条百分比计算的分母。
     * - 本地模式：从本地 getAiConfig() 读取（默认 100000）
     * - 远程模式：从服务端 /api/ai-config 读取 max_context_length
     */
    suspend fun getActiveContextLength(): Int? {
        return when (val res = getAiConfig()) {
            is Resource.Success -> {
                val json = res.data ?: return null
                runCatching {
                    Gson().fromJson(json, AiConfig::class.java)?.maxContextLength
                }.getOrNull()
            }
            else -> null
        }
    }

    /** 获取指定 purpose 的激活模型（用于 vision/tts/stt 等场景）。 */
    suspend fun getActiveLocalModelByPurpose(purpose: String): LocalAiModelEntity? =
        if (isLocal) local.getActiveModel(purpose) else null

    suspend fun testLocalModel(model: LocalAiModelEntity) =
        if (isLocal) local.testModel(model) else null

    // ==================== Token 用量统计 ====================

    suspend fun tokenStats(dateRange: String? = null, startDate: String? = null, endDate: String? = null): Resource<TokenStats> =
        if (isLocal) Resource.Success(local.tokenStats(dateRange, startDate, endDate))
        else remote.tokenStats(dateRange, startDate, endDate)

    suspend fun tokenRankings(): Resource<TokenRankings> =
        if (isLocal) Resource.Success(local.tokenRankings())
        else remote.tokenRankings()

    /**
     * 获取指定会话历史总 Token 数。
     *
     * - 本地模式：从 token 用量记录中按 session_id 聚合 input+output。
     * - 远程模式：调用 GET /api/tokens?dateRange=all，从 records 数组按 session_id 过滤求和。
     *   注意：服务端消息本身不携带 token 字段，必须依赖此聚合端点。
     *
     * 失败或无记录时返回 0。
     */
    suspend fun sessionTokenUsage(sessionId: String): Long {
        return if (isLocal) {
            local.sessionTokenUsage(sessionId)
        } else {
            when (val res = remote.tokenStats(dateRange = "all")) {
                is Resource.Success -> {
                    val data = res.data ?: return 0L
                    // records 是按条记录的数组；按 session_id 过滤累加 total
                    val records = data.records ?: data.recentRecords ?: emptyList()
                    var sum = 0L
                    for (rec in records) {
                        if (!rec.isJsonObject) continue
                        val obj = rec.asJsonObject
                        val sid = obj.get("session_id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        if (sid != sessionId) continue
                        val total = obj.get("total")?.asLong
                            ?: obj.get("total_tokens")?.asLong
                            ?: ((obj.get("input")?.asLong ?: obj.get("input_tokens")?.asLong ?: 0L) +
                                (obj.get("output")?.asLong ?: obj.get("output_tokens")?.asLong ?: 0L))
                        sum += total
                    }
                    sum
                }
                else -> 0L
            }
        }
    }

    // ==================== 角色卡导入 ====================

    /**
     * 导入角色卡：支持 .json 和 .zip。
     * - 本地模式：直接解析文件并保存到 Room
     * - 服务器模式：上传到 /api/personality/import 解析，再用返回的 character 数据调用 createCharacter 保存
     * @return 导入后的 CharacterPreset
     */
    suspend fun importCharacter(bytes: ByteArray, fileName: String): Resource<CharacterPreset> {
        return if (isLocal) {
            try {
                Resource.Success(local.importCharacter(bytes, fileName))
            } catch (e: Exception) {
                Resource.Error(e.message ?: "导入失败")
            }
        } else {
            try {
                // 服务器模式：用 OkHttp 直接 multipart 上传（复用认证拦截器）
                val result = remote.importCharacterFile(bytes, fileName)
                when (result) {
                    is Resource.Success -> {
                        val preset = result.data
                        // 服务器已返回完整 character 对象，直接 createPreset 保存
                        val json = gson.toJsonTree(preset)
                        Resource.Success(remote.createCharacterPreset(json))
                    }
                    is Resource.Error -> result
                    is Resource.Loading -> result
                }
            } catch (e: Exception) {
                Resource.Error(e.message ?: "导入失败")
            }
        }
    }

    /** 转发到远程仓库（仅服务器模式使用）。 */
    fun remote(): NekobotRepository = remote

    /** 转发到本地仓库（仅本地模式使用）。 */
    fun local(): LocalRepository = local

    // ==================== 工作区 ====================
    /** 列出工作区文件。 */
    suspend fun listWorkspaceFiles(sessionId: String, path: String? = null): Resource<JsonElement> {
        return if (isLocal) {
            Resource.Success(local.listWorkspaceFiles(sessionId, path))
        } else {
            remote.listWorkspaceFiles(sessionId, path)
        }
    }

    /** 上传文件到工作区。本地模式直接落盘到 filesDir/workspace/<sessionId>/。 */
    suspend fun uploadWorkspaceFile(sessionId: String, file: MultipartBody.Part): Resource<JsonElement> {
        return if (isLocal) {
            try {
                val body = file.body ?: return Resource.Error("文件内容为空")
                val bytes = okio.Buffer().use { buf ->
                    body.writeTo(buf)
                    buf.readByteArray()
                }
                val name = file.headers?.get("Content-Disposition")
                    ?.substringAfter("filename=")
                    ?.trim('"')
                    ?: "uploaded_${System.currentTimeMillis()}"
                Resource.Success(local.uploadWorkspaceFile(sessionId, bytes, name))
            } catch (e: Exception) {
                Resource.Error(e.message ?: "上传失败")
            }
        } else {
            remote.uploadWorkspaceFile(sessionId, file)
        }
    }

    /** 删除工作区文件。 */
    suspend fun deleteWorkspaceFile(sessionId: String, filename: String): Resource<JsonElement> {
        return if (isLocal) {
            Resource.Success(local.deleteWorkspaceFile(sessionId, filename))
        } else {
            remote.deleteWorkspaceFile(sessionId, filename)
        }
    }

    /**
     * 下载工作区文件。
     * - 远程模式：返回 retrofit2.Response<ResponseBody>
     * - 本地模式：返回 null（调用方应改用 [downloadWorkspaceFileLocal]）
     */
    suspend fun downloadWorkspaceFile(sessionId: String, filename: String): retrofit2.Response<okhttp3.ResponseBody>? {
        return if (isLocal) null else remote.downloadWorkspaceFile(sessionId, filename)
    }

    /** 本地模式下载工作区文件：直接返回本地 File 对象。 */
    suspend fun downloadWorkspaceFileLocal(sessionId: String, filename: String): java.io.File? =
        if (isLocal) local.downloadWorkspaceFile(sessionId, filename) else null

    // ==================== 扩展功能（仅远程模式，本地模式返回错误）====================
    // 以下 12 组模块仅在远程模式可用，本地模式返回 Resource.Error

    private fun localNotSupported(module: String): Resource<Nothing> =
        Resource.Error("本地模式不支持 $module，请切换到服务器模式")

    // ---- Hook 管理 ----
    suspend fun listHooks(scope: String? = null, event: String? = null, enabled: String? = null): Resource<List<Hook>> =
        if (isLocal) runCatching { Resource.Success(local.listHooks()) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.listHooks(scope, event, enabled)
    suspend fun createHook(req: HookRequest): Resource<Hook> =
        if (isLocal) runCatching { Resource.Success(local.createHook(req)) }
            .getOrElse { Resource.Error(it.message ?: "创建失败") }
        else remote.createHook(req)
    suspend fun updateHook(id: String, req: HookRequest): Resource<Hook> =
        if (isLocal) runCatching { Resource.Success(local.updateHook(id, req)) }
            .getOrElse { Resource.Error(it.message ?: "更新失败") }
        else remote.updateHook(id, req)
    suspend fun deleteHook(id: String): Resource<Unit> =
        if (isLocal) runCatching { local.deleteHook(id); Resource.Success(Unit) }
            .getOrElse { Resource.Error(it.message ?: "删除失败") }
        else remote.deleteHook(id)
    suspend fun toggleHook(id: String): Resource<Hook> =
        if (isLocal) runCatching { Resource.Success(local.toggleHook(id)) }
            .getOrElse { Resource.Error(it.message ?: "切换失败") }
        else remote.toggleHook(id)
    suspend fun testHook(body: JsonElement): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.testHook(body)) else remote.testHook(body)
    suspend fun listHookLogs(hookId: String? = null, limit: Int = 100): Resource<List<HookExecutionLog>> =
        if (isLocal) Resource.Success(local.listHookLogs(hookId, limit)) else remote.listHookLogs(hookId, limit)
    suspend fun hookStats(): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.hookStats()) else remote.hookStats()

    // ---- 任务中心 ----
    suspend fun listTasks(): Resource<List<TaskItem>> =
        if (isLocal) runCatching { Resource.Success(local.listTasks()) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.listTasks()
    suspend fun createTask(req: TaskRequest): Resource<TaskItem> =
        if (isLocal) runCatching { Resource.Success(local.createTask(req)) }
            .getOrElse { Resource.Error(it.message ?: "创建失败") }
        else remote.createTask(req)
    suspend fun updateTask(id: String, req: TaskRequest): Resource<TaskItem> =
        if (isLocal) runCatching { Resource.Success(local.updateTask(id, req)) }
            .getOrElse { Resource.Error(it.message ?: "更新失败") }
        else remote.updateTask(id, req)
    suspend fun deleteTask(id: String): Resource<Unit> =
        if (isLocal) runCatching { local.deleteTask(id); Resource.Success(Unit) }
            .getOrElse { Resource.Error(it.message ?: "删除失败") }
        else remote.deleteTask(id)
    suspend fun toggleTask(id: String): Resource<TaskItem> =
        if (isLocal) runCatching { Resource.Success(local.toggleTask(id)) }
            .getOrElse { Resource.Error(it.message ?: "切换失败") }
        else remote.toggleTask(id)
    suspend fun runTask(id: String): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.runTask(id)) else remote.runTask(id)

    // ---- 工作流 ----
    suspend fun listWorkflows(): Resource<List<Workflow>> =
        if (isLocal) runCatching { Resource.Success(local.listWorkflows()) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.listWorkflows()
    suspend fun createWorkflow(req: WorkflowRequest): Resource<Workflow> =
        if (isLocal) runCatching { Resource.Success(local.createWorkflow(req)) }
            .getOrElse { Resource.Error(it.message ?: "创建失败") }
        else remote.createWorkflow(req)
    suspend fun updateWorkflow(id: String, req: WorkflowRequest): Resource<Workflow> =
        if (isLocal) runCatching { Resource.Success(local.updateWorkflow(id, req)) }
            .getOrElse { Resource.Error(it.message ?: "更新失败") }
        else remote.updateWorkflow(id, req)
    suspend fun deleteWorkflow(id: String): Resource<Unit> =
        if (isLocal) runCatching { local.deleteWorkflow(id); Resource.Success(Unit) }
            .getOrElse { Resource.Error(it.message ?: "删除失败") }
        else remote.deleteWorkflow(id)
    suspend fun toggleWorkflow(id: String): Resource<Workflow> =
        if (isLocal) runCatching { Resource.Success(local.toggleWorkflow(id)) }
            .getOrElse { Resource.Error(it.message ?: "切换失败") }
        else remote.toggleWorkflow(id)
    suspend fun executeWorkflow(id: String): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.executeWorkflow(id)) else remote.executeWorkflow(id)
    suspend fun aiGenerateWorkflow(description: String): Resource<WorkflowRequest> =
        if (isLocal) runCatching { Resource.Success(local.aiGenerateWorkflow(description)) }
            .getOrElse { Resource.Error(it.message ?: "AI 生成失败") }
        else Resource.Error("远程模式暂不支持 AI 生成工作流，请在本地模式使用")

    // ---- 知识库 ----
    suspend fun listKnowledge(): Resource<List<KnowledgeDocument>> =
        if (isLocal) localNotSupported("知识库") else remote.listKnowledge()
    suspend fun createKnowledge(req: KnowledgeDocumentRequest): Resource<KnowledgeDocument> =
        if (isLocal) localNotSupported("知识库") else remote.createKnowledge(req)
    suspend fun updateKnowledge(id: String, req: KnowledgeDocumentRequest): Resource<KnowledgeDocument> =
        if (isLocal) localNotSupported("知识库") else remote.updateKnowledge(id, req)
    suspend fun deleteKnowledge(id: String): Resource<Unit> =
        if (isLocal) localNotSupported("知识库") else remote.deleteKnowledge(id)
    suspend fun indexKnowledge(id: String): Resource<JsonElement> =
        if (isLocal) localNotSupported("知识库") else remote.indexKnowledge(id)
    suspend fun knowledgeStats(): Resource<KnowledgeStats> =
        if (isLocal) localNotSupported("知识库") else remote.knowledgeStats()
    suspend fun searchKnowledge(req: KnowledgeSearchRequest): Resource<List<KnowledgeSearchResult>> =
        if (isLocal) localNotSupported("知识库") else remote.searchKnowledge(req)
    suspend fun rebuildKnowledge(): Resource<JsonElement> =
        if (isLocal) localNotSupported("知识库") else remote.rebuildKnowledge()

    // ---- Skills 配置 ----
    suspend fun listSkills(): Resource<List<Skill>> =
        if (isLocal) runCatching { Resource.Success(local.listSkills()) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.listSkills()
    suspend fun getSkillStorage(skill: Skill): Resource<Skill> =
        if (isLocal) runCatching {
            val latest = local.listSkills().firstOrNull { it.id == skill.id || it.name == skill.name }
                ?: throw IllegalStateException("Skill 不存在")
            Resource.Success(latest)
        }.getOrElse { Resource.Error(it.message ?: "加载 Skill 文件失败") }
        else remote.getSkillStorage(skill)
    suspend fun readSkillFile(skillName: String, relativePath: String): Resource<String> =
        if (isLocal) runCatching { Resource.Success(local.readSkillFile(skillName, relativePath)) }
            .getOrElse { Resource.Error(it.message ?: "读取文件失败") }
        else Resource.Error("远程模式不支持读取 Skill 文件")
    suspend fun createSkill(req: SkillRequest): Resource<Skill> {
        val normalized = runCatching { validateSkillNameValue(req.name) }
            .getOrElse { return Resource.Error(it.message ?: "Skill 名称无效") }
        val body = if (normalized == req.name) req else req.copy(name = normalized)
        return if (isLocal) runCatching { Resource.Success(local.createSkill(body)) }
            .getOrElse { Resource.Error(it.message ?: "创建失败") }
        else remote.createSkill(body)
    }
    suspend fun updateSkill(id: String, req: SkillRequest): Resource<Skill> {
        val normalized = runCatching { validateSkillNameValue(req.name) }
            .getOrElse { return Resource.Error(it.message ?: "Skill 名称无效") }
        val body = if (normalized == req.name) req else req.copy(name = normalized)
        return if (isLocal) runCatching { Resource.Success(local.updateSkill(id, body)) }
            .getOrElse { Resource.Error(it.message ?: "更新失败") }
        else remote.updateSkill(id, body)
    }
    suspend fun deleteSkill(id: String): Resource<Unit> =
        if (isLocal) runCatching { local.deleteSkill(id); Resource.Success(Unit) }
            .getOrElse { Resource.Error(it.message ?: "删除失败") }
        else remote.deleteSkill(id)
    suspend fun toggleSkill(id: String): Resource<Skill> =
        if (isLocal) runCatching { Resource.Success(local.toggleSkill(id)) }
            .getOrElse { Resource.Error(it.message ?: "切换失败") }
        else remote.toggleSkill(id)
    suspend fun installSkillFromUrl(req: SkillInstallRequest): Resource<List<Skill>> =
        if (isLocal) {
            runCatching { Resource.Success(local.installSkillFromUrl(req)) }
                .getOrElse { Resource.Error(it.message ?: "安装失败") }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val packages = skillPackageDownloader.download(req.url)
                    val duplicateNames = packages
                        .groupBy { it.name.lowercase(java.util.Locale.ROOT) }
                        .filterValues { it.size > 1 }
                        .keys
                    if (duplicateNames.isNotEmpty()) {
                        return@withContext Resource.Error(
                            "仓库中包含重名 Skill: ${duplicateNames.joinToString()}"
                        )
                    }
                    val listed = when (val result = remote.listSkills()) {
                        is Resource.Success -> result.data
                        is Resource.Error -> return@withContext result
                        is Resource.Loading -> return@withContext Resource.Loading
                    }
                    val conflicts = packages.mapNotNull { pkg ->
                        listed.firstOrNull { it.name.equals(pkg.name, true) }
                    }
                    if (conflicts.isNotEmpty() && !req.overwrite) {
                        return@withContext Resource.Error(
                            "以下 Skill 已存在：${conflicts.joinToString { it.name }}。如需替换，请开启覆盖同名。"
                        )
                    }
                    if (req.overwrite) {
                        for (conflict in conflicts) {
                            val id = conflict.id ?: continue
                            if (remote.deleteSkill(id) is Resource.Error) {
                                return@withContext Resource.Error("无法覆盖 Skill「${conflict.name}」")
                            }
                        }
                    }
                    val installed = mutableListOf<Skill>()
                    for (pkg in packages) {
                        val uploaded = when (val result = remote.uploadSkillPackage(pkg)) {
                            is Resource.Success -> result.data
                            is Resource.Error -> return@withContext result
                            is Resource.Loading -> return@withContext Resource.Loading
                        }
                        val finalSkill = if (!req.enabled && uploaded.enabled) {
                            val id = uploaded.id
                                ?: return@withContext Resource.Error("服务器未返回 Skill ID")
                            when (val toggled = remote.toggleSkill(id)) {
                                is Resource.Success -> toggled.data
                                is Resource.Error -> return@withContext toggled
                                is Resource.Loading -> return@withContext Resource.Loading
                            }
                        } else uploaded
                        installed += finalSkill.copy(
                            skillMd = pkg.skillMd,
                            referenceMd = pkg.referenceMd,
                            sourceUrl = req.url,
                            hasStorage = true
                        )
                    }
                    Resource.Success(installed)
                } catch (e: Exception) {
                    Resource.Error(e.message ?: "安装失败")
                }
            }
        }

    // ---- Tools 配置 ----
    suspend fun listTools(): Resource<List<Tool>> =
        if (isLocal) runCatching { Resource.Success(local.listTools()) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.listTools()
    suspend fun createTool(req: ToolRequest): Resource<Tool> =
        if (isLocal) runCatching { Resource.Success(local.createTool(req)) }
            .getOrElse { Resource.Error(it.message ?: "创建失败") }
        else remote.createTool(req)
    suspend fun updateTool(id: String, req: ToolRequest): Resource<Tool> =
        if (isLocal) runCatching { Resource.Success(local.updateTool(id, req)) }
            .getOrElse { Resource.Error(it.message ?: "更新失败") }
        else remote.updateTool(id, req)
    suspend fun deleteTool(id: String): Resource<Unit> =
        if (isLocal) runCatching { local.deleteTool(id); Resource.Success(Unit) }
            .getOrElse { Resource.Error(it.message ?: "删除失败") }
        else remote.deleteTool(id)
    suspend fun toggleTool(id: String): Resource<Tool> =
        if (isLocal) runCatching { Resource.Success(local.toggleTool(id)) }
            .getOrElse { Resource.Error(it.message ?: "切换失败") }
        else remote.toggleTool(id)

    // ---- MCP 服务 ----
    suspend fun listMcpServers(): Resource<List<McpServer>> =
        if (isLocal) runCatching { Resource.Success(local.listMcpServers()) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.listMcpServers()
    suspend fun createMcpServer(req: McpServerRequest): Resource<McpServer> =
        if (isLocal) runCatching { Resource.Success(local.createMcpServer(req)) }
            .getOrElse { Resource.Error(it.message ?: "创建失败") }
        else remote.createMcpServer(req)
    suspend fun updateMcpServer(id: String, req: McpServerRequest): Resource<McpServer> =
        if (isLocal) runCatching { Resource.Success(local.updateMcpServer(id, req)) }
            .getOrElse { Resource.Error(it.message ?: "更新失败") }
        else remote.updateMcpServer(id, req)
    suspend fun deleteMcpServer(id: String): Resource<Unit> =
        if (isLocal) runCatching { local.deleteMcpServer(id); Resource.Success(Unit) }
            .getOrElse { Resource.Error(it.message ?: "删除失败") }
        else remote.deleteMcpServer(id)
    suspend fun connectMcpServer(id: String): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.connectMcpServer(id)) else remote.connectMcpServer(id)
    suspend fun disconnectMcpServer(id: String): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.disconnectMcpServer(id)) else remote.disconnectMcpServer(id)
    suspend fun mcpServerTools(id: String): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.mcpServerTools(id)) else remote.mcpServerTools(id)
    suspend fun testMcpServer(id: String): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.testMcpServer(id)) else remote.testMcpServer(id)

    // ---- 频道管理 ----
    suspend fun channelPresets(): Resource<List<ChannelPreset>> =
        if (isLocal) localNotSupported("频道管理") else remote.channelPresets()
    suspend fun createChannelFromPreset(presetId: String): Resource<Channel> =
        if (isLocal) localNotSupported("频道管理") else remote.createChannelFromPreset(presetId)
    suspend fun listChannels(): Resource<List<Channel>> =
        if (isLocal) localNotSupported("频道管理") else remote.listChannels()
    suspend fun createChannel(req: ChannelRequest): Resource<Channel> =
        if (isLocal) localNotSupported("频道管理") else remote.createChannel(req)
    suspend fun updateChannel(id: String, req: ChannelRequest): Resource<Channel> =
        if (isLocal) localNotSupported("频道管理") else remote.updateChannel(id, req)
    suspend fun deleteChannel(id: String): Resource<Unit> =
        if (isLocal) localNotSupported("频道管理") else remote.deleteChannel(id)
    /**
     * 切换频道启停。后端只返回 `{success, enabled}`，无完整 Channel 对象，
     * 故返回新的启用状态；调用方一般会再触发 load() 刷新列表。
     */
    suspend fun toggleChannel(id: String): Resource<Boolean> =
        if (isLocal) localNotSupported("频道管理") else remote.toggleChannel(id)

    // ---- 消息过滤 ----
    suspend fun listMessageFilter(): Resource<MessageFilterConfig> =
        if (isLocal) localNotSupported("消息过滤") else remote.listMessageFilter()
    suspend fun createMessageFilterRule(req: MessageFilterRuleRequest): Resource<MessageFilterRule> =
        if (isLocal) localNotSupported("消息过滤") else remote.createMessageFilterRule(req)
    suspend fun updateMessageFilterRule(id: String, channel: String?, sessionId: String?, req: MessageFilterRuleRequest): Resource<MessageFilterRule> =
        if (isLocal) localNotSupported("消息过滤") else remote.updateMessageFilterRule(id, channel, sessionId, req)
    suspend fun deleteMessageFilterRule(id: String, channel: String? = null, sessionId: String? = null): Resource<Unit> =
        if (isLocal) localNotSupported("消息过滤") else remote.deleteMessageFilterRule(id, channel, sessionId)
    suspend fun toggleMessageFilter(enabled: Boolean): Resource<JsonElement> =
        if (isLocal) localNotSupported("消息过滤") else remote.toggleMessageFilter(enabled)

    // ---- TTS 试验场 ----
    suspend fun listTtsVoices(): Resource<List<TtsVoice>> =
        if (isLocal) localNotSupported("TTS 试验场") else remote.listTtsVoices()
    suspend fun ttsPreview(req: TtsPreviewRequest): Resource<TtsPreviewResponse> =
        if (isLocal) localNotSupported("TTS 试验场") else remote.ttsPreview(req)
    suspend fun uploadTtsVoice(file: okhttp3.MultipartBody.Part, customName: okhttp3.RequestBody, text: okhttp3.RequestBody): Resource<TtsVoice> =
        if (isLocal) localNotSupported("TTS 试验场") else remote.uploadTtsVoice(file, customName, text)

    // ---- 角色立绘/头像上传（远程模式专用，本地模式不支持） ----
    suspend fun uploadPortrait(file: okhttp3.MultipartBody.Part): Resource<String> =
        if (isLocal) localNotSupported("立绘上传") else remote.uploadPortrait(file)

    // ---- AI 立绘生成（本地模式直接生成，远程模式异步任务+轮询） ----
    suspend fun generatePortrait(
        characterName: String,
        description: String,
        basicInfo: String,
        personality: String
    ): Resource<JsonElement> =
        if (isLocal) {
            runCatching { Resource.Success(local.generatePortraitLocal(characterName, description, basicInfo, personality)) }
                .getOrElse { Resource.Error(it.message ?: "AI 立绘生成失败") }
        } else {
            remote.generatePortrait(characterName, description, basicInfo, personality)
        }
    suspend fun getPortraitGenerationStatus(taskId: String): Resource<JsonElement> =
        if (isLocal) localNotSupported("AI 立绘生成") else remote.getPortraitGenerationStatus(taskId)

    // ---- 登录令牌 ----
    suspend fun listLoginTokens(): Resource<List<LoginToken>> =
        if (isLocal) localNotSupported("登录令牌") else remote.listLoginTokens()
    suspend fun createLoginToken(req: LoginTokenRequest): Resource<LoginTokenResponse> =
        if (isLocal) localNotSupported("登录令牌") else remote.createLoginToken(req)
    suspend fun deleteLoginToken(tokenHash: String): Resource<Unit> =
        if (isLocal) localNotSupported("登录令牌") else remote.deleteLoginToken(tokenHash)
    suspend fun deleteAllLoginTokens(): Resource<Unit> =
        if (isLocal) localNotSupported("登录令牌") else remote.deleteAllLoginTokens()

    // ---- API Keys ----
    suspend fun listApiKeys(): Resource<List<ApiKey>> =
        if (isLocal) runCatching { Resource.Success(local.listApiKeys()) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.listApiKeys()
    suspend fun getApiKey(id: String): Resource<ApiKey> =
        if (isLocal) runCatching {
            local.getApiKey(id)?.let { Resource.Success(it) } ?: Resource.Error("API Key 不存在")
        }.getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.getApiKey(id)
    suspend fun createApiKey(req: ApiKeyRequest): Resource<ApiKey> =
        if (isLocal) runCatching { Resource.Success(local.createApiKey(req)) }
            .getOrElse { Resource.Error(it.message ?: "创建失败") }
        else remote.createApiKey(req)
    suspend fun updateApiKey(id: String, req: ApiKeyRequest): Resource<ApiKey> =
        if (isLocal) runCatching { Resource.Success(local.updateApiKey(id, req)) }
            .getOrElse { Resource.Error(it.message ?: "更新失败") }
        else remote.updateApiKey(id, req)
    suspend fun deleteApiKey(id: String): Resource<Unit> =
        if (isLocal) runCatching { local.deleteApiKey(id); Resource.Success(Unit) }
            .getOrElse { Resource.Error(it.message ?: "删除失败") }
        else remote.deleteApiKey(id)

    // ==================== 会话归档 ====================
    suspend fun archiveSession(id: String): Resource<JsonElement> {
        return if (isLocal) {
            local.updateSession(id, archived = true)
            Resource.Success(JsonParser.parseString("{\"success\":true,\"archived\":true}"))
        } else remote.archiveSession(id)
    }

    suspend fun restoreSession(id: String): Resource<JsonElement> {
        return if (isLocal) {
            local.updateSession(id, archived = false)
            Resource.Success(JsonParser.parseString("{\"success\":true,\"archived\":false}"))
        } else remote.restoreSession(id)
    }

    // ==================== 故事图 ====================
    suspend fun plotToggle(req: PlotToggleRequest): Resource<JsonElement> =
        if (isLocal) {
            // 本地模式：更新会话的 plotMode 和 plotChoiceStyle
            local.updateSession(
                id = req.sessionId,
                plotMode = req.enabled,
                plotChoiceStyle = req.plotChoiceStyle
            )
            Resource.Success(JsonParser.parseString("{\"success\":true,\"plot_mode\":${req.enabled}}"))
        } else remote.plotToggle(req)

    suspend fun plotRealTimeSyncToggle(body: Map<String, Any>): Resource<JsonElement> =
        if (isLocal) {
            val sid = body["session_id"] as? String
            if (sid.isNullOrEmpty()) {
                Resource.Error("缺少 session_id")
            } else {
                val enabled = body["enabled"] as? Boolean ?: false
                local.updateSession(sid, plotRealTimeSync = enabled)
                Resource.Success(JsonParser.parseString("{\"success\":true}"))
            }
        } else remote.plotRealTimeSyncToggle(body)

    suspend fun plotGraph(conversationId: String): Resource<PlotGraphData> =
        if (isLocal) {
            // 本地模式：从 PlotGraphManager 获取数据
            val mgr = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            val graph = mgr.getGraph(conversationId)
            val gson = Gson()
            Resource.Success(PlotGraphData(
                nodes = (graph["nodes"] as? List<Map<String, Any>>)?.map {
                    gson.fromJson(gson.toJson(it), PlotNodeData::class.java)
                } ?: emptyList(),
                choices = (graph["choices"] as? List<Map<String, Any>>)?.map {
                    gson.fromJson(gson.toJson(it), PlotChoiceData::class.java)
                } ?: emptyList(),
                edges = (graph["edges"] as? List<Map<String, Any>>)?.map {
                    gson.fromJson(gson.toJson(it), PlotEdgeData::class.java)
                } ?: emptyList(),
                activeNodeId = mgr.getActiveNodeId(conversationId)
            ))
        } else remote.plotGraph(conversationId)

    suspend fun plotLatestChoices(conversationId: String): Resource<JsonElement> =
        if (isLocal) {
            val mgr = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            val choices = mgr.getLatestChoices(conversationId)
            val gson = Gson()
            Resource.Success(JsonParser.parseString("{\"choices\":${gson.toJson(choices.map { it.toDict() })}}"))
        } else remote.plotLatestChoices(conversationId)

    suspend fun plotMermaid(conversationId: String): Resource<JsonElement> =
        if (isLocal) {
            val mermaid = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
                .generateMermaid(conversationId)
            Resource.Success(JsonParser.parseString(Gson().toJson(mapOf("mermaid" to mermaid))))
        } else remote.plotMermaid(conversationId)

    suspend fun plotSelect(conversationId: String, req: PlotSelectRequest): Resource<JsonElement> =
        if (isLocal) {
            val mgr = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            val ok = mgr.selectChoice(req.choiceId)
            if (ok) {
                local.persistPlotGraph()
                Resource.Success(JsonParser.parseString("{\"success\":true,\"choice_id\":\"${req.choiceId}\"}"))
            } else Resource.Error("剧情选项不存在")
        } else remote.plotSelect(conversationId, req)

    suspend fun plotRegenerateChoices(conversationId: String): Resource<JsonElement> =
        if (isLocal) {
            when (val event = local.regeneratePlotChoicesLocal(conversationId).firstOrNull {
                it is RealtimeEvent.PlotChoices || it is RealtimeEvent.Error
            }) {
                is RealtimeEvent.PlotChoices -> Resource.Success(event.choices)
                is RealtimeEvent.Error -> Resource.Error(event.message)
                else -> Resource.Error("未能生成剧情选项")
            }
        } else remote.plotRegenerateChoices(conversationId)

    suspend fun plotRollback(conversationId: String, req: PlotRollbackRequest): Resource<JsonElement> =
        if (isLocal) {
            val mgr = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            val ok = mgr.rollback(req.nodeId)
            if (ok) {
                local.replaceMessagesWithPlotPath(conversationId, req.nodeId)
                local.persistPlotGraph()
                local.syncPlotChoicesFromGraph(conversationId)
                Resource.Success(JsonParser.parseString("{\"success\":true,\"node_id\":\"${req.nodeId}\"}"))
            } else Resource.Error("剧情节点不存在")
        } else remote.plotRollback(conversationId, req)

    suspend fun plotBranchPreview(conversationId: String, nodeId: String): Resource<JsonElement> =
        if (isLocal) {
            val mgr = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            val path = mgr.materializePath(nodeId)
            val gson = Gson()
            Resource.Success(JsonParser.parseString("{\"node_id\":\"$nodeId\",\"messages\":${gson.toJson(path)}}"))
        } else remote.plotBranchPreview(conversationId, nodeId)

    suspend fun plotSwitch(conversationId: String, req: PlotSwitchRequest): Resource<JsonElement> {
        return if (isLocal) {
            val mgr = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            val node = mgr.getNode(req.nodeId)
            if (node == null || node.conversationId != conversationId) return Resource.Error("剧情节点不存在")
            mgr.setActiveNode(conversationId, req.nodeId)
            local.replaceMessagesWithPlotPath(conversationId, req.nodeId)
            local.persistPlotGraph()
            local.syncPlotChoicesFromGraph(conversationId)
            Resource.Success(JsonParser.parseString("{\"success\":true,\"node_id\":\"${req.nodeId}\"}"))
        } else remote.plotSwitch(conversationId, req)
    }

    suspend fun plotBranch(conversationId: String, req: PlotBranchRequest): Resource<JsonElement> {
        return if (isLocal) {
            val manager = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager()
            val choice = manager.getChoice(req.choiceId)
                ?: return Resource.Error("剧情选项不存在")
            if (choice.nodeId != req.nodeId) return Resource.Error("选项不属于当前节点")
            val model = local.getActiveModel() ?: return Resource.Error("未配置激活的 AI 模型")
            if (!manager.selectChoice(req.choiceId)) return Resource.Error("无法选择该剧情选项")
            local.persistPlotGraph()
            var generationError: String? = null
            local.chatWithPipeline(conversationId, choice.text, model).collect { event ->
                if (event is RealtimeEvent.Error) generationError = event.message
            }
            generationError?.let { Resource.Error(it) }
                ?: Resource.Success(JsonParser.parseString("{\"success\":true,\"choice_id\":\"${req.choiceId}\"}"))
        } else remote.plotBranch(conversationId, req)
    }

    suspend fun archivePlotBranch(conversationId: String, req: PlotSwitchRequest): Resource<JsonElement> =
        if (isLocal) {
            val node = com.nekobot.app.data.local.ai.getGlobalPlotGraphManager().getNode(req.nodeId)
            if (node == null || node.conversationId != conversationId) {
                Resource.Error("剧情节点不存在")
            } else {
                val count = local.archivePlotBranch(conversationId, req.nodeId)
                if (count > 0) {
                    Resource.Success(JsonParser.parseString("{\"success\":true,\"archived_count\":$count}"))
                } else Resource.Error("该分支没有可归档的对话")
            }
        } else remote.archivePlotBranch(conversationId, req)

    // ==================== 绑定角色 / 消息收藏 ====================
    suspend fun bindCharacter(sessionId: String, req: BindCharacterRequest): Resource<JsonElement> =
        if (isLocal) runCatching { Resource.Success(local.bindCharacter(sessionId, req)) }
            .getOrElse { Resource.Error(it.message ?: "绑定失败") }
        else remote.bindCharacter(sessionId, req)

    suspend fun listMessageFavorites(sessionId: String): Resource<JsonElement> =
        if (isLocal) runCatching { Resource.Success(local.listMessageFavorites(sessionId)) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.listMessageFavorites(sessionId)

    suspend fun updateMessageFavorites(sessionId: String, req: MessageFavoriteRequest): Resource<JsonElement> =
        if (isLocal) runCatching { Resource.Success(local.updateMessageFavorites(sessionId, req)) }
            .getOrElse { Resource.Error(it.message ?: "保存失败") }
        else remote.updateMessageFavorites(sessionId, req)

    /**
     * 删除指定收藏夹。
     * 本地模式：直接调用 DAO 删除。
     * 远程模式：调用后端 DELETE /api/sessions/{id}/message-favorites/{collectionId} 端点。
     */
    suspend fun deleteMessageFavorite(sessionId: String, collectionId: String): Resource<JsonElement> =
        if (isLocal) runCatching { Resource.Success(local.deleteMessageFavorite(collectionId)) }
            .getOrElse { Resource.Error(it.message ?: "删除失败") }
        else remote.deleteMessageFavorite(sessionId, collectionId)

    // ==================== AI 配置中心 ====================
    suspend fun getAiConfig(): Resource<JsonElement> =
        if (isLocal) runCatching { Resource.Success(local.getAiConfig()) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.getAiConfig()

    suspend fun updateAiConfig(json: JsonElement): Resource<ApiResult> =
        if (isLocal) runCatching {
            val res = local.updateAiConfig(json)
            val apiResult = ApiResult(
                success = res.takeIf { it.isJsonObject }?.asJsonObject?.get("success")?.asBoolean,
                message = res.takeIf { it.isJsonObject }?.asJsonObject?.get("message")
            )
            Resource.Success(apiResult)
        }.getOrElse { Resource.Error(it.message ?: "保存失败") }
        else remote.updateAiConfig(json)

    suspend fun testAiConfig(json: JsonElement): Resource<com.nekobot.app.data.model.TestResponse> =
        if (isLocal) runCatching {
            val res = local.testAiConfig()
            val obj = res.takeIf { it.isJsonObject }?.asJsonObject
            Resource.Success(
                com.nekobot.app.data.model.TestResponse(
                    success = obj?.get("success")?.asBoolean,
                    message = obj?.get("message")?.asString
                )
            )
        }.getOrElse { Resource.Error(it.message ?: "测试失败") }
        else remote.testAiConfig(json)

    suspend fun getAllPurposesConfig(): Resource<JsonElement> =
        if (isLocal) Resource.Success(local.allPurposes())
        else remote.getAllPurposesConfig()

    // ==================== 故障转移队列 ====================
    suspend fun getFailoverQueue(purpose: String): Resource<JsonElement> =
        if (isLocal) runCatching { Resource.Success(local.getFailoverQueue(purpose)) }
            .getOrElse { Resource.Error(it.message ?: "加载失败") }
        else remote.getFailoverQueue(purpose)

    suspend fun resetFailover(modelId: String? = null): Resource<JsonElement> =
        if (isLocal) runCatching { Resource.Success(local.resetFailover(modelId)) }
            .getOrElse { Resource.Error(it.message ?: "重置失败") }
        else remote.resetFailover(modelId)

    suspend fun reorderFailover(purpose: String, modelIds: List<String>): Resource<JsonElement> {
        return if (isLocal) {
            runCatching {
                val body = JsonObject().apply {
                    addProperty("purpose", purpose)
                    add("model_ids", gson.toJsonTree(modelIds))
                }
                Resource.Success(local.reorderFailover(body))
            }.getOrElse { Resource.Error(it.message ?: "排序失败") }
        } else remote.reorderFailover(purpose, modelIds)
    }

    /**
     * 取单个模型的故障转移详情：本地从 Room + 健康存储 + 用量读取器组装；
     * 远程走 GET /api/ai-models/failover-detail/{modelId}。
     */
    suspend fun getFailoverDetail(
        modelId: String
    ): Resource<com.nekobot.app.data.model.FailoverModelDetail> = if (isLocal) {
        runCatching {
            local.getFailoverDetail(modelId)?.let { Resource.Success(it) }
                ?: Resource.Error("模型不存在: $modelId")
        }.getOrElse { Resource.Error(it.message ?: "加载详情失败") }
    } else {
        remote.getFailoverDetail(modelId)
    }

    /**
     * 更新模型故障转移策略（token 限额 + 超时秒数）。
     * 三个数值字段必须非负，否则直接返回错误，不分发到本地/远程。
     */
    suspend fun updateFailoverPolicy(
        modelId: String,
        tokenLimitDaily: Long,
        tokenLimitWeekly: Long,
        failoverTimeout: Int
    ): Resource<JsonElement> {
        // 非负校验：拦截非法输入
        if (tokenLimitDaily < 0 || tokenLimitWeekly < 0 || failoverTimeout < 0) {
            return Resource.Error("token_limit_daily / token_limit_weekly / failover_timeout 必须为非负数")
        }
        return if (isLocal) {
            runCatching {
                Resource.Success(local.updateFailoverPolicy(modelId, tokenLimitDaily, tokenLimitWeekly, failoverTimeout))
            }.getOrElse { Resource.Error(it.message ?: "更新策略失败") }
        } else {
            remote.updateFailoverPolicy(modelId, tokenLimitDaily, tokenLimitWeekly, failoverTimeout)
        }
    }

    // ==================== WebDAV 备份 ====================
    suspend fun getWebDavConfig(): Resource<WebDavConfig> =
        if (isLocal) localNotSupported("WebDAV 备份") else remote.getWebDavConfig()
    suspend fun saveWebDavConfig(config: WebDavConfig): Resource<JsonElement> =
        if (isLocal) localNotSupported("WebDAV 备份") else remote.saveWebDavConfig(config)
    suspend fun testWebDav(req: WebDavTestRequest): Resource<JsonElement> =
        if (isLocal) localNotSupported("WebDAV 备份") else remote.testWebDav(req)
    suspend fun webDavInfo(): Resource<JsonElement> =
        if (isLocal) localNotSupported("WebDAV 备份") else remote.webDavInfo()
    suspend fun webDavBackup(req: WebDavBackupRequest): Resource<JsonElement> =
        if (isLocal) localNotSupported("WebDAV 备份") else remote.webDavBackup(req)
    suspend fun webDavSync(req: WebDavBackupRequest): Resource<JsonElement> =
        if (isLocal) localNotSupported("WebDAV 备份") else remote.webDavSync(req)

    // ==================== 配置迁移 ====================
    suspend fun exportConfig(req: ConfigExportRequest): retrofit2.Response<okhttp3.ResponseBody> =
        if (isLocal) { throw UnsupportedOperationException("本地模式不支持配置迁移") } else remote.exportConfig(req)
    suspend fun importConfig(file: okhttp3.MultipartBody.Part, password: okhttp3.RequestBody, overwrite: okhttp3.RequestBody): Resource<JsonElement> =
        if (isLocal) localNotSupported("配置迁移") else remote.importConfig(file, password, overwrite)

    // ==================== 功能开关 ====================
    suspend fun listSwitches(): Resource<JsonElement> =
        if (isLocal) localNotSupported("功能开关") else remote.listSwitches()
    suspend fun toggleSwitch(req: SwitchStateRequest): Resource<SwitchToggleResponse> =
        if (isLocal) localNotSupported("功能开关") else remote.toggleSwitch(req)

    // ==================== 语音识别（STT）====================
    suspend fun sttTranscribe(audio: okhttp3.MultipartBody.Part, language: okhttp3.RequestBody): Resource<SttTranscribeResponse> =
        if (isLocal) localNotSupported("语音识别") else remote.sttTranscribe(audio, language)

    /**
     * 统一 STT 接口：接受音频字节数组，本地/远程模式均可用。
     * - 本地模式：调用配置的 purpose=stt 激活模型
     * - 远程模式：将字节包装为 multipart 后调用 remote.sttTranscribe
     */
    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        filename: String,
        language: String? = "zh"
    ): Resource<SttTranscribeResponse> = if (isLocal) {
        try {
            val text = local.transcribeSpeech(audioBytes, filename, language)
            Resource.Success(SttTranscribeResponse(success = true, text = text, language = language, provider = "local"))
        } catch (e: Exception) {
            Resource.Error(e.message ?: "本地 STT 识别失败")
        }
    } else {
        try {
            val mediaType = "audio/mpeg".toMediaTypeOrNull()
            val body = audioBytes.toRequestBody(mediaType)
            val part = okhttp3.MultipartBody.Part.createFormData("audio", filename, body)
            val lang = (language ?: "").toRequestBody("text/plain".toMediaTypeOrNull())
            remote.sttTranscribe(part, lang)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "STT 请求异常")
        }
    }

    // ==================== TTS 语音合成（本地优先）====================

    /**
     * 统一 TTS 接口：本地模式走故障转移队列合成并返回缓存 URI；
     * 远程模式暂不支持（远程 TTS 走会话内 session.ttsConfig 触发，由后端推送音频 URL）。
     */
    suspend fun synthesizeAudio(
        text: String,
        voice: String = "alloy",
        speed: Float = 1.0f
    ): Resource<com.nekobot.app.data.local.LocalAudioResult> = if (isLocal) {
        try {
            Resource.Success(local.synthesizeAudio(text, voice, speed))
        } catch (e: Exception) {
            Resource.Error(e.message ?: "本地 TTS 合成失败")
        }
    } else {
        Resource.Error("远程模式不支持独立 TTS 合成，请在会话设置中启用 TTS")
    }

    // ==================== 图片生成（本地 + 远程）====================

    /**
     * 统一图片生成接口：
     * - 本地模式：走故障转移队列生成并返回缓存 URI 列表。
     * - 远程模式：从远程 AI 模型列表中筛选 purpose=image_generation 的模型，
     *   直接调用模型供应商的 OpenAI 兼容 /images/generations 端点（后端目前无通用图片生成 API）。
     */
    suspend fun generateImages(
        prompt: String,
        size: String = "1024x1024",
        n: Int = 1
    ): Resource<List<com.nekobot.app.data.local.LocalImageResult>> {
        if (isLocal) {
            return try {
                Resource.Success(local.generateImages(prompt, size, n))
            } catch (e: Exception) {
                Resource.Error(e.message ?: "本地图片生成失败")
            }
        }
        return try {
            val allModels = when (val res = remote.listAiModels()) {
                is Resource.Success -> res.data
                is Resource.Error -> return Resource.Error(res.message)
                is Resource.Loading -> return Resource.Error("模型列表加载中")
            }
            val imageModels = allModels
                .filter { it.purpose == "image_generation" && it.enabled != false }
                .sortedByDescending { it.priority ?: 0 }
            if (imageModels.isEmpty()) {
                return Resource.Error("未配置图片生成模型，请在 AI 配置中心启用 purpose=image_generation 的模型")
            }
            Resource.Success(local.generateImagesFromRemoteModels(imageModels, prompt, size, n))
        } catch (e: Exception) {
            Resource.Error(e.message ?: "远程图片生成失败")
        }
    }

    /**
     * 统一视觉识别接口：本地模式走故障转移队列解析图片，返回描述或非阻塞失败标记。
     * 远程模式暂不支持。
     */
    suspend fun describeImage(
        imageUrl: String,
        question: String = "请详细描述这张图片的内容。"
    ): Resource<String> = if (isLocal) {
        try {
            Resource.Success(local.describeImageViaQueue(imageUrl, question))
        } catch (e: Exception) {
            Resource.Error(e.message ?: "本地视觉识别失败")
        }
    } else {
        Resource.Error("远程模式不支持独立视觉识别")
    }

    // ==================== 本地 DB Profile 管理 ====================

    /**
     * 从远程服务器下载 nbotcfg 配置包并导入为新本地 db profile。
     * 流程：POST /api/config-transfer/export 下载 zip → Fernet 解密 → 写入新 db。
     *
     * @param url 远程服务器地址（如 https://server.com）
     * @param token 远程服务器认证 token
     * @param password nbotcfg 加密密码
     * @param profileName 目标 db profile 名（不含扩展名，需唯一）
     * @param displayName profile 显示名
     */
    suspend fun importNbotConfigFromRemote(
        url: String,
        token: String,
        password: String,
        profileName: String,
        displayName: String
    ): NbotConfigImporter.ImportResult {
        val ctx = appContext
            ?: return NbotConfigImporter.ImportResult(false, message = "应用上下文未初始化")
        return NbotConfigImporter.importFromRemote(
            context = ctx,
            url = url,
            token = token,
            password = password,
            profileName = profileName,
            displayName = displayName
        )
    }

}
