package com.nekobot.app.ui.screens.plot

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.data.model.PlotChoiceData
import com.nekobot.app.data.model.PlotGraphData
import com.nekobot.app.data.model.PlotNodeData
import com.nekobot.app.data.model.PlotRollbackRequest
import com.nekobot.app.data.model.PlotSelectRequest
import com.nekobot.app.data.model.PlotSwitchRequest
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.Secondary
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** 用于格式化 JSON 输出 */
private val prettyGson = GsonBuilder().setPrettyPrinting().setLenient().disableHtmlEscaping().create()

/**
 * 故事图 ViewModel：加载剧情分支图数据，支持选择选项、回滚、切换分支、重新生成选项、获取 Mermaid 代码。
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

    fun selectChoice(sessionId: String, choiceId: String) {
        launchResult(
            block = { unified.plotSelect(sessionId, PlotSelectRequest(choiceId)) },
            onSuccess = {
                showToast("已选择分支")
                loadGraph(sessionId)
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

    fun getBranchPreview(sessionId: String, nodeId: String, onResult: (String) -> Unit) {
        launchResult(
            block = { unified.plotBranchPreview(sessionId, nodeId) },
            onSuccess = { el ->
                val formatted = formatBranchPreview(el)
                onResult(formatted)
            }
        )
    }

    /** 将分支预览的 JSON 响应格式化为易读的文本 */
    private fun formatBranchPreview(el: JsonElement?): String {
        if (el == null) return "（无内容）"
        return try {
            val obj = el.asJsonObject
            val sb = StringBuilder()
            // 标题
            obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.let {
                sb.append("标题：$it\n")
            }
            // 摘要
            obj.get("summary")?.takeIf { !it.isJsonNull }?.asString?.let {
                sb.append("摘要：$it\n")
            }
            // 节点路径
            obj.getAsJsonArray("nodes")?.let { arr ->
                sb.append("\n节点路径（${arr.size()}）：\n")
                arr.forEachIndexed { idx, node ->
                    val n = node.asJsonObject
                    val title = n.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "未命名"
                    val level = n.get("level")?.takeIf { !it.isJsonNull }?.asString ?: "normal"
                    sb.append("${idx + 1}. [$level] $title\n")
                    n.get("summary")?.takeIf { !it.isJsonNull }?.asString?.let { s ->
                        sb.append("   $s\n")
                    }
                }
            }
            // 消息列表
            obj.getAsJsonArray("messages")?.let { arr ->
                sb.append("\n消息记录（${arr.size()}）：\n")
                arr.forEachIndexed { idx, msg ->
                    val m = msg.asJsonObject
                    val role = m.get("role")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
                    val content = m.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val preview = content.take(100) + if (content.length > 100) "..." else ""
                    sb.append("${idx + 1}. [$role] $preview\n")
                }
            }
            // 选项列表
            obj.getAsJsonArray("choices")?.let { arr ->
                sb.append("\n可选分支（${arr.size()}）：\n")
                arr.forEach { c ->
                    val co = c.asJsonObject
                    val text = co.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val level = co.get("level")?.takeIf { !it.isJsonNull }?.asString ?: "normal"
                    sb.append("  • [$level] $text\n")
                }
            }
            if (sb.isBlank()) {
                prettyGson.toJson(el)
            } else {
                sb.toString().trimEnd()
            }
        } catch (_: Exception) {
            el.toString()
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

    var selectedNode by remember { mutableStateOf<PlotNodeData?>(null) }
    var showMermaid by remember { mutableStateOf(false) }
    var mermaidText by remember { mutableStateOf("") }
    var showBranchPreview by remember { mutableStateOf(false) }
    var branchPreviewText by remember { mutableStateOf("") }

    LaunchedEffect(sessionId) { vm.loadGraph(sessionId) }
    LaunchedEffect(toast) {
        if (!toast.isNullOrBlank()) vm.clearToast()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("故事图", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
                StoryGraphCanvas(
                    graphData = graphData,
                    onNodeClick = { selectedNode = it },
                    modifier = Modifier.fillMaxSize()
                )

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
            onDismiss = { selectedNode = null },
            onSwitch = {
                vm.switchBranch(sessionId, node.id.orEmpty())
                selectedNode = null
            },
            onRollback = {
                vm.rollback(sessionId, node.id.orEmpty())
                selectedNode = null
            },
            onBranchPreview = {
                vm.getBranchPreview(sessionId, node.id.orEmpty()) { text ->
                    branchPreviewText = text
                    showBranchPreview = true
                }
            },
            onSelectChoice = { choiceId ->
                vm.selectChoice(sessionId, choiceId)
                selectedNode = null
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

    // 分支预览弹窗
    if (showBranchPreview) {
        TextContentDialog(
            title = "分支预览",
            content = branchPreviewText,
            onDismiss = { showBranchPreview = false }
        )
    }
}

// ==================== 图可视化 ====================

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
    nodes: List<PlotNodeData>,
    nodeWidth: Float,
    nodeHeight: Float,
    hGap: Float,
    vGap: Float
): GraphLayout {
    if (nodes.isEmpty()) return GraphLayout(emptyMap(), 0f, 0f)

    val nodeMap = nodes.filter { !it.id.isNullOrBlank() }.associateBy { it.id!! }
    if (nodeMap.isEmpty()) return GraphLayout(emptyMap(), 0f, 0f)

    // 构建 parent -> children 映射，识别根节点
    val childrenMap = mutableMapOf<String, MutableList<String>>()
    val roots = mutableListOf<String>()
    for (n in nodes) {
        val id = n.id ?: continue
        val pid = n.parentNodeId
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

/**
 * 故事图画布：在可滚动区域内绘制节点与边。
 */
@Composable
private fun StoryGraphCanvas(
    graphData: PlotGraphData,
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

    val layout = remember(graphData) {
        computeLayout(graphData.nodes, nodeWidthPx, nodeHeightPx, hGapPx, vGapPx)
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
                            color = Color.Gray.copy(alpha = 0.5f),
                            style = Stroke(width = 2f)
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
                    isActive = isActive,
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
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val levelColor = nodeLevelColor(node.level)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) levelColor.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                        text = node.level ?: "normal",
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

// ==================== 弹窗组件 ====================

/** 节点详情弹窗：展示节点信息、选项列表、操作按钮 */
@Composable
private fun NodeDetailDialog(
    node: PlotNodeData,
    choices: List<PlotChoiceData>,
    onDismiss: () -> Unit,
    onSwitch: () -> Unit,
    onRollback: () -> Unit,
    onBranchPreview: () -> Unit,
    onSelectChoice: (String) -> Unit
) {
    val levelColor = nodeLevelColor(node.level)
    NekoDialog(
        onDismiss = onDismiss,
        title = node.title ?: "节点详情",
        confirmText = "关闭",
        onConfirm = onDismiss,
        cancelText = null,
        onCancel = null
    ) {
        // 级别 + 创建时间
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(levelColor.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    node.level ?: "normal",
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor
                )
            }
            Spacer(Modifier.width(8.dp))
            node.createdAt?.let {
                Text(
                    "创建：${it.take(19)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 摘要
        if (!node.summary.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("摘要", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                node.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 地点 / 氛围
        node.location?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text("地点：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        node.mood?.takeIf { it.isNotBlank() }?.let {
            Text("氛围：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        node.activityType?.takeIf { it.isNotBlank() }?.let {
            Text("活动：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 操作按钮
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onSwitch,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Text("切换分支", style = MaterialTheme.typography.labelSmall, maxLines = 1, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onRollback,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Text("回滚节点", style = MaterialTheme.typography.labelSmall, maxLines = 1, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onBranchPreview,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Text("预览分支", style = MaterialTheme.typography.labelSmall, maxLines = 1, fontSize = 11.sp)
            }
        }

        // 选项列表
        if (choices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "选项列表（${choices.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            choices.forEach { choice ->
                ChoiceItem(
                    choice = choice,
                    onSelect = { choice.id?.let { onSelectChoice(it) } }
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 选项条目：展示文本、级别、风险、选中状态，点击选择 */
@Composable
private fun ChoiceItem(
    choice: PlotChoiceData,
    onSelect: () -> Unit
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
            .clickable(onClick = onSelect)
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
                        choice.level ?: "normal",
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
            Text(
                content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(8.dp)
            )
        }
    }
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
