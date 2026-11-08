package com.nekobot.app.data.local

import com.google.gson.JsonParser
import com.nekobot.app.data.model.Session

/** 首页轻聊完成会话绑定后的结果。 */
data class HomeQuickChatBinding(
    val session: Session,
    val created: Boolean,
    /** 绑定的是单角色（companion）会话：关系由独立评估服务维护。 */
    val companion: Boolean
)

/** 首页轻聊/场景卡使用的最近一轮关系与状态快照。 */
data class HomeRelationSnapshot(
    val affection: Int,
    val trust: Int,
    val familiarity: Int,
    val dependency: Int,
    val security: Int,
    val jealousy: Int,
    val mood: String,
    val energy: Int
)

/** 轻聊面板里的剧情选项（只保留展示与发送需要的字段）。 */
data class HomePlotChoice(val id: String, val text: String)

/**
 * 首页轻聊的纯策略。
 *
 * 抽成无 IO 的纯函数，便于单元测试覆盖「单角色会话优先 / 接着上次 / 新场景」与草稿回收。
 */
object HomeQuickChatPolicy {

    enum class Binding {
        /** 角色已有单角色会话：独占规则下只能继续它。 */
        COMPANION,

        /** 绑定该角色最近的一段会话。 */
        EXISTING,

        /** 新建一个场景会话。 */
        NEW
    }

    fun binding(
        preferExisting: Boolean,
        hasCompanionSession: Boolean,
        hasExistingSession: Boolean
    ): Binding = when {
        hasCompanionSession -> Binding.COMPANION
        preferExisting && hasExistingSession -> Binding.EXISTING
        else -> Binding.NEW
    }

    /** 结算草稿：一条用户消息都没有时丢弃（连同会话）。 */
    fun shouldDiscardDraft(userMessageCount: Int): Boolean = userMessageCount <= 0

    /** 场景卡上「接着聊」的默认行为：近 24h 有会话则接着上次。 */
    fun preferExistingByDefault(lastActiveAtMillis: Long?, nowMillis: Long): Boolean {
        if (lastActiveAtMillis == null || lastActiveAtMillis <= 0L) return false
        return nowMillis - lastActiveAtMillis <= DEFAULT_CONTINUE_WINDOW_MS
    }

    /** 24 小时：超过这个时间没有新消息，默认开新场景。 */
    const val DEFAULT_CONTINUE_WINDOW_MS = 24L * 60L * 60L * 1000L
}

/** 草稿编码：`角色ID|会话ID|创建时间戳`。 */
object HomeQuickChatDraftCodec {

    fun encode(characterId: String, sessionId: String, createdAt: Long): String =
        "$characterId|$sessionId|$createdAt"

    fun characterId(raw: String): String = raw.substringBefore('|')

    fun sessionId(raw: String): String =
        raw.split('|').getOrNull(1).orEmpty()

    fun createdAt(raw: String): Long =
        raw.substringAfterLast('|').toLongOrNull() ?: 0L

    fun matches(raw: String, sessionId: String): Boolean =
        raw.isNotBlank() && sessionId(raw) == sessionId
}

/**
 * 轻聊面板的正文分段：与完整会话一致，按 `<||>` 拆成多条短消息气泡。
 *
 * 每段去除首尾空白并丢弃空段；没有实际内容时返回空列表。
 */
fun splitHomeQuickChatBubbles(content: String?): List<String> {
    if (content.isNullOrBlank()) return emptyList()
    return content.split("<||>")
        .map(String::trim)
        .filter(String::isNotEmpty)
}

/**
 * 解析剧情选项 JSON，兼容 `{"choices":[...]}` 与直接数组两种形态。
 *
 * 失败、空串或结构不符时返回空列表，不影响轻聊面板其它内容。
 */
fun parseHomePlotChoices(json: String?): List<HomePlotChoice> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = JsonParser.parseString(json)
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.get("choices")
                ?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        } ?: return emptyList()
        array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val text = listOf("text", "title", "content")
                .firstNotNullOfOrNull { key ->
                    obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
                } ?: return@mapNotNull null
            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            HomePlotChoice(id = id.ifBlank { "home_plot_${text.hashCode()}" }, text = text)
        }
    }.getOrDefault(emptyList())
}
