package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.util.UUID

/**
 * 故事分支图系统，对应原仓库 nbot/plot/。
 *
 * 包含：PlotNode / PlotChoice / PlotEdge 数据模型 + PlotGraphManager 图谱管理器 +
 * PlotChoiceGenerator AI 选项生成器。
 *
 * 简化点：
 * - 不含 memory_bridge / world_book_bridge / multimedia_bridge（Android 无对应服务）
 * - 不含事件总线（用回调替代）
 * - 不含 Mermaid 导出（UI 层处理）
 */

private val plotGson = Gson()

// ============================================================================
// 数据模型
// ============================================================================

/** 故事节点 */
data class PlotNode(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String = "",
    val characterId: String = "",
    var title: String = "",
    var summary: String = "",
    var level: String = "normal",  // normal/important/turning_point/ending
    var scene: Map<String, Any> = emptyMap(),
    var stateSnapshot: Map<String, Any> = emptyMap(),
    var relationshipSnapshot: Map<String, Any> = emptyMap(),
    var parentNodeId: String? = null,
    var selectedChoiceId: String? = null,
    val createdAt: String = Instant.now().toString(),
    var userMessage: String = "",
    var assistantMessage: String = "",
    // Activity Graph 扩展
    var activityType: String = "chat",  // chat/group/event/quest/ending
    var participants: List<String> = emptyList(),
    var location: String = "",
    var mood: String = "",
    var worldChanges: List<String> = emptyList(),
    var memoryRefs: List<String> = emptyList(),
    var reviewScore: Map<String, Float> = emptyMap()
) {
    fun toDict(): Map<String, Any> = buildMap {
        put("id", id); put("conversation_id", conversationId); put("character_id", characterId)
        put("title", title); put("summary", summary); put("level", level)
        put("scene", scene); put("state_snapshot", stateSnapshot)
        put("relationship_snapshot", relationshipSnapshot)
        parentNodeId?.let { put("parent_node_id", it) }; selectedChoiceId?.let { put("selected_choice_id", it) }
        put("created_at", createdAt); put("user_message", userMessage); put("assistant_message", assistantMessage)
        put("activity_type", activityType); put("participants", participants)
        put("location", location); put("mood", mood)
        put("world_changes", worldChanges); put("memory_refs", memoryRefs)
        put("review_score", reviewScore)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromDict(data: Map<String, Any>): PlotNode = PlotNode(
            id = data["id"] as? String ?: UUID.randomUUID().toString(),
            conversationId = data["conversation_id"] as? String ?: "",
            characterId = data["character_id"] as? String ?: "",
            title = data["title"] as? String ?: "",
            summary = data["summary"] as? String ?: "",
            level = data["level"] as? String ?: "normal",
            scene = data["scene"] as? Map<String, Any> ?: emptyMap(),
            stateSnapshot = data["state_snapshot"] as? Map<String, Any> ?: emptyMap(),
            relationshipSnapshot = data["relationship_snapshot"] as? Map<String, Any> ?: emptyMap(),
            parentNodeId = data["parent_node_id"] as? String,
            selectedChoiceId = data["selected_choice_id"] as? String,
            createdAt = data["created_at"] as? String ?: Instant.now().toString(),
            userMessage = data["user_message"] as? String ?: "",
            assistantMessage = data["assistant_message"] as? String ?: "",
            activityType = data["activity_type"] as? String ?: "chat",
            participants = (data["participants"] as? List<String>) ?: emptyList(),
            location = data["location"] as? String ?: "",
            mood = data["mood"] as? String ?: "",
            worldChanges = (data["world_changes"] as? List<String>) ?: emptyList(),
            memoryRefs = (data["memory_refs"] as? List<String>) ?: emptyList(),
            reviewScore = (data["review_score"] as? Map<String, Float>) ?: emptyMap()
        )
    }
}

