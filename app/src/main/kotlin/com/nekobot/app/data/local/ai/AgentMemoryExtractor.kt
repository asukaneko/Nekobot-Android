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

    /** 单次最多写入的条目数（也限制单轮最多几条操作）。 */
    internal const val MAX_ITEMS = 6

    /** 改写后记忆全文的字符上限，与 [GlobalAgentMemoryStore.MAX_CONTENT_CHARS] 保持一致的量级。 */
    internal const val MAX_MEMORY_CHARS = 32_000

    /** 过短的条目通常是噪音（“好的”“中文”等）。 */
    private const val MIN_ITEM_CHARS = 4

    private const val SYSTEM_PROMPT =
        "你是长期记忆维护器。先读当前记忆，再只输出 `动作 | 分类 | 内容` 形式的操作行，" +
            "不要任何解释、寒暄或代码块。"

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

    /**
     * 注入提示词的既有记忆上限。
     *
     * 记忆文件本身可到 32000 字符，全量塞进抽取提示词既贵又容易让模型忽略关键条目；
     * 这里给一个预算，超出时按分类均衡截断并明确告知模型"只看到了一部分"，
     * 避免它把没看到的内容当成不存在而重复写入。
     */
    internal const val MAX_EXISTING_MEMORY_CHARS = 6_000

    /**
     * 构造抽取用的用户提示词（对外可见，便于测试与调试）。
     *
     * @param existingMemory 当前已有的长期记忆全文（为空表示还没有任何记忆）
     */
    internal fun buildExtractionPrompt(
        userMessage: String,
        assistantMessage: String,
        existingMemory: String = ""
    ): String = buildString {
        appendLine("从下面这一轮对话里，提取**值得跨会话长期记住**的内容，并按固定分类整理。")
        appendLine()
        appendLine("【只允许下面三类，标题必须原样使用，不要自造分类、不要加别的小节】")
        appendLine("- ## ${Category.PREFERENCE.heading()}：用户稳定的偏好、习惯与约束（语言、风格、详略、明确说过的“不要”）。")
        appendLine("- ## ${Category.ENVIRONMENT.heading()}：长期稳定的环境与工具事实（操作系统、构建方式、常用命令、长期使用的外部服务）。")
        appendLine("- ## ${Category.CONVENTION.heading()}：用户明确要求以后一直遵守的约定、流程或规则。")
        appendLine()
        appendLine("【先审查已有记忆，再决定写什么】")
        appendLine("下面是当前已经记住的内容。先逐条判断它们在**本轮对话后是否仍然成立**，然后才决定动作：")
        appendLine("- 已经有等价条目 → 不要重复写，跳过；")
        appendLine("- 新信息让旧条目过时或与之冲突 → 用 replace 改写那条旧条目，不要新旧并存；")
        appendLine("- 旧条目已被用户明确否定、或确属一次性内容 → 用 delete 删掉；")
        appendLine("- 确实是全新的稳定信息 → 用 add 新增。")
        appendLine("宁可什么都不做，也不要因为没看到就重复添加。")
        appendLine()
        appendExistingMemory(existingMemory)
        appendLine("【输出格式】")
        appendLine("每行一条操作，格式：`动作 | 分类 | 内容`")
        appendLine("- 动作只能是 add / replace / delete 三个之一；")
        appendLine("- 分类用上面三个标题之一（写分类名即可，不要带 `##`）；")
        appendLine("- add / replace 的「内容」写一句话要点；delete 的「内容」写**要删除的那条旧条目的原文**，必须与上面已有记忆里的文字完全一致；")
        appendLine("- 没有任何改动时，只输出 NONE。")
        appendLine()
        appendLine("【示例】")
        appendLine("add | ${Category.PREFERENCE.heading()} | 用户要求回复使用简体中文，并且偏好先给结论。")
        appendLine("replace | ${Category.ENVIRONMENT.heading()} | 该 Android 项目的 release 构建需要先把 JAVA_HOME 指向 JDK 21。")
        appendLine("delete | ${Category.CONVENTION.heading()} | 用户要求所有回复都附带详细注释。")
        appendLine()
        appendLine("【写入标准】")
        appendLine("1. 只写“以后还用得上”的稳定信息：换个会话、换个任务依然成立才写。")
        appendLine("2. 一句话说清；不要展开叙述，不要照抄对话。")
        appendLine("3. 单轮最多 6 条操作，只保留最有价值的；宁可少写，也不要凑数。")
        appendLine()
        appendLine("【绝对不要记录】")
        appendLine("- 本次/本轮任务的具体内容：改了哪个文件、哪一行、报了什么错、修了什么 bug；")
        appendLine("- 一次性的路径、行号、提交哈希、版本号、临时变量或临时状态；")
        appendLine("- 寒暄、闲聊、复述对话、过程与心情描述；")
        appendLine("- 未经确认的猜测或你自己的推论；")
        appendLine("- 密钥、令牌、口令等敏感信息。")
        appendLine()
        appendLine(AiOutputLanguage.directive())
        appendLine()
        appendLine("【用户】")
        appendLine(userMessage.take(4_000))
        appendLine()
        appendLine("【Agent】")
        appendLine(assistantMessage.take(6_000))
    }.trim()

    /** 渲染提示词里的「当前已有记忆」小节；无记忆或全部被预算截断时给出明确说明。 */
    private fun StringBuilder.appendExistingMemory(existingMemory: String) {
        val trimmed = existingMemory.trim()
        if (trimmed.isEmpty()) {
            appendLine("【当前已有记忆】")
            appendLine("（尚无任何记忆，本轮全部按 add 处理。）")
            appendLine()
            return
        }
        val budgeted = trimmed.take(MAX_EXISTING_MEMORY_CHARS)
        appendLine("【当前已有记忆】")
        appendLine(budgeted)
        if (budgeted.length < trimmed.length) {
            appendLine()
            appendLine("（注意：已有记忆较长，这里只显示了前面一部分。")
            appendLine("看不到的部分**可能已经记过相关内容**，因此不确定时优先不写，不要凭猜测重复添加。）")
        }
        appendLine()
    }

    /**
     * 清洗模型输出：去代码块围栏，得到逐行的操作文本。
     *
     * 只做格式层面的事情（去围栏、去空行、截断）；操作的合法性在
     * [parseActions] 里逐条判定，因为是否可删除还依赖当前记忆的实际内容。
     */
    internal fun cleanReviewOutput(raw: String): String {
        val text = raw
            .replace(Regex("(?s)```[a-zA-Z]*\\n?"), "")
            .replace("```", "")
            .trim()
        if (text.isEmpty()) return ""
        if (text.equals("NONE", ignoreCase = true)) return ""
        return text
    }

    /** 对既有记忆的一次改动。 */
    internal sealed interface MemoryAction {
        val category: Category
        val content: String

        /** 新增一条要点。 */
        data class Add(override val category: Category, override val content: String) : MemoryAction

        /**
         * 改写一条既有要点。
         *
         * [target] 是模型指认的旧条目原文（可能为 null：模型用了本分类的另一种表述）。
         * 应用时优先按 [target] 精确定位，定位不到再退化为「替换本分类第一条」。
         */
        data class Replace(
            override val category: Category,
            override val content: String,
            val target: String?
        ) : MemoryAction

        /** 删除一条既有要点；[target] 是旧条目原文。 */
        data class Delete(override val category: Category, override val content: String) : MemoryAction
    }

    /**
     * 解析模型输出的操作行（`动作 | 分类 | 内容`）。
     *
     * 容错取向：动作与分类必须能识别，识别不了的整行丢弃；delete 的原文允许带/不带 `- ` 前缀。
     * 最多返回 [MAX_ITEMS] 条，避免一次改写吃掉整个记忆预算。
     */
    internal fun parseActions(raw: String): List<MemoryAction> {
        val cleaned = cleanReviewOutput(raw)
        if (cleaned.isEmpty()) return emptyList()
        val actions = mutableListOf<MemoryAction>()
        cleaned.lineSequence().forEach { line ->
            if (actions.size >= MAX_ITEMS) return@forEach
            val trimmed = line.trim().trimStart('-', '*', '+').trim()
            if (trimmed.isEmpty() || !trimmed.contains('|')) return@forEach
            val parts = trimmed.split('|').map { it.trim() }
            if (parts.size < 3) return@forEach
            val verb = parts[0].lowercase(Locale.ROOT)
            val category = categoryOf(parts[1])
            if (category == null) {
                LocalLogger.i(TAG, "记忆操作被忽略：分类不在标准范围（${parts[1].take(30)}）")
                return@forEach
            }
            // 内容里可能本来就有 `|`，后面几段要重新拼回去。
            val content = parts.drop(2).joinToString("|")
                .replace(ITEM_MARKER, "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_ITEM_CHARS)
            if (content.length < MIN_ITEM_CHARS) return@forEach
            when {
                verb.startsWith("add") || verb.startsWith("新增") -> {
                    if (!TRANSIENT_PATTERN.containsMatchIn(content)) {
                        actions.add(MemoryAction.Add(category, content))
                    }
                }
                verb.startsWith("replace") || verb.startsWith("update") || verb.startsWith("改写") ->
                    actions.add(MemoryAction.Replace(category, content, target = null))
                verb.startsWith("delete") || verb.startsWith("remove") || verb.startsWith("删除") ->
                    actions.add(MemoryAction.Delete(category, content))
                else -> LocalLogger.i(TAG, "记忆操作被忽略：动作无法识别（${parts[0].take(20)}）")
            }
        }
        return actions
    }

    /**
     * 应用结果：改写后的全文 + 各动作实际生效的条数。
     *
     * 条数用于会话内的「已自动记忆 N 条」提示，也让日志能看出模型的动作是否真的落地。
     */
    internal data class ApplyResult(
        val content: String,
        val added: Int = 0,
        val replaced: Int = 0,
        val deleted: Int = 0
    ) {
        val changedItems: Int get() = added + replaced + deleted
    }

    /**
     * 把审查得到的操作应用到既有记忆上。
     *
     * 与旧的「同分类整段覆盖」不同：这里按**单条要点**增删改，其余条目原样保留，
     * 因此用户手工写的、以及模型之前记下的无关内容都不会被一次抽取抹掉。
     *
     * 安全约束（模型输出不可信）：
     * - delete 只删除确实存在的条目（按文本全等匹配），凭空删除会被忽略；
     * - replace 找不到目标时退化为「改写本分类第一条」，而不是整段覆盖；
     * - 被改动到的分类统一写成标准小节标题（旧记忆里的 `# 偏好` 这类别名不再保留），
     *   空掉的小节整节移除，未被改动的自定义小节原样保留。
     */
    internal fun applyActions(existing: String, actions: List<MemoryAction>): ApplyResult {
        if (actions.isEmpty()) return ApplyResult(existing, 0, 0, 0)

        // 既有记忆按小节拆开：每个小节记录标题、归属分类、要点列表，并保留原始顺序。
        val sections = linkedMapOf<String, MutableList<String>>()
        val order = mutableListOf<String>()
        val headingOf = mutableMapOf<String, Category?>()
        splitSections(existing).forEach { (title, body) ->
            val key = title.trim()
            if (key !in sections) {
                sections[key] = mutableListOf()
                order.add(key)
                headingOf[key] = categoryOf(title)
            }
            memoryItems(body).forEach { if (it !in sections.getValue(key)) sections.getValue(key).add(it) }
        }

        /** 某个分类对应的小节键：优先复用既有小节（含别名标题），没有则新建标准小节。 */
        fun keyFor(category: Category): String {
            order.firstOrNull { headingOf[it] == category }?.let { return it }
            val key = "## ${category.heading()}"
            if (key !in sections) {
                sections[key] = mutableListOf()
                order.add(key)
                headingOf[key] = category
            }
            return key
        }

        var added = 0
        var replaced = 0
        var deleted = 0
        for (action in actions) {
            when (action) {
                is MemoryAction.Add -> {
                    val items = sections.getValue(keyFor(action.category))
                    if (items.none { it.equals(action.content, ignoreCase = true) }) {
                        items.add(action.content)
                        added++
                    }
                }
                is MemoryAction.Replace -> {
                    val items = sections.getValue(keyFor(action.category))
                    if (items.isEmpty()) {
                        items.add(action.content)
                        added++
                    } else {
                        // 优先按模型指认的旧原文定位；没有目标时改写本分类第一条。
                        val index = if (action.target != null) {
                            items.indexOfFirst { it.equals(action.target, ignoreCase = true) }
                        } else {
                            -1
                        }
                        val target = if (index >= 0) index else 0
                        if (!items[target].equals(action.content, ignoreCase = true)) {
                            items[target] = action.content
                            replaced++
                        }
                    }
                }
                is MemoryAction.Delete -> {
                    // 只在既有条目里删：模型凭空给出一条不存在的原文时不做任何事。
                    val removed = order.asSequence()
                        .map { sections.getValue(it) }
                        .any { items ->
                            val hit = items.removeAll { it.equals(action.content, ignoreCase = true) }
                            if (hit) deleted++
                            hit
                        }
                    if (!removed) {
                        LocalLogger.i(TAG, "删除操作未命中任何既有条目，已忽略")
                    }
                }
            }
        }

        // 被改动过的分类：无论原来用什么别名标题，都统一按标准标题渲染，
        // 且同名分类只能出现一次（否则会留下两个同类小节）。
        val touched = actions.map { it.category }.distinct()
        val rendered = StringBuilder()
        val emitted = mutableSetOf<Category>()
        // 第一遍：按原始顺序渲染，被改动过的分类在它原本的位置上换成标准标题。
        for (key in order) {
            val items = sections.getValue(key)
            val category = headingOf[key]
            if (category != null && category in touched) {
                if (!emitted.add(category)) continue
                if (items.isEmpty()) continue
                rendered.appendLine("## ${category.heading()}")
                items.forEach { rendered.appendLine("- $it") }
                rendered.appendLine()
            } else {
                if (items.isEmpty()) continue
                if (key.isNotEmpty()) rendered.appendLine(key)
                items.forEach { rendered.appendLine("- $it") }
                rendered.appendLine()
            }
        }

        val content = rendered.toString().trim().take(MAX_MEMORY_CHARS)
        return ApplyResult(content, added, replaced, deleted)
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
 * Agent 长期记忆抽取阶段。
 *
 * RUNNING：后台抽取已启动（界面显示"正在整理长期记忆"）；
 * DONE：抽取结束（`changedItems` 为 0 表示本轮没有写入任何内容）。
 */
enum class AgentMemoryPhase { RUNNING, DONE }

/**
 * 长期记忆抽取结果通知：界面据此提示"正在整理 / 已自动记忆 N 条"。
 *
 * 与 [AgentSkillNotice] 同形态，让用户在会话里能直接看到后台自动写了什么，
 * 而不是记忆被静默改写。
 */
data class AgentMemoryNotice(
    val sessionId: String,
    val changedItems: Int,
    val phase: AgentMemoryPhase = AgentMemoryPhase.DONE
)

/**
 * Agent 长期记忆写入器：读取当前记忆 → 交给模型审查 → 按模型给出的增/改/删操作改写。
 *
 * 与 [SessionNameGenerator] 同构：优先走故障转移队列，其次回退当前激活模型；
 * token 计入 `agent_memory` 来源；同一会话同时只跑一个抽取任务。
 *
 * 关键点：模型看到的是**当前记忆全文**，因此它能判断"这条已经记过了""这条过时了该改"
 * "这条被用户否定了该删"，而不是盲目追加。
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

    /** @return 真正生效的改动条数；0 表示本轮没有值得长期记忆的内容或调用失败。 */
    suspend fun extractAndAppend(
        sessionId: String,
        userMessage: String,
        assistantMessage: String
    ): Int {
        if (!AgentMemoryExtractor.shouldExtract(userMessage, assistantMessage)) return 0
        if (inProgress.putIfAbsent(sessionId, true) != null) return 0
        return try {
            val existing = runCatching { readMemory() }.getOrDefault("")
            val promptMessages = listOf(
                mapOf("role" to "system", "content" to AgentMemoryExtractor.systemPrompt()),
                mapOf(
                    "role" to "user",
                    "content" to AgentMemoryExtractor.buildExtractionPrompt(
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        existingMemory = existing
                    )
                )
            )
            val fallbackModel = aiModelProvider?.invoke()
            if (failoverExecutor == null && fallbackModel == null) return 0
            val execution = failoverExecutor?.let { executor ->
                runCatching { executor.execute(promptMessages) }
                    .onFailure { LocalLogger.w(TAG, "记忆抽取队列不可用，回退激活模型: ${it.message}") }
                    .getOrNull()
            }
            val result = execution?.value
                ?: fallbackModel?.let { aiClient.chatOnce(it, promptMessages) }
                ?: return 0
            if (result.error != null) {
                LocalLogger.w(TAG, "记忆抽取失败: ${result.error}")
                return 0
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
            val actions = AgentMemoryExtractor.parseActions(result.content)
            if (actions.isEmpty()) {
                LocalLogger.i(TAG, "本轮没有符合标准的长期记忆改动（NONE 或输出不可解析）")
                return 0
            }
            // 重新读一次再落库：模型调用期间用户可能刚手工改过记忆，
            // 用调用前的快照做基准会把那次修改覆盖掉。
            val base = runCatching { readMemory() }.getOrDefault(existing)
            val applied = AgentMemoryExtractor.applyActions(base, actions)
            if (applied.changedItems <= 0) {
                LocalLogger.i(TAG, "模型给出的操作未改变任何内容，跳过写入")
                return 0
            }
            writeMemory(applied.content)
            LocalLogger.i(
                TAG,
                "已更新 Agent 长期记忆: 新增 ${applied.added}、改写 ${applied.replaced}、删除 ${applied.deleted}"
            )
            applied.changedItems
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalLogger.w(TAG, "记忆抽取异常（不影响主流程）: ${e.message}", e)
            0
        } finally {
            inProgress.remove(sessionId)
        }
    }
}
