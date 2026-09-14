package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.LocalLogger
import java.util.Locale

/**
 * Agent 会话的长期记忆自动抽取。
 *
 * 角色会话由 CharacterRuntime 触发记忆抽取；Agent 会话此前完全没有对应能力，
 * 跨会话的偏好、约定与环境事实只能靠用户手动写进全局 Agent 记忆。
 * 这里在 Agent 回合结束后异步抽取一轮，把值得长期保留的条目追加到全局记忆
 * （注入时已按相关性挑选小节，见 [selectRelevantMemorySections]）。
 *
 * 规范策略：
 * - 记忆文件只由固定分类（用户偏好 / 环境与工具 / 长期约定）组成，每条是一行 `- ` 要点，
 *   不再让模型自由拟标题，否则同类信息会以不同措辞的小节反复堆积、去重失效；
 * - 只收长期稳定的信息，明确禁止记录"本次改了什么"这类一次性任务细节；
 * - 只在开关开启、单轮内容足够长时触发，避免把寒暄也写进长期记忆；
 * - 抽取结果按分类合并（同一分类覆盖旧内容），单次追加有上限，模型失败/超时绝不影响主流程。
 */
internal object AgentMemoryExtractor {

    private const val TAG = "AgentMemoryExtractor"

    /** 触发抽取的最小回合字数（用户消息 + 回复）：太短的回合通常没有长期价值。 */
    internal const val MIN_TURN_CHARS = 600

    /** 单次最多追加的字符数，避免一次抽取吃掉整个记忆预算。 */
    internal const val MAX_APPEND_CHARS = 800

    /** 单条记忆的字符上限。 */
    internal const val MAX_ITEM_CHARS = 160

    /** 单次最多写入的条目数。 */
    internal const val MAX_ITEMS = 6

    /** 过短的条目通常是噪音（“好的”“中文”等）。 */
    private const val MIN_ITEM_CHARS = 4

    private const val SYSTEM_PROMPT =
        "你是长期记忆维护器。只输出标准 Markdown 小节，不要任何解释、寒暄或代码块。"

    /** 抽取用的 system 提示词（供写入器复用）。 */
    internal fun systemPrompt(): String = SYSTEM_PROMPT

    /**
     * 长期记忆的固定分类：记忆文件始终按这三类组织，便于按标题去重与按相关性挑选。
     */
    internal enum class Category(
        val key: String,
        private val zh: String,
        private val en: String,
        private val ja: String,
        private val ko: String
    ) {
        PREFERENCE("preference", "用户偏好", "User preferences", "ユーザーの好み", "사용자 선호"),
        ENVIRONMENT("environment", "环境与工具", "Environment & tools", "環境とツール", "환경과 도구"),
        CONVENTION("convention", "长期约定", "Long-term conventions", "長期の約束", "장기 규칙");

        /** 当前 AI 输出语言下的分类标题（不含 `#`）。 */
        fun heading(): String = when (AiOutputLanguage.languageTag()) {
            "en" -> en
            "ja" -> ja
            "ko" -> ko
            else -> zh
        }
    }

    /** 分类标题别名：模型可能用同义词或别的语言作标题，统一归一到固定分类。 */
    private val CATEGORY_ALIASES: Map<String, Category> = buildMap {
        fun register(category: Category, vararg names: String) {
            names.forEach { put(normalizeHeading(it), category) }
        }
        register(
            Category.PREFERENCE,
            "用户偏好", "偏好", "用户习惯", "习惯", "用户喜好", "回答偏好", "语言偏好",
            "user preference", "user preferences", "preference", "preferences", "habits", "style",
            "ユーザーの好み", "好み", "習慣", "回答スタイル",
            "사용자 선호", "선호", "습관", "응답 스타일"
        )
        register(
            Category.ENVIRONMENT,
            "环境与工具", "环境", "工作环境", "项目环境", "工具", "常用命令", "技术栈",
            "environment", "environment and tools", "environment & tools", "tools", "toolchain",
            "setup", "stack", "commands",
            "環境", "環境とツール", "ツール", "よく使うコマンド",
            "환경", "환경과 도구", "도구", "자주 쓰는 명령"
        )
        register(
            Category.CONVENTION,
            "长期约定", "约定", "长期规则", "规则", "工作约定", "流程约定",
            "convention", "conventions", "long-term conventions", "long term conventions",
            "rules", "agreement",
            "長期の約束", "約束", "ルール", "取り決め",
            "장기 규칙", "규칙", "약속", "합의"
        )
    }

