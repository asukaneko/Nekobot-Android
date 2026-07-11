package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * 角色运行时数据模型，对应原仓库 nbot/character/models.py。
 *
 * 包含：角色卡、运行时状态、关系状态、记忆、反应计划、身份标识、每轮上下文。
 */

private val gson = Gson()

// ==================== 静态角色卡 ====================

/**
 * 静态角色卡，描述角色的固定设定。
 * 对应 CharacterProfile。
 */
data class CharacterProfile(
    val id: String = "",
    val name: String = "",
    val version: Int = 1,
    val description: String = "",
    val avatar: String = "",
    val portrait: String = "",
    val tags: List<String> = emptyList(),
    val basicInfo: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val exampleDialogues: String = "",
    val responseFormat: String = "",
    val rules: List<String> = emptyList(),
    /** 初始状态（关系值等） */
    val initialState: Map<String, Any> = emptyMap(),
    /** 扩展元数据（含 greeting 等） */
    val metadata: Map<String, Any> = emptyMap(),
    /** 旧字段兼容 */
    val systemPrompt: String = ""
) {
    companion object {
        /** 从旧 personality.json 格式（nekobot 协议）转换 */
        @Suppress("UNCHECKED_CAST")
        fun fromPersonalityDict(data: Map<String, Any>): CharacterProfile {
            return CharacterProfile(
                id = data["id"] as? String ?: "",
                name = data["name"] as? String ?: "",
                description = data["description"] as? String ?: "",
                avatar = data["avatar"] as? String ?: "",
                portrait = data["portrait"] as? String ?: "",
                tags = (data["tags"] as? List<String>) ?: emptyList(),
                basicInfo = data["basicInfo"] as? String ?: "",
                personality = data["personality"] as? String ?: "",
                scenario = data["scenario"] as? String ?: "",
                firstMessage = data["firstMessage"] as? String ?: "",
                exampleDialogues = data["exampleDialogues"] as? String ?: "",
                responseFormat = data["responseFormat"] as? String ?: "",
                rules = (data["rules"] as? List<String>) ?: emptyList(),
                systemPrompt = data["systemPrompt"] as? String ?: "",
                initialState = normalizeInitialState(data),
                metadata = mapOf("greeting" to (data["greeting"] as? String ?: ""))
            )
        }
    }

    /** 转换为旧 personality.json 格式（兼容 API） */
    fun toPersonalityDict(): Map<String, Any> = buildMap {
        put("id", id)
        put("name", name)
        put("description", description)
        put("avatar", avatar)
        put("portrait", portrait)
        put("tags", tags)
        put("basicInfo", basicInfo)
        put("personality", personality)
        put("scenario", scenario)
        put("firstMessage", firstMessage)
        put("exampleDialogues", exampleDialogues)
        put("responseFormat", responseFormat)
        put("rules", rules)
        put("state", initialState)
        put("systemPrompt", systemPrompt)
        put("greeting", metadata["greeting"] ?: "")
    }
}

// ==================== 运行时状态 ====================

/**
 * 角色运行时状态，每个 scope_id（会话/用户）独立。
 * 对应 CharacterState。
 */
