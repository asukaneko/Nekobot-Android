package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.AgentToolLimits
import com.nekobot.app.ui.components.AgentToolSetPickerDialog
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassCard

/** Agent 设置下所有数值的可选范围与默认值，保持与 PrefsManager 一致。 */
private const val MAX_TOOL_CALLS_MIN = 1
private const val MAX_TOOL_CALLS_MAX = 1000
private const val MAX_TOOL_CALLS_DEFAULT = 150

/** Subagent 相关数值范围与默认值。 */
private const val SUBAGENT_MAX_DEPTH_MIN = 0
private const val SUBAGENT_MAX_DEPTH_MAX = 10
private const val SUBAGENT_MAX_DEPTH_DEFAULT = 3
private const val SUBAGENT_MAX_TOOL_CALLS_MIN = 1
private const val SUBAGENT_MAX_TOOL_CALLS_MAX = 500
private const val SUBAGENT_MAX_TOOL_CALLS_DEFAULT = 60

/**
 * Agent 设置界面：布局沿用「拓展功能」风格（分组卡片 + 彩色图标行），
 * 包含 Agent 会话运行限制设置与 Subagent 子代理设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(onBack: () -> Unit) {
    // 新会话默认工具集弹窗与刷新计数（改动后立即刷新摘要文案）
    var showDefaultToolSetDialog by remember { mutableStateOf(false) }
    var toolSetRevision by remember { mutableStateOf(0) }
    val defaultToolSetStat = remember(toolSetRevision) { loadDefaultToolSetStat() }

    if (showDefaultToolSetDialog) {
        DefaultToolSetDialog(
            onDismiss = { showDefaultToolSetDialog = false },
            onChanged = { toolSetRevision++ }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_settings_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 运行限制分组：组标题 + 设置行（沿用拓展功能分组的卡片样式）
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.agent_settings_group_runtime),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                )
                AgentSettingRow(
                    icon = Icons.Filled.Psychology,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.agent_settings_max_tool_calls),
                    desc = stringResource(R.string.agent_settings_max_tool_calls_desc, MAX_TOOL_CALLS_DEFAULT),
                    trailing = {
                        ToolCallsInput()
                    }
                )
            }

            // Subagent 子代理设置分组
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.agent_settings_group_subagent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                )
                var subagentEnabled by remember {
                    mutableStateOf(ServiceContainer.prefs.subagentEnabled)
                }
                AgentSettingRow(
                    icon = Icons.Filled.AccountTree,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.agent_settings_subagent_enable),
                    desc = stringResource(R.string.agent_settings_subagent_enable_desc),
                    trailing = {
                        Switch(
                            checked = subagentEnabled,
                            onCheckedChange = {
                                subagentEnabled = it
                                ServiceContainer.prefs.subagentEnabled = it
                            }
                        )
                    }
                )
                AgentSettingRow(
                    icon = Icons.Filled.Timeline,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.agent_settings_subagent_max_depth),
                    desc = stringResource(R.string.agent_settings_subagent_max_depth_desc, SUBAGENT_MAX_DEPTH_DEFAULT),
                    trailing = {
                        NumericInput(
                            initial = ServiceContainer.prefs.subagentMaxDepth.toString(),
                            min = SUBAGENT_MAX_DEPTH_MIN,
                            max = SUBAGENT_MAX_DEPTH_MAX,
                            onValid = { ServiceContainer.prefs.subagentMaxDepth = it }
                        )
                    }
                )
                AgentSettingRow(
                    icon = Icons.Filled.Psychology,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.agent_settings_subagent_max_tool_calls),
                    desc = stringResource(R.string.agent_settings_subagent_max_tool_calls_desc, SUBAGENT_MAX_TOOL_CALLS_DEFAULT),
                    trailing = {
                        NumericInput(
                            initial = ServiceContainer.prefs.subagentMaxToolCalls.toString(),
                            min = SUBAGENT_MAX_TOOL_CALLS_MIN,
                            max = SUBAGENT_MAX_TOOL_CALLS_MAX,
                            onValid = { ServiceContainer.prefs.subagentMaxToolCalls = it }
                        )
                    }
                )
                var defaultBackground by remember {
                    mutableStateOf(ServiceContainer.prefs.subagentDefaultBackground)
                }
                AgentSettingRow(
                    icon = Icons.Filled.Timeline,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.agent_settings_subagent_default_background),
                    desc = stringResource(R.string.agent_settings_subagent_default_background_desc),
                    trailing = {
                        Switch(
                            checked = defaultBackground,
                            onCheckedChange = {
                                defaultBackground = it
                                ServiceContainer.prefs.subagentDefaultBackground = it
                            }
                        )
                    }
                )
            }

            // Agent 网络访问策略分组
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.agent_settings_group_tools),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                )
                var networkAccess by remember {
                    mutableStateOf(ServiceContainer.prefs.agentNetworkAccessEnabled)
                }
                AgentSettingRow(
                    icon = Icons.Filled.Cloud,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.agent_settings_network_access),
                    desc = stringResource(R.string.agent_settings_network_access_desc),
                    trailing = {
                        Switch(
                            checked = networkAccess,
                            onCheckedChange = {
                                networkAccess = it
                                ServiceContainer.prefs.agentNetworkAccessEnabled = it
                            }
                        )
                    }
                )
                var autoMemory by remember {
                    mutableStateOf(ServiceContainer.prefs.agentAutoMemoryEnabled)
                }
                AgentSettingRow(
                    icon = Icons.Filled.Psychology,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.agent_settings_auto_memory),
                    desc = stringResource(R.string.agent_settings_auto_memory_desc),
                    trailing = {
                        Switch(
                            checked = autoMemory,
                            onCheckedChange = {
                                autoMemory = it
                                ServiceContainer.prefs.agentAutoMemoryEnabled = it
                            }
                        )
                    }
                )
                // 自动总结 Skill：与自动长期记忆同组，沉淀"怎么做事"的流程型知识。
                var autoSkill by remember {
                    mutableStateOf(ServiceContainer.prefs.agentAutoSkillEnabled)
                }
                AgentSettingRow(
                    icon = Icons.Filled.AutoAwesome,
                    tint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.agent_settings_auto_skill),
                    desc = stringResource(R.string.agent_settings_auto_skill_desc),
                    trailing = {
                        Switch(
                            checked = autoSkill,
                            onCheckedChange = {
                                autoSkill = it
                                ServiceContainer.prefs.agentAutoSkillEnabled = it
                            }
                        )
                    }
                )
            }

            // 新会话默认工具集分组：仅本地模式有工具集概念（服务器模式工具由后端决定）。
            if (ServiceContainer.prefs.isLocalMode) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.agent_settings_group_default_toolset),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                    )
                    AgentSettingRow(
                        icon = Icons.Filled.Extension,
                        tint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.agent_settings_default_toolset),
                        desc = if (defaultToolSetStat.customized) {
                            stringResource(
                                R.string.agent_settings_default_toolset_customized,
                                defaultToolSetStat.enabledCategories,
                                defaultToolSetStat.totalCategories
                            )
                        } else {
                            stringResource(R.string.agent_settings_default_toolset_all)
                        },
                        trailing = {
                            TextButton(onClick = { showDefaultToolSetDialog = true }) {
                                Text(stringResource(R.string.agent_settings_default_toolset_config))
                            }
                        }
                    )
                    // 作用范围说明：新会话与未单独自定义的会话都跟随该默认值
                    Text(
                        text = stringResource(R.string.agent_settings_default_toolset_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 4.dp)
                    )
                }
            }

            // 截断字符数分组：所有工具的输出上限与进度卡预览上限各一个统一值。
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.agent_settings_group_limits),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                )
                AgentSettingRow(
                    icon = Icons.Filled.Straighten,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.agent_settings_tool_output_chars),
                    desc = stringResource(
                        R.string.agent_settings_tool_output_chars_desc,
                        AgentToolLimits.MIN_TOOL_OUTPUT_CHARS,
                        AgentToolLimits.MAX_TOOL_OUTPUT_CHARS,
                        AgentToolLimits.DEFAULT_TOOL_OUTPUT_CHARS
                    ),
                    trailing = {
                        NumericInput(
                            initial = ServiceContainer.prefs.agentToolOutputChars.toString(),
                            min = AgentToolLimits.MIN_TOOL_OUTPUT_CHARS,
                            max = AgentToolLimits.MAX_TOOL_OUTPUT_CHARS,
                            onValid = { ServiceContainer.prefs.agentToolOutputChars = it }
                        )
                    }
                )
                AgentSettingRow(
                    icon = Icons.AutoMirrored.Filled.Article,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.agent_settings_progress_preview_chars),
                    desc = stringResource(
                        R.string.agent_settings_progress_preview_chars_desc,
                        AgentToolLimits.MIN_PROGRESS_PREVIEW_CHARS,
                        AgentToolLimits.MAX_PROGRESS_PREVIEW_CHARS,
                        AgentToolLimits.DEFAULT_PROGRESS_PREVIEW_CHARS
                    ),
                    trailing = {
                        NumericInput(
                            initial = ServiceContainer.prefs.agentProgressPreviewChars.toString(),
                            min = AgentToolLimits.MIN_PROGRESS_PREVIEW_CHARS,
                            max = AgentToolLimits.MAX_PROGRESS_PREVIEW_CHARS,
                            onValid = { ServiceContainer.prefs.agentProgressPreviewChars = it }
                        )
                    }
                )
            }
        }
    }
}

/** 最大工具调用次数：数字输入，仅允许数字，合法范围（1-1000）内即时保存。 */
@Composable
private fun ToolCallsInput() {
    var text by remember {
        mutableStateOf(ServiceContainer.prefs.agentMaxToolCalls.toString())
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() }
            text = filtered
            filtered.toIntOrNull()?.let { num ->
                if (num in MAX_TOOL_CALLS_MIN..MAX_TOOL_CALLS_MAX) {
                    ServiceContainer.prefs.agentMaxToolCalls = num
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(92.dp)
    )
}

/** 通用数字输入：仅允许数字，合法范围内即时保存，非法输入仅更新显示不写入。 */
@Composable
private fun NumericInput(
    initial: String,
    min: Int,
    max: Int,
    onValid: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() }
            text = filtered
            filtered.toIntOrNull()?.let { num ->
                if (num in min..max) {
                    onValid(num)
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(92.dp)
    )
}

/** Agent 设置行：彩色图标底座 + 标题/描述 + 右侧控件（沿用拓展功能单行布局）。 */
@Composable
private fun AgentSettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** 「新会话默认工具集」摘要统计：是否已自定义 + 已启用大类数/大类总数。 */
private data class DefaultToolSetStat(
    val customized: Boolean,
    val enabledCategories: Int,
    val totalCategories: Int
)

/** 读取当前「新会话默认工具集」并统计大类启用情况（未设置默认时视为全部启用）。 */
private fun loadDefaultToolSetStat(): DefaultToolSetStat {
    val ids = ServiceContainer.unified.defaultSessionToolIds()
    val categories = ServiceContainer.unified.toolCategories()
    if (ids == null) {
        return DefaultToolSetStat(
            customized = false,
            enabledCategories = categories.size,
            totalCategories = categories.size
        )
    }
    return DefaultToolSetStat(
        customized = true,
        enabledCategories = categories.count { (_, toolIds) -> toolIds.all { it in ids } },
        totalCategories = categories.size
    )
}

/**
 * 「新会话默认工具集」编辑弹窗。
 *
 * 与聊天面板的会话工具集弹窗共用 [AgentToolSetPickerDialog] 界面，
 * 区别只在于读写的是全局默认配置（新会话与未单独自定义的会话沿用它）。
 */
@Composable
private fun DefaultToolSetDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val categories = remember { ServiceContainer.unified.toolCategories() }
    val allToolIds = remember(categories) { categories.flatMap { it.second }.toSet() }
    var enabled by remember {
        mutableStateOf(ServiceContainer.unified.defaultSessionToolIds() ?: allToolIds)
    }

    AgentToolSetPickerDialog(
        title = stringResource(R.string.agent_settings_default_toolset_dialog_title),
        subtitle = stringResource(R.string.agent_settings_default_toolset_dialog_subtitle),
        resetLabel = stringResource(R.string.toolset_reset_all),
        categories = categories,
        enabled = enabled,
        onToggleCategory = { categoryId, on ->
            ServiceContainer.unified.setDefaultSessionCategoryEnabled(categoryId, on)
            val toolIds = categories.firstOrNull { it.first == categoryId }?.second.orEmpty()
            enabled = if (on) enabled + toolIds else enabled - toolIds.toSet()
            onChanged()
        },
        onToggleTool = { toolId, on ->
            ServiceContainer.unified.setDefaultSessionToolEnabled(toolId, on)
            enabled = if (on) enabled + toolId else enabled - toolId
            onChanged()
        },
        onReset = {
            ServiceContainer.unified.resetDefaultSessionToolSet()
            enabled = allToolIds
            onChanged()
        },
        onDismiss = onDismiss
    )
}