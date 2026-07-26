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
    /** 当前会话绑定的归档会话 ID（压缩上下文时由后端写入） */
    @SerializedName(value = "archive_session_id", alternate = ["archiveSessionId"])
    val archiveSessionId: String? = null,
    /** 归档会话指向的原会话 ID（自动归档会话独有） */
    @SerializedName(value = "source_session_id", alternate = ["sourceSessionId"])
    val sourceSessionId: String? = null,
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
    /** 剧情大纲文本（用户导入/粘贴，AI 生成选项时围绕此走向推进） */
    @SerializedName(value = "plot_outline", alternate = ["plotOutline"]) val plotOutline: String? = null,
    /** 本会话用户人设/背景提示词（注入到 PromptStack 的 user.persona 项） */
    @SerializedName(value = "user_persona", alternate = ["userPersona"]) val userPersona: String? = null,
    @SerializedName("character_runtime_snapshot") val characterRuntimeSnapshot: JsonElement? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("group_id") val groupId: String? = null,
    /** 群聊配置（本地模式直接持久化；服务器模式按返回字段透传）。 */
    @SerializedName("group_config") val groupConfig: JsonElement? = null,
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
    val tags: List<String>? = null,
    @SerializedName("group_config") val groupConfig: com.google.gson.JsonElement? = null,
    /** 仅供本地模式创建角色会话时选择六维状态来源，不发送给远程服务。 */
    @Transient val relationshipStateSource: String = RELATIONSHIP_STATE_SOURCE_INHERIT
)

const val RELATIONSHIP_STATE_SOURCE_INITIAL = "initial"
const val RELATIONSHIP_STATE_SOURCE_INHERIT = "inherit"

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
    @SerializedName("plot_outline") val plotOutline: String? = null,
    @SerializedName("user_persona") val userPersona: String? = null,
    @SerializedName("proactive_chat") val proactiveChat: JsonElement? = null,
    @SerializedName("tts_config") val ttsConfig: JsonElement? = null,
    @SerializedName("share_config") val shareConfig: JsonElement? = null,
    @SerializedName("disabled_prompt_keys") val disabledPromptKeys: List<String>? = null,
    @SerializedName("is_public") val isPublic: Boolean? = null
)

/** 创建或更新公开分享时提交给独立 public API 的配置。 */
data class PublicShareRequest(
    @SerializedName("expires_days") val expiresDays: Int = 30,
    val password: String = "",
    @SerializedName("include_character") val includeCharacter: Boolean = true,
    @SerializedName("include_user_messages") val includeUserMessages: Boolean = true,
    @SerializedName("message_start") val messageStart: Int? = null,
    @SerializedName("message_end") val messageEnd: Int? = null
)

data class PublicShareOptions(
    @SerializedName("expires_days") val expiresDays: Int = 30,
    @SerializedName("include_character") val includeCharacter: Boolean = true,
    @SerializedName("include_user_messages") val includeUserMessages: Boolean = true,
    @SerializedName("message_start") val messageStart: Int? = null,
    @SerializedName("message_end") val messageEnd: Int? = null
)

/** POST /public 与 GET /public/status 的兼容响应。 */
data class PublicSessionStatus(
    val success: Boolean = false,
    @SerializedName("is_public") val isPublic: Boolean = false,
    @SerializedName("public_id") val publicId: String? = null,
    @SerializedName("public_url") val publicUrl: String? = null,
    @SerializedName("expires_at") val expiresAt: Double? = null,
    val options: PublicShareOptions? = null,
    @SerializedName("password_required") val passwordRequired: Boolean = false
)

/** 绑定角色请求体 */
data class BindCharacterRequest(
    @SerializedName("sender_name") val senderName: String,
    @SerializedName("character_id") val characterId: String? = null,
    @SerializedName("sender_avatar") val senderAvatar: String? = null,
    @SerializedName("sender_portrait") val senderPortrait: String? = null,
    val scenario: String? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null
)