data class CharacterState(
    val characterId: String = "",
    val scopeId: String = "",
    /** 当前情绪（如"平静"/"开心"/"愤怒"） */
    var mood: String = "平静",
    /** 情绪强度 0-1 */
    var moodIntensity: Float = 0.5f,
    /** 精力 0-100 */
    var energy: Int = 70,
    /** 场景信息（current_activity 等） */
    var scene: Map<String, Any> = emptyMap(),
    var lastActiveAt: String = "",
    var updatedAt: String = "",
    /**
     * 性格演化：经历塑造人格偏移，不修改 profile.personality 本身。
     * 结构示例: [{"trait":"openness","delta":-5,"reason":"长期被忽视后更内向","turn":42}]
     */
    var personalityEvolution: List<Map<String, Any>> = emptyList()
) {
    fun toDict(): Map<String, Any> = mapOf(
        "character_id" to characterId,
        "scope_id" to scopeId,
        "mood" to mood,
        "mood_intensity" to moodIntensity,
        "energy" to energy,
        "scene" to scene,
        "last_active_at" to lastActiveAt,
        "updated_at" to updatedAt,
        "personality_evolution" to personalityEvolution
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromDict(data: Map<String, Any>): CharacterState = CharacterState(
            characterId = data["character_id"] as? String ?: "",
            scopeId = data["scope_id"] as? String ?: "",
            mood = data["mood"] as? String ?: "平静",
            moodIntensity = (data["mood_intensity"] as? Number)?.toFloat() ?: 0.5f,
            energy = (data["energy"] as? Number)?.toInt() ?: 70,
            scene = data["scene"] as? Map<String, Any> ?: emptyMap(),
            lastActiveAt = data["last_active_at"] as? String ?: "",
            updatedAt = data["updated_at"] as? String ?: "",
            personalityEvolution = (data["personality_evolution"] as? List<Map<String, Any>>) ?: emptyList()
        )

        /** 从 JSON 字符串反序列化 */
        fun fromJson(json: String): CharacterState {
            if (json.isBlank()) return CharacterState()
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                fromDict(gson.fromJson(obj, object : TypeToken<Map<String, Any>>() {}.type))
            } catch (e: Exception) {
                CharacterState()
            }
        }
    }

    /** 序列化为 JSON 字符串 */
    fun toJson(): String = gson.toJson(toDict())
}

// ==================== 关系状态 ====================

/**
 * 角色与目标用户的关系状态。
 * 对应 RelationshipState。
 */
data class RelationshipState(
    val characterId: String = "",
    val targetId: String = "",
    /** 好感 0-100 */
    var affection: Int = 50,
    /** 信任 0-100 */
    var trust: Int = 50,
    /** 熟悉 0-100 */
    var familiarity: Int = 30,
    /** 依赖 0-100 */
    var dependency: Int = 30,
    /** 安全感 0-100 */
    var security: Int = 50,
    /** 嫉妒 0-100 */
    var jealousy: Int = 0,
    var updatedAt: String = ""
) {
    fun toDict(): Map<String, Any> = mapOf(
        "character_id" to characterId,
        "target_id" to targetId,
        "affection" to affection,
        "trust" to trust,
        "familiarity" to familiarity,
        "dependency" to dependency,
        "security" to security,
        "jealousy" to jealousy,
        "updated_at" to updatedAt
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromDict(data: Map<String, Any>): RelationshipState = RelationshipState(
            characterId = data["character_id"] as? String ?: "",
            targetId = data["target_id"] as? String ?: "",
            affection = (data["affection"] as? Number)?.toInt() ?: 50,
            trust = (data["trust"] as? Number)?.toInt() ?: 50,
            familiarity = (data["familiarity"] as? Number)?.toInt() ?: 30,
            dependency = (data["dependency"] as? Number)?.toInt() ?: 30,
            security = (data["security"] as? Number)?.toInt() ?: 50,
            jealousy = (data["jealousy"] as? Number)?.toInt() ?: 0,
            updatedAt = data["updated_at"] as? String ?: ""
        )

        fun fromJson(json: String): RelationshipState {
            if (json.isBlank()) return RelationshipState()
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                fromDict(gson.fromJson(obj, object : TypeToken<Map<String, Any>>() {}.type))
            } catch (e: Exception) {
                RelationshipState()
            }
        }
    }

    fun toJson(): String = gson.toJson(toDict())
}

// ==================== 角色记忆 ====================

/**
 * 角色记忆条目。
 * 对应 CharacterMemory。
 */
data class CharacterMemory(
    val id: String = "",
    val characterId: String = "",
    val targetId: String = "",
    /** 类型：long / short / flash */
    val type: String = "long",
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    /** 重要性 1-10 */
    val importance: Int = 5,
    /** 情感影响 */
    val emotionImpact: Map<String, Any> = emptyMap(),
    val sourceTurnId: String? = null,
    val createdAt: String = "",
    val expiresAt: String? = null
) {
    fun toDict(): Map<String, Any> = buildMap {
        put("id", id)
        put("character_id", characterId)
        put("target_id", targetId)
        put("type", type)
        put("title", title)
        put("summary", summary)
        put("content", content)
        put("importance", importance)
        put("emotion_impact", emotionImpact)
        sourceTurnId?.let { put("source_turn_id", it) }
        put("created_at", createdAt)
        expiresAt?.let { put("expires_at", it) }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromDict(data: Map<String, Any>): CharacterMemory = CharacterMemory(
            id = data["id"] as? String ?: "",
            characterId = data["character_id"] as? String ?: "",
            targetId = data["target_id"] as? String ?: "",
            type = data["type"] as? String ?: "long",
            title = data["title"] as? String ?: "",
            summary = data["summary"] as? String ?: "",
            content = data["content"] as? String ?: "",
            importance = (data["importance"] as? Number)?.toInt() ?: 5,
            emotionImpact = data["emotion_impact"] as? Map<String, Any> ?: emptyMap(),
            sourceTurnId = data["source_turn_id"] as? String,
            createdAt = data["created_at"] as? String ?: "",
            expiresAt = data["expires_at"] as? String
        )
    }
}

