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

/** 默认选项（与原仓库 choice_generator.py 对齐） */
private val DEFAULT_CHOICES = listOf(
    mapOf("level" to "normal", "text" to "我拉起你，说走，带你去个地方。", "intent" to "顺势推进，引入一个新的去处把场景往前带"),
    mapOf("level" to "important", "text" to "对了，我一直想跟你聊聊另一件事。", "intent" to "主动开辟新话题，把对话推向尚未触及的方向"),
    mapOf("level" to "turning_point", "text" to "几天后，我带着一个没人知道的消息回来找你。", "intent" to "时间跳跃并引入突发事件，打破当前格局")
)

/** 风格预设 */
private val STYLE_PRESETS = mapOf(
    "default" to "",
    "sweet" to "整体氛围偏甜蜜暧昧：三个选择都带上恰到好处的心动、亲近或试探，在推进剧情的同时让彼此的关系更靠近一步，语气温柔、有暖意。",
    "suspense" to "整体氛围偏悬疑紧张：三个选择都带上不安、悬念或压迫感，引入疑点、未解之谜或潜在危机，让玩家有'接下来会发生什么'的紧张预期。",
    "daily" to "整体氛围偏日常轻松：三个选择自然、生活化、带点小趣味，像真实相处里的闲聊与小事，不必强行制造大冲突，但仍要带来新的小进展。",
    "dramatic" to "整体氛围偏戏剧转折：三个选择都倾向于制造强烈的情节起伏，用意外、抉择、冲突或重大事件推动剧情，让局面产生明显变化。"
)

/**
 * 剧情选项生成器，对应原仓库 nbot/plot/choice_generator.py。
 *
 * 调用 LLM 生成 3 个力度递增的剧情选项。
 */