/** 分支选择 */
data class PlotChoice(
    val id: String = UUID.randomUUID().toString(),
    val nodeId: String = "",
    var text: String = "",
    var level: String = "normal",  // normal/important/turning_point/ending/hidden
    var intent: String = "",
    var selected: Boolean = false,
    var risk: String = "low",  // low/medium/high
    var expectedEffect: String = "",
    var hiddenRequirements: String = ""
) {
    fun toDict(): Map<String, Any> = mapOf(
        "id" to id, "node_id" to nodeId, "text" to text, "level" to level,
        "intent" to intent, "selected" to selected, "risk" to risk,
        "expected_effect" to expectedEffect, "hidden_requirements" to hiddenRequirements
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromDict(data: Map<String, Any>): PlotChoice = PlotChoice(
            id = data["id"] as? String ?: UUID.randomUUID().toString(),
            nodeId = data["node_id"] as? String ?: "",
            text = data["text"] as? String ?: "",
            level = data["level"] as? String ?: "normal",
            intent = data["intent"] as? String ?: "",
            selected = data["selected"] as? Boolean ?: false,
            risk = data["risk"] as? String ?: "low",
            expectedEffect = data["expected_effect"] as? String ?: "",
            hiddenRequirements = data["hidden_requirements"] as? String ?: ""
        )
    }
}

/** 故事边 */
data class PlotEdge(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String = "",
    val toNodeId: String = "",
    val choiceId: String = "",
    var label: String = ""
) {
    fun toDict(): Map<String, Any> = mapOf(
        "id" to id, "from_node_id" to fromNodeId, "to_node_id" to toNodeId,
        "choice_id" to choiceId, "label" to label
    )

    companion object {
        fun fromDict(data: Map<String, Any>): PlotEdge = PlotEdge(
            id = data["id"] as? String ?: UUID.randomUUID().toString(),
            fromNodeId = data["from_node_id"] as? String ?: "",
            toNodeId = data["to_node_id"] as? String ?: "",
            choiceId = data["choice_id"] as? String ?: "",
            label = data["label"] as? String ?: ""
        )
    }
}

// ============================================================================
// 图谱管理器
// ============================================================================

/**
 * 故事图谱管理器，对应原仓库 nbot/plot/graph_manager.py。
 *
 * 管理节点/选项/边的 CRUD、选择、分支、回滚。
 * 内存存储（Android 简化：不持久化到文件，可由调用方序列化）。
 */
class PlotGraphManager {
    private val nodes = mutableMapOf<String, PlotNode>()
    private val choices = mutableMapOf<String, PlotChoice>()
    private val edges = mutableMapOf<String, PlotEdge>()
    private val active = mutableMapOf<String, String>()  // conversation_id → active node id

    // ---- Node CRUD ----

    fun addNode(node: PlotNode) {
        nodes[node.id] = node
        if (node.conversationId.isNotEmpty() && !active.containsKey(node.conversationId)) {
            active[node.conversationId] = node.id
        }
    }

    fun setNodeMessages(nodeId: String, userMessage: String, assistantMessage: String) {
        nodes[nodeId]?.let {
            it.userMessage = userMessage
            it.assistantMessage = assistantMessage
        }
    }

    fun getNode(nodeId: String): PlotNode? = nodes[nodeId]

    // ---- Choice CRUD ----

    fun addChoice(choice: PlotChoice) {
        choices[choice.id] = choice
    }

    fun getChoice(choiceId: String): PlotChoice? = choices[choiceId]

    fun deleteChoicesForNode(nodeId: String) {
        val toDelete = choices.values.filter { it.nodeId == nodeId && !it.selected }.map { it.id }
        toDelete.forEach { choices.remove(it) }
    }

    // ---- Edge CRUD ----

    fun addEdge(edge: PlotEdge) {
        edges[edge.id] = edge
    }

    fun createEdgeForChoice(choice: PlotChoice, fromNodeId: String, toNodeId: String) {
        addEdge(PlotEdge(fromNodeId = fromNodeId, toNodeId = toNodeId, choiceId = choice.id, label = choice.text))
    }

    // ---- Selection ----

    /**
     * 选择某个选项。
     *
     * @return 是否成功
     */
    fun selectChoice(choiceId: String): Boolean {
        val choice = choices[choiceId] ?: return false
        choices.values
            .filter { it.nodeId == choice.nodeId }
            .forEach { it.selected = it.id == choiceId }

        // 设父节点 selected_choice_id
        nodes[choice.nodeId]?.selectedChoiceId = choiceId

        // 激活节点指向父节点
        val parentNode = nodes[choice.nodeId]
        if (parentNode != null && parentNode.conversationId.isNotEmpty()) {
            active[parentNode.conversationId] = parentNode.id
        }

        return true
    }

