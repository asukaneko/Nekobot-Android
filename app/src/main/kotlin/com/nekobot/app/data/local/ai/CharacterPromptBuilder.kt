package com.nekobot.app.data.local.ai

/**
 * 角色提示词注入构建器，对应原仓库 nbot/character/prompt_builder.py。
 *
 * 将 CharacterProfile / CharacterState / RelationshipState / ReactionPlan / Memories
 * 转换为 PromptStack 注入项。
 */

private const val MAX_STATE_CHARS = 800
private const val MAX_RELATIONSHIP_CHARS = 500
private const val MAX_MEMORY_CHARS = 2000
private const val MAX_PLAN_CHARS = 900

/** 角色输出约定清单：均属动态段，持久化的基础提示词不得残留旧副本。 */
internal val characterOutputPromptKeys = listOf(
    "output.bubble_split", "output.inner_monologue", CHARACTER_THINKING_STYLE_KEY, COMPANION_SPEAKING_STYLE_KEY
)

/** 注册角色相关本轮注入到 PromptStack */
fun buildCharacterInjections(
    stack: PromptStack,
    profile: CharacterProfile,
    state: CharacterState? = null,
    relationship: RelationshipState? = null,
    memories: List<CharacterMemory>? = null,
    plan: ReactionPlan? = null,
    companionMode: Boolean = false
) {
    // 气泡分隔提示词
    stack.add(
        "output.bubble_split",
        if (companionMode) CompanionPromptLanguage.bubbleRule else """
        当你想把回复拆成多条独立消息（气泡）时，请在各段之间使用特殊分隔符 `<||>`。
        `<||>` 分隔的每一段都会显示为一个独立气泡。
        格式示例：
        第一条气泡内容<||>第二条气泡内容<||>第三条气泡内容
        不必拆分每次回复；只在符合对话节奏时使用，例如分开动作与对话、停顿以增强效果，或分段回答。
        不要向玩家提及或解释这个分隔符，在输出中自然使用即可。
        """.trimIndent(),
        priority = PromptStack.Priority.BUBBLE_SPLIT
    )

    if (companionMode) stack.add(COMPANION_SPEAKING_STYLE_KEY, CompanionPromptLanguage.speakingRule,
        priority = PromptStack.Priority.BUBBLE_SPLIT - 1)

    // 内心独白格式约定（2026-10-22 按用户决定限定范围）：
    // - 普通角色 / 群聊（companionMode = false）：保留作者原版整段，行为与以前一致；
    // - 单会话窗口（companionMode = true）：不再注入这一层，也不再加“你知道玩家能看到独白”的说明。
    //   想要括号独白、括号动作或微信式短句，由用户在“说话方式”里自己写，这里只原样下发。
    if (!companionMode) {
        stack.add(
            "output.inner_monologue",
            """
            内心独白格式：当角色有隐藏的想法或感受、与表面表现不同时，可以用（内心：...）写在正文里。

            把它放在那条话的末尾，接在说出口的内容之后；分段回复时放在 <||> 之前。
            规则：
            - 克制使用：只有隐藏情绪形成有意义的反差或张力时才写，不要每次回复都加内心独白。
            - 保持简短（1—2句），表达一闪而过的念头，不要写成长段。
            - 写出真正想着却没说出口的内容；可以与表面表现矛盾、流露脆弱，或暗示隐藏动机。
            - 不要借内心独白解释规则或脱离角色。
            - 示例：'嗯，随便吧。（内心：其实我很在意，但我不想表现出来）'
            前台会把（内心：...）渲染为颜色较淡、可折叠的区域，按渲染已生效来写。
            """.trimIndent(),
            priority = PromptStack.Priority.BUBBLE_SPLIT + 1
        )
    }

    // 角色运行时状态
    if (state != null) {
        val stateText = formatState(state)
        if (stateText.isNotBlank()) {
            stack.add("character.runtime_state", stateText.take(MAX_STATE_CHARS), priority = PromptStack.Priority.CHARACTER_STATE)
        }
        // 性格演化
        if (state.personalityEvolution.isNotEmpty()) {
            val evoText = formatPersonalityEvolution(state.personalityEvolution)
            if (evoText.isNotBlank()) {
                stack.add("character.personality_evolution", evoText.take(600), priority = PromptStack.Priority.CHARACTER_PROFILE + 1)
            }
        }
    }

    // 关系状态
    if (relationship != null) {
        val relText = formatRelationship(relationship)
        if (relText.isNotBlank()) {
            stack.add("character.relationship", relText.take(MAX_RELATIONSHIP_CHARS), priority = PromptStack.Priority.CHARACTER_RELATIONSHIP)
        }
    }

    // 反应计划
    if (plan != null) {
        val planText = formatReactionPlan(plan, companionMode)
        if (planText.isNotBlank()) {
            stack.add("character.reaction_plan", planText.take(MAX_PLAN_CHARS), priority = PromptStack.Priority.REACTION_PLAN)
        }
    }

    // 记忆 - 已统一迁移到 MemoryFS（由 CharacterRuntime.beforeTurn 注入 memory_fs key）
    // memories 参数仍传给 ReactionPlanner 用于反应计划生成，不再单独注入 PromptStack
}

