package com.nekobot.app.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** 统一 API 响应包装 */
data class ApiResult(
    val success: Boolean? = null,
    val message: JsonElement? = null,
    val error: String? = null,
    val filtered: Boolean? = null
) {
    /** 将 message 字段统一转为字符串展示。 */
    fun messageAsString(): String? = when {
        message == null -> null
        message!!.isJsonPrimitive -> message!!.asString
        message!!.isJsonObject || message!!.isJsonArray -> message.toString()
        else -> null
    }
}

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
    @SerializedName(value = "character_id", alternate = ["characterId"]) val characterId: String? = null,
    @SerializedName(value = "character_ids", alternate = ["characterIds"]) val characterIds: List<String>? = null,
    val tags: List<String>? = null,
    val favorite: Boolean? = null,
    val pinned: Boolean? = null,
    @SerializedName("is_public") val isPublic: Boolean? = null,
    @SerializedName("proactive_chat") val proactiveChat: JsonElement? = null,
    @SerializedName("sender_name") val senderName: String? = null,
    @SerializedName("sender_avatar") val senderAvatar: String? = null,
    @SerializedName(value = "character_name", alternate = ["characterName"]) val characterName: String? = null,
    @SerializedName(value = "character_avatar", alternate = ["characterAvatar"]) val characterAvatar: String? = null,
    @SerializedName(value = "portrait", alternate = ["portrait_url", "character_portrait", "character_portrait_url", "sender_portrait"])
    val portrait: String? = null,
    @SerializedName("message_count") val messageCount: Int? = null,
    @SerializedName("last_message") val lastMessage: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    val archived: Boolean? = null,
    @SerializedName("is_archive") val isArchive: Boolean? = null,
    @SerializedName("read_only") val readOnly: Boolean? = null,
    @SerializedName("tts_config") val ttsConfig: JsonElement? = null,
    /** 公开分享配置 JSON: {expires_days, password, include_character, include_user_messages, message_start, message_end} */
    @SerializedName("share_config") val shareConfig: JsonElement? = null,
    @SerializedName("first_message") val firstMessage: String? = null,
    val scenario: String? = null,
    @SerializedName("auto_state_interval") val autoStateInterval: Int? = null,
    @SerializedName("plot_mode") val plotMode: Boolean? = null,
    @SerializedName("plot_real_time_sync") val plotRealTimeSync: Boolean? = null,
    /** 剧情选项风格（回复风格）：预设 key 或自定义文本 */
    @SerializedName("plot_choice_style") val plotChoiceStyle: String? = null,
    @SerializedName("character_runtime_snapshot") val characterRuntimeSnapshot: JsonElement? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("group_id") val groupId: String? = null,
    /** 自定义提示词列表（持久化，用户编辑，每项含 order/title/content） */
    @SerializedName("custom_prompts") val customPrompts: JsonElement? = null,
    /** 运行时提示词注入栈调试信息（每次对话后由后端更新，只读展示） */
    @SerializedName("prompt_stack_debug") val promptStackDebug: JsonElement? = null,
    /** 已禁用的注入项 key 列表（用户可切换某项的启用状态） */
    @SerializedName("disabled_prompt_keys") val disabledPromptKeys: List<String>? = null,
    /** 运行时合成后的完整系统提示词（每次对话后更新，只读展示） */
    @SerializedName("composed_system_prompt") val composedSystemPrompt: String? = null
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
    val archived: Boolean? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    @SerializedName("character_ids") val characterIds: List<String>? = null,
    @SerializedName("auto_state_interval") val autoStateInterval: Int? = null,
    @SerializedName("plot_mode") val plotMode: Boolean? = null,
    @SerializedName("plot_real_time_sync") val plotRealTimeSync: Boolean? = null,
    @SerializedName("plot_choice_style") val plotChoiceStyle: String? = null,
    @SerializedName("proactive_chat") val proactiveChat: JsonElement? = null,
    @SerializedName("tts_config") val ttsConfig: JsonElement? = null,
    @SerializedName("share_config") val shareConfig: JsonElement? = null,
    @SerializedName("disabled_prompt_keys") val disabledPromptKeys: List<String>? = null,
    @SerializedName("is_public") val isPublic: Boolean? = null
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
    val type: String? = null,
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
    /** 是否为进度卡片（thinking_card），不应在聊天列表中展示 */
    val isThinkingCard: Boolean
        get() = type.equals("thinking_card", ignoreCase = true) || role.equals("system", ignoreCase = true)
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
    @SerializedName(value = "character_ids", alternate = ["character_id", "characterId", "characterIds"])
    val characterIds: List<String>? = null,
    val enabled: Boolean? = null,
    val entries: List<WorldBookEntry>? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "未命名世界书"
    /** 兼容旧代码：取首个绑定角色 ID */
    val characterId: String? get() = characterIds?.firstOrNull()
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
    @SerializedName("character_ids") val characterIds: List<String>? = null,
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
    val users: JsonElement? = null,
    val purposes: JsonElement? = null
) {
    val totalDisplay: Long get() = total ?: totalTokens ?: 0L
    val todayTotal: Long get() = today ?: ((todayInput ?: 0L) + (todayOutput ?: 0L))
}

