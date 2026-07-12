package com.nekobot.app.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 功能开关条目 */
data class SwitchItem(
    val name: String,
    val description: String,
    val state: Boolean
)

/**
 * 功能开关界面 ViewModel：通过 /api/settings 的 features 字段管理功能开关。
 *
 * 原仓库没有独立的 /api/switches 端点，功能开关存储在 settings.features 对象中，
 * 每个键代表一个功能开关（布尔值），通过 PUT /api/settings 更新。
 */
class FeatureSwitchesViewModel : BaseViewModel() {

    private val _switches = MutableStateFlow<List<SwitchItem>>(emptyList())
    val switches: StateFlow<List<SwitchItem>> = _switches.asStateFlow()

    /** 完整的 settings JSON，用于切换时回写 */
    private var fullSettings: JsonObject? = null

    init {
        if (ServiceContainer.prefs.appMode != AppMode.LOCAL) {
            load()
        }
    }

    /** 加载开关列表：从 /api/settings 提取 features 对象 */
    fun load() {
        launchResult(
            block = { repo.getSettings() },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                fullSettings = obj
                _switches.value = parseFeatures(obj)
            }
        )
    }

    /** 切换开关状态：更新 settings.features 中对应字段并回写 */
    fun toggle(name: String, newState: Boolean) {
        // 乐观更新
        _switches.value = _switches.value.map {
            if (it.name == name) it.copy(state = newState) else it
        }
        val settings = fullSettings ?: return
        // 更新 features 对象
        val features = settings.getAsJsonObject("features") ?: JsonObject().also {
            settings.add("features", it)
        }
        features.add(name, JsonPrimitive(newState))
        launchResult(
            block = { repo.updateSettings(settings) },
            onSuccess = {
                showToast("已${if (newState) "开启" else "关闭"} $name")
                // 重新加载确保与服务器一致
                load()
            },
            onError = { _ ->
                // 失败时回滚
                _switches.value = _switches.value.map {
                    if (it.name == name) it.copy(state = !newState) else it
                }
            }
        )
    }

    /** 从 settings JSON 中解析 features 对象为开关列表 */
    private fun parseFeatures(obj: JsonObject?): List<SwitchItem> {
        if (obj == null) return emptyList()
        val features = obj.getAsJsonObject("features") ?: return emptyList()
        return features.entrySet().mapNotNull { (key, value) ->
            if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                SwitchItem(
                    name = key,
                    description = featureDescription(key),
                    state = value.asBoolean
                )
            } else null
        }.sortedBy { it.name }
    }

    /** 功能开关的中文描述映射 */
    private fun featureDescription(name: String): String {
        return when (name) {
            "skills_prompt_injection" -> "技能提示词注入"
            "live2d" -> "Live2D 表情"
            "sticker" -> "表情贴图"
            "image_generation" -> "图片生成"
            "tts" -> "TTS 语音"
            "auto_reply" -> "自动回复"
            "active_chat" -> "主动聊天"
            else -> name
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSwitchesScreen(onBack: () -> Unit) {
    val vm: FeatureSwitchesViewModel = viewModel()
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    val switches by vm.switches.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("功能开关", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (appMode != AppMode.LOCAL) {
                        IconButton(onClick = { vm.load() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
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
            if (appMode == AppMode.LOCAL) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "此功能仅服务器模式可用",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    error?.let {
                        ErrorBanner(message = it, onRetry = { vm.clearError() })
                    }

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(
                            title = "功能开关",
                            subtitle = "控制各项功能的启用状态"
                        )
                    }

                    if (switches.isEmpty() && !loading) {
                        EmptyState(
                            title = "暂无开关",
                            hint = "点击右上角刷新重试",
                            icon = {
                                Icon(
                                    Icons.Filled.ToggleOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(switches, key = { it.name }) { sw ->
                                SwitchItemCard(
                                    item = sw,
                                    onToggle = { vm.toggle(sw.name, !sw.state) }
                                )
                            }
                        }
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }
}

/** 单个开关卡片：名称 + 描述 + Switch */
@Composable
private fun SwitchItemCard(
    item: SwitchItem,
    onToggle: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                if (item.description.isNotBlank() && item.description != item.name) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = item.state,
                onCheckedChange = { onToggle() }
            )
        }
    }
}
