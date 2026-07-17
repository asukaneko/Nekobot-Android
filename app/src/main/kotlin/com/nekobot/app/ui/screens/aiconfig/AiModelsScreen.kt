package com.nekobot.app.ui.screens.aiconfig

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.nekobot.app.data.model.AiModel
import com.nekobot.app.data.model.AiModelRequest
import com.nekobot.app.data.model.FetchModelsRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.OnPrimary
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.SuccessGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI 模型管理页 ViewModel
 */
class AiModelsViewModel : BaseViewModel() {

    private val _models = MutableStateFlow<List<AiModel>>(emptyList())
    val models: StateFlow<List<AiModel>> = _models.asStateFlow()

    private val _protocols = MutableStateFlow<List<String>>(emptyList())
    val protocols: StateFlow<List<String>> = _protocols.asStateFlow()

    private val _purposes = MutableStateFlow<List<String>>(emptyList())
    val purposes: StateFlow<List<String>> = _purposes.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init {
        load()
    }

    fun load() {
        launchResult(
            block = { repo.listAiModels() },
            onSuccess = { _models.value = it }
        )
        loadProtocols()
        loadPurposes()
    }

    fun loadProtocols() {
        launchResult(
            block = { repo.listProtocols() },
            onSuccess = { json -> _protocols.value = parseStringList(json).ifEmpty { listOf("openai") } },
            onError = { _protocols.value = listOf("openai") }
        )
    }

    fun loadPurposes() {
        launchResult(
            block = { repo.listPurposes() },
            onSuccess = { json -> _purposes.value = parseStringList(json).ifEmpty { listOf("chat") } },
            onError = { _purposes.value = listOf("chat") }
        )
    }

    fun create(req: AiModelRequest) {
        launchResult(
            block = { repo.createAiModel(req) },
            onSuccess = { load() }
        )
    }

    fun update(id: String, req: AiModelRequest) {
        launchResult(
            block = { repo.updateAiModel(id, req) },
            onSuccess = { load() }
        )
    }

    fun delete(id: String) {
        launchResult(
            block = { repo.deleteAiModel(id) },
            onSuccess = { load() }
        )
    }

    fun apply(id: String) {
        launchResult(
            block = { repo.applyAiModel(id) },
            onSuccess = {
                showToast("已应用")
                load()
            }
        )
    }

    fun toggle(id: String) {
        launchResult(
            block = { repo.toggleAiModel(id) },
            onSuccess = { load() }
        )
    }

    fun clone(id: String) {
        launchResult(
            block = { repo.cloneAiModel(id) },
            onSuccess = {
                showToast("已克隆")
                load()
            }
        )
    }

    fun test(id: String) {
        launchResult(
            block = { repo.testAiModel(id) },
            onSuccess = { res ->
                _testResult.value = buildString {
                    append("状态: ${if (res.success == true) "成功" else "失败"}\n")
                    append("消息: ${res.message ?: "无"}")
                }
            }
        )
    }