data class TokenRankings(
    val sessions: List<JsonElement>? = null,
    val models: List<JsonElement>? = null,
    val users: List<JsonElement>? = null,
    val purposes: List<JsonElement>? = null
)

// ==================== 角色记忆（MemoryFS）====================
/**
 * MemoryFS 逻辑记忆文件，对应后端 `/api/review/memory-fs` 返回项。
 *
 * path 规范（使用 / 分隔，不含前导 /）：
 *   characters/{char_id}/general.md
 *   characters/{char_id}/users/{user_id}/user_persona.md
 *   characters/{char_id}/users/{user_id}/character_persona.md
 *   characters/{char_id}/users/{user_id}/recent_digest.md
 *   characters/{char_id}/events/{conversation_id}.md
 *   characters/{char_id}/plot/{conversation_id}.md
 *   characters/{char_id}/timeline.md
 *   characters/{char_id}/life_sim/{conversation_id}.md
 *
 * category 由路径推断：user_persona / character_persona / important_event /
 * timeline / life_sim / recent_digest / legacy
 */
data class MemoryFile(
    val path: String = "",
    @SerializedName("character_id") val characterId: String = "",
    @SerializedName("target_id") val targetId: String = "",
    val title: String = "",
    val content: String = "",
    val summary: String = "",
    val tags: List<String>? = null,
    val importance: Float = 0f,
    val version: Int = 1,
    @SerializedName("source_event_id") val sourceEventId: String = "",
    @SerializedName("memory_ids") val memoryIds: List<String>? = null,
    @SerializedName("updated_at") val updatedAt: String = "",
    // 由 /api/review/memory-fs 附加的元数据
    val category: String = "legacy",
    @SerializedName("category_label") val categoryLabel: String = "旧版/其他",
    @SerializedName("injects_to_prompt") val injectsToPrompt: Boolean = false,
    @SerializedName("category_order") val categoryOrder: Int = 99
)

/** MemoryFS 列表响应。 */
data class MemoryFsListResponse(
    val files: List<MemoryFile>? = null,
    val total: Int = 0
)

/**
 * 旧版记忆条目，对应 `/api/memory`。
 * type 只有 "long" 或 "short"；priority 为 high/normal/low。
 */
data class LegacyMemory(
    val id: String? = null,
    val title: String = "",
    val content: String = "",
    val summary: String? = null,
    val type: String = "long",
    val priority: String = "normal",
    @SerializedName("expire_days") val expireDays: Int = 7,
    @SerializedName("target_id") val targetId: String = "",
    @SerializedName("character_name") val characterName: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)