    /** 一次性 / 临时性表述：这类内容不该写进长期记忆。 */
    private val TRANSIENT_PATTERN = Regex(
        "(本轮|这轮|这一轮|刚刚|刚才|临时|暂时|目前这次|当前任务|本次任务|今天先|未提交|待办|TODO|" +
            "this turn|just now|for now|temporar|current task)",
        RegexOption.IGNORE_CASE
    )

    /** 列表项前缀（`- `、`* `、`1. ` 等）。 */
    private val ITEM_MARKER = Regex("^\\s*(?:[-*+•]|\\d+[.)、])\\s*")

    /** 判断这一轮是否值得抽取（同样要求足够长的回合，避免把寒暄写进长期记忆）。 */
    internal fun shouldExtract(userMessage: String, assistantMessage: String): Boolean =
        assistantMessage.isNotBlank() &&
            (userMessage.length + assistantMessage.length) >= MIN_TURN_CHARS

    /** 构造抽取用的用户提示词（对外可见，便于测试与调试）。 */
    internal fun buildExtractionPrompt(userMessage: String, assistantMessage: String): String = buildString {
        appendLine("从下面这一轮对话里，提取**值得跨会话长期记住**的内容，并按固定分类整理。")
        appendLine()
        appendLine("【只允许下面三类，标题必须原样使用，不要自造分类、不要加别的小节】")
        appendLine("- ## ${Category.PREFERENCE.heading()}：用户稳定的偏好、习惯与约束（语言、风格、详略、明确说过的“不要”）。")
        appendLine("- ## ${Category.ENVIRONMENT.heading()}：长期稳定的环境与工具事实（操作系统、构建方式、常用命令、长期使用的外部服务）。")
        appendLine("- ## ${Category.CONVENTION.heading()}：用户明确要求以后一直遵守的约定、流程或规则。")
        appendLine()
        appendLine("【写入标准】")
        appendLine("1. 只写“以后还用得上”的稳定信息：换个会话、换个任务依然成立才写。")
        appendLine("2. 每条一行，用 `- ` 开头，一句话说清；不要展开叙述，不要照抄对话。")
        appendLine("3. 最多 6 条，只保留最有价值的；宁可少写，也不要凑数。")
        appendLine("4. 确实没有值得长期记住的内容时，只输出 NONE。")
        appendLine()
        appendLine("【绝对不要记录】")
        appendLine("- 本次/本轮任务的具体内容：改了哪个文件、哪一行、报了什么错、修了什么 bug；")
        appendLine("- 一次性的路径、行号、提交哈希、版本号、临时变量或临时状态；")
        appendLine("- 寒暄、闲聊、复述对话、过程与心情描述；")
        appendLine("- 未经确认的猜测或你自己的推论；")
        appendLine("- 密钥、令牌、口令等敏感信息。")
        appendLine()
        appendLine("【示例】")
        appendLine("值得记：`- 用户要求回复使用简体中文，并且偏好先给结论。`")
        appendLine("值得记：`- 该 Android 项目的 release 构建需要先把 JAVA_HOME 指向 JDK 21。`")
        appendLine("不要记：`- 本次把 xxx.kt 第 120 行的空指针修好了。`")
        appendLine("不要记：`- 今天用户让我改了一个按钮颜色。`")
        appendLine()
        appendLine(AiOutputLanguage.directive())
        appendLine()
        appendLine("【用户】")
        appendLine(userMessage.take(4_000))
        appendLine()
        appendLine("【Agent】")
        appendLine(assistantMessage.take(6_000))
    }.trim()

