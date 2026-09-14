package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import java.util.concurrent.ConcurrentHashMap

private const val MIN_MESSAGES_FOR_FIRST_NAMING = 2
private const val RE_NAME_INTERVAL = 10

internal data class SessionNamingState(
    val autoNamed: Boolean = false,
    val lastRenameCount: Int = 0
)

/**
 * 会话命名风格。两种会话对"标题"的期待完全不同：
 * - 角色 / 群聊会话：标题本身也是内容的一部分，要诗情画意、优雅耐看；
 * - Agent 会话：标题是任务索引，只需一句话说清"这件事是什么"。
 */
internal enum class SessionNamingStyle(
    /** 期望字数（写进提示词，模型偶有超出） */
    val preferredLength: String,
    /** 硬性字数上限，超出后截断 */
    val maxLength: Int
) {
    ROLEPLAY(preferredLength = "4-10", maxLength = 12),
    AGENT(preferredLength = "4-12", maxLength = 12);

    companion object {
        /** 仅 Agent 会话使用简明概括风格，其余（角色 / 群聊 / 未知）都按角色风格处理。 */
        fun of(sessionMode: String?): SessionNamingStyle =
            if (sessionMode.equals("agent", ignoreCase = true)) AGENT else ROLEPLAY
    }
}

/**
 * 角色会话的题材底色。同样是"好听"，古风玄幻要典雅如诗，二次元都市要清新有生活感，
 * 用错腔调会让标题和故事完全脱节。
 */
internal enum class RoleplayTheme {
    /** 古风 / 仙侠 / 玄幻 / 武侠 / 历史：走诗情画意的路子 */
    CLASSICAL,

    /** 二次元 / 都市 / 现代 / 校园 / 科幻：清新灵动，有画面与生活感 */
    MODERN,

    /** 角色卡没有给出可判定的题材信号：交给模型按对话气质自行选择 */
    UNKNOWN;

    companion object {
        /** 古风 / 玄幻侧关键词（≥2 字为主，避免"魔""妖"等在二次元语境同样常见的单字误判） */
        private val CLASSICAL_KEYWORDS = listOf(
            "古风", "古代", "古典", "仙侠", "修仙", "修真", "玄幻", "武侠", "江湖", "仙门",
            "仙尊", "上仙", "仙子", "仙人", "妖族", "仙族", "魔道", "魔尊", "神话", "洪荒",
            "封神", "聊斋", "志怪", "宫廷", "后宫", "朝堂", "王朝", "帝王", "皇帝", "皇后",
            "贵妃", "王爷", "将军", "丞相", "世家", "门阀", "科举", "书院", "剑客", "刀客",
            "侠客", "侠女", "道侣", "灵根", "法器", "渡劫", "飞升", "丹药", "秘境", "宗门",
            "长老", "师尊", "师门", "历史", "三国", "唐宋", "明清", "诗词", "汉服", "东方奇幻",
            "江湖恩怨", "权谋", "仙", "侠",
            "ancient", "historical", "wuxia", "xianxia", "cultivation", "immortal",
            "medieval", "dynasty", "imperial", "palace", "jianghu", "taoist", "martial"
        )

        /** 二次元 / 都市 / 现代侧关键词 */
        private val MODERN_KEYWORDS = listOf(
            "二次元", "动漫", "动画", "漫画", "轻小说", "校园", "学园", "高中生", "大学生",
            "同学", "学姐", "学妹", "学长", "青梅竹马", "都市", "现代", "日常", "恋爱", "青春",
            "偶像", "娱乐圈", "明星", "经纪", "咖啡", "甜品", "便利店", "上班", "职场", "公司",
            "社畜", "警察", "医生", "护士", "律师", "侦探", "推理", "悬疑", "科幻", "赛博",
            "机甲", "末世", "丧尸", "末日", "系统", "网游", "乐队", "摄影", "猫娘", "女仆",
            "管家", "魔法少女", "异能", "废土", "直播", "学生", "校服", "制服", "公寓",
            "anime", "manga", "school", "campus", "modern", "urban", "cyberpunk",
            "sci-fi", "scifi", "idol", "romance", "office", "student", "contemporary"
        )

        /**
         * 依据角色卡元数据判定题材。
         *
         * 标签是角色卡对自身门类的声明，最可靠：只要标签能给出判定就直接采用。
         * 标签沉默或自相矛盾时，才退回描述 / 人设 / 场景等文本信号。
         *
         * @param tags 角色卡标签（JSON 数组或逗号分隔文本，宽松匹配即可）
         * @param texts 其他可用于判定的文本（名称、描述、场景、人设等）
         */
        fun classify(tags: String? = null, vararg texts: String?): RoleplayTheme {
            verdict(
                hitCount(tags, CLASSICAL_KEYWORDS),
                hitCount(tags, MODERN_KEYWORDS)
            )?.let { return it }

            var classical = 0
            var modern = 0
            texts.forEach { text ->
                classical += hitCount(text, CLASSICAL_KEYWORDS)
                modern += hitCount(text, MODERN_KEYWORDS)
            }
            return verdict(classical, modern) ?: UNKNOWN
        }

        private fun verdict(classical: Int, modern: Int): RoleplayTheme? = when {
            classical > modern -> CLASSICAL
            modern > classical -> MODERN
            else -> null
        }

        /** 命中多少个不同关键词（同一关键词反复出现只算一次，避免长描述压制标签） */
        private fun hitCount(text: String?, keywords: List<String>): Int {
            if (text.isNullOrBlank()) return 0
            val lower = text.lowercase()
            return keywords.count { lower.contains(it) }
        }
    }
}

