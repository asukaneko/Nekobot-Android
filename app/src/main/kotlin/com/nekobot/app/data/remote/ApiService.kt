package com.nekobot.app.data.remote

import com.google.gson.JsonElement
import com.nekobot.app.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ==================== 认证 ====================
    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("api/logout")
    suspend fun logout(): Response<ApiResult>

    @POST("api/verify-token")
    suspend fun verifyToken(@Body body: Map<String, String>): Response<JsonElement>

    @GET("api/startup-status")
    suspend fun startupStatus(): Response<JsonElement>

    // ==================== 会话 ====================
    @GET("api/sessions")
    suspend fun listSessions(): Response<List<Session>>

    @POST("api/sessions")
    suspend fun createSession(@Body body: CreateSessionRequest): Response<Session>

    @GET("api/sessions/{id}")
    suspend fun getSession(@Path("id") id: String): Response<Session>

    @PUT("api/sessions/{id}")
    suspend fun updateSession(
        @Path("id") id: String,
        @Body body: UpdateSessionRequest
    ): Response<Session>

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): Response<ApiResult>

    @POST("api/sessions/{id}/chat")
    suspend fun chat(@Path("id") id: String, @Body body: ChatRequest): Response<ApiResult>

    @POST("api/sessions/{id}/regenerate")
    suspend fun regenerate(
        @Path("id") id: String,
        @Body body: Map<String, String?> = emptyMap()
    ): Response<ApiResult>

    @POST("api/stop")
    suspend fun stopGeneration(@Body body: Map<String, String>): Response<ApiResult>

    @GET("api/sessions/{id}/messages")
    suspend fun listMessages(@Path("id") id: String): Response<List<Message>>

    @POST("api/sessions/{id}/messages")
    suspend fun addMessage(
        @Path("id") id: String,
        @Body body: SendMessageRequest
    ): Response<Message>

    @PUT("api/sessions/{id}/messages/{messageId}")
    suspend fun updateMessage(
        @Path("id") id: String,
        @Path("messageId") messageId: String,
        @Body body: SendMessageRequest
    ): Response<Message>

    @DELETE("api/sessions/{id}/messages/{messageId}")
    suspend fun deleteMessage(
        @Path("id") id: String,
        @Path("messageId") messageId: String
    ): Response<ApiResult>

    @DELETE("api/sessions/{id}/messages")
    suspend fun clearMessages(@Path("id") id: String): Response<ApiResult>

    // ==================== 角色 ====================
    @GET("api/characters")
    suspend fun listCharacters(): Response<List<Character>>

    @POST("api/characters")
    suspend fun createCharacter(@Body body: CharacterRequest): Response<Character>

    @GET("api/characters/{id}")
    suspend fun getCharacter(@Path("id") id: String): Response<Character>

    @PUT("api/characters/{id}")
    suspend fun updateCharacter(
        @Path("id") id: String,
        @Body body: CharacterRequest
    ): Response<Character>

    @DELETE("api/characters/{id}")
    suspend fun deleteCharacter(@Path("id") id: String): Response<ApiResult>

    // ==================== 完整角色卡数据源（custom-presets）====================
    /**
     * 角色卡的**完整数据源**（含 avatar/basicInfo/personality/scenario/
     * firstMessage/exampleDialogues/responseFormat/rules/state/systemPrompt/portrait 等全字段）。
     * 数据来源：`data/web/custom_personality_presets.json`。
     * 注意：与 `/api/characters`（运行时快照）不同，本接口返回完整资料。
     *
     * 重要：根据迁移指南，
     *  - GET 单条端点不存在（无 `/api/personality/custom-presets/<id>`），单条需在客户端 filter 列表
     *  - POST 响应直接返回 preset 对象，不是 `{"character": ...}`
     *  - 创建请求体不要传 id / systemPrompt / greeting（后端自动管理）
     *  - PUT 路径参数语义是 preset_id，等价于 character_id
     *  - DELETE 静默成功，删除不存在也返回 success
     */
    @GET("api/personality/custom-presets")
    suspend fun listCharacterPresets(): Response<JsonElement>

    @POST("api/personality/custom-presets")
    suspend fun createCharacterPreset(@Body body: JsonElement): Response<JsonElement>

    @PUT("api/personality/custom-presets/{id}")
    suspend fun updateCharacterPreset(
        @Path("id") id: String,
        @Body body: JsonElement
    ): Response<JsonElement>

    @DELETE("api/personality/custom-presets/{id}")
    suspend fun deleteCharacterPreset(@Path("id") id: String): Response<JsonElement>

    // ==================== 世界书 ====================
    @GET("api/world-books")
    suspend fun listWorldBooks(): Response<List<WorldBook>>

    @POST("api/world-books")
    suspend fun createWorldBook(@Body body: WorldBookRequest): Response<WorldBook>

    @GET("api/world-books/{id}")
    suspend fun getWorldBook(@Path("id") id: String): Response<WorldBook>

    @PUT("api/world-books/{id}")
    suspend fun updateWorldBook(
        @Path("id") id: String,
        @Body body: WorldBookRequest
    ): Response<WorldBook>

    @DELETE("api/world-books/{id}")
    suspend fun deleteWorldBook(@Path("id") id: String): Response<ApiResult>

    @GET("api/world-books/{id}/entries")
    suspend fun listEntries(@Path("id") bookId: String): Response<List<WorldBookEntry>>

    @POST("api/world-books/{id}/entries")
    suspend fun createEntry(
        @Path("id") bookId: String,
        @Body body: WorldBookEntryRequest
    ): Response<WorldBookEntry>

    @PUT("api/world-books/{id}/entries/{entryId}")
    suspend fun updateEntry(
        @Path("id") bookId: String,
        @Path("entryId") entryId: String,
        @Body body: WorldBookEntryRequest
    ): Response<WorldBookEntry>

    @DELETE("api/world-books/{id}/entries/{entryId}")
    suspend fun deleteEntry(
        @Path("id") bookId: String,
        @Path("entryId") entryId: String
    ): Response<ApiResult>

    // ==================== AI 配置 ====================
    @GET("api/ai-config")
    suspend fun getAiConfig(): Response<JsonElement>

    @PUT("api/ai-config")
    suspend fun updateAiConfig(@Body body: JsonElement): Response<ApiResult>

    @GET("api/ai-config/all-purposes")
    suspend fun getAllPurposesConfig(): Response<JsonElement>

    @POST("api/ai-config/test")
    suspend fun testAiConfig(@Body body: JsonElement): Response<TestResponse>

    // ==================== AI 模型 ====================
    @GET("api/ai-models")
    suspend fun listAiModels(): Response<AiModelListResponse>

    @POST("api/ai-models")
    suspend fun createAiModel(@Body body: AiModelRequest): Response<AiModel>

    @PUT("api/ai-models/{id}")
    suspend fun updateAiModel(
        @Path("id") id: String,
        @Body body: AiModelRequest
    ): Response<AiModel>

    @DELETE("api/ai-models/{id}")
    suspend fun deleteAiModel(@Path("id") id: String): Response<ApiResult>

    @POST("api/ai-models/{id}/apply")
    suspend fun applyAiModel(@Path("id") id: String): Response<ApiResult>

    @POST("api/ai-models/{id}/toggle")
    suspend fun toggleAiModel(@Path("id") id: String): Response<ApiResult>

    @POST("api/ai-models/{id}/clone")
    suspend fun cloneAiModel(@Path("id") id: String): Response<AiModel>

    @POST("api/ai-models/{id}/test")
    suspend fun testAiModel(@Path("id") id: String): Response<TestResponse>

    @POST("api/ai-models/fetch-models")
    suspend fun fetchModels(@Body body: FetchModelsRequest): Response<FetchModelsResponse>

    @GET("api/ai-models/protocols")
    suspend fun listProtocols(): Response<JsonElement>

    @GET("api/ai-models/purposes")
    suspend fun listPurposes(): Response<JsonElement>

    @GET("api/ai-models/active-by-purpose")
    suspend fun activeByPurpose(): Response<JsonElement>

    // ==================== Token 用量 ====================
    @GET("api/tokens")
    suspend fun tokenStats(
        @Query("dateRange") dateRange: String? = null,
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null
    ): Response<TokenStats>

    @GET("api/tokens/rankings")
    suspend fun tokenRankings(): Response<TokenRankings>

    // ==================== 统计 ====================
    @GET("api/stats")
    suspend fun stats(): Response<JsonElement>

    @GET("api/stats/messages")
    suspend fun messageStats(@Query("period") period: String = "day"): Response<JsonElement>

    // ==================== 角色状态 / 历程 ====================
    /** 单会话状态历程时间线：返回该会话内角色状态随消息推进的变化。 */
    @GET("api/sessions/{id}/runtime-timeline")
    suspend fun sessionRuntimeTimeline(@Path("id") id: String): Response<JsonElement>

    /** 压缩会话上下文（将早期消息摘要化以节省 token）。 */
    @POST("api/sessions/{id}/compress")
    suspend fun compressContext(@Path("id") id: String): Response<JsonElement>

    /** 从指定消息处分叉新会话（分支）。 */
    @POST("api/sessions/{id}/fork")
    suspend fun forkSession(@Path("id") id: String, @Body body: ForkRequest): Response<JsonElement>

    /** 获取会话的自定义提示词列表（custom_prompts）。 */
    @GET("api/sessions/{id}/custom-prompts")
    suspend fun getCustomPrompts(@Path("id") id: String): Response<JsonElement>

    /** 全量更新会话的自定义提示词列表。 */
    @PUT("api/sessions/{id}/custom-prompts")
    suspend fun updateCustomPrompts(
        @Path("id") id: String,
        @Body body: Map<String, Any>
    ): Response<JsonElement>

    /** 跨渠道（QQ/Telegram/Feishu/Web）状态历程，合成 timeline sessions 列表。 */
    @GET("api/channel_runtime_timeline")
    suspend fun channelRuntimeTimeline(
        @Query("channel") channel: String? = null,
        @Query("character_id") characterId: String? = null
    ): Response<JsonElement>

    // ==================== 剧情模式 ====================
    @GET("api/plot/{conversationId}/latest-choices")
    suspend fun getLatestPlotChoices(@Path("conversationId") conversationId: String): Response<JsonElement>

    @POST("api/plot/{conversationId}/select")
    suspend fun selectPlotChoice(
        @Path("conversationId") conversationId: String,
        @Body body: Map<String, String>
    ): Response<ApiResult>

    @POST("api/plot/{conversationId}/regenerate-choices")
    suspend fun regeneratePlotChoices(@Path("conversationId") conversationId: String): Response<JsonElement>

    /** 单角色当前运行时状态（心情/能量等），scope_id 形如 web:<session_id>。 */
    @GET("api/characters/{id}/state")
    suspend fun characterState(
        @Path("id") id: String,
        @Query("scope_id") scopeId: String
    ): Response<JsonElement>

    /** 单角色对某 target 的关系状态（好感/信任/熟悉度等）。 */
    @GET("api/characters/{id}/relationships")
    suspend fun characterRelationships(
        @Path("id") id: String,
        @Query("target_id") targetId: String
    ): Response<JsonElement>

    // ==================== 系统设置 ====================
    @GET("api/settings")
    suspend fun getSettings(): Response<JsonElement>

    @PUT("api/settings")
    suspend fun updateSettings(@Body body: JsonElement): Response<JsonElement>

    @POST("api/system/reload-config")
    suspend fun reloadConfig(): Response<ApiResult>

    @GET("api/logs")
    suspend fun listLogs(
        @Query("level") level: String = "all",
        @Query("limit") limit: Int = 100
    ): Response<JsonElement>

    // ==================== 角色记忆（MemoryFS + 旧版）====================
    /** 列出 MemoryFS 逻辑文件，可选按角色过滤。 */
    @GET("api/review/memory-fs")
    suspend fun listMemoryFs(
        @Query("character_id") characterId: String? = null
    ): Response<MemoryFsListResponse>

    /** 读取指定路径的 MemoryFS 文件。 */
    @GET("api/review/memory-fs/read")
    suspend fun readMemoryFs(@Query("path") path: String): Response<MemoryFile>

    /** 删除指定路径的 MemoryFS 文件。 */
    @DELETE("api/review/memory-fs/delete")
    suspend fun deleteMemoryFs(
        @Query("path") path: String,
        @Query("character_id") characterId: String? = null
    ): Response<ApiResult>

    /** 旧版记忆列表，可按 type/target_id/character_name 过滤。 */
    @GET("api/memory")
    suspend fun listLegacyMemory(
        @Query("type") type: String? = null,
        @Query("target_id") targetId: String? = null,
        @Query("character_name") characterName: String? = null
    ): Response<LegacyMemoryListResponse>

    /** 新增旧版记忆。 */
    @POST("api/memory")
    suspend fun createLegacyMemory(@Body body: LegacyMemoryRequest): Response<ApiResult>

    /** 更新旧版记忆。 */
    @PUT("api/memory/{memoryId}")
    suspend fun updateLegacyMemory(
        @Path("memoryId") memoryId: String,
        @Body body: JsonElement
    ): Response<JsonElement>

    /** 删除单条旧版记忆。 */
    @DELETE("api/memory/{memoryId}")
    suspend fun deleteLegacyMemory(@Path("memoryId") memoryId: String): Response<ApiResult>

    /** 导出全部旧版记忆。 */
    @GET("api/memory/export")
    suspend fun exportLegacyMemory(): Response<LegacyMemoryListResponse>

    // ==================== 工作区 ====================
    /** 列出会话工作区文件 */
    @GET("api/sessions/{id}/workspace/files")
    suspend fun listWorkspaceFiles(
        @Path("id") id: String,
        @Query("path") path: String? = null
    ): Response<JsonElement>

    /** 上传文件到会话工作区（multipart） */
    @Multipart
    @POST("api/sessions/{id}/workspace/upload")
    suspend fun uploadWorkspaceFile(
        @Path("id") id: String,
        @Part file: MultipartBody.Part
    ): Response<JsonElement>

    /** 删除工作区文件 */
    @DELETE("api/sessions/{id}/workspace/files/{filename}")
    suspend fun deleteWorkspaceFile(
        @Path("id") id: String,
        @Path("filename") filename: String
    ): Response<JsonElement>

    /** 下载工作区文件 */
    @Streaming
    @GET("api/sessions/{id}/workspace/files/{filename}")
    suspend fun downloadWorkspaceFile(
        @Path("id") id: String,
        @Path("filename") filename: String
    ): Response<ResponseBody>
}
