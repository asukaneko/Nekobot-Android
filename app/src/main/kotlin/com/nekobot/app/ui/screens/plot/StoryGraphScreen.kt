package com.nekobot.app.ui.screens.plot

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.data.model.PlotChoiceData
import com.nekobot.app.data.model.PlotBranchRequest
import com.nekobot.app.data.model.PlotGraphData
import com.nekobot.app.data.model.PlotNodeData
import com.nekobot.app.data.model.PlotRollbackRequest
import com.nekobot.app.data.model.PlotSwitchRequest
import com.google.gson.JsonElement
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.Secondary
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class StoryGraphView { Graph, Timeline }

data class BranchPreviewMessage(
    val role: String,
    val sender: String?,
    val content: String
)

private data class PlotUserTurn(
    val content: String,
    val kind: String,
    val choiceText: String? = null
)

/**
 * 故事图 ViewModel：加载剧情分支图数据并执行会改变当前剧情路径的操作。
 */
class StoryGraphViewModel : BaseViewModel() {

    private val _graphData = MutableStateFlow(PlotGraphData())
    val graphData: StateFlow<PlotGraphData> = _graphData.asStateFlow()

    fun loadGraph(sessionId: String) {
        launchResult(
            block = { unified.plotGraph(sessionId) },
            onSuccess = { _graphData.value = it },
            onError = { showError(it) }
        )
    }

    fun createBranch(sessionId: String, nodeId: String, choiceId: String, onCreated: () -> Unit) {
        launchResult(
            block = { unified.plotBranch(sessionId, PlotBranchRequest(nodeId, choiceId)) },
            onSuccess = {
                showToast("已创建分支，正在生成新剧情")
                onCreated()
            }
        )
    }

    fun rollback(sessionId: String, nodeId: String) {
        launchResult(
            block = { unified.plotRollback(sessionId, PlotRollbackRequest(nodeId)) },
            onSuccess = {
                showToast("已回滚到此节点")
                loadGraph(sessionId)
            }
        )
    }

    fun switchBranch(sessionId: String, nodeId: String) {
        launchResult(
            block = { unified.plotSwitch(sessionId, PlotSwitchRequest(nodeId)) },
            onSuccess = {
                showToast("已切换到此分支")
                loadGraph(sessionId)
            }
        )
    }

    fun archiveBranch(sessionId: String, nodeId: String) {
        launchResult(
            block = { unified.archivePlotBranch(sessionId, PlotSwitchRequest(nodeId)) },
            onSuccess = { response ->
                val count = response?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("archived_count")?.takeIf { !it.isJsonNull }?.asInt
                showToast(if (count != null) "已归档该分支（$count 条消息）" else "已归档该分支")
            }
        )
    }

    fun regenerateChoices(sessionId: String) {
        launchResult(
            block = { unified.plotRegenerateChoices(sessionId) },
            onSuccess = {
                showToast("已重新生成选项")
                loadGraph(sessionId)
            }
        )
    }

    fun getMermaid(sessionId: String, onResult: (String) -> Unit) {
        launchResult(
            block = { unified.plotMermaid(sessionId) },
            onSuccess = { el ->
                val text = el?.asJsonObject?.get("mermaid")?.takeIf { !it.isJsonNull }?.asString
                    ?: el?.toString() ?: ""
                onResult(text)
            }
        )
    }

    fun getBranchPreview(sessionId: String, nodeId: String, onResult: (List<BranchPreviewMessage>) -> Unit) {
        launchResult(
            block = { unified.plotBranchPreview(sessionId, nodeId) },
            onSuccess = { onResult(parseBranchPreview(it)) }
        )
    }