/** 消息收藏请求体 */
data class MessageFavoriteRequest(
    @SerializedName("message_ids") val messageIds: List<String>,
    val title: String? = null,
    @SerializedName("collection_id") val collectionId: String? = null
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
    @SerializedName(value = "sender", alternate = ["sender_name", "character_name"])
    val sender: String? = null,
    @SerializedName(value = "name", alternate = ["display_name"])
    val name: String? = null,
    @SerializedName(
        value = "avatar",
        alternate = ["sender_avatar", "sender_portrait", "character_avatar", "portrait"]
    )
    val avatar: String? = null,
    val type: String? = null,
    val timestamp: String? = null,
    @SerializedName("audio_url") val audioUrl: String? = null,
    val tokens: Int? = null,
    @SerializedName("input_tokens") val inputTokens: Int? = null,
    @SerializedName("output_tokens") val outputTokens: Int? = null,
    val model: String? = null,
    val filtered: Boolean? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    // 进度卡片（thinking_card）的结构化字段：服务端推送时携带，普通消息为 null
    val steps: List<ThinkingStep>? = null,
    @SerializedName("is_complete") val isComplete: Boolean? = null,
    @SerializedName("is_agent") val isAgent: Boolean? = null,
    @SerializedName("parent_message_id") val parentMessageId: String? = null,
    /**
     * 该用户消息关联的进度卡片列表（持久化字段）。
     *
     * 对应后端 message.thinking_cards：每条 agent 模式的用户消息都会挂载一个或多个
     * thinking_card（通常一个），记录 AI 在生成回复过程中的思考/工具调用/检索等步骤。
     * UI 在用户气泡与 AI 气泡之间渲染。
     */
    @SerializedName("thinking_cards") val thinkingCards: List<ThinkingCard>? = null,
    @SerializedName(value = "session_id", alternate = ["conversation_id", "sessionId"])
    val sessionId: String? = null
) {
    val isUser: Boolean
        get() = role.equals("user", ignoreCase = true) || role.equals("human", ignoreCase = true)
    val displayContent: String get() = content ?: ""
    /** 是否为进度卡片（thinking_card），不应在聊天列表中展示 */
    val isThinkingCard: Boolean
        get() = type.equals("thinking_card", ignoreCase = true) || role.equals("system", ignoreCase = true)
}

/**
 * 进度卡片单步：对应原仓库 progress_card.py 的 step 结构。
 * 字段对齐 Web 端 thinking-step 渲染所需信息。
 */
data class ThinkingStep(
    val type: String? = null,           // start/thinking/ai_thinking/tool/tool_done/image/file/upload/knowledge/done
    val icon: String? = null,           // emoji：🤔 💭 🧠 🔧 🖼️ 📄 📤 📚 ✅
    val name: String? = null,           // 步骤名称（如"AI 正在思考..."/工具显示名）
    val status: String? = null,         // active/running/done/error
    val detail: String? = null,         // 摘要（工具参数/结果前 100-200 字符）
    val arguments: Map<String, Any>? = null,
    @SerializedName("full_result") val fullResult: Any? = null,
    @SerializedName("thinking_content") val thinkingContent: String? = null
)

/**
 * 进度卡片聚合视图：便于 UI 层统一处理本地/远程两种来源。
 * 远程模式由 thinking_card Message 转换而来；本地模式由 ProgressReporter 直接构造。
 */
data class ThinkingCard(
    val id: String,
    val content: String,                // 头部文本，如"🔄 AI 正在处理... (1/50)"或"✅ 处理完成"
    val steps: List<ThinkingStep> = emptyList(),
    /** 0-100 的确定进度；为空时沿用不确定进度动画。 */
    val progress: Int? = null,
    val isComplete: Boolean = false,
    val isAgent: Boolean = false,
    /**
     * 最后更新时间。
     * - 远程：后端推送的 ISO 字符串（如 "2026-07-18T00:20:15.416921"）
     * - 本地：[com.nekobot.app.data.local.LocalRepository.nowIsoStatic] 产出的 ISO 字符串
     * 使用 String 类型以兼容后端 ISO 字符串，避免 Gson 反序列化时 Long 解析失败。
     */
    val timestamp: String = com.nekobot.app.data.local.LocalRepository.nowIsoStatic(),
    /** 关联的父用户消息 id；用于在用户气泡与 AI 气泡之间渲染，并持久化到父消息 */
    @SerializedName("parent_message_id") val parentMessageId: String? = null
)

data class ChatRequest(
    val message: String? = null,
    val content: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    val attachments: List<Map<String, Any>> = emptyList()
) {
    companion object {
        fun of(
            text: String,
            attachments: List<Map<String, Any>> = emptyList()
        ) = ChatRequest(
            message = text,
            content = text,
            attachments = attachments
        )
    }
}

data class SendMessageRequest(
    val role: String = "user",
    val content: String,
    val sender: String? = null
)