/**
 * 构造会话命名用的 system prompt。抽成纯函数以便单测，不依赖 Android 运行时。
 *
 * @param style 命名风格（角色 / Agent）
 * @param theme 角色会话的题材底色，仅 [SessionNamingStyle.ROLEPLAY] 生效
 * @param roleContext 角色上下文描述，可为空
 * @param isUpdate 是否为"对话进行中"的重新命名
 * @param languageDirective 输出语言约束（见 [AiOutputLanguage.directive]）
 */
internal fun buildSessionNamingPrompt(
    style: SessionNamingStyle,
    theme: RoleplayTheme,
    roleContext: String,
    isUpdate: Boolean,
    languageDirective: String
): String = buildString {
    append("你是一个会话命名助手。")
    append(roleContext)
    when (style) {
        SessionNamingStyle.ROLEPLAY -> {
            append("请为这段角色扮演对话取一个标题。\n\n")
            if (isUpdate) {
                append("对话已经进行了较长时间，请依据最新的场景与情绪重新取名，忽略早期已经结束的段落。\n\n")
            }
            append("要求：\n")
            append("- ${style.preferredLength}个字\n")
            when (theme) {
                RoleplayTheme.CLASSICAL -> {
                    append("- 典雅而富有诗意，像诗集篇目或章回小说的回目，适合直接当作标题观赏\n")
                    append("- 用意象、景致或情绪点题，让人一眼看见这段故事的氛围\n")
                }
                RoleplayTheme.MODERN -> {
                    append("- 清新灵动、有画面感，像轻小说章节名或手帐里的一行小标题\n")
                    append("- 从当下的场景、心情或一个小细节落笔，带一点生活气息\n")
                    append("- 不要堆砌古风辞藻，不要写成诗句\n")
                }
                RoleplayTheme.UNKNOWN -> {
                    append("- 贴合题材气质：古风玄幻可典雅如诗，二次元都市则清新灵动、有生活感\n")
                    append("- 用意象、场景或情绪点题，让人一眼看见这段故事的氛围\n")
                }
            }
            append("- 不要直白复述剧情，不要出现\"对话\"\"聊天\"\"会话\"等字样\n")
            append("- 直接返回标题，不要引号、书名号、标点或解释")
        }
        SessionNamingStyle.AGENT -> {
            append("请为这段 Agent 任务对话取一个标题。\n\n")
            if (isUpdate) {
                append("任务已经进行了较长时间，请依据最新的任务目标重新概括，忽略已经完成的早期步骤。\n\n")
            }
            append("要求：\n")
            append("- ${style.preferredLength}个字\n")
            append("- 只做整体概括，一句话点明这件事的主题即可\n")
            append("- 不要描述细节：不罗列文件名、命令、工具名、参数、数值、步骤\n")
            append("- 不要出现\"对话\"\"聊天\"\"会话\"等字样\n")
            append("- 直接返回标题，不要引号、书名号、标点或解释")
        }
    }
    append("\n\n")
    append(languageDirective)
}

/**
 * 旧版本没有持久化命名状态时，按“首次 2 条、之后每 10 条”恢复最近一次触发点。
 */
internal fun recoverSessionNamingState(
    isDefaultName: Boolean,
    totalCount: Int
): SessionNamingState {
    if (isDefaultName) return SessionNamingState()
    val lastRenameCount = when {
        totalCount <= MIN_MESSAGES_FOR_FIRST_NAMING -> MIN_MESSAGES_FOR_FIRST_NAMING
        else -> MIN_MESSAGES_FOR_FIRST_NAMING +
            ((totalCount - MIN_MESSAGES_FOR_FIRST_NAMING - 1) / RE_NAME_INTERVAL) * RE_NAME_INTERVAL
    }
    return SessionNamingState(autoNamed = true, lastRenameCount = lastRenameCount)
}