    private fun parseBranchPreview(el: JsonElement?): List<BranchPreviewMessage> {
        if (el == null || !el.isJsonObject) return emptyList()
        return try {
            el.asJsonObject.getAsJsonArray("messages")?.mapNotNull { item ->
                val message = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (content.isBlank()) return@mapNotNull null
                BranchPreviewMessage(
                    role = message.get("role")?.takeIf { !it.isJsonNull }?.asString ?: "assistant",
                    sender = message.get("sender")?.takeIf { !it.isJsonNull }?.asString,
                    content = content
                )
            }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * 故事图界面：展示会话的剧情分支图，支持节点查看、分支切换、回滚、选项选择等操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryGraphScreen(
    sessionId: String,
    onBack: () -> Unit
) {
    val vm: StoryGraphViewModel = viewModel(key = "story_graph_$sessionId")
    val graphData by vm.graphData.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()

    var graphView by remember { mutableStateOf(StoryGraphView.Graph) }
    var selectedNode by remember { mutableStateOf<PlotNodeData?>(null) }
    var showMermaid by remember { mutableStateOf(false) }
    var mermaidText by remember { mutableStateOf("") }
    var branchPreview by remember { mutableStateOf<List<BranchPreviewMessage>>(emptyList()) }
    var previewLoading by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<Pair<String, PlotNodeData>?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val topology = remember(graphData) { buildGraphTopology(graphData) }
    val activePathIds = remember(topology) {
        topology.currentPath.mapNotNull { it.id }.toSet()
    }

    LaunchedEffect(sessionId) { vm.loadGraph(sessionId) }
    LaunchedEffect(graphData) {
        if (graphData.nodes.isNotEmpty() && selectedNode?.id !in graphData.nodes.map { it.id }) {
            val initialNode = topology.currentNode ?: graphData.nodes.last()
            selectedNode = initialNode
            previewLoading = true
            vm.getBranchPreview(sessionId, initialNode.id.orEmpty()) {
                if (selectedNode?.id == initialNode.id) {
                    branchPreview = it
                    previewLoading = false
                }
            }
        }
    }
    LaunchedEffect(toast) {
        toast?.takeIf { it.isNotBlank() }?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("故事地图", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.loadGraph(sessionId) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
                PrimaryTabRow(selectedTabIndex = graphView.ordinal) {
                    Tab(
                        selected = graphView == StoryGraphView.Graph,
                        onClick = { graphView = StoryGraphView.Graph },
                        text = { Text("图谱") },
                        icon = { Icon(Icons.Filled.AccountTree, contentDescription = null) }
                    )
                    Tab(
                        selected = graphView == StoryGraphView.Timeline,
                        onClick = { graphView = StoryGraphView.Timeline },
                        text = { Text("时间线") },
                        icon = { Icon(Icons.Filled.Timeline, contentDescription = null) }
                    )
                }
            }
        },
        bottomBar = {
            BottomActionBar(
                onRegenerate = { vm.regenerateChoices(sessionId) },
                onMermaid = {
                    vm.getMermaid(sessionId) { text ->
                        mermaidText = text
                        showMermaid = true
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (loading && graphData.nodes.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                if (graphView == StoryGraphView.Graph) {
                    StoryGraphCanvas(
                        graphData = graphData,
                        topology = topology,
                        activePathIds = activePathIds,
                        onNodeClick = { node ->
                            selectedNode = node
                            previewLoading = true
                            branchPreview = emptyList()
                            vm.getBranchPreview(sessionId, node.id.orEmpty()) {
                                if (selectedNode?.id == node.id) {
                                    branchPreview = it
                                    previewLoading = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    StoryTimeline(
                        graphData = graphData,
                        nodes = topology.currentPath,
                        selectedNodeId = selectedNode?.id,
                        onNodeClick = { node ->
                            selectedNode = node
                            previewLoading = true
                            branchPreview = emptyList()
                            vm.getBranchPreview(sessionId, node.id.orEmpty()) {
                                if (selectedNode?.id == node.id) {
                                    branchPreview = it
                                    previewLoading = false
                                }
                            }
                        },
                        onRollback = { pendingAction = "rollback" to it },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 节点统计信息（放在右上角，避免遮挡左上角内容）
                if (graphData.nodes.isNotEmpty()) {
                    GlassCard(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        cornerRadius = 12
                    ) {
                        Text(
                            "节点：${graphData.nodes.size}  选项：${graphData.choices.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 颜色图例（放在右下角，避免遮挡节点）
                GlassCard(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    cornerRadius = 12
                ) {
                    Column {
                        LegendItem("普通", OnSurfaceVariant)
                        LegendItem("重要", WarningAmber)
                        LegendItem("转折", Primary)
                        LegendItem("结局", Secondary)
                    }
                }

                error?.let {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        ErrorBanner(
                            message = it,
                            onRetry = {
                                vm.clearError()
                                vm.loadGraph(sessionId)
                            }
                        )
                    }
                }
            }
        }
    }

    // 节点详情弹窗
    selectedNode?.let { node ->
        val nodeChoices = graphData.choices.filter { it.nodeId == node.id }
        NodeDetailDialog(
            node = node,
            choices = nodeChoices,
            displayedLevel = topology.levelFor(node),
            userTurn = topology.userTurnFor(node),
            isActive = node.id == graphData.activeNodeId,
            isOnActivePath = node.id in activePathIds,
            preview = branchPreview,
            previewLoading = previewLoading,
            onDismiss = { selectedNode = null },
            onSwitch = { pendingAction = "switch" to node },
            onRollback = { pendingAction = "rollback" to node },
            onArchive = { pendingAction = "archive" to node },
            onCreateBranch = { choiceId ->
                vm.createBranch(sessionId, node.id.orEmpty(), choiceId, onBack)
            }
        )
    }

    // Mermaid 图弹窗
    if (showMermaid) {
        TextContentDialog(
            title = "Mermaid 图代码",
            content = mermaidText,
            onDismiss = { showMermaid = false }
        )
    }

    pendingAction?.let { (action, node) ->
        val isRollback = action == "rollback"
        val isArchive = action == "archive"
        NekoDialog(
            onDismiss = { pendingAction = null },
            title = when {
                isRollback -> "回溯到此节点"
                isArchive -> "归档此分支"
                else -> "切换分支"
            },
            message = when {
                isRollback -> "将移除此节点之后的剧情分支与对话，且不可恢复。"
                isArchive -> "将把根节点到“${node.title ?: "该节点"}”的对话复制到归档会话。"
                else -> "将把当前对话切换到“${node.title ?: "该分支"}”所在位置。"
            },
            confirmText = when {
                isRollback -> "确认回溯"
                isArchive -> "确认归档"
                else -> "确认切换"
            },
            onConfirm = {
                when {
                    isRollback -> vm.rollback(sessionId, node.id.orEmpty())
                    isArchive -> vm.archiveBranch(sessionId, node.id.orEmpty())
                    else -> vm.switchBranch(sessionId, node.id.orEmpty())
                }
                pendingAction = null
                if (!isArchive) selectedNode = null
            },
            cancelText = "取消",
            onCancel = { pendingAction = null }
        )
    }
}

// ==================== 图可视化 ====================

private data class GraphTopology(
    val sortedNodes: List<PlotNodeData>,
    val byId: Map<String, PlotNodeData>,
    val parentOf: Map<String, String>,
    val childrenOf: Map<String, List<String>>,
    val choicesById: Map<String, PlotChoiceData>,
    val incomingEdgeByNode: Map<String, com.nekobot.app.data.model.PlotEdgeData>,
    val currentPath: List<PlotNodeData>,
    val currentNode: PlotNodeData?
) {
    fun pathTo(nodeId: String?): List<PlotNodeData> {
        var current = nodeId?.let(byId::get) ?: return emptyList()
        val result = ArrayDeque<PlotNodeData>()
        val visited = mutableSetOf<String>()
        while (current.id != null && visited.add(current.id)) {
            result.addFirst(current)
            current = parentOf[current.id]?.let(byId::get) ?: break
        }
        return result.toList()
    }

    fun levelFor(node: PlotNodeData): String {
        val incomingChoiceId = node.id?.let { incomingEdgeByNode[it]?.choiceId }
        val parentChoiceId = node.id?.let(parentOf::get)?.let(byId::get)?.selectedChoiceId
        val inherited = choicesById[incomingChoiceId]?.level ?: choicesById[parentChoiceId]?.level
        return when (inherited ?: node.level ?: "normal") {
            "hidden" -> "important"
            else -> inherited ?: node.level ?: "normal"
        }
    }

    fun userTurnFor(node: PlotNodeData): PlotUserTurn? {
        val content = node.userMessage.asMessageContent().trim()
        if (content.isBlank()) return null
        val choiceText = node.id
            ?.let { incomingEdgeByNode[it]?.choiceId }
            ?.let(choicesById::get)
            ?.text
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val kind = when {
            choiceText == null -> "manual"
            content == choiceText -> "selected"
            else -> "edited"
        }
        return PlotUserTurn(content, kind, choiceText)
    }
}

private fun JsonElement?.asMessageContent(): String = try {
    when {
        this == null || isJsonNull -> ""
        isJsonPrimitive -> asString
        isJsonObject -> asJsonObject.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        else -> ""
    }
} catch (_: Exception) {
    ""
}

private fun buildGraphTopology(graph: PlotGraphData): GraphTopology {
    val sorted = graph.nodes
        .filter { !it.id.isNullOrBlank() }
        .sortedBy { it.createdAt.orEmpty() }
    val byId = sorted.associateBy { it.id!! }
    val parentOf = linkedMapOf<String, String>()

    sorted.forEach { node ->
        val id = node.id ?: return@forEach
        node.parentNodeId?.takeIf(byId::containsKey)?.let { parentOf[id] = it }
    }
    graph.edges.forEach { edge ->
        val from = edge.fromNodeId
        val to = edge.toNodeId
        if (from != null && to != null && from in byId && to in byId && to !in parentOf) {
            parentOf[to] = from
        }
    }
    var previous: PlotNodeData? = null
    sorted.forEach { node ->
        val id = node.id ?: return@forEach
        if (id !in parentOf) previous?.id?.let { parentOf[id] = it }
        previous = node
    }

    val children = parentOf.entries.groupBy({ it.value }, { it.key }).mapValues { (_, ids) ->
        ids.sortedBy { byId[it]?.createdAt.orEmpty() }
    }
    var tip = graph.activeNodeId?.let(byId::get) ?: sorted.lastOrNull()
    val descentGuard = mutableSetOf<String>()
    while (tip?.id != null && descentGuard.add(tip.id)) {
        val newestChild = children[tip.id].orEmpty().lastOrNull()?.let(byId::get) ?: break
        tip = newestChild
    }

    val base = GraphTopology(
        sortedNodes = sorted,
        byId = byId,
        parentOf = parentOf,
        childrenOf = children,
        choicesById = graph.choices.mapNotNull { choice -> choice.id?.let { it to choice } }.toMap(),
        incomingEdgeByNode = graph.edges.mapNotNull { edge -> edge.toNodeId?.let { it to edge } }.toMap(),
        currentPath = emptyList(),
        currentNode = tip
    )
    return base.copy(currentPath = base.pathTo(tip?.id))
}

/** 图布局计算结果 */
private data class GraphLayout(
    val positions: Map<String, Pair<Float, Float>>,
    val totalWidth: Float,
    val totalHeight: Float
)

/**
 * 计算节点在图中的位置：按 parentNodeId 构建树结构，BFS 分配层级，同层节点水平居中分布。
 */
private fun computeLayout(
    topology: GraphTopology,
    nodeWidth: Float,
    nodeHeight: Float,
    hGap: Float,
    vGap: Float
): GraphLayout {
    val nodes = topology.sortedNodes
    if (nodes.isEmpty()) return GraphLayout(emptyMap(), 0f, 0f)

    val nodeMap = topology.byId
    if (nodeMap.isEmpty()) return GraphLayout(emptyMap(), 0f, 0f)

    // 构建 parent -> children 映射，识别根节点
    val childrenMap = mutableMapOf<String, MutableList<String>>()
    val roots = mutableListOf<String>()
    for (n in nodes) {
        val id = n.id ?: continue
        val pid = topology.parentOf[id]
        if (pid.isNullOrBlank() || !nodeMap.containsKey(pid)) {
            roots.add(id)
        } else {
            childrenMap.getOrPut(pid) { mutableListOf() }.add(id)
        }
    }
    if (roots.isEmpty()) roots.addAll(nodeMap.keys)

    // BFS 分配深度
    val depths = mutableMapOf<String, Int>()
    val queue = ArrayDeque<String>()
    for (r in roots) {
        if (depths[r] == null) {
            depths[r] = 0
            queue.add(r)
        }
    }
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        val d = depths[cur] ?: 0
        childrenMap[cur]?.forEach { c ->
            if (depths[c] == null) {
                depths[c] = d + 1
                queue.add(c)
            }
        }
    }
    for (id in nodeMap.keys) {
        if (depths[id] == null) depths[id] = 0
    }

    // 按深度分组
    val byDepth = mutableMapOf<Int, MutableList<String>>()
    for ((id, d) in depths) {
        byDepth.getOrPut(d) { mutableListOf() }.add(id)
    }

    val maxDepth = byDepth.keys.maxOrNull() ?: 0
    val maxCount = byDepth.values.maxOf { it.size }

    val totalWidth = maxOf(maxCount * (nodeWidth + hGap), nodeWidth + hGap)
    val totalHeight = (maxDepth + 1) * (nodeHeight + vGap)

    val positions = mutableMapOf<String, Pair<Float, Float>>()
    for ((d, ids) in byDepth) {
        val count = ids.size
        val rowWidth = count * (nodeWidth + hGap) - hGap
        val startX = (totalWidth - rowWidth) / 2f
        ids.forEachIndexed { i, id ->
            val x = startX + i * (nodeWidth + hGap)
            val y = d * (nodeHeight + vGap)
            positions[id] = x to y
        }
    }

    return GraphLayout(positions, totalWidth, totalHeight)
}

/** 根据节点级别返回对应颜色 */
private fun nodeLevelColor(level: String?): Color {
    return when (level) {
        "important" -> WarningAmber
        "turning_point" -> Primary
        "ending" -> Secondary
        else -> OnSurfaceVariant
    }
}

private fun levelLabel(level: String?): String = when (level) {
    "important" -> "推进"
    "turning_point" -> "转折"
    "ending" -> "结局"
    else -> "顺势"
}

private fun userTurnLabel(kind: String): String = when (kind) {
    "selected" -> "选择项"
    "edited" -> "选后编辑"
    else -> "手动回复"
}

/**
 * 故事图画布：在可滚动区域内绘制节点与边。
 */
@Composable
private fun StoryGraphCanvas(
    graphData: PlotGraphData,
    topology: GraphTopology,
    activePathIds: Set<String>,
    onNodeClick: (PlotNodeData) -> Unit,
    modifier: Modifier = Modifier
) {
    if (graphData.nodes.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("暂无剧情节点", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("开启剧情模式后对话将生成故事节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val density = LocalDensity.current
    val nodeWidthDp = 140.dp
    val nodeHeightDp = 100.dp
    val hGapDp = 32.dp
    val vGapDp = 56.dp

    val nodeWidthPx = with(density) { nodeWidthDp.toPx() }
    val nodeHeightPx = with(density) { nodeHeightDp.toPx() }
    val hGapPx = with(density) { hGapDp.toPx() }
    val vGapPx = with(density) { vGapDp.toPx() }

    val layout = remember(topology) {
        computeLayout(topology, nodeWidthPx, nodeHeightPx, hGapPx, vGapPx)
    }

    val totalWidthDp = with(density) { layout.totalWidth.toDp() }
    val totalHeightDp = with(density) { layout.totalHeight.toDp() }
    val activeNodeId = graphData.activeNodeId

    Box(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier.size(width = totalWidthDp, height = totalHeightDp)
        ) {
            // 绘制边
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (edge in graphData.edges) {
                    val from = edge.fromNodeId?.let { layout.positions[it] }
                    val to = edge.toNodeId?.let { layout.positions[it] }
                    if (from != null && to != null) {
                        val startX = from.first + nodeWidthPx / 2
                        val startY = from.second + nodeHeightPx
                        val endX = to.first + nodeWidthPx / 2
                        val endY = to.second
                        val midY = (startY + endY) / 2
                        val path = Path().apply {
                            moveTo(startX, startY)
                            cubicTo(startX, midY, endX, midY, endX, endY)
                        }
                        drawPath(
                            path = path,
                            color = if (edge.fromNodeId in activePathIds && edge.toNodeId in activePathIds) {
                                nodeLevelColor(edge.toNodeId?.let(topology.byId::get)?.let(topology::levelFor)).copy(alpha = 0.8f)
                            } else Color.Gray.copy(alpha = 0.3f),
                            style = Stroke(width = if (edge.fromNodeId in activePathIds && edge.toNodeId in activePathIds) 4f else 2f)
                        )
                    }
                }
            }

            // 绘制边标签
            graphData.edges.forEach { edge ->
                val from = edge.fromNodeId?.let { layout.positions[it] }
                val to = edge.toNodeId?.let { layout.positions[it] }
                val label = edge.label
                if (from != null && to != null && !label.isNullOrBlank()) {
                    val midX = (from.first + to.first) / 2 + nodeWidthPx / 2
                    val midY = (from.second + nodeHeightPx + to.second) / 2
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .offset { IntOffset(midX.roundToInt(), midY.roundToInt()) }
                            .width(nodeWidthDp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // 绘制节点
            graphData.nodes.forEach { node ->
                val id = node.id ?: return@forEach
                val pos = layout.positions[id] ?: return@forEach
                val isActive = id == activeNodeId
                NodeCard(
                    node = node,
                    displayedLevel = topology.levelFor(node),
                    isActive = isActive,
                    isOnActivePath = id in activePathIds,
                    onClick = { onNodeClick(node) },
                    modifier = Modifier
                        .offset { IntOffset(pos.first.roundToInt(), pos.second.roundToInt()) }
                        .size(width = nodeWidthDp, height = nodeHeightDp)
                )
            }
        }
    }
}

/** 节点卡片：展示标题、摘要、级别徽章，激活节点高亮 */
@Composable
private fun NodeCard(
    node: PlotNodeData,
    displayedLevel: String,
    isActive: Boolean,
    isOnActivePath: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val levelColor = nodeLevelColor(displayedLevel)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) levelColor.copy(alpha = 0.25f)
                else if (isOnActivePath) levelColor.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) levelColor else levelColor.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(levelColor)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = node.title ?: "未命名",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 级别标签放在标题右侧，避免底部被裁剪
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(levelColor.copy(alpha = 0.2f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = levelLabel(displayedLevel),
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontSize = 9.sp
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = node.summary ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StoryTimeline(
    graphData: PlotGraphData,
    nodes: List<PlotNodeData>,
    selectedNodeId: String?,
    onNodeClick: (PlotNodeData) -> Unit,
    onRollback: (PlotNodeData) -> Unit,
    modifier: Modifier = Modifier
) {
    if (nodes.isEmpty()) {
        Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text("暂无当前分支时间线", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimelineStat("节点", graphData.nodes.size, Modifier.weight(1f))
                TimelineStat("分支", graphData.choices.size, Modifier.weight(1f))
                TimelineStat("当前线", nodes.size, Modifier.weight(1f))
            }
        }
        items(nodes, key = { it.id.orEmpty() }) { node ->
            val choices = graphData.choices.filter { it.nodeId == node.id }
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (node.id == selectedNodeId) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                        else Modifier
                    )
                    .clickable { onNodeClick(node) },
                cornerRadius = 16
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(nodeLevelColor(node.level))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(node.title ?: "剧情节点", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(levelLabel(node.level), style = MaterialTheme.typography.labelSmall, color = nodeLevelColor(node.level))
                }
                if (!node.summary.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(node.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                choices.forEach { choice ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (choice.selected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(choice.text.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onRollback(node) }, modifier = Modifier.align(Alignment.End)) {
                    Text("回溯到此", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TimelineStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ==================== 弹窗组件 ====================

/** 节点详情弹窗：展示节点信息、选项列表、操作按钮 */
@Composable
private fun NodeDetailDialog(
    node: PlotNodeData,
    choices: List<PlotChoiceData>,
    displayedLevel: String,
    userTurn: PlotUserTurn?,
    isActive: Boolean,
    isOnActivePath: Boolean,
    preview: List<BranchPreviewMessage>,
    previewLoading: Boolean,
    onDismiss: () -> Unit,
    onSwitch: () -> Unit,
    onRollback: () -> Unit,
    onArchive: () -> Unit,
    onCreateBranch: (String) -> Unit
) {
    val levelColor = nodeLevelColor(displayedLevel)
    NekoDialog(
        onDismiss = onDismiss,
        title = node.title ?: "节点详情",
        confirmText = "关闭",
        onConfirm = onDismiss,
        cancelText = null,
        onCancel = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(levelColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(levelLabel(displayedLevel), style = MaterialTheme.typography.labelSmall, color = levelColor)
                }
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Text("当前位置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                node.createdAt?.let {
                    Text(it.take(19), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            userTurn?.let { turn ->
                Spacer(Modifier.height(10.dp))
                DetailMessageCard(
                    label = "我 · ${userTurnLabel(turn.kind)}",
                    text = turn.content,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
                if (turn.kind == "edited" && !turn.choiceText.isNullOrBlank()) {
                    Text(
                        "原选项：${turn.choiceText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 8.dp)
                    )
                }
            }

            if (!node.summary.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                DetailMessageCard(
                    label = "AI",
                    text = node.summary,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            }

            RelationshipSnapshot(node.relationshipSnapshot)

            if (!node.location.isNullOrBlank() || !node.mood.isNullOrBlank() || !node.activityType.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(
                        node.location?.takeIf(String::isNotBlank)?.let { "地点：$it" },
                        node.mood?.takeIf(String::isNotBlank)?.let { "氛围：$it" },
                        node.activityType?.takeIf(String::isNotBlank)?.let { "活动：$it" }
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (choices.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("分支选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                choices.forEach { choice ->
                    ChoiceItem(choice = choice, onCreateBranch = { choice.id?.let(onCreateBranch) })
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSwitch,
                    enabled = !isActive && !isOnActivePath,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when {
                            isActive -> "当前位置"
                            isOnActivePath -> "同一分支"
                            else -> "切换分支"
                        },
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                OutlinedButton(onClick = onRollback, modifier = Modifier.weight(1f)) {
                    Text("回溯到此", fontSize = 11.sp, maxLines = 1)
                }
            }
            OutlinedButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) {
                Text("归档此分支", fontSize = 11.sp)
            }

            Spacer(Modifier.height(12.dp))
            Text("分支预览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            when {
                previewLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                preview.isEmpty() -> Text("该分支暂无可预览的对话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> preview.forEach { message ->
                    DetailMessageCard(
                        label = if (message.role == "user") "我" else message.sender ?: "AI",
                        text = message.content.take(500),
                        containerColor = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** 选项条目：未走过的选项可真正创建新分支，已走过的选项只展示状态。 */
@Composable
private fun ChoiceItem(
    choice: PlotChoiceData,
    onCreateBranch: () -> Unit
) {
    val choiceColor = nodeLevelColor(choice.level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (choice.selected == true) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                choice.text ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(choiceColor.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        levelLabel(choice.level),
                        style = MaterialTheme.typography.labelSmall,
                        color = choiceColor,
                        fontSize = 10.sp
                    )
                }
                choice.risk?.takeIf { it.isNotBlank() }?.let { risk ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "风险：$risk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                choice.intent?.takeIf { it.isNotBlank() }?.let { intent ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "意图：$intent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (choice.selected == true) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = onCreateBranch,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("创建分支", fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DetailMessageCard(label: String, text: String, containerColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .padding(10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RelationshipSnapshot(snapshot: JsonElement?) {
    val entries = remember(snapshot) {
        try {
            snapshot?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.mapNotNull { (key, value) ->
                value.takeIf { !it.isJsonNull }?.let { key to it.asString }
            }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
    if (entries.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text("当时关系", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (key, value) ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                Text("$key  $value", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 文本内容弹窗：用于 Mermaid 代码和分支预览 */
@Composable
private fun TextContentDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var fullscreen by remember { mutableStateOf(false) }
    var renderFailed by remember { mutableStateOf(false) }
    val html = remember(content) { buildMermaidHtml(content) }
    // 桥接：JS 调用 savePng(base64) 后，由协程写入文件
    val bridge = remember {
        MermaidDownloadBridge { base64 ->
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Nekobot").apply { mkdirs() }
                        val file = java.io.File(dir, "mermaid_${System.currentTimeMillis()}.png")
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        file.writeBytes(bytes)
                        file
                    }.getOrNull()
                }
                if (saved != null) {
                    android.widget.Toast.makeText(context, "已保存到 ${saved.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(context, "保存失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // WebView 引用，用于触发下载
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    if (fullscreen) {
        // 全屏 Dialog：WebView 占满屏幕 + 顶部操作栏
        androidx.compose.ui.window.Dialog(onDismissRequest = { fullscreen = false }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF111111))
            ) {
                MermaidWebView(
                    html = html,
                    bridge = bridge,
                    onWebViewReady = { webViewRef = it },
                    onRenderFailed = { renderFailed = it },
                    renderFailed = renderFailed,
                    fallbackText = content,
                    modifier = Modifier.fillMaxSize()
                )
                // 顶部操作栏：下载 + 退出全屏
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.IconButton(onClick = { webViewRef?.evaluateJavascript("downloadPng();", null) }) {
                        androidx.compose.material3.Icon(Icons.Filled.Download, contentDescription = "下载 PNG", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    androidx.compose.material3.IconButton(onClick = { fullscreen = false }) {
                        androidx.compose.material3.Icon(Icons.Filled.FullscreenExit, contentDescription = "退出全屏", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        return
    }

    NekoDialog(
        onDismiss = onDismiss,
        title = title,
        confirmText = "关闭",
        onConfirm = onDismiss,
        cancelText = null,
        onCancel = null
    ) {
        if (content.isBlank()) {
            Text("（无内容）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // 操作栏：下载 PNG + 全屏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.TextButton(onClick = { webViewRef?.evaluateJavascript("downloadPng();", null) }) {
                    androidx.compose.material3.Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载 PNG")
                }
                androidx.compose.material3.TextButton(onClick = { fullscreen = true }) {
                    androidx.compose.material3.Icon(Icons.Filled.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("全屏")
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                MermaidWebView(
                    html = html,
                    bridge = bridge,
                    onWebViewReady = { webViewRef = it },
                    onRenderFailed = { renderFailed = it },
                    renderFailed = renderFailed,
                    fallbackText = content,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Mermaid WebView：支持双指缩放，加载失败时回退到文本展示。 */
@Composable
private fun MermaidWebView(
    html: String,
    bridge: MermaidDownloadBridge,
    onWebViewReady: (WebView) -> Unit,
    onRenderFailed: (Boolean) -> Unit,
    renderFailed: Boolean,
    fallbackText: String,
    modifier: Modifier = Modifier
) {
    if (renderFailed) {
        Text(
            fallbackText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        )
    } else {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    // 启用双指缩放
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(true)
                    addJavascriptInterface(bridge, "AndroidBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            onRenderFailed(true)
                        }
                    }
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    onWebViewReady(this)
                }
            },
            update = { webView ->
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            },
            modifier = modifier
        )
    }
}

/** JS 桥接：把 PNG base64 传回 Kotlin。 */
private class MermaidDownloadBridge(private val onSave: (String) -> Unit) {
    @android.webkit.JavascriptInterface
    fun savePng(base64: String) {
        // 截掉 "data:image/png;base64," 前缀
        val data = base64.substringAfter("base64,")
        onSave(data)
    }
}

/**
 * 用 mermaid.js CDN 渲染 Mermaid 代码为 SVG 图。
 * 浅色背景以匹配弹窗；脚本执行后调用 mermaid.run() 触发渲染。
 * 暴露 downloadPng() 供 Kotlin 调用以导出 PNG。
 */
private fun buildMermaidHtml(mermaidCode: String): String {
    // 用 Gson 把 mermaid 代码序列化为合法 JSON 字符串，避免手工转义出错
    val codeJson = com.google.gson.Gson().toJson(mermaidCode)
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
<style>
  html, body { margin: 0; padding: 8px; background: transparent; }
  body { font-family: -apple-system, "Helvetica Neue", sans-serif; }
  #target { text-align: center; background: #ffffff; border-radius: 6px; padding: 8px; min-height: 60px; }
  #target svg { max-width: 100%; height: auto; }
  #fallback { display: none; white-space: pre-wrap; font-family: monospace; text-align: left; color: #333; padding: 8px; }
  #loading { color: #888; font-size: 12px; padding: 8px; text-align: center; }
</style>
</head>
<body>
<div id="target"><div id="loading">渲染中…</div></div>
<pre id="fallback"></pre>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
<script>
  var code = $codeJson;
  var target = document.getElementById('target');
  var fallback = document.getElementById('fallback');
  fallback.textContent = code;
  function showFallback(err) {
    target.style.display = 'none';
    fallback.style.display = 'block';
    if (err) console.error(err);
  }
  try {
    if (typeof mermaid === 'undefined') { showFallback(new Error('mermaid.js 加载失败')); }
    else {
      target.textContent = code;
      target.setAttribute('class', 'mermaid');
      mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' });
      mermaid.run({ nodes: [target] })
        .catch(showFallback);
    }
  } catch(e) {
    showFallback(e);
  }

  // 导出 PNG：把 SVG 绘制到 canvas 后转 base64 回传 Kotlin
  function downloadPng() {
    try {
      var svg = document.querySelector('#target svg');
      if (!svg) { alert('SVG 尚未渲染完成'); return; }
      // 克隆 SVG 并显式设置尺寸（避免 getBBox 在某些情况下返回 0）
      var clone = svg.cloneNode(true);
      var bbox = svg.getBoundingClientRect();
      var width = Math.max(bbox.width, 320);
      var height = Math.max(bbox.height, 200);
      clone.setAttribute('width', width);
      clone.setAttribute('height', height);
      clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
      var svgData = new XMLSerializer().serializeToString(clone);
      var svgBlob = new Blob([svgData], { type: 'image/svg+xml;charset=utf-8' });
      var url = URL.createObjectURL(svgBlob);
      var img = new Image();
      img.onload = function() {
        var scale = 2;  // 2x 分辨率以保证清晰度
        var canvas = document.createElement('canvas');
        canvas.width = width * scale;
        canvas.height = height * scale;
        var ctx = canvas.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        var pngData = canvas.toDataURL('image/png');
        AndroidBridge.savePng(pngData);
        URL.revokeObjectURL(url);
      };
      img.onerror = function() {
        alert('PNG 导出失败：图片加载错误');
        URL.revokeObjectURL(url);
      };
      img.src = url;
    } catch(e) {
      alert('PNG 导出失败：' + e.message);
    }
  }
</script>
</body>
</html>
    """.trimIndent()
}

// ==================== 辅助组件 ====================

/** 底部操作栏：重新生成选项 + Mermaid 图查看 */
@Composable
private fun BottomActionBar(
    onRegenerate: () -> Unit,
    onMermaid: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRegenerate,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新生成选项")
            }
            Button(
                onClick = onMermaid,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.AccountTree, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Mermaid 图", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** 颜色图例条目 */
@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
