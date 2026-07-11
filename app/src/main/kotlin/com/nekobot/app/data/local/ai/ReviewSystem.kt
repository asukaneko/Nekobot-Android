package com.nekobot.app.data.local.ai

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * 对话后审查系统，对应原仓库 nbot/review/。
 *
 * 包含：ReviewInput/ReviewOutput 数据模型 + RuleReview 规则引擎 + ReviewPipeline 编排器 +
 * TimeContext 现实时间上下文 + SelfCorrection 自我纠错提示。
 *
 * 简化点：
 * - 不含 LLM 模式（rule_review 已覆盖核心功能）
 * - 不含事件总线（用回调替代）
 * - 不含 offline_plot 持久化缓存（简化为内存）
 */

// ============================================================================
// 数据模型
// ============================================================================

/** 8 维评分（0-1） */
data class ReviewScore(
    var characterFidelity: Float = 0f,
    var immersion: Float = 0f,
    var relationshipProgress: Float = 0f,
    var storyProgress: Float = 0f,
    var memoryValue: Float = 0f,
    var worldConsistency: Float = 0f,
    var userEngagement: Float = 0f,
    var risk: Float = 0f
)

/** 记忆条目 */
data class MemoryItem(
    val target: String = "user",  // user/character/world
    val memType: String = "long",  // short/long/relationship/event
    val title: String = "",
    val content: String = "",
    val importance: Float = 0.5f,
    val ttl: String? = null
)

/** 关系增量 */
data class RelationshipDelta(
    var affection: Int = 0,
    var trust: Int = 0,
    var familiarity: Int = 0,
    var dependency: Int = 0,
    var security: Int = 0,
    var jealousy: Int = 0,
    var reason: String = "",
    val source: String = "rule",
    val plotNodeId: String? = null,
    val conversationId: String = ""
)

/** 剧情更新 */
data class PlotUpdate(
    val shouldCreateNode: Boolean = false,
    val level: String = "normal",
    val summary: String = "",
    val title: String = ""
)

/** 世界书更新 */
data class WorldBookUpdate(
    val shouldUpdate: Boolean = false,
    val reason: String = "",
    val entryTitle: String = "",
    val entryContent: String = ""
)

/** 离线剧情更新 */
data class OfflinePlotUpdate(
    val shouldInject: Boolean = false,
    val level: String = "same_day",  // same_day/days/long_absence
    val elapsedLabel: String = "",
    val characterActivity: String = "",
    val worldChanges: List<String> = emptyList(),
    val summary: String = "",
    val promptText: String = ""
)

/** 审查输入 */
data class ReviewInput(
    val conversationId: String = "",
    val characterId: String = "",
    val userId: String = "",
    val groupId: String = "",
    val userMessage: String = "",
    val assistantMessage: String = "",
    val recentMessages: List<Map<String, String>> = emptyList(),
    val activePlotNode: PlotNode? = null,
    val selectedChoice: PlotChoice? = null,
    val relationshipState: RelationshipState? = null,
    val characterState: CharacterState? = null,
    val worldContext: String = "",
    val realTimeContext: Map<String, Any> = emptyMap(),
    val plotMode: Boolean = false,
    val plotRealTimeSync: Boolean = false,
    val toolCalls: List<Map<String, Any>> = emptyList()
)

/** 审查输出 */
data class ReviewOutput(
    val shouldWriteMemory: Boolean = false,
    val memoryItems: List<MemoryItem> = emptyList(),
    val relationshipDelta: RelationshipDelta = RelationshipDelta(),
    val plotUpdate: PlotUpdate = PlotUpdate(),
    val offlinePlotUpdate: OfflinePlotUpdate = OfflinePlotUpdate(),
    val worldBookUpdate: WorldBookUpdate = WorldBookUpdate(),
    val scores: ReviewScore = ReviewScore(),
    val source: String = "rule",  // rule/llm
    val skipped: Boolean = false
)

// ============================================================================
// 规则审查引擎
// ============================================================================

/** 信任提升关键词 */
private val TRUST_UP_KEYWORDS = listOf("谢谢", "感谢", "信任", "靠谱", "辛苦了")
/** 好感提升关键词 */
private val AFFECTION_UP_KEYWORDS = listOf("喜欢", "爱", "想你", "抱抱", "亲亲", "贴贴", "陪", "温暖")
/** 好感下降关键词 */
private val AFFECTION_DOWN_KEYWORDS = listOf("讨厌", "烦死", "滚", "闭嘴", "恶心", "不需要你")

/**
 * 规则审查引擎，对应原仓库 nbot/review/rule_review.py。
 *
 * 基于关键词和规则的关系变化/记忆写入/剧情更新判定。
 */
