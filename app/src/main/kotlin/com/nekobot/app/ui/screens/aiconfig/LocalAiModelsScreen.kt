package com.nekobot.app.ui.screens.aiconfig

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.ai.LocalProtocols
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
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
            showToast("已保存")
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            ServiceContainer.unified.deleteLocalAiModel(id)
            showToast("已删除")
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch {
            ServiceContainer.unified.setActiveLocalModel(id)
            showToast("已设为当前模型")
        }
    }

    fun testModel(model: LocalAiModelEntity, onResult: (String) -> Unit) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val result = ServiceContainer.unified.testLocalModel(model)
                if (result?.error != null) {
                    onResult("失败: ${result.error}")
                } else {
                    onResult("成功: ${result?.content?.take(50)}")
                }
            } catch (e: Exception) {
                onResult("异常: ${e.message}")
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
                title = { Text("本地 AI 模型", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        logRecords = LocalLogger.listLogs()
                        showLogs = true
                    }) {
                        Icon(Icons.Filled.Description, contentDescription = "本地日志", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        editingModel = null
                        showEditDialog = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加", tint = MaterialTheme.colorScheme.onSurface)
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
                    Text("暂无 AI 模型，点击右上角 + 添加", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = "删除模型",
            message = "确定删除「${model.name}」吗？",
            confirmText = "删除",
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
            title = "测试 ${model.name}",
            confirmText = "关闭",
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
                    Text("正在测试…", style = MaterialTheme.typography.bodyMedium)
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
            title = "本地日志 (${logRecords.size})",
            confirmText = "关闭",
            onConfirm = { showLogs = false },
            cancelText = "清空",
            onCancel = {
                LocalLogger.clear()
                logRecords = emptyList()
            }
        ) {
            if (logRecords.isEmpty()) {
                Text(
                    text = "暂无日志",
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
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Memory,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    "${model.protocol} · ${model.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val purposeLabel = remember(model.purpose) {
                    when (model.purpose) {
                        "chat" -> "💬 对话"
                        "vision" -> "🖼️ 图片理解"
                        "video" -> "🎬 视频理解"
                        "tts" -> "🔊 语音合成"
                        "stt" -> "🎤 语音识别"
                        "embedding" -> "📊 向量嵌入"
                        "image_generation" -> "🎨 图片生成"
                        else -> model.purpose
                    }
                }
                Text(
                    purposeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isActive) "当前模型" else "设为当前") },
                        onClick = {
                            menuExpanded = false
                            if (!isActive) onSetActive()
                        },
                        enabled = !isActive
                    )
                    DropdownMenuItem(
                        text = { Text("测试") },
                        onClick = {
                            menuExpanded = false
                            onTest()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
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
            "chat" to "💬 对话模型",
            "vision" to "🖼️ 图片理解",
            "video" to "🎬 视频理解",
            "tts" to "🔊 语音合成",
            "stt" to "🎤 语音识别",
            "embedding" to "📊 向量嵌入",
            "image_generation" to "🎨 图片生成"
        )
    }
    val now = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) }

    var name by remember(model) { mutableStateOf(model?.name ?: "") }
    var protocol by remember(model) { mutableStateOf(model?.protocol ?: protocols.first()) }
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

    val purposeLabel = remember(purpose) {
        purposes.firstOrNull { it.first == purpose }?.second ?: "💬 对话模型"
    }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (model == null) "新建 AI 模型" else "编辑 AI 模型",
        confirmText = "保存",
        onConfirm = {
            if (name.isNotBlank() && apiKey.isNotBlank() && baseUrl.isNotBlank() && modelName.isNotBlank()) {
                val entity = LocalAiModelEntity(
                    id = model?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    protocol = protocol,
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
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // 用途选择（7 种 purpose）
            Box {
                OutlinedTextField(
                    value = purposeLabel,
                    onValueChange = {},
                    label = { Text("用途 (purpose)") },
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
                    purposes.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
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
                    label = { Text("协议") },
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
                label = { Text("模型名") },
                placeholder = { Text("gpt-4o / claude-sonnet-4-20250514") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "自动拼接路径",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = appendPath,
                    onCheckedChange = { appendPath = it }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter { c -> c.isDigit() } },
                    label = { Text("优先级") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("温度") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text("最大 token") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = topP,
                    onValueChange = { topP = it },
                    label = { Text("Top P") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            if (purpose != "chat") {
                Text(
                    when (purpose) {
                        "vision" -> "用于图片消息理解，将作为图片描述的回退模型"
                        "video" -> "用于视频消息理解（暂未在本地模式自动调用）"
                        "tts" -> "用于文本转语音，可在聊天界面手动触发"
                        "stt" -> "用于语音转文字，可在录音后自动调用"
                        "embedding" -> "用于向量嵌入（本地模式暂未接入知识库）"
                        "image_generation" -> "用于 AI 生图（本地模式暂未接入）"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
