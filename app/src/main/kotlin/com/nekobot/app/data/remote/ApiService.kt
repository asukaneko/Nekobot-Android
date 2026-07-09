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
    suspend fun regenerate(@Path("id") id: String): Response<ApiResult>

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
}