object RuleReview {

    /**
     * 执行规则审查。
     */
    fun runRuleReview(inp: ReviewInput): ReviewOutput {
        val relDelta = RelationshipDelta(conversationId = inp.conversationId)
        val scores = ReviewScore()
        val memoryItems = mutableListOf<MemoryItem>()

        // === 1. 关系变化 ===
        relDelta.familiarity = 1  // 默认熟悉度 +1

        val userMsg = inp.userMessage
        if (TRUST_UP_KEYWORDS.any { it in userMsg }) {
            relDelta.trust = 1
            relDelta.reason = "用户表达信任/感谢"
        }
        if (AFFECTION_UP_KEYWORDS.any { it in userMsg }) {
            relDelta.affection = 1
            relDelta.reason = if (relDelta.reason.isNotEmpty()) "${relDelta.reason}; 好感提升" else "好感提升"
        }
        if (AFFECTION_DOWN_KEYWORDS.any { it in userMsg }) {
            relDelta.affection = -1
            relDelta.reason = if (relDelta.reason.isNotEmpty()) "${relDelta.reason}; 好感下降" else "好感下降"
        }

        // choice level 影响
        val choiceLevel = inp.selectedChoice?.level ?: ""
        when (choiceLevel) {
            "important" -> {
                relDelta.affection += 1
                relDelta.trust += 1
            }
            "turning_point" -> {
                relDelta.affection += 2
                relDelta.trust += 1
            }
        }

        // 时间等级影响（离线剧情）
        val timeLevel = (inp.realTimeContext["continuity_level"] as? String) ?: ""
        if (timeLevel in listOf("days", "long_absence")) {
            relDelta.familiarity += 1
        }

        val totalDelta = relDelta.affection + relDelta.trust + relDelta.familiarity +
            relDelta.dependency + relDelta.security

        // === 2. 记忆价值评估 ===
        val memoryValue = calcMemoryValue(inp, choiceLevel)
        val storyProgress = calcStoryProgress(choiceLevel)
        val engagement = calcEngagement(userMsg)

        // === 3. 记忆写入判定 ===
        val shouldWriteMemory = memoryValue >= 0.65f ||
            choiceLevel in listOf("important", "turning_point", "ending") ||
            totalDelta >= 3 ||
            timeLevel in listOf("days", "long_absence")

        if (shouldWriteMemory) {
            val memType = when (choiceLevel) {
                "turning_point", "ending" -> "event"
                "important" -> "relationship"
                else -> "short"
            }
            val ttl = when {
                memoryValue >= 0.8f -> null  // 长期
                memoryValue >= 0.5f -> "7d"
                else -> "1d"
            }
            memoryItems.add(MemoryItem(
                target = "user",
                memType = memType,
                title = extractMemoryTitle(inp),
                content = extractMemoryContent(inp, timeLevel),
                importance = memoryValue,
                ttl = ttl
            ))
        }

        // === 4. 剧情更新 ===
        val plotUpdate = if (choiceLevel in listOf("important", "turning_point", "ending")) {
            PlotUpdate(
                shouldCreateNode = true,
                level = choiceLevel,
                summary = inp.assistantMessage.take(200),
                title = inp.selectedChoice?.text?.take(80) ?: "剧情节点"
            )
        } else PlotUpdate()

        // === 5. 离线剧情 ===
        val offlinePlot = if (inp.plotMode && inp.plotRealTimeSync && timeLevel in listOf("same_day_gap", "days", "long_absence")) {
            buildOfflinePlotUpdate(inp, timeLevel, (inp.realTimeContext["elapsed_label"] as? String) ?: "")
        } else OfflinePlotUpdate()

        // === 6. 世界书更新 ===
        val worldBookUpdate = if (choiceLevel in listOf("turning_point", "ending")) {
            WorldBookUpdate(
                shouldUpdate = true,
                reason = "剧情转折/结局触发",
                entryTitle = inp.selectedChoice?.text?.take(50) ?: "剧情事件",
                entryContent = inp.assistantMessage.take(500)
            )
        } else WorldBookUpdate()

        // === 7. 评分 ===
        scores.memoryValue = memoryValue
        scores.storyProgress = storyProgress
        scores.relationshipProgress = (totalDelta.toFloat() / 5f).coerceIn(0f, 1f)
        scores.userEngagement = engagement
        scores.risk = 0f
        // character_fidelity/immersion/world_consistency 从 AutoState 缓存读取（调用方设置）

        // === 8. skipped 判定 ===
        val skipped = choiceLevel.isEmpty() && memoryValue < 0.3f &&
            timeLevel !in listOf("days", "long_absence") &&
            !offlinePlot.shouldInject && !shouldWriteMemory

        return ReviewOutput(
            shouldWriteMemory = shouldWriteMemory,
            memoryItems = memoryItems,
            relationshipDelta = relDelta,
            plotUpdate = plotUpdate,
            offlinePlotUpdate = offlinePlot,
            worldBookUpdate = worldBookUpdate,
            scores = scores,
            source = "rule",
            skipped = skipped
        )
    }