    fun fetchModels(baseUrl: String, apiKey: String?, protocol: String?) {
        val req = FetchModelsRequest(baseUrl = baseUrl, apiKey = apiKey, protocol = protocol)
        launchResult(
            block = { repo.fetchModels(req) },
            onSuccess = { res ->
                // 服务端返回的 models 是 [{id, name, ...}] 对象数组，提取 id 作为下拉选项
                val ids = res.models?.mapNotNull { it.id } ?: emptyList()
                _availableModels.value = ids
                if (res.success == false) {
                    showError(res.message ?: "获取模型列表失败")
                }
            }
        )
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    /** 将 JsonElement 解析为字符串列表 */
    private fun parseStringList(json: JsonElement): List<String> {
        return try {
            when {
                json.isJsonArray -> json.asJsonArray.map {
                    try { it.asString } catch (e: Exception) { it.toString() }
                }
                json.isJsonObject -> {
                    val obj = json.asJsonObject
                    obj.get("protocols")?.let { return parseStringList(it) }
                    obj.get("purposes")?.let { return parseStringList(it) }
                    obj.get("items")?.let { return parseStringList(it) }
                    obj.get("list")?.let { return parseStringList(it) }
                    obj.keySet().toList()
                }
                else -> listOf(json.asString)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelsScreen(onBack: () -> Unit) {
    val vm: AiModelsViewModel = viewModel()
    val models by vm.models.collectAsState()
    val protocols by vm.protocols.collectAsState()
    val purposes by vm.purposes.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val testResult by vm.testResult.collectAsState()
    val context = LocalContext.current

    var showForm by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<AiModel?>(null) }
    var deleteTarget by remember { mutableStateOf<AiModel?>(null) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 模型管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingModel = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建模型")
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
            if (models.isEmpty() && !loading) {
                EmptyState(title = "暂无模型", hint = "点击右上角创建第一个模型")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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
                    items(models, key = { it.id ?: it.name ?: it.hashCode().toString() }) { model ->
                        ModelCard(
                            model = model,
                            onToggle = { model.id?.let { vm.toggle(it) } },
                            onApply = { model.id?.let { vm.apply(it) } },
                            onTest = { model.id?.let { vm.test(it) } },
                            onClone = { model.id?.let { vm.clone(it) } },
                            onEdit = {
                                editingModel = model
                                showForm = true
                            },
                            onDelete = {
                                deleteTarget = model
                            }
                        )
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
    if (showForm) {
        AiModelFormDialog(
            initial = editingModel,
            protocols = protocols,
            purposes = purposes,
            availableModels = availableModels,
            onFetchModels = { baseUrl, apiKey, protocol ->
                vm.fetchModels(baseUrl, apiKey, protocol)
            },
            onConfirm = { req ->
                editingModel?.id?.let { id -> vm.update(id, req) } ?: vm.create(req)
                showForm = false
                editingModel = null
            },
            onDismiss = {
                showForm = false
                editingModel = null
            }
        )
    }

    // 删除确认弹窗
    deleteTarget?.let { model ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = "确认删除",
            message = "确定删除模型「${model.displayName}」吗？",
            confirmText = "删除",
            onConfirm = {
                model.id?.let { vm.delete(it) }
                deleteTarget = null
            }
        )
    }

    // 测试结果弹窗
    testResult?.let { result ->
        NekoDialog(
            onDismiss = { vm.clearTestResult() },
            title = "测试结果",
            message = result,
            confirmText = "确定",
            onConfirm = { vm.clearTestResult() },
            cancelText = null,
            onCancel = null
        )
    }
}

/**
 * 模型卡片
 */
@Composable
private fun ModelCard(
    model: AiModel,
    onToggle: () -> Unit,
    onApply: () -> Unit,
    onTest: () -> Unit,
    onClone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：名称 + 激活标记 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (model.active == true) {
                Box(
                    modifier = Modifier
                        .background(SuccessGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("已激活", style = MaterialTheme.typography.labelSmall, color = SuccessGreen)
                }
                Spacer(Modifier.width(8.dp))
            }
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
                    DropdownMenuItem(text = { Text("应用") }, onClick = { menuExpanded = false; onApply() })
                    DropdownMenuItem(text = { Text("测试") }, onClick = { menuExpanded = false; onTest() })
                    DropdownMenuItem(text = { Text("克隆") }, onClick = { menuExpanded = false; onClone() })
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 信息行
        Text("协议: ${model.protocol ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("地址: ${model.baseUrl ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("用途: ${model.purpose ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("模型: ${model.model ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(8.dp))

        // 启用开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(
                checked = model.enabled ?: false,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

/**
 * 模型新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiModelFormDialog(
    initial: AiModel?,
    protocols: List<String>,
    purposes: List<String>,
    availableModels: List<String>,
    onFetchModels: (baseUrl: String, apiKey: String?, protocol: String?) -> Unit,
    onConfirm: (AiModelRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var protocol by remember { mutableStateOf(initial?.protocol ?: "") }
    var provider by remember { mutableStateOf(initial?.provider ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var purpose by remember { mutableStateOf(initial?.purpose ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) "新建模型" else "编辑模型",
        confirmText = "保存",
        onConfirm = {
            val req = AiModelRequest(
                name = name.ifBlank { "未命名模型" },
                protocol = protocol.ifBlank { null },
                provider = provider.ifBlank { null },
                apiKey = apiKey.ifBlank { null },
                baseUrl = baseUrl.ifBlank { null },
                model = model.ifBlank { null },
                enabled = enabled,
                purpose = purpose.ifBlank { null }
            )
            onConfirm(req)
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            val protocolOptions = if (protocols.isEmpty()) listOf("openai") else protocols
            DropdownField(
                label = "协议 (protocol)",
                value = protocol,
                options = protocolOptions,
                onSelect = { protocol = it }
            )

            OutlinedTextField(
                value = provider,
                onValueChange = { provider = it },
                label = { Text("提供商 (provider)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            // 模型选择 + 拉取按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DropdownField(
                    label = "模型 (model)",
                    value = model,
                    options = availableModels,
                    onSelect = { model = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    onFetchModels(baseUrl, apiKey.ifBlank { null }, protocol.ifBlank { null })
                }) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = "拉取可用模型", tint = MaterialTheme.colorScheme.primary)
                }
            }

            val purposeOptions = if (purposes.isEmpty()) listOf("chat") else purposes
            DropdownField(
                label = "用途 (purpose)",
                value = purpose,
                options = purposeOptions,
                onSelect = { purpose = it }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
            }
        }
    }
}

/**
 * 下拉选择字段
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
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
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("暂无选项") },
                    onClick = { expanded = false }
                )
            } else {
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
}
