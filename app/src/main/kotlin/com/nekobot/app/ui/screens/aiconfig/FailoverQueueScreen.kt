package com.nekobot.app.ui.screens.aiconfig

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private data class FailoverPurpose(val id: String, val label: String)

private val failoverPurposes = listOf(
    FailoverPurpose("chat", "对话"),
    FailoverPurpose("vision", "图片理解"),
    FailoverPurpose("video", "视频理解"),
    FailoverPurpose("tts", "语音合成"),
    FailoverPurpose("stt", "语音识别"),
    FailoverPurpose("embedding", "向量嵌入"),
    FailoverPurpose("image_generation", "图片生成")
)

internal data class FailoverQueueItem(
    val id: String,
    val name: String,
    val model: String,
    val provider: String,
    val priority: Int,
    val available: Boolean,
    val dailyFailures: Int,
    val consecutiveFailures: Int,
    val lastFailureCode: Int,
    val cooldownRemaining: Double,
    val dailyTokenLimit: Long,
    val weeklyTokenLimit: Long,
    val timeoutSeconds: Int
)

internal class FailoverQueueViewModel : BaseViewModel() {
    private val _purpose = MutableStateFlow(failoverPurposes.first().id)
    val purpose: StateFlow<String> = _purpose.asStateFlow()

    private val _queue = MutableStateFlow<List<FailoverQueueItem>>(emptyList())
    val queue: StateFlow<List<FailoverQueueItem>> = _queue.asStateFlow()

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
            block = { repo.getFailoverQueue(_purpose.value) },
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
            block = { repo.reorderFailover(_purpose.value, reordered.map { it.id }) },
            onSuccess = {
                _queue.value = reordered
                showToast("队列顺序已保存")
            }
        )
    }

    fun reset(modelId: String? = null) {
        launchResult(
            block = { repo.resetFailover(modelId) },
            onSuccess = {
                showToast(if (modelId == null) "已重置全部健康状态" else "已重置模型健康状态")
                load()
            }
        )
    }

    private fun JsonObject.toQueueItem(): FailoverQueueItem? {
        val modelId = string("model_id") ?: return null
        val health = get("health")?.takeIf { it.isJsonObject }?.asJsonObject
        return FailoverQueueItem(
            id = modelId,
            name = string("name").orEmpty().ifBlank { string("model").orEmpty().ifBlank { modelId } },
            model = string("model").orEmpty(),
            provider = string("provider").orEmpty(),
            priority = int("priority"),
            available = health?.bool("available", true) ?: true,
            dailyFailures = health?.int("daily_failures") ?: 0,
            consecutiveFailures = health?.int("consecutive_failures") ?: 0,
            lastFailureCode = health?.int("last_failure_code") ?: 0,
            cooldownRemaining = health?.double("cooldown_remaining") ?: 0.0,
            dailyTokenLimit = long("token_limit_daily"),
            weeklyTokenLimit = long("token_limit_weekly"),
            timeoutSeconds = int("failover_timeout")
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
    val context = LocalContext.current

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("故障转移队列") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::load) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { vm.reset() }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = "重置全部健康状态")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    failoverPurposes.forEach { item ->
                        FilterChip(
                            selected = purpose == item.id,
                            onClick = { vm.selectPurpose(item.id) },
                            label = { Text(item.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                error?.let {
                    ErrorBanner(message = it, onRetry = vm::load)
                    Spacer(Modifier.height(8.dp))
                }
                if (!loading && queue.isEmpty()) {
                    EmptyState(title = "该用途暂无可用模型", hint = "请先在 AI 模型中启用并设置对应用途")
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
                                onReset = { vm.reset(item.id) }
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
            LoadingOverlay(visible = loading, message = "正在加载队列...")
        }
    }
}

@Composable
private fun FailoverModelCard(
    item: FailoverQueueItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onReset: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text("P${item.priority}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(item.provider, item.model).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                if (item.available) "可用" else "冷却中",
                color = if (item.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            buildString {
                append("今日失败 ${item.dailyFailures} 次")
                if (item.consecutiveFailures > 0) append(" · 连续 ${item.consecutiveFailures} 次")
                if (item.lastFailureCode > 0) append(" · HTTP ${item.lastFailureCode}")
                if (item.cooldownRemaining > 0) append(" · 剩余 ${item.cooldownRemaining.toInt()} 秒")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val limits = buildList {
            if (item.dailyTokenLimit > 0) add("日限额 ${item.dailyTokenLimit}")
            if (item.weeklyTokenLimit > 0) add("周限额 ${item.weeklyTokenLimit}")
            if (item.timeoutSeconds > 0) add("超时 ${item.timeoutSeconds}s")
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
                Icon(Icons.Filled.ArrowUpward, contentDescription = "上移")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "下移")
            }
            OutlinedButton(onClick = onReset) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("重置状态")
            }
        }
    }
}
