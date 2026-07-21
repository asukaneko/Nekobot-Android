package com.nekobot.app.ui.screens.aiconfig

import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.ai.LocalProtocols
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.ModelCardDivider
import com.nekobot.app.ui.components.ModelCardFrame
import com.nekobot.app.ui.components.ModelCardMenuButton
import com.nekobot.app.ui.components.ModelEndpointRow
import com.nekobot.app.ui.components.ModelInfoChip
import com.nekobot.app.ui.components.ModelStatusBadge
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.ProviderLogo
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 本地 AI 模型管理 ViewModel：增删改查本地模型、设置激活模型、测试连通性。
 */
class LocalAiModelsViewModel : BaseViewModel() {

    private val _models = MutableStateFlow<List<LocalAiModelEntity>>(emptyList())
    val models: StateFlow<List<LocalAiModelEntity>> = _models.asStateFlow()

    private val _activeModel = MutableStateFlow<LocalAiModelEntity?>(null)
    val activeModel: StateFlow<LocalAiModelEntity?> = _activeModel.asStateFlow()

    init {
        observeModels()
    }

    private fun observeModels() {
        viewModelScope.launch {
            ServiceContainer.unified.observeLocalAiModels()?.collect { list ->
                _models.value = list
            }
        }
        viewModelScope.launch {
            ServiceContainer.unified.observeLocalActiveModel()?.collect { model ->
                _activeModel.value = model
            }
        }
    }

    fun saveModel(model: LocalAiModelEntity) {
        viewModelScope.launch {
            ServiceContainer.unified.upsertLocalAiModel(model)
            showToast(string(R.string.localai_saved))
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            ServiceContainer.unified.deleteLocalAiModel(id)
            showToast(string(R.string.localai_deleted))
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch {
            ServiceContainer.unified.setActiveLocalModel(id)
            showToast(string(R.string.localai_set_active))
        }
    }

    fun testModel(model: LocalAiModelEntity, onResult: (String) -> Unit) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val result = ServiceContainer.unified.testLocalModel(model)
                if (result?.error != null) {
                    onResult(string(R.string.localai_test_failed, result.error))
                } else {
                    onResult(string(R.string.localai_test_success, result?.content?.take(50) ?: ""))
                }
            } catch (e: Exception) {
                onResult(string(R.string.localai_test_exception, e.message ?: ""))
            } finally {
                setLoading(false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiModelsScreen(onBack: () -> Unit) {
    val vm: LocalAiModelsViewModel = viewModel()
    val models by vm.models.collectAsState()
    val activeModel by vm.activeModel.collectAsState()
    val loading by vm.loading.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    var showEditDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<LocalAiModelEntity?>(null) }
    var deletingModel by remember { mutableStateOf<LocalAiModelEntity?>(null) }
    var testingModel by remember { mutableStateOf<LocalAiModelEntity?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var logRecords by remember { mutableStateOf<List<LocalLogger.Record>>(emptyList()) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aiconfig_local_ai_models), color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        logRecords = LocalLogger.listLogs()
                        showLogs = true
                    }) {
                        Icon(Icons.Filled.Description, contentDescription = stringResource(R.string.localai_local_logs), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        editingModel = null
                        showEditDialog = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onSurface)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (models.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.localai_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(models, key = { it.id }) { model ->
                        ModelCard(
                            model = model,
                            isActive = model.active,
                            onSetActive = { vm.setActive(model.id) },
                            onEdit = {
                                editingModel = model
                                showEditDialog = true
                            },
                            onDelete = { deletingModel = model },
                            onTest = {
                                testingModel = model
                                testResult = null
                                vm.testModel(model) { testResult = it }
                            }
                        )
                    }
                }
            }
        }