internal fun shouldAutoRenameSession(
    isDefaultName: Boolean,
    totalCount: Int,
    state: SessionNamingState
): Boolean = totalCount >= MIN_MESSAGES_FOR_FIRST_NAMING &&
    (
        isDefaultName ||
            (state.autoNamed && totalCount - state.lastRenameCount >= RE_NAME_INTERVAL)
        )

internal fun isDefaultAutoNamingSessionName(name: String): Boolean =
    name.isBlank() ||
        SessionNameGenerator.DEFAULT_NAME_PREFIXES.any { name.startsWith(it) } ||
        SessionNameGenerator.DEFAULT_NAME_SUFFIXES.any { name.endsWith(it) }

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
 * 命名风格按会话模式区分（见 [SessionNamingStyle]）：角色 / 群聊会话取诗情画意、优雅耐看的
 * 标题；Agent 会话只做简明整体概括，不落细节。角色会话再按角色卡题材细分腔调
 * （见 [RoleplayTheme]）：古风 / 玄幻走典雅诗意，二次元 / 都市 / 现代走清新生活感。
 *
 * @param aiClient 本地 AI 客户端
 * @param aiModelProvider 提供命名用 AI 模型（与 chat 共用 active 模型）
 * @param onTokenUsage 二级 LLM token 记账回调
 *        参数：source, model（配置名）, actualModel（实际模型标识，用于排行榜聚合）, inputTokens, outputTokens
 */
