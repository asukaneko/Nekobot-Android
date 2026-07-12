package com.nekobot.app.ui.screens.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.nekobot.app.data.model.McpServer
import com.nekobot.app.data.model.McpServerRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.SuccessGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MCP 服务管理页 ViewModel：管理 MCP 服务列表的加载、创建、更新、删除、连接/断开、测试、工具查看。
 */
class McpServersViewModel : BaseViewModel() {

    private val _list = MutableStateFlow<List<McpServer>>(emptyList())
    val list: StateFlow<List<McpServer>> = _list.asStateFlow()

    /** MCP 工具列表（查看工具时填充） */
    private val _tools = MutableStateFlow<JsonElement?>(null)
    val tools: StateFlow<JsonElement?> = _tools.asStateFlow()

    /** 测试结果 */
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init {
        load()
    }

    /** 加载 MCP 服务列表 */
    fun load() = launchResult(
        block = { unified.listMcpServers() },
        onSuccess = { _list.value = it ?: emptyList() }
    )

    /** 创建 MCP 服务 */
    fun create(req: McpServerRequest) = launchResult(
        block = { unified.createMcpServer(req) },
        onSuccess = { load() }
    )

    /** 更新 MCP 服务 */
    fun update(id: String, req: McpServerRequest) = launchResult(
        block = { unified.updateMcpServer(id, req) },
        onSuccess = { load() }
    )

    /** 删除 MCP 服务 */
    fun delete(id: String) = launchResult(
        block = { unified.deleteMcpServer(id) },
        onSuccess = { load() }
    )

    /** 连接 MCP 服务 */
    fun connect(id: String) = launchResult(
        block = { unified.connectMcpServer(id) },
        onSuccess = {
            showToast("已发起连接")
            load()
        }
    )

    /** 断开 MCP 服务 */
    fun disconnect(id: String) = launchResult(
        block = { unified.disconnectMcpServer(id) },
        onSuccess = {
            showToast("已断开连接")
            load()
        }
    )

    /** 测试 MCP 服务 */
    fun test(id: String) = launchResult(
        block = { unified.testMcpServer(id) },
        onSuccess = { _testResult.value = it?.toString() ?: "无返回结果" }
    )

    /** 加载 MCP 服务工具列表 */
    fun loadTools(id: String) = launchResult(
        block = { unified.mcpServerTools(id) },
        onSuccess = { _tools.value = it }
    )

    /** 清除测试结果 */
    fun clearTestResult() {
        _testResult.value = null
    }

    /** 清除工具列表 */
    fun clearTools() {
        _tools.value = null
    }
}

