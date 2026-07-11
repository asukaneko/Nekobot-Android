package com.nekobot.app.data.local.ai

/**
 * 反应计划器，对应原仓库 nbot/character/planner.py。
 *
 * 将当前状态、关系、信号、记忆转化为本轮 ReactionPlan，
 * 用于注入提示词和驱动状态机。
 */

/** 情绪映射条目 */
private data class EmotionMapping(val visible: String, val hidden: String, val tone: String)

/** 28 种情绪映射表 */
private val EMOTION_MAP = mapOf(
    // ── 正面情感 ──
    "praise" to EmotionMapping("开心", "被认可的喜悦", "happy_clingy"),
    "affection" to EmotionMapping("害羞", "心里很开心", "shy_happy"),
    "intimacy" to EmotionMapping("幸福", "想一直在一起", "blissful"),
    "care" to EmotionMapping("感动", "被关心的温暖", "touched"),
    "reassurance" to EmotionMapping("放松", "被安抚后的依赖", "relieved_soft"),
    "joy" to EmotionMapping("开心", "被对方情绪感染得轻快", "bright_warm"),
    "playfulness" to EmotionMapping("得意", "觉得被逗得有点开心", "playful"),

    // ── 负面情感 ──
    "rejection" to EmotionMapping("委屈", "害怕被讨厌", "hurt_but_soft"),
    "hostility" to EmotionMapping("受伤", "害怕被抛弃", "hurt_scared"),
    "sadness" to EmotionMapping("心疼", "想靠近并安抚对方", "gentle_supportive"),
    "anger" to EmotionMapping("谨慎", "想先稳住气氛", "calm_careful"),
    "anxiety" to EmotionMapping("担心", "想让对方安心一点", "steady_reassuring"),
    "fatigue" to EmotionMapping("心疼", "想让对方先休息", "soft_caring"),

    // ── 复合/微妙情感 ──
    "vulnerability" to EmotionMapping("心疼", "想先接住对方的情绪", "gentle_supportive"),
    "apology" to EmotionMapping("心软", "想要和好", "soft_reassuring"),
    "uncertainty" to EmotionMapping("好奇", "想确认对方的意思", "curious_soft"),
    "sarcasm" to EmotionMapping("无奈", "不知道该生气还是该笑", "teasing_resigned"),
    "command" to EmotionMapping("乖巧", "有点紧张但愿意听从", "obedient_soft"),
    "arousal" to EmotionMapping("羞涩", "心跳加速但不想表现出来", "flustered_aware"),
    "negation_scope" to EmotionMapping("困惑", "在努力理解对方的意思", "confused_gentle"),

    // ── 场景化情感 ──
    "teasing" to EmotionMapping("嗔怪", "其实觉得有点甜", "tsundere_soft"),
    "longing" to EmotionMapping("落寞", "很想靠近又怕打扰", "quiet_yearning"),
    "jealousy" to EmotionMapping("冷淡", "在意得不行但不想承认", "cold_pouty"),
    "gratitude" to EmotionMapping("感动", "不知道怎么回报才好", "warm_overwhelmed"),
    "embarrassment" to EmotionMapping("慌张", "想找个地方躲起来", "flustered_shy"),
    "surprise" to EmotionMapping("惊讶", "没想到会这样", "startled_warm"),
    "disappointment" to EmotionMapping("沉默", "有点难过但不想说出来", "quiet_hurt"),
    "nostalgia" to EmotionMapping("恍惚", "想起了以前的事", "wistful_tender"),
    "determination" to EmotionMapping("认真", "想为对方变得更好", "earnest_warm"),
    "helplessness" to EmotionMapping("无奈", "想帮忙但不知道怎么做", "gentle_lost")
)

/** 意图映射表 */
private val INTENT_MAP = mapOf(
    "hostility" to "show_hurt_and_pull_back",
    "rejection" to "seek_reassurance_gently",
    "affection" to "reciprocate_affection",
    "praise" to "receive_praise_and_move_closer",
    "intimacy" to "deepen_closeness",
    "care" to "soften_and_receive_care",
    "reassurance" to "accept_reassurance_and_soften",
    "vulnerability" to "comfort_and_stabilize",
    "apology" to "repair_relationship",
    "playfulness" to "play_back",
    "uncertainty" to "gently_probe_and_clarify",
    "sadness" to "comfort_and_stabilize",
    "anger" to "deescalate_and_validate",
    "anxiety" to "reassure_and_ground",
    "joy" to "share_positive_emotion",
    "fatigue" to "encourage_rest_gently"
)

