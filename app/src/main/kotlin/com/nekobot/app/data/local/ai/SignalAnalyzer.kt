package com.nekobot.app.data.local.ai

/**
 * 用户信号分析器，对应原仓库 nbot/character/policies.py。
 *
 * 完全本地化、确定性逻辑：关键词规则 + 强度修饰词 + 否定范围检测 +
 * 状态上下文调整 + 关系上下文调整。
 * 产出 21 个归一化分数（0-1）的 UserSignals。
 */

/** 归一化用户信号（21 个维度，0-1） */
data class UserSignals(
    var praiseScore: Float = 0f,
    var rejectionScore: Float = 0f,
    var affectionScore: Float = 0f,
    var hostilityScore: Float = 0f,
    var careScore: Float = 0f,
    var intimacyScore: Float = 0f,
    var reassuranceScore: Float = 0f,
    var vulnerabilityScore: Float = 0f,
    var questionScore: Float = 0f,
    var commandScore: Float = 0f,
    var sentimentScore: Float = 0f,      // -1 ~ 1
    var arousalScore: Float = 0f,
    var uncertaintyScore: Float = 0f,
    var apologyScore: Float = 0f,
    var playfulnessScore: Float = 0f,
    var sadnessScore: Float = 0f,
    var angerScore: Float = 0f,
    var anxietyScore: Float = 0f,
    var joyScore: Float = 0f,
    var fatigueScore: Float = 0f,
    var sarcasmScore: Float = 0f,
    var negationScopeScore: Float = 0f,
    val detectedKeywords: MutableList<String> = mutableListOf()
) {
    fun toDict(): Map<String, Any> = buildMap {
        put("praise_score", (praiseScore * 100).roundToInt() / 100f)
        put("rejection_score", (rejectionScore * 100).roundToInt() / 100f)
        put("affection_score", (affectionScore * 100).roundToInt() / 100f)
        put("hostility_score", (hostilityScore * 100).roundToInt() / 100f)
        put("care_score", (careScore * 100).roundToInt() / 100f)
        put("intimacy_score", (intimacyScore * 100).roundToInt() / 100f)
        put("reassurance_score", (reassuranceScore * 100).roundToInt() / 100f)
        put("vulnerability_score", (vulnerabilityScore * 100).roundToInt() / 100f)
        put("question_score", (questionScore * 100).roundToInt() / 100f)
        put("command_score", (commandScore * 100).roundToInt() / 100f)
        put("sentiment_score", (sentimentScore * 100).roundToInt() / 100f)
        put("arousal_score", (arousalScore * 100).roundToInt() / 100f)
        put("uncertainty_score", (uncertaintyScore * 100).roundToInt() / 100f)
        put("apology_score", (apologyScore * 100).roundToInt() / 100f)
        put("playfulness_score", (playfulnessScore * 100).roundToInt() / 100f)
        put("sadness_score", (sadnessScore * 100).roundToInt() / 100f)
        put("anger_score", (angerScore * 100).roundToInt() / 100f)
        put("anxiety_score", (anxietyScore * 100).roundToInt() / 100f)
        put("joy_score", (joyScore * 100).roundToInt() / 100f)
        put("fatigue_score", (fatigueScore * 100).roundToInt() / 100f)
        put("sarcasm_score", (sarcasmScore * 100).roundToInt() / 100f)
        put("negation_scope_score", (negationScopeScore * 100).roundToInt() / 100f)
        put("detected_keywords", detectedKeywords.toList())
    }
}

private fun Float.roundToInt() = kotlin.math.round(this)

// ==================== 关键词规则 ====================

private data class KeywordRule(val keywords: List<String>, val score: Float)