    private fun calcMemoryValue(inp: ReviewInput, choiceLevel: String): Float {
        var value = 0.2f
        if (choiceLevel == "important") value = 0.6f
        if (choiceLevel == "turning_point") value = 0.9f
        if (choiceLevel == "ending") value = 1.0f
        // 用户消息长度影响
        val msgLen = inp.userMessage.length
        if (msgLen > 100) value += 0.1f
        if (msgLen > 300) value += 0.1f
        return value.coerceIn(0f, 1f)
    }

    private fun calcStoryProgress(choiceLevel: String): Float = when (choiceLevel) {
        "normal" -> 0.2f
        "important" -> 0.6f
        "turning_point" -> 0.9f
        "ending" -> 1.0f
        else -> 0.1f
    }

    private fun calcEngagement(userMsg: String): Float {
        val len = userMsg.length
        return when {
            len < 10 -> 0.2f
            len < 50 -> 0.4f
            len < 200 -> 0.6f
            len < 500 -> 0.8f
            else -> 1.0f
        }
    }

    private fun extractMemoryTitle(inp: ReviewInput): String {
        val choiceText = inp.selectedChoice?.text ?: ""
        if (choiceText.isNotEmpty()) return choiceText.take(50)
        return inp.userMessage.take(50).replace("\n", " ")
    }

    private fun extractMemoryContent(inp: ReviewInput, timeLevel: String): String {
        val parts = mutableListOf<String>()
        if (timeLevel in listOf("days", "long_absence")) {
            parts.add("[时间间隔: $timeLevel]")
        }
        parts.add("用户: ${inp.userMessage.take(500)}")
        parts.add("角色: ${inp.assistantMessage.take(500)}")
        return parts.joinToString("\n")
    }

    private fun buildOfflinePlotUpdate(inp: ReviewInput, timeLevel: String, elapsedLabel: String): OfflinePlotUpdate {
        val (activity, worldChanges) = when (timeLevel) {
            "same_day_gap" -> "你稍作休息，继续之前的活动。" to listOf("时间推进到下午")
            "days" -> "你度过了平静的几天，处理了一些日常事务。" to listOf("时间推进数日", "日常事务推进")
            "long_absence" -> "你经历了一段较长的独处时光，有了新的感悟。" to listOf("时间推进数周", "角色内心成长")
            else -> "" to emptyList()
        }

        val promptText = buildString {
            append("【离线剧情推进】\n")
            append("经过时间: $elapsedLabel\n")
            if (activity.isNotEmpty()) append("角色活动: $activity\n")
            if (worldChanges.isNotEmpty()) {
                append("世界变化:\n")
                worldChanges.forEach { append("- $it\n") }
            }
            append("摘要: ${inp.assistantMessage.take(200)}")
        }

        return OfflinePlotUpdate(
            shouldInject = true,
            level = timeLevel,
            elapsedLabel = elapsedLabel,
            characterActivity = activity,
            worldChanges = worldChanges,
            summary = inp.assistantMessage.take(200),
            promptText = promptText
        )
    }
}

// ============================================================================
// 审查管道编排器
// ============================================================================

/**
 * 审查管道编排器，对应原仓库 nbot/review/pipeline.py。
 */
class ReviewPipeline {

    /**
     * 执行审查。
     *
     * @param inp 审查输入
     * @return 审查输出
     */
    fun run(inp: ReviewInput): ReviewOutput {
        return try {
            RuleReview.runRuleReview(inp)
        } catch (e: Exception) {
            ReviewOutput(skipped = true, source = "rule")
        }
    }
}

// ============================================================================
// 全局单例
// ============================================================================

private val globalReviewPipeline = ReviewPipeline()

fun getGlobalReviewPipeline(): ReviewPipeline = globalReviewPipeline

// ============================================================================
// 现实时间上下文
// ============================================================================

/**
 * 现实时间上下文，对应原仓库 nbot/review/time_context.py。
 *
 * 计算现实时间连续性等级、昼夜节律、当前活动推断。
 */
object TimeContext {

