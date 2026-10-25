package com.nekobot.app.ui.screens.aiconfig

import androidx.compose.ui.platform.LocalResources

import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedButton as OutlinedButton
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.ApiKey
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.ModelCardDivider
import com.nekobot.app.ui.components.ModelCardFrame
import com.nekobot.app.ui.components.ModelCardMenuButton
import com.nekobot.app.ui.components.ModelEndpointRow
import com.nekobot.app.ui.components.ModelInfoChip
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.ProviderLogo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.nekobot.app.data.local.ai.ModelCapability
import com.nekobot.app.data.local.ai.ModelCapabilityReport
import com.nekobot.app.data.local.ai.CapabilityObservation
import com.nekobot.app.data.local.ai.capabilityFingerprint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal val localModelPurposes = listOf(
    "chat",
    "live",
    "vision",
    "video",
    "tts",
    "stt",
    "embedding",
    "image_generation"
)

/**
 * 本地 AI 模型管理 ViewModel：增删改查本地模型、设置激活模型、测试连通性。
 */
class LocalAiModelsViewModel : BaseViewModel() {

    private val _modelsLoaded = MutableStateFlow(false)
    val modelsLoaded = _modelsLoaded.asStateFlow()

    private val _models = MutableStateFlow<List<LocalAiModelEntity>>(emptyList())
    val models: StateFlow<List<LocalAiModelEntity>> = _models.asStateFlow()

    private val _activeModel = MutableStateFlow<LocalAiModelEntity?>(null)
    val activeModel: StateFlow<LocalAiModelEntity?> = _activeModel.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    fun clearEditorModels() { _availableModels.value = emptyList(); clearError() }

    private val _apiKeys = MutableStateFlow<List<ApiKey>>(emptyList())
    val apiKeys: StateFlow<List<ApiKey>> = _apiKeys.asStateFlow()

    init {
        observeModels()
        loadApiKeys()
    }

    private fun observeModels() {
        viewModelScope.launch {
            ServiceContainer.unified.observeLocalAiModels()?.collect { list ->
                _models.value = list
                _modelsLoaded.value = true
            }
        }
        viewModelScope.launch {
            ServiceContainer.unified.observeLocalActiveModel()?.collect { model ->
                _activeModel.value = model
            }
        }
    }