    // ---- Graph Query ----

    fun getGraph(conversationId: String): Map<String, Any> {
        val convNodes = nodes.values.filter { it.conversationId == conversationId }
        val convNodeIds = convNodes.map { it.id }.toSet()
        val convChoices = choices.values.filter { it.nodeId in convNodeIds }
        val convEdges = edges.values.filter { it.fromNodeId in convNodeIds || it.toNodeId in convNodeIds }
        return mapOf(
            "nodes" to convNodes.map { it.toDict() },
            "choices" to convChoices.map { it.toDict() },
            "edges" to convEdges.map { it.toDict() }
        )
    }

    fun getLatestNode(conversationId: String): PlotNode? {
        val activeId = active[conversationId]
        if (activeId != null) return nodes[activeId]
        return nodes.values.filter { it.conversationId == conversationId }
            .maxByOrNull { it.createdAt }
    }

    fun getLatestChoices(conversationId: String): List<PlotChoice> {
        val node = getLatestNode(conversationId) ?: return emptyList()
        return choices.values.filter { it.nodeId == node.id && !it.selected }
    }

    fun getActiveNodeId(conversationId: String): String? = active[conversationId]

    fun setActiveNode(conversationId: String, nodeId: String) {
        if (nodes[nodeId]?.conversationId == conversationId) {
            active[conversationId] = nodeId
        }
    }

    /** 生成与服务端兼容的 Mermaid 流程图，而不是返回空占位图。 */
    fun generateMermaid(conversationId: String): String {
        val graph = getGraph(conversationId)
        @Suppress("UNCHECKED_CAST")
        val graphNodes = graph["nodes"] as? List<Map<String, Any>> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val graphEdges = graph["edges"] as? List<Map<String, Any>> ?: emptyList()
        fun safeId(raw: String) = "n_" + raw.replace(Regex("[^A-Za-z0-9_]"), "_")
        fun safeLabel(raw: String) = raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

        return buildString {
            appendLine("graph TD")
            graphNodes.forEach { node ->
                val id = node["id"] as? String ?: return@forEach
                val title = (node["title"] as? String).orEmpty().ifBlank { "剧情节点" }
                appendLine("    ${safeId(id)}[\"${safeLabel(title)}\"]")
            }
            graphEdges.forEach { edge ->
                val from = edge["from_node_id"] as? String ?: return@forEach
                val to = edge["to_node_id"] as? String ?: return@forEach
                val label = (edge["label"] as? String).orEmpty()
                if (label.isBlank()) appendLine("    ${safeId(from)} --> ${safeId(to)}")
                else appendLine("    ${safeId(from)} -- \"${safeLabel(label)}\" --> ${safeId(to)}")
            }
        }.trimEnd()
    }

    // ---- In-session Branching ----

    /** 从某 choice 创建新分支 */
    fun branchFrom(choiceId: String, newNode: PlotNode): Boolean {
        val choice = choices[choiceId] ?: return false
        val parentNode = nodes[choice.nodeId] ?: return false

        newNode.parentNodeId = parentNode.id
        addNode(newNode)
        createEdgeForChoice(choice, parentNode.id, newNode.id)

        if (newNode.conversationId.isNotEmpty()) {
            active[newNode.conversationId] = newNode.id
        }
        return true
    }

    /** 回滚：删除目标节点的所有后代，复位目标节点 */
    fun rollback(nodeId: String): Boolean {
        val target = nodes[nodeId] ?: return false

        // BFS 收集后代
        val toDelete = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        edges.values.filter { it.fromNodeId == nodeId }.forEach { queue.add(it.toNodeId) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in toDelete) continue
            toDelete.add(current)
            edges.values.filter { it.fromNodeId == current }.forEach { queue.add(it.toNodeId) }
        }

        // 删除后代节点及其选项/边
        for (id in toDelete) {
            nodes.remove(id)
            choices.values.filter { it.nodeId == id }.forEach { choices.remove(it.id) }
            edges.values.filter { it.fromNodeId == id || it.toNodeId == id }.forEach { edges.remove(it.id) }
        }

        // 复位目标节点
        target.selectedChoiceId = null
        choices.values.filter { it.nodeId == nodeId }.forEach { it.selected = false }