/**
 * MCP 服务管理页：展示所有 MCP 服务，支持新建、编辑、删除、连接/断开、测试、查看工具。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(onBack: () -> Unit, viewModel: McpServersViewModel = viewModel()) {
    val list by viewModel.list.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val tools by viewModel.tools.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var deleteTarget by remember { mutableStateOf<McpServer?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("MCP 服务", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        editing = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建 MCP 服务", tint = MaterialTheme.colorScheme.primary)
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
            if (list.isEmpty() && !loading) {
                EmptyState(
                    title = "暂无 MCP 服务",
                    hint = "点击右上角新建一个 MCP 服务",
                    icon = {
                        Icon(
                            Icons.Filled.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(0.dp)
                        )
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorBanner(message = error!!, onRetry = {
                                viewModel.clearError()
                                viewModel.load()
                            })
                        }
                    }
                    items(list, key = { it.id ?: it.name ?: it.hashCode().toString() }) { server ->
                        McpServerCard(
                            server = server,
                            onEdit = {
                                editing = server
                                showForm = true
                            },
                            onDelete = { deleteTarget = server },
                            onConnect = { server.id?.let { viewModel.connect(it) } },
                            onDisconnect = { server.id?.let { viewModel.disconnect(it) } },
                            onTest = { server.id?.let { viewModel.test(it) } },
                            onTools = { server.id?.let { viewModel.loadTools(it) } }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        McpServerFormDialog(
            initial = editing,
            onConfirm = { req ->
                editing?.id?.let { id -> viewModel.update(id, req) } ?: viewModel.create(req)
                showForm = false
                editing = null
            },
            onDismiss = {
                showForm = false
                editing = null
            }
        )
    }

    // 删除确认弹窗
    deleteTarget?.let { server ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = "确认删除",
            message = "确定删除 MCP 服务「${server.displayName}」吗？",
            confirmText = "删除",
            onConfirm = {
                server.id?.let { viewModel.delete(it) }
                deleteTarget = null
            }
        )
    }

    // 测试结果弹窗
    testResult?.let { result ->
        NekoDialog(
            onDismiss = { viewModel.clearTestResult() },
            title = "测试结果",
            message = result,
            confirmText = "确定",
            onConfirm = { viewModel.clearTestResult() },
            cancelText = null,
            onCancel = null
        )
    }

    // 工具列表弹窗
    tools?.let { toolsJson ->
        NekoDialog(
            onDismiss = { viewModel.clearTools() },
            title = "MCP 工具列表",
            confirmText = "关闭",
            onConfirm = { viewModel.clearTools() },
            cancelText = null,
            onCancel = null
        ) {
            Text(
                text = toolsJson.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

/** 单个 MCP 服务卡片 */
@Composable
private fun McpServerCard(
    server: McpServer,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTest: () -> Unit,
    onTools: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：名称 + 连接状态 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = server.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 连接状态标记
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (server.connected) SuccessGreen.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (server.connected) "已连接" else "未连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (server.connected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            // 操作菜单
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menuExpanded = false; onEdit() })
                    if (server.connected) {
                        DropdownMenuItem(text = { Text("断开") }, onClick = { menuExpanded = false; onDisconnect() })
                    } else {
                        DropdownMenuItem(text = { Text("连接") }, onClick = { menuExpanded = false; onConnect() })
                    }
                    DropdownMenuItem(text = { Text("测试") }, onClick = { menuExpanded = false; onTest() })
                    DropdownMenuItem(text = { Text("查看工具") }, onClick = { menuExpanded = false; onTools() })
                    // 内置 MCP 不可删除
                    if (!server.builtin) {
                        DropdownMenuItem(text = {
                            Text("删除", color = ErrorRed)
                        }, onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 信息行
        Text("传输方式: ${server.transport}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("工具数量: ${server.toolCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // HTTP 模式显示 url，stdio 模式显示 command
        if (server.transport == "streamable-http") {
            Text("URL: ${server.url ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Text("命令: ${server.command ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!server.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(server.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(8.dp))

        // 启用 / 自动连接状态
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (server.enabled) "已启用" else "已禁用",
                style = MaterialTheme.typography.labelSmall,
                color = if (server.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (server.autoConnect) {
                Text("自动连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * MCP 服务新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerFormDialog(
    initial: McpServer?,
    onConfirm: (McpServerRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var transport by remember { mutableStateOf(initial?.transport ?: "streamable-http") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var autoConnect by remember { mutableStateOf(initial?.autoConnect ?: false) }
    // streamable-http 模式
    var url by remember { mutableStateOf(initial?.url ?: "") }
    // stdio 模式
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var argsText by remember { mutableStateOf(initial?.args?.joinToString(" ") ?: "") }
    var envText by remember { mutableStateOf(initial?.env?.toEnvText() ?: "") }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) "新建 MCP 服务" else "编辑 MCP 服务",
        confirmText = "保存",
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            val req = McpServerRequest(
                name = name.trim(),
                transport = transport,
                description = description.trim().takeIf { it.isNotBlank() },
                enabled = enabled,
                autoConnect = autoConnect,
                url = if (transport == "streamable-http") url.trim().takeIf { it.isNotBlank() } else null,
                command = if (transport == "stdio") command.trim().takeIf { it.isNotBlank() } else null,
                args = if (transport == "stdio") argsText.splitWhitespace() else emptyList(),
                env = if (transport == "stdio") envText.toEnvObject() else null
            )
            onConfirm(req)
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 名称（必填）
            LabeledField("名称 *")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            // 传输方式下拉
            LabeledField("传输方式")
            DropdownField(
                value = transport,
                options = listOf("streamable-http", "stdio"),
                onSelect = { transport = it }
            )
            Spacer(Modifier.height(8.dp))

            // 描述
            LabeledField("描述")
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            // 根据传输方式显示不同字段
            if (transport == "streamable-http") {
                LabeledField("URL")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    placeholder = { Text("https://example.com/mcp") }
                )
                Spacer(Modifier.height(8.dp))
            } else {
                LabeledField("命令 (command)")
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    placeholder = { Text("npx") }
                )
                Spacer(Modifier.height(8.dp))
                LabeledField("参数 (args，空格分隔)")
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    placeholder = { Text("-y @modelcontextprotocol/server-filesystem") }
                )
                Spacer(Modifier.height(8.dp))
                LabeledField("环境变量 (env，每行 KEY=VALUE)")
                OutlinedTextField(
                    value = envText,
                    onValueChange = { envText = it },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    placeholder = { Text("API_KEY=xxx\nDEBUG=true") }
                )
                Spacer(Modifier.height(8.dp))
            }

            // 启用开关
            ToggleRow(label = "启用", checked = enabled, onCheckedChange = { enabled = it })
            Spacer(Modifier.height(8.dp))
            // 自动连接开关
            ToggleRow(label = "自动连接", checked = autoConnect, onCheckedChange = { autoConnect = it })
        }
    }
}

// ==================== 通用辅助组件与扩展 ====================

/** 字段标签 */
@Composable
private fun LabeledField(label: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 启用开关行 */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 下拉选择字段 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        @Suppress("DEPRECATION")
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 统一的输入框配色 */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
)

/** 将 JsonElement 形式的 env 转换为 "KEY=VALUE" 多行文本 */
private fun JsonElement?.toEnvText(): String {
    if (this == null || !this.isJsonObject) return ""
    return try {
        this.asJsonObject.entrySet().joinToString("\n") { (k, v) ->
            "$k=${if (v.isJsonPrimitive) v.asString else v.toString()}"
        }
    } catch (e: Exception) {
        ""
    }
}

/** 将 "KEY=VALUE" 多行文本解析为 JsonObject */
private fun String.toEnvObject(): JsonObject? {
    val obj = JsonObject()
    var hasEntry = false
    for (line in this.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val idx = trimmed.indexOf('=')
        if (idx <= 0) continue
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isNotEmpty()) {
            obj.add(key, JsonPrimitive(value))
            hasEntry = true
        }
    }
    return if (hasEntry) obj else null
}

/** 按空格拆分参数（连续空格视为一个分隔符） */
private fun String.splitWhitespace(): List<String> =
    this.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