    fun saveModel(model: LocalAiModelEntity, onSaved: () -> Unit = {}) {
        if (loading.value) return
        setLoading(true)
        clearError()
        viewModelScope.launch {
            try {
                check(ServiceContainer.prefs.isLocalMode)
                ServiceContainer.unified.upsertLocalAiModel(model)
                showToast(string(R.string.localai_saved))
                onSaved()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { showError(failure.message ?: string(R.string.common_unknown_error)) }
            finally { setLoading(false) }
        }
    }

    /** 复制模型配置，副本默认不激活，避免复制后改变当前聊天模型。 */
    fun duplicateModel(model: LocalAiModelEntity) {
        viewModelScope.launch {
            val suffix = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val duplicate = model.copy(
                id = UUID.randomUUID().toString(),
                name = "${model.name} (${string(R.string.localai_copy_suffix)} $suffix)",
                active = false,
                createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )
            ServiceContainer.unified.upsertLocalAiModel(duplicate)
            showToast(string(R.string.localai_duplicated, duplicate.name))
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

    /** 启用/停用模型：停用后不再参与故障转移队列，但保留当前选中(active)状态。 */
    fun setEnabled(model: LocalAiModelEntity, enabled: Boolean) {
        viewModelScope.launch {
            ServiceContainer.unified.setLocalAiModelEnabled(model.id, enabled)
            showToast(
                string(
                    if (enabled) R.string.localai_enabled_toast else R.string.localai_disabled_toast,
                    model.name
                )
            )
        }
    }

    fun testModel(model: LocalAiModelEntity, onResult: (String) -> Unit) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val result = ServiceContainer.unified.testLocalModel(model)
                if (result?.error != null) {
                    onResult(string(R.string.localai_test_failed, result.error))
                } else if (model.purpose == "image_generation") {
                    val imageCount = result?.content?.toIntOrNull() ?: 0
                    onResult(string(R.string.localai_image_generation_test_success, imageCount))
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

    private val _probeReport = MutableStateFlow<ModelCapabilityReport?>(null)
    val probeReport = _probeReport.asStateFlow()
    private val _probeRunning = MutableStateFlow(false)
    val probeRunning = _probeRunning.asStateFlow()
    private var probeJob: kotlinx.coroutines.Job? = null
    private var probeTag: String? = null
    private var cancelProbeRequest: (() -> Unit)? = null

    fun loadProbeReport(model: LocalAiModelEntity) {
        cancelProbe()
        _probeReport.value = null
        val target = unified
        val revision = ServiceContainer.dataSourceRevision.value
        probeJob = viewModelScope.launch {
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { target.modelCapabilityReport(model.id) }
            if (revision == ServiceContainer.dataSourceRevision.value) _probeReport.value = report
        }
    }

    fun startProbe(model: LocalAiModelEntity) {
        cancelProbe()
        val target = unified
        val revision = ServiceContainer.dataSourceRevision.value
        val tag = "model-probe:" + UUID.randomUUID()
        probeTag = tag
        cancelProbeRequest = { target.cancelModelCapabilityProbe(tag) }
        _probeRunning.value = true
        _probeReport.value = ModelCapabilityReport(model.id, capabilityFingerprint(model), "", emptyList())
        probeJob = viewModelScope.launch {
            try {
                val report = target.probeModelCapabilities(model, tag) { result ->
                    if (probeTag == tag && revision == ServiceContainer.dataSourceRevision.value)
                        _probeReport.update { it?.copy(results = it.results.filterNot { previous -> previous.capability == result.capability } + result) }
                }
                if (probeTag == tag && revision == ServiceContainer.dataSourceRevision.value) _probeReport.value = report
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (revision == ServiceContainer.dataSourceRevision.value) showError(error.message)
            } finally {
                if (probeTag == tag) { _probeRunning.value = false; probeTag = null; cancelProbeRequest = null }
            }
        }
    }

    fun cancelProbe() {
        cancelProbeRequest?.invoke()
        cancelProbeRequest = null
        probeTag = null
        probeJob?.cancel()
        probeJob = null
        _probeRunning.value = false
    }

    fun applyProbe(model: LocalAiModelEntity) {
        val report = _probeReport.value ?: return
        if (report.fingerprint != capabilityFingerprint(model)) return
        val observed = report.results.filter { it.observation == CapabilityObservation.OBSERVED }.map { it.capability }.toSet()
        saveModel(model.copy(supportsStream = model.supportsStream || ModelCapability.STREAM in observed,
            supportsTools = model.supportsTools || ModelCapability.TOOLS in observed,
            supportsVision = model.supportsVision || ModelCapability.VISION in observed,
            supportsReasoning = model.supportsReasoning || ModelCapability.REASONING in observed))
    }

    override fun onCleared() { cancelProbe(); super.onCleared() }

    private fun loadApiKeys() {
        viewModelScope.launch {
            runCatching { ServiceContainer.localRepository.listApiKeys() }
                .onSuccess { _apiKeys.value = it }
        }
    }

    /** 从其他页面（如 ApiKeysScreen）返回时刷新已保存 Key 列表，确保下拉菜单可用。 */
    fun refreshApiKeys() = loadApiKeys()

    fun resolveApiKey(id: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            ServiceContainer.localRepository.getApiKey(id)?.key?.let(onResult)
        }
    }

    fun fetchModels(
        baseUrl: String,
        apiKey: String,
        protocol: String,
        appendBaseUrlPath: Boolean,
        proxyUrl: String
    ) {
        clearError()
        _availableModels.value = emptyList()
        viewModelScope.launch {
            setLoading(true)
            try {
                _availableModels.value =
                    ServiceContainer.localRepository.fetchAvailableModels(
                        baseUrl,
                        apiKey,
                        protocol,
                        appendBaseUrlPath,
                        proxyUrl
                    )
                if (_availableModels.value.isEmpty()) {
                    showToast(string(R.string.localai_no_available_models))
                }
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.localai_fetch_models_failed))
            } finally {
                setLoading(false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAiModelsScreen(onBack: () -> Unit, onEditModel: (String?) -> Unit) {
    val textResources = LocalResources.current
    val vm: LocalAiModelsViewModel = viewModel()
    val models by vm.models.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var deletingModel by remember { mutableStateOf<LocalAiModelEntity?>(null) }
    var testingModel by remember { mutableStateOf<LocalAiModelEntity?>(null) }
    var probeModelId by remember { mutableStateOf<String?>(null) }
    val probeReport by vm.probeReport.collectAsStateWithLifecycle()
    val probeRunning by vm.probeRunning.collectAsStateWithLifecycle()
    val sourceRevision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    LaunchedEffect(sourceRevision) { probeModelId = null; vm.cancelProbe() }

    var testResult by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    var logRecords by remember { mutableStateOf<List<LocalLogger.Record>>(emptyList()) }
    var selectedPurpose by rememberSaveable { mutableStateOf<String?>(null) }
    val purposeOptions = remember(models) {
        val customPurposes = models
            .map { it.purpose }
            .filter { it.isNotBlank() && it !in localModelPurposes }
            .distinct()
            .sorted()
        localModelPurposes + customPurposes
    }
    val purposeCounts = remember(models) { models.groupingBy { it.purpose }.eachCount() }
    val filteredModels by remember(models, selectedPurpose) {
        derivedStateOf {
            selectedPurpose?.let { purpose -> models.filter { it.purpose == purpose } } ?: models
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    // 从 ApiKeysScreen 等页面返回时刷新已保存 Key 列表，确保编辑对话框中下拉菜单可用
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshApiKeys()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    ModelPricingCatalogRefreshAction()
                    IconButton(onClick = {
                        logRecords = LocalLogger.listLogs()
                        showLogs = true
                    }) {
                        Icon(Icons.Filled.Description, contentDescription = stringResource(R.string.localai_local_logs), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        vm.clearError()
                        onEditModel(null)
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
            Text(textResources.getString(R.string.single_role_manage_providers_and_models_here_characters_can_select_and_reorde),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
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
                LocalModelPurposeFilter(
                    options = purposeOptions,
                    counts = purposeCounts,
                    totalCount = models.size,
                    selectedPurpose = selectedPurpose,
                    onSelect = { selectedPurpose = it }
                )
                Spacer(Modifier.height(10.dp))
                if (filteredModels.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.localai_filter_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { selectedPurpose = null }) {
                            Text(stringResource(R.string.localai_filter_show_all))
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredModels, key = { it.id }) { model ->
                            ModelCard(
                                model = model,
                                isActive = model.active,
                                onSetActive = { vm.setActive(model.id) },
                                onToggleEnabled = { vm.setEnabled(model, !model.enabled) },
                                onEdit = {
                                    vm.clearError()
                                    onEditModel(model.id)
                                },
                                onDuplicate = { vm.duplicateModel(model) },
                                onDelete = { deletingModel = model },
                                onProbe = { probeModelId = model.id; vm.loadProbeReport(model) },
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
        }

        LoadingOverlay(visible = loading)
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

    models.firstOrNull { it.id == probeModelId }?.let { model ->
        ModelCapabilityDialog(model, probeReport?.takeIf { it.modelId == model.id }, probeRunning,
            onRun = { vm.startProbe(model) }, onStop = vm::cancelProbe,
            onApply = { vm.applyProbe(model) },
            onDismiss = { vm.cancelProbe(); probeModelId = null })
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
                    // 日志上限提升后同毫秒同内容的重复行会变多，key 里带上序号保证唯一
                    itemsIndexed(logRecords, key = { index, _ -> index }) { _, record ->
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
private fun LocalModelPurposeFilter(
    options: List<String>,
    counts: Map<String, Int>,
    totalCount: Int,
    selectedPurpose: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "all") {
            val selected = selectedPurpose == null
            FilterChip(
                selected = selected,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.localai_filter_all)) },
                leadingIcon = {
                    Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = { LocalModelFilterCount(totalCount, selected) }
            )
        }
        items(options, key = { it }) { purpose ->
            val selected = selectedPurpose == purpose
            FilterChip(
                selected = selected,
                onClick = { onSelect(purpose) },
                label = { Text(localModelPurposeLabel(purpose)) },
                leadingIcon = {
                    Icon(localModelPurposeIcon(purpose), contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = { LocalModelFilterCount(counts[purpose] ?: 0, selected) }
            )
        }
    }
}

@Composable
private fun LocalModelFilterCount(count: Int, selected: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun localModelPurposeIcon(purpose: String): ImageVector = when (purpose) {
    "chat" -> Icons.Filled.ChatBubble
    "live" -> Icons.Filled.GraphicEq
    "vision" -> Icons.Filled.Image
    "video" -> Icons.Filled.Movie
    "tts" -> Icons.AutoMirrored.Filled.VolumeUp
    "stt" -> Icons.Filled.Mic
    "embedding" -> Icons.Filled.Hub
    "image_generation" -> Icons.Filled.AutoAwesome
    else -> Icons.Filled.Tune
}

@Composable
private fun localModelPurposeLabel(purpose: String): String = when (purpose) {
    "chat" -> stringResource(R.string.aimodel_editor_purpose_chat)
    "live" -> stringResource(R.string.aimodel_editor_purpose_live)
    "vision" -> stringResource(R.string.aimodel_editor_purpose_vision)
    "video" -> stringResource(R.string.aimodel_editor_purpose_video)
    "tts" -> stringResource(R.string.aimodel_editor_purpose_tts)
    "stt" -> stringResource(R.string.aimodel_editor_purpose_stt)
    "embedding" -> stringResource(R.string.aimodel_editor_purpose_embedding)
    "image_generation" -> stringResource(R.string.aimodel_editor_purpose_image_generation)
    else -> purpose
}

@Composable
private fun ModelCard(
    model: LocalAiModelEntity,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onProbe: () -> Unit
) {
    val textResources = LocalResources.current
    var menuExpanded by remember { mutableStateOf(false) }
    val purposeLabel = localModelPurposeLabel(model.purpose)
    val isEnabled = model.enabled

    // 停用模型整体降透明度，与启用模型形成一眼可辨的对比（选中态侧边标记保留）。
    val contentAlpha = if (isEnabled) 1f else 0.45f

    ModelCardFrame(
        isActive = isActive,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 身份区与服务器模型卡片保持一致，切换模式时不改变阅读路径。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.alpha(contentAlpha)) {
                ProviderLogo(
                    provider = model.provider,
                    baseUrl = model.baseUrl,
                    model = model.model,
                    size = 46.dp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(contentAlpha)
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = model.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            if (!isEnabled) {
                // 停用态文字徽标：仅用弱色小字，避免与"当前模型"对勾争夺注意力。
                Text(
                    text = stringResource(R.string.localai_disabled_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = if (model.purpose == "chat") textResources.getString(R.string.single_role_primary_model_in_default_queue) else stringResource(R.string.localai_current_model),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Box {
                ModelCardMenuButton(
                    contentDescription = stringResource(R.string.localai_more),
                    onClick = { menuExpanded = true }
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (model.purpose.ifBlank { "chat" } in setOf("chat", "vision")) DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_probe_title)) },
                        leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        onClick = { menuExpanded = false; onProbe() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_copy)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        }
                    )
                    // 启用/停用：与卡片右上角徽标、整体透明度联动，操作后立即可见。
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (isEnabled) R.string.localai_disable else R.string.localai_enable
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isEnabled) Icons.Filled.Block else Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleEnabled()
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

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.alpha(contentAlpha)
        ) {
            ModelInfoChip(text = model.protocol)
            if (model.proxyUrl.isNotBlank()) {
                ModelInfoChip(text = stringResource(R.string.localai_proxy_badge))
            }
            ModelInfoChip(
                text = purposeLabel,
                accent = true,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        if (model.baseUrl.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            Box(modifier = Modifier.alpha(contentAlpha)) {
                ModelEndpointRow(url = model.baseUrl)
            }
        }

        ModelCardDivider(modifier = Modifier.padding(vertical = 10.dp))

        // 底部操作区：紧凑等分按钮栏，避免左侧大面积留白，视觉上更协调。
        if (isActive) {
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.aiconfig_test),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onTest,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.aiconfig_test),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                FilledTonalButton(
                    onClick = onSetActive,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.localai_set_current),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
