package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.validateSkillNameValue
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 会话的「自动总结 Skill」（技能沉淀）。
 *
 * 设计参考 Hermes Agent 的后台 Skill Review 机制：
 * - 计数式触发：按工具调用累积量（`creation_nudge_interval`）与单轮复杂度（5-tool-call 规则）
 *   决定是否值得审查，而不是每轮都跑一次模型；
 * - 后台审查：回合结束后 fork 一次轻量模型调用，把"这轮是怎么做成的"提炼成可复用的
 *   SKILL.md，全程不打断对话，失败不影响主流程；
 * - 优先级：先更新本轮用过 / 已有的同类 Skill，实在没有再新建，避免近义技能不断堆积；
 * - 只沉淀"怎么做事"的流程型知识，长期事实与偏好仍归 [AgentMemoryExtractor] 的长期记忆。
 *
 * 与长期记忆一样，开关见「设置 → Agent 设置 → 自动总结 Skill」。
 */
internal object AgentSkillExtractor {

    /** 单轮工具调用数达到该值即视为"复杂任务"，值得沉淀（对齐 Hermes 的 5-tool-call 规则）。 */
    internal const val COMPLEX_TURN_TOOL_CALLS = 5

    /** 累计工具调用数达到该值补一次审查（对齐 Hermes skills.creation_nudge_interval 默认 10）。 */
    internal const val NUDGE_TOOL_CALL_INTERVAL = 10

    /** 触发审查的最小回合字数（用户消息 + 回复），太短的回合通常没有可复用价值。 */
    internal const val MIN_TURN_CHARS = 200

    /** 单个 SKILL.md 的字符上限，避免一次沉淀吃掉整个技能目录预算。 */
    internal const val MAX_SKILL_MD_CHARS = 8_000

    /** Skill 名称长度上限（存储目录名同样受此约束）。 */
    internal const val MAX_SKILL_NAME_CHARS = 40

    /** Skill 描述长度上限（技能目录会注入系统提示词，过长会持续消耗 token）。 */
    internal const val MAX_DESCRIPTION_CHARS = 120

    /** 注入提示词的工具调用轨迹上限。 */
    internal const val MAX_TRAJECTORY_CHARS = 6_000

    private const val SYSTEM_PROMPT =
        "你是技能沉淀器（Skill Reviewer）。你只输出一个 JSON 对象，不要输出解释、寒暄或 Markdown 代码块。"

    /** 审查用的 system 提示词（供写入器复用）。 */
    internal fun systemPrompt(): String = SYSTEM_PROMPT

    /**
     * 用户是否明确要求把这一轮的做法沉淀成 Skill。
     *
     * 明确要求时不再受计数阈值限制（对应 Hermes 的 `/learn` 与"用户纠正做法"场景）。
     */
    internal fun isExplicitLearnRequest(userMessage: String): Boolean {
        if (userMessage.isBlank()) return false
        val text = userMessage.lowercase()
        return EXPLICIT_LEARN_PATTERNS.any { text.contains(it) }
    }

    private val EXPLICIT_LEARN_PATTERNS = listOf(
        "做成skill", "做成 skill", "记成skill", "记成 skill", "存成skill", "存成 skill",
        "总结成skill", "总结成 skill", "提炼成skill", "提炼成 skill", "沉淀成skill",
        "变成skill", "生成skill", "保存为skill", "写成skill", "记到skill",
        "做成技能", "记成技能", "存成技能", "总结成技能", "提炼成技能", "沉淀成技能",
        "沉淀为技能", "做成模板", "存成模板", "记为技能",
        "记住这个流程", "记住这套流程", "记住这个做法", "把这个流程记下来", "把这套流程记下来",
        "把这个流程保存", "把这个方法记下来", "把这个方法存下来", "以后都这么做", "以后都这样",
        "下次还用这个流程", "下次直接这么做", "下次照这个来",
        "make a skill", "create a skill", "save as a skill", "save this as a skill",
        "learn this", "remember this workflow", "remember this process",
        "スキルにして", "スキルとして保存", "この手順を覚えて",
        "스킬로 만들어", "스킬로 저장", "이 과정을 기억해"
    )