    /**
     * 构建现实时间上下文。
     *
     * @param previousTurnTime 上次互动时间（ISO 字符串），可为空
     * @param currentTime 当前时间，默认 now
     * @return 上下文字典
     */
    fun buildRealTimeContext(
        previousTurnTime: String? = null,
        currentTime: LocalDateTime = LocalDateTime.now()
    ): Map<String, Any> {
        val nowIso = currentTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        if (previousTurnTime.isNullOrBlank()) {
            return mapOf(
                "current_time" to nowIso,
                "previous_turn_time" to "",
                "elapsed_seconds" to 0L,
                "elapsed_label" to "首次对话",
                "continuity_level" to "first_contact",
                "roleplay_hint" to "这是第一次对话，请自然地开始互动。"
            )
        }

        val prevTime = parseDateTime(previousTurnTime) ?: return mapOf(
            "current_time" to nowIso,
            "previous_turn_time" to previousTurnTime,
            "elapsed_seconds" to 0L,
            "elapsed_label" to "",
            "continuity_level" to "continuous",
            "roleplay_hint" to ""
        )

        val elapsedSeconds = Duration.between(prevTime, currentTime).seconds
        val elapsedLabel = elapsedLabel(elapsedSeconds)
        val level = continuityLevel(elapsedSeconds)
        val hint = roleplayHint(level, elapsedLabel)

        return mapOf(
            "current_time" to nowIso,
            "previous_turn_time" to previousTurnTime,
            "elapsed_seconds" to elapsedSeconds,
            "elapsed_label" to elapsedLabel,
            "continuity_level" to level,
            "roleplay_hint" to hint
        )
    }

    /** 计算连续性等级 */
    private fun continuityLevel(seconds: Long): String = when {
        seconds < 1800 -> "continuous"      // 30 分钟内
        seconds < 21600 -> "short_gap"      // 6 小时内
        seconds < 86400 -> "same_day_gap"   // 24 小时内
        seconds < 604800 -> "days"          // 7 天内
        else -> "long_absence"
    }