        // 激活节点指向回溯目标
        if (target.conversationId.isNotEmpty()) {
            active[target.conversationId] = nodeId
        }
        return true
    }

    /** 物化从根到 node_id 的完整消息列表 */
    fun materializePath(nodeId: String): List<Map<String, String>> {
        val path = pathToNode(nodeId)
        val messages = mutableListOf<Map<String, String>>()
        for (node in path) {
            val userMsg = node.userMessage.ifEmpty { node.title }
            val assistantMsg = node.assistantMessage.ifEmpty { node.summary }
            if (userMsg.isNotEmpty()) messages.add(mapOf("role" to "user", "content" to userMsg))
            if (assistantMsg.isNotEmpty()) messages.add(mapOf("role" to "assistant", "content" to assistantMsg))
        }
        return messages
    }

    /** 从根到目标节点的路径（含目标） */
    fun pathToNode(nodeId: String): List<PlotNode> {
        val path = mutableListOf<PlotNode>()
        var current = nodes[nodeId] ?: return path
        path.add(0, current)
        while (current.parentNodeId != null) {
            val parent = nodes[current.parentNodeId!!] ?: break
            path.add(0, parent)
            current = parent
        }
        return path
    }

    // ---- 持久化 ----

    fun toJson(): String {
        return plotGson.toJson(mapOf(
            "nodes" to nodes.values.map { it.toDict() },
            "choices" to choices.values.map { it.toDict() },
            "edges" to edges.values.map { it.toDict() },
            "active" to active
        ))
    }

    fun fromJson(json: String) {
        if (json.isBlank()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val data = plotGson.fromJson(json, Map::class.java) as Map<String, Any>
            nodes.clear(); choices.clear(); edges.clear(); active.clear()

            (data["nodes"] as? List<Map<String, Any>>)?.forEach {
                val node = PlotNode.fromDict(it)
                nodes[node.id] = node
            }
            (data["choices"] as? List<Map<String, Any>>)?.forEach {
                val choice = PlotChoice.fromDict(it)
                choices[choice.id] = choice
            }
            (data["edges"] as? List<Map<String, Any>>)?.forEach {
                val edge = PlotEdge.fromDict(it)
                edges[edge.id] = edge
            }
            @Suppress("UNCHECKED_CAST")
            (data["active"] as? Map<String, String>)?.forEach { (k, v) -> active[k] = v }
        } catch (e: Exception) {
            // 忽略解析错误
        }
    }

    fun clear() {
        nodes.clear(); choices.clear(); edges.clear(); active.clear()
    }
}

// ============================================================================
// 全局单例
// ============================================================================

private val globalPlotGraphManager = PlotGraphManager()

fun getGlobalPlotGraphManager(): PlotGraphManager = globalPlotGraphManager

// ============================================================================
// AI 选项生成器
// ============================================================================

/** 默认选项 */
private val DEFAULT_CHOICES = listOf(
    mapOf("level" to "normal", "text" to "继续对话", "intent" to "respond"),
    mapOf("level" to "important", "text" to "推进当前话题", "intent" to "advance"),
    mapOf("level" to "turning_point", "text" to "做出重大决定", "intent" to "turn")
)

/** 风格预设 */
private val STYLE_PRESETS = mapOf(
    "default" to "",
    "sweet" to "风格：甜蜜温馨，选项偏向情感表达和亲密互动。",
    "suspense" to "风格：悬疑紧张，选项偏向探索未知和冒险。",
    "daily" to "风格：日常生活，选项偏向平凡但温暖的互动。",
    "dramatic" to "风格：戏剧冲突，选项偏向对抗和抉择。"
)

/**
 * 剧情选项生成器，对应原仓库 nbot/plot/choice_generator.py。
 *
 * 调用 LLM 生成 3 个力度递增的剧情选项。
 */
