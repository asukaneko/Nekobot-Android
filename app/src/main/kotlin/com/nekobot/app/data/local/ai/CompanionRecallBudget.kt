package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class CompanionRecallSettings(
    val rounds: Int = 3, val tokens: Int = 4000,
    /** 单次上限；0 表示旧记录未保存此项，按默认值处理。 */
    val perCall: Int = CompanionRecallBudget.PER_CALL_TOKENS
) {
    fun valid() = rounds in 1..100 && tokens in 256..64000 && (perCall == 0 || perCall in MIN_PER_CALL..64000)

    /** 实际生效的单次上限：始终不超过资料上限（超出部分按资料上限计）。 */
    val effectivePerCall: Int get() = (if (perCall <= 0) CompanionRecallBudget.PER_CALL_TOKENS else perCall).coerceAtMost(tokens)

    companion object { const val MIN_PER_CALL = 128 }
}

/** 只保存计数，不保存原话、可见编号或模型响应。按资料库、会话、本轮玩家消息隔离。 */
internal data class CompanionRecallUsage(
    val settings: CompanionRecallSettings = CompanionRecallSettings(),
    var rounds: Int = 0, var tokens: Int = 0,
    val requests: MutableSet<String> = mutableSetOf()
)

internal data class CompanionRecallRequest(
    val messages: List<Map<String, Any>>, val tools: List<Map<String, Any>>, val mustAnswer: Boolean
)

