package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.isLocalCommandMessage
import com.nekobot.app.data.local.db.CompanionExamplesEntity

internal data class CompanionExample(val user: String, val assistant: String)
internal data class CompanionExampleCandidate(val id: String, val pair: CompanionExample)

internal object CompanionExamplesPolicy {
    const val INVITATION_ROUNDS = 20
    private val gson = Gson()
    private val correction = Regex("不是|不对|说错|错了|纠正|别这样|不要这样|你误会|我说的是|你理解错|重来|重新回答|not what|that's wrong|incorrect", RegexOption.IGNORE_CASE)
    private val failure = Regex("报错|请求失败|生成失败|网络错误|连接失败|超时|HTTP\\s*[45]\\d\\d|exception|traceback|error[:：]|content_filter", RegexOption.IGNORE_CASE)

    fun offer(row: CompanionExamplesEntity, rounds: Int): CompanionExamplesEntity =
        if (row.state == "waiting" && rounds >= INVITATION_ROUNDS) row.copy(state = "offered") else row
    fun decline(row: CompanionExamplesEntity): CompanionExamplesEntity =
        if (row.state == "offered") row.copy(state = "declined") else row
    fun editorVisible(row: CompanionExamplesEntity?): Boolean = row?.optedIn == true
    fun stop(row: CompanionExamplesEntity, anchorId: String): CompanionExamplesEntity =
        if (row.state == "disabled") row else row.copy(state = "disabled", noticeAfterId = row.noticeAfterId ?: anchorId.takeIf {
            row.state == "active" && read(row.pairsJson).isNotEmpty()
        })

    /** 必须相邻且完整；孤立开场白、工具、系统、删消息造成的断点不能配成一轮。 */
    fun pairs(rows: List<LocalMessageEntity>): List<Pair<Int, CompanionExample>> = rows.zipWithNext().mapIndexedNotNull { index, (user, assistant) ->
        if (user.deleted || assistant.deleted || user.sessionId != assistant.sessionId || user.role != "user" || assistant.role != "assistant" ||
            assistant.source in setOf("companion_incomplete", "task_center", "proactive_chat") ||
            user.isLocalCommandMessage() || assistant.isLocalCommandMessage() || user.content.isBlank() || assistant.content.isBlank()) null
        else index to CompanionExample(user.content, assistant.content)
    }

    fun candidates(rows: List<LocalMessageEntity>, limit: Int = 15): List<CompanionExampleCandidate> {
        return balancedCandidates(rows.zipWithNext().mapIndexedNotNull { index, (user, assistant) ->
            candidate(user, assistant, rows.getOrNull(index + 2))
        }, limit)
    }

    fun candidate(user: LocalMessageEntity, assistant: LocalMessageEntity, next: LocalMessageEntity?): CompanionExampleCandidate? {
        val pair = pairs(listOf(user, assistant)).singleOrNull()?.second ?: return null
        return if (pair.user.length in 4..300 && pair.assistant.length in 8..800 &&
            !failure.containsMatchIn(pair.user + "\n" + pair.assistant) &&
            next?.takeIf { it.role == "user" }?.let { !correction.containsMatchIn(it.content) } != false
        ) CompanionExampleCandidate(assistant.id, pair) else null
    }

    fun balancedCandidates(eligible: List<CompanionExampleCandidate>, limit: Int = 15): List<CompanionExampleCandidate> {
        // 每种场景轮流取，避免日常闲聊淹没安慰、玩笑、约定等语气。
        val groups = eligible.groupBy { scene(it.pair.user) }.values.map { it.toMutableList() }
        return buildList { while (size < limit && groups.any { it.isNotEmpty() }) {
            for (group in groups) if (group.isNotEmpty() && size < limit) add(group.removeAt(0))
        } }
    }

    private fun scene(text: String): Int = when {
        Regex("难过|伤心|累|失败|没考好|紧张|担心").containsMatchIn(text) -> 1
        Regex("想你|喜欢|爱你|抱抱|亲|吃醋").containsMatchIn(text) -> 2
        Regex("明天|约|一起|打算|计划").containsMatchIn(text) -> 3
        Regex("哈哈|玩笑|逗|傻|调皮").containsMatchIn(text) -> 4
        else -> 0
    }

    fun read(json: String): List<CompanionExample> = runCatching {
        gson.fromJson<List<CompanionExample>>(json, object : TypeToken<List<CompanionExample>>() {}.type)
            .filter { !it.user.isNullOrBlank() && !it.assistant.isNullOrBlank() }
    }.getOrDefault(emptyList())
    fun encode(pairs: List<CompanionExample>): String = gson.toJson(pairs)
    fun selected(raw: String, candidates: List<CompanionExampleCandidate>): List<CompanionExample> {
        val ids = JsonParser.parseString(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            .asJsonObject.getAsJsonArray("ids").map { it.asString }
        require(ids.size in 2..3 && ids.distinct().size == ids.size) { "示例挑选结果无效" }
        return ids.map { id -> requireNotNull(candidates.firstOrNull { it.id == id }) { "示例不是候选原话" }.pair }
    }
    fun reference(pairs: List<CompanionExample>, name: String): String = if (pairs.isEmpty()) "" else
        "以下旧对话只帮助找到说话语气，不是新消息或事实依据；不要复述。\n" +
            pairs.joinToString("\n\n") { "你：${it.user}\n$name：${it.assistant}" }
    fun reference(row: CompanionExamplesEntity?, name: String): String =
        if (row?.state == "active" && row.optedIn) reference(read(row.pairsJson), name) else ""
}
