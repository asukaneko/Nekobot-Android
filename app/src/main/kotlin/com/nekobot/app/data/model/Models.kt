package com.nekobot.app.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** 统一 API 响应包装 */
data class ApiResult(
    val success: Boolean? = null,
    val message: String? = null,
    val error: String? = null,
    val filtered: Boolean? = null
)

// ==================== 认证 ====================
data class LoginRequest(
    val username: String,
    val password: String
)

/**
 * 登录响应字段名文档未明确，使用 alternate 覆盖常见命名。
 */
data class LoginResponse(
    @SerializedName(value = "success", alternate = ["ok"]) val success: Boolean? = null,
    @SerializedName(value = "token", alternate = ["access_token", "auth_token", "session_token"])
    val token: String? = null,
    val message: String? = null,
    val error: String? = null,
    @SerializedName(value = "user", alternate = ["user_info", "profile"])
    val user: JsonElement? = null
)

// ==================== 会话 ====================
data class Session(
    @SerializedName(value = "id", alternate = ["session_id", "_id"])
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    @SerializedName("session_mode") val sessionMode: String? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    @SerializedName("character_id") val characterId: String? = null,
    @SerializedName("character_ids") val characterIds: List<String>? = null,
    val tags: List<String>? = null,
    val favorite: Boolean? = null,
    val pinned: Boolean? = null,
    @SerializedName("is_public") val isPublic: Boolean? = null,
    @SerializedName("proactive_chat") val proactiveChat: JsonElement? = null,
    @SerializedName("sender_name") val senderName: String? = null,
    @SerializedName("sender_avatar") val senderAvatar: String? = null,
    @SerializedName("character_name") val characterName: String? = null,
    @SerializedName("character_avatar") val characterAvatar: String? = null,
    @SerializedName(value = "portrait", alternate = ["portrait_url", "character_portrait", "character_portrait_url"])
    val portrait: String? = null,
    @SerializedName("message_count") val messageCount: Int? = null,
    @SerializedName("last_message") val lastMessage: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    val archived: Boolean? = null,
    @SerializedName("is_archive") val isArchive: Boolean? = null,
    @SerializedName("read_only") val readOnly: Boolean? = null,
    @SerializedName("tts_config") val ttsConfig: JsonElement? = null,
    @SerializedName("first_message") val firstMessage: String? = null,
    val scenario: String? = null,
    @SerializedName("auto_state_interval") val autoStateInterval: Int? = null,
    @SerializedName("plot_mode") val plotMode: Boolean? = null,
    @SerializedName("plot_real_time_sync") val plotRealTimeSync: Boolean? = null,
    @SerializedName("character_runtime_snapshot") val characterRuntimeSnapshot: JsonElement? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("group_id") val groupId: String? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "未命名会话"
    /** 角色立绘 URL：优先 portrait，回退 characterAvatar */
    val portraitUrl: String? get() = portrait ?: characterAvatar
}

data class CreateSessionRequest(
    val name: String? = null,
    val type: String = "web",
    @SerializedName("session_mode") val sessionMode: String = "character",
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("character_id") val characterId: String? = null,
    @SerializedName("character_ids") val characterIds: List<String>? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    @SerializedName("first_message") val firstMessage: String? = null,
    val scenario: String? = null,
    @SerializedName("sender_name") val senderName: String? = null,
    @SerializedName("sender_avatar") val senderAvatar: String? = null,
    @SerializedName("sender_portrait") val senderPortrait: String? = null,
    val tags: List<String>? = null
)

data class UpdateSessionRequest(
    val name: String? = null,
    val tags: List<String>? = null,
    val favorite: Boolean? = null,
    val pinned: Boolean? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    @SerializedName("character_ids") val characterIds: List<String>? = null,
    @SerializedName("auto_state_interval") val autoStateInterval: Int? = null,
    @SerializedName("plot_mode") val plotMode: Boolean? = null,
    @SerializedName("plot_real_time_sync") val plotRealTimeSync: Boolean? = null,
    @SerializedName("proactive_chat") val proactiveChat: JsonElement? = null,
    @SerializedName("tts_config") val ttsConfig: JsonElement? = null
)

/** 分叉会话请求体：从指定 message_id 处复制到新会话。 */
data class ForkRequest(
    @SerializedName("message_id") val messageId: String
)