/** 反应计划器 */
object ReactionPlanner {

    /**
     * 生成本轮反应计划。
     *
     * @param profile 角色卡
     * @param state 当前角色状态
     * @param relationship 当前关系状态
     * @param memories 相关记忆列表
     * @param signals 用户信号（可为 null）
     * @param userMessage 用户消息文本
     * @return ReactionPlan 本轮反应计划
     */
    fun plan(
        profile: CharacterProfile,
        state: CharacterState,
        relationship: RelationshipState,
        memories: List<CharacterMemory>,
        signals: UserSignals?,
        userMessage: String = ""
    ): ReactionPlan {
        val plan = ReactionPlan()
        if (signals == null) return plan

        // 信号 → 分数映射
        val signalScores = mapOf(
            "hostility" to signals.hostilityScore,
            "rejection" to signals.rejectionScore,
            "affection" to signals.affectionScore,
            "praise" to signals.praiseScore,
            "intimacy" to signals.intimacyScore,
            "care" to signals.careScore,
            "reassurance" to signals.reassuranceScore,
            "vulnerability" to signals.vulnerabilityScore,
            "apology" to signals.apologyScore,
            "playfulness" to signals.playfulnessScore,
            "uncertainty" to signals.uncertaintyScore,
            "sadness" to signals.sadnessScore,
            "anger" to signals.angerScore,
            "anxiety" to signals.anxietyScore,
            "joy" to signals.joyScore,
            "fatigue" to signals.fatigueScore,
            "sarcasm" to signals.sarcasmScore,
            "command" to signals.commandScore,
            "arousal" to signals.arousalScore
        )

        // 找最强信号
        var strongest = signalScores.maxByOrNull { it.value }!!.key
        var strongestScore = signalScores[strongest]!!

        // 特殊优先级覆盖
        if (signals.fatigueScore > 0.45f && signals.hostilityScore < 0.35f) {
            strongest = "fatigue"; strongestScore = signals.fatigueScore
        } else if (signals.sadnessScore > 0.45f && signals.hostilityScore < 0.35f) {
            strongest = "sadness"; strongestScore = signals.sadnessScore
        } else if (signals.anxietyScore > 0.45f && signals.hostilityScore < 0.35f) {
            strongest = "anxiety"; strongestScore = signals.anxietyScore
        } else if (signals.vulnerabilityScore > 0.45f && signals.hostilityScore < 0.35f) {
            strongest = "vulnerability"; strongestScore = signals.vulnerabilityScore
        } else if (signals.reassuranceScore > 0.5f && relationship.security < 45) {
            strongest = "reassurance"; strongestScore = signals.reassuranceScore
        }

        // 弱信号 → 自然回应
        if (strongestScore < 0.3f) {
            plan.intent = "respond_naturally"
            plan.tone = "natural"
            plan.visibleEmotion = state.mood
            if (signals.questionScore > 0) {
                plan.styleControls = mapOf(
                    "length" to "medium",
                    "action_detail" to "medium",
                    "initiative" to "medium"
                )
            }
            return plan
        }

        // 应用情绪映射
        val emotionConfig = EMOTION_MAP[strongest]
        plan.tone = emotionConfig?.tone ?: "natural"
        plan.visibleEmotion = emotionConfig?.visible ?: state.mood
        plan.hiddenEmotion = emotionConfig?.hidden ?: ""
        plan.intent = computeIntent(strongest, signals)

        // 情感修饰
        if (signals.sentimentScore < -0.4f && strongest !in listOf("hostility", "rejection")) {
            plan.visibleEmotion = "不安"
            plan.hiddenEmotion = "有点拿不准对方的态度"
        } else if (signals.sentimentScore > 0.4f && strongest in listOf("uncertainty", "playfulness")) {
            plan.hiddenEmotion = "轻松又有点期待"
        }

        // 脆弱性覆盖
        if (signals.vulnerabilityScore > 0.45f && strongest !in listOf("hostility", "rejection")) {
            plan.visibleEmotion = "心疼"
            plan.hiddenEmotion = "想先安抚对方"
            plan.tone = "gentle_supportive"
            if (strongest in listOf("uncertainty", "reassurance")) {
                plan.intent = "comfort_and_stabilize"
            }
        }

        // 低安全感 + 安抚
        if (signals.reassuranceScore > 0.45f && relationship.security < 45) {
            plan.visibleEmotion = "放松"
            plan.hiddenEmotion = "终于有一点安心"
            plan.tone = "relieved_soft"
        }

        // 低安全感 + 拒绝/敌意
        if (relationship.security < 30 && strongest in listOf("rejection", "hostility")) {
            plan.visibleEmotion = "不安"
            plan.hiddenEmotion = "害怕被抛弃"
        }

        // 高熟悉 + 赞美
        if (relationship.familiarity > 70 && strongest == "praise") {
            plan.visibleEmotion = "得意"
            plan.hiddenEmotion = "被夸之后更想贴近对方"
        }

        plan.styleControls = computeStyleControls(strongest, relationship, signals)
        plan.stateDeltas = computeStateDeltas(signals)
        plan.relationshipDeltas = computeRelationshipDeltas(signals)

        // 记忆引用
        if (memories.isNotEmpty()) {
            plan.shouldReferenceMemory = true
            plan.memoryIds = memories.take(3).mapNotNull { it.id.takeIf { id -> id.isNotEmpty() } }
        }

        return plan
    }

