package com.nekobot.app.ui.screens.aiconfig

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.FailoverModelDetail
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private data class FailoverPurpose(val id: String, val labelResId: Int)

private val failoverPurposes = listOf(
    FailoverPurpose("chat", R.string.failover_purpose_chat),
    FailoverPurpose("vision", R.string.failover_purpose_vision),
    FailoverPurpose("video", R.string.failover_purpose_video),
    FailoverPurpose("tts", R.string.failover_purpose_tts),
    FailoverPurpose("stt", R.string.failover_purpose_stt),
    FailoverPurpose("embedding", R.string.failover_purpose_embedding),
    FailoverPurpose("image_generation", R.string.failover_purpose_image_generation)
)

internal data class FailoverQueueItem(
    val id: String,
    val name: String,
    val model: String,
    val provider: String,
    val priority: Int,
    val active: Boolean = false,
    val available: Boolean,
    val dailyFailures: Int,
    val consecutiveFailures: Int,
    val lastFailureCode: Int,
    val cooldownRemaining: Double,
    val dailyTokenLimit: Long,
    val weeklyTokenLimit: Long,
    val timeoutSeconds: Int,
    val dailyTokens: Long = 0,
    val weeklyTokens: Long = 0
) {
    /** 是否为 P0（首选模型） */
    val isPrimary: Boolean get() = priority == 0

    /** 日用量百分比 0..100，无限额时返回 0 */
    val dailyUsagePercent: Int
        get() = if (dailyTokenLimit <= 0) 0 else ((dailyTokens.toDouble() / dailyTokenLimit) * 100).toInt().coerceIn(0, 100)

    /** 周用量百分比 0..100，无限额时返回 0 */
    val weeklyUsagePercent: Int
        get() = if (weeklyTokenLimit <= 0) 0 else ((weeklyTokens.toDouble() / weeklyTokenLimit) * 100).toInt().coerceIn(0, 100)
}

internal class FailoverQueueViewModel : BaseViewModel() {
    private val _purpose = MutableStateFlow(failoverPurposes.first().id)
    val purpose: StateFlow<String> = _purpose.asStateFlow()

    private val _queue = MutableStateFlow<List<FailoverQueueItem>>(emptyList())
    val queue: StateFlow<List<FailoverQueueItem>> = _queue.asStateFlow()

    /** 当前打开的详情对话框数据；null 表示对话框关闭 */
    private val _selectedDetail = MutableStateFlow<FailoverModelDetail?>(null)
    val selectedDetail: StateFlow<FailoverModelDetail?> = _selectedDetail.asStateFlow()

    /** 详情对话框加载状态（独立于列表 loading，避免覆盖） */
    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    /** 详情保存中状态 */
    private val _detailSaving = MutableStateFlow(false)
    val detailSaving: StateFlow<Boolean> = _detailSaving.asStateFlow()

    private val _smartRoutingEnabled =
        MutableStateFlow(ServiceContainer.prefs.smartRoutingEnabled)
    val smartRoutingEnabled: StateFlow<Boolean> = _smartRoutingEnabled.asStateFlow()

    private val _smartRoutingBudget =
        MutableStateFlow(ServiceContainer.prefs.smartRoutingDailyBudgetUsd)
    val smartRoutingBudget: StateFlow<Double> = _smartRoutingBudget.asStateFlow()

    init {
        load()
    }

    fun selectPurpose(value: String) {
        if (_purpose.value == value) return
        _purpose.value = value
        load()
    }

    fun load() {
        launchResult(
            block = { unified.getFailoverQueue(_purpose.value) },
            onSuccess = { json ->
                val array = json.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("queue")
                _queue.value = array?.mapNotNull { element ->
                    element.takeIf { it.isJsonObject }?.asJsonObject?.toQueueItem()
                }.orEmpty().sortedBy { it.priority }
            }
        )
    }

    fun move(index: Int, offset: Int) {
        val target = index + offset
        val current = _queue.value
        if (index !in current.indices || target !in current.indices) return
        val reordered = current.toMutableList().apply {
            val item = removeAt(index)
            add(target, item)
        }.mapIndexed { priority, item -> item.copy(priority = priority) }
        launchResult(
            block = { unified.reorderFailover(_purpose.value, reordered.map { it.id }) },
            onSuccess = {
                _queue.value = reordered
                showToast(string(R.string.failover_order_saved))
            },
            onError = { msg ->
                // 重排失败：恢复服务端实际顺序，并显示错误
                showError(msg)
                load()
            }
        )
    }

