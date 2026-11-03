package com.nekobot.app.data.local.ai

internal object FusedCompanionTools {
    // 陪伴模式的能力边界，不依赖界面隐藏，也不改写玩家原有工具开关。
    val allowedNames = setOf(
        "agent_memory_read", "agent_memory_update", "agent_recall_search", "agent_history_search", "agent_history_read",
        "get_date_time", "generate_image",
        "understand_image", "list_stickers", "send_sticker", "view_sticker"
    )
    val readNames = setOf("agent_memory_read", "db_list_memories")
    val writeNames = setOf("agent_memory_update", "db_save_memory", "db_delete_memory")
    const val READ_LIMIT = 10
    const val VECTOR_MIN_SCORE = 0.5f
    /** 只保留与问题相关的条目，不把零分记忆塞给模型。 */
    fun <T> relevant(scored: List<Pair<T, Float>>, limit: Int = READ_LIMIT): List<T> =
        scored.filter { it.second > 0f }.sortedByDescending { it.second }.map { it.first }.take(limit)
    fun definitions(original: List<Map<String, Any>>): List<Map<String, Any>> = original.filter {
        toolNameOf(it) in allowedNames
    }.map { definition ->
        val function = definition["function"] as? Map<*, *> ?: definition
        val name = function["name"] as? String
        when (name) {
            "agent_recall_search" -> buildAgentRecallToolDefinitions(currentOnly = true).first()
            in readNames -> tool(requireNotNull(name), "检索当前长久陪伴窗口的角色记忆。不会读取公共 Agent 记忆。", mapOf("query" to text("问题或主题，空则读取当前记录")), emptyList())
            in writeNames -> tool(requireNotNull(name), "根据本轮用户原话维护当前会话的角色记忆，不操作公共记忆。" + FusedReplyMemoryMapping.RULE,
                mapOf("op" to mapOf("type" to "string", "enum" to listOf("add", "update", "cancel", "complete", "retract")),
                    "target" to text("已有M编号；新增不填。未知编号直接失败，不会另建记忆。"),
                    "category" to text("user_profile/character_profile/relationship/episode/promise/open_issue"),
                    "content" to text("角色第一人称的一句话；add/update必填"),
                    "evidence" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to listOf("S1")), "minItems" to 1, "maxItems" to 1),
                    "time_text" to text("本轮原话中的时间词，无则空串"), "model_date" to text("yyyy-MM-dd，未知留空"),
                    "date_type" to mapOf("type" to "string", "enum" to listOf("day", "habit")),
                    "tags" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "doubt" to text("拿不准时指向所怀疑记忆的M编号"), "kind" to text("可选core/fact/task/birthday/episode")),
                listOf("op", "evidence"))
            else -> definition
        }
    }
    private fun text(description: String) = mapOf("type" to "string", "description" to description)
    private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String>): Map<String, Any> =
        mapOf("type" to "function", "function" to mapOf("name" to name, "description" to description,
            "parameters" to mapOf("type" to "object", "properties" to properties, "required" to required)))
}