/** 旧版记忆列表响应：{memories, long_term, short_term} */
data class LegacyMemoryListResponse(
    val memories: List<LegacyMemory>? = null,
    @SerializedName("long_term") val longTerm: List<LegacyMemory>? = null,
    @SerializedName("short_term") val shortTerm: List<LegacyMemory>? = null
)

/** 创建/更新旧版记忆请求体。 */
data class LegacyMemoryRequest(
    val title: String,
    val content: String,
    val summary: String? = null,
    val type: String = "long",
    val priority: String = "normal",
    @SerializedName("expire_days") val expireDays: Int = 7,
    @SerializedName("target_id") val targetId: String = "",
    @SerializedName("character_name") val characterName: String = ""
)

// ==================== Hook 管理 ====================
/**
 * 对话 Hook，对应后端 `/api/hooks`。
 * event 支持通配符如 "character.*"；scope 为 global/character/conversation/user。
 */
data class Hook(
    @SerializedName(value = "id", alternate = ["hook_id", "_id"]) val id: String? = null,
    val name: String = "",
    val event: String = "",
    val actions: List<JsonElement> = emptyList(),
    val description: String? = null,
    val enabled: Boolean = true,
    val scope: String = "global",
    val priority: Int = 100,
    val conditions: JsonElement? = null,
    val permissions: JsonElement? = null,
    @SerializedName("timeout_ms") val timeoutMs: Int = 3000,
    @SerializedName("max_retries") val maxRetries: Int = 0,
    @SerializedName("trigger_mode") val triggerMode: String = "always",
    @SerializedName("condition_logic") val conditionLogic: String = "and",
    @SerializedName("character_id") val characterId: String? = null,
    @SerializedName("conversation_id") val conversationId: String? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
) {
    val displayName: String get() = name.ifBlank { "未命名 Hook" }
}

data class HookRequest(
    val name: String,
    val event: String,
    val actions: List<JsonElement> = emptyList(),
    val description: String? = null,
    val enabled: Boolean = true,
    val scope: String = "global",
    val priority: Int = 100,
    val conditions: JsonElement? = null,
    val permissions: JsonElement? = null,
    @SerializedName("timeout_ms") val timeoutMs: Int = 3000,
    @SerializedName("max_retries") val maxRetries: Int = 0,
    @SerializedName("trigger_mode") val triggerMode: String = "always",
    @SerializedName("condition_logic") val conditionLogic: String = "and",
    @SerializedName("character_id") val characterId: String? = null,
    @SerializedName("conversation_id") val conversationId: String? = null,
    @SerializedName("user_id") val userId: String? = null
)

