package com.nekobot.app.ui.screens.aiconfig

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.AiConfig
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.OnPrimary
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * AI 配置页 ViewModel
 */
class AiConfigViewModel : BaseViewModel() {

    private val _config = MutableStateFlow(AiConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    private val _rawJson = MutableStateFlow<JsonElement?>(null)
    val rawJson: StateFlow<JsonElement?> = _rawJson.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _navigateBack = MutableStateFlow(false)
    val navigateBack: StateFlow<Boolean> = _navigateBack.asStateFlow()

    init {
        load()
    }

    fun load() {
        launchResult(
            block = { repo.getAiConfig() },
            onSuccess = { json ->
                _rawJson.value = json
                _config.value = try {
                    ServiceContainer.gson.fromJson(json, AiConfig::class.java) ?: AiConfig()
                } catch (e: Exception) {
                    AiConfig()
                }
            }
        )
    }

    fun updateConfig(transform: (AiConfig) -> AiConfig) {
        _config.value = transform(_config.value)
    }

    fun save() {
        val json = ServiceContainer.gson.toJsonTree(_config.value)
        launchResult(
            block = { repo.updateAiConfig(json) },
            onSuccess = {
                showToast("保存成功")
                _navigateBack.value = true
            }
        )
    }

    fun test() {
        val json = ServiceContainer.gson.toJsonTree(_config.value)
        launchResult(
            block = { repo.testAiConfig(json) },
            onSuccess = { res ->
                _testResult.value = buildString {
                    append("状态: ${if (res.success == true) "成功" else "失败"}\n")
                    append("消息: ${res.message ?: "无"}")
                }
            }
        )
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun resetNavigateBack() {
        _navigateBack.value = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigScreen(onBack: () -> Unit) {
    val vm: AiConfigViewModel = viewModel()
    val config by vm.config.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val testResult by vm.testResult.collectAsState()
    val navigateBack by vm.navigateBack.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(navigateBack) {
        if (navigateBack) {
            vm.resetNavigateBack()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.test() }) {
                        Text("测试", color = MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = { vm.save() }) {
                        Text("保存", color = MaterialTheme.colorScheme.onSurface)
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                error?.let {
                    ErrorBanner(message = it, onRetry = {
                        vm.clearError()
                        vm.load()
                    })
                }

                // 模型参数
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = "模型参数")
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = config.model ?: "",
                        onValueChange = { v ->
                            vm.updateConfig { it.copy(model = v.ifBlank { null }) }
                        },
                        label = { Text("模型名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 温度
                    val temperature = config.temperature ?: 0.7
                    SliderRow(
                        label = "温度 (temperature)",
                        valueText = String.format(Locale.US, "%.2f", temperature),
                        value = temperature.toFloat(),
                        onValueChange = { v ->
                            vm.updateConfig { it.copy(temperature = v.toDouble()) }
                        },
                        valueRange = 0f..2f
                    )

                    // 最大 token
                    OutlinedTextField(
                        value = config.maxTokens?.toString() ?: "",
                        onValueChange = { s ->
                            val n = s.toIntOrNull()
                            vm.updateConfig { it.copy(maxTokens = n) }
                        },
                        label = { Text("最大 Token (max_tokens)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // top_p
                    val topP = config.topP ?: 1.0
                    SliderRow(
                        label = "Top P",
                        valueText = String.format(Locale.US, "%.2f", topP),
                        value = topP.toFloat(),
                        onValueChange = { v ->
                            vm.updateConfig { it.copy(topP = v.toDouble()) }
                        },
                        valueRange = 0f..1f
                    )

                    // 频率惩罚
                    val freqPenalty = config.frequencyPenalty ?: 0.0
                    SliderRow(
                        label = "频率惩罚 (frequency_penalty)",
                        valueText = String.format(Locale.US, "%.2f", freqPenalty),
                        value = freqPenalty.toFloat(),
                        onValueChange = { v ->
                            vm.updateConfig { it.copy(frequencyPenalty = v.toDouble()) }
                        },
                        valueRange = -2f..2f
                    )

                    // presence_penalty
                    val presPenalty = config.presencePenalty ?: 0.0
                    SliderRow(
                        label = "Presence 惩罚 (presence_penalty)",
                        valueText = String.format(Locale.US, "%.2f", presPenalty),
                        value = presPenalty.toFloat(),
                        onValueChange = { v ->
                            vm.updateConfig { it.copy(presencePenalty = v.toDouble()) }
                        },
                        valueRange = -2f..2f
                    )
                }

                // 系统提示词
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = "系统提示词")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = config.systemPrompt ?: "",
                        onValueChange = { v ->
                            vm.updateConfig { it.copy(systemPrompt = v.ifBlank { null }) }
                        },
                        label = { Text("system_prompt") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 保存按钮
                Button(
                    onClick = { vm.save() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("保存配置", color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            LoadingOverlay(visible = loading)
        }
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
 * 滑块行：标签 + 数值 + Slider
 */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