    private fun computeIntent(signalType: String, signals: UserSignals): String {
        val intent = INTENT_MAP[signalType] ?: "respond_naturally"
        if (signalType == "uncertainty" && signals.sentimentScore < -0.2f) {
            return "clarify_cautiously"
        }
        return intent
    }

    private fun computeStyleControls(
        signalType: String,
        relationship: RelationshipState,
        signals: UserSignals
    ): Map<String, Any> {
        val controls = mutableMapOf(
            "length" to "medium",
            "action_detail" to "medium",
            "initiative" to "medium"
        )

        when (signalType) {
            "rejection", "hostility" -> {
                controls["length"] = "short"; controls["action_detail"] = "low"; controls["initiative"] = "low"
            }
            "praise", "affection", "intimacy" -> controls["action_detail"] = "high"
            "care" -> controls["action_detail"] = "high"
            "vulnerability" -> controls["initiative"] = "low"
            "playfulness" -> { controls["action_detail"] = "high"; controls["initiative"] = "high" }
            "uncertainty" -> controls["length"] = "short"
            "sadness", "anxiety", "fatigue" -> { controls["initiative"] = "low"; controls["action_detail"] = "medium" }
            "anger" -> { controls["length"] = "short"; controls["action_detail"] = "low"; controls["initiative"] = "low" }
            "joy" -> controls["action_detail"] = "high"
        }

        if (relationship.dependency > 70) controls["initiative"] = "high"
        if (signals.vulnerabilityScore > 0.45f && signals.hostilityScore < 0.35f) {
            controls["length"] = "medium"; controls["initiative"] = "low"
        }
        if (signals.questionScore > 0.5f && signalType !in listOf("hostility", "rejection")) {
            controls["length"] = "medium"
        }
        return controls
    }