    fun reset(modelId: String? = null) {
        launchResult(
            block = { unified.resetFailover(modelId) },
            onSuccess = {
                showToast(if (modelId == null) string(R.string.failover_reset_all) else string(R.string.failover_reset_model))
                load()
                // 如果详情对话框打开且被重置的模型是当前详情，也刷新详情
                _selectedDetail.value?.let { d ->
                    if (modelId == null || modelId == d.modelId) openDetail(d.modelId)
                }
            }
        )
    }

    /** 打开详情对话框：通过类型化 API 加载完整详情（含用量/价格） */
    fun openDetail(modelId: String) {
        // 直接使用 viewModelScope 而非 launchResult，避免触发全屏 LoadingOverlay
        _detailLoading.value = true
        viewModelScope.launch {
            try {
                when (val res = unified.getFailoverDetail(modelId)) {
                    is Resource.Success -> _selectedDetail.value = res.data
                    is Resource.Error -> {
                        showError(res.message)
                        _selectedDetail.value = null
                    }
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.failover_load_detail_failed))
                _selectedDetail.value = null
            } finally {
                _detailLoading.value = false
            }
        }
    }

    /** 保存详情对话框中的策略编辑：token 限额 + 超时秒数 */
    fun saveDetail(tokenLimitDaily: Long, tokenLimitWeekly: Long, failoverTimeout: Int) {
        val detail = _selectedDetail.value ?: return
        // 非负校验：UI 层兜底
        if (tokenLimitDaily < 0 || tokenLimitWeekly < 0 || failoverTimeout < 0) {
            showError(string(R.string.failover_invalid_limit))
            return
        }
        // 直接使用 viewModelScope，避免全屏 LoadingOverlay 覆盖对话框
        _detailSaving.value = true
        viewModelScope.launch {
            try {
                when (val res = unified.updateFailoverPolicy(detail.modelId, tokenLimitDaily, tokenLimitWeekly, failoverTimeout)) {
                    is Resource.Success -> {
                        showToast(string(R.string.failover_policy_saved))
                        _selectedDetail.value = null
                        load() // 刷新列表以反映新限额
                    }
                    is Resource.Error -> showError(res.message)
                    is Resource.Loading -> {}
                }
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.failover_save_policy_failed))
            } finally {
                _detailSaving.value = false
            }
        }
    }

    /** 关闭详情对话框 */
    fun dismissDetail() {
        _selectedDetail.value = null
    }

    fun saveSmartRouting(enabled: Boolean, dailyBudgetUsd: Double) {
        ServiceContainer.prefs.smartRoutingEnabled = enabled
        ServiceContainer.prefs.smartRoutingDailyBudgetUsd = dailyBudgetUsd
        _smartRoutingEnabled.value = enabled
        _smartRoutingBudget.value = dailyBudgetUsd
        showToast(string(R.string.smart_routing_saved))
    }

    private fun JsonObject.toQueueItem(): FailoverQueueItem? {
        val modelId = string("model_id") ?: return null
        val health = get("health")?.takeIf { it.isJsonObject }?.asJsonObject
        val usage = get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
        return FailoverQueueItem(
            id = modelId,
            name = string("name").orEmpty().ifBlank { string("model").orEmpty().ifBlank { modelId } },
            model = string("model").orEmpty(),
            provider = string("provider").orEmpty(),
            priority = int("priority"),
            active = bool("active", false),
            available = health?.bool("available", true) ?: true,
            dailyFailures = health?.int("daily_failures") ?: 0,
            consecutiveFailures = health?.int("consecutive_failures") ?: 0,
            lastFailureCode = health?.int("last_failure_code") ?: 0,
            cooldownRemaining = health?.double("cooldown_remaining") ?: 0.0,
            dailyTokenLimit = long("token_limit_daily"),
            weeklyTokenLimit = long("token_limit_weekly"),
            timeoutSeconds = int("failover_timeout"),
            dailyTokens = usage?.long("daily_tokens") ?: 0L,
            weeklyTokens = usage?.long("weekly_tokens") ?: 0L
        )
    }
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun JsonObject.int(key: String): Int =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0 }.getOrDefault(0)

private fun JsonObject.long(key: String): Long =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L }.getOrDefault(0L)

private fun JsonObject.double(key: String): Double =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0 }.getOrDefault(0.0)