    /** 秒数 → 可读标签 */
    private fun elapsedLabel(seconds: Long): String = when {
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60}分钟前"
        seconds < 86400 -> "${seconds / 3600}小时前"
        seconds < 604800 -> "${seconds / 86400}天${(seconds % 86400) / 3600}小时前"
        else -> "${seconds / 86400}天前"
    }

    /** 角色扮演提示 */
    private fun roleplayHint(level: String, label: String): String = when (level) {
        "first_contact" -> "这是第一次对话，请自然地开始互动。"
        "continuous" -> "对话连续进行中，保持当前节奏。"
        "short_gap" -> "距上次对话已过去$label，可以自然衔接之前的话题。"
        "same_day_gap" -> "今天已有过对话，距上次已过去$label，可以提及今天的互动。"
        "days" -> "已经过去${label}了，角色应该表现出对这段时间的感知。"
        "long_absence" -> "已经过去${label}了，角色应该表现出想念和对近况的关心。"
        else -> ""
    }

    /** 解析时间字符串 */
    private fun parseDateTime(value: String): LocalDateTime? {
        return try {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(value.replace("Z", ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } catch (e2: Exception) { null }
        }
    }

    // ---- 昼夜节律 ----

    /** 昼夜阶段 */
    fun circadianPhase(hour: Int): String = when (hour) {
        in 0..5 -> "sleeping"
        in 6..8 -> "morning"
        in 9..11 -> "forenoon"
        in 12..13 -> "noon"
        in 14..17 -> "afternoon"
        in 18..20 -> "evening"
        else -> "night"
    }

    /** 昼夜标签 */
    fun circadianLabel(phase: String): String = when (phase) {
        "sleeping" -> "深夜睡眠时间"
        "morning" -> "清晨"
        "forenoon" -> "上午"
        "noon" -> "中午"
        "afternoon" -> "下午"
        "evening" -> "傍晚"
        "night" -> "夜晚"
        else -> ""
    }

    /** 昼夜精力修正 */
    fun circadianEnergyModifier(phase: String): Int = when (phase) {
        "sleeping" -> -25
        "morning" -> -8
        "noon" -> -5
        "afternoon" -> 0
        "evening" -> -3
        "night" -> -15
        else -> 0
    }

    /** 构建昼夜状态 */
    fun buildCircadianState(now: LocalDateTime = LocalDateTime.now()): Map<String, Any> {
        val phase = circadianPhase(now.hour)
        return mapOf(
            "phase" to phase,
            "hour" to now.hour,
            "label" to circadianLabel(phase),
            "energy_modifier" to circadianEnergyModifier(phase),
            "current_time" to now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    // ---- 当前活动推断 ----

    /** 每个阶段的活动池 */
    private val ACTIVITY_POOL = mapOf(
        "sleeping" to listOf("睡觉", "休息", "做梦"),
        "morning" to listOf("刚醒来", "洗漱", "吃早餐", "整理床铺"),
        "forenoon" to listOf("看书", "整理物品", "思考", "练习"),
        "noon" to listOf("吃午饭", "小憩", "晒太阳"),
        "afternoon" to listOf("散步", "聊天", "喝茶", "处理事务"),
        "evening" to listOf("准备晚餐", "看书", "整理一天"),
        "night" to listOf("准备休息", "回想今天", "安静地待着")
    )

    /** 推断当前活动（同一小时内稳定） */
    fun inferCurrentActivity(now: LocalDateTime = LocalDateTime.now()): String {
        val phase = circadianPhase(now.hour)
        val pool = ACTIVITY_POOL[phase] ?: return "待着"
        // 用日期+小时做种子，确保同一小时内稳定
        val seed = (now.year * 10000 + now.monthValue * 100 + now.dayOfMonth) * 100 + now.hour
        return pool[Random(seed).nextInt(pool.size)]
    }
}

// ============================================================================
// 自我纠错提示
// ============================================================================

/**
 * 自我纠错提示，对应原仓库 nbot/review/self_correction.py。
 *
 * 根据质量评分生成下一轮的修正提示，注入到 PromptStack。
 */
object SelfCorrection {
    private val THRESHOLDS = mapOf(
        "character_fidelity" to 0.6f,
        "immersion" to 0.5f,
        "world_consistency" to 0.6f
    )
    private const val RISK_THRESHOLD = 0.6f

    private val HINTS = mapOf(
        "character_fidelity" to "请注意保持角色设定的一致性，确保回复符合角色性格和背景。",
        "immersion" to "请提升沉浸感，让回复更自然、更有代入感。",
        "world_consistency" to "请注意世界观一致性，确保回复不与设定矛盾。",
        "risk" to "⚠️ 检测到潜在风险，请注意回复内容的适当性。"
    )

    private val hintCache = mutableMapOf<String, String>()

    /**
     * 根据评分生成修正提示。
     */
    fun buildCorrectionHint(scores: ReviewScore): String {
        val hints = mutableListOf<String>()

        for ((field, threshold) in THRESHOLDS) {
            val score = when (field) {
                "character_fidelity" -> scores.characterFidelity
                "immersion" -> scores.immersion
                "world_consistency" -> scores.worldConsistency
                else -> 0f
            }
            if (score in 0.001f..threshold) {
                HINTS[field]?.let { hints.add(it) }
            }
        }

        if (scores.risk >= RISK_THRESHOLD) {
            HINTS["risk"]?.let { hints.add(it) }
        }

        return if (hints.isEmpty()) "" else "【自我纠错提示】\n${hints.joinToString("\n")}"
    }

    /** 缓存下一轮提示 */
    fun storeHint(characterId: String, userId: String, conversationId: String, hint: String) {
        val key = "$characterId:$userId:$conversationId"
        if (hint.isBlank()) {
            hintCache.remove(key)
            return
        }
        hintCache[key] = hint
        if (hintCache.size > 200) {
            val firstKey = hintCache.keys.first()
            hintCache.remove(firstKey)
        }
    }

    /** 读取并清除提示 */
    fun consumeHint(characterId: String, userId: String, conversationId: String): String? {
        val key = "$characterId:$userId:$conversationId"
        return hintCache.remove(key)
    }
}

// ============================================================================
// 离线剧情缓存
// ============================================================================

/**
 * 离线剧情更新缓存，对应原仓库 nbot/review/offline_plot.py。
 *
 * 一次性缓存，消费即清除。
 */
object OfflinePlotCache {
    private val cache = mutableMapOf<String, OfflinePlotUpdate>()

    private fun key(characterId: String, userId: String, conversationId: String) =
        "$characterId:$userId:$conversationId"

    fun storeUpdate(characterId: String, userId: String, conversationId: String, update: OfflinePlotUpdate?) {
        val k = key(characterId, userId, conversationId)
        if (update == null || !update.shouldInject || update.promptText.isBlank()) {
            cache.remove(k)
            return
        }
        cache[k] = update
        if (cache.size > 200) {
            val firstKey = cache.keys.first()
            cache.remove(firstKey)
        }
    }

    fun consumeUpdate(characterId: String, userId: String, conversationId: String): OfflinePlotUpdate? {
        val k = key(characterId, userId, conversationId)
        return cache.remove(k)
    }
}
