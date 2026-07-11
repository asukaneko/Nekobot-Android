package com.nekobot.app.data.local.ai

import java.time.Instant
import kotlin.random.Random

/**
 * 状态机，对应原仓库 nbot/character/state_machine.py。
 *
 * 核心原则：
 * - 情绪惯性：情绪不会一句话突变（旧情绪权重 0.75，新信号 0.25）
 * - 数值边界：所有关系值在 0-100 范围内
 * - 每轮变化限幅：避免暴涨暴跌
 */

/** 情绪惯性系数 */
private const val MOOD_INERTIA = 0.75f

/** 每轮变化限幅 */
private val MAX_DELTA_PER_TURN = mapOf(
    "affection" to 3,
    "trust" to 3,
    "familiarity" to 2,
    "dependency" to 2,
    "security" to 4,
    "jealousy" to 2
)

/** 情绪转移表：情绪的自然变化路径 */
private val MOOD_TRANSITIONS = mapOf(
    "开心" to listOf("放松", "得意", "黏人"),
    "委屈" to listOf("不安", "沉默", "伤心"),
    "害羞" to listOf("开心", "放松"),
    "受伤" to listOf("不安", "沉默", "委屈"),
    "感动" to listOf("幸福", "依赖"),
    "幸福" to listOf("黏人", "放松"),
    "不安" to listOf("害怕", "试探"),
    "平静" to listOf("放松", "期待"),
    "生气" to listOf("沉默", "委屈")
)

/** 恢复性关键词 */
private val RESTORATIVE_KEYWORDS = listOf(
    "休息", "睡觉", "补觉", "放松", "歇一会", "休息一下",
    "吃饭", "喝水", "充电", "摸摸", "抱抱", "晚安"
)

private fun clampEnergy(value: Int): Int = value.coerceIn(0, 100)

private fun signalScore(signals: UserSignals?, field: String): Float {
    if (signals == null) return 0f
    return when (field) {
        "careScore" -> signals.careScore
        "affectionScore" -> signals.affectionScore
        "intimacyScore" -> signals.intimacyScore
        "praiseScore" -> signals.praiseScore
        "apologyScore" -> signals.apologyScore
        "playfulnessScore" -> signals.playfulnessScore
        "hostilityScore" -> signals.hostilityScore
        "rejectionScore" -> signals.rejectionScore
        "commandScore" -> signals.commandScore
        "arousalScore" -> signals.arousalScore
        else -> 0f
    }
}

/** 角色状态机，负责情绪和关系的更新 */
object StateMachine {

    /**
     * 应用状态变化。
     *
     * @param oldState 旧角色状态
     * @param oldRelationship 旧关系状态
     * @param signals 用户信号
     * @param plan 反应计划
     * @param userMessage 用户消息
     * @param assistantMessage 助手回复
     * @return Pair(new_state, new_relationship)
     */
    fun apply(
        oldState: CharacterState,
        oldRelationship: RelationshipState,
        signals: UserSignals?,
        plan: ReactionPlan,
        userMessage: String = "",
        assistantMessage: String = ""
    ): Pair<CharacterState, RelationshipState> {
        val newState = applyState(oldState, plan, signals, userMessage, assistantMessage)
        val newRelationship = applyRelationship(oldRelationship, plan)
        return newState to newRelationship
    }

    /** 更新角色运行时状态 */
    private fun applyState(
        oldState: CharacterState,
        plan: ReactionPlan,
        signals: UserSignals? = null,
        userMessage: String = "",
        assistantMessage: String = ""
    ): CharacterState {
        val now = Instant.now().toString()
        val newState = CharacterState(
            characterId = oldState.characterId,
            scopeId = oldState.scopeId,
            mood = oldState.mood,
            moodIntensity = oldState.moodIntensity,
            energy = oldState.energy,
            scene = oldState.scene.toMap(),
            lastActiveAt = now,
            updatedAt = now,
            personalityEvolution = oldState.personalityEvolution.toList()
        )

        // 情绪更新
        val deltas = plan.stateDeltas
        var moodChangedByPlan = false
        if (deltas.isNotEmpty()) {
            val targetMood = deltas["mood_toward"] as? String ?: oldState.mood
            val intensityDelta = (deltas["mood_intensity_delta"] as? Number)?.toFloat() ?: 0f

            if (targetMood != oldState.mood && (kotlin.math.abs(intensityDelta) >= 0.08f || oldState.moodIntensity < 0.55f)) {
                // 情绪惯性：弱信号不会立刻覆盖较强的当前情绪
                newState.mood = targetMood
                moodChangedByPlan = true
            }

            // 情绪强度更新：惯性混合
            val newIntensity = oldState.moodIntensity * MOOD_INERTIA +
                (oldState.moodIntensity + intensityDelta) * (1 - MOOD_INERTIA)
            newState.moodIntensity = newIntensity.coerceIn(0f, 1f)
        } else {
            // 无显著信号时缓慢回落
            newState.moodIntensity = (oldState.moodIntensity - 0.03f).coerceAtLeast(0f)
        }

        // 精力更新
        val energyDelta = calculateEnergyDelta(oldState.energy, signals, userMessage, assistantMessage)
        newState.energy = clampEnergy(oldState.energy + energyDelta)

        // 情绪自然转移
        if (!moodChangedByPlan) {
            maybeApplyMoodTransition(newState)
        }

        return newState
    }

