package com.nekobot.app.data.local.ai

/**
 * Agent 会话的长期记忆自动抽取。
 *
 * 角色会话由 CharacterRuntime 触发记忆抽取；Agent 会话此前完全没有对应能力，
 * 跨会话的偏好、约定与环境事实只能靠用户手动写进全局 Agent 记忆。
 * 这里在 Agent 回合结束后异步抽取一轮，把值得长期保留的条目追加到全局记忆
 * （注入时已按相关性挑选小节，见 [selectRelevantMemorySections]）。
 *
 * 保守策略：
 * - 只在开关开启、单轮内容足够长时触发，避免把寒暄也写进长期记忆；
 * - 抽取结果按小节标题去重合并，重复内容不会累积；
 * - 单次追加有上限，模型失败/超时绝不影响主流程。
 */
internal object AgentMemoryExtractor {

    /** 触发抽取的最小回合字数（用户消息 + 回复）：太短的回合通常没有长期价值。 */
    internal const val MIN_TURN_CHARS = 200

    /** 单次最多追加的字符数，避免一次抽取吃掉整个记忆预算。 */
    internal const val MAX_APPEND_CHARS = 1_200

    private const val SYSTEM_PROMPT =
        "你是长期记忆维护器。只输出 Markdown 小节，不要任何解释、寒暄或代码块。"

    /** 抽取用的 system 提示词（供写入器复用）。 */
    internal fun systemPrompt(): String = SYSTEM_PROMPT

    /** 判断这一轮是否值得抽取。 */
    internal fun shouldExtract(userMessage: String, assistantMessage: String): Boolean =
        (userMessage.length + assistantMessage.length) >= MIN_TURN_CHARS &&
            assistantMessage.isNotBlank()

    /** 构造抽取用的用户提示词（对外可见，便于测试与调试）。 */
    internal fun buildExtractionPrompt(userMessage: String, assistantMessage: String): String = buildString {
        appendLine("从下面这一轮对话中提取值得**跨会话长期记住**的内容，例如：")
        appendLine("- 用户稳定偏好、习惯、约束（语言、风格、不要做什么）")
        appendLine("- 项目/环境的长期事实（路径、约定、技术栈、常用命令）")
        appendLine("- 用户明确要求以后一直遵守的约定")
        appendLine()
        appendLine("要求：")
        appendLine("1. 只保留长期有效的信息；一次性的任务细节、寒暄、临时状态一律忽略。")
        appendLine("2. 每条单独成节，格式为：`# 简短标题` 换行后接一句话说明。")
        appendLine("3. 与本次任务无关或无法确认的内容不要编造。")
        appendLine("4. 如果没有任何值得长期记住的内容，只输出 NONE。")
        appendLine()
        appendLine("【用户】")
        appendLine(userMessage.take(4_000))
        appendLine()
        appendLine("【Agent】")
        appendLine(assistantMessage.take(6_000))
    }

    /**
     * 清洗模型输出：去代码块围栏、去空行、限制长度；无有效小节或输出 NONE 时返回 ""。
     */
    internal fun sanitizeExtraction(raw: String): String {
        val withoutFence = raw
            .replace(Regex("(?s)```[a-zA-Z]*\\n?"), "")
            .replace("```", "")
            .trim()
        if (withoutFence.isEmpty()) return ""
        if (withoutFence.equals("NONE", ignoreCase = true)) return ""
        if (!withoutFence.contains('#')) return ""
        return withoutFence.take(MAX_APPEND_CHARS).trim()
    }

    /**
     * 把抽取结果合并进既有记忆：按小节标题去重（标题相同视为同一主题，保留新内容）。
     */
    internal fun mergeMemory(existing: String, addition: String): String {
        val cleanAddition = sanitizeExtraction(addition)
        if (cleanAddition.isBlank()) return existing
        val existingSections = splitSections(existing)
        val additionSections = splitSections(cleanAddition)
        if (additionSections.isEmpty()) return existing

        val merged = LinkedHashMap<String, String>()
        existingSections.forEach { (title, body) -> merged[title] = body }
        additionSections.forEach { (title, body) -> merged[title] = body }
        return merged.entries.joinToString("\n\n") { (title, body) ->
            if (title.isEmpty()) body.trim() else "$title\n${body.trim()}"
        }.trim()
    }