// ==================== 消息 ====================
data class Message(
    @SerializedName(value = "id", alternate = ["message_id", "_id"])
    val id: String? = null,
    val role: String? = null,
    val content: String? = null,
    val sender: String? = null,
    val name: String? = null,
    val avatar: String? = null,
    val timestamp: String? = null,
    @SerializedName("audio_url") val audioUrl: String? = null,
    val tokens: Int? = null,
    @SerializedName("input_tokens") val inputTokens: Int? = null,
    @SerializedName("output_tokens") val outputTokens: Int? = null,
    val model: String? = null,
    val filtered: Boolean? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    val isUser: Boolean
        get() = role.equals("user", ignoreCase = true) || role.equals("human", ignoreCase = true)
    val displayContent: String get() = content ?: ""
}

data class ChatRequest(
    val message: String? = null,
    val content: String? = null,
    @SerializedName("session_id") val sessionId: String? = null
) {
    companion object {
        fun of(text: String) = ChatRequest(message = text, content = text)
    }
}

data class SendMessageRequest(
    val role: String = "user",
    val content: String,
    val sender: String? = null
)

// ==================== 完整角色卡（CharacterPreset）====================
/**
 * 完整角色卡数据模型，对应后端 `/api/personality/custom-presets` 完整数据源
 * `data/web/custom_personality_presets.json`。
 * 与 `Character`（`/api/characters` 运行时快照）不同，本模型包含全部字段。
 *
 * 字段顺序与迁移指南 §4 响应样例保持一致。
 */
data class CharacterPreset(
    @SerializedName(value = "id", alternate = ["preset_id", "_id"])
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val avatar: String? = null,
    val portrait: String? = null,
    val tags: List<String>? = null,

    // basicInfo 在服务端是字符串（包含身高/年龄/职业等多行）
    val basicInfo: String? = null,
    val personality: String? = null,
    val scenario: String? = null,
    @SerializedName("firstMessage") val firstMessage: String? = null,
    @SerializedName("alternateGreetings") val alternateGreetings: List<String>? = null,
    @SerializedName("exampleDialogues", alternate = ["dialog_examples", "mes_example"])
    val exampleDialogues: String? = null,
    @SerializedName("responseFormat") val responseFormat: String? = null,
    val rules: List<String>? = null,
    // state 是 JSON 对象（affection / trust / familiarity / dependency / security / mood）
    val state: com.google.gson.JsonObject? = null,
    @SerializedName("systemPrompt") val systemPrompt: String? = null,
    val greeting: String? = null,

    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "未命名角色"
    val avatarUrl: String? get() = portrait ?: avatar
}

data class BasicInfo(
    val name: String? = null,
    val description: String? = null,
    val age: String? = null,
    val gender: String? = null,
    val occupation: String? = null,
    val avatar: String? = null,
    @SerializedName("portrait") val portrait: String? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "未命名"
    val avatarUrl: String? get() = portrait ?: avatar
}

/**
 * 运行时快照角色卡，对应 `/api/characters`（数据源 `data/character/profiles.json`）。
 * 字段可能不完整。**完整角色卡数据请用 [CharacterPreset]**。
 */
data class Character(
    @SerializedName(value = "id", alternate = ["character_id", "_id"])
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val personality: String? = null,
    @SerializedName("first_message") val firstMessage: String? = null,
    val scenario: String? = null,
    @SerializedName("dialog_examples", alternate = ["mes_example", "example_dialogue"])
    val dialogExamples: String? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    @SerializedName("alternate_greetings") val alternateGreetings: List<String>? = null,
    val tags: List<String>? = null,
    val creator: String? = null,
    @SerializedName("creator_notes") val creatorNotes: String? = null,
    val avatar: String? = null,
    @SerializedName(value = "portrait", alternate = ["portrait_url", "avatar_url"])
    val portrait: String? = null,
    val version: String? = null,
    @SerializedName("character_book") val characterBook: JsonElement? = null,
    val favorite: Boolean? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "未命名角色"
    val avatarUrl: String? get() = portrait ?: avatar
}

data class CharacterRequest(
    val name: String,
    val description: String? = null,
    val personality: String? = null,
    @SerializedName("first_message") val firstMessage: String? = null,
    val scenario: String? = null,
    @SerializedName("dialog_examples") val dialogExamples: String? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    @SerializedName("alternate_greetings") val alternateGreetings: List<String>? = null,
    val tags: List<String>? = null,
    @SerializedName("creator_notes") val creatorNotes: String? = null,
    val avatar: String? = null,
    val version: String? = null
)

// ==================== 世界书 ====================
data class WorldBook(
    @SerializedName(value = "id", alternate = ["book_id", "_id"])
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    @SerializedName("character_id") val characterId: String? = null,
    val enabled: Boolean? = null,
    val entries: List<WorldBookEntry>? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "未命名世界书"
}

