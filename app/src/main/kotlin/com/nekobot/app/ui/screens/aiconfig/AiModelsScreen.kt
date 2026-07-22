package com.nekobot.app.ui.screens.aiconfig

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.nekobot.app.R
import com.nekobot.app.data.model.AiModel
import com.nekobot.app.data.model.AiModelRequest
import com.nekobot.app.data.model.FetchModelsRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.ModelCardDivider
import com.nekobot.app.ui.components.ModelCardFrame
import com.nekobot.app.ui.components.ModelCardMenuButton
import com.nekobot.app.ui.components.ModelEndpointRow
import com.nekobot.app.ui.components.ModelInfoChip
import com.nekobot.app.ui.components.ModelStatusBadge
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.ProviderLogo
import com.nekobot.app.ui.theme.SuccessGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 协议选项：key 为提交给后端的协议标识，displayName 为 UI 显示的友好名称
 */
data class ProtocolOption(val key: String, val displayName: String)

/**
 * AI 模型管理页 ViewModel
 */
class AiModelsViewModel : BaseViewModel() {

    private val _models = MutableStateFlow<List<AiModel>>(emptyList())
    val models: StateFlow<List<AiModel>> = _models.asStateFlow()

    private val _protocols = MutableStateFlow<List<ProtocolOption>>(emptyList())
    val protocols: StateFlow<List<ProtocolOption>> = _protocols.asStateFlow()

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
            onSuccess = { json -> _protocols.value = parseProtocols(json).ifEmpty { listOf(ProtocolOption("openai", "openai")) } },
            onError = { _protocols.value = listOf(ProtocolOption("openai", "openai")) }
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
                showToast(string(R.string.aimodels_applied))
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
                showToast(string(R.string.aimodels_cloned))
                load()
            }
        )
    }

    fun test(id: String) {
        launchResult(
            block = { repo.testAiModel(id) },
            onSuccess = { res ->
                _testResult.value = string(
                    R.string.aiconfig_test_result,
                    if (res.success == true) string(R.string.aiconfig_test_success) else string(R.string.aiconfig_test_fail),
                    res.message ?: string(R.string.common_none)
                )
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
                    showError(res.message ?: string(R.string.aimodels_fetch_failed))
                }
            }
        )
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    /**
     * 将 JsonElement 解析为协议选项列表。
     * 兼容三种服务端返回格式：
     * 1. 字符串数组：["openai", "anthropic_messages"]
     * 2. 协议对象数组：[{"key":"openai_responses","name":"OpenAI Responses API",...}]
     * 3. 包裹对象：{"protocols": [...]} / {"items": [...]} / {"list": [...]}
     */
    private fun parseProtocols(json: JsonElement): List<ProtocolOption> {
        return try {
            when {
                json.isJsonArray -> json.asJsonArray.mapNotNull { el ->
                    when {
                        el.isJsonObject -> {
                            val obj = el.asJsonObject
                            val key = obj.get("key")?.takeIf { !it.isJsonNull }?.asString
                                ?: obj.get("protocol_key")?.takeIf { !it.isJsonNull }?.asString
                                ?: obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                                ?: return@mapNotNull null
                            val displayName = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: key
                            ProtocolOption(key, displayName)
                        }
                        else -> try { ProtocolOption(el.asString, el.asString) } catch (e: Exception) { null }
                    }
                }
                json.isJsonObject -> {
                    val obj = json.asJsonObject
                    obj.get("protocols")?.let { return parseProtocols(it) }
                    obj.get("items")?.let { return parseProtocols(it) }
                    obj.get("list")?.let { return parseProtocols(it) }
                    obj.keySet().map { ProtocolOption(it, it) }
                }
                else -> try { listOf(ProtocolOption(json.asString, json.asString)) } catch (e: Exception) { emptyList() }
            }
        } catch (e: Exception) {
            emptyList()
        }
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
                title = { Text(stringResource(R.string.aimodels_management_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingModel = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.aimodels_new_model))
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
                EmptyState(title = stringResource(R.string.aimodels_empty_title), hint = stringResource(R.string.aimodels_empty_hint))
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
            title = stringResource(R.string.aimodels_confirm_delete),
            message = stringResource(R.string.aimodels_delete_message, model.displayName),
            confirmText = stringResource(R.string.common_delete),
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
            title = stringResource(R.string.aiconfig_test_result_title),
            message = result,
            confirmText = stringResource(R.string.common_ok),
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
    val isActive = model.active == true
    val isEnabled = model.enabled == true
    var menuExpanded by remember { mutableStateOf(false) }

    ModelCardFrame(
        isActive = isActive,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 身份区：Logo、显示名称与真实模型名保持明确层级。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderLogo(
                provider = model.provider,
                baseUrl = model.baseUrl,
                model = model.model,
                size = 46.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        ModelStatusBadge(
                            text = stringResource(R.string.aimodels_active_badge),
                            color = SuccessGreen
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = model.model ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Box {
                ModelCardMenuButton(
                    contentDescription = stringResource(R.string.aimodels_action),
                    onClick = { menuExpanded = true }
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.aimodels_clone)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = { menuExpanded = false; onClone() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 协议和用途保持弱强调，激活状态只在标题区出现一次。
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModelInfoChip(text = model.protocol ?: "—")
            ModelInfoChip(
                text = model.purpose ?: "—",
                accent = true,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        if (!model.baseUrl.isNullOrBlank()) {
            Spacer(Modifier.height(9.dp))
            ModelEndpointRow(url = model.baseUrl)
        }

        ModelCardDivider(modifier = Modifier.padding(vertical = 10.dp))

        // 高频操作使用轻量文字按钮，避免底部再形成一层按钮卡片。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { onToggle() }
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.aimodels_enabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onTest,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.aiconfig_test))
            }
            TextButton(
                onClick = onApply,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.common_apply))
            }
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
    protocols: List<ProtocolOption>,
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

    val unnamedFallback = stringResource(R.string.aimodels_unnamed)
    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.aimodels_new_model) else stringResource(R.string.aimodels_edit_model),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            val req = AiModelRequest(
                name = name.ifBlank { unnamedFallback },
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
                label = { Text(stringResource(R.string.aimodels_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            val protocolOptions = if (protocols.isEmpty()) listOf(ProtocolOption("openai", "openai")) else protocols
            DropdownField(
                label = stringResource(R.string.aimodels_protocol_label),
                value = protocol,
                options = protocolOptions.map { it.key },
                onSelect = { protocol = it },
                labelFor = { key -> protocolOptions.find { it.key == key }?.displayName ?: key }
            )

            OutlinedTextField(
                value = provider,
                onValueChange = { provider = it },
                label = { Text(stringResource(R.string.aimodels_provider)) },
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
                    label = stringResource(R.string.aimodels_model_label),
                    value = model,
                    options = availableModels,
                    onSelect = { model = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    onFetchModels(baseUrl, apiKey.ifBlank { null }, protocol.ifBlank { null })
                }) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = stringResource(R.string.aimodels_fetch_available), tint = MaterialTheme.colorScheme.primary)
                }
            }

            val purposeOptions = if (purposes.isEmpty()) listOf("chat") else purposes
            DropdownField(
                label = stringResource(R.string.aimodels_purpose_label),
                value = purpose,
                options = purposeOptions,
                onSelect = { purpose = it }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.aimodels_enabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
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
 * @param labelFor 可选的显示名转换函数：传入 option 的 value，返回用于 UI 显示的文本。
 *                 若为 null，则直接显示 value 本身。用于协议等需要 key/label 分离的场景。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelFor: ((String) -> String)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = labelFor?.invoke(value) ?: value
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        @Suppress("DEPRECATION")
        OutlinedTextField(
            value = displayValue,
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
                    text = { Text(stringResource(R.string.aimodels_no_options)) },
                    onClick = { expanded = false }
                )
            } else {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelFor?.invoke(option) ?: option) },
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