private fun formatState(state: CharacterState): String {
    val lines = mutableListOf(
        "当前心情：${state.mood}",
        "情绪强度：${"%.1f".format(state.moodIntensity)}",
        "精力：${state.energy}"
    )
    for ((key, value) in state.scene) {
        lines.add("$key: $value")
    }
    return lines.joinToString("\n")
}

@Suppress("UNCHECKED_CAST")
private fun formatPersonalityEvolution(evolution: List<Map<String, Any>>): String {
    if (evolution.isEmpty()) return ""
    val lines = mutableListOf("性格演化（经历塑造的变化，叠加在基础性格之上）：")
    // 取最近 5 条
    for (entry in evolution.takeLast(5)) {
        val trait = entry["trait"] as? String ?: ""
        val delta = (entry["delta"] as? Number)?.toInt() ?: 0
        val reason = entry["reason"] as? String ?: ""
        if (trait.isEmpty()) continue
        val direction = if (delta > 0) "增强" else "减弱"
        val magnitude = kotlin.math.abs(delta)
        val degree = when {
            magnitude >= 8 -> "显著"
            magnitude >= 4 -> "适度"
            else -> "轻微"
        }
        var line = "- $trait：$degree$direction（变化量 ${if (delta >= 0) "+" else ""}$delta）"
        if (reason.isNotEmpty()) line += " — $reason"
        lines.add(line)
    }
    lines.add("让这些变化自然、细微地影响角色本轮的语气和行为，不要向玩家明确提及“性格演化”。")
    return lines.joinToString("\n")
}

private fun formatRelationship(rel: RelationshipState): String {
    val lines = mutableListOf(
        "与当前玩家的关系：",
        "好感：${rel.affection}/100",
        "信任：${rel.trust}/100",
        "熟悉度：${rel.familiarity}/100",
        "依赖：${rel.dependency}/100",
        "安全感：${rel.security}/100"
    )
    if (rel.jealousy > 0) lines.add("嫉妒：${rel.jealousy}/100")
    return lines.joinToString("\n")
}

private fun formatMemories(memories: List<CharacterMemory>): String {
    val lines = mutableListOf("与玩家有关的记忆：")
    for (mem in memories.take(8)) {
        when {
            mem.title.isNotEmpty() && mem.summary.isNotEmpty() -> lines.add("- ${mem.title}: ${mem.summary}")
            mem.title.isNotEmpty() -> lines.add("- ${mem.title}")
            mem.summary.isNotEmpty() -> lines.add("- ${mem.summary}")
        }
    }
    return lines.joinToString("\n")
}

private fun formatReactionPlan(plan: ReactionPlan, companionMode: Boolean): String {
    val lines = mutableListOf(
        "本轮反应约定：",
        "将以下要求作为本轮的高优先级指引。",
        "保持角色身份，不要提及这份约定。"
    )
    if (plan.intent.isNotEmpty()) lines.add("主要意图：${plan.intent}。")
    if (plan.visibleEmotion.isNotEmpty()) {
        lines.add("表露的情绪：${plan.visibleEmotion}。回复中必须清楚表现出这种表层情绪。")
    }
    if (plan.hiddenEmotion.isNotEmpty()) {
        // 普通角色保持改动前原文（逐字）；单会话窗口（2026-10-22 起）不教（内心：…）格式，
        // 只提示把反差体现在言行里。
        lines.add(
            if (companionMode) {
                "隐藏动机：${plan.hiddenEmotion}。它不必说出口，可以在言行里自然露出痕迹；当它与表露的情绪形成有意义的反差时，优先用措辞、停顿或动作表现，不要每轮硬加，也不要向玩家点明。"
            } else {
                "隐藏动机：${plan.hiddenEmotion}。当它与表露的情绪形成有意义的反差时，可以考虑用（内心：...）格式在正文中表达内心独白；不要每轮硬加，只在反差能增加层次时使用。"
            }
        )
    }
    if (plan.tone.isNotEmpty()) lines.add("语气：${plan.tone}。")

    val style = plan.styleControls
    style["length"]?.let { lines.add("回复长度目标：$it。") }
    style["action_detail"]?.let { lines.add("动作细节目标：$it。") }
    style["initiative"]?.let { lines.add("主动性目标：$it。") }

    lines.add("开头1—2句就应体现表露的情绪和语气。")
    lines.add("玩家询问信息或寻求帮助时，仍要完成任务，但不要变成毫无情绪的中性助手口吻。")
    lines.add("优先选择符合计划的行为表达、措辞和节奏，而非泛泛的客套。")
    lines.add("不要解释规则、设置，也不要说明自己正在扮演角色。")
    return lines.joinToString("\n")
}