/** 更新单条消息；所有字段可选，TTS 仅提交 audio_url，不会改写正文。 */
data class UpdateMessageRequest(
    val role: String? = null,
    val content: String? = null,
    val sender: String? = null,
    @SerializedName("audio_url") val audioUrl: String? = null,
    @SerializedName("truncate_after") val truncateAfter: Boolean? = null
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
    // 兼容 SillyTavern v2 snake_case：first_mes / alternate_greetings
    @SerializedName(value = "firstMessage", alternate = ["first_mes"]) val firstMessage: String? = null,
    @SerializedName(value = "alternateGreetings", alternate = ["alternate_greetings"]) val alternateGreetings: List<String>? = null,
    @SerializedName("exampleDialogues", alternate = ["dialog_examples", "mes_example"])
    val exampleDialogues: String? = null,
    @SerializedName("responseFormat") val responseFormat: String? = null,
    val rules: List<String>? = null,
    // state 是 JSON 对象（affection / trust / familiarity / dependency / security / mood）
    val state: com.google.gson.JsonObject? = null,
    // 兼容 SillyTavern v2 snake_case：system_prompt
    @SerializedName(value = "systemPrompt", alternate = ["system_prompt"]) val systemPrompt: String? = null,
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
    @SerializedName(value = "keys", alternate = ["keywords"])
    val keys: List<String>? = null,
    val content: String? = null,
    val comment: String? = null,
    val enabled: Boolean? = null,
    @SerializedName(value = "constant", alternate = ["always_on"])
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
    @SerializedName(value = "character_ids", alternate = ["character_id", "characterId", "characterIds"])
    val characterIds: List<String>? = null,
    val enabled: Boolean? = null
)

data class WorldBookEntryRequest(
    @SerializedName(value = "keywords", alternate = ["keys"])
    val keys: List<String>? = null,
    val content: String? = null,
    val comment: String? = null,
    val enabled: Boolean? = null,
    @SerializedName(value = "always_on", alternate = ["constant"])
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
    @SerializedName("max_context_length") val maxContextLength: Int? = null,
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
    @SerializedName(value = "provider_type", alternate = ["protocol"])
    val protocol: String? = null,
    val provider: String? = null,
    @SerializedName("api_key") val apiKey: String? = null,
    @SerializedName("base_url") val baseUrl: String? = null,
    @SerializedName("append_base_url_path") val appendBaseUrlPath: Boolean? = null,
    @SerializedName(value = "model", alternate = ["model_name"])
    val model: String? = null,
    val enabled: Boolean? = null,
    val purpose: String? = null,
    val priority: Int? = null,
    val temperature: Double? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("max_context_length") val maxContextLength: Int? = null,
    @SerializedName("top_p") val topP: Double? = null,
    @SerializedName("input_price") val inputPrice: Double? = null,
    @SerializedName("output_price") val outputPrice: Double? = null,
    @SerializedName("supports_tools") val supportsTools: Boolean? = null,
    @SerializedName("supports_reasoning") val supportsReasoning: Boolean? = null,
    @SerializedName("supports_stream") val supportsStream: Boolean? = null,
    @SerializedName("tts_provider") val ttsProvider: String? = null,
    @SerializedName("tts_url") val ttsUrl: String? = null,
    @SerializedName("tts_model") val ttsModel: String? = null,
    @SerializedName("tts_voice") val ttsVoice: String? = null,
    @SerializedName("tts_speed") val ttsSpeed: Float? = null,
    @SerializedName("tts_pitch") val ttsPitch: Float? = null,
    @SerializedName("tts_volume") val ttsVolume: Float? = null,
    @SerializedName("tts_format") val ttsFormat: String? = null,
    @SerializedName("tts_upload_url") val ttsUploadUrl: String? = null,
    @SerializedName("tts_headers") val ttsHeaders: String? = null,
    @SerializedName("tts_body_template") val ttsBodyTemplate: String? = null,
    @SerializedName("tts_resource_id") val ttsResourceId: String? = null,
    @SerializedName("tts_ref_audio") val ttsRefAudio: String? = null,
    @SerializedName("tts_user") val ttsUser: String? = null,
    val language: String? = null,
    @SerializedName("stt_provider") val sttProvider: String? = null,
    @SerializedName("stt_url") val sttUrl: String? = null,
    @SerializedName("stt_model") val sttModel: String? = null,
    @SerializedName("stt_language") val sttLanguage: String? = null,
    @SerializedName("stt_headers") val sttHeaders: String? = null,
    val dimensions: Int? = null,
    val size: String? = null,
    @SerializedName("prompt_template") val promptTemplate: String? = null,
    val type: String? = null,
    val active: Boolean? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: model ?: "未命名模型"
}