    /**
     * 是否值得跑一次技能沉淀审查。
     *
     * @param explicit 用户是否明确要求沉淀
     * @param turnToolCalls 本轮工具调用次数
     * @param accumulatedToolCalls 含本轮的累计工具调用次数（尚未重置的计数）
     */
    internal fun shouldReview(
        explicit: Boolean,
        turnToolCalls: Int,
        accumulatedToolCalls: Int,
        userMessage: String,
        assistantMessage: String
    ): Boolean {
        if (assistantMessage.isBlank()) return false
        val turnChars = userMessage.length + assistantMessage.length
        if (explicit) return turnChars >= MIN_TURN_CHARS
        // 没有工具调用的一轮通常只是闲聊或纯问答，没有可沉淀的流程。
        if (turnToolCalls <= 0) return false
        if (turnChars < MIN_TURN_CHARS) return false
        return turnToolCalls >= COMPLEX_TURN_TOOL_CALLS ||
            accumulatedToolCalls >= NUDGE_TOOL_CALL_INTERVAL
    }

    /** 构造审查用的用户提示词（对外可见，便于测试与调试）。 */
    internal fun buildReviewPrompt(
        userMessage: String,
        assistantMessage: String,
        toolTrace: List<Map<String, Any>>,
        existingSkills: List<AgentSkillBrief>,
        explicit: Boolean
    ): String = buildString {
        appendLine("用户在 Agent 会话里刚完成一轮任务。请判断这次的做法是否值得沉淀成可复用的 Skill，并给出结果。")
        appendLine()
        appendLine("【决策优先级（从高到低）】")
        appendLine("1. 本轮读取过的 Skill：优先补丁式更新它（action=update，name 用它的原名）。")
        appendLine("2. 已有技能列表中范围匹配的：更新它，不要新建近义技能。")
        appendLine("3. 确实没有可复用的：才新建（action=create），名称必须落在可复用的类别层面")
        appendLine("   （例如 `android-release-build`、`nginx-tls-setup`），不要出现日期或一次性任务名。")
        appendLine("4. 这一轮没有可复用的通用做法：输出 action=skip。")
        appendLine()
        appendLine("【硬性要求】")
        appendLine("1. 只沉淀「怎么做事」的流程（步骤、命令、参数、踩过的坑、边界处理）。")
        appendLine("   长期事实、偏好、约定属于长期记忆，不要写进 Skill。")
        appendLine("2. 步骤必须具体到能照着执行；只写本轮真实发生过的做法，不要编造没有出现过的步骤。")
        appendLine("3. 正文结构固定为：")
        appendLine("   `# <技能名称>` + `## 功能描述` + `## 适用场景` + `## 操作步骤` + `## 注意事项`。")
        appendLine("4. 更新已有 Skill 时必须给出**完整**的新正文，保留其中仍然有效的内容，不要只给差异片段。")
        appendLine("5. 若还需要补充参考资料，可额外给出 reference_md（没有就省略该字段）。")
        appendLine("6. 正文使用${AiOutputLanguage.languageName()}书写，名称用简短的英文小写或名词短语。")
        appendLine("7. 只输出一个 JSON 对象，不要代码块、不要任何解释文字。")
        appendLine()
        appendLine(AiOutputLanguage.directive())
        appendLine()
        if (explicit) {
            appendLine("【特别说明】用户已明确要求把这次的做法沉淀成 Skill，请务必给出 create 或 update（除非内容完全不可复用）。")
            appendLine()
        }
        appendLine("【已有技能列表】")
        if (existingSkills.isEmpty()) {
            appendLine("（暂无）")
        } else {
            existingSkills.take(60).forEach { skill ->
                appendLine("- ${skill.name}${if (skill.enabled) "" else "（已停用）"}: ${skill.description.ifBlank { "无描述" }}")
            }
        }
        appendLine()
        val used = usedSkillNames(toolTrace)
        if (used.isNotEmpty()) {
            appendLine("【本轮读取过的 Skill】")
            used.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("【本轮用户请求】")
        appendLine(userMessage.take(4_000))
        appendLine()
        appendLine("【本轮工具调用轨迹】")
        appendLine(buildToolTrajectory(toolTrace))
        appendLine()
        appendLine("【本轮最终回复】")
        appendLine(assistantMessage.take(6_000))
        appendLine()
        appendLine("【输出 JSON 格式】")
        appendLine("{\"action\":\"create|update|skip\",\"name\":\"技能名\",\"description\":\"一句话说明（不超过 60 字）\",")
        appendLine("\"aliases\":[\"别名\"],\"reason\":\"判断理由（一句话）\",\"skill_md\":\"完整的 SKILL.md 正文\"}")
    }.trim()

    /** 本轮被读取/查看过的 Skill 名称（从工具调用参数里提取）。 */
    internal fun usedSkillNames(toolTrace: List<Map<String, Any>>): List<String> {
        val names = mutableListOf<String>()
        toolTrace.forEach { message ->
            if (message["role"] != "assistant") return@forEach
            val calls = message["tool_calls"] as? List<*> ?: return@forEach
            calls.forEach { entry ->
                val function = (entry as? Map<*, *>)?.get("function") as? Map<*, *> ?: return@forEach
                val name = function["name"]?.toString().orEmpty()
                if (name !in SKILL_TOOL_IDS) return@forEach
                val arguments = function["arguments"]?.toString().orEmpty()
                SKILL_NAME_PATTERN.find(arguments)?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { if (it !in names) names.add(it) }
            }
        }
        return names
    }

    /** 把本轮工具调用轨迹压缩成可读文本（供审查模型理解"怎么做的"）。 */
    internal fun buildToolTrajectory(toolTrace: List<Map<String, Any>>): String {
        if (toolTrace.isEmpty()) return "（本轮没有工具调用）"
        val builder = StringBuilder()
        toolTrace.forEach { message ->
            when (message["role"]) {
                "assistant" -> {
                    val calls = message["tool_calls"] as? List<*> ?: return@forEach
                    calls.forEach { entry ->
                        val function = (entry as? Map<*, *>)?.get("function") as? Map<*, *> ?: return@forEach
                        val name = function["name"]?.toString().orEmpty()
                        if (name.isBlank()) return@forEach
                        val arguments = function["arguments"]?.toString().orEmpty().take(400)
                        builder.appendLine("- 调用 $name($arguments)")
                    }
                }
                "tool" -> {
                    val name = message["name"]?.toString().orEmpty()
                    val preview = toolResultPreview(message["content"]).take(300)
                    builder.appendLine("  ↳ ${name.ifBlank { "工具" }} 结果: $preview")
                }
            }
        }
        val text = builder.toString().trim()
        if (text.isEmpty()) return "（本轮没有工具调用）"
        return if (text.length <= MAX_TRAJECTORY_CHARS) text else text.take(MAX_TRAJECTORY_CHARS) + "\n（轨迹过长已截断）"
    }

    /** 工具结果可能是字符串或多模态分块，这里统一拍平成短文本。 */
    private fun toolResultPreview(content: Any?): String = when (content) {
        null -> ""
        is String -> content.replace(Regex("\\s+"), " ").trim()
        is List<*> -> content.mapNotNull { part ->
            when (part) {
                is String -> part
                is Map<*, *> -> part["text"]?.toString()
                else -> null
            }
        }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        else -> content.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 解析审查结果。
     *
     * 返回 null 表示"不沉淀"（action=skip、JSON 非法或字段不可用）。
     */
    internal fun parseReview(raw: String): AgentSkillDraft? {
        val json = extractJsonObject(raw) ?: return null
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val action = obj.get("action")?.asString?.trim()?.lowercase().orEmpty()
        if (action != "create" && action != "update") return null
        val name = sanitizeSkillName(obj.get("name")?.asString.orEmpty())
        if (name.isEmpty()) return null
        val skillMdRaw = obj.get("skill_md")?.asString.orEmpty()
        if (skillMdRaw.isBlank()) return null
        val description = obj.get("description")?.asString
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_DESCRIPTION_CHARS)
            ?.takeIf { it.isNotBlank() }
        val aliases = runCatching {
            (obj.getAsJsonArray("aliases") ?: return@runCatching emptyList<String>())
                .mapNotNull { element ->
                    element.asString?.trim()?.takeIf { it.isNotBlank() && it.length <= MAX_SKILL_NAME_CHARS }
                }
        }.getOrDefault(emptyList())
        val referenceMd = obj.get("reference_md")?.asString
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_SKILL_MD_CHARS)
        return AgentSkillDraft(
            name = name,
            description = description,
            aliases = aliases.distinct().take(8),
            skillMd = sanitizeSkillMd(skillMdRaw, name),
            referenceMd = referenceMd,
            createNew = action == "create"
        )
    }