data class WorldBookEntry(
    @SerializedName(value = "id", alternate = ["entry_id", "_id"])
    val id: String? = null,
    val keys: List<String>? = null,
    val content: String? = null,
    val comment: String? = null,
    val enabled: Boolean? = null,
    val constant: Boolean? = null,
    val selective: Boolean? = null,
    @SerializedName("insertion_order") val insertionOrder: Int? = null,
    val priority: Int? = null,
    val position: String? = null,
    @SerializedName("case_sensitive") val caseSensitive: Boolean? = null,
    @SerializedName("display_index") val displayIndex: Int? = null
) {
    val keysText: String get() = keys?.joinToString(", ") ?: ""
}

data class WorldBookRequest(
    val name: String,
    val description: String? = null,
    @SerializedName("character_id") val characterId: String? = null,
    val enabled: Boolean? = null
)

data class WorldBookEntryRequest(
    val keys: List<String>? = null,
    val content: String? = null,
    val comment: String? = null,
    val enabled: Boolean? = null,
    val constant: Boolean? = null,
    val selective: Boolean? = null,
    @SerializedName("insertion_order") val insertionOrder: Int? = null,
    val priority: Int? = null,
    val position: String? = null,
    @SerializedName("case_sensitive") val caseSensitive: Boolean? = null
)

// ==================== AI 配置 / 模型 ====================
/**
 * AI 配置字段文档未明确，使用宽松的 JsonElement 持有原始结构，
 * 同时提供常用字段的便捷访问。
 */
data class AiConfig(
    val model: String? = null,
    val temperature: Double? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("top_p") val topP: Double? = null,
    @SerializedName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerializedName("presence_penalty") val presencePenalty: Double? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    val purpose: String? = null
)

data class AiModel(
    @SerializedName(value = "id", alternate = ["model_id", "_id"])
    val id: String? = null,
    val name: String? = null,
    val protocol: String? = null,
    val provider: String? = null,
    @SerializedName("api_key") val apiKey: String? = null,
    @SerializedName("base_url") val baseUrl: String? = null,
    @SerializedName(value = "model", alternate = ["model_name"])
    val model: String? = null,
    val enabled: Boolean? = null,
    val purpose: String? = null,
    val priority: Int? = null,
    val type: String? = null,
    val active: Boolean? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: model ?: "未命名模型"
}

data class AiModelRequest(
    val name: String,
    val protocol: String? = null,
    val provider: String? = null,
    @SerializedName("api_key") val apiKey: String? = null,
    @SerializedName("base_url") val baseUrl: String? = null,
    val model: String? = null,
    val enabled: Boolean? = null,
    val purpose: String? = null,
    val priority: Int? = null
)

data class FetchModelsRequest(
    @SerializedName("base_url") val baseUrl: String,
    @SerializedName("api_key") val apiKey: String? = null,
    val protocol: String? = null
)

/** 远端 API 返回的单个模型信息（id + 显示名） */
data class FetchedModel(
    val id: String? = null,
    val name: String? = null,
    @SerializedName("owned_by") val ownedBy: String? = null
)

data class FetchModelsResponse(
    val success: Boolean? = null,
    val message: String? = null,
    val models: List<FetchedModel>? = null
)

/** GET /api/ai-models 的响应包装。 */
data class AiModelListResponse(
    val models: List<AiModel>? = null,
    @SerializedName("active_model_id") val activeModelId: String? = null
)

data class TestResponse(
    val success: Boolean? = null,
    val message: String? = null
)

// ==================== Token 用量统计 ====================
data class TokenStats(
    val today: Long? = null,
    val month: Long? = null,
    val total: Long? = null,
    @SerializedName("total_tokens") val totalTokens: Long? = null,
    @SerializedName("today_input") val todayInput: Long? = null,
    @SerializedName("today_output") val todayOutput: Long? = null,
    @SerializedName("message_count") val messageCount: Long? = null,
    @SerializedName("avg_tokens_per_msg") val avgTokensPerMsg: Double? = null,
    @SerializedName("estimated_cost") val estimatedCost: String? = null,
    @SerializedName("active_sessions") val activeSessions: Int? = null,
    @SerializedName("avg_response_time") val avgResponseTime: String? = null,
    val history: List<JsonElement>? = null,
    @SerializedName("recent_records") val recentRecords: List<JsonElement>? = null,
    val records: List<JsonElement>? = null,
    val sessions: JsonElement? = null,
    val models: JsonElement? = null,
    val users: JsonElement? = null
) {
    val totalDisplay: Long get() = total ?: totalTokens ?: 0L
    val todayTotal: Long get() = today ?: ((todayInput ?: 0L) + (todayOutput ?: 0L))
}

data class TokenRankings(
    val sessions: List<JsonElement>? = null,
    val models: List<JsonElement>? = null,
    val users: List<JsonElement>? = null
)