    /** 按 `# 标题` 切分小节；标题键保留完整的标题行（含 `#`），没有标题时整段作为无标题小节。 */
    private fun splitSections(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()
        val headingPattern = Regex("(?m)^#{1,6}\\s+.*$")
        val matches = headingPattern.findAll(text).toList()
        if (matches.isEmpty()) {
            return listOf("" to text.trim())
        }
        val sections = mutableListOf<Pair<String, String>>()
        val preamble = text.substring(0, matches.first().range.first).trim()
        if (preamble.isNotEmpty()) sections.add("" to preamble)
        matches.forEachIndexed { index, match ->
            val start = match.range.last + 1
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val title = match.value.trim()
            val body = text.substring(start, end).trim()
            sections.add(title to body)
        }
        return sections
    }
}

/**
 * Agent 长期记忆写入器：调用一次轻量模型抽取，然后合并进全局 Agent 记忆。
 *
 * 与 [SessionNameGenerator] 同构：优先走故障转移队列，其次回退当前激活模型；
 * token 计入 `agent_memory` 来源；同一会话同时只跑一个抽取任务。
 */
internal class AgentMemoryWriter(
    private val aiClient: LocalAiClient,
    private val readMemory: () -> String,
    private val writeMemory: (String) -> Unit,
    private val aiModelProvider: (suspend () -> com.nekobot.app.data.local.db.LocalAiModelEntity?)? = null,
    private val failoverExecutor: LocalChatFailoverExecutor? = null,
    private val onTokenUsage: ((String, String, String, Int, Int) -> Unit)? = null
) {
    private companion object {
        const val TAG = "AgentMemoryWriter"
    }

    private val inProgress = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** @return 是否真正写入了新内容。 */
    suspend fun extractAndAppend(
        sessionId: String,
        userMessage: String,
        assistantMessage: String
    ): Boolean {
        if (!AgentMemoryExtractor.shouldExtract(userMessage, assistantMessage)) return false
        if (inProgress.putIfAbsent(sessionId, true) != null) return false
        return try {
            val promptMessages = listOf(
                mapOf("role" to "system", "content" to AgentMemoryExtractor.systemPrompt()),
                mapOf(
                    "role" to "user",
                    "content" to AgentMemoryExtractor.buildExtractionPrompt(userMessage, assistantMessage)
                )
            )
            val fallbackModel = aiModelProvider?.invoke()
            if (failoverExecutor == null && fallbackModel == null) return false
            val execution = failoverExecutor?.let { executor ->
                runCatching { executor.execute(promptMessages) }
                    .onFailure { com.nekobot.app.data.local.LocalLogger.w(TAG, "记忆抽取队列不可用，回退激活模型: ${it.message}") }
                    .getOrNull()
            }
            val result = execution?.value
                ?: fallbackModel?.let { aiClient.chatOnce(it, promptMessages) }
                ?: return false
            if (result.error != null) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "记忆抽取失败: ${result.error}")
                return false
            }
            if (result.usage.isNotEmpty()) {
                val usedModel = execution?.model ?: fallbackModel
                if (usedModel != null) {
                    onTokenUsage?.invoke(
                        "agent_memory",
                        usedModel.name,
                        usedModel.model,
                        result.usage["prompt"] ?: 0,
                        result.usage["completion"] ?: 0
                    )
                }
            }
            val addition = AgentMemoryExtractor.sanitizeExtraction(result.content)
            if (addition.isBlank()) return false
            val merged = AgentMemoryExtractor.mergeMemory(readMemory(), addition)
            writeMemory(merged)
            com.nekobot.app.data.local.LocalLogger.i(TAG, "已写入 Agent 长期记忆: +${addition.length} 字符")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "记忆抽取异常（不影响主流程）: ${e.message}", e)
            false
        } finally {
            inProgress.remove(sessionId)
        }
    }
}
