package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser

/**
 * 新 Agent 会话欢迎页的起步候选。
 *
 * 候选由当前激活模型根据全局 Agent 长期记忆生成：优先覆盖记忆里正在推进的事、
 * 稳定偏好、常用环境与长期约定。模型不可用或返回不合法时，由界面回退到本地默认候选。
 */
internal object AgentWelcomeSuggestions {

    /** 欢迎页固定展示 4 条候选。 */
    const val COUNT = 4

    /** 单条候选的字符上限，避免模型输出整段任务说明。 */
    const val MAX_CHARS = 60

    /** 提示词里最多带多少字符的记忆，避免一次性补全吃掉过多上下文。 */
    private const val MAX_MEMORY_CHARS = 6_000

    private val ITEM_MARKER = Regex("^\\s*(?:[-*+•]|\\d+[.)、])\\s*")

    /** 生成候选用的 system 提示词。 */
    fun buildSystemPrompt(): String = buildString {
        appendLine("你是 Agent 会话的起步建议生成器。用户刚打开一个新的 Agent 会话，请结合用户维护的全局长期记忆，")
        appendLine("给出 ${COUNT} 条可以直接发送的起步指令。")
        appendLine()
        appendLine("要求：")
        appendLine("- 每条 8~28 个字，是完整的祈使句，具体、可执行；")
        appendLine("- 四条角度互不重复：优先从记忆里正在推进的事、稳定偏好、常用环境与工具、长期约定中取材；")
        appendLine("- 记忆只是背景：不要复述记忆原文，不要出现「根据记忆」「长期记忆」等字样，不要编造记忆中没有的项目或事实；")
        appendLine("- 记忆不足以支持具体任务时，给出通用的高效起步指令；")
        appendLine("- 不要编号、不要解释、不要引号；")
        appendLine("- 只输出 JSON 字符串数组，例如 [\"...\",\"...\",\"...\",\"...\"]。")
        appendLine()
        append(AiOutputLanguage.directive())
    }

    /** 生成候选用的 user 提示词；[memory] 是全局 Agent 记忆全文。 */
    fun buildUserPrompt(memory: String): String = buildString {
        val trimmed = memory.trim()
        if (trimmed.isEmpty()) {
            appendLine("用户还没有维护任何全局记忆。请给出 ${COUNT} 条通用但具体的起步指令。")
        } else {
            appendLine("以下是用户的全局长期记忆（可能较长，只截取开头部分）：")
            appendLine("<memory>")
            appendLine(trimmed.take(MAX_MEMORY_CHARS))
            appendLine("</memory>")
            appendLine()
            appendLine("请据此给出 ${COUNT} 条最值得在这个新会话里开始的请求。")
        }
    }

    /** 解析模型输出：优先 JSON 数组，其次逐行文本；去重后最多 [COUNT] 条。 */
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val jsonItems = parseJsonItems(raw)
        val source = jsonItems.ifEmpty {
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
        return source
            .map(::cleanItem)
            .filter { it.length in 2..MAX_CHARS }
            .distinct()
            .take(COUNT)
    }

    /** 把候选编码成可持久化的一行一条文本。 */
    fun encode(items: List<String>): String = items
        .map(::cleanItem)
        .filter { it.isNotBlank() }
        .take(COUNT)
        .joinToString("\n") { it.replace('\n', ' ') }

    /** 读取 [encode] 写入的缓存；未命中或格式不对时返回空列表。 */
    fun decode(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map(::cleanItem)
            .filter { it.length in 2..MAX_CHARS }
            .distinct()
            .take(COUNT)
            .toList()
    }

    /** 从模型输出里截取 JSON 数组；也接受 `{"suggestions": [...]}` 这类包了一层的输出。 */
    private fun parseJsonItems(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start >= 0 && end > start) {
            runCatching {
                val array = JsonParser.parseString(raw.substring(start, end + 1)).asJsonArray
                return array.mapNotNull { element ->
                    element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }
            }
        }
        runCatching {
            val obj = JsonParser.parseString(raw.trim()).asJsonObject
            for (key in listOf("suggestions", "items", "prompts")) {
                val array = obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                return array.mapNotNull { element ->
                    element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }
            }
        }
        return emptyList()
    }

    /** 去掉列表符号、编号、引号与多余空白，并截断到可展示长度。 */
    private fun cleanItem(raw: String): String = raw
        .trim()
        .removeSurrounding("`")
        .replace(ITEM_MARKER, "")
        .trim()
        .trim('"', '\'', '“', '”', '「', '」', '『', '』')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_CHARS)
        .trim()
}
