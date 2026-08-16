package com.nekobot.app.ui.screens.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A/B 测试配置 ViewModel。
 *
 * 通过 ServiceContainer.prefs 读写 A/B 测试开关、分流比例、
 * 对照组/实验组模型 ID 与测试名称，并从 LocalRepository 加载
 * chat 用途的可用模型列表供选择。
 */
class AbTestSettingsViewModel : ViewModel() {

    private val prefs get() = ServiceContainer.prefs

    private val _models = MutableStateFlow<List<LocalAiModelEntity>>(emptyList())
    val models: StateFlow<List<LocalAiModelEntity>> = _models.asStateFlow()

    var enabled by mutableStateOf(prefs.abTestEnabled)
        private set
    var splitRatio by mutableStateOf(prefs.abTestSplitRatio)
        private set
    var controlModelId by mutableStateOf(prefs.abTestControlModelId)
        private set
    var experimentModelId by mutableStateOf(prefs.abTestExperimentModelId)
        private set
    var testName by mutableStateOf(prefs.abTestName)
        private set

    init {
        loadModels()
    }

    /** 加载 chat 用途模型列表。 */
    private fun loadModels() {
        viewModelScope.launch {
            try {
                _models.value = withContext(Dispatchers.IO) {
                    ServiceContainer.localRepository.listModelsByPurpose("chat")
                }
            } catch (e: Exception) {
                // 静默失败，模型列表为空时 UI 会提示无可用模型
            }
        }
    }

    fun updateEnabled(v: Boolean) {
        enabled = v
        prefs.abTestEnabled = v
    }

    fun updateSplitRatio(v: Float) {
        splitRatio = v
        prefs.abTestSplitRatio = v
    }

    fun setControlModel(id: String?) {
        controlModelId = id
        prefs.abTestControlModelId = id
    }

    fun setExperimentModel(id: String?) {
        experimentModelId = id
        prefs.abTestExperimentModelId = id
    }

    fun updateTestName(v: String) {
        testName = v
        prefs.abTestName = v
    }
}

/**
 * A/B 测试配置界面。
 *
 * 允许用户启用/禁用模型路由 A/B 测试、配置分流比例、
 * 选择对照组与实验组模型、设置测试名称。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbTestSettingsScreen(onBack: () -> Unit) {
    val vm: AbTestSettingsViewModel = viewModel()
    val models by vm.models.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tokens_ab_test_settings), color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 说明卡片
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = stringResource(R.string.abtest_section_title),
                    subtitle = stringResource(R.string.abtest_section_subtitle)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.abtest_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 启用开关
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.abtest_enable),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.abtest_enable_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = vm.enabled,
                        onCheckedChange = { vm.updateEnabled(it) }
                    )
                }
            }

            // 分流比例
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = stringResource(R.string.abtest_split_ratio))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.abtest_experiment_ratio, (vm.splitRatio * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.abtest_ratio_summary,
                        ((1 - vm.splitRatio) * 100).toInt(),
                        (vm.splitRatio * 100).toInt()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = vm.splitRatio,
                    onValueChange = { vm.updateSplitRatio(it) },
                    valueRange = 0f..1f,
                    steps = 19, // 每 5% 一档
                    enabled = vm.enabled
                )
            }

            // 对照组模型选择
            ModelSelectorCard(
                title = stringResource(R.string.abtest_control_model),
                hint = stringResource(R.string.abtest_control_model_hint),
                models = models,
                selectedId = vm.controlModelId,
                enabled = vm.enabled,
                onSelect = { vm.setControlModel(it) }
            )

            // 实验组模型选择
            ModelSelectorCard(
                title = stringResource(R.string.abtest_experiment_model),
                hint = stringResource(R.string.abtest_experiment_model_hint),
                models = models,
                selectedId = vm.experimentModelId,
                enabled = vm.enabled,
                onSelect = { vm.setExperimentModel(it) }
            )

            // 测试名称
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = stringResource(R.string.abtest_name))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = vm.testName,
                    onValueChange = { vm.updateTestName(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.abtest_name_placeholder)) },
                    enabled = vm.enabled
                )
            }

            // 模型列表为空时的提示
            if (models.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.abtest_no_chat_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

/**
 * 模型选择卡片：使用 ExposedDropdownMenu 展示可用模型列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectorCard(
    title: String,
    hint: String,
    models: List<LocalAiModelEntity>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedModel = models.firstOrNull { it.id == selectedId }
    val displayText = selectedModel?.name ?: stringResource(R.string.abtest_not_selected)

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title)
        Spacer(Modifier.height(4.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it }
        ) {
            OutlinedTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                label = { Text(stringResource(R.string.abtest_select_model)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                enabled = enabled
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                // 未选择项
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.abtest_not_selected)) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.abtest_no_models)) },
                        onClick = { expanded = false }
                    )
                }
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    model.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    model.model,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onSelect(model.id)
                            expanded = false
                        }
                    )
                }
            }
        }
        if (selectedModel != null) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.abtest_model_name, selectedModel.model),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 简化的下拉菜单项（避免依赖具体 Material3 版本的签名差异）。
 */
@Composable
private fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit
) {
    androidx.compose.material3.DropdownMenuItem(
        text = text,
        onClick = onClick
    )
}