internal class SessionNameGenerator(
    private val aiClient: LocalAiClient,
    private val aiModelProvider: (suspend () -> LocalAiModelEntity?)? = null,
    private val failoverExecutor: LocalChatFailoverExecutor? = null,
    private val onTokenUsage: ((String, String, String, Int, Int) -> Unit)? = null,
    private val stateLoader: ((String) -> SessionNamingState?)? = null,
    private val stateSaver: ((String, SessionNamingState) -> Unit)? = null
) {
    companion object {
        private const val TAG = "SessionNameGen"
        /** 默认名称前缀（用于检测是否需要首次命名） */
        internal val DEFAULT_NAME_PREFIXES = listOf(
            "新会话", "新对话", "Web 会话", "Agent 会话", "Agent 对话", "群聊",
            "New chat", "New session", "New conversation", "Web session", "Agent chat", "Group chat",
            "新しい会話", "新規会話", "新規チャット", "エージェント会話", "エージェントチャット", "グループ会話",
            "새 대화", "새 세션", "에이전트 대화", "그룹 대화"
        )
        /** 默认名称后缀（用于检测是否需要首次命名） */
        internal val DEFAULT_NAME_SUFFIXES = listOf("的对话")
    }

    /** 各会话的命名状态：sessionId → SessionNamingState */
    private val states = ConcurrentHashMap<String, SessionNamingState>()

    /** 各会话命名任务的进行中标志，防止并发重复生成 */
    private val inProgress = ConcurrentHashMap<String, Boolean>()

    /**
     * 检查并触发会话自动命名（必要时）。
     *
     * @param session 当前会话实体
     * @param messages 会话所有消息（按时间顺序）
     * @param characterName 角色名（可选，用于 prompt 上下文）
     * @param characterDescription 角色描述（可选，用于 prompt 上下文）
     * @param characterTags 角色卡标签（可选，用于判定古风 / 现代题材）
     * @param characterScenario 角色卡场景设定（可选，题材判定的辅助信号）
     * @return 新名称（未触发或无产出时为 null）
     */
    suspend fun tryAutoName(
        session: LocalSessionEntity,
        messages: List<LocalMessageEntity>,
        characterName: String = "",
        characterDescription: String = "",
        characterTags: String = "",
        characterScenario: String = ""
    ): String? {
        val sessionId = session.id

        // 仅处理 user/assistant 消息
        val userAssistantMsgs = messages.filter { it.role == "user" || it.role == "assistant" }
        val totalCount = userAssistantMsgs.size
        if (totalCount < MIN_MESSAGES_FOR_FIRST_NAMING) return null

        val name = session.name
        val isDefaultName = isDefaultAutoNamingSessionName(name)

        val state = states[sessionId]
            ?: runCatching { stateLoader?.invoke(sessionId) }.getOrNull()
            ?: recoverSessionNamingState(isDefaultName, totalCount)
        states.putIfAbsent(sessionId, state)
        val shouldRename = shouldAutoRenameSession(isDefaultName, totalCount, state)

        if (!shouldRename) return null

        // 防并发
        if (inProgress[sessionId] == true) return null
        inProgress[sessionId] = true

        // 角色会话求诗意，Agent 会话求概括：风格由会话模式决定。
        val style = SessionNamingStyle.of(session.sessionMode)
        // 角色会话再按题材细分腔调：古风玄幻走典雅诗意，二次元都市走清新生活感。
        val theme = if (style == SessionNamingStyle.ROLEPLAY) {
            RoleplayTheme.classify(
                tags = characterTags.ifBlank { session.tags },
                characterName,
                characterDescription,
                characterScenario
            )
        } else {
            RoleplayTheme.UNKNOWN
        }

        try {
            val newName = generateName(
                messages = userAssistantMsgs.takeLast(10),
                characterName = characterName,
                characterDescription = characterDescription,
                isUpdate = totalCount > 6,
                style = style,
                theme = theme
            ) ?: return null

            if (newName.length !in 2..style.maxLength) return null

            val nextState = SessionNamingState(
                autoNamed = true,
                lastRenameCount = totalCount
            )
            states[sessionId] = nextState
            runCatching { stateSaver?.invoke(sessionId, nextState) }
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
        isUpdate: Boolean,
        style: SessionNamingStyle,
        theme: RoleplayTheme
    ): String? {
        val fallbackModel = aiModelProvider?.invoke()
        if (failoverExecutor == null && fallbackModel == null) return null

        // 角色上下文只对角色会话有意义；Agent 会话即使残留角色名也不该让它带偏标题。
        val roleContext = if (style == SessionNamingStyle.ROLEPLAY && characterName.isNotBlank()) {
            val desc = characterDescription.take(100)
            if (desc.isNotBlank()) "当前角色是'$characterName'（$desc）。"
            else "当前角色是'$characterName'。"
        } else ""

        val systemPrompt = buildSessionNamingPrompt(
            style = style,
            theme = theme,
            roleContext = roleContext,
            isUpdate = isUpdate,
            // 输出语言跟随应用当前设置的语言
            languageDirective = AiOutputLanguage.directive()
        )

        // Agent 会话里助手侧常混着工具输出，截断得更短一些，避免标题被细节带偏。
        val contentLimit = if (style == SessionNamingStyle.AGENT) 120 else 200
        val conversationText = messages.takeLast(10).joinToString("\n") { msg ->
            val role = if (msg.role == "user") "用户" else "角色"
            val content = msg.content.take(contentLimit)
            "$role: $content"
        }

        val promptMessages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to "请为以下对话生成标题：\n\n$conversationText")
        )

        // 聊天主请求允许直接回退到 active 模型；会话命名也必须保持同一语义。
        // 部分旧配置只有 active 模型、没有 purpose=chat 队列，不能因此静默丢失首次命名。
        val execution = failoverExecutor?.let { executor ->
            runCatching { executor.execute(promptMessages) }
                .onFailure {
                    com.nekobot.app.data.local.LocalLogger.w(
                        TAG,
                        "命名故障转移队列不可用，回退到当前激活模型: ${it.message}"
                    )
                }
                .getOrNull()
        }
        val result = execution?.value
            ?: fallbackModel?.let { aiClient.chatOnce(it, promptMessages) }
            ?: return null
        val usedModel = execution?.model ?: fallbackModel ?: return null
        // 记账二级 LLM 调用 token（source=session_name）
        if (result.usage.isNotEmpty()) {
            onTokenUsage?.invoke(
                "session_name", usedModel.name, usedModel.model,
                result.usage["prompt"] ?: 0,
                result.usage["completion"] ?: 0
            )
        }
        if (result.error != null || result.content.isBlank()) return null

        return cleanName(result.content, style.maxLength)
    }

    /** 清理 LLM 返回的名称：去除引号/标点/前缀/换行，并按风格上限截断 */
    private fun cleanName(raw: String, maxLength: Int = 15): String? {
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
        // 与原仓库 server.py 保持一致：模型偶尔无视字数提示，
        // 仍应截断为可用标题，不能直接丢弃导致会话一直叫"新会话"。
        if (name.length > maxLength) {
            name = name.take(maxLength).trimEnd(
                '`', '*', '_', '#', ' ', '\t', '\r', '\n', '"', '\'',
                '[', ']', '(', ')', '{', '}', '<', '>', ':', '：', '-', '—',
                ',', '，', '.', '。', '!', '！', '?', '？'
            )
        }
        return name.ifBlank { null }
    }
}
