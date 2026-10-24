package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.google.gson.JsonElement
import com.nekobot.app.data.local.db.*
import com.nekobot.app.data.local.isLocalCommandMessage

internal data class RelationshipSource(val id: String, val hash: String)
internal data class RelationshipTurn(val rows: List<LocalMessageEntity>) { val endId get() = rows.last().id }

internal object CompanionRelationshipPolicy {
    val dimensions = linkedMapOf("affection" to "亲近", "trust" to "信任", "familiarity" to "熟悉",
        "dependency" to "依赖", "security" to "安全感", "jealousy" to "嫉妒")
    val initial = mapOf("affection" to 50, "trust" to 50, "familiarity" to 30, "dependency" to 30, "security" to 50, "jealousy" to 0)
    /** 完整历史补评已覆盖到当前末轮时，旧的等待标记不再代表仍有内容待补评。 */
    fun backfillComplete(state: CompanionRelationshipEntity, narrative: RelationshipNarrative?, pending: Int?): Boolean =
        state.awaitingHistory && !state.historyReviewRequired && state.cursorId != null && pending == 0 &&
            dimensions.keys.all { narrative?.relationships?.get(it)?.score != null }
    fun hash(row: LocalMessageEntity) = CompanionMemoryPolicy.fingerprint(row)
    fun values(json: String): Map<String, Int> {
        val obj = JsonParser.parseString(json).asJsonObject
        return dimensions.keys.associateWith { obj[it]?.asInt ?: 0 }
    }
    /** 强度是心情的程度，不是精力、好感或情绪正负；拒绝错误单位和类型，不能截断为合法值。 */
    fun parseMoodIntensity(element: JsonElement?): Float? {
        requireNotNull(element) { "评估结果缺少情绪强度；无法判断时应返回 null" }
        if (element.isJsonNull) return null
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "情绪强度必须是 0—1 的数值或 null" }
        val value = element.asDouble
        require(value.isFinite() && value in 0.0..1.0) { "情绪强度超出 0—1 范围，已保留旧状态" }
        return value.toFloat()
    }
    /** 连续玩家消息到首条有效回复算一轮；不把主动消息和重生成重复算轮次。 */
    fun turns(rows: List<LocalMessageEntity>): List<RelationshipTurn> {
        val result = mutableListOf<RelationshipTurn>()
        val pending = mutableListOf<LocalMessageEntity>()
        for (row in rows.filter { !it.deleted && !it.isLocalCommandMessage() && it.content.isNotBlank() && it.source !in setOf("task_center", "proactive_chat", "companion_incomplete") }) {
            when (row.role) {
                "user" -> pending.add(row)
                "assistant" -> if (pending.isNotEmpty()) {
                    result.add(RelationshipTurn(pending.toList() + row)); pending.clear()
                }
            }
        }
        return result
    }
    fun pending(turns: List<RelationshipTurn>, cursor: String?): List<RelationshipTurn> {
        if (cursor == null) return turns
        val at = turns.indexOfFirst { it.endId == cursor }
        require(at >= 0) { "上次评估的位置已变化，请在页面确认从现在继续；已保存的关系积累不会删除。" }
        return turns.drop(at + 1)
    }

    /** 固定作业启动时的末轮；请求期间新增对话留给下一批，不随后台处理扩大范围。 */
    fun throughBoundary(turns: List<RelationshipTurn>, boundary: String?): List<RelationshipTurn> {
        if (boundary == null) return emptyList()
        val at = turns.indexOfFirst { it.endId == boundary }
        require(at >= 0) { "评估范围内的聊天已经变化，请重新发起" }
        return turns.take(at + 1)
    }

    /** 已成功落库的一轮只通知一次；后续界面结束/取消不能让轮数增长却漏发通知。 */
    fun onTurnSaved(ctx: PipelineContext, message: Map<String, Any>, companionMode: Boolean,
        regenerated: Boolean, source: String?, notify: () -> Unit) {
        if (!companionMode || ctx.error != null || ctx.stoppedPrematurely || regenerated ||
            ctx.metadata["skip_character_after_turn"] == true ||
            source in setOf("task_center", "proactive_chat", "companion_incomplete") ||
            (message["content"] as? String).isNullOrBlank()) return
        if (ctx.metadata.put("_relationship_turn_notified", true) != true) notify()
    }
    fun totals(base: Map<String, Int>, deltas: List<Map<String, Int>>) = dimensions.keys.associateWith { key ->
        // 不在每次更新时截断积累，否则纠错无法撤回原事件，连续失信也会被压成相同的底值。
        (base[key] ?: initial.getValue(key)).toLong() + deltas.sumOf { (it[key] ?: 0).toLong() }
    }
    /** 只能提出一次范围确认，不能据此断言旧记录从未评估，更不能自动发起付费补评。 */
    fun needsHistoryReview(state: CompanionRelationshipEntity, hasEvents: Boolean, hasSnapshots: Boolean,
        initialValues: Map<String, Int>): Boolean = state.historyScopeVersion == 0 && state.legacy &&
        state.cursorId != null && state.updatedAt == 0L && !hasEvents && !hasSnapshots &&
        state.feelingSources == "[]" && values(state.baselineJson) == initialValues

    private fun band(value: Long) = when { value < 20 -> 0; value < 40 -> 1; value < 65 -> 2; value < 85 -> 3; else -> 4 }
    fun shortDescription(key: String, value: Long): String = when (key) {
        "affection" -> listOf("保持距离", "有些疏远", "愿意相处", "感到亲近", "深厚亲近")
        "trust" -> listOf("难以相信", "有所保留", "愿意倾听", "认为可靠", "信任稳固")
        "familiarity" -> listOf("尚不熟悉", "逐渐了解", "已有默契", "熟悉习惯", "了解细致")
        "dependency" -> listOf("主要自立", "偶尔求助", "愿意依靠", "习惯求助", "依赖较强")
        "security" -> listOf("缺乏安心", "仍有戒备", "部分放松", "多数安心", "稳定安心")
        else -> listOf("无明显迹象", "偶尔在意", "对比较敏感", "较易不安", "不安较强")
    }[band(value)]
    fun describe(key: String, value: Long): String {
        val band = band(value)
        return when (key) {
            "trust" -> listOf("很难相信承诺，需要持续兑现", "对承诺有明显保留", "愿意听取承诺，也会看实际行动", "多数时候认为对方可靠", "长期相处中形成了较稳固的信任")
            "affection" -> listOf("保持距离", "相处还有些疏远", "愿意继续相处", "感到亲近", "积累了深厚的亲近感")
            "familiarity" -> listOf("彼此还不熟悉", "正在逐渐了解", "已有一些相处默契", "熟悉彼此的习惯", "对彼此有长期而细致的了解")
            "dependency" -> listOf("主要依靠自己", "偶尔愿意求助", "有些事情愿意依靠对方", "比较习惯寻求对方支持", "对对方支持的依赖较强，也需要保留自主性")
            "security" -> listOf("相处时明显缺乏安心感", "仍有顾虑和戒备", "部分情境下能够放松", "多数时候相处安心", "长期相处带来了稳定的安心感")
            else -> listOf("没有明显的争宠或排他感受", "偶尔会在意被忽略", "对关系中的比较有些敏感", "较容易因比较而不安", "比较或排他带来的不安较强，需要尊重彼此边界")
        }[band]
    }
    fun validateDelta(kind: String, values: Map<String, Int>) {
        require(kind in setOf("reliability", "comfort", "familiarity", "boundary", "other")) { "评估返回了未知事件类型" }
        values.forEach { (key, delta) ->
            require(key in dimensions && delta in -8..6) { "评估的关系变化超出单次范围" }
            if (key == "trust" && delta > 0) {
                require(kind == "reliability" && delta <= 3) { "信任改善需要兑现或可靠性的依据，不能只因一次安慰上升" }
            }
        }
    }
}
