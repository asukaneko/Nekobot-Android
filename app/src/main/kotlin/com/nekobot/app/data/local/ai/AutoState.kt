package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.db.LocalAiModelEntity
import java.time.Instant

/**
 * 完整版 AutoState（LLM 状态评估），对应原仓库 nbot/character/auto_state.py。
 *
 * 每 2 轮（可配置）调用 LLM 评估角色状态变化：
 * mood / mood_intensity / energy / relationship / personality_evolution + 质量评分。
 */
class AutoState(
    private val aiClient: LocalAiClient,
    private val aiModelProvider: (suspend () -> LocalAiModelEntity?)? = null,
    /**
     * 二级 LLM 调用 token 记账回调
     * 参数：source, model（配置名）, actualModel（实际模型标识，用于排行榜聚合）, inputTokens, outputTokens
     */
    private val onTokenUsage: ((String, String, String, Int, Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AutoState"
        private const val STATE_TURN_INTERVAL = 2
        private const val STATE_TURN_WINDOW = 5
        private const val MAX_PERSONALITY_EVOLUTION = 20

        val RELATIONSHIP_FIELDS = listOf("affection", "trust", "familiarity", "dependency", "security", "jealousy")
        val RELATIONSHIP_MAX_DELTA = mapOf(
            "affection" to 8, "trust" to 8, "security" to 8,
            "familiarity" to 6, "dependency" to 6, "jealousy" to 6
        )
        private val QUALITY_SCORE_FIELDS = listOf("character_fidelity", "immersion", "world_consistency", "risk")
    }

    private val gson = Gson()
    private val turnCounters = mutableMapOf<String, Int>()
    private val turnBuffers = mutableMapOf<String, MutableList<Map<String, String>>>()
    private val qualityScoreCache = mutableMapOf<String, Map<String, Float>>()

    /**
     * 从最近对话更新角色状态（主入口）。
     *
     * @param profile 角色卡
     * @param state 当前状态
     * @param relationship 当前关系
     * @param userMessage 用户消息
     * @param assistantMessage 助手回复
     * @param metadata 元数据（含 auto_state_interval/skip_auto_state 等）
     * @param conversationId 会话 ID
     * @param resultError 错误信息（非空时跳过）
     * @return Triple(new_state, new_relationship, updated)
     */
    suspend fun updateStateFromRecentTurns(
        profile: CharacterProfile,
        state: CharacterState,
        relationship: RelationshipState,
        userMessage: String,
        assistantMessage: String,
        metadata: Map<String, Any> = emptyMap(),
        conversationId: String = "",
        resultError: String? = null
    ): Triple<CharacterState, RelationshipState, Boolean> {
        if (resultError != null) {
            com.nekobot.app.data.local.LocalLogger.d(TAG, "跳过 AutoState: resultError=$resultError")
            return Triple(state, relationship, false)
        }
        if (metadata["is_heartbeat"] == true) {
            com.nekobot.app.data.local.LocalLogger.d(TAG, "跳过 AutoState: heartbeat")
            return Triple(state, relationship, false)
        }
        if (metadata["skip_auto_state"] == true) {
            com.nekobot.app.data.local.LocalLogger.d(TAG, "跳过 AutoState: skip_auto_state=true")
            return Triple(state, relationship, false)
        }

        if (userMessage.length < 2 || assistantMessage.length < 2) {
            com.nekobot.app.data.local.LocalLogger.d(TAG, "跳过 AutoState: 消息过短 (user=${userMessage.length}, assistant=${assistantMessage.length})")
            return Triple(state, relationship, false)
        }

        val characterId = profile.id.ifEmpty { profile.name }
        val targetId = relationship.targetId.ifEmpty { (metadata["target_id"] as? String) ?: "" }
        val sessionId = (metadata["session_id"] as? String) ?: conversationId
        if (characterId.isEmpty() || targetId.isEmpty()) {
            com.nekobot.app.data.local.LocalLogger.d(TAG, "跳过 AutoState: characterId=$characterId targetId=$targetId 为空")
            return Triple(state, relationship, false)
        }

        val key = "$characterId:${state.scopeId}:$targetId:$conversationId:$sessionId"

        // 缓冲区累积
        val buffer = turnBuffers.getOrPut(key) { mutableListOf() }
        buffer.add(mapOf("user" to userMessage, "assistant" to assistantMessage))
        if (buffer.size > STATE_TURN_WINDOW) buffer.removeAt(0)

        // 计数器递增
        val count = (turnCounters[key] ?: 0) + 1
        turnCounters[key] = count

        // 会话级间隔覆盖
        val sessionInterval = (metadata["auto_state_interval"] as? Number)?.toInt() ?: STATE_TURN_INTERVAL
        if (sessionInterval <= 0) {
            com.nekobot.app.data.local.LocalLogger.d(TAG, "跳过 AutoState: sessionInterval=$sessionInterval <= 0")
            return Triple(state, relationship, false)
        }
        if (count < sessionInterval) {
            com.nekobot.app.data.local.LocalLogger.d(TAG, "AutoState 计数 $count/$sessionInterval，未达触发间隔 (key=$key)")
            return Triple(state, relationship, false)
        }
        com.nekobot.app.data.local.LocalLogger.i(TAG, "AutoState 达到触发条件: count=$count interval=$sessionInterval (key=$key)")

        // 取出缓冲区
        val turns = buffer.toList()

        // 调用 LLM 评估
        val adjustment = try {
            callStateModel(turns, profile, state, relationship)
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "状态评估异常: ${e.message}", e)
            turnCounters[key] = 0
            return Triple(state, relationship, false)
        }

        turnCounters[key] = 0

        if (adjustment.isEmpty()) return Triple(state, relationship, false)

        // 缓存质量评分
        @Suppress("UNCHECKED_CAST")
        val qualityScores = adjustment["quality_scores"] as? Map<String, Any>
        if (qualityScores != null) {
            storeQualityScores(characterId, targetId, conversationId, qualityScores)
        }

        // 应用调整
        val (newState, newRel) = applyAiAdjustment(state, relationship, adjustment)
        return Triple(newState, newRel, true)
    }

    /** 获取质量评分 */
    fun getQualityScores(characterId: String, targetId: String, conversationId: String): Map<String, Float> {
        val exactKey = "$characterId:$targetId:$conversationId"
        qualityScoreCache[exactKey]?.let { return it }
        // 退而求其次：按 character_id 前缀匹配
        val prefix = "$characterId:"
        for ((k, v) in qualityScoreCache.entries.reversed()) {
            if (k.startsWith(prefix)) return v
        }
        return emptyMap()
    }

    /** 调用 LLM 评估状态 */
    private suspend fun callStateModel(
        turns: List<Map<String, String>>,
        profile: CharacterProfile,
        state: CharacterState,
        relationship: RelationshipState
    ): Map<String, Any> {
        val model = aiModelProvider?.invoke() ?: run {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "callStateModel: 无可用激活模型（aiModelProvider 返回 null）")
            return emptyMap()
        }

        com.nekobot.app.data.local.LocalLogger.i(TAG, "callStateModel: 开始调用 LLM 状态评估 | 模型=${model.name}(${model.model}) | 轮次=${turns.size}")

        val currentSnapshot = mapOf(
            "mood" to state.mood,
            "mood_intensity" to state.moodIntensity,
            "energy" to state.energy,
            "relationship" to relationship.toDict()
        )

        val systemPrompt = buildStateSystemPrompt()
        val userPrompt = buildStateUserPrompt(profile.name, currentSnapshot, turns)

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        val result = aiClient.chatOnce(model, messages)
        // 记账二级 LLM 调用 token（与主对话区分，source=state）
        if (result.usage.isNotEmpty()) {
            onTokenUsage?.invoke(
                "state", model.name, model.model,
                result.usage["prompt"] ?: 0,
                result.usage["completion"] ?: 0
            )
        }
        if (result.error != null) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "callStateModel: LLM 调用返回错误: ${result.error}")
            return emptyMap()
        }
        if (result.content.isBlank()) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "callStateModel: LLM 返回空内容")
            return emptyMap()
        }

        com.nekobot.app.data.local.LocalLogger.i(TAG, "callStateModel: LLM 返回内容长度=${result.content.length} | 前100字符=${result.content.take(100)}")
        val parsed = parseStateResponse(result.content)
        if (parsed.isEmpty()) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "callStateModel: JSON 解析失败，返回空 Map")
        } else {
            com.nekobot.app.data.local.LocalLogger.i(TAG, "callStateModel: 解析成功，字段=${parsed.keys}")
        }
        return parsed
    }

    /** 构建状态评估 system prompt */
    private fun buildStateSystemPrompt(): String {
        return """你是一个角色状态评估器，不是角色扮演者。

任务：根据最近对话，评估角色状态变化。返回 JSON：
{
  "mood": "情绪字符串（空字符串保持当前）",
  "mood_intensity_delta": -0.35 到 0.35,
  "energy_delta": -8 到 12（休息/放松时恢复）,
  "relationship_deltas": {
    "affection": -8到8, "trust": -8到8, "familiarity": -6到6,
    "dependency": -6到6, "security": -8到8, "jealousy": -6到6
  },
  "quality_scores": {
    "character_fidelity": 0.0-1.0,
    "immersion": 0.0-1.0,
    "world_consistency": 0.0-1.0,
    "risk": 0.0-1.0
  },
  "personality_evolution": [
    {"trait": "特质名", "delta": -10到10, "reason": "简短理由"}
  ],
  "reason": "简短理由"
}

要求：
- 状态应情感响应：有意义事件后 mood_intensity 可增加
- 关系值在持久信号下变化，避免小聊导致剧变
- 0 表示不变
- personality_evolution 仅重大经历才加，普通聊天为空数组
- quality_scores 基于对话质量评估

只返回 JSON，不要其他文字。"""
    }

    /** 构建状态评估 user prompt */
    private fun buildStateUserPrompt(
        characterName: String,
        currentSnapshot: Map<String, Any>,
        turns: List<Map<String, String>>
    ): String {
        val turnParts = turns.mapIndexed { idx, turn ->
            "--- Turn ${idx + 1} ---\n用户:\n${turn["user"] ?: ""}\n\n$characterName:\n${turn["assistant"] ?: ""}"
        }.joinToString("\n\n")
        return "角色名: $characterName\n\n当前状态:\n${gson.toJson(currentSnapshot)}\n\n最近对话:\n$turnParts"
    }

    /** 解析 LLM 返回的状态 JSON */
    private fun parseStateResponse(text: String): Map<String, Any> {
        val cleaned = cleanResponseContent(text)
        if (cleaned.isEmpty()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(cleaned, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 应用 LLM 返回的调整 */
    private fun applyAiAdjustment(
        state: CharacterState,
        relationship: RelationshipState,
        adjustment: Map<String, Any>
    ): Pair<CharacterState, RelationshipState> {
        val now = Instant.now().toString()
        val newState = CharacterState(
            characterId = state.characterId, scopeId = state.scopeId,
            mood = state.mood, moodIntensity = state.moodIntensity, energy = state.energy,
            scene = state.scene, lastActiveAt = now, updatedAt = now,
            personalityEvolution = state.personalityEvolution.toList()
        )
        val newRel = RelationshipState(
            characterId = relationship.characterId, targetId = relationship.targetId,
            affection = relationship.affection, trust = relationship.trust,
            familiarity = relationship.familiarity, dependency = relationship.dependency,
            security = relationship.security, jealousy = relationship.jealousy,
            updatedAt = now
        )

        // mood
        val moodStr = (adjustment["mood"] as? String ?: "").trim()
        if (moodStr.isNotEmpty()) newState.mood = moodStr.take(40)

        // mood_intensity
        val moodIntensityDelta = clampFloat(
            (adjustment["mood_intensity_delta"] as? Number)?.toFloat() ?: 0f,
            -0.35f, 0.35f
        )
        newState.moodIntensity = (state.moodIntensity + moodIntensityDelta).coerceIn(0f, 1f)

        // energy
        val energyDelta = clampInt(
            (adjustment["energy_delta"] as? Number)?.toInt() ?: 0,
            -8, 12
        )
        newState.energy = (state.energy + energyDelta).coerceIn(0, 100)

        // relationship
        @Suppress("UNCHECKED_CAST")
        val relDeltas = adjustment["relationship_deltas"] as? Map<String, Any> ?: emptyMap()
        for (field in RELATIONSHIP_FIELDS) {
            val delta = clampInt(
                (relDeltas[field] as? Number)?.toInt() ?: 0,
                -(RELATIONSHIP_MAX_DELTA[field] ?: 6), (RELATIONSHIP_MAX_DELTA[field] ?: 6)
            )
            when (field) {
                "affection" -> newRel.affection = (relationship.affection + delta).coerceIn(0, 100)
                "trust" -> newRel.trust = (relationship.trust + delta).coerceIn(0, 100)
                "familiarity" -> newRel.familiarity = (relationship.familiarity + delta).coerceIn(0, 100)
                "dependency" -> newRel.dependency = (relationship.dependency + delta).coerceIn(0, 100)
                "security" -> newRel.security = (relationship.security + delta).coerceIn(0, 100)
                "jealousy" -> newRel.jealousy = (relationship.jealousy + delta).coerceIn(0, 100)
            }
        }

        // personality_evolution
        @Suppress("UNCHECKED_CAST")
        val evolution = adjustment["personality_evolution"] as? List<Map<String, Any>> ?: emptyList()
        if (evolution.isNotEmpty()) {
            val currentList = state.personalityEvolution.toMutableList()
            val today = java.time.LocalDate.now().toString()
            for (item in evolution.take(2)) {
                val trait = (item["trait"] as? String ?: "").trim()
                if (trait.isEmpty()) continue
                val delta = clampInt((item["delta"] as? Number)?.toInt() ?: 0, -10, 10)
                val reason = (item["reason"] as? String ?: "").take(200)
                currentList.add(mapOf("trait" to trait, "delta" to delta, "reason" to reason, "turn" to today))
            }
            newState.personalityEvolution = currentList.takeLast(MAX_PERSONALITY_EVOLUTION)
        }

        return newState to newRel
    }

    /** 缓存质量评分 */
    private fun storeQualityScores(characterId: String, targetId: String, conversationId: String, rawScores: Map<String, Any>) {
        val key = "$characterId:$targetId:$conversationId"
        val scores = mutableMapOf<String, Float>()
        for (field in QUALITY_SCORE_FIELDS) {
            scores[field] = clampFloat((rawScores[field] as? Number)?.toFloat() ?: 0f, 0f, 1f)
        }
        qualityScoreCache[key] = scores
        if (qualityScoreCache.size > 200) {
            val firstKey = qualityScoreCache.keys.first()
            qualityScoreCache.remove(firstKey)
        }
    }

    private fun clampFloat(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)
    private fun clampInt(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)
}
