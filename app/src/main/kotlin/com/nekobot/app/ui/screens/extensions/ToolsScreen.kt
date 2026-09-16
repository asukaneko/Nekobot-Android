package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.SessionToolCatalog
import com.nekobot.app.data.local.ai.toolDisplayFallbackName
import com.nekobot.app.data.model.Tool
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.toolDescResId
import com.nekobot.app.ui.components.toolNameResId
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.SuccessGreen
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tools 配置 ViewModel：只读地展示当前实际存在的工具（内置 + 动态发现的 MCP 工具）。
 *
 * 工具清单完全来自 [com.nekobot.app.data.repository.UnifiedRepository.listTools]，
 * 界面不再硬编码任何工具条目：新增工具只要进入 [BuiltinTools] 或运行期动态注册到
 * [SessionToolCatalog]，就会自动出现在本页，无需改动 UI。
 */
class ToolsViewModel : BaseViewModel() {
    private val _list = MutableStateFlow<List<Tool>>(emptyList())
    val list: StateFlow<List<Tool>> = _list.asStateFlow()

    init { load() }

    fun load() = launchResult(block = { unified.listTools() }, onSuccess = { _list.value = it ?: emptyList() })
}

/** 一个大类及其包含的工具（按 [SessionToolCatalog] 的目录顺序与分组动态生成）。 */
private data class ToolGroup(
    val categoryId: String,
    val tools: List<Tool>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val vm: ToolsViewModel = viewModel()
    val list by vm.list.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var viewTarget by remember { mutableStateOf<Tool?>(null) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 按工具集目录动态分组：未归类（如用户自定义/MCP 漏注册）工具统一落到「其他」组，保证不丢条目。
    val groups = remember(list) { groupTools(list) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (list.isEmpty() && !loading) {
                EmptyState(title = stringResource(R.string.tools_empty_title), hint = stringResource(R.string.tools_empty_hint))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorBanner(message = error!!, onRetry = {
                                vm.clearError()
                                vm.load()
                            })
                        }
                    }
                    groups.forEach { group ->
                        val expanded = group.categoryId !in collapsed
                        item(key = "cat_${group.categoryId}") {
                            CategoryHeader(
                                categoryId = group.categoryId,
                                count = group.tools.size,
                                expanded = expanded,
                                onToggleExpand = {
                                    collapsed = if (expanded) collapsed + group.categoryId
                                    else collapsed - group.categoryId
                                }
                            )
                        }
                        if (expanded) {
                            items(group.tools, key = { it.id ?: it.hashCode().toString() }) { tool ->
                                ToolCard(tool = tool, onView = { viewTarget = tool })
                            }
                        }
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 内置工具查看弹窗
    viewTarget?.let { target ->
        val isSearchWebTool = target.id == "search_web"
        var exaApiKey by remember(target.id) {
            mutableStateOf(if (isSearchWebTool) ServiceContainer.prefs.exaApiKey else "")
        }
        NekoDialog(
            onDismiss = { viewTarget = null },
            title = stringResource(R.string.tools_detail_title),
            confirmText = stringResource(if (isSearchWebTool) R.string.common_save else R.string.common_close),
            onConfirm = {
                if (isSearchWebTool) ServiceContainer.prefs.exaApiKey = exaApiKey.trim()
                viewTarget = null
            },
            cancelText = null,
            onCancel = null,
            content = {
                Column {
                    Text(localizedToolName(target), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tools_id, target.id ?: "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(localizedToolDescription(target), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.tools_status, if (target.enabled) stringResource(R.string.tools_status_enabled) else stringResource(R.string.tools_status_disabled)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isSearchWebTool) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = exaApiKey,
                            onValueChange = { exaApiKey = it },
                            label = { Text(stringResource(R.string.tools_exa_key_label)) },
                            supportingText = { Text(stringResource(R.string.tools_exa_key_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        )
    }
}

/**
 * 把工具按工具集目录分组。
 *
 * 顺序与分组完全由 [SessionToolCatalog] 决定（含运行期注册的 MCP 工具），
 * 这里不写任何工具 id —— 新增工具会自动归入所属大类。
 */
private fun groupTools(tools: List<Tool>): List<ToolGroup> {
    val byId = tools.mapNotNull { tool -> tool.id?.let { it to tool } }.toMap()
    val grouped = LinkedHashMap<String, MutableList<Tool>>()
    val assigned = mutableSetOf<String>()

    SessionToolCatalog.categories.forEach { category ->
        val matched = category.toolIds.mapNotNull { byId[it] }
        if (matched.isEmpty()) return@forEach
        grouped.getOrPut(category.id) { mutableListOf() }.addAll(matched)
        assigned.addAll(matched.mapNotNull { it.id })
    }

    // 未归类的剩余工具（用户自定义条目、未注册的 MCP 工具等）兜底展示，避免界面丢条目。
    val rest = tools.filter { it.id == null || it.id !in assigned }
    if (rest.isNotEmpty()) grouped.getOrPut(UNCATEGORIZED_ID) { mutableListOf() }.addAll(rest)

    return grouped.map { (categoryId, items) -> ToolGroup(categoryId, items) }
}

/** 未归类工具的兜底大类 id。 */
private const val UNCATEGORIZED_ID = "__other__"

/** 大类分组标题：名称、数量、展开箭头。 */
@Composable
private fun CategoryHeader(
    categoryId: String,
    count: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = categoryName(categoryId),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 大类 id → 本地化名称；未收录（含动态大类）时回退为 id 本身。 */
@Composable
private fun categoryName(categoryId: String): String = when (categoryId) {
    UNCATEGORIZED_ID -> stringResource(R.string.tools_category_other)
    else -> com.nekobot.app.ui.components.toolsetCategoryName(categoryId)
}

/**
 * 工具卡片：本地化名称、本地化描述与启停状态。
 * 内置工具不可删除/切换，卡片点击查看详情。
 */
@Composable
private fun ToolCard(
    tool: Tool,
    onView: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onView)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = localizedToolName(tool),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            val (statusText, statusColor) = if (tool.enabled) stringResource(R.string.tools_status_enabled) to SuccessGreen else stringResource(R.string.tools_status_disabled) to WarningAmber
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            if (tool.builtin) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(Primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.tools_builtin_badge), style = MaterialTheme.typography.labelSmall, color = Primary)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Spacer(Modifier.height(6.dp))
        Text(
            text = localizedToolDescription(tool),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 工具本地化名称：优先字符串资源 [toolNameResId]，缺失时回退内置名，最后回退清洗过的 id。 */
@Composable
private fun localizedToolName(tool: Tool): String {
    val id = tool.id
    if (id != null) {
        val resId = remember(id) { toolNameResId(id) }
        if (resId != 0) return stringResource(resId)
    }
    return tool.name.takeIf { it.isNotBlank() } ?: toolDisplayFallbackName(id.orEmpty())
}

/**
 * 工具本地化描述：优先字符串资源 [toolDescResId]，缺失时回退数据库中的内置中文描述。
 * 新增工具只需补一条 `tool_desc_<id>` 即可完成多语言，不需要改这里。
 */
@Composable
private fun localizedToolDescription(tool: Tool): String {
    val id = tool.id
    if (id != null) {
        val resId = remember(id) { toolDescResId(id) }
        if (resId != 0) return stringResource(resId)
    }
    return tool.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tools_no_description)
}