        LoadingOverlay(visible = loading)
    }

    // 编辑/新建对话框
    if (showEditDialog) {
        LocalAiModelEditDialog(
            model = editingModel,
            onDismiss = {
                showEditDialog = false
                editingModel = null
            },
            onSave = { model ->
                vm.saveModel(model)
                showEditDialog = false
                editingModel = null
            }
        )
    }

    // 删除确认
    deletingModel?.let { model ->
        NekoDialog(
            onDismiss = { deletingModel = null },
            title = stringResource(R.string.localai_delete_title),
            message = stringResource(R.string.localai_delete_message, model.name),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                vm.deleteModel(model.id)
                deletingModel = null
            }
        )
    }

    // 测试结果
    testingModel?.let { model ->
        NekoDialog(
            onDismiss = {
                testingModel = null
                testResult = null
            },
            title = stringResource(R.string.localai_test_title, model.name),
            confirmText = stringResource(R.string.common_close),
            onConfirm = {
                testingModel = null
                testResult = null
            },
            cancelText = null,
            onCancel = null
        ) {
            if (testResult == null) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.localai_testing), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    text = testResult ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    // 本地日志查看
    if (showLogs) {
        NekoDialog(
            onDismiss = { showLogs = false },
            title = stringResource(R.string.localai_logs_title, logRecords.size),
            confirmText = stringResource(R.string.common_close),
            onConfirm = { showLogs = false },
            cancelText = stringResource(R.string.common_clear),
            onCancel = {
                LocalLogger.clear()
                logRecords = emptyList()
            }
        ) {
            if (logRecords.isEmpty()) {
                Text(
                    text = stringResource(R.string.localai_no_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logRecords, key = { "${it.time}_${it.tag}_${it.message.hashCode()}" }) { record ->
                        LocalLogCard(record)
                    }
                }
            }
        }
    }
}

