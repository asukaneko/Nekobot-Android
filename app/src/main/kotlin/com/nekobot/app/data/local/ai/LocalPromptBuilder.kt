package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.isLocalCommandMessage
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity

/**
 * 本地 Prompt 构建器。
 *
 * 对应后端 `nbot/character/prompt_builder.py` + `world_book_injector.py`，仅保留本地模式所需逻辑：
 * 1. 角色卡 systemPrompt + basicInfo + personality + scenario + rules + responseFormat → system 消息
 * 2. 世界书条目按关键词匹配 + constant 条目注入到 system 末尾
 * 3. firstMessage 作为 assistant 首条消息（可选）
 * 4. 历史消息（按 created_at 顺序）
 * 5. 当前用户输入
 *
 * 不实现：MemoryFS / 状态机 / 工具调用 / 群聊跨角色。
 */
object LocalPromptBuilder {

    private val gson = Gson()

    /**
     * 构造发送给 AI 的 messages 列表。
     *
     * @param session 会话实体（含 systemPrompt/firstMessage/characterId）
     * @param character 角色卡（可空，无角色会话）
     * @param history 历史消息（不含当前用户输入）
     * @param userInput 当前用户输入
     * @param worldBookEntries 命中后的世界书条目（已筛选）
     * @return messages 列表，元素 = {role, content}
     */
    fun build(
        session: LocalSessionEntity,
        character: LocalCharacterEntity?,
        history: List<LocalMessageEntity>,
        userInput: String,
        worldBookEntries: List<LocalWorldBookEntryEntity> = emptyList()
    ): List<Map<String, Any>> {
        val messages = mutableListOf<Map<String, Any>>()

        // 1. system 消息
        val systemText = buildSystemText(session, character, worldBookEntries, userInput)
        if (systemText.isNotBlank()) {
            messages.add(mapOf("role" to "system", "content" to systemText))
        }

        // 2. firstMessage 作为 assistant 首条消息（仅当历史为空时插入，避免重复）
        val firstMessage = session.firstMessage
            ?: character?.firstMessage
            ?: character?.greeting
        if (history.isEmpty() && !firstMessage.isNullOrBlank()) {
            messages.add(mapOf("role" to "assistant", "content" to firstMessage))
        }

        // 3. 历史消息
        history.filterNot { it.isLocalCommandMessage() }.forEach { msg ->
            val role = when (msg.role.lowercase()) {
                "user", "human" -> "user"
                "assistant", "ai" -> "assistant"
                else -> return@forEach  // 跳过 system/工具消息
            }
            messages.add(mapOf("role" to role, "content" to msg.content))
        }

        // 4. 当前用户输入
        if (userInput.isNotBlank()) {
            messages.add(mapOf("role" to "user", "content" to userInput))
        }

        return messages
    }

    /**
     * 构造 system 文本：角色卡字段 + 世界书条目。
     */
    private fun buildSystemText(
        session: LocalSessionEntity,
        character: LocalCharacterEntity?,
        worldBookEntries: List<LocalWorldBookEntryEntity>,
        userInput: String
    ): String {
        val parts = mutableListOf<String>()

        // 优先用会话自身的 systemPrompt，其次用角色卡的 systemPrompt
        val systemPrompt = session.systemPrompt?.takeIf { it.isNotBlank() }
            ?: character?.systemPrompt?.takeIf { it.isNotBlank() }
        systemPrompt?.let { parts.add(it) }

        // 自定义提示词（按 order 排序注入）
        parseCustomPrompts(session.customPrompts).forEach { cp ->
            val title = cp.first
            val content = cp.second
            if (content.isNotBlank()) {
                parts.add(if (title.isNotBlank()) "[$title]\n$content" else content)
            }
        }

        // 角色卡补充字段
        if (character != null) {
            character.basicInfo?.takeIf { it.isNotBlank() }?.let { parts.add("【基本信息】\n$it") }
            character.personality?.takeIf { it.isNotBlank() }?.let { parts.add("【性格】\n$it") }
            character.scenario?.takeIf { it.isNotBlank() }?.let { parts.add("【场景】\n$it") }
            character.exampleDialogues?.takeIf { it.isNotBlank() }?.let { parts.add("【对话示例】\n$it") }
            character.responseFormat?.takeIf { it.isNotBlank() }?.let { parts.add("【回复格式】\n$it") }
            parseStringList(character.rules).takeIf { it.isNotEmpty() }?.let {
                parts.add("【规则】\n" + it.joinToString("\n- ", prefix = "- "))
            }
        }

        // 会话 scenario（覆盖角色卡 scenario）
        session.scenario?.takeIf { it.isNotBlank() }?.let {
            // 已在角色卡里加过则跳过
            if (character?.scenario.isNullOrBlank()) parts.add("【场景】\n$it")
        }

        // 世界书：constant 条目直接注入，匹配条目按关键词注入
        if (worldBookEntries.isNotEmpty()) {
            val worldText = buildWorldBookText(worldBookEntries, userInput)
            if (worldText.isNotBlank()) parts.add(worldText)
        }

        return parts.joinToString("\n\n").trim()
    }

    /**
     * 构造世界书注入文本。
     * - constant=true 的条目无条件注入
     * - 其他条目按 keys 在 userInput 中匹配则注入
     * - 按 insertionOrder 排序
     */
    private fun buildWorldBookText(
        entries: List<LocalWorldBookEntryEntity>,
        userInput: String
    ): String {
        val matched = entries.filter { entry ->
            if (!entry.enabled) return@filter false
            if (entry.constant) return@filter true
            val keys = parseStringList(entry.keys)
            if (keys.isEmpty()) return@filter false
            val haystack = if (entry.caseSensitive) userInput else userInput.lowercase()
            keys.any { key ->
                val needle = if (entry.caseSensitive) key else key.lowercase()
                needle.isNotEmpty() && haystack.contains(needle)
            }
        }.sortedBy { it.insertionOrder }

        if (matched.isEmpty()) return ""
        val blocks = matched.mapNotNull { entry ->
            entry.content?.takeIf { it.isNotBlank() }?.let { content ->
                if (entry.comment.isNullOrBlank()) content else "[$entry.comment]\n$content"
            }
        }
        return if (blocks.isEmpty()) "" else "【世界书】\n" + blocks.joinToString("\n---\n")
    }

    /**
     * 解析 JSON 数组字符串为 List<String>，失败返回空列表。
     */
    fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JsonParser.parseString(json)
            if (arr.isJsonArray) {
                arr.asJsonArray.mapNotNull { el ->
                    el.takeIf { !it.isJsonNull }?.asString
                }
            } else emptyList()
        } catch (_: Exception) {
            // 兼容逗号分隔
            json.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /** 把 List<String> 序列化为 JSON 数组字符串（供 Entity 字段存储）。 */
    fun stringifyList(list: List<String>?): String? {
        if (list.isNullOrEmpty()) return null
        return gson.toJson(list)
    }

    /**
     * 解析自定义提示词 JSON 字符串为 (title, content) 列表，按 order 排序。
     * JSON 格式：[{"order":1,"title":"...","content":"..."}, ...]
     */
    fun parseCustomPrompts(json: String?): List<Pair<String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JsonParser.parseString(json)
            if (!arr.isJsonArray) return emptyList()
            arr.asJsonArray.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val order = obj.get("order")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                Triple(order, title, content)
            }.sortedBy { it.first }.map { it.second to it.third }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