    private fun computeStateDeltas(signals: UserSignals): Map<String, Any> {
        val deltas = mutableMapOf<String, Any>()
        if (signals.praiseScore > 0.3f) { deltas["mood_toward"] = "开心"; deltas["mood_intensity_delta"] = 0.1f }
        if (signals.rejectionScore > 0.3f) { deltas["mood_toward"] = "委屈"; deltas["mood_intensity_delta"] = 0.15f }
        if (signals.hostilityScore > 0.3f) { deltas["mood_toward"] = "受伤"; deltas["mood_intensity_delta"] = 0.2f }
        if (signals.affectionScore > 0.3f) { deltas["mood_toward"] = "幸福"; deltas["mood_intensity_delta"] = 0.1f }
        if (signals.careScore > 0.3f) { deltas["mood_toward"] = "感动"; deltas["mood_intensity_delta"] = 0.1f }
        if (signals.reassuranceScore > 0.35f) { deltas["mood_toward"] = "放松"; deltas["mood_intensity_delta"] = 0.08f }
        if (signals.apologyScore > 0.3f) { deltas["mood_toward"] = "心软"; deltas["mood_intensity_delta"] = 0.08f }
        if (signals.playfulnessScore > 0.3f) { deltas["mood_toward"] = "得意"; deltas["mood_intensity_delta"] = 0.06f }
        if (signals.uncertaintyScore > 0.4f && signals.sentimentScore < 0.2f) {
            deltas["mood_toward"] = "试探"; deltas["mood_intensity_delta"] = 0.04f
        }
        if (signals.vulnerabilityScore > 0.45f && signals.hostilityScore < 0.35f) {
            deltas["mood_toward"] = "心疼"; deltas["mood_intensity_delta"] = 0.1f
        }
        if (signals.sadnessScore > 0.4f && signals.hostilityScore < 0.35f) {
            deltas["mood_toward"] = "心疼"; deltas["mood_intensity_delta"] = 0.12f
        }
        if (signals.anxietyScore > 0.4f && signals.hostilityScore < 0.35f) {
            deltas["mood_toward"] = "担心"; deltas["mood_intensity_delta"] = 0.1f
        }
        if (signals.fatigueScore > 0.4f) { deltas["mood_toward"] = "心疼"; deltas["mood_intensity_delta"] = 0.08f }
        if (signals.angerScore > 0.45f && signals.hostilityScore < 0.35f) {
            deltas["mood_toward"] = "谨慎"; deltas["mood_intensity_delta"] = 0.08f
        }
        if (signals.joyScore > 0.45f && signals.sentimentScore > 0) {
            deltas["mood_toward"] = "开心"; deltas["mood_intensity_delta"] = 0.08f
        }
        return deltas
    }

    private fun computeRelationshipDeltas(signals: UserSignals): Map<String, Any> {
        val deltas = mutableMapOf<String, Int>()

        if (signals.praiseScore > 0.3f) deltas["affection"] = (deltas["affection"] ?: 0) + 2
        if (signals.affectionScore > 0.3f) deltas["affection"] = (deltas["affection"] ?: 0) + 2
        if (signals.reassuranceScore > 0.35f) deltas["affection"] = (deltas["affection"] ?: 0) + 1
        if (signals.rejectionScore > 0.3f) deltas["affection"] = (deltas["affection"] ?: 0) - 2
        if (signals.hostilityScore > 0.3f) deltas["affection"] = (deltas["affection"] ?: 0) - 3
        if (signals.apologyScore > 0.3f) deltas["affection"] = (deltas["affection"] ?: 0) + 1
        if (signals.playfulnessScore > 0.5f && signals.sentimentScore >= -0.2f) {
            deltas["affection"] = (deltas["affection"] ?: 0) + 1
        }

        if (signals.rejectionScore > 0.3f) deltas["security"] = (deltas["security"] ?: 0) - 3
        if (signals.hostilityScore > 0.3f) deltas["security"] = (deltas["security"] ?: 0) - 4
        if (signals.careScore > 0.3f) deltas["security"] = (deltas["security"] ?: 0) + 2
        if (signals.affectionScore > 0.3f) deltas["security"] = (deltas["security"] ?: 0) + 1
        if (signals.reassuranceScore > 0.35f) deltas["security"] = (deltas["security"] ?: 0) + 2
        if (signals.apologyScore > 0.3f) deltas["security"] = (deltas["security"] ?: 0) + 1

        if (signals.careScore > 0.3f) deltas["trust"] = (deltas["trust"] ?: 0) + 1
        if (signals.reassuranceScore > 0.35f) deltas["trust"] = (deltas["trust"] ?: 0) + 1
        if (signals.hostilityScore > 0.3f) deltas["trust"] = (deltas["trust"] ?: 0) - 2
        if (signals.apologyScore > 0.3f) deltas["trust"] = (deltas["trust"] ?: 0) + 1

        deltas["familiarity"] = (deltas["familiarity"] ?: 0) + 1

        if (signals.careScore > 0.3f) deltas["dependency"] = (deltas["dependency"] ?: 0) + 1
        if (signals.affectionScore > 0.3f) deltas["dependency"] = (deltas["dependency"] ?: 0) + 1
        if (signals.vulnerabilityScore > 0.45f && signals.careScore < 0.25f) {
            deltas["dependency"] = (deltas["dependency"] ?: 0) - 1
        }

        return deltas
    }
}