private val KEYWORD_RULES = mapOf(
    "praise" to KeywordRule(listOf("可爱", "好棒", "厉害", "优秀", "真好", "最棒", "最喜欢", "爱你", "真棒", "靠谱", "聪明", "懂我", "谢谢你", "感谢", "辛苦你了"), 0.6f),
    "rejection" to KeywordRule(listOf("别烦", "讨厌", "走开", "滚", "不要你", "离我远点", "别管我", "别理我", "闭嘴", "烦死了"), 0.7f),
    "affection" to KeywordRule(listOf("摸摸", "抱抱", "亲亲", "喜欢你", "想你", "爱你", "贴贴", "牵手", "在一起"), 0.7f),
    "hostility" to KeywordRule(listOf("恨你", "去死", "废物", "垃圾", "蠢", "笨蛋", "恶心"), 0.8f),
    "care" to KeywordRule(listOf("你还好吗", "辛苦了", "累不累", "注意休息", "别太累", "关心", "担心你", "照顾好自己"), 0.5f),
    "intimacy" to KeywordRule(listOf("晚安", "早安", "想你了", "陪我", "一起", "永远", "一直", "不会离开"), 0.5f),
    "reassurance" to KeywordRule(listOf("没事", "别怕", "我在", "不会离开", "我陪你", "别担心", "慢慢来", "没关系"), 0.55f),
    "vulnerability" to KeywordRule(listOf("难过", "害怕", "委屈", "不安", "好累", "好难受", "心情不好", "没安全感"), 0.45f)
)

private val INTENSIFIERS = listOf("非常", "超级", "特别", "真的", "好", "太", "最", "超", "绝对")
private val DOWNTONERS = listOf("有点", "稍微", "可能", "也许", "大概", "一点", "好像")
private val SOFTENERS = listOf("请", "拜托", "可以吗", "好吗", "辛苦你", "麻烦你", "能不能")
private val APOLOGY_KEYWORDS = listOf("对不起", "抱歉", "不好意思", "我错了", "别生气", "原谅我")
private val UNCERTAINTY_KEYWORDS = listOf("吗", "呢", "是不是", "可以吗", "行不行", "能不能", "也许", "可能", "会不会")
private val PLAYFUL_KEYWORDS = listOf("哈哈", "嘿嘿", "hhh", "233", "逗你", "开玩笑", "略略", "哼哼", "笑死", "乐")
private val SADNESS_KEYWORDS = listOf("难过", "伤心", "想哭", "哭了", "崩溃", "失落", "委屈", "心酸", "撑不住", "没人懂")
private val ANGER_KEYWORDS = listOf("生气", "火大", "烦躁", "气死", "受不了", "离谱", "无语", "讨厌死", "真服了")
private val ANXIETY_KEYWORDS = listOf("焦虑", "紧张", "害怕", "慌", "不安", "担心", "怕", "怎么办", "完蛋", "糟了")
private val JOY_KEYWORDS = listOf("开心", "高兴", "快乐", "舒服", "安心", "期待", "喜欢", "太好了", "好耶")
private val FATIGUE_KEYWORDS = listOf("累", "困", "疲惫", "没力气", "不想动", "熬夜", "撑不住", "倦", "麻了")
private val NEGATION_MARKERS = listOf("不", "没", "没有", "别", "不是", "并不", "不太", "不要")
private val SARCASM_MARKERS = listOf("呵呵", "啊对对对", "真行", "可真", "你可真", "也是醉了", "笑死", "6", "行吧")
private val COMMAND_PATTERNS = listOf("帮我", "给我", "去做", "快点", "马上", "立刻", "现在就")
private val REST_CARE_KEYWORDS = listOf("休息", "睡觉", "补觉", "放松", "吃饭", "喝水", "别太累", "歇一会")

private val POSITIVE_FIELDS = listOf("praiseScore", "affectionScore", "careScore", "intimacyScore", "reassuranceScore")
private val NEGATIVE_FIELDS = listOf("rejectionScore", "hostilityScore")

// ==================== 辅助函数 ====================

private fun clampScore(value: Float): Float = value.coerceIn(0f, 1f)

private fun containsAny(text: String, keywords: List<String>): List<String> {
    val textLower = text.lowercase()
    return keywords.filter { it in textLower }
}

private fun boostIf(value: Float, condition: Boolean, amount: Float): Float =
    if (condition) clampScore(value + amount) else value

private fun scoreKeywords(text: String, keywords: List<String>, base: Float = 0.22f, perHit: Float = 0.12f): Float {
    val hits = containsAny(text, keywords)
    if (hits.isEmpty()) return 0f
    return clampScore(base + hits.size * perHit)
}

private fun hasNegatedKeyword(text: String, keyword: String, window: Int = 4): Boolean {
    val regex = Regex(Regex.escape(keyword))
    for (match in regex.findAll(text)) {
        val start = (match.range.first - window).coerceAtLeast(0)
        val prefix = text.substring(start, match.range.first)
        if (NEGATION_MARKERS.any { it in prefix }) return true
    }
    return false
}