    /** 从模型输出里截出 JSON 对象（容忍代码块围栏与前后解释文字）。 */
    private fun extractJsonObject(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        val fenced = Regex("(?s)^```[a-zA-Z]*\\s*\\n(.*?)\\n?```$").find(text)
        if (fenced != null) text = fenced.groupValues[1].trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /**
     * 清洗 Skill 名称：去掉路径分隔符/控制字符，空白折成 `-`，统一小写并截断。
     *
     * 小写只影响新建时的目录名；更新既有 Skill 时一律沿用库里的原名（查找按大小写不敏感）。
     */
    internal fun sanitizeSkillName(raw: String): String {
        val cleaned = raw.trim()
            .lowercase(Locale.ROOT)
            .replace('\\', '-')
            .replace('/', '-')
            .filter { it.code >= 32 }
            .replace(Regex("\\s+"), "-")
            .take(MAX_SKILL_NAME_CHARS)
            .trim('-', '.', '_')
        if (cleaned.isEmpty()) return ""
        return runCatching { validateSkillNameValue(cleaned) }.getOrDefault("")
    }

    /** 清洗 SKILL.md 正文：只在整体被代码块包裹时脱壳，必要时补一级标题，并限制长度。 */
    internal fun sanitizeSkillMd(raw: String, name: String): String {
        var text = raw.trim()
        val fenced = Regex("(?s)^```[a-zA-Z]*\\s*\\n(.*?)\\n?```$").find(text)
        if (fenced != null) text = fenced.groupValues[1].trim()
        if (text.isEmpty()) return ""
        if (!text.lineSequence().any { it.trimStart().startsWith("# ") }) {
            text = "# $name\n\n$text"
        }
        return text.take(MAX_SKILL_MD_CHARS).trim()
    }

    private val SKILL_TOOL_IDS = setOf("skill_read", "skill_view")
    private val SKILL_NAME_PATTERN = Regex("\"skill_name\"\\s*:\\s*\"([^\"]+)\"")
}

/** 审查提示词里用到的已有 Skill 摘要（名称 + 描述 + 启用状态）。 */
internal data class AgentSkillBrief(
    val name: String,
    val description: String = "",
    val enabled: Boolean = true
)

/** 审查产出的 Skill 草稿。 */
internal data class AgentSkillDraft(
    val name: String,
    val description: String?,
    val aliases: List<String>,
    val skillMd: String,
    val referenceMd: String?,
    /** true 表示新建，false 表示更新既有 Skill。 */
    val createNew: Boolean
)

/**
 * 技能沉淀阶段。
 *
 * RUNNING：后台审查已启动（界面显示"正在总结技能"）；
 * DONE：审查结束（`skillName` 为空表示本轮没有沉淀任何技能）。
 */
enum class AgentSkillPhase { RUNNING, DONE }

/** 沉淀结果通知：界面据此提示"正在总结 / 已自动新建（更新）Skill"。 */
data class AgentSkillNotice(
    val sessionId: String,
    val skillName: String,
    val created: Boolean,
    val phase: AgentSkillPhase = AgentSkillPhase.DONE
)

/**
 * Agent 技能沉淀写入器：调用一次轻量模型做审查，然后新建或更新本地 Skill。
 *
 * 与 [AgentMemoryWriter] 同构：优先走故障转移队列，其次回退当前激活模型；
 * token 计入 `skill` 来源；同一会话同时只跑一个审查任务，后台审查不会再次触发沉淀。
 */
internal class AgentSkillWriter(
    private val aiClient: LocalAiClient,
    private val listSkills: suspend () -> List<AgentSkillBrief>,
    private val applyDraft: suspend (AgentSkillDraft) -> Boolean,
    private val aiModelProvider: (suspend () -> LocalAiModelEntity?)? = null,
    private val failoverExecutor: LocalChatFailoverExecutor? = null,
    private val onTokenUsage: ((String, String, String, Int, Int) -> Unit)? = null
) {
    private companion object {
        const val TAG = "AgentSkillWriter"
    }

    private val inProgress = ConcurrentHashMap<String, Boolean>()

    /** @return 沉淀结果通知；返回 null 表示本轮没有值得沉淀的内容或调用失败。 */
    suspend fun review(
        sessionId: String,
        userMessage: String,
        assistantMessage: String,
        toolTrace: List<Map<String, Any>>,
        explicit: Boolean
    ): AgentSkillNotice? {
        if (inProgress.putIfAbsent(sessionId, true) != null) return null
        return try {
            val existing = runCatching { listSkills() }.getOrDefault(emptyList())
            val promptMessages = listOf(
                mapOf("role" to "system", "content" to AgentSkillExtractor.systemPrompt()),
                mapOf(
                    "role" to "user",
                    "content" to AgentSkillExtractor.buildReviewPrompt(
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        toolTrace = toolTrace,
                        existingSkills = existing,
                        explicit = explicit
                    )
                )
            )
            val fallbackModel = aiModelProvider?.invoke()
            if (failoverExecutor == null && fallbackModel == null) return null
            val execution = failoverExecutor?.let { executor ->
                runCatching { executor.execute(promptMessages) }
                    .onFailure { LocalLogger.w(TAG, "技能沉淀队列不可用，回退激活模型: ${it.message}") }
                    .getOrNull()
            }
            val result = execution?.value
                ?: fallbackModel?.let { aiClient.chatOnce(it, promptMessages) }
                ?: return null
            if (result.error != null) {
                LocalLogger.w(TAG, "技能沉淀审查失败: ${result.error}")
                return null
            }
            if (result.usage.isNotEmpty()) {
                val usedModel = execution?.model ?: fallbackModel
                if (usedModel != null) {
                    onTokenUsage?.invoke(
                        "skill",
                        usedModel.name,
                        usedModel.model,
                        result.usage["prompt"] ?: 0,
                        result.usage["completion"] ?: 0
                    )
                }
            }
            val draft = AgentSkillExtractor.parseReview(result.content)
            if (draft == null) {
                LocalLogger.i(TAG, "本轮无需沉淀 Skill（skip 或输出不可解析）")
                return null
            }
            val created = applyDraft(draft)
            LocalLogger.i(
                TAG,
                "已${if (created) "新建" else "更新"} Skill「${draft.name}」（${if (draft.createNew) "create" else "update"}）"
            )
            AgentSkillNotice(sessionId = sessionId, skillName = draft.name, created = created)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalLogger.w(TAG, "技能沉淀异常（不影响主流程）: ${e.message}", e)
            null
        } finally {
            inProgress.remove(sessionId)
        }
    }
}