private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: fallback }.getOrDefault(fallback)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailoverQueueScreen(onBack: () -> Unit) {
    val vm: FailoverQueueViewModel = viewModel()
    val purpose by vm.purpose.collectAsState()
    val queue by vm.queue.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val selectedDetail by vm.selectedDetail.collectAsState()
    val detailLoading by vm.detailLoading.collectAsState()
    val detailSaving by vm.detailSaving.collectAsState()
    val smartRoutingEnabled by vm.smartRoutingEnabled.collectAsState()
    val smartRoutingBudget by vm.smartRoutingBudget.collectAsState()
    val context = LocalContext.current
    var smartRoutingEnabledInput by remember(smartRoutingEnabled) {
        mutableStateOf(smartRoutingEnabled)
    }
    var smartRoutingBudgetInput by remember(smartRoutingBudget) {
        mutableStateOf(
            smartRoutingBudget.takeIf { it > 0.0 }?.toString().orEmpty()
        )
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.failover_queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::load) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.failover_refresh))
                    }
                    IconButton(onClick = { vm.reset() }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = stringResource(R.string.failover_reset_all_health))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (ServiceContainer.prefs.isLocalMode && purpose == "chat") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.smart_routing_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.smart_routing_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = smartRoutingEnabledInput,
                                onCheckedChange = { smartRoutingEnabledInput = it }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = smartRoutingBudgetInput,
                                onValueChange = { value ->
                                    smartRoutingBudgetInput = value.filter {
                                        it.isDigit() || it == '.'
                                    }
                                },
                                label = {
                                    Text(stringResource(R.string.smart_routing_daily_budget))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    vm.saveSmartRouting(
                                        smartRoutingEnabledInput,
                                        smartRoutingBudgetInput.toDoubleOrNull() ?: 0.0
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.common_save))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    failoverPurposes.forEach { item ->
                        FilterChip(
                            selected = purpose == item.id,
                            onClick = { vm.selectPurpose(item.id) },
                            label = { Text(stringResource(item.labelResId)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                error?.let {
                    ErrorBanner(message = it, onRetry = vm::load)
                    Spacer(Modifier.height(8.dp))
                }
                if (!loading && queue.isEmpty()) {
                    EmptyState(title = stringResource(R.string.failover_empty_title), hint = stringResource(R.string.failover_empty_hint))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                            FailoverModelCard(
                                item = item,
                                canMoveUp = index > 0,
                                canMoveDown = index < queue.lastIndex,
                                onMoveUp = { vm.move(index, -1) },
                                onMoveDown = { vm.move(index, 1) },
                                onReset = { vm.reset(item.id) },
                                onClick = { vm.openDetail(item.id) }
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
            LoadingOverlay(visible = loading, message = stringResource(R.string.failover_loading_queue))
        }
    }

    // 详情对话框
    selectedDetail?.let { detail ->
        FailoverDetailDialog(
            detail = detail,
            loading = detailLoading,
            saving = detailSaving,
            onSave = { daily, weekly, timeout -> vm.saveDetail(daily, weekly, timeout) },
            onReset = { vm.reset(detail.modelId) },
            onDismiss = vm::dismissDetail
        )
    }
}

@Composable
private fun FailoverModelCard(
    item: FailoverQueueItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onReset: () -> Unit,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 16
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (item.isPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "P${item.priority}",
                    color = if (item.isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.isPrimary) {
                        Spacer(Modifier.size(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                stringResource(R.string.failover_primary),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (item.active) {
                        Spacer(Modifier.size(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                stringResource(R.string.failover_current),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
                Text(
                    listOf(item.provider, item.model).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                if (item.available) stringResource(R.string.failover_available) else stringResource(R.string.failover_cooling),
                color = if (item.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        val todayFailuresStr = stringResource(R.string.failover_today_failures, item.dailyFailures)
        val consecutiveFailuresStr = stringResource(R.string.failover_consecutive_failures, item.consecutiveFailures)
        val remainingSecondsStr = stringResource(R.string.failover_remaining_seconds, item.cooldownRemaining.toInt())
        Text(
            buildString {
                append(todayFailuresStr)
                if (item.consecutiveFailures > 0) append(" · ").append(consecutiveFailuresStr)
                if (item.lastFailureCode > 0) append(" · HTTP ${item.lastFailureCode}")
                if (item.cooldownRemaining > 0) append(" · ").append(remainingSecondsStr)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 用量条：仅在有限额时显示
        if (item.dailyTokenLimit > 0 || item.weeklyTokenLimit > 0) {
            Spacer(Modifier.height(6.dp))
            if (item.dailyTokenLimit > 0) {
                Text(
                    stringResource(R.string.failover_daily_usage, item.dailyTokens, item.dailyTokenLimit, item.dailyUsagePercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { item.dailyUsagePercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = usageColor(item.dailyUsagePercent),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            if (item.weeklyTokenLimit > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.failover_weekly_usage, item.weeklyTokens, item.weeklyTokenLimit, item.weeklyUsagePercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { item.weeklyUsagePercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = usageColor(item.weeklyUsagePercent),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        val dailyLimitStr = stringResource(R.string.failover_daily_limit, item.dailyTokenLimit)
        val weeklyLimitStr = stringResource(R.string.failover_weekly_limit, item.weeklyTokenLimit)
        val timeoutStr = stringResource(R.string.failover_timeout, item.timeoutSeconds)
        val limits = buildList {
            if (item.dailyTokenLimit > 0) add(dailyLimitStr)
            if (item.weeklyTokenLimit > 0) add(weeklyLimitStr)
            if (item.timeoutSeconds > 0) add(timeoutStr)
        }
        if (limits.isNotEmpty()) {
            Text(
                limits.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.failover_move_up))
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.failover_move_down))
            }
            OutlinedButton(onClick = onReset) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.failover_reset_status))
            }
        }
    }
}

/** 用量百分比颜色：<80% primary，80..99% tertiary，>=100% error */
@Composable
private fun usageColor(percent: Int): androidx.compose.ui.graphics.Color = when {
    percent >= 100 -> MaterialTheme.colorScheme.error
    percent >= 80 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun FailoverDetailDialog(
    detail: FailoverModelDetail,
    loading: Boolean,
    saving: Boolean,
    onSave: (dailyLimit: Long, weeklyLimit: Long, timeout: Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var dailyLimitText by remember(detail.modelId) {
        mutableStateOf(detail.tokenLimitDaily.takeIf { it > 0 }?.toString() ?: "")
    }
    var weeklyLimitText by remember(detail.modelId) {
        mutableStateOf(detail.tokenLimitWeekly.takeIf { it > 0 }?.toString() ?: "")
    }
    var timeoutText by remember(detail.modelId) {
        mutableStateOf(detail.failoverTimeout.takeIf { it > 0 }?.toString() ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(detail.name.ifBlank { detail.model }, fontWeight = FontWeight.SemiBold)
        },
        text = {
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        listOfNotNull(detail.provider, detail.model).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // 健康状态摘要
                    val statusStr = stringResource(R.string.failover_status_label, if (detail.health.available) stringResource(R.string.failover_available) else stringResource(R.string.failover_cooling))
                    val remainingShortStr = stringResource(R.string.failover_remaining_seconds_short, detail.health.cooldownRemaining.toInt())
                    val consecutiveFailDetailStr = stringResource(R.string.failover_consecutive_failures_detail, detail.health.consecutiveFailures)
                    Text(
                        buildString {
                            append(statusStr)
                            if (detail.health.cooldownRemaining > 0) append(" · ").append(remainingShortStr)
                            if (detail.health.consecutiveFailures > 0) append(" · ").append(consecutiveFailDetailStr)
                            if (detail.health.lastFailureCode > 0) append(" · HTTP ${detail.health.lastFailureCode}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 用量摘要
                    if (detail.usage.dailyLimit > 0 || detail.usage.weeklyLimit > 0) {
                        Spacer(Modifier.height(6.dp))
                        val dailyUsageStr = stringResource(R.string.failover_daily_usage, detail.usage.dailyTokens, detail.usage.dailyLimit, detail.usage.dailyPercent)
                        val weeklyUsageStr = stringResource(R.string.failover_weekly_usage, detail.usage.weeklyTokens, detail.usage.weeklyLimit, detail.usage.weeklyPercent)
                        Text(
                            buildString {
                                if (detail.usage.dailyLimit > 0) {
                                    append(dailyUsageStr)
                                }
                                if (detail.usage.weeklyLimit > 0) {
                                    if (isNotEmpty()) append("\n")
                                    append(weeklyUsageStr)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = dailyLimitText,
                        onValueChange = { dailyLimitText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.failover_daily_token_limit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = weeklyLimitText,
                        onValueChange = { weeklyLimitText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.failover_weekly_token_limit)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { timeoutText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.failover_timeout_seconds)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val daily = dailyLimitText.toLongOrNull() ?: 0L
                    val weekly = weeklyLimitText.toLongOrNull() ?: 0L
                    val timeout = timeoutText.toIntOrNull() ?: 0
                    onSave(daily, weekly, timeout)
                },
                enabled = !loading && !saving
            ) {
                Text(if (saving) stringResource(R.string.failover_saving) else stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = onReset, enabled = !loading && !saving) {
                    Text(stringResource(R.string.failover_reset_health))
                }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}