// ==================== 信号分析器 ====================

/** 信号分析器：将用户消息分析为 21 个维度的归一化信号 */
object SignalAnalyzer {

    /**
     * 分析用户消息，生成本地信号。
     *
     * @param userMessage 用户消息文本
     * @param state 当前角色状态（用于上下文调整）
     * @param relationship 当前关系状态（用于上下文调整）
     * @return UserSignals 21 维度信号
     */
    fun analyze(
        userMessage: String,
        state: CharacterState? = null,
        relationship: RelationshipState? = null
    ): UserSignals {
        val signals = UserSignals()
        if (userMessage.isBlank()) return signals

        // 强度修饰词
        var intensityMultiplier = 1f
        val matchedIntensifiers = containsAny(userMessage, INTENSIFIERS)
        val matchedDowntoners = containsAny(userMessage, DOWNTONERS)
        val matchedSofteners = containsAny(userMessage, SOFTENERS)

        if (matchedIntensifiers.isNotEmpty()) {
            intensityMultiplier += minOf(0.35f, matchedIntensifiers.size * 0.08f)
            signals.detectedKeywords.addAll(matchedIntensifiers)
        }
        if (matchedDowntoners.isNotEmpty()) {
            intensityMultiplier -= minOf(0.25f, matchedDowntoners.size * 0.06f)
            signals.detectedKeywords.addAll(matchedDowntoners)
        }
        if (matchedSofteners.isNotEmpty()) {
            signals.detectedKeywords.addAll(matchedSofteners)
        }

        // 关键词规则匹配
        for ((category, rule) in KEYWORD_RULES) {
            val matched = containsAny(userMessage, rule.keywords)
            if (matched.isEmpty()) continue

            val score = clampScore((rule.score + matched.size * 0.1f) * intensityMultiplier)
            signals.detectedKeywords.addAll(matched)

            when (category) {
                "praise" -> signals.praiseScore = score
                "rejection" -> signals.rejectionScore = score
                "affection" -> signals.affectionScore = score
                "hostility" -> signals.hostilityScore = score
                "care" -> signals.careScore = score
                "intimacy" -> signals.intimacyScore = score
                "reassurance" -> signals.reassuranceScore = score
                "vulnerability" -> signals.vulnerabilityScore = score
            }
        }

        // 疑问句
        val questionMarks = userMessage.count { it == '?' || it == '？' }
        if (questionMarks > 0 || "吗" in userMessage || "呢" in userMessage) {
            signals.questionScore = clampScore(
                0.25f + questionMarks * 0.12f +
                (if ("吗" in userMessage || "呢" in userMessage) 0.18f else 0f)
            )
        }

        // 道歉
        val matchedApologies = containsAny(userMessage, APOLOGY_KEYWORDS)
        if (matchedApologies.isNotEmpty()) {
            signals.apologyScore = clampScore(0.45f + matchedApologies.size * 0.12f)
            signals.careScore = maxOf(signals.careScore, signals.apologyScore * 0.6f)
            signals.detectedKeywords.addAll(matchedApologies)
        }

        // 不确定
        val matchedUncertainty = containsAny(userMessage, UNCERTAINTY_KEYWORDS)
        if (matchedUncertainty.isNotEmpty() || signals.questionScore > 0) {
            signals.uncertaintyScore = clampScore(
                0.2f + matchedUncertainty.size * 0.08f + signals.questionScore * 0.35f
            )
            signals.detectedKeywords.addAll(matchedUncertainty)
        }

        // 俏皮
        val matchedPlayful = containsAny(userMessage, PLAYFUL_KEYWORDS)
        if (matchedPlayful.isNotEmpty()) {
            signals.playfulnessScore = clampScore(0.3f + matchedPlayful.size * 0.14f)
            signals.detectedKeywords.addAll(matchedPlayful)
        }

        // 情感词典
        applyAffectLexicons(signals, userMessage, intensityMultiplier)

        // 命令
        val commandHits = COMMAND_PATTERNS.filter { it in userMessage }
        if (commandHits.isNotEmpty()) {
            val rawCommand = 0.35f + commandHits.size * 0.12f
            val softenRatio = minOf(0.25f, matchedSofteners.size * 0.08f)
            signals.commandScore = clampScore(rawCommand - softenRatio)
            signals.detectedKeywords.addAll(commandHits)
        }

        // 唤醒度
        val exclamationCount = userMessage.count { it == '!' || it == '！' }
        val repeatedMarkCount = listOf("!!", "！！", "??", "？？").sumOf { userMessage.windowed(it.length, 1, partialWindows = false).count { w -> w == it } }
        signals.arousalScore = clampScore(
            maxOf(
                signals.praiseScore, signals.rejectionScore, signals.affectionScore,
                signals.hostilityScore, signals.careScore, signals.intimacyScore,
                signals.reassuranceScore, signals.vulnerabilityScore
            ) + minOf(0.25f, exclamationCount * 0.05f + repeatedMarkCount * 0.08f)
        )

        // 状态/关系上下文调整
        applyStateContext(signals, userMessage, state)
        applyRelationshipContext(signals, relationship)
        softenOrDisambiguate(signals, userMessage, matchedSofteners)

        // 情感总分
        var positiveScore = maxOf(
            signals.praiseScore, signals.affectionScore, signals.careScore,
            signals.intimacyScore, signals.reassuranceScore
        )
        positiveScore = maxOf(positiveScore, signals.joyScore)
        var negativeScore = maxOf(signals.rejectionScore, signals.hostilityScore)
        negativeScore = maxOf(
            negativeScore,
            signals.sadnessScore * 0.75f,
            signals.anxietyScore * 0.6f,
            signals.angerScore * 0.85f,
            signals.fatigueScore * 0.45f
        )
        if (signals.sarcasmScore > 0.3f && positiveScore > negativeScore) {
            positiveScore *= 0.75f
            negativeScore = maxOf(negativeScore, signals.sarcasmScore * 0.45f)
        }
        signals.sentimentScore = (positiveScore - negativeScore).coerceIn(-1f, 1f)

        return signals
    }

