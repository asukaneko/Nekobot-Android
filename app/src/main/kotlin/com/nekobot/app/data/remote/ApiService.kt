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

    @POST("api/sessions/{id}/public")
    suspend fun makeSessionPublic(
        @Path("id") id: String,
        @Body body: PublicShareRequest
    ): Response<PublicSessionStatus>

    @DELETE("api/sessions/{id}/public")
    suspend fun removeSessionPublic(@Path("id") id: String): Response<ApiResult>

    @GET("api/sessions/{id}/public/status")
    suspend fun getSessionPublicStatus(@Path("id") id: String): Response<PublicSessionStatus>

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

    /** AI 根据自然语言描述生成完整角色卡 */
    @POST("api/personality/ai-generate")
    suspend fun aiGenerateCharacter(@Body body: Map<String, String>): Response<JsonElement>

    // ==================== 世界书 ====================
    @GET("api/world-books")
    suspend fun listWorldBooks(): Response<List<WorldBook>>

    @POST("api/world-books")
    suspend fun createWorldBook(@Body body: WorldBookRequest): Response<JsonElement>

    @GET("api/world-books/{id}")
    suspend fun getWorldBook(@Path("id") id: String): Response<WorldBook>

    @PUT("api/world-books/{id}")
    suspend fun updateWorldBook(
        @Path("id") id: String,
        @Body body: WorldBookRequest
    ): Response<JsonElement>

    @DELETE("api/world-books/{id}")
    suspend fun deleteWorldBook(@Path("id") id: String): Response<ApiResult>

    @GET("api/world-books/{id}/entries")
    suspend fun listEntries(@Path("id") bookId: String): Response<List<WorldBookEntry>>

    @POST("api/world-books/{id}/entries")
    suspend fun createEntry(
        @Path("id") bookId: String,
        @Body body: WorldBookEntryRequest
    ): Response<JsonElement>

    @PUT("api/world-books/{id}/entries/{entryId}")
    suspend fun updateEntry(
        @Path("id") bookId: String,
        @Path("entryId") entryId: String,
        @Body body: WorldBookEntryRequest
    ): Response<JsonElement>

    @DELETE("api/world-books/{id}/entries/{entryId}")
    suspend fun deleteEntry(
        @Path("id") bookId: String,
        @Path("entryId") entryId: String
    ): Response<ApiResult>

    /** AI 根据绑定角色与主题批量生成世界书条目（已持久化到后端） */
    @POST("api/world-books/{id}/ai-generate")
    suspend fun aiGenerateWorldBookEntries(
        @Path("id") bookId: String,
        @Body body: Map<String, String?>
    ): Response<JsonElement>

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

    @GET("api/ai-models/failover-queue/{purpose}")
    suspend fun getFailoverQueue(@Path("purpose") purpose: String): Response<JsonElement>

    @POST("api/ai-models/failover-reset")
    suspend fun resetFailover(@Body body: JsonElement): Response<JsonElement>

    @POST("api/ai-models/failover-reorder")
    suspend fun reorderFailover(@Body body: JsonElement): Response<JsonElement>

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

    /** 从归档会话提取 N 轮对话回到当前会话。 */
    @POST("api/sessions/{id}/restore-from-archive")
    suspend fun restoreFromArchive(
        @Path("id") id: String,
        @Body body: Map<String, Int>
    ): Response<JsonElement>

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

    // ==================== Hook 管理 ====================
    @GET("api/hooks")
    suspend fun listHooks(
        @Query("scope") scope: String? = null,
        @Query("event") event: String? = null,
        @Query("enabled") enabled: String? = null
    ): Response<HookListResponse>

    @POST("api/hooks")
    suspend fun createHook(@Body body: HookRequest): Response<HookResponse>

    @GET("api/hooks/{id}")
    suspend fun getHook(@Path("id") id: String): Response<HookResponse>

    @PUT("api/hooks/{id}")
    suspend fun updateHook(@Path("id") id: String, @Body body: HookRequest): Response<HookResponse>

    @DELETE("api/hooks/{id}")
    suspend fun deleteHook(@Path("id") id: String): Response<ApiResult>

    @POST("api/hooks/{id}/toggle")
    suspend fun toggleHook(@Path("id") id: String): Response<HookResponse>

    @POST("api/hooks/test")
    suspend fun testHook(@Body body: JsonElement): Response<JsonElement>

    @GET("api/hooks/logs")
    suspend fun listHookLogs(
        @Query("hook_id") hookId: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<HookLogListResponse>

    @GET("api/hooks/stats")
    suspend fun hookStats(): Response<JsonElement>

    // ==================== 任务中心 ====================
    @GET("api/task-center")
    suspend fun listTasks(): Response<TaskListResponse>

    @POST("api/task-center")
    suspend fun createTask(@Body body: TaskRequest): Response<TaskResponse>

    @PUT("api/task-center/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body body: TaskRequest): Response<TaskResponse>

    @DELETE("api/task-center/{id}")
    suspend fun deleteTask(@Path("id") id: String): Response<ApiResult>

    @POST("api/task-center/{id}/toggle")
    suspend fun toggleTask(@Path("id") id: String): Response<TaskItemResponse>

    @POST("api/task-center/{id}/run")
    suspend fun runTask(@Path("id") id: String): Response<JsonElement>

    // ==================== 工作流 ====================
    @GET("api/workflows")
    suspend fun listWorkflows(): Response<List<Workflow>>

    @POST("api/workflows")
    suspend fun createWorkflow(@Body body: WorkflowRequest): Response<Workflow>

    @PUT("api/workflows/{id}")
    suspend fun updateWorkflow(@Path("id") id: String, @Body body: WorkflowRequest): Response<Workflow>

    @DELETE("api/workflows/{id}")
    suspend fun deleteWorkflow(@Path("id") id: String): Response<ApiResult>

    @POST("api/workflows/{id}/toggle")
    suspend fun toggleWorkflow(@Path("id") id: String): Response<Workflow>

    @POST("api/workflows/{id}/execute")
    suspend fun executeWorkflow(@Path("id") id: String): Response<JsonElement>

    // ==================== 知识库 ====================
    @GET("api/knowledge")
    suspend fun listKnowledge(): Response<List<KnowledgeDocument>>

    @POST("api/knowledge")
    suspend fun createKnowledge(@Body body: KnowledgeDocumentRequest): Response<KnowledgeDocument>

    @GET("api/knowledge/{id}")
    suspend fun getKnowledge(@Path("id") id: String): Response<KnowledgeDocument>

    @PUT("api/knowledge/{id}")
    suspend fun updateKnowledge(@Path("id") id: String, @Body body: KnowledgeDocumentRequest): Response<KnowledgeDocument>

    @DELETE("api/knowledge/{id}")
    suspend fun deleteKnowledge(@Path("id") id: String): Response<ApiResult>

    @POST("api/knowledge/{id}/index")
    suspend fun indexKnowledge(@Path("id") id: String): Response<JsonElement>

    @POST("api/knowledge/batch")
    suspend fun batchKnowledge(@Body body: JsonElement): Response<JsonElement>

    @POST("api/knowledge/batch-delete")
    suspend fun batchDeleteKnowledge(@Body body: JsonElement): Response<JsonElement>

    @GET("api/knowledge/stats")
    suspend fun knowledgeStats(): Response<KnowledgeStats>

    @POST("api/knowledge/search")
    suspend fun searchKnowledge(@Body body: KnowledgeSearchRequest): Response<List<KnowledgeSearchResult>>

    @POST("api/knowledge/rebuild")
    suspend fun rebuildKnowledge(): Response<JsonElement>

    // ==================== Skills 配置 ====================
    @GET("api/skills")
    suspend fun listSkills(): Response<List<Skill>>

    @POST("api/skills")
    suspend fun createSkill(@Body body: SkillRequest): Response<Skill>

    @PUT("api/skills/{id}")
    suspend fun updateSkill(@Path("id") id: String, @Body body: SkillRequest): Response<Skill>

    @DELETE("api/skills/{id}")
    suspend fun deleteSkill(@Path("id") id: String): Response<ApiResult>

    @POST("api/skills/{id}/toggle")
    suspend fun toggleSkill(@Path("id") id: String): Response<Skill>

    // ==================== Tools 配置 ====================
    @GET("api/tools")
    suspend fun listTools(): Response<List<Tool>>

    @POST("api/tools")
    suspend fun createTool(@Body body: ToolRequest): Response<Tool>

    @PUT("api/tools/{id}")
    suspend fun updateTool(@Path("id") id: String, @Body body: ToolRequest): Response<Tool>

    @DELETE("api/tools/{id}")
    suspend fun deleteTool(@Path("id") id: String): Response<ApiResult>

    @POST("api/tools/{id}/toggle")
    suspend fun toggleTool(@Path("id") id: String): Response<Tool>

    // ==================== MCP 服务 ====================
    @GET("api/mcp-servers")
    suspend fun listMcpServers(): Response<List<McpServer>>

    @POST("api/mcp-servers")
    suspend fun createMcpServer(@Body body: McpServerRequest): Response<McpServer>

    @PUT("api/mcp-servers/{id}")
    suspend fun updateMcpServer(@Path("id") id: String, @Body body: McpServerRequest): Response<McpServer>

    @DELETE("api/mcp-servers/{id}")
    suspend fun deleteMcpServer(@Path("id") id: String): Response<ApiResult>

    @POST("api/mcp-servers/{id}/connect")
    suspend fun connectMcpServer(@Path("id") id: String): Response<JsonElement>

    @POST("api/mcp-servers/{id}/disconnect")
    suspend fun disconnectMcpServer(@Path("id") id: String): Response<JsonElement>

    @GET("api/mcp-servers/{id}/tools")
    suspend fun mcpServerTools(@Path("id") id: String): Response<JsonElement>

    @POST("api/mcp-servers/{id}/test")
    suspend fun testMcpServer(@Path("id") id: String): Response<JsonElement>

    // ==================== 频道管理 ====================
    @GET("api/channels/presets")
    suspend fun channelPresets(): Response<ChannelPresetListResponse>

    @POST("api/channels/presets/{presetId}")
    suspend fun createChannelFromPreset(@Path("presetId") presetId: String): Response<ChannelResponse>

    @GET("api/channels")
    suspend fun listChannels(): Response<ChannelListResponse>

    @POST("api/channels")
    suspend fun createChannel(@Body body: ChannelRequest): Response<ChannelResponse>

    @PUT("api/channels/{id}")
    suspend fun updateChannel(@Path("id") id: String, @Body body: ChannelRequest): Response<ChannelResponse>

    @DELETE("api/channels/{id}")
    suspend fun deleteChannel(@Path("id") id: String): Response<ApiResult>

    @POST("api/channels/{id}/toggle")
    suspend fun toggleChannel(@Path("id") id: String): Response<ChannelToggleResponse>

    // ==================== 消息过滤 ====================
    @GET("api/message-filter")
    suspend fun listMessageFilter(): Response<MessageFilterConfig>

    @POST("api/message-filter")
    suspend fun createMessageFilterRule(@Body body: MessageFilterRuleRequest): Response<MessageFilterRule>

    @PUT("api/message-filter/{id}")
    suspend fun updateMessageFilterRule(
        @Path("id") id: String,
        @Query("channel") channel: String? = null,
        @Query("session_id") sessionId: String? = null,
        @Body body: MessageFilterRuleRequest
    ): Response<MessageFilterRule>

    @DELETE("api/message-filter/{id}")
    suspend fun deleteMessageFilterRule(
        @Path("id") id: String,
        @Query("channel") channel: String? = null,
        @Query("session_id") sessionId: String? = null
    ): Response<ApiResult>

    @POST("api/message-filter/toggle")
    suspend fun toggleMessageFilter(@Body body: Map<String, Boolean>): Response<JsonElement>

    // ==================== TTS 试验场 ====================
    @GET("api/tts/voices")
    suspend fun listTtsVoices(): Response<TtsVoiceListResponse>

    @POST("api/tts/preview")
    suspend fun ttsPreview(@Body body: TtsPreviewRequest): Response<TtsPreviewResponse>

    @Multipart
    @POST("api/tts/upload-voice")
    suspend fun uploadTtsVoice(
        @Part file: MultipartBody.Part,
        @Part("customName") customName: RequestBody,
        @Part("text") text: RequestBody
    ): Response<TtsVoiceUploadResponse>

    /** 上传角色立绘/头像图片到服务器，返回服务器相对 URL */
    @Multipart
    @POST("api/personality/portrait")
    suspend fun uploadPortrait(
        @Part file: MultipartBody.Part
    ): Response<PortraitUploadResponse>

    // ==================== 登录令牌 ====================
    @GET("api/login-tokens")
    suspend fun listLoginTokens(): Response<LoginTokenListResponse>

    @POST("api/login-tokens")
    suspend fun createLoginToken(@Body body: LoginTokenRequest): Response<LoginTokenResponse>

    @DELETE("api/login-tokens/{tokenHash}")
    suspend fun deleteLoginToken(@Path("tokenHash") tokenHash: String): Response<ApiResult>

    @DELETE("api/login-tokens")
    suspend fun deleteAllLoginTokens(): Response<ApiResult>

    // ==================== API Keys ====================
    @GET("api/api-keys")
    suspend fun listApiKeys(): Response<ApiKeyListResponse>

    @GET("api/api-keys/{id}")
    suspend fun getApiKey(@Path("id") id: String): Response<ApiKeyResponse>

    @POST("api/api-keys")
    suspend fun createApiKey(@Body body: ApiKeyRequest): Response<ApiKeyResponse>

    @PUT("api/api-keys/{id}")
    suspend fun updateApiKey(@Path("id") id: String, @Body body: ApiKeyRequest): Response<ApiKeyResponse>

    @DELETE("api/api-keys/{id}")
    suspend fun deleteApiKey(@Path("id") id: String): Response<ApiResult>

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

    // ==================== 会话归档 ====================
    @POST("api/sessions/{id}/archive")
    suspend fun archiveSession(@Path("id") id: String): Response<JsonElement>

    @POST("api/sessions/{id}/restore")
    suspend fun restoreSession(@Path("id") id: String): Response<JsonElement>

    @POST("api/sessions/{id}/archive-branch")
    suspend fun archivePlotBranch(
        @Path("id") id: String,
        @Body body: PlotSwitchRequest
    ): Response<JsonElement>

    // ==================== 故事图（Plot Graph）====================
    @POST("api/plot/toggle")
    suspend fun plotToggle(@Body body: PlotToggleRequest): Response<JsonElement>

    @POST("api/plot/real-time-sync/toggle")
    suspend fun plotRealTimeSyncToggle(
        @Body body: Map<String, Any>
    ): Response<JsonElement>

    @GET("api/plot/{conversationId}/graph")
    suspend fun plotGraph(@Path("conversationId") conversationId: String): Response<PlotGraphData>

    @GET("api/plot/{conversationId}/latest-choices")
    suspend fun plotLatestChoices(@Path("conversationId") conversationId: String): Response<JsonElement>

    @GET("api/plot/{conversationId}/mermaid")
    suspend fun plotMermaid(@Path("conversationId") conversationId: String): Response<JsonElement>

    @POST("api/plot/{conversationId}/select")
    suspend fun plotSelect(
        @Path("conversationId") conversationId: String,
        @Body body: PlotSelectRequest
    ): Response<JsonElement>

    @POST("api/plot/{conversationId}/regenerate-choices")
    suspend fun plotRegenerateChoices(@Path("conversationId") conversationId: String): Response<JsonElement>

    @POST("api/plot/{conversationId}/rollback")
    suspend fun plotRollback(
        @Path("conversationId") conversationId: String,
        @Body body: PlotRollbackRequest
    ): Response<JsonElement>

    @GET("api/plot/{conversationId}/branch-preview")
    suspend fun plotBranchPreview(
        @Path("conversationId") conversationId: String,
        @Query("node_id") nodeId: String
    ): Response<JsonElement>

    @POST("api/plot/{conversationId}/switch")
    suspend fun plotSwitch(
        @Path("conversationId") conversationId: String,
        @Body body: PlotSwitchRequest
    ): Response<JsonElement>

    @POST("api/plot/{conversationId}/branch")
    suspend fun plotBranch(
        @Path("conversationId") conversationId: String,
        @Body body: PlotBranchRequest
    ): Response<JsonElement>

    // ==================== WebDAV 备份 ====================
    @GET("api/webdav/config")
    suspend fun getWebDavConfig(): Response<WebDavConfig>

    @PUT("api/webdav/config")
    suspend fun saveWebDavConfig(@Body body: WebDavConfig): Response<JsonElement>

    @POST("api/webdav/test")
    suspend fun testWebDav(@Body body: WebDavTestRequest): Response<JsonElement>

    @GET("api/webdav/info")
    suspend fun webDavInfo(): Response<JsonElement>

    @POST("api/webdav/backup")
    suspend fun webDavBackup(@Body body: WebDavBackupRequest): Response<JsonElement>

    @POST("api/webdav/sync")
    suspend fun webDavSync(@Body body: WebDavBackupRequest): Response<JsonElement>

    // ==================== 配置迁移 ====================
    @POST("api/config-transfer/export")
    suspend fun exportConfig(@Body body: ConfigExportRequest): Response<ResponseBody>

    @Multipart
    @POST("api/config-transfer/import")
    suspend fun importConfig(
        @Part file: MultipartBody.Part,
        @Part("password") password: RequestBody,
        @Part("overwrite") overwrite: RequestBody
    ): Response<JsonElement>

    // ==================== 功能开关 ====================
    @GET("api/switches")
    suspend fun listSwitches(): Response<JsonElement>

    @POST("api/switches/toggle")
    suspend fun toggleSwitch(@Body body: SwitchStateRequest): Response<SwitchToggleResponse>

    // ==================== 语音识别（STT）====================
    @Multipart
    @POST("api/stt/transcribe")
    suspend fun sttTranscribe(
        @Part audio: MultipartBody.Part,
        @Part("language") language: RequestBody
    ): Response<SttTranscribeResponse>

    // ==================== 绑定角色 ====================
    @PUT("api/sessions/{id}/bind-character")
    suspend fun bindCharacter(
        @Path("id") id: String,
        @Body body: BindCharacterRequest
    ): Response<JsonElement>

    // ==================== 消息收藏 ====================
    @GET("api/sessions/{id}/message-favorites")
    suspend fun listMessageFavorites(@Path("id") id: String): Response<JsonElement>

    @PUT("api/sessions/{id}/message-favorites")
    suspend fun updateMessageFavorites(
        @Path("id") id: String,
        @Body body: MessageFavoriteRequest
    ): Response<JsonElement>
}