data class AiModelRequest(
    val name: String,
    @SerializedName("provider_type") val protocol: String? = null,
    val provider: String? = null,
    @SerializedName("api_key") val apiKey: String? = null,
    @SerializedName("base_url") val baseUrl: String? = null,
    @SerializedName("append_base_url_path") val appendBaseUrlPath: Boolean? = null,
    val model: String? = null,
    val enabled: Boolean? = null,
    val purpose: String? = null,
    val priority: Int? = null,
    val temperature: Double? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("max_context_length") val maxContextLength: Int? = null,
    @SerializedName("top_p") val topP: Double? = null,
    @SerializedName("input_price") val inputPrice: Double? = null,
    @SerializedName("output_price") val outputPrice: Double? = null,
    @SerializedName("supports_tools") val supportsTools: Boolean? = null,
    @SerializedName("supports_reasoning") val supportsReasoning: Boolean? = null,
    @SerializedName("supports_stream") val supportsStream: Boolean? = null,
    @SerializedName("tts_provider") val ttsProvider: String? = null,
    @SerializedName("tts_url") val ttsUrl: String? = null,
    @SerializedName("tts_model") val ttsModel: String? = null,
    @SerializedName("tts_voice") val ttsVoice: String? = null,
    @SerializedName("tts_speed") val ttsSpeed: Float? = null,
    @SerializedName("tts_pitch") val ttsPitch: Float? = null,
    @SerializedName("tts_volume") val ttsVolume: Float? = null,
    @SerializedName("tts_format") val ttsFormat: String? = null,
    @SerializedName("tts_upload_url") val ttsUploadUrl: String? = null,
    @SerializedName("tts_headers") val ttsHeaders: String? = null,
    @SerializedName("tts_body_template") val ttsBodyTemplate: String? = null,
    @SerializedName("tts_resource_id") val ttsResourceId: String? = null,
    @SerializedName("tts_ref_audio") val ttsRefAudio: String? = null,
    @SerializedName("tts_user") val ttsUser: String? = null,
    val language: String? = null,
    @SerializedName("stt_provider") val sttProvider: String? = null,
    @SerializedName("stt_url") val sttUrl: String? = null,
    @SerializedName("stt_model") val sttModel: String? = null,
    @SerializedName("stt_language") val sttLanguage: String? = null,
    @SerializedName("stt_headers") val sttHeaders: String? = null,
    val dimensions: Int? = null,
    val size: String? = null,
    @SerializedName("prompt_template") val promptTemplate: String? = null
)

data class FetchModelsRequest(
    @SerializedName("base_url") val baseUrl: String,
    @SerializedName("api_key") val apiKey: String? = null,
    @SerializedName("provider_type") val protocol: String? = null,
    @SerializedName("append_base_url_path") val appendBaseUrlPath: Boolean = true
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
    @SerializedName("updated_at") val updatedAt: String? = null,
    // 本地模式扩展：真实 memoryfs category（user_persona/character_persona/important_event/timeline/life_sim/recent_digest/legacy）
    // 远程模式反序列化时若无此字段则为 null，不影响兼容性
    val category: String? = null
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
    @SerializedName("skill_md") val skillMd: String? = null,
    @SerializedName("reference_md") val referenceMd: String? = null,
    val license: String? = null,
    @SerializedName("source_url") val sourceUrl: String? = null,
    @SerializedName("has_storage") val hasStorage: Boolean = false,
    val files: List<SkillFileInfo> = emptyList(),
    @SerializedName("created_at") val createdAt: String? = null
) {
    val displayName: String get() = name.ifBlank { "未命名 Skill" }
}

data class SkillFileInfo(
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    val type: String = "other"
)

data class SkillRequest(
    val name: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val enabled: Boolean = true,
    val parameters: JsonElement? = null,
    @SerializedName("skill_md") val skillMd: String? = null,
    @SerializedName("reference_md") val referenceMd: String? = null
)

data class SkillInstallRequest(
    val url: String,
    val enabled: Boolean = true,
    val overwrite: Boolean = false
)

data class SkillMutationResponse(
    val success: Boolean? = null,
    val skill: Skill? = null,
    val enabled: Boolean? = null
)

data class SkillStorageDetail(
    val name: String = "",
    @SerializedName("skill_md") val skillMd: String? = null,
    @SerializedName("reference_md") val referenceMd: String? = null,
    val license: String? = null,
    val files: List<SkillFileInfo> = emptyList(),
    val scripts: List<String> = emptyList(),
    val resources: List<String> = emptyList()
)

data class SkillUploadResponse(
    val success: Boolean? = null,
    val skill: Skill? = null,
    val message: String? = null,
    @SerializedName("files_count") val filesCount: Int? = null
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

/** 角色立绘/头像上传响应：返回服务器相对 URL，写入 character.portrait/avatar 字段 */
data class PortraitUploadResponse(
    val success: Boolean? = null,
    val url: String? = null,
    val error: String? = null
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

// ==================== 负一屏角色发现 ====================
/**
 * AI 随机生成的角色灵感条目。
 * 在数据层定义，避免 data 模块反向依赖 UI 层。
 */
data class RandomCharacterIdea(
    val title: String,
    val description: String,
    val tags: List<String> = emptyList()
)
