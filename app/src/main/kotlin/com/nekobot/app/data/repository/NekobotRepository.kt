package com.nekobot.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.model.*
import com.nekobot.app.data.model.BindCharacterRequest
import com.nekobot.app.data.model.MessageFavoriteRequest
import com.nekobot.app.data.remote.NetworkClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
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
    suspend fun makeSessionPublic(id: String, req: PublicShareRequest): Resource<PublicSessionStatus> =
        safeCall { api.makeSessionPublic(id, req) }
    suspend fun removeSessionPublic(id: String): Resource<Unit> =
        safeCall { api.removeSessionPublic(id) }.map { }
    suspend fun getSessionPublicStatus(id: String): Resource<PublicSessionStatus> =
        safeCall { api.getSessionPublicStatus(id) }
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
     * AI 生成角色卡：调用 /api/personality/ai-generate，返回完整 CharacterPreset。
     * 后端返回 {"success": true, "character": {...}}，提取 character 字段。
     */
    suspend fun aiGenerateCharacter(description: String): Resource<CharacterPreset> {
        val raw: Resource<JsonElement> = safeCall { api.aiGenerateCharacter(mapOf("description" to description)) }
        return when (raw) {
            is Resource.Success -> {
                val obj = raw.data?.takeIf { it.isJsonObject }?.asJsonObject
                val success = obj?.get("success")?.asBoolean ?: true
                if (!success) {
                    Resource.Error(obj?.get("error")?.asString ?: "AI 生成失败")
                } else {
                    val charEl = obj?.get("character") ?: obj
                    Resource.Success(extractPreset(charEl ?: JsonParser.parseString("{}")))
                }
            }
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
    /**
     * 从响应中提取 WorldBook 对象。
     * 原仓库 POST/PUT /api/world-books 返回 {"success": true, "world_book": {...}} 包装格式，
     * 但 GET 直接返回 book 对象本身，这里两种格式都兼容。
     */
    private fun extractWorldBook(el: JsonElement?): WorldBook {
        if (el == null || el.isJsonNull) return WorldBook()
        if (el.isJsonObject) {
            val obj = el.asJsonObject
            // 包装格式：{"success": true, "world_book": {...}}
            obj.get("world_book")?.takeIf { it.isJsonObject }?.let {
                return gson.fromJson(it, WorldBook::class.java)
            }
            // 直接是 book 对象
            return gson.fromJson(el, WorldBook::class.java)
        }
        return WorldBook()
    }

    /**
     * 解析世界书条目响应。
     * 原仓库 POST/PUT /api/world-books/{id}/entries[/...] 返回 {"success": true, "entry": {...}} 包装格式，
     * 但 GET 列表直接返回数组。这里兼容包装格式与裸 entry 对象两种情况。
     */
    private fun extractEntry(el: JsonElement?): WorldBookEntry {
        if (el == null || el.isJsonNull) return WorldBookEntry()
        if (el.isJsonObject) {
            val obj = el.asJsonObject
            // 包装格式：{"success": true, "entry": {...}}
            obj.get("entry")?.takeIf { it.isJsonObject }?.let {
                return gson.fromJson(it, WorldBookEntry::class.java)
            }
            // 直接是 entry 对象
            return gson.fromJson(el, WorldBookEntry::class.java)
        }
        return WorldBookEntry()
    }

    suspend fun listWorldBooks(): Resource<List<WorldBook>> = safeCall { api.listWorldBooks() }
    suspend fun getWorldBook(id: String): Resource<WorldBook> = safeCall { api.getWorldBook(id) }
    suspend fun createWorldBook(req: WorldBookRequest): Resource<WorldBook> =
        safeCall { api.createWorldBook(req) }.let { res ->
            when (res) {
                is Resource.Success -> Resource.Success(extractWorldBook(res.data))
                is Resource.Error -> res
                is Resource.Loading -> res
            }
        }
    suspend fun updateWorldBook(id: String, req: WorldBookRequest): Resource<WorldBook> =
        safeCall { api.updateWorldBook(id, req) }.let { res ->
            when (res) {
                is Resource.Success -> Resource.Success(extractWorldBook(res.data))
                is Resource.Error -> res
                is Resource.Loading -> res
            }
        }
    suspend fun deleteWorldBook(id: String): Resource<Unit> = safeCall { api.deleteWorldBook(id) }.map { }
    suspend fun listEntries(bookId: String): Resource<List<WorldBookEntry>> = safeCall { api.listEntries(bookId) }
    suspend fun createEntry(bookId: String, req: WorldBookEntryRequest): Resource<WorldBookEntry> =
        safeCall { api.createEntry(bookId, req) }.let { res ->
            when (res) {
                is Resource.Success -> Resource.Success(extractEntry(res.data))
                is Resource.Error -> res
                is Resource.Loading -> res
            }
        }
    suspend fun updateEntry(bookId: String, entryId: String, req: WorldBookEntryRequest): Resource<WorldBookEntry> =
        safeCall { api.updateEntry(bookId, entryId, req) }.let { res ->
            when (res) {
                is Resource.Success -> Resource.Success(extractEntry(res.data))
                is Resource.Error -> res
                is Resource.Loading -> res
            }
        }
    suspend fun deleteEntry(bookId: String, entryId: String): Resource<Unit> = safeCall { api.deleteEntry(bookId, entryId) }.map { }

    /**
     * AI 批量生成世界书条目：调用 /api/world-books/{id}/ai-generate。
     * 后端会立即持久化新增的条目，并返回 {"success": true, "count": N, "entries": [...]}。
     */
    suspend fun aiGenerateWorldBookEntries(bookId: String, topic: String?): Resource<JsonElement> =
        safeCall { api.aiGenerateWorldBookEntries(bookId, mapOf("topic" to topic)) }

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
    suspend fun getFailoverQueue(purpose: String): Resource<JsonElement> = safeCall { api.getFailoverQueue(purpose) }
    suspend fun resetFailover(modelId: String? = null): Resource<JsonElement> {
        val body = com.google.gson.JsonObject().apply {
            modelId?.let { addProperty("model_id", it) }
        }
        return safeCall { api.resetFailover(body) }
    }
    suspend fun reorderFailover(purpose: String, modelIds: List<String>): Resource<JsonElement> {
        val priorities = com.google.gson.JsonArray().apply {
            modelIds.forEachIndexed { priority, id ->
                add(com.google.gson.JsonObject().apply {
                    addProperty("id", id)
                    addProperty("priority", priority)
                })
            }
        }
        val body = com.google.gson.JsonObject().apply {
            addProperty("purpose", purpose)
            add("priorities", priorities)
        }
        return safeCall { api.reorderFailover(body) }
    }

    /** 取单个模型的故障转移详情：健康状态 + token 用量 + 策略 + 价格。 */
    suspend fun getFailoverDetail(modelId: String): Resource<com.nekobot.app.data.model.FailoverModelDetail> =
        safeCall { api.getFailoverDetail(modelId) }

    /** 更新模型 token 限额与超时策略；三个数值字段必须非负，由调用方校验。 */
    suspend fun updateFailoverPolicy(
        modelId: String,
        tokenLimitDaily: Long,
        tokenLimitWeekly: Long,
        failoverTimeout: Int
    ): Resource<JsonElement> {
        val body = com.nekobot.app.data.model.FailoverPolicyUpdate(
            modelId = modelId,
            tokenLimitDaily = tokenLimitDaily,
            tokenLimitWeekly = tokenLimitWeekly,
            failoverTimeout = failoverTimeout
        )
        return safeCall { api.updateFailoverPolicy(body) }
    }

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
    suspend fun restoreFromArchive(id: String, turns: Int): Resource<JsonElement> =
        safeCall { api.restoreFromArchive(id, mapOf("turns" to turns)) }
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

    // ==================== 绑定角色 ====================
    suspend fun bindCharacter(sessionId: String, req: BindCharacterRequest): Resource<JsonElement> =
        safeCall { api.bindCharacter(sessionId, req) }

    // ==================== 消息收藏 ====================
    suspend fun listMessageFavorites(sessionId: String): Resource<JsonElement> =
        safeCall { api.listMessageFavorites(sessionId) }

    suspend fun updateMessageFavorites(sessionId: String, req: MessageFavoriteRequest): Resource<JsonElement> =
        safeCall { api.updateMessageFavorites(sessionId, req) }

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

    // ==================== Hook 管理 ====================
    suspend fun listHooks(scope: String? = null, event: String? = null, enabled: String? = null): Resource<List<Hook>> =
        safeCall { api.listHooks(scope, event, enabled) }.mapData { it.hooks }
    suspend fun createHook(req: HookRequest): Resource<Hook> =
        safeCall { api.createHook(req) }.mapData { it.hook ?: Hook() }
    suspend fun getHook(id: String): Resource<Hook> =
        safeCall { api.getHook(id) }.mapData { it.hook ?: Hook() }
    suspend fun updateHook(id: String, req: HookRequest): Resource<Hook> =
        safeCall { api.updateHook(id, req) }.mapData { it.hook ?: Hook() }
    suspend fun deleteHook(id: String): Resource<Unit> = safeCall { api.deleteHook(id) }.map { }
    suspend fun toggleHook(id: String): Resource<Hook> =
        safeCall { api.toggleHook(id) }.mapData { it.hook ?: Hook() }
    suspend fun testHook(body: JsonElement): Resource<JsonElement> = safeCall { api.testHook(body) }
    suspend fun listHookLogs(hookId: String? = null, limit: Int = 100): Resource<List<HookExecutionLog>> =
        safeCall { api.listHookLogs(hookId, limit) }.mapData { it.logs }
    suspend fun hookStats(): Resource<JsonElement> = safeCall { api.hookStats() }

    // ==================== 任务中心 ====================
    suspend fun listTasks(): Resource<List<TaskItem>> =
        safeCall { api.listTasks() }.mapData { it.items }
    suspend fun createTask(req: TaskRequest): Resource<TaskItem> =
        safeCall { api.createTask(req) }.mapData { it.task ?: TaskItem() }
    suspend fun updateTask(id: String, req: TaskRequest): Resource<TaskItem> =
        safeCall { api.updateTask(id, req) }.mapData { it.task ?: TaskItem() }
    suspend fun deleteTask(id: String): Resource<Unit> = safeCall { api.deleteTask(id) }.map { }
    suspend fun toggleTask(id: String): Resource<TaskItem> =
        safeCall { api.toggleTask(id) }.mapData { it.item ?: TaskItem() }
    suspend fun runTask(id: String): Resource<JsonElement> = safeCall { api.runTask(id) }

    // ==================== 工作流 ====================
    suspend fun listWorkflows(): Resource<List<Workflow>> = safeCall { api.listWorkflows() }
    suspend fun createWorkflow(req: WorkflowRequest): Resource<Workflow> = safeCall { api.createWorkflow(req) }
    suspend fun updateWorkflow(id: String, req: WorkflowRequest): Resource<Workflow> = safeCall { api.updateWorkflow(id, req) }
    suspend fun deleteWorkflow(id: String): Resource<Unit> = safeCall { api.deleteWorkflow(id) }.map { }
    suspend fun toggleWorkflow(id: String): Resource<Workflow> = safeCall { api.toggleWorkflow(id) }
    suspend fun executeWorkflow(id: String): Resource<JsonElement> = safeCall { api.executeWorkflow(id) }

    // ==================== 知识库 ====================
    suspend fun listKnowledge(): Resource<List<KnowledgeDocument>> = safeCall { api.listKnowledge() }
    suspend fun createKnowledge(req: KnowledgeDocumentRequest): Resource<KnowledgeDocument> = safeCall { api.createKnowledge(req) }
    suspend fun getKnowledge(id: String): Resource<KnowledgeDocument> = safeCall { api.getKnowledge(id) }
    suspend fun updateKnowledge(id: String, req: KnowledgeDocumentRequest): Resource<KnowledgeDocument> = safeCall { api.updateKnowledge(id, req) }
    suspend fun deleteKnowledge(id: String): Resource<Unit> = safeCall { api.deleteKnowledge(id) }.map { }
    suspend fun indexKnowledge(id: String): Resource<JsonElement> = safeCall { api.indexKnowledge(id) }
    suspend fun batchKnowledge(body: JsonElement): Resource<JsonElement> = safeCall { api.batchKnowledge(body) }
    suspend fun batchDeleteKnowledge(body: JsonElement): Resource<JsonElement> = safeCall { api.batchDeleteKnowledge(body) }
    suspend fun knowledgeStats(): Resource<KnowledgeStats> = safeCall { api.knowledgeStats() }
    suspend fun searchKnowledge(req: KnowledgeSearchRequest): Resource<List<KnowledgeSearchResult>> = safeCall { api.searchKnowledge(req) }
    suspend fun rebuildKnowledge(): Resource<JsonElement> = safeCall { api.rebuildKnowledge() }

    // ==================== Skills 配置 ====================
    suspend fun listSkills(): Resource<List<Skill>> = safeCall { api.listSkills() }
    suspend fun createSkill(req: SkillRequest): Resource<Skill> = safeCall { api.createSkill(req) }
    suspend fun updateSkill(id: String, req: SkillRequest): Resource<Skill> = safeCall { api.updateSkill(id, req) }
    suspend fun deleteSkill(id: String): Resource<Unit> = safeCall { api.deleteSkill(id) }.map { }
    suspend fun toggleSkill(id: String): Resource<Skill> = safeCall { api.toggleSkill(id) }

    // ==================== Tools 配置 ====================
    suspend fun listTools(): Resource<List<Tool>> = safeCall { api.listTools() }
    suspend fun createTool(req: ToolRequest): Resource<Tool> = safeCall { api.createTool(req) }
    suspend fun updateTool(id: String, req: ToolRequest): Resource<Tool> = safeCall { api.updateTool(id, req) }
    suspend fun deleteTool(id: String): Resource<Unit> = safeCall { api.deleteTool(id) }.map { }
    suspend fun toggleTool(id: String): Resource<Tool> = safeCall { api.toggleTool(id) }

    // ==================== MCP 服务 ====================
    suspend fun listMcpServers(): Resource<List<McpServer>> = safeCall { api.listMcpServers() }
    suspend fun createMcpServer(req: McpServerRequest): Resource<McpServer> = safeCall { api.createMcpServer(req) }
    suspend fun updateMcpServer(id: String, req: McpServerRequest): Resource<McpServer> = safeCall { api.updateMcpServer(id, req) }
    suspend fun deleteMcpServer(id: String): Resource<Unit> = safeCall { api.deleteMcpServer(id) }.map { }
    suspend fun connectMcpServer(id: String): Resource<JsonElement> = safeCall { api.connectMcpServer(id) }
    suspend fun disconnectMcpServer(id: String): Resource<JsonElement> = safeCall { api.disconnectMcpServer(id) }
    suspend fun mcpServerTools(id: String): Resource<JsonElement> = safeCall { api.mcpServerTools(id) }
    suspend fun testMcpServer(id: String): Resource<JsonElement> = safeCall { api.testMcpServer(id) }

    // ==================== 频道管理 ====================
    suspend fun channelPresets(): Resource<List<ChannelPreset>> =
        safeCall { api.channelPresets() }.mapData { it.presets }
    suspend fun createChannelFromPreset(presetId: String): Resource<Channel> =
        safeCall { api.createChannelFromPreset(presetId) }.mapData { it.channel ?: Channel() }
    suspend fun listChannels(): Resource<List<Channel>> =
        safeCall { api.listChannels() }.mapData { it.channels }
    suspend fun createChannel(req: ChannelRequest): Resource<Channel> =
        safeCall { api.createChannel(req) }.mapData { it.channel ?: Channel() }
    suspend fun updateChannel(id: String, req: ChannelRequest): Resource<Channel> =
        safeCall { api.updateChannel(id, req) }.mapData { it.channel ?: Channel() }
    suspend fun deleteChannel(id: String): Resource<Unit> = safeCall { api.deleteChannel(id) }.map { }
    /**
     * 切换频道启停状态。后端只返回 `{"success": true, "enabled": bool}`，
     * 不返回完整 Channel 对象，故返回新启用状态，调用方一般会触发 load() 刷新列表。
     */
    suspend fun toggleChannel(id: String): Resource<Boolean> =
        safeCall { api.toggleChannel(id) }.mapData { it.enabled ?: false }

    // ==================== 消息过滤 ====================
    suspend fun listMessageFilter(): Resource<MessageFilterConfig> = safeCall { api.listMessageFilter() }
    suspend fun createMessageFilterRule(req: MessageFilterRuleRequest): Resource<MessageFilterRule> = safeCall { api.createMessageFilterRule(req) }
    suspend fun updateMessageFilterRule(id: String, channel: String?, sessionId: String?, req: MessageFilterRuleRequest): Resource<MessageFilterRule> =
        safeCall { api.updateMessageFilterRule(id, channel, sessionId, req) }
    suspend fun deleteMessageFilterRule(id: String, channel: String? = null, sessionId: String? = null): Resource<Unit> =
        safeCall { api.deleteMessageFilterRule(id, channel, sessionId) }.map { }
    suspend fun toggleMessageFilter(enabled: Boolean): Resource<JsonElement> =
        safeCall { api.toggleMessageFilter(mapOf("enabled" to enabled)) }

    // ==================== TTS 试验场 ====================
    suspend fun listTtsVoices(): Resource<List<TtsVoice>> =
        safeCall { api.listTtsVoices() }.mapData { it.voices }
    suspend fun ttsPreview(req: TtsPreviewRequest): Resource<TtsPreviewResponse> = safeCall { api.ttsPreview(req) }
    /**
     * 上传自定义音色。后端返回 `{"success": true, "voice_id": ..., "name": ...}`，
     * 不含完整 TtsVoice 字段，故构造一个仅含 id/name 的对象返回。
     */
    suspend fun uploadTtsVoice(file: MultipartBody.Part, customName: RequestBody, text: RequestBody): Resource<TtsVoice> =
        safeCall { api.uploadTtsVoice(file, customName, text) }.mapData { res ->
            TtsVoice(id = res.voiceId ?: "", name = res.name ?: "")
        }

    /** 上传角色立绘/头像图片到服务器，返回服务器相对 URL 字符串 */
    suspend fun uploadPortrait(file: MultipartBody.Part): Resource<String> =
        safeCall { api.uploadPortrait(file) }.mapData { res ->
            res.url ?: throw IllegalStateException(res.error ?: "上传失败")
        }

    // ==================== 登录令牌 ====================
    suspend fun listLoginTokens(): Resource<List<LoginToken>> =
        safeCall { api.listLoginTokens() }.mapData { it.tokens }
    suspend fun createLoginToken(req: LoginTokenRequest): Resource<LoginTokenResponse> = safeCall { api.createLoginToken(req) }
    suspend fun deleteLoginToken(tokenHash: String): Resource<Unit> = safeCall { api.deleteLoginToken(tokenHash) }.map { }
    suspend fun deleteAllLoginTokens(): Resource<Unit> = safeCall { api.deleteAllLoginTokens() }.map { }

    // ==================== API Keys ====================
    suspend fun listApiKeys(): Resource<List<ApiKey>> =
        safeCall { api.listApiKeys() }.mapData { it.keys }
    suspend fun getApiKey(id: String): Resource<ApiKey> =
        safeCall { api.getApiKey(id) }.mapData { it.key ?: ApiKey() }
    suspend fun createApiKey(req: ApiKeyRequest): Resource<ApiKey> =
        safeCall { api.createApiKey(req) }.mapData { it.key ?: ApiKey(name = req.name) }
    suspend fun updateApiKey(id: String, req: ApiKeyRequest): Resource<ApiKey> =
        safeCall { api.updateApiKey(id, req) }.mapData { it.key ?: ApiKey(id = id, name = req.name) }
    suspend fun deleteApiKey(id: String): Resource<Unit> = safeCall { api.deleteApiKey(id) }.map { }

    // ==================== 会话归档 ====================
    suspend fun archiveSession(id: String): Resource<JsonElement> = safeCall { api.archiveSession(id) }
    suspend fun restoreSession(id: String): Resource<JsonElement> = safeCall { api.restoreSession(id) }
    suspend fun archivePlotBranch(id: String, req: PlotSwitchRequest): Resource<JsonElement> =
        safeCall { api.archivePlotBranch(id, req) }

    // ==================== 故事图 ====================
    suspend fun plotToggle(req: PlotToggleRequest): Resource<JsonElement> = safeCall { api.plotToggle(req) }
    suspend fun plotRealTimeSyncToggle(body: Map<String, Any>): Resource<JsonElement> = safeCall { api.plotRealTimeSyncToggle(body) }
    suspend fun plotGraph(conversationId: String): Resource<PlotGraphData> = safeCall { api.plotGraph(conversationId) }
    suspend fun plotLatestChoices(conversationId: String): Resource<JsonElement> = safeCall { api.plotLatestChoices(conversationId) }
    suspend fun plotMermaid(conversationId: String): Resource<JsonElement> = safeCall { api.plotMermaid(conversationId) }
    suspend fun plotSelect(conversationId: String, req: PlotSelectRequest): Resource<JsonElement> = safeCall { api.plotSelect(conversationId, req) }
    suspend fun plotRegenerateChoices(conversationId: String): Resource<JsonElement> = safeCall { api.plotRegenerateChoices(conversationId) }
    suspend fun plotRollback(conversationId: String, req: PlotRollbackRequest): Resource<JsonElement> = safeCall { api.plotRollback(conversationId, req) }
    suspend fun plotBranchPreview(conversationId: String, nodeId: String): Resource<JsonElement> = safeCall { api.plotBranchPreview(conversationId, nodeId) }
    suspend fun plotSwitch(conversationId: String, req: PlotSwitchRequest): Resource<JsonElement> = safeCall { api.plotSwitch(conversationId, req) }
    suspend fun plotBranch(conversationId: String, req: PlotBranchRequest): Resource<JsonElement> = safeCall { api.plotBranch(conversationId, req) }

    // ==================== WebDAV 备份 ====================
    suspend fun getWebDavConfig(): Resource<WebDavConfig> = safeCall { api.getWebDavConfig() }
    suspend fun saveWebDavConfig(config: WebDavConfig): Resource<JsonElement> = safeCall { api.saveWebDavConfig(config) }
    suspend fun testWebDav(req: WebDavTestRequest): Resource<JsonElement> = safeCall { api.testWebDav(req) }
    suspend fun webDavInfo(): Resource<JsonElement> = safeCall { api.webDavInfo() }
    suspend fun webDavBackup(req: WebDavBackupRequest): Resource<JsonElement> = safeCall { api.webDavBackup(req) }
    suspend fun webDavSync(req: WebDavBackupRequest): Resource<JsonElement> = safeCall { api.webDavSync(req) }

    // ==================== 配置迁移 ====================
    suspend fun exportConfig(req: ConfigExportRequest): Response<ResponseBody> = api.exportConfig(req)
    suspend fun importConfig(file: MultipartBody.Part, password: RequestBody, overwrite: RequestBody): Resource<JsonElement> =
        safeCall { api.importConfig(file, password, overwrite) }

    // ==================== 功能开关 ====================
    suspend fun listSwitches(): Resource<JsonElement> = safeCall { api.listSwitches() }
    suspend fun toggleSwitch(req: SwitchStateRequest): Resource<SwitchToggleResponse> = safeCall { api.toggleSwitch(req) }

    // ==================== 语音识别（STT）====================
    suspend fun sttTranscribe(audio: MultipartBody.Part, language: RequestBody): Resource<SttTranscribeResponse> =
        safeCall { api.sttTranscribe(audio, language) }
}


/** 把无数据成功结果映射为 Unit */
fun <T> Resource<T>.map(transform: (T) -> Unit): Resource<Unit> = when (this) {
    is Resource.Success -> Resource.Success(transform(data))
    is Resource.Error -> this
    is Resource.Loading -> this
}

/** 把成功结果的数据映射为另一种类型（用于拆解后端包装响应）。 */
fun <T, R> Resource<T>.mapData(transform: (T) -> R): Resource<R> = when (this) {
    is Resource.Success -> Resource.Success(transform(data))
    is Resource.Error -> this
    is Resource.Loading -> this
}
