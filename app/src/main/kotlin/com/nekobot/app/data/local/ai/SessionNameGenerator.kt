package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话名称自动生成器，对应原仓库 nbot/web/ai_service.py:_try_auto_name_session
 * 与 nbot/web/server.py:_generate_session_name。
 *
 * 触发规则：
 * - 默认名称（"新会话"/"新对话"等）+ 至少 2 条 user/assistant 消息 → 首次命名
 * - 已自动命名 + 累积 10 条新消息 → 重新命名（追踪最新话题）
 *
 * 每个会话维护 _naming_in_progress 防并发；命名结果通过 onRenamed 回调通知 UI。
 *
 * @param aiClient 本地 AI 客户端
 * @param aiModelProvider 提供命名用 AI 模型（与 chat 共用 active 模型）
 * @param onTokenUsage 二级 LLM token 记账回调
 *        参数：source, model（配置名）, actualModel（实际模型标识，用于排行榜聚合）, inputTokens, outputTokens
 */
class SessionNameGenerator(
    private val aiClient: LocalAiClient,
    private val aiModelProvider: (suspend () -> LocalAiModelEntity?)? = null,
    private val onTokenUsage: ((String, String, String, Int, Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "SessionNameGen"
        /** 首次命名最少消息数（user + assistant 总数） */
        private const val MIN_MESSAGES_FOR_FIRST_NAMING = 2
        /** 已自动命名后，每多少条新消息触发重新命名 */
        private const val RE_NAME_INTERVAL = 10
        /** 默认名称前缀（用于检测是否需要首次命名） */
        private val DEFAULT_NAME_PREFIXES = listOf(
            "新会话", "新对话", "Web 会话", "Agent 会话", "群聊",
            "New session", "New conversation", "Web session", "Agent chat", "Group chat",
            "新しい会話", "新規会話", "エージェント会話", "グループ会話",
            "새 대화", "새 세션", "에이전트 대화", "그룹 대화"
        )
        /** 默认名称后缀（用于检测是否需要首次命名） */
        private val DEFAULT_NAME_SUFFIXES = listOf("的对话")
    }

    /** 各会话的命名状态：sessionId → NamingState */
    private val states = ConcurrentHashMap<String, NamingState>()

    /** 各会话命名任务的进行中标志，防止并发重复生成 */
    private val inProgress = ConcurrentHashMap<String, Boolean>()

    data class NamingState(
        /** 是否已自动命名过 */
        val autoNamed: Boolean = false,
        /** 上次命名时的消息总数 */
        val lastRenameCount: Int = 0
    )

    /**
     * 检查并触发会话自动命名（必要时）。
     *
     * @param session 当前会话实体
     * @param messages 会话所有消息（按时间顺序）
     * @param characterName 角色名（可选，用于 prompt 上下文）
     * @param characterDescription 角色描述（可选，用于 prompt 上下文）
     * @return 新名称（未触发或无产出时为 null）
     */
    suspend fun tryAutoName(
        session: LocalSessionEntity,
        messages: List<LocalMessageEntity>,
        characterName: String = "",
        characterDescription: String = ""
    ): String? {
        val sessionId = session.id

        // 仅处理 user/assistant 消息
        val userAssistantMsgs = messages.filter { it.role == "user" || it.role == "assistant" }
        val totalCount = userAssistantMsgs.size
        if (totalCount < MIN_MESSAGES_FOR_FIRST_NAMING) return null

        val name = session.name
        val isDefaultName = name.isBlank() ||
            DEFAULT_NAME_PREFIXES.any { name.startsWith(it) } ||
            DEFAULT_NAME_SUFFIXES.any { name.endsWith(it) }

        val state = states[sessionId] ?: NamingState()
        val shouldRename = isDefaultName ||
            (state.autoNamed && totalCount - state.lastRenameCount >= RE_NAME_INTERVAL)

        if (!shouldRename) return null

        // 防并发
        if (inProgress[sessionId] == true) return null
        inProgress[sessionId] = true

        try {
            val newName = generateName(
                messages = userAssistantMsgs.takeLast(10),
                characterName = characterName,
                characterDescription = characterDescription,
                isUpdate = totalCount > 6
            ) ?: return null

            if (newName.length !in 2..15) return null

            states[sessionId] = NamingState(
                autoNamed = true,
                lastRenameCount = totalCount
            )
            return newName
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "会话自动命名失败: ${e.message}", e)
            return null
        } finally {
            inProgress[sessionId] = false
        }
    }

    /** 调用 LLM 生成会话名称 */
    private suspend fun generateName(
        messages: List<LocalMessageEntity>,
        characterName: String,
        characterDescription: String,
        isUpdate: Boolean
    ): String? {
        val model = aiModelProvider?.invoke() ?: return null

        val roleContext = if (characterName.isNotBlank()) {
            val desc = characterDescription.take(100)
            if (desc.isNotBlank()) "当前角色是'$characterName'（$desc）。"
            else "当前角色是'$characterName'。"
        } else ""

        val updateHint = if (isUpdate) {
            "对话已经进行了较长时间，请根据最新的主要话题重新命名，忽略早期已结束的话题。\n"
        } else ""

        val systemPrompt = buildString {
            append("你是一个会话命名助手。")
            append(roleContext)
            append("请根据对话内容生成一个简短、有辨识度、贴合当前话题的标题。\n\n")
            append(updateHint)
            append("要求：\n")
            append("- 2-15个字\n")
            append("- 概括当前主要话题或最新亮点\n")
            append("- 自然口语化，像聊天记录名称\n")
            append("- 有趣或有诗意更好\n")
            append("- 直接返回标题，不要引号、标点或解释")
        }

        val conversationText = messages.takeLast(12).joinToString("\n") { msg ->
            val role = if (msg.role == "user") "用户" else "角色"
            val content = msg.content.take(200)
            "$role: $content"
        }

        val promptMessages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to "请为以下对话生成标题：\n\n$conversationText")
        )

        val result = aiClient.chatOnce(model, promptMessages)
        // 记账二级 LLM 调用 token（source=session_name）
        if (result.usage.isNotEmpty()) {
            onTokenUsage?.invoke(
                "session_name", model.name, model.model,
                result.usage["prompt"] ?: 0,
                result.usage["completion"] ?: 0
            )
        }
        if (result.error != null || result.content.isBlank()) return null

        return cleanName(result.content)
    }

    /** 清理 LLM 返回的名称：去除引号/标点/前缀/换行 */
    private fun cleanName(raw: String): String? {
        var name = raw.trim().trim('"', '\'', '「', '」', '『', '』', '【', '】', '(', ')', '（', '）')
        name = name.split("\n", "\r").firstOrNull()?.trim() ?: ""
        // 去除常见前缀
        for (prefix in listOf("标题:", "标题：", "会话标题:", "会话标题：", "Title:", "title:")) {
            if (name.startsWith(prefix)) {
                name = name.removePrefix(prefix).trim()
                break
            }
        }
        name = name.trim('`', '*', '_', '#', ' ', '\t', '\r', '\n', '"', '\'',
            '[', ']', '(', ')', '{', '}', '<', '>', ':', '：', '-', '—',
            ',', '，', '.', '。', '!', '！', '?', '？')
        // 与原仓库 server.py 保持一致：模型偶尔无视“15 字以内”的提示，
        // 仍应截断为可用标题，不能直接丢弃导致会话一直叫“新会话”。
        if (name.length > 15) {
            name = name.take(15).trimEnd(
                '`', '*', '_', '#', ' ', '\t', '\r', '\n', '"', '\'',
                '[', ']', '(', ')', '{', '}', '<', '>', ':', '：', '-', '—',
                ',', '，', '.', '。', '!', '！', '?', '？'
            )
        }
        return name.ifBlank { null }
    }
}