    private fun applyAffectLexicons(signals: UserSignals, userMessage: String, intensityMultiplier: Float) {
        val groups = listOf(
            "sadness" to SADNESS_KEYWORDS,
            "anger" to ANGER_KEYWORDS,
            "anxiety" to ANXIETY_KEYWORDS,
            "joy" to JOY_KEYWORDS,
            "fatigue" to FATIGUE_KEYWORDS
        )
        for ((category, keywords) in groups) {
            val hits = containsAny(userMessage, keywords)
            val validHits = hits.filter { !hasNegatedKeyword(userMessage, it) }
            if (hits.isNotEmpty() && validHits.size < hits.size) {
                signals.negationScopeScore = clampScore(signals.negationScopeScore + 0.12f)
            }
            if (validHits.isNotEmpty()) {
                val score = clampScore((0.22f + validHits.size * 0.13f) * intensityMultiplier)
                when (category) {
                    "sadness" -> signals.sadnessScore = score
                    "anger" -> signals.angerScore = score
                    "anxiety" -> signals.anxietyScore = score
                    "joy" -> signals.joyScore = score
                    "fatigue" -> signals.fatigueScore = score
                }
                signals.detectedKeywords.addAll(validHits)
            }
        }

        // 讽刺
        val sarcasmHits = containsAny(userMessage, SARCASM_MARKERS)
        if (sarcasmHits.isNotEmpty()) {
            signals.sarcasmScore = clampScore(0.2f + sarcasmHits.size * 0.14f)
            signals.detectedKeywords.addAll(sarcasmHits)
            if (signals.praiseScore > 0 && signals.joyScore < 0.25f) {
                signals.praiseScore *= 0.65f
            }
            signals.angerScore = boostIf(signals.angerScore, true, 0.12f)
        }

        // 脆弱性联动
        if (signals.sadnessScore > 0 || signals.anxietyScore > 0 || signals.fatigueScore > 0) {
            signals.vulnerabilityScore = maxOf(
                signals.vulnerabilityScore,
                clampScore(maxOf(signals.sadnessScore, signals.anxietyScore, signals.fatigueScore) * 0.85f)
            )
        }
        if (signals.angerScore > 0 && signals.hostilityScore == 0f && signals.rejectionScore == 0f) {
            signals.rejectionScore = maxOf(signals.rejectionScore, signals.angerScore * 0.45f)
        }
        if (signals.joyScore > 0) {
            signals.sentimentScore = maxOf(signals.sentimentScore, signals.joyScore * 0.5f)
        }
    }

