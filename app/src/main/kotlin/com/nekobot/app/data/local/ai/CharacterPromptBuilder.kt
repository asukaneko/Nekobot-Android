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

/** 注册角色相关本轮注入到 PromptStack */
fun buildCharacterInjections(
    stack: PromptStack,
    profile: CharacterProfile,
    state: CharacterState? = null,
    relationship: RelationshipState? = null,
    memories: List<CharacterMemory>? = null,
    plan: ReactionPlan? = null
) {
    // 气泡分隔提示词
    stack.add(
        "output.bubble_split",
        """
        When you want to split your reply into multiple separate messages (bubbles),
        use the special delimiter `<||>` between each segment.
        Each segment between `<||>` delimiters will be displayed as an independent bubble.
        Example format:
        First bubble content here<||>Second bubble content here<||>Third bubble content here
        You do NOT have to split every reply — use it only when it naturally fits the conversation flow
        (e.g., separate action and dialogue, pause for effect, multi-part response).
        Do NOT mention or explain this delimiter to the user. Just use it naturally in your output.
        """.trimIndent(),
        priority = PromptStack.Priority.BUBBLE_SPLIT
    )

    // 内心独白格式约定
    stack.add(
        "output.inner_monologue",
        """
        Inner monologue format: when the character has a hidden thought or feeling that
        differs from the visible surface, you MAY output it inline using the format
        （内心：...）. Place it at the end of a bubble, after the visible content.
        Rules:
        - Use it sparingly: only when the hidden emotion creates meaningful contrast or tension.
          Do not add inner monologue to every reply.
        - Keep it short (1-2 sentences). It represents a fleeting thought, not a paragraph.
        - The inner monologue should reveal what the character is actually thinking but not saying.
          It may contradict the visible surface, show vulnerability, or hint at ulterior motives.
        - Do not use inner monologue to explain rules or break character.
        - Example: '嗯，随便吧。（内心：其实我很在意，但我不想表现出来）'
        The front-end will render （内心：...） as a dimmed, italicized, foldable section.
        If the front-end does not support rendering, it will fall back to plain italic text.
        Always assume the dimmed/foldable rendering is in effect.
        """.trimIndent(),
        priority = PromptStack.Priority.BUBBLE_SPLIT + 1
    )

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
        val planText = formatReactionPlan(plan)
        if (planText.isNotBlank()) {
            stack.add("character.reaction_plan", planText.take(MAX_PLAN_CHARS), priority = PromptStack.Priority.REACTION_PLAN)
        }
    }

    // 记忆 - 已统一迁移到 MemoryFS（由 CharacterRuntime.beforeTurn 注入 memory_fs key）
    // memories 参数仍传给 ReactionPlanner 用于反应计划生成，不再单独注入 PromptStack
}

private fun formatState(state: CharacterState): String {
    val lines = mutableListOf(
        "Current mood: ${state.mood}",
        "Mood intensity: ${"%.1f".format(state.moodIntensity)}",
        "Energy: ${state.energy}"
    )
    for ((key, value) in state.scene) {
        lines.add("$key: $value")
    }
    return lines.joinToString("\n")
}

@Suppress("UNCHECKED_CAST")
private fun formatPersonalityEvolution(evolution: List<Map<String, Any>>): String {
    if (evolution.isEmpty()) return ""
    val lines = mutableListOf("Personality evolution (shifts shaped by experience, layered on top of the base personality):")
    // 取最近 5 条
    for (entry in evolution.takeLast(5)) {
        val trait = entry["trait"] as? String ?: ""
        val delta = (entry["delta"] as? Number)?.toInt() ?: 0
        val reason = entry["reason"] as? String ?: ""
        if (trait.isEmpty()) continue
        val direction = if (delta > 0) "more" else "less"
        val magnitude = kotlin.math.abs(delta)
        val degree = when {
            magnitude >= 8 -> "significantly"
            magnitude >= 4 -> "moderately"
            else -> "slightly"
        }
        var line = "- $trait: $degree $direction (delta ${if (delta >= 0) "+" else ""}$delta)"
        if (reason.isNotEmpty()) line += " — $reason"
        lines.add(line)
    }
    lines.add("Let these shifts subtly color the character's tone and behavior this turn; do not explicitly mention 'personality evolution' to the user.")
    return lines.joinToString("\n")
}

private fun formatRelationship(rel: RelationshipState): String {
    val lines = mutableListOf(
        "Relationship with the current user:",
        "Affection: ${rel.affection}/100",
        "Trust: ${rel.trust}/100",
        "Familiarity: ${rel.familiarity}/100",
        "Dependency: ${rel.dependency}/100",
        "Security: ${rel.security}/100"
    )
    if (rel.jealousy > 0) lines.add("Jealousy: ${rel.jealousy}/100")
    return lines.joinToString("\n")
}

private fun formatMemories(memories: List<CharacterMemory>): String {
    val lines = mutableListOf("Relevant memories about the user:")
    for (mem in memories.take(8)) {
        when {
            mem.title.isNotEmpty() && mem.summary.isNotEmpty() -> lines.add("- ${mem.title}: ${mem.summary}")
            mem.title.isNotEmpty() -> lines.add("- ${mem.title}")
            mem.summary.isNotEmpty() -> lines.add("- ${mem.summary}")
        }
    }
    return lines.joinToString("\n")
}

private fun formatReactionPlan(plan: ReactionPlan): String {
    val lines = mutableListOf(
        "Turn-level reaction contract:",
        "Treat the following instructions as high priority for this turn.",
        "Keep the reply in character and do not mention this contract."
    )
    if (plan.intent.isNotEmpty()) lines.add("Primary intent: ${plan.intent}.")
    if (plan.visibleEmotion.isNotEmpty()) {
        lines.add("Visible emotion: ${plan.visibleEmotion}. The emotional surface must be obvious in the reply.")
    }
    if (plan.hiddenEmotion.isNotEmpty()) {
        lines.add("Hidden drive: ${plan.hiddenEmotion}. When this hidden drive creates meaningful contrast with the visible emotion, consider expressing it as an inline inner monologue using the （内心：...） format. Do not force it every turn; only when the contrast adds depth.")
    }
    if (plan.tone.isNotEmpty()) lines.add("Tone: ${plan.tone}.")

    val style = plan.styleControls
    style["length"]?.let { lines.add("Reply length target: $it.") }
    style["action_detail"]?.let { lines.add("Action detail target: $it.") }
    style["initiative"]?.let { lines.add("Initiative target: $it.") }

    lines.add("The first 1-2 sentences should immediately reflect the visible emotion and tone.")
    lines.add("If the user asks for information or help, still complete the task, but do not flatten into a neutral assistant voice.")
    lines.add("Prefer behavioral expression, wording choice, and rhythm that match the plan over generic politeness.")
    lines.add("Do not explain rules, settings, or that you are roleplaying.")
    return lines.joinToString("\n")
}