class PlotChoiceGenerator(
    private val aiClient: LocalAiClient,
    private val aiModelProvider: (suspend () -> com.nekobot.app.data.local.db.LocalAiModelEntity?)? = null,
    private val onTokenUsage: ((model: String, inputTokens: Int, outputTokens: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PlotChoiceGenerator"
    }

    /**
     * 生成剧情选项。
     *
     * @param responseText AI 回复文本
     * @param recentHistory 最近对话历史 [{role, content}]
     * @param turnContext 当前轮上下文（mood/relationship）
     * @param sessionContext 会话上下文（recent_topics/current_arc）
     * @param style 风格预设
     * @return 选项列表 [{level, text, intent}]
     */
    suspend fun generate(
        responseText: String,
        recentHistory: List<Map<String, String>> = emptyList(),
        turnContext: Map<String, Any> = emptyMap(),
        sessionContext: Map<String, Any> = emptyMap(),
        style: String = "default"
    ): List<Map<String, String>> {
        if (responseText.isBlank()) return DEFAULT_CHOICES

        val model = aiModelProvider?.invoke() ?: return DEFAULT_CHOICES

        val systemPrompt = buildSystemPrompt(style)
        val userPrompt = buildUserPrompt(responseText, recentHistory, turnContext, sessionContext)

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        return try {
            val result = aiClient.chatOnce(model, messages)
            if (result.error != null || result.content.isBlank()) return DEFAULT_CHOICES

            // 记录 token 用量（剧情生成用途）
            if (onTokenUsage != null && result.usage.isNotEmpty()) {
                try {
                    val input = (result.usage["prompt"] as? Int)
                        ?: (result.usage["input_tokens"] as? Int)
                        ?: (result.usage["prompt_tokens"] as? Int)
                        ?: 0
                    val output = (result.usage["completion"] as? Int)
                        ?: (result.usage["output_tokens"] as? Int)
                        ?: (result.usage["completion_tokens"] as? Int)
                        ?: 0
                    if (input > 0 || output > 0) {
                        onTokenUsage.invoke(model.model, input, output)
                    }
                } catch (_: Exception) { }
            }

            val cleaned = cleanResponseContent(result.content)
            val parsed = parseChoices(cleaned)

            // 不足 3 个用默认补齐
            val finalChoices = parsed.toMutableList()
            while (finalChoices.size < 3) {
                finalChoices.add(DEFAULT_CHOICES[finalChoices.size])
            }
            finalChoices.take(3)
        } catch (e: Exception) {
            DEFAULT_CHOICES
        }
    }

    private fun buildSystemPrompt(style: String): String {
        val base = """你是一个剧情选项生成器。根据当前对话，生成 3 个力度递增的选项。

铁律：
1. 必须推进剧情，不能原地踏步
2. 三个选项力度递增：normal（日常）→ important（重要）→ turning_point（转折）
3. 选项文本用第一人称（"我..."），可直接发送

返回 JSON 数组：
[{"level":"normal","text":"选项文本","intent":"respond"}, ...]

只返回 JSON，不要其他文字。"""
        val styleHint = STYLE_PRESETS[style] ?: ""
        return if (styleHint.isNotEmpty()) "$base\n\n$styleHint" else base
    }

    private fun buildUserPrompt(
        responseText: String,
        recentHistory: List<Map<String, String>>,
        turnContext: Map<String, Any>,
        sessionContext: Map<String, Any>
    ): String {
        val parts = mutableListOf<String>()
        parts.add("角色回复:\n$responseText")

        if (recentHistory.isNotEmpty()) {
            val historyText = recentHistory.takeLast(8).joinToString("\n") { msg ->
                val role = if (msg["role"] == "user") "玩家" else "角色"
                "$role: ${msg["content"] ?: ""}"
            }
            parts.add("最近对话:\n$historyText")
        }

        (turnContext["mood"] as? String)?.let { parts.add("当前心情: $it") }
        @Suppress("UNCHECKED_CAST")
        (turnContext["relationship"] as? Map<String, Any>)?.let {
            parts.add("关系: 好感${it["affection"]}/信任${it["trust"]}")
        }
        (sessionContext["current_arc"] as? String)?.let { if (it.isNotEmpty()) parts.add("当前剧情弧: $it") }

        return parts.joinToString("\n\n")
    }

    private fun parseChoices(raw: String): List<Map<String, String>> {
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            @Suppress("UNCHECKED_CAST")
            val list = plotGson.fromJson<List<Map<String, Any>>>(raw, type) ?: return emptyList()
            list.map { item ->
                mapOf(
                    "level" to ((item["level"] as? String) ?: "normal"),
                    "text" to ((item["text"] as? String) ?: ""),
                    "intent" to ((item["intent"] as? String) ?: "respond")
                )
            }.filter { it["text"]!!.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