    private fun applyStateContext(signals: UserSignals, userMessage: String, state: CharacterState?) {
        if (state == null) return

        // 低精力 + 休息关键词 → 关心信号增强
        if (state.energy <= 35 && REST_CARE_KEYWORDS.any { it in userMessage }) {
            signals.careScore = boostIf(signals.careScore, true, 0.24f)
            signals.reassuranceScore = boostIf(signals.reassuranceScore, true, 0.1f)
            if (signals.reassuranceScore > 0) {
                signals.careScore = boostIf(signals.careScore, true, 0.18f)
            }
        }

        // 受伤/委屈/不安状态 → 安抚和关心信号增强
        if (state.mood in setOf("受伤", "委屈", "不安")) {
            if (signals.reassuranceScore > 0) {
                signals.reassuranceScore = boostIf(signals.reassuranceScore, true, 0.12f)
            }
            if (signals.careScore > 0) {
                signals.careScore = boostIf(signals.careScore, true, 0.1f)
            } else if (signals.reassuranceScore > 0) {
                signals.careScore = boostIf(signals.careScore, true, 0.28f)
            }
        }

        // 高情绪强度 + 命令 → 命令信号增强
        if (state.moodIntensity >= 0.75f && signals.commandScore > 0) {
            signals.commandScore = boostIf(signals.commandScore, true, 0.08f)
        }
    }

    private fun applyRelationshipContext(signals: UserSignals, relationship: RelationshipState?) {
        if (relationship == null) return

        // 低安全感 → 拒绝/敌意/安抚信号增强
        if (relationship.security < 30) {
            if (signals.rejectionScore > 0) {
                signals.rejectionScore = clampScore(signals.rejectionScore * 1.3f)
            }
            if (signals.hostilityScore > 0) {
                signals.hostilityScore = clampScore(signals.hostilityScore * 1.2f)
            }
            if (signals.reassuranceScore > 0) {
                signals.reassuranceScore = boostIf(signals.reassuranceScore, true, 0.15f)
            }
        }

        // 高信任 + 道歉 → 降低拒绝/敌意
        if (relationship.trust > 70 && signals.apologyScore > 0) {
            signals.rejectionScore *= 0.7f
            signals.hostilityScore *= 0.7f
        }

        // 高熟悉 + 命令 + 关心 → 降低命令感
        if (relationship.familiarity > 75 && signals.commandScore > 0 && signals.careScore > 0) {
            signals.commandScore = maxOf(0f, signals.commandScore - 0.12f)
        }

        // 高依赖 + 安抚 → 增强亲密
        if (relationship.dependency > 70 && signals.reassuranceScore > 0) {
            signals.intimacyScore = boostIf(signals.intimacyScore, true, 0.1f)
        }
    }

    private fun softenOrDisambiguate(signals: UserSignals, userMessage: String, matchedSofteners: List<String>) {
        // 俏皮 + 敌意 → 降低敌意
        if (signals.playfulnessScore > 0 && signals.hostilityScore > 0) {
            val playfulFactor = if (listOf("逗你", "开玩笑", "哈哈", "嘿嘿").any { it in userMessage }) 0.35f else 0.65f
            signals.hostilityScore *= playfulFactor
            signals.rejectionScore *= 0.6f
        }

        // 软化词 + 命令 → 降低命令感
        if (matchedSofteners.isNotEmpty() && signals.commandScore > 0) {
            signals.commandScore = maxOf(0f, signals.commandScore - 0.08f)
        }

        // 脆弱性 + 疑问 → 增强不确定
        if (signals.vulnerabilityScore > 0 && signals.questionScore > 0) {
            signals.uncertaintyScore = boostIf(signals.uncertaintyScore, true, 0.1f)
        } else if (signals.vulnerabilityScore > 0) {
            signals.uncertaintyScore = boostIf(signals.uncertaintyScore, true, 0.05f)
        }

        // 安抚 + "不会离开" → 增强亲密
        if (signals.reassuranceScore > 0 && "不会离开" in userMessage) {
            signals.intimacyScore = boostIf(signals.intimacyScore, true, 0.12f)
        }
    }
}