    /** 根据转移表自然漂移情绪 */
    private fun maybeApplyMoodTransition(state: CharacterState) {
        val candidates = MOOD_TRANSITIONS[state.mood] ?: return
        val transitionProb = if (state.moodIntensity >= 0.4f) 0.15f else 0.35f
        if (Random.nextFloat() < transitionProb) {
            state.mood = candidates.random()
            state.moodIntensity = (state.moodIntensity + 0.05f).coerceAtMost(1f)
        }
    }

    /** 计算本轮精力变化量 */
    private fun calculateEnergyDelta(
        oldEnergy: Int,
        signals: UserSignals?,
        userMessage: String,
        assistantMessage: String
    ): Int {
        var delta = 0

        if (oldEnergy < 30) delta += 2
        else if (oldEnergy < 65) delta += 1

        val care = signalScore(signals, "careScore")
        val affection = signalScore(signals, "affectionScore")
        val intimacy = signalScore(signals, "intimacyScore")
        val praise = signalScore(signals, "praiseScore")
        val apology = signalScore(signals, "apologyScore")
        val playfulness = signalScore(signals, "playfulnessScore")
        val hostility = signalScore(signals, "hostilityScore")
        val rejection = signalScore(signals, "rejectionScore")
        val command = signalScore(signals, "commandScore")
        val arousal = signalScore(signals, "arousalScore")

        if (care >= 0.45f) delta += 2
        if (maxOf(affection, intimacy, praise) >= 0.65f) delta += 1
        if (apology >= 0.45f) delta += 1
        if (playfulness >= 0.35f && hostility < 0.4f) delta += 1

        val loweredMessage = userMessage.lowercase()
        val hasRestorativeKeyword = RESTORATIVE_KEYWORDS.any { it in loweredMessage }
        if (hasRestorativeKeyword) delta += 2

        if (maxOf(hostility, rejection) >= 0.6f) delta -= 2
        if (command >= 0.4f) delta -= 1
        if (arousal >= 0.75f && care < 0.45f) delta -= 1
        if (assistantMessage.length > 1200) delta -= 1

        if (oldEnergy <= 20 && delta < 1) delta = 1
        if (oldEnergy >= 85 && delta > 1 && !hasRestorativeKeyword) delta = 1

        return delta.coerceIn(-3, 5)
    }

    /** 更新关系状态 */
    private fun applyRelationship(
        oldRel: RelationshipState,
        plan: ReactionPlan
    ): RelationshipState {
        val newRel = RelationshipState(
            characterId = oldRel.characterId,
            targetId = oldRel.targetId,
            affection = oldRel.affection,
            trust = oldRel.trust,
            familiarity = oldRel.familiarity,
            dependency = oldRel.dependency,
            security = oldRel.security,
            jealousy = oldRel.jealousy,
            updatedAt = Instant.now().toString()
        )

        val deltas = plan.relationshipDeltas
        if (deltas.isEmpty()) return newRel

        for ((fieldName, delta) in deltas) {
            val maxDelta = MAX_DELTA_PER_TURN[fieldName] ?: continue
            val clampedDelta = (delta as? Int ?: delta.toString().toIntOrNull() ?: 0).coerceIn(-maxDelta, maxDelta)

            when (fieldName) {
                "affection" -> newRel.affection = (newRel.affection + clampedDelta).coerceIn(0, 100)
                "trust" -> newRel.trust = (newRel.trust + clampedDelta).coerceIn(0, 100)
                "familiarity" -> newRel.familiarity = (newRel.familiarity + clampedDelta).coerceIn(0, 100)
                "dependency" -> newRel.dependency = (newRel.dependency + clampedDelta).coerceIn(0, 100)
                "security" -> newRel.security = (newRel.security + clampedDelta).coerceIn(0, 100)
                "jealousy" -> newRel.jealousy = (newRel.jealousy + clampedDelta).coerceIn(0, 100)
            }
        }
        return newRel
    }
}