    /**
     * 清洗模型输出：去代码块围栏、只保留固定分类的小节、过滤一次性细节，并限制条数与长度。
     *
     * 无有效内容（输出 NONE、没有标准分类、条目全部被过滤）时返回 ""。
     */
    internal fun sanitizeExtraction(raw: String): String {
        val text = raw
            .replace(Regex("(?s)```[a-zA-Z]*\\n?"), "")
            .replace("```", "")
            .trim()
        if (text.isEmpty()) return ""
        if (text.equals("NONE", ignoreCase = true)) return ""

        val grouped = linkedMapOf<Category, MutableList<String>>()
        splitSections(text).forEach { (title, body) ->
            val category = categoryOf(title)
            if (category == null) {
                LocalLogger.i(TAG, "记忆小节被忽略：分类不在标准范围（${title.take(40)}）")
                return@forEach
            }
            val bucket = grouped.getOrPut(category) { mutableListOf() }
            memoryItems(body).forEach { if (it !in bucket) bucket.add(it) }
        }
        if (grouped.isEmpty()) return ""

        val output = StringBuilder()
        var itemCount = 0
        for (category in Category.entries) {
            val items = grouped[category] ?: continue
            val heading = "## ${category.heading()}"
            val block = StringBuilder()
            for (item in items) {
                if (itemCount >= MAX_ITEMS) break
                val line = "- ${item.take(MAX_ITEM_CHARS).trim()}"
                val projected = output.length + heading.length + block.length + line.length + 2
                if (output.isNotEmpty() && projected > MAX_APPEND_CHARS) break
                block.appendLine(line)
                itemCount++
            }
            if (block.isEmpty()) continue
            output.appendLine(heading)
            output.append(block)
            output.appendLine()
        }
        return output.toString().trim()
    }

    /**
     * 把抽取结果合并进既有记忆：按分类（标题）合并，同一分类用新内容覆盖旧内容。
     *
     * 历史记忆里可能残留 `# 偏好` 这类自由标题，这里一并按别名归一到固定分类，
     * 使新写入的标准小节替换掉旧的同类小节，而不是两个小节并存。
     */
    internal fun mergeMemory(existing: String, addition: String): String {
        val cleanAddition = sanitizeExtraction(addition)
        if (cleanAddition.isBlank()) return existing
        val merged = LinkedHashMap<String, Pair<String, String>>()
        fun keyOf(title: String): String = categoryOf(title)?.key
            ?: title.trim().lowercase(Locale.ROOT)
        splitSections(existing).forEach { (title, body) ->
            merged[keyOf(title)] = title.trim() to body.trim()
        }
        splitSections(cleanAddition).forEach { (title, body) ->
            merged[keyOf(title)] = title.trim() to body.trim()
        }
        return merged.values.joinToString("\n\n") { (title, body) ->
            if (title.isEmpty()) body else "$title\n$body"
        }.trim()
    }

    /** 小节标题归属的固定分类；不属于标准分类时返回 null。 */
    private fun categoryOf(title: String): Category? {
        val normalized = normalizeHeading(title)
        if (normalized.isEmpty()) return null
        return CATEGORY_ALIASES[normalized]
    }

    /** 归一化标题：去掉 `#`、统一小写、折叠空白与分隔符。 */
    private fun normalizeHeading(title: String): String = title
        .replace(Regex("^#+"), "")
        .replace(Regex("[\\s_\\-]+"), " ")
        .trim()
        .lowercase(Locale.ROOT)

    /** 从一个小节正文里提炼要点行：去掉列表符号、过滤临时细节与噪音、限制长度。 */
    private fun memoryItems(body: String): List<String> = body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { ITEM_MARKER.replace(it, "").replace(Regex("\\s+"), " ").trim() }
        .filter { it.length >= MIN_ITEM_CHARS }
        .filterNot { TRANSIENT_PATTERN.containsMatchIn(it) }
        .map { it.take(MAX_ITEM_CHARS) }
        .distinct()
        .toList()

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
                    .onFailure { LocalLogger.w(TAG, "记忆抽取队列不可用，回退激活模型: ${it.message}") }
                    .getOrNull()
            }
            val result = execution?.value
                ?: fallbackModel?.let { aiClient.chatOnce(it, promptMessages) }
                ?: return false
            if (result.error != null) {
                LocalLogger.w(TAG, "记忆抽取失败: ${result.error}")
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
            if (addition.isBlank()) {
                LocalLogger.i(TAG, "本轮没有符合标准的长期记忆内容，跳过写入")
                return false
            }
            val merged = AgentMemoryExtractor.mergeMemory(readMemory(), addition)
            writeMemory(merged)
            LocalLogger.i(TAG, "已写入 Agent 长期记忆: +${addition.length} 字符")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalLogger.w(TAG, "记忆抽取异常（不影响主流程）: ${e.message}", e)
            false
        } finally {
            inProgress.remove(sessionId)
        }
    }
}