data class HookExecutionLog(
    val id: String? = null,
    @SerializedName("hook_id") val hookId: String? = null,
    @SerializedName("event_id") val eventId: String? = null,
    val status: String = "",
    @SerializedName("actions_executed") val actionsExecuted: Int = 0,
    val error: String? = null,
    @SerializedName("duration_ms") val durationMs: Int = 0,
    @SerializedName("conversation_id") val conversationId: String? = null,
    @SerializedName("event_type") val eventType: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

// ==================== 任务中心 ====================
/**
 * 任务中心聚合项，对应后端 `/api/task-center`。
 * kind 为 heartbeat / workflow / custom；trigger 为 interval / cron / run_at。
 */
data class TaskItem(
    @SerializedName(value = "id", alternate = ["task_id", "_id"]) val id: String = "",
    val kind: String = "custom",
    val name: String = "",
    val description: String? = null,
    val enabled: Boolean = true,
    val trigger: String = "interval",
    val config: JsonElement? = null,
    @SerializedName("target_session_id") val targetSessionId: String? = null,
    val prompt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("last_run") val lastRun: String? = null,
    @SerializedName("next_run") val nextRun: String? = null
) {
    val displayName: String get() = name.ifBlank { "未命名任务" }
}

data class TaskRequest(
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val trigger: String = "interval",
    val config: JsonElement? = null,
    @SerializedName("target_session_id") val targetSessionId: String? = null,
    val prompt: String? = null
)

// ==================== 工作流 ====================
/**
 * 工作流，对应后端 `/api/workflows`。trigger 为 manual / cron。
 */
data class Workflow(
    @SerializedName(value = "id", alternate = ["workflow_id", "_id"]) val id: String? = null,
    val name: String = "",
    val description: String? = null,
    val enabled: Boolean = true,
    val trigger: String = "manual",
    val config: JsonElement? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    val displayName: String get() = name.ifBlank { "未命名工作流" }
}

data class WorkflowRequest(
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val trigger: String = "manual",
    val config: JsonElement? = null
)

// ==================== 知识库 ====================
/**
 * 知识库文档，对应后端 `/api/knowledge`。
 */
data class KnowledgeDocument(
    @SerializedName(value = "id", alternate = ["doc_id", "_id"]) val id: String? = null,
    val title: String = "",
    val content: String = "",
    val source: String? = null,
    val tags: List<String> = emptyList(),
    @SerializedName("created_at") val createdAt: String? = null,
    val metadata: JsonElement? = null
) {
    val displayName: String get() = title.ifBlank { "未命名文档" }
}

data class KnowledgeDocumentRequest(
    val title: String,
    val content: String,
    val source: String? = null,
    val tags: List<String> = emptyList(),
    val metadata: JsonElement? = null
)

data class KnowledgeStats(
    val total: Int = 0,
    val indexed: Int = 0,
    val pending: Int = 0
)

data class KnowledgeSearchRequest(
    val query: String,
    @SerializedName("top_k") val topK: Int = 5
)

data class KnowledgeSearchResult(
    val id: String? = null,
    val title: String? = null,
    val content: String? = null,
    val score: Float? = null,
    val source: String? = null
)

// ==================== Skills 配置 ====================
/**
 * Skill 元数据，对应后端 `/api/skills`。
 */
data class Skill(
    @SerializedName(value = "id", alternate = ["skill_id", "_id"]) val id: String? = null,
    val name: String = "",
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val enabled: Boolean = true,
    val parameters: JsonElement? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    val displayName: String get() = name.ifBlank { "未命名 Skill" }
}

data class SkillRequest(
    val name: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val enabled: Boolean = true,
    val parameters: JsonElement? = null
)

// ==================== Tools 配置 ====================
/**
 * 工具配置，对应后端 `/api/tools`。内置工具 builtin=true 不可删除/切换。
 */
data class Tool(
    @SerializedName(value = "id", alternate = ["tool_id", "_id"]) val id: String? = null,
    val name: String = "",
    val description: String? = null,
    val enabled: Boolean = true,
    val parameters: JsonElement? = null,
    val implementation: JsonElement? = null,
    @SerializedName("_builtin") val builtin: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null
) {
    val displayName: String get() = name.ifBlank { "未命名工具" }
}

data class ToolRequest(
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val parameters: JsonElement? = null,
    val implementation: JsonElement? = null
)

// ==================== MCP 服务 ====================
/**
 * MCP 服务，对应后端 `/api/mcp-servers`。
 * transport 为 streamable-http / stdio；HTTP 模式用 url，stdio 模式用 command/args/env。
 */
data class McpServer(
    @SerializedName(value = "id", alternate = ["server_id", "_id"]) val id: String? = null,
    val name: String = "",
    val transport: String = "streamable-http",
    val description: String? = null,
    val enabled: Boolean = true,
    @SerializedName("auto_connect") val autoConnect: Boolean = false,
    val connected: Boolean = false,
    @SerializedName("tool_count") val toolCount: Int = 0,
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: JsonElement? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("last_connected_at") val lastConnectedAt: String? = null,
    @SerializedName("_builtin") val builtin: Boolean = false
) {
    val displayName: String get() = name.ifBlank { "未命名 MCP 服务" }
}

data class McpServerRequest(
    val name: String,
    val transport: String = "streamable-http",
    val description: String? = null,
    val enabled: Boolean = true,
    @SerializedName("auto_connect") val autoConnect: Boolean = false,
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: JsonElement? = null
)

// ==================== 频道管理 ====================
/**
 * 频道，对应后端 `/api/channels`。
 * type 为 custom/telegram/feishu/feishu_ws/qqbot/web/qq；transport 为 webhook/websocket/socketio/napcat。
 */
data class Channel(
    @SerializedName(value = "id", alternate = ["channel_id", "_id"]) val id: String? = null,
    val name: String = "",
    val type: String = "custom",
    val transport: String = "webhook",
    val description: String? = null,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    val config: JsonElement? = null,
    val capabilities: JsonElement? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
) {
    val displayName: String get() = name.ifBlank { "未命名频道" }
}

data class ChannelRequest(
    val name: String,
    val type: String = "custom",
    val transport: String = "webhook",
    val description: String? = null,
    val enabled: Boolean = true,
    val config: JsonElement? = null,
    val capabilities: JsonElement? = null
)

data class ChannelPreset(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val transport: String = "",
    val description: String? = null,
    val config: JsonElement? = null
)

// ==================== 消息过滤 ====================
/**
 * 消息过滤规则，对应后端 `/api/message-filter`。
 * type 为 keyword/regex；action 为 strip/recall；filter_target 为 user/ai/both。
 */
data class MessageFilterRule(
    @SerializedName(value = "id", alternate = ["rule_id", "_id"]) val id: String? = null,
    val pattern: String = "",
    val type: String = "keyword",
    val action: String = "strip",
    @SerializedName("filter_target") val filterTarget: String = "both",
    @SerializedName("session_scope") val sessionScope: String = "all",
    @SerializedName("session_id") val sessionId: String? = null,
    val enabled: Boolean = true,
    @SerializedName("created_at") val createdAt: String? = null
) {
    val displayName: String get() = pattern.ifBlank { "未命名规则" }
}

data class MessageFilterRuleRequest(
    val pattern: String,
    val type: String = "keyword",
    val action: String = "strip",
    @SerializedName("filter_target") val filterTarget: String = "both",
    @SerializedName("session_scope") val sessionScope: String = "all",
    @SerializedName("session_id") val sessionId: String? = null,
    val enabled: Boolean = true
)

/** 消息过滤全局配置（顶层结构） */
data class MessageFilterConfig(
    val enabled: Boolean = true,
    val global: List<MessageFilterRule> = emptyList(),
    val channels: JsonElement? = null
)

// ==================== TTS 试验场 ====================
/**
 * TTS 音色，对应后端 `/api/tts/voices`。provider 为 xiaomi/doubao/openai；custom=true 为自定义音色。
 */
data class TtsVoice(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val provider: String? = null,
    val custom: Boolean = false,
    @SerializedName("sample_text") val sampleText: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
) {
    val displayName: String get() = name.ifBlank { id }
}

data class TtsPreviewRequest(
    val text: String,
    @SerializedName("model_id") val modelId: String? = null,
    val voice: String,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f
)

data class TtsPreviewResponse(
    val success: Boolean? = null,
    @SerializedName("audio_url") val audioUrl: String? = null,
    val message: String? = null
)

// ==================== 登录令牌 / API Keys ====================
/**
 * 登录令牌，对应后端 `/api/login-tokens`。tokenHash 为 SHA-256，明文仅创建时返回一次。
 */
data class LoginToken(
    // 后端列表接口返回 `hash_full`（完整哈希），用于 DELETE 路径参数。
    @SerializedName("hash_full") val tokenHash: String = "",
    @SerializedName("token_prefix") val tokenPrefix: String? = null,
    val username: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("ip_address") val ipAddress: String? = null
) {
    val displayName: String get() = "${tokenPrefix ?: "令牌"} · $username"
}

data class LoginTokenRequest(
    val username: String,
    @SerializedName("expires_days") val expiresDays: Int = 30
)

data class LoginTokenResponse(
    val success: Boolean? = null,
    val token: String? = null,
    val message: String? = null
)

/**
 * API Key，对应后端 `/api/api-keys`。key 仅详情接口返回。
 */
data class ApiKey(
    @SerializedName(value = "id", alternate = ["key_id", "_id"]) val id: String? = null,
    val name: String = "",
    val key: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
) {
    val displayName: String get() = name.ifBlank { "未命名 Key" }
}

data class ApiKeyRequest(
    val name: String,
    val key: String
)

// ==================== 包装响应（Envelope Responses）====================
// 后端实际返回形如 {"hooks": [...], "total": N} / {"hook": {...}} / {"items": [...]} /
// {"success": true, "task": {...}} / {"presets": [...]} / {"channels": [...]} /
// {"success": true, "channel": {...}} / {"success": true, "enabled": bool} /
// {"success": true, "voices": [...]} / {"success": true, "tokens": [...]} /
// {"success": true, "keys": [...]} / {"success": true, "key": {...}} 等包装结构。
// 直接用裸类型反序列化会得到空对象/空列表，因此此处显式声明包装类。

// ---- Hook ----
data class HookListResponse(
    val hooks: List<Hook> = emptyList(),
    val total: Int = 0
)
data class HookResponse(val hook: Hook? = null)
data class HookLogListResponse(
    val logs: List<HookExecutionLog> = emptyList(),
    val total: Int = 0
)

// ---- Task Center ----
data class TaskListResponse(val items: List<TaskItem> = emptyList())
data class TaskResponse(
    val success: Boolean? = null,
    val task: TaskItem? = null
)
data class TaskItemResponse(
    val success: Boolean? = null,
    val item: TaskItem? = null
)

// ---- Channels ----
data class ChannelPresetListResponse(
    val presets: List<ChannelPreset> = emptyList()
)
data class ChannelListResponse(
    val channels: List<Channel> = emptyList(),
    @SerializedName("registered_adapters") val registeredAdapters: List<String> = emptyList(),
    @SerializedName("registered_handlers") val registeredHandlers: JsonElement? = null,
    @SerializedName("feishu_ws_running") val feishuWsRunning: JsonElement? = null,
    @SerializedName("qqbot_running") val qqbotRunning: JsonElement? = null
)
data class ChannelResponse(
    val success: Boolean? = null,
    val channel: Channel? = null
)
data class ChannelToggleResponse(
    val success: Boolean? = null,
    val enabled: Boolean? = null
)

// ---- TTS ----
data class TtsVoiceListResponse(
    val success: Boolean? = null,
    val voices: List<TtsVoice> = emptyList()
)
data class TtsVoiceUploadResponse(
    val success: Boolean? = null,
    @SerializedName("voice_id") val voiceId: String? = null,
    val name: String? = null
)

// ---- Login Tokens ----
data class LoginTokenListResponse(
    val success: Boolean? = null,
    val tokens: List<LoginToken> = emptyList()
)

// ---- API Keys ----
data class ApiKeyListResponse(
    val success: Boolean? = null,
    val keys: List<ApiKey> = emptyList()
)
data class ApiKeyResponse(
    val success: Boolean? = null,
    val key: ApiKey? = null
)

// ==================== 故事图（Plot Graph）====================
/** 故事图节点（API 版本，对应原仓库 PlotNode） */
data class PlotNodeData(
    @SerializedName(value = "id", alternate = ["node_id"]) val id: String? = null,
    @SerializedName("conversation_id") val conversationId: String? = null,
    @SerializedName("character_id") val characterId: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val level: String? = null,
    val scene: JsonElement? = null,
    @SerializedName("state_snapshot") val stateSnapshot: JsonElement? = null,
    @SerializedName("relationship_snapshot") val relationshipSnapshot: JsonElement? = null,
    @SerializedName("parent_node_id") val parentNodeId: String? = null,
    @SerializedName("selected_choice_id") val selectedChoiceId: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("user_message") val userMessage: JsonElement? = null,
    @SerializedName("assistant_message") val assistantMessage: JsonElement? = null,
    @SerializedName("activity_type") val activityType: String? = null,
    val participants: List<String>? = null,
    val location: String? = null,
    val mood: String? = null
)

/** 故事图选项 */
data class PlotChoiceData(
    @SerializedName(value = "id", alternate = ["choice_id"]) val id: String? = null,
    @SerializedName("node_id") val nodeId: String? = null,
    val text: String? = null,
    val level: String? = null,
    val intent: String? = null,
    val selected: Boolean? = null,
    val risk: String? = null
)

/** 故事图边 */
data class PlotEdgeData(
    val id: String? = null,
    @SerializedName("from_node_id") val fromNodeId: String? = null,
    @SerializedName("to_node_id") val toNodeId: String? = null,
    @SerializedName("choice_id") val choiceId: String? = null,
    val label: String? = null
)

/** 故事图完整数据 */
data class PlotGraphData(
    val nodes: List<PlotNodeData> = emptyList(),
    val choices: List<PlotChoiceData> = emptyList(),
    val edges: List<PlotEdgeData> = emptyList(),
    @SerializedName("active_node_id") val activeNodeId: String? = null
)

/** 开启剧情模式请求 */
data class PlotToggleRequest(
    @SerializedName("session_id") val sessionId: String,
    val enabled: Boolean,
    @SerializedName("plot_choice_style") val plotChoiceStyle: String? = null
)

/** 选择剧情分支请求 */
data class PlotSelectRequest(
    @SerializedName("choice_id") val choiceId: String
)

/** 剧情分支切换请求 */
data class PlotSwitchRequest(
    @SerializedName("node_id") val nodeId: String
)

/** 剧情分支创建请求 */
data class PlotBranchRequest(
    @SerializedName("node_id") val nodeId: String,
    @SerializedName("choice_id") val choiceId: String
)

/** 剧情回滚请求 */
data class PlotRollbackRequest(
    @SerializedName("node_id") val nodeId: String
)

// ==================== WebDAV 备份 ====================
data class WebDavConfig(
    val enabled: Boolean? = null,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerializedName("encryption_password") val encryptionPassword: String? = null,
    @SerializedName("last_backup_at") val lastBackupAt: String? = null,
    @SerializedName("last_sync_at") val lastSyncAt: String? = null,
    @SerializedName("last_error") val lastError: String? = null,
    @SerializedName("last_file_size") val lastFileSize: Long? = null,
    @SerializedName("last_modified") val lastModified: String? = null,
    @SerializedName("resolved_file_url") val resolvedFileUrl: String? = null,
    @SerializedName("has_password") val hasPassword: Boolean? = null,
    @SerializedName("has_encryption_password") val hasEncryptionPassword: Boolean? = null
)

data class WebDavTestRequest(
    val url: String? = null,
    val username: String? = null,
    val password: String? = null
)

data class WebDavBackupRequest(
    val password: String? = null,
    @SerializedName("include_portraits") val includePortraits: Boolean? = null
)

// ==================== 配置迁移 ====================
data class ConfigExportRequest(
    val password: String? = null
)

data class ConfigImportRequest(
    val password: String? = null,
    val overwrite: Boolean? = null
)

// ==================== 功能开关 ====================
data class SwitchInfo(
    val name: String? = null,
    val default: Boolean? = null,
    val description: String? = null
)

data class SwitchStateRequest(
    val name: String,
    val state: Boolean,
    @SerializedName("group_id") val groupId: String? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("conversation_id") val conversationId: String? = null
)

data class SwitchToggleResponse(
    val success: Boolean? = null,
    val name: String? = null,
    val state: Boolean? = null
)

// ==================== 语音识别（STT）====================
data class SttTranscribeResponse(
    val success: Boolean? = null,
    val text: String? = null,
    val language: String? = null,
    val provider: String? = null
)

