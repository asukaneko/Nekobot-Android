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

    /**
     * 构建离线剧情更新（对齐原仓库 build_offline_plot_update）。
     *
     * 当 plot_mode + plot_real_time_sync 开启且时间间隔达到 same_day_gap/days/long_absence 时，
     * 生成离线剧情推进提示，供 AIPipeline 注入 plot.real_time_sync PromptStack 项。
     */
    fun buildOfflinePlotUpdate(inp: ReviewInput, timeLevel: String, elapsedLabel: String): OfflinePlotUpdate {
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
                "elapsed_label" to "初次记录",
                "continuity_level" to "first_contact",
                "roleplay_hint" to "这是第一次可用的现实时间记录；不要假装已经知道之前离线时发生的事。"
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

        val elapsedSeconds = Duration.between(prevTime, currentTime).seconds.coerceAtLeast(0L)
        val elapsedLabel = elapsedLabel(elapsedSeconds)
        val level = continuityLevel(elapsedSeconds, hasPrevious = true)
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

    /** 计算连续性等级（对齐原仓库 _continuity_level） */
    private fun continuityLevel(seconds: Long, hasPrevious: Boolean = true): String {
        if (!hasPrevious) return "first_contact"
        return when {
            seconds < 1800 -> "continuous"      // < 30 分钟
            seconds < 21600 -> "short_gap"      // < 6 小时
            seconds < 86400 -> "same_day_gap"   // < 24 小时
            seconds < 604800 -> "days"          // < 7 天
            else -> "long_absence"
        }
    }

    /** 秒数 → 可读标签（对齐原仓库 _elapsed_label，不加"前"字） */
    private fun elapsedLabel(seconds: Long): String {
        if (seconds <= 0) return "刚刚"
        val minutes = seconds / 60
        if (minutes < 1) return "刚刚"
        if (minutes < 60) return "${minutes}分钟"
        val hours = minutes / 60
        if (hours < 24) return "${hours}小时"
        val days = hours / 24
        val remainingHours = hours % 24
        return if (remainingHours > 0) "${days}天${remainingHours}小时" else "${days}天"
    }

    /** 角色扮演提示（对齐原仓库 _roleplay_hint） */
    private fun roleplayHint(level: String, label: String): String = when (level) {
        "first_contact" -> "这是第一次可用的现实时间记录；不要假装已经知道之前离线时发生的事。"
        "continuous" -> "现实中几乎没有间隔；把这轮当作连续对话自然承接。"
        "short_gap" -> "现实中已经过去$label；可轻微承认时间流逝，但不要夸大成久别。"
        "same_day_gap" -> "现实中已经过去$label；角色可以像在同一天稍后再次见面一样回应。"
        "days" -> "现实中已经过去$label；角色应意识到隔了几天，可体现等待、生活延续或重新见面的感觉。"
        "long_absence" -> "现实中已经过去$label；角色应把这当作较长分别后的再次互动，但不要编造未经确认的具体经历。"
        else -> ""
    }

    /** 将时间上下文格式化为可注入 Prompt 的文本（对齐原仓库 format_real_time_prompt_context） */
    fun formatRealTimePromptContext(context: Map<String, Any>): String {
        if (context.isEmpty()) return ""
        val lines = mutableListOf<String>()
        lines.add("当前现实时间: ${context["current_time"] ?: ""}")
        val prev = (context["previous_turn_time"] as? String).orEmpty()
        if (prev.isNotEmpty()) lines.add("上次互动时间: $prev")
        lines.add("现实时间间隔: ${context["elapsed_label"] ?: ""}")
        lines.add("连续性等级: ${context["continuity_level"] ?: ""}")
        lines.add("角色扮演提示: ${context["roleplay_hint"] ?: ""}")
        lines.add("把现实时间流逝当作角色生活连续性的一部分；可以体现等待、日常延续、重新见面的感觉。")
        lines.add("不要编造未经用户确认的具体离线经历、事件或承诺。")
        return lines.joinToString("\n") { it }
    }

    /** 解析时间字符串 */
    private fun parseDateTime(value: String): LocalDateTime? {
        return try {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: Exception) {
            try {
                // 兼容带时区的 ISO 字符串（如 2026-06-20T10:00:00+08:00）
                val cleaned = value.replace("Z", "").substringBefore("+").substringBeforeLast("-")
                LocalDateTime.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } catch (e2: Exception) { null }
        }
    }

    // ---- 昼夜节律 ----

    /** 昼夜阶段（对齐原仓库 _circadian_phase，evening 18-21，night 22-23） */
    fun circadianPhase(hour: Int): String = when (hour) {
        in 0..5 -> "sleeping"
        in 6..8 -> "morning"
        in 9..11 -> "forenoon"
        in 12..13 -> "noon"
        in 14..17 -> "afternoon"
        in 18..21 -> "evening"
        else -> "night"  // 22-23
    }

    /** 昼夜标签（对齐原仓库 _circadian_label） */
    fun circadianLabel(phase: String): String = when (phase) {
        "sleeping" -> "深夜睡眠"
        "morning" -> "清晨起床"
        "forenoon" -> "上午时段"
        "noon" -> "午间休息"
        "afternoon" -> "下午时段"
        "evening" -> "傍晚晚间"
        "night" -> "深夜准备休息"
        else -> "日常"
    }

    /** 昼夜精力修正（对齐原仓库 _circadian_energy_modifier） */
    fun circadianEnergyModifier(phase: String): Int = when (phase) {
        "sleeping" -> -25
        "morning" -> -8
        "forenoon" -> 0
        "noon" -> -5
        "afternoon" -> 0
        "evening" -> -3
        "night" -> -15
        else -> 0
    }

    /** 每个时段的详细角色扮演提示（对齐原仓库 _circadian_roleplay_hint） */
    fun circadianRoleplayHint(phase: String, hour: Int): String = when (phase) {
        "sleeping" -> "现在是凌晨${hour}点，角色应当处于睡眠状态。若被消息吵醒，反应可以带迷糊、困倦、不情愿，甚至略带起床气；不要主动展开长对话，节奏应放慢。"
        "morning" -> "现在是清晨${hour}点，角色可能刚起床不久，可以体现晨间状态（刚醒、洗漱、吃早餐、规划今天的事），语气可以略带慵懒或清新。"
        "forenoon" -> "现在是上午${hour}点，角色通常处于工作/学习/日常事务中，回复节奏可以略紧凑，体现被打断或抽空回应的感觉。"
        "noon" -> "现在是午间${hour}点，角色可能在吃饭或午休，可以体现餐后困倦、午睡被打扰或边吃边聊的状态。"
        "afternoon" -> "现在是下午${hour}点，角色处于下午时段，可以是继续工作、小憩、喝茶、散步等，状态相对松弛。"
        "evening" -> "现在是晚间${hour}点，角色已结束主要日程，处于放松时段，可以体现晚餐、洗澡、看电视/看书、准备休息等生活气息。"
        "night" -> "现在是深夜${hour}点，角色应当正在准备睡觉或已经入睡，语气可以带倦意，互动节奏放缓，可体现'该睡了'的边界感。"
        else -> ""
    }

    /** 构建昼夜状态（含 roleplay_hint，对齐原仓库 build_circadian_state） */
    fun buildCircadianState(now: LocalDateTime = LocalDateTime.now()): Map<String, Any> {
        val phase = circadianPhase(now.hour)
        return mapOf(
            "phase" to phase,
            "hour" to now.hour,
            "label" to circadianLabel(phase),
            "roleplay_hint" to circadianRoleplayHint(phase, now.hour),
            "energy_modifier" to circadianEnergyModifier(phase),
            "current_time" to now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    /** 将昼夜节律格式化为可注入 Prompt 的文本（对齐原仓库 format_circadian_prompt） */
    fun formatCircadianPrompt(context: Map<String, Any>): String {
        if (context.isEmpty()) return ""
        val lines = mutableListOf<String>()
        lines.add("作息阶段: ${context["label"] ?: ""}（${context["hour"] ?: ""}点）")
        lines.add("扮演提示: ${context["roleplay_hint"] ?: ""}")
        val modifier = (context["energy_modifier"] as? Number)?.toInt() ?: 0
        if (modifier != 0) {
            val direction = if (modifier < 0) "下降" else "上升"
            lines.add("本轮精力修正: ${kotlin.math.abs(modifier)}（$direction）")
        }
        lines.add("把当前时段当作角色真实生活的背景；不要刻意强调时间，但要让反应自然带有此时段的状态。")
        return lines.joinToString("\n") { it }
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
