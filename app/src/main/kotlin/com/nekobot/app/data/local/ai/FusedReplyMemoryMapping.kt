package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser

/** 编号只存在本轮回调实例中；内部仍用真实ID核对、去重、计量，发送时再转M编号。 */
internal object FusedReplyMemoryMapping {
    const val RULE = "记忆前的[M1]等编号由程序编排，只用于记忆工具，聊天时绝不把编号说出来。只能引用本轮已给出的M编号，不得自己起编号；本轮用户原话固定是S1。"

    fun retireAuxiliary(messages: List<Map<String, Any>>, valid: Set<String>, refs: FusedMemoryReferences) = messages.map { row ->
        val ids = (row[CompanionReferences.META] as? List<*>)?.filterIsInstance<String>() ?: return@map row
        if (ids.all { it in valid }) return@map row
        val retired = refs.entries().filterValues { it !in valid }.keys.map { "[$it] " }.toSet()
        row + mapOf(CompanionReferences.META to ids.filter { it in valid }, "content" to
            row["content"].toString().lineSequence().filterNot { line -> retired.any(line::startsWith) }.joinToString("\n"))
    }

    fun outbound(message: Map<String, Any>, refs: FusedMemoryReferences): Map<String, Any> {
        if (message["role"] != "tool" || message["name"] !in setOf("agent_memory_read", "agent_recall_search")) return message
        val json = runCatching { JsonParser.parseString(message["content"].toString()).asJsonObject }.getOrNull() ?: return message
        for (field in listOf("memories", "results")) {
            val entries = json[field]?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (entry in entries) {
                val row = entry.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                if (row["source_type"]?.asString == "episode") continue
                val id = row["id"]?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val no = refs.register(id)
                row.addProperty("id", no)
                row.remove("key"); row.remove("fact_key")
                row["content"]?.takeIf { it.isJsonPrimitive }?.asString?.let { row.addProperty("content", "[$no] $it") }
            }
        }
        return message + ("content" to json.toString())
    }
}