class PlotChoiceGenerator(
    private val aiClient: LocalAiClient,
    private val aiModelProvider: (suspend () -> com.nekobot.app.data.local.db.LocalAiModelEntity?)? = null,
    private val failoverExecutor: LocalChatFailoverExecutor? = null,
    private val onTokenUsage: ((model: String, actualModel: String, inputTokens: Int, outputTokens: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PlotChoiceGenerator"
        private const val MAX_LLM_RETRIES = 3
    }

    /**
     * 生成剧情选项（含 3 次重试）。
     *
     * @param responseText AI 回复文本
     * @param recentHistory 最近对话历史 [{role, content}]
     * @param turnContext 当前轮上下文（mood/relationship）
     * @param sessionContext 会话上下文（recent_topics/current_arc）
     * @param style 风格预设
     * @param outline 剧情大纲文本（用户导入/粘贴，选项需契合此整体走向）
     * @return 选项列表 [{level, text, intent}]
     */
    suspend fun generate(
        responseText: String,
        recentHistory: List<Map<String, String>> = emptyList(),
        turnContext: Map<String, Any> = emptyMap(),
        sessionContext: Map<String, Any> = emptyMap(),
        style: String = "default",
        outline: String = ""
    ): List<Map<String, String>> {
        if (responseText.isBlank()) return DEFAULT_CHOICES

        val fallbackModel = if (failoverExecutor == null) aiModelProvider?.invoke() else null
        if (failoverExecutor == null && fallbackModel == null) return DEFAULT_CHOICES

        val systemPrompt = buildSystemPrompt(style, outline)
        val userPrompt = buildUserPrompt(responseText, recentHistory, turnContext, sessionContext, outline)

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        // 3 次重试
        var lastError: String? = null
        repeat(MAX_LLM_RETRIES) { attempt ->
            try {
                val execution = failoverExecutor?.execute(messages)
                val result = execution?.value ?: aiClient.chatOnce(fallbackModel!!, messages)
                val usedModel = execution?.model ?: fallbackModel!!
                if (result.error != null) {
                    lastError = "LLM 错误: ${result.error}"
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "剧情选项 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次失败: ${result.error}")
                    return@repeat
                }
                if (result.content.isBlank()) {
                    lastError = "LLM 返回空内容"
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "剧情选项 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次返回空内容")
                    return@repeat
                }

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
                            onTokenUsage.invoke(usedModel.name, usedModel.model, input, output)
                        }
                    } catch (_: Exception) { }
                }

                val cleaned = cleanResponseContent(result.content)
                val parsed = parseChoices(cleaned)

                if (parsed.isEmpty()) {
                    lastError = "解析后为空"
                    com.nekobot.app.data.local.LocalLogger.w(TAG, "剧情选项 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次解析失败，将重试")
                    return@repeat
                }

                com.nekobot.app.data.local.LocalLogger.i(TAG, "剧情选项 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次成功，生成 ${parsed.size} 个选项")

                // 不足 3 个用默认补齐
                val finalChoices = parsed.toMutableList()
                while (finalChoices.size < 3) {
                    finalChoices.add(DEFAULT_CHOICES[finalChoices.size])
                }
                return finalChoices.take(3)
            } catch (e: Exception) {
                lastError = "异常: ${e.message}"
                com.nekobot.app.data.local.LocalLogger.w(TAG, "剧情选项 LLM 第 ${attempt + 1}/$MAX_LLM_RETRIES 次异常: ${e.message}", e)
            }
        }
        com.nekobot.app.data.local.LocalLogger.w(TAG, "剧情选项 LLM $MAX_LLM_RETRIES 次重试均失败: $lastError，使用默认选项")
        return DEFAULT_CHOICES
    }

    private fun buildSystemPrompt(style: String, outline: String = ""): String {
        val base = """你是一个互动故事分支设计师。根据当前的对话内容、角色状态和最近的剧情进展，为玩家生成 3 个能让故事向前推进的剧情选择。

非常重要：每个选择的 text 会在玩家点击后，被前端原样作为玩家消息发送给角色。因此 text 必须是"玩家可以直接发出去的话或动作"，不能是给玩家看的摘要、指令或旁白。

【核心铁律——必须推进剧情】
每一个选择都必须引入一个"上一轮还不存在的新东西"，从下列至少一类中取材：
  - 新的行动目标或动作（去做一件具体的事）
  - 一个尚未聊过的新话题
  - 场景或地点的转移（换个地方、出门、进入新环境）
  - 时间推移或跳跃（过了一会儿 / 几天后 / 第二天）
  - 新出场的人物或新的关系进展
  - 一个突发的外部事件
严禁以下"原地打转"的写法：
  - 复述、改写角色刚说过的话，或仅仅对刚才那句话做出情绪反应
  - 反复追问同一件已经在聊的事
  - 停留在同一个场景、同一个情绪里继续纠缠而不带来任何新进展
  - 含糊的表态（"我懂你""我会陪着你"这类不推动任何事的话）
如果你发现三个选择都还困在当前这一刻，请推翻重写，强行把故事往前带。

【三个选择的推进力度，从小到大】
1. normal（顺势推进）：接着当前情境自然往下走一小步，但要带出一个新的小细节或下一步动作。
2. important（主动开辟）：主动开启一个新话题、转移场景，或推进彼此关系到新的阶段。
3. turning_point（打破格局）：用时间跳跃、突发事件、新人物登场或一个重大决定，彻底改变当前局面。

【文本写法要求】
- text 必须使用第一人称或直接动作，例如："我拉起你往门外走。"、"我想跟你说件事，关于……"。
- 禁止使用"告诉她……""问她是否……""向她表达……""选择……"这类元指令句式。
- 禁止出现"她/他/角色/玩家/选项"等面向系统或第三人称的描述；要像真实聊天消息一样自然。
- 三个选择之间要彼此不同，分别指向不同的剧情方向，不要只是同一句话的三种语气。

返回格式为 JSON 数组，每个元素包含：
- level: 选择级别（normal / important / turning_point）
- text: 点击后直接发送给角色的玩家消息（简短，建议 8-24 字）
- intent: 选择的意图说明（一句话描述这个选择会把剧情带向哪个新方向）

只返回 JSON 数组，不要包含其他内容。"""
        val styleHint = STYLE_PRESETS[style] ?: ""
        val outlineHint = if (outline.isNotBlank()) {
            "\n\n【剧情大纲已启用】\n本次会话存在一份用户提供的剧情大纲。生成的 3 个选择必须契合该大纲的整体走向与既定目标，但每个选择仍必须引入一个新的推进元素（新动作/新话题/新场景/时间推移/新人物/突发事件），禁止直接复述大纲原文，也不要把三个选择都写成大纲的同一种实现路径。上文'必须推进剧情'的铁律与文本写法要求仍然全部适用。"
        } else ""
        val styleSection = if (styleHint.isNotEmpty()) {
            "\n\n【本次剧情风格要求】\n$styleHint\n注意：风格只影响三个选择的语气与取向，上文'必须推进剧情'的铁律与文本写法要求仍然全部适用。"
        } else ""
        return base + outlineHint + styleSection
    }

    private fun buildUserPrompt(
        responseText: String,
        recentHistory: List<Map<String, String>>,
        turnContext: Map<String, Any>,
        sessionContext: Map<String, Any>,
        outline: String = ""
    ): String {
        val parts = mutableListOf<String>()
        parts.add("当前对话内容：\n${responseText.take(800)}")

        // 剧情大纲：用户导入/粘贴，选项需围绕此走向推进
        // 上限 8000 字符（约 8k token），覆盖大多数 5k-7k 字大纲，对上下文窗口压力可控
        if (outline.isNotBlank()) {
            val truncated = outline.take(8000)
            parts.add("剧情大纲（生成选项时需契合此整体走向，但每个选择仍必须引入新的推进，不要直接复述大纲原文）：\n$truncated")
        }

        if (recentHistory.isNotEmpty()) {
            val historyText = recentHistory.takeLast(8).joinToString("\n") { msg ->
                val role = if (msg["role"] == "user") "玩家" else "角色"
                "$role：${(msg["content"] ?: "").take(120)}"
            }
            parts.add("最近几轮对话（已经聊过/做过的内容，新选择必须避免重复这些，并在此基础上把剧情往前推进）：\n$historyText")
        }

        (turnContext["mood"] as? String)?.let { if (it.isNotEmpty()) parts.add("角色当前心情：$it") }
        @Suppress("UNCHECKED_CAST")
        (turnContext["relationship"] as? Map<String, Any>)?.let {
            parts.add("关系状态：好感${it["affection"]}/信任${it["trust"]}")
        }
        (sessionContext["current_arc"] as? String)?.let { if (it.isNotEmpty()) parts.add("当前剧情线：$it") }

        parts.add("\n请根据以上信息生成 3 个能推进剧情的分支选择。每个选择都必须引入一个上文还没出现过的新东西（新动作/新话题/新场景/时间推移/新人物/突发事件），不要在当前这一刻原地打转。注意：text 会被直接发给角色，必须写成玩家本人可以直接发送的消息。")
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
                    "intent" to ((item["intent"] as? String) ?: "")
                )
            }.filter { it["text"]!!.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
