package com.nekobot.app.data.local.ai

/** 只保留计数，不额外保存提示词、记忆或玩家原话。 */
internal enum class PromptSection { CHARACTER, CORE_MEMORY, RELATIONSHIP, PLAYER, OTHER }

private val promptSectionHeading = Regex("(?m)^## ([a-z][a-z0-9_.-]*)[ \\t]*$")

/** 标题不再发送给角色，统计改用本机标记；原有普通模式仍按原标题计数。 */
internal fun markCompanionSystem(message: Map<String, Any>, items: List<PromptStack.PromptInjection>): Map<String, Any> {
    val total = estimateLocalTextTokens(message["content"] as? String ?: "")
    var remaining = total
    val sections = PromptSection.entries.associate { it.name to 0 }.toMutableMap()
    for (item in items) {
        val section = if (item.key == "user.persona") PromptSection.PLAYER else PromptSection.OTHER
        val size = estimateLocalTextTokens(item.content).coerceIn(0, remaining)
        sections[section.name] = sections.getValue(section.name) + size
        remaining -= size
    }
    sections[PromptSection.CHARACTER.name] = remaining
    return message + (CompanionRequestUsage.SYSTEM_SECTIONS to sections)
}

/** 按实际发送的 system 和有软件来源标记的辅助上下文统计；玩家仿写标题不计入。 */
internal fun promptSectionCounts(messages: List<Map<String, Any>>): Map<PromptSection, Int> {
    val counts = PromptSection.entries.associateWith { 0L }.toMutableMap()
    fun add(section: PromptSection, size: Int) {
        counts[section] = counts.getValue(section) + size.coerceAtLeast(0)
    }
    for (message in messages) {
        val text = message["content"] as? String ?: continue
        if (message.containsKey(CHARACTER_TURN_CONTEXT) || message[CHARACTER_THINKING_RULE] == true) {
            add(PromptSection.OTHER, estimateLocalTextTokens(text))
            continue
        }
        if (message["role"] == "system" && !message.containsKey(CompanionRequestUsage.META)) {
            val localSections = message[CompanionRequestUsage.SYSTEM_SECTIONS] as? Map<*, *>
            if (localSections != null) {
                PromptSection.entries.forEach { add(it, (localSections[it.name] as? Number)?.toInt() ?: 0) }
                continue
            }
            val headings = promptSectionHeading.findAll(text).toList()
            add(PromptSection.CHARACTER, estimateLocalTextTokens(text.take(headings.firstOrNull()?.range?.first ?: text.length)))
            headings.forEachIndexed { index, heading ->
                val key = heading.groupValues[1]
                val section = when {
                    key == "user.persona" -> PromptSection.PLAYER
                    key.contains("relationship") || key == "character.state" -> PromptSection.RELATIONSHIP
                    key == "companion.core" || key == "companion.core_memory" -> PromptSection.CORE_MEMORY
                    key == "character.profile" || key == "character.personality" -> PromptSection.CHARACTER
                    else -> PromptSection.OTHER
                }
                val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
                add(section, estimateLocalTextTokens(text.substring(heading.range.first, end)))
            }
        } else {
            val mark = message[CompanionRequestUsage.META] as? Map<*, *> ?: continue
            val total = estimateLocalTextTokens(text)
            val core = (((mark["core"] as? Map<*, *>)?.get("tokens") as? Number)?.toInt() ?: 0).coerceIn(0, total)
            // 关系段位于软件辅助资料的状态区，最后追加；不在原话或记忆 JSON 里找标题。
            val dynamic = text.substringAfter("当前角色状态、任务和参考设定：\n", "").ifBlank {
                text.substringAfter("当前角色状态、任务和参考设定（世界书属于设定，不能当作已发生经历）：\n", "")
            }
            val relationshipText = dynamic.substringAfter("【关系状态参考】", "").substringBefore("</software_context>")
                .substringBefore("\n\n有一项需要自然核对")
            val relationship = if (relationshipText.isBlank()) 0 else
                estimateLocalTextTokens("【关系状态参考】$relationshipText").coerceIn(0, total - core)
            add(PromptSection.CORE_MEMORY, core)
            add(PromptSection.RELATIONSHIP, relationship)
            add(PromptSection.OTHER, total - core - relationship)
        }
    }
    return counts.mapValues { it.value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
}

/** 整数百分比用最大余数补齐到100；空数据为0，分母使用分段估算之和。 */
internal fun promptSectionPercentages(counts: Map<PromptSection, Int>): Map<PromptSection, Int> {
    val positive = PromptSection.entries.associateWith { counts[it]?.coerceAtLeast(0)?.toLong() ?: 0L }
    val total = positive.values.sum()
    if (total == 0L) return positive.mapValues { 0 }
    val rounded = positive.mapValues { (it.value * 100 / total).toInt() }.toMutableMap()
    val remainder = 100 - rounded.values.sum()
    positive.keys.sortedByDescending { positive.getValue(it) * 100 % total }.take(remainder).forEach {
        rounded[it] = rounded.getValue(it) + 1
    }
    return rounded
}