/** 按返回资料估算用量；可见正文每次重建，累计用量持续保留，异常中断保留预留额度。 */
internal class CompanionRecallBudget(
    private val settings: () -> CompanionRecallSettings,
    private val load: () -> String?, private val save: (String) -> Unit,
    private val diagnostic: (String, Map<String, Any?>) -> Unit = { _, _ -> }
) {
    private data class Span(val key: String, val start: Int, val text: String)
    private data class Request(val id: String, val visible: MutableList<Span>, var room: Int, val limit: Int,
        val providedMemoryIds: Set<String>)
    private val requests = mutableMapOf<String, Request>()
    private val gson = Gson()

    private fun usage(): CompanionRecallUsage = load()?.let {
        // 损坏的计数不得当成零；停止回查并要求重试，避免恢复时悄悄刷新额度。
        gson.fromJson(it, CompanionRecallUsage::class.java).also { state ->
            require(state.settings.valid() && state.rounds >= 0 && state.tokens >= 0) { "回查用量记录无法读取" }
        }
    } ?: CompanionRecallUsage(settings().also { require(it.valid()) }).also {
        // 本轮首次准备上下文时冻结设置；玩家中途修改只影响下一条新消息。
        save(gson.toJson(it))
    }

    suspend fun prepare(
        actor: String, messages: List<Map<String, Any>>, tools: List<Map<String, Any>>,
        maxContext: Int, outputReserve: Int
    ): CompanionRecallRequest = mutex.withLock {
        val state = usage()
        val visible = mutableListOf<Span>()
        messages.forEach { message ->
            // 只认软件附着的原消息身份，且正文仍与当时完整正文完全一致。
            val source = message[SOURCE_META] as? Map<*, *>
            if (source != null && message["content"] is String) {
                val body = message["content"] as String
                if (CompanionMemoryPolicy.digest(body) == source["revision"]) {
                    val id = source["id"] as? String
                    if (id != null) visible += Span("raw:$id:${source["revision"]}", 0, body)
                }
            }
            if (message["role"] == "tool" && message["name"] in readNames) {
                val body = message["content"] as? String ?: return@forEach
                val result = runCatching { gson.fromJson(body, Map::class.java) }.getOrNull() ?: return@forEach
                rows(result).forEach { (_, row) -> span(row)?.let(visible::add) }
            }
        }
        val outbound = messages.map { it - SOURCE_META }
        // 与工具循环的 20% 输出预留一致，避免读完后又被循环的总上下文检查拒绝。
        val inputLimit = minOf(maxContext - outputReserve, (maxContext * 0.8).toInt())
        val room = (inputLimit - estimateLocalMessagesTokens(outbound.map(CompanionRequestUsage::clean)) -
            estimateLocalTextTokens(gson.toJson(tools)) - 512).coerceAtLeast(0)
        // 总份额同时受设置和模型上下文的一小部分限制，避免一次回忆挤占正常对话。
        val limit = totalLimit(state.settings.tokens, maxContext)
        requests[actor] = Request(UUID.randomUUID().toString(), visible, room, limit, CompanionReferences.visibleIds(messages))
        val exhausted = state.rounds >= state.settings.rounds || limit - state.tokens < MIN_RESULT_TOKENS || room < MIN_RESULT_TOKENS
        // 额度到达后保留其他工作工具；移除记忆回查工具，明确不能把资料不足当成事实。
        val lastCalls = (messages.lastOrNull { it["role"] == "assistant" && it["tool_calls"] is List<*> }
            ?.get("tool_calls") as? List<*>).orEmpty()
        val finishRecall = exhausted && lastCalls.isNotEmpty() && lastCalls.all {
            val call = it as? Map<*, *>
            ((call?.get("function") as? Map<*, *>)?.get("name") ?: call?.get("name")) in readNames
        }
        val available = when {
            finishRecall -> emptyList()
            exhausted -> tools.filterNot { toolNameOf(it) in readNames }
            else -> tools
        }
        // 查询原则已通过 PromptStack 合并到顶部系统提示词。这里仅执行额度控制，
        // 不在玩家消息/工具结果后追加提示，也不把动态计数混入聊天上下文。
        observe("recall_prepare", mapOf("rounds" to state.rounds, "max_rounds" to state.settings.rounds,
            "tokens" to state.tokens, "max_tokens" to state.settings.tokens, "per_call" to state.settings.effectivePerCall,
            "limit" to limit, "room" to room, "max_context" to maxContext, "output_reserve" to outputReserve,
            "exhausted" to exhausted, "must_answer" to finishRecall, "reason" to when {
                state.rounds >= state.settings.rounds -> "rounds"
                limit - state.tokens < MIN_RESULT_TOKENS -> "tokens"
                room < MIN_RESULT_TOKENS -> "context"
                else -> "none"
            }))
        CompanionRecallRequest(outbound, available, finishRecall)
    }

    suspend fun read(actor: String, fetch: suspend () -> Map<String, Any>): Map<String, Any> = mutex.withLock {
        val request = requests[actor] ?: return@withLock stopped("还没能翻开这些回忆", "error")
        val state = usage()
        val newRound = request.id !in state.requests
        if ((newRound && state.rounds >= state.settings.rounds) || request.limit - state.tokens < MIN_RESULT_TOKENS || request.room < MIN_RESULT_TOKENS) {
            return@withLock stopped("本轮不能再查找记忆", "quota_exhausted", state, request)
        }
        if (newRound) { state.rounds++; state.requests += request.id }
        // 单次最多取一部分份额，一次大结果不会把整轮额度用光。
        val allowance = perCallAllowance(request.limit - state.tokens, request.room, state.settings.effectivePerCall)
        val previouslyUsed = state.tokens
        // 先保留额度再读取。进程被杀时不会把尚未确认完成的调用当成免费重试。
        state.tokens += allowance
        save(gson.toJson(state))
        val raw = try { fetch() } catch (cancelled: CancellationException) {
            observe("recall_result", mapOf("status" to "cancelled", "reason" to "interrupted", "rounds" to state.rounds, "tokens" to state.tokens))
            throw cancelled
        }
        catch (failure: Exception) {
            state.tokens = previouslyUsed
            save(gson.toJson(state))
            return@withLock withStatus(mapOf("success" to false, "error" to (failure.message ?: "查找失败")), state, request)
        }
        val filtered = deduplicate(markMemoryBodies(raw), request.visible, request.providedMemoryIds)
        val fitted = fit(filtered, allowance - 16)
        val charged = (estimateLocalTextTokens(gson.toJson(fitted)) + 16).coerceAtMost(allowance)
        state.tokens = previouslyUsed + charged
        save(gson.toJson(state))
        request.room -= charged
        // 同批并行调用串行通过此处，重叠片段也不会被重复传出。
        rows(fitted).forEach { (_, row) -> span(row)?.let(request.visible::add) }
        withStatus(fitted, state, request, mapOf("candidates" to rows(raw).size, "charged" to charged))
    }

    private fun stopped(reason: String, status: String = "quota_exhausted", state: CompanionRecallUsage? = null,
        request: Request? = null): Map<String, Any> {
        val base = mapOf<String, Any>("success" to true, "recall_stopped" to true, "note" to reason, "status" to status)
        return if (state != null && request != null) withStatus(base, state, request) else base
    }

    /** 结果附带真实状态与剩余份额，由模型按角色自行组织回答。 */
    private fun withStatus(result: Map<String, Any>, state: CompanionRecallUsage, request: Request,
                           extra: Map<String, Any?> = emptyMap()): Map<String, Any> {
        val output = result.toMutableMap()
        val status = recallStatus(result, rows(result).map { it.second })
        output["status"] = status
        output["rounds_left"] = (state.settings.rounds - state.rounds).coerceAtLeast(0)
        output["tokens_left"] = (request.limit - state.tokens).coerceAtLeast(0)
        if (status == "empty") output["note"] = EMPTY_NOTE
        observe("recall_result", extra + mapOf("status" to status, "rounds" to state.rounds,
            "tokens" to state.tokens, "max_rounds" to state.settings.rounds, "limit" to request.limit,
            "room" to request.room, "returned" to rows(result).size,
            "duplicates" to (result["visible_duplicates"] as? Number ?: 0), "truncated" to (status == "truncated")))
        return output
    }

    private fun observe(kind: String, values: Map<String, Any?>) { runCatching { diagnostic(kind, values) } }

    private fun markMemoryBodies(result: Map<String, Any>): Map<String, Any> = result.toMutableMap().apply {
        for ((field, entries) in rows(result).groupBy({ it.first }, { it.second })) {
            put(field, entries.map { row ->
                val body = row["content"] as? String
                if (row["message_id"] == null && row["id"] is String && body != null)
                    row + mapOf("content_revision" to CompanionMemoryPolicy.digest(body), "text_offset" to 0)
                else row
            })
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(result: Map<*, *>): List<Pair<String, Map<String, Any>>> =
        listOf("messages", "results", "memories").flatMap { key ->
            (result[key] as? List<*>)?.mapNotNull { (it as? Map<String, Any>)?.let { row -> key to row } }.orEmpty()
        }

    private fun span(row: Map<String, Any>): Span? {
        val body = row["content"] as? String ?: return null
        if (body.isEmpty()) return null
        val messageId = row["message_id"] as? String
        if (messageId != null) {
            val revision = row["source_revision"] as? String ?: return null
            return Span("raw:$messageId:$revision", (row["text_offset"] as? Number)?.toInt() ?: 0, body)
        }
        val id = row["id"] as? String ?: return null
        return Span("memory:$id:${row["content_revision"] ?: CompanionMemoryPolicy.digest(body)}",
            (row["text_offset"] as? Number)?.toInt() ?: 0, body)
    }

    private fun deduplicate(result: Map<String, Any>, visible: List<Span>, providedMemoryIds: Set<String>): Map<String, Any> {
        val output = result.toMutableMap()
        var skipped = 0
        for ((field, entries) in rows(result).groupBy({ it.first }, { it.second })) {
            output[field] = entries.flatMap { row ->
                if (row["message_id"] == null && row["id"] in providedMemoryIds) {
                    skipped++
                    return@flatMap emptyList()
                }
                val source = span(row)
                if (source == null) listOf(row) else {
                    var ranges = listOf(source.start until source.start + source.text.length)
                    visible.filter { it.key == source.key }.forEach { old ->
                        ranges = ranges.flatMap { range ->
                            val start = maxOf(range.first, old.start)
                            val end = minOf(range.last + 1, old.start + old.text.length)
                            // 不只检查编号/哈希：保留文本必须与重叠区域逐字一致。
                            if (start >= end || source.text.substring(start - source.start, end - source.start) !=
                                old.text.substring(start - old.start, end - old.start)) listOf(range)
                            else buildList {
                                if (range.first < start) add(range.first until start)
                                if (end <= range.last) add(end..range.last)
                            }
                        }
                    }
                    if (ranges.sumOf { it.count() } < source.text.length) skipped++
                    ranges.map { range -> row.toMutableMap().apply {
                        put("content", source.text.substring(range.first - source.start, range.last + 1 - source.start))
                        if (row.containsKey("message_id") || row.containsKey("content_revision")) {
                            put("text_offset", range.first)
                            put("next_text_offset", range.last + 1)
                            put("truncated", range.last + 1 < source.start + source.text.length || row["truncated"] == true)
                        }
                    }.toMap() }
                }
            }
        }
        if (skipped > 0) {
            output["visible_duplicates"] = skipped
            output["duplicate_note"] = "这些内容前面已经翻到了，可以接着那些回忆说；不代表没有相关资料。"
        }
        return output
    }

    private fun fit(result: Map<String, Any>, allowance: Int): Map<String, Any> {
        fun size(value: Map<String, Any>) = estimateLocalTextTokens(gson.toJson(value))
        if (size(result) <= allowance) return result
        val fields = rows(result).groupBy({ it.first }, { it.second })
        val output = result.filterKeys { it !in fields }.toMutableMap()
        output["budget_limited"] = true
        output["note"] = "这次先翻到这些，还有一些没看完。"
        fields.keys.forEach { output[it] = emptyList<Map<String, Any>>() }
        if (size(output) > allowance) return stopped("先用已经想起来的事回答，记不清就直说")
        for ((field, entries) in fields) {
            val selected = mutableListOf<Map<String, Any>>()
            for (entry in entries) {
                output[field] = selected + entry
                if (size(output) <= allowance) { selected += entry; continue }
                val textKey = if (entry["content"] is String) "content" else "snippet"
                val body = entry[textKey] as? String
                if (!body.isNullOrEmpty()) {
                    var low = 0; var high = body.length
                    fun clipped(n: Int): Map<String, Any> = entry.toMutableMap().apply {
                        put(textKey, body.take(n)); put("truncated", true)
                        if (textKey == "content") {
                            put("next_text_offset", ((entry["text_offset"] as? Number)?.toInt() ?: 0) + n)
                        }
                    }
                    while (low < high) {
                        val mid = (low + high + 1) / 2
                        output[field] = selected + clipped(mid)
                        if (size(output) <= allowance) low = mid else high = mid - 1
                    }
                    if (low > 0) selected += clipped(low)
                }
                output[field] = selected.toList()
                break
            }
            output[field] = selected.toList()
        }
        return output
    }

    companion object {
        const val QUERY_RULES = CompanionPromptLanguage.recallRules
        const val PER_CALL_TOKENS = 1500
        const val CONTEXT_SHARE = 0.15
        const val EMPTY_NOTE = "这次没有找到相关内容；没找到不代表这件事没有发生过。"
        internal fun totalLimit(tokens: Int, maxContext: Int) = minOf(tokens, (maxContext * CONTEXT_SHARE).toInt())
        internal fun perCallAllowance(remaining: Int, room: Int, perCall: Int = PER_CALL_TOKENS) = minOf(remaining, room, perCall)
        internal fun recallStatus(result: Map<*, *>, rows: List<Map<*, *>>): String = when {
            result["success"] == false -> "error"
            result["recall_stopped"] == true -> (result["status"] as? String) ?: "quota_exhausted"
            result["budget_limited"] == true || rows.any { it["truncated"] == true } -> "truncated"
            rows.isEmpty() && result["visible_duplicates"] == null -> "empty"
            else -> "found"
        }
        // 多入口、并行工具和恢复后的实例共用锁，避免同一剩余额度被同时消费。
        private val mutex = Mutex()
        private const val MIN_RESULT_TOKENS = 128
        const val SOURCE_META = "_companion_source"
        val readNames = FusedCompanionTools.readNames + agentRecallToolIds
    }
}