// ==================== 反应计划 ====================

/**
 * 本轮反应计划，由 ReactionPlanner 在 before_turn 中生成。
 * 对应 ReactionPlan。
 */
data class ReactionPlan(
    /** 意图：respond / respond_naturally / comfort / tease / withdraw 等 */
    var intent: String = "respond",
    /** 语气：natural / warm / cold / playful / serious 等 */
    var tone: String = "natural",
    /** 表面情绪 */
    var visibleEmotion: String = "平静",
    /** 内心情绪（可与 visible_emotion 不同） */
    var hiddenEmotion: String = "",
    var shouldReferenceMemory: Boolean = false,
    var memoryIds: List<String> = emptyList(),
    /** 风格控制：length / action_detail / initiative 等 */
    var styleControls: Map<String, Any> = emptyMap(),
    /** 状态增量：mood_toward / mood_intensity_delta */
    var stateDeltas: Map<String, Any> = emptyMap(),
    /** 关系增量：affection / trust / ... 增量 */
    var relationshipDeltas: Map<String, Any> = emptyMap()
)

// ==================== 身份标识 ====================

/**
 * 角色身份标识，用于在 Pipeline 中传递角色上下文。
 * 对应 CharacterIdentity。
 */
data class CharacterIdentity(
    val characterId: String = "",
    val targetId: String = "",
    /** 状态隔离作用域 */
    val scopeId: String = "",
    val channel: String = ""
)

// ==================== 每轮上下文 ====================

/**
 * 角色运行时每轮上下文，包含 before_turn 的全部产出。
 * 对应 CharacterTurnContext。
 */
data class CharacterTurnContext(
    val profile: CharacterProfile = CharacterProfile(),
    var state: CharacterState = CharacterState(),
    var relationship: RelationshipState = RelationshipState(),
    val memories: List<CharacterMemory> = emptyList(),
    /** 用户信号分析结果（UserSignals），阶段2实现 */
    val signals: Any? = null,
    val plan: ReactionPlan = ReactionPlan(),
    /** 编译后的提示词文本 */
    var promptText: String = "",
    val worldBookEntries: List<Any> = emptyList()
)

// ==================== 辅助函数 ====================

/** 初始状态容器 key，兼容多种格式 */
private val INITIAL_STATE_CONTAINER_KEYS = listOf("state", "initial_state", "initialState")
/** 关系容器 key */
private val RELATIONSHIP_CONTAINER_KEYS = listOf("relationship", "initial_relationship", "initialRelationship")

/**
 * 归一化角色初始状态，从多种 key 中提取关系值。
 * 对应原仓库 normalize_character_initial_state。
 */
@Suppress("UNCHECKED_CAST")
fun normalizeInitialState(data: Map<String, Any>): Map<String, Any> {
    val initialState = mutableMapOf<String, Any>()

    for (key in INITIAL_STATE_CONTAINER_KEYS) {
        mergeInitialStateFields(initialState, data[key] as? Map<String, Any>)
    }
    for (key in RELATIONSHIP_CONTAINER_KEYS) {
        mergeInitialStateFields(initialState, data[key] as? Map<String, Any>)
    }
    mergeInitialStateFields(initialState, data)

    return initialState
}

private fun mergeInitialStateFields(target: MutableMap<String, Any>, source: Map<String, Any>?) {
    if (source == null) return
    val knownKeys = setOf(
        "affection", "trust", "familiarity", "dependency", "security", "jealousy",
        "mood", "mood_intensity", "energy"
    )
    for (key in knownKeys) {
        source[key]?.let { target[key] = it }
    }
}
