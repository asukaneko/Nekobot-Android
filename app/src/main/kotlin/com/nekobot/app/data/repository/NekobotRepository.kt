package com.nekobot.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.model.*
import com.nekobot.app.data.remote.NetworkClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * 统一结果包装：成功带数据，失败带错误信息。
 */
sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val code: Int? = null) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}

class NekobotRepository(
    private val network: NetworkClient,
    private val prefs: PrefsManager
) {
    private val api get() = network.apiService
    private val gson = Gson()

    private fun parseError(code: Int, raw: String?): String {
        if (raw.isNullOrBlank()) return "请求失败 (HTTP $code)"
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            obj.get("message")?.asString
                ?: obj.get("error")?.asString
                ?: obj.get("msg")?.asString
                ?: "请求失败 (HTTP $code)"
        } catch (e: Exception) {
            raw.take(200)
        }
    }

    private suspend fun <T> safeCall(
        block: suspend () -> retrofit2.Response<T>
    ): Resource<T> {
        return try {
            val resp = block()
            if (resp.isSuccessful) {
                val body = resp.body()
                Resource.Success(body ?: castNull())
            } else {
                Resource.Error(parseError(resp.code(), resp.errorBody()?.string()), resp.code())
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "网络异常", null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> castNull(): T = null as T

    // ==================== 认证 ====================
    suspend fun login(username: String, password: String): Resource<LoginResponse> {
        return safeCall { api.login(LoginRequest(username, password)) }.let { res ->
            when (res) {
                is Resource.Success -> {
                    val token = res.data.token
                    if (!token.isNullOrEmpty()) {
                        prefs.token = token
                        prefs.username = username
                        Resource.Success(res.data)
                    } else {
                        Resource.Error("登录成功但未返回 token", null)
                    }
                }
                else -> res
            }
        }
    }

    suspend fun logout(): Resource<Unit> {
        val res = safeCall { api.logout() }
        prefs.clearAuth()
        return res.map { }
    }

    fun isLoggedIn(): Boolean = prefs.isLoggedIn

    fun logoutLocal() = prefs.clearAuth()

    // ==================== 会话 ====================
    suspend fun listSessions(): Resource<List<Session>> = safeCall { api.listSessions() }
    suspend fun createSession(req: CreateSessionRequest): Resource<Session> = safeCall { api.createSession(req) }
    suspend fun getSession(id: String): Resource<Session> = safeCall { api.getSession(id) }
    suspend fun updateSession(id: String, req: UpdateSessionRequest): Resource<Session> = safeCall { api.updateSession(id, req) }
    suspend fun deleteSession(id: String): Resource<Unit> = safeCall { api.deleteSession(id) }.map { }

    suspend fun chat(id: String, message: String): Resource<ApiResult> =
        safeCall { api.chat(id, ChatRequest.of(message)) }

    suspend fun regenerate(id: String, messageId: String? = null): Resource<ApiResult> =
        safeCall { api.regenerate(id, mapOf("message_id" to messageId)) }
    suspend fun stopGeneration(id: String): Resource<ApiResult> = safeCall { api.stopGeneration(mapOf("session_id" to id)) }
    suspend fun listMessages(id: String): Resource<List<Message>> = safeCall { api.listMessages(id) }
    suspend fun addMessage(id: String, content: String): Resource<Message> =
        safeCall { api.addMessage(id, SendMessageRequest(content = content)) }
    suspend fun deleteMessage(id: String, messageId: String): Resource<Unit> = safeCall { api.deleteMessage(id, messageId) }.map { }
    suspend fun clearMessages(id: String): Resource<Unit> = safeCall { api.clearMessages(id) }.map { }

    // ==================== 完整角色卡数据源（custom-presets）====================
    // 注意：以下方法用 `/api/personality/custom-presets` 接口，
    // 数据源为 `data/web/custom_personality_presets.json`（完整字段）。
    // 按迁移指南：
    //  - 无单条 GET 端点，单条需在客户端从全列表 filter
    //  - POST 响应直接是 preset 对象
    //  - 创建请求体不传 id / systemPrompt / greeting（后端自动管理）

    /** 把 JsonElement（数组或包装对象）转 List<CharacterPreset> */
    private fun parsePresetList(el: JsonElement): List<CharacterPreset> {
        val arr = when {
            el.isJsonArray -> el.asJsonArray
            el.isJsonObject -> {
                val obj = el.asJsonObject
                obj.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: obj.get("presets")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: obj.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: obj.get("results")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: JsonArray()
            }
            else -> JsonArray()
        }
        return arr.map { gson.fromJson(it, CharacterPreset::class.java) }
    }

    /** 从响应中提取 preset 对象。POST 直接返回 preset，但兼容 `{"success": true, "preset": ...}` 包装 */
    private fun extractPreset(el: JsonElement): CharacterPreset {
        if (el.isJsonObject) {
            val obj = el.asJsonObject
            // 包装格式：{"success": true, "preset": {...}} / {"character": {...}}
            obj.get("preset")?.takeIf { it.isJsonObject }?.let {
                return gson.fromJson(it, CharacterPreset::class.java)
            }
            obj.get("character")?.takeIf { it.isJsonObject }?.let {
                return gson.fromJson(it, CharacterPreset::class.java)
            }
            // 直接是 preset 对象
            return gson.fromJson(el, CharacterPreset::class.java)
        }
        return CharacterPreset()
    }

    suspend fun listCharacters(): Resource<List<CharacterPreset>> {
        val raw: Resource<JsonElement> = safeCall { api.listCharacterPresets() }
        return when (raw) {
            is Resource.Success -> Resource.Success(parsePresetList(raw.data))
            is Resource.Error -> raw
            is Resource.Loading -> raw
        }
    }

    /**
     * 获取单个角色：新接口无单条端点，先拉全列表再 filter。
     * 如后端有响应会立即返回；fallback 接受不完整 GET /api/characters/<id> 数据。
     */
    suspend fun getCharacter(id: String): Resource<CharacterPreset> {
        val raw: Resource<JsonElement> = safeCall { api.listCharacterPresets() }
        return when (raw) {
            is Resource.Success -> {
                val match = parsePresetList(raw.data).firstOrNull { it.id == id }
                if (match != null) Resource.Success(match)
                else Resource.Error("角色不存在 (id=$id)")
            }
            is Resource.Error -> raw
            is Resource.Loading -> raw
        }
    }

    /**
     * 创建完整角色卡。body 为 JsonElement 透传，调用方应避免传 id / systemPrompt / greeting。
     * 响应直接是 preset 对象（已自动包含后端生成的 id / created_at / systemPrompt）。
     */
    suspend fun createCharacter(req: JsonElement): Resource<CharacterPreset> {
        val raw: Resource<JsonElement> = safeCall { api.createCharacterPreset(req) }
        return when (raw) {
            is Resource.Success -> Resource.Success(extractPreset(raw.data))
            is Resource.Error -> raw
            is Resource.Loading -> raw
        }
    }

    /** 更新：路径参数语义是 preset_id，等价于 character_id。 */
    suspend fun updateCharacter(id: String, req: JsonElement): Resource<CharacterPreset> {
        val raw: Resource<JsonElement> = safeCall { api.updateCharacterPreset(id, req) }
        return when (raw) {
            is Resource.Success -> Resource.Success(extractPreset(raw.data))
            is Resource.Error -> raw
            is Resource.Loading -> raw
        }
    }

    /** 删除：静默成功，删除不存在也返回 success。 */
    suspend fun deleteCharacter(id: String): Resource<Unit> {
        val raw: Resource<JsonElement> = safeCall { api.deleteCharacterPreset(id) }
        return when (raw) {
            is Resource.Success -> Resource.Success(Unit)
            is Resource.Error -> raw
            is Resource.Loading -> raw
        }
    }

    /**
     * 上传角色卡文件到 /api/personality/import 解析（不保存）。
     * 服务器返回 {"success": true, "character": {...}}，这里提取 character 对象。
     * 调用方拿到结果后应再调用 [createCharacter] 保存。
     */
    suspend fun importCharacterFile(bytes: ByteArray, fileName: String): Resource<CharacterPreset> {
        return try {
            val mediaType = "application/octet-stream".toMediaType()
            val reqBody = bytes.toRequestBody(mediaType)
            val multipart = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", fileName, reqBody)
                .build()
            val url = network.baseUrl().trimEnd('/') + "/api/personality/import"
            val request = okhttp3.Request.Builder().url(url).post(multipart).build()
            val resp = network.client.newCall(request).execute()
            if (!resp.isSuccessful) {
                return Resource.Error(parseError(resp.code, resp.body?.string()), resp.code)
            }
            val raw = resp.body?.string().orEmpty()
            val obj = JsonParser.parseString(raw).asJsonObject
            val success = obj.get("success")?.asBoolean ?: true
            if (!success) {
                return Resource.Error(obj.get("error")?.asString ?: "导入失败")
            }
            val charEl = obj.get("character") ?: obj
            Resource.Success(extractPreset(charEl))
        } catch (e: Exception) {
            Resource.Error(e.message ?: "导入失败", null)
        }
    }

    /** 直接用 CharacterPreset 对象调用 createPreset（供 importCharacter 流程使用）。 */
    suspend fun createCharacterPreset(req: JsonElement): CharacterPreset {
        val raw: Resource<JsonElement> = safeCall { api.createCharacterPreset(req) }
        return when (raw) {
            is Resource.Success -> extractPreset(raw.data)
            else -> throw IllegalStateException((raw as Resource.Error).message)
        }
    }

    // ==================== 世界书 ====================
    suspend fun listWorldBooks(): Resource<List<WorldBook>> = safeCall { api.listWorldBooks() }
    suspend fun getWorldBook(id: String): Resource<WorldBook> = safeCall { api.getWorldBook(id) }
    suspend fun createWorldBook(req: WorldBookRequest): Resource<WorldBook> = safeCall { api.createWorldBook(req) }
    suspend fun updateWorldBook(id: String, req: WorldBookRequest): Resource<WorldBook> = safeCall { api.updateWorldBook(id, req) }
    suspend fun deleteWorldBook(id: String): Resource<Unit> = safeCall { api.deleteWorldBook(id) }.map { }
    suspend fun listEntries(bookId: String): Resource<List<WorldBookEntry>> = safeCall { api.listEntries(bookId) }
    suspend fun createEntry(bookId: String, req: WorldBookEntryRequest): Resource<WorldBookEntry> = safeCall { api.createEntry(bookId, req) }
    suspend fun updateEntry(bookId: String, entryId: String, req: WorldBookEntryRequest): Resource<WorldBookEntry> = safeCall { api.updateEntry(bookId, entryId, req) }
    suspend fun deleteEntry(bookId: String, entryId: String): Resource<Unit> = safeCall { api.deleteEntry(bookId, entryId) }.map { }

    // ==================== AI 配置 ====================
    suspend fun getAiConfig(): Resource<JsonElement> = safeCall { api.getAiConfig() }
    suspend fun updateAiConfig(json: JsonElement): Resource<ApiResult> = safeCall { api.updateAiConfig(json) }
    suspend fun getAllPurposesConfig(): Resource<JsonElement> = safeCall { api.getAllPurposesConfig() }
    suspend fun testAiConfig(json: JsonElement): Resource<TestResponse> = safeCall { api.testAiConfig(json) }

    // ==================== AI 模型 ====================
    suspend fun listAiModels(): Resource<List<AiModel>> {
        val raw: Resource<AiModelListResponse> = safeCall { api.listAiModels() }
        return when (raw) {
            is Resource.Success -> {
                // 服务端返回 {"models":[...], "active_model_id":"..."}
                // 根据 active_model_id 给对应模型打上 active=true 标记
                val list = raw.data.models ?: emptyList()
                val activeId = raw.data.activeModelId
                val processed = if (activeId.isNullOrBlank()) list
                else list.map { m -> if (m.id == activeId) m.copy(active = true) else m }
                Resource.Success(processed)
            }
            is Resource.Error -> raw
            is Resource.Loading -> raw
        }
    }
    suspend fun createAiModel(req: AiModelRequest): Resource<AiModel> = safeCall { api.createAiModel(req) }
    suspend fun updateAiModel(id: String, req: AiModelRequest): Resource<AiModel> = safeCall { api.updateAiModel(id, req) }
    suspend fun deleteAiModel(id: String): Resource<Unit> = safeCall { api.deleteAiModel(id) }.map { }
    suspend fun applyAiModel(id: String): Resource<ApiResult> = safeCall { api.applyAiModel(id) }
    suspend fun toggleAiModel(id: String): Resource<ApiResult> = safeCall { api.toggleAiModel(id) }
    suspend fun cloneAiModel(id: String): Resource<AiModel> = safeCall { api.cloneAiModel(id) }
    suspend fun testAiModel(id: String): Resource<TestResponse> = safeCall { api.testAiModel(id) }
    suspend fun fetchModels(req: FetchModelsRequest): Resource<FetchModelsResponse> = safeCall { api.fetchModels(req) }
    suspend fun listProtocols(): Resource<JsonElement> = safeCall { api.listProtocols() }
    suspend fun listPurposes(): Resource<JsonElement> = safeCall { api.listPurposes() }
    suspend fun activeByPurpose(): Resource<JsonElement> = safeCall { api.activeByPurpose() }

    // ==================== Token 用量 ====================
    suspend fun tokenStats(dateRange: String? = null, startDate: String? = null, endDate: String? = null): Resource<TokenStats> =
        safeCall { api.tokenStats(dateRange, startDate, endDate) }
    suspend fun tokenRankings(): Resource<TokenRankings> = safeCall { api.tokenRankings() }

    // ==================== 系统设置 ====================
    suspend fun getSettings(): Resource<JsonElement> = safeCall { api.getSettings() }
    suspend fun updateSettings(json: JsonElement): Resource<JsonElement> = safeCall { api.updateSettings(json) }
    suspend fun reloadConfig(): Resource<ApiResult> = safeCall { api.reloadConfig() }
    suspend fun listLogs(level: String = "all", limit: Int = 100): Resource<JsonElement> = safeCall { api.listLogs(level, limit) }
    suspend fun stats(): Resource<JsonElement> = safeCall { api.stats() }
    suspend fun messageStats(period: String = "day"): Resource<JsonElement> = safeCall { api.messageStats(period) }

    // ==================== 角色状态 / 历程 ====================
    suspend fun sessionRuntimeTimeline(id: String): Resource<JsonElement> = safeCall { api.sessionRuntimeTimeline(id) }
    suspend fun compressContext(id: String): Resource<JsonElement> = safeCall { api.compressContext(id) }
    suspend fun forkSession(id: String, messageId: String): Resource<JsonElement> =
        safeCall { api.forkSession(id, com.nekobot.app.data.model.ForkRequest(messageId)) }
    suspend fun channelRuntimeTimeline(channel: String? = null, characterId: String? = null): Resource<JsonElement> =
        safeCall { api.channelRuntimeTimeline(channel, characterId) }

    /** 获取会话的自定义提示词列表 */
    suspend fun getCustomPrompts(id: String): Resource<JsonElement> = safeCall { api.getCustomPrompts(id) }

    /** 全量更新会话的自定义提示词列表 */
    suspend fun updateCustomPrompts(id: String, customPrompts: List<Map<String, Any>>): Resource<JsonElement> =
        safeCall { api.updateCustomPrompts(id, mapOf("custom_prompts" to customPrompts)) }

    // ==================== 剧情模式 ====================
    suspend fun getLatestPlotChoices(conversationId: String): Resource<JsonElement> =
        safeCall { api.getLatestPlotChoices(conversationId) }
    suspend fun selectPlotChoice(conversationId: String, choiceId: String): Resource<ApiResult> =
        safeCall { api.selectPlotChoice(conversationId, mapOf("choice_id" to choiceId)) }
    suspend fun regeneratePlotChoices(conversationId: String): Resource<JsonElement> =
        safeCall { api.regeneratePlotChoices(conversationId) }
    suspend fun characterState(id: String, scopeId: String): Resource<JsonElement> = safeCall { api.characterState(id, scopeId) }
    suspend fun characterRelationships(id: String, targetId: String): Resource<JsonElement> = safeCall { api.characterRelationships(id, targetId) }

    // ==================== 工具 ====================
    fun toJson(obj: Any): JsonElement = gson.toJsonTree(obj)

    fun parseJson(json: String): JsonElement = JsonParser.parseString(json)

    // ==================== 角色记忆（MemoryFS + 旧版）====================
    /** 列出 MemoryFS 逻辑文件，可选按角色过滤。 */
    suspend fun listMemoryFs(characterId: String? = null): Resource<MemoryFsListResponse> =
        safeCall { api.listMemoryFs(characterId) }

    /** 读取指定路径的 MemoryFS 文件。 */
    suspend fun readMemoryFs(path: String): Resource<MemoryFile> =
        safeCall { api.readMemoryFs(path) }

    /** 删除指定路径的 MemoryFS 文件。 */
    suspend fun deleteMemoryFs(path: String, characterId: String? = null): Resource<ApiResult> =
        safeCall { api.deleteMemoryFs(path, characterId) }

    /** 旧版记忆列表，可按 type/target_id/character_name 过滤。 */
    suspend fun listLegacyMemory(
        type: String? = null,
        targetId: String? = null,
        characterName: String? = null
    ): Resource<LegacyMemoryListResponse> =
        safeCall { api.listLegacyMemory(type, targetId, characterName) }

    /** 新增旧版记忆。 */
    suspend fun createLegacyMemory(req: LegacyMemoryRequest): Resource<ApiResult> =
        safeCall { api.createLegacyMemory(req) }

    /** 更新旧版记忆。body 为 JsonElement 透传（字段可选）。 */
    suspend fun updateLegacyMemory(memoryId: String, body: JsonElement): Resource<JsonElement> =
        safeCall { api.updateLegacyMemory(memoryId, body) }

    /** 删除单条旧版记忆。 */
    suspend fun deleteLegacyMemory(memoryId: String): Resource<ApiResult> =
        safeCall { api.deleteLegacyMemory(memoryId) }

    /** 导出全部旧版记忆。 */
    suspend fun exportLegacyMemory(): Resource<LegacyMemoryListResponse> =
        safeCall { api.exportLegacyMemory() }

    // ==================== 工作区 ====================
    /** 列出会话工作区文件 */
    suspend fun listWorkspaceFiles(sessionId: String, path: String? = null): Resource<JsonElement> =
        safeCall { api.listWorkspaceFiles(sessionId, path) }

    /** 上传文件到会话工作区 */
    suspend fun uploadWorkspaceFile(sessionId: String, file: MultipartBody.Part): Resource<JsonElement> =
        safeCall { api.uploadWorkspaceFile(sessionId, file) }

    /** 删除工作区文件 */
    suspend fun deleteWorkspaceFile(sessionId: String, filename: String): Resource<JsonElement> =
        safeCall { api.deleteWorkspaceFile(sessionId, filename) }

    /** 下载工作区文件（流式） */
    suspend fun downloadWorkspaceFile(sessionId: String, filename: String): Response<ResponseBody> =
        api.downloadWorkspaceFile(sessionId, filename)
}

/** 把无数据成功结果映射为 Unit */
fun <T> Resource<T>.map(transform: (T) -> Unit): Resource<Unit> = when (this) {
    is Resource.Success -> Resource.Success(transform(data))
    is Resource.Error -> this
    is Resource.Loading -> this
}
