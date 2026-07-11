package com.nekobot.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.model.ApiResult
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookEntry
import com.nekobot.app.data.model.WorldBookEntryRequest
import com.nekobot.app.data.model.WorldBookRequest
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.flow.Flow
import okhttp3.MultipartBody

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
            local.updateSession(
                id = id,
                name = req.name,
                systemPrompt = req.systemPrompt,
                favorite = req.favorite,
                tags = req.tags,
                plotMode = req.plotMode,
                plotRealTimeSync = req.plotRealTimeSync,
                autoStateInterval = req.autoStateInterval,
                disabledPromptKeys = req.disabledPromptKeys
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
     */
    fun chatStream(id: String, message: String): Flow<RealtimeEvent>? {
        if (!isLocal) return null
        val model = kotlinx.coroutines.runBlocking { local.getActiveModel() }
            ?: return null
        // 启用角色运行时的会话走 Pipeline，否则走旧流程
        return local.chatWithPipeline(id, message, model)
    }

    /**
     * 本地模式：直接走旧流程（不启用角色运行时）；
     * 服务器模式：返回 null（调用方走 Socket）。
     */
    fun chatStreamLegacy(id: String, message: String): Flow<RealtimeEvent>? {
        if (!isLocal) return null
        val model = kotlinx.coroutines.runBlocking { local.getActiveModel() }
            ?: return null
        return local.chat(id, message, model)
    }

    /** 服务器模式：走 HTTP chat 接口（同时 Socket 推送流式分片）。 */
    suspend fun chat(id: String, message: String): Resource<ApiResult> =
        if (isLocal) Resource.Success(local.apiResultOk()) else remote.chat(id, message)

    /**
     * 重新生成。本地模式返回 Flow，服务器模式返回 null（走 HTTP + Socket）。
     */
    fun regenerateStream(id: String, messageId: String?): Flow<RealtimeEvent>? {
        if (!isLocal) return null
        val model = kotlinx.coroutines.runBlocking { local.getActiveModel() } ?: return null
        return local.regenerate(id, messageId, model)
    }

    suspend fun regenerate(id: String, messageId: String? = null): Resource<ApiResult> =
        if (isLocal) Resource.Success(local.apiResultOk()) else remote.regenerate(id, messageId)

    suspend fun stopGeneration(id: String): Resource<ApiResult> {
        return if (isLocal) {
            local.stopGeneration()
            Resource.Success(local.apiResultOk())
        } else remote.stopGeneration(id)
    }

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
        return if (ok) Resource.Success(JsonParser.parseString("{\"success\":true}"))
        else Resource.Error("压缩失败")
    }

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
            Resource.Success(local.upsertCharacter(preset))
        } else remote.updateCharacter(id, req)

    suspend fun deleteCharacter(id: String): Resource<Unit> =
        if (isLocal) { local.deleteCharacter(id); Resource.Success(Unit) } else remote.deleteCharacter(id)

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
        if (isLocal) local.setActiveModel(id)
    }

    suspend fun deleteLocalAiModel(id: String) {
        if (isLocal) local.deleteAiModel(id)
    }

    suspend fun getActiveLocalModel(): LocalAiModelEntity? =
        if (isLocal) local.getActiveModel() else null

    suspend fun testLocalModel(model: LocalAiModelEntity) =
        if (isLocal) local.testModel(model) else null

    // ==================== Token 用量统计 ====================

    suspend fun tokenStats(dateRange: String? = null, startDate: String? = null, endDate: String? = null): Resource<TokenStats> =
        if (isLocal) Resource.Success(local.tokenStats())
        else remote.tokenStats(dateRange, startDate, endDate)

    suspend fun tokenRankings(): Resource<TokenRankings> =
        if (isLocal) Resource.Success(local.tokenRankings())
        else remote.tokenRankings()

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
    /** 列出工作区文件：本地模式返回空数组，远程模式转发 */
    suspend fun listWorkspaceFiles(sessionId: String, path: String? = null): Resource<JsonElement> {
        return if (isLocal) {
            Resource.Success(JsonParser.parseString("[]"))
        } else {
            remote.listWorkspaceFiles(sessionId, path)
        }
    }

    /** 上传文件到工作区：本地模式不支持，远程模式转发 */
    suspend fun uploadWorkspaceFile(sessionId: String, file: MultipartBody.Part): Resource<JsonElement> {
        return if (isLocal) {
            Resource.Error("本地模式不支持工作区上传")
        } else {
            remote.uploadWorkspaceFile(sessionId, file)
        }
    }

    /** 删除工作区文件：本地模式不支持，远程模式转发 */
    suspend fun deleteWorkspaceFile(sessionId: String, filename: String): Resource<JsonElement> {
        return if (isLocal) {
            Resource.Error("本地模式不支持工作区操作")
        } else {
            remote.deleteWorkspaceFile(sessionId, filename)
        }
    }

    /** 下载工作区文件：本地模式返回 null，远程模式转发 */
    suspend fun downloadWorkspaceFile(sessionId: String, filename: String): retrofit2.Response<okhttp3.ResponseBody>? {
        return if (isLocal) null else remote.downloadWorkspaceFile(sessionId, filename)
    }
}