/** 本地日志单条卡片：左侧等级色条 + 时间 + tag + 消息。 */
@Composable
private fun LocalLogCard(record: LocalLogger.Record) {
    val levelColor = when (record.level.lowercase()) {
        "error" -> MaterialTheme.colorScheme.error
        "warning" -> Color(0xFFFFB347)
        "debug" -> Color(0xFF6BAED6)
        else -> MaterialTheme.colorScheme.primary
    }
    val levelLabel = when (record.level.lowercase()) {
        "warning" -> "WARN"
        else -> record.level.uppercase()
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 10,
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(levelColor)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = levelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = record.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (record.tag.isNotBlank()) {
                    Text(
                        text = record.tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalAiModelEntity,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val purposeLabel = when (model.purpose) {
        "chat" -> stringResource(R.string.localai_purpose_chat)
        "vision" -> stringResource(R.string.localai_purpose_vision)
        "video" -> stringResource(R.string.localai_purpose_video)
        "tts" -> stringResource(R.string.localai_purpose_tts)
        "stt" -> stringResource(R.string.localai_purpose_stt)
        "embedding" -> stringResource(R.string.localai_purpose_embedding)
        "image_generation" -> stringResource(R.string.localai_purpose_image_generation)
        else -> model.purpose
    }

    ModelCardFrame(
        isActive = isActive,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 身份区与服务器模型卡片保持一致，切换模式时不改变阅读路径。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderLogo(
                provider = model.provider,
                baseUrl = model.baseUrl,
                model = model.model,
                size = 52.dp
            )
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = model.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Box {
                ModelCardMenuButton(
                    contentDescription = stringResource(R.string.localai_more),
                    onClick = { menuExpanded = true }
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive) {
                ModelStatusBadge(text = stringResource(R.string.localai_current_model))
            }
            ModelInfoChip(text = model.protocol)
            ModelInfoChip(
                text = purposeLabel,
                accent = true,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        if (model.baseUrl.isNotBlank()) {
            Spacer(Modifier.height(11.dp))
            ModelEndpointRow(url = model.baseUrl)
        }

        ModelCardDivider(modifier = Modifier.padding(vertical = 13.dp))

        // 高频操作直接露出，避免每次测试或切换模型都要打开菜单。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.aiconfig_test), maxLines = 1)
            }
            Button(
                onClick = onSetActive,
                enabled = !isActive,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                )
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isActive) stringResource(R.string.localai_current_model)
                    else stringResource(R.string.localai_set_current),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LocalAiModelEditDialog(
    model: LocalAiModelEntity?,
    onDismiss: () -> Unit,
    onSave: (LocalAiModelEntity) -> Unit
) {
    val protocols = remember { LocalProtocols.names() }
    val purposes = remember {
        listOf(
            "chat" to R.string.localai_purpose_chat_model,
            "vision" to R.string.localai_purpose_vision,
            "video" to R.string.localai_purpose_video,
            "tts" to R.string.localai_purpose_tts,
            "stt" to R.string.localai_purpose_stt,
            "embedding" to R.string.localai_purpose_embedding,
            "image_generation" to R.string.localai_purpose_image_generation
        )
    }
    val now = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) }

    var name by remember(model) { mutableStateOf(model?.name ?: "") }
    var protocol by remember(model) { mutableStateOf(model?.protocol ?: protocols.first()) }
    var provider by remember(model) { mutableStateOf(model?.provider ?: "") }
    var apiKey by remember(model) { mutableStateOf(model?.apiKey ?: "") }
    var baseUrl by remember(model) { mutableStateOf(model?.baseUrl ?: "") }
    var modelName by remember(model) { mutableStateOf(model?.model ?: "") }
    var purpose by remember(model) { mutableStateOf(model?.purpose ?: "chat") }
    var priority by remember(model) { mutableStateOf(model?.priority.toString()) }
    var temperature by remember(model) { mutableStateOf(model?.temperature?.toString() ?: "") }
    var maxTokens by remember(model) { mutableStateOf(model?.maxTokens?.toString() ?: "") }
    var topP by remember(model) { mutableStateOf(model?.topP?.toString() ?: "") }
    var appendPath by remember(model) { mutableStateOf(model?.appendBaseUrlPath ?: true) }
    var protocolMenuExpanded by remember { mutableStateOf(false) }
    var purposeMenuExpanded by remember { mutableStateOf(false) }

    val purposeLabelResId = remember(purpose) {
        purposes.firstOrNull { it.first == purpose }?.second ?: R.string.localai_purpose_chat_model
    }
    val purposeLabel = stringResource(purposeLabelResId)

    NekoDialog(
        onDismiss = onDismiss,
        title = if (model == null) stringResource(R.string.localai_new_model) else stringResource(R.string.localai_edit_model),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (name.isNotBlank() && apiKey.isNotBlank() && baseUrl.isNotBlank() && modelName.isNotBlank()) {
                val entity = LocalAiModelEntity(
                    id = model?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    protocol = protocol,
                    provider = provider.trim().ifBlank { null },
                    apiKey = apiKey.trim(),
                    baseUrl = baseUrl.trim(),
                    model = modelName.trim(),
                    purpose = purpose,
                    priority = priority.toIntOrNull() ?: 0,
                    active = model?.active ?: false,
                    temperature = temperature.toFloatOrNull(),
                    maxTokens = maxTokens.toIntOrNull(),
                    topP = topP.toFloatOrNull(),
                    appendBaseUrlPath = appendPath,
                    createdAt = model?.createdAt ?: now
                )
                onSave(entity)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
            // 用途选择（7 种 purpose）
            Box {
                OutlinedTextField(
                    value = purposeLabel,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.aimodels_purpose_label)) },
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { purposeMenuExpanded = true },
                    trailingIcon = {
                        IconButton(onClick = { purposeMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                    }
                )
                DropdownMenu(
                    expanded = purposeMenuExpanded,
                    onDismissRequest = { purposeMenuExpanded = false }
                ) {
                    purposes.forEach { (key, labelResId) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelResId)) },
                            onClick = {
                                purpose = key
                                purposeMenuExpanded = false
                            }
                        )
                    }
                }
            }
            // 协议选择
            Box {
                OutlinedTextField(
                    value = protocol,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.localai_protocol)) },
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { protocolMenuExpanded = true },
                    trailingIcon = {
                        IconButton(onClick = { protocolMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                    }
                )
                DropdownMenu(
                    expanded = protocolMenuExpanded,
                    onDismissRequest = { protocolMenuExpanded = false }
                ) {
                    protocols.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p) },
                            onClick = {
                                protocol = p
                                protocolMenuExpanded = false
                            }
                        )
                    }
                }
            }
            // Provider（仅 STT 等多协议 purpose 实际生效；留空走默认）
            OutlinedTextField(
                value = provider,
                onValueChange = { provider = it },
                label = { Text(stringResource(R.string.localai_provider)) },
                placeholder = { Text(stringResource(R.string.localai_provider_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text(stringResource(R.string.localai_model_name)) },
                placeholder = { Text("gpt-4o / claude-sonnet-4-20250514") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.localai_append_path),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = appendPath,
                    onCheckedChange = { appendPath = it }
                )
            }
            OutlinedTextField(
                value = priority,
                onValueChange = { priority = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.localai_priority)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = temperature,
                onValueChange = { temperature = it },
                label = { Text(stringResource(R.string.localai_temperature)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                label = { Text(stringResource(R.string.localai_max_tokens)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = topP,
                onValueChange = { topP = it },
                label = { Text("Top P") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (purpose != "chat") {
                Text(
                    when (purpose) {
                        "vision" -> stringResource(R.string.localai_purpose_hint_vision)
                        "video" -> stringResource(R.string.localai_purpose_hint_video)
                        "tts" -> stringResource(R.string.localai_purpose_hint_tts)
                        "stt" -> stringResource(R.string.localai_purpose_hint_stt)
                        "embedding" -> stringResource(R.string.localai_purpose_hint_embedding)
                        "image_generation" -> stringResource(R.string.localai_purpose_hint_image_generation)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
