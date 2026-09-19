package com.nekobot.app.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.ai.AgentToolLimits
import com.nekobot.app.data.local.ai.SessionToolCatalog
import com.nekobot.app.ui.components.AgentToolSetPickerDialog
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.toolSetModeDescription
import com.nekobot.app.ui.components.toolSetModeLabel
import com.nekobot.app.ui.components.toolSetModeNameResId
import com.nekobot.app.ui.navigation.Routes

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
fun AgentSettingsScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    // 工具集模式相关弹窗与刷新计数（任何改动后立即刷新摘要文案）
    var showDefaultModeDialog by remember { mutableStateOf(false) }
    var showDefaultToolSetDialog by remember { mutableStateOf(false) }
    var showManageModesDialog by remember { mutableStateOf(false) }
    var editingCustomModeId by remember { mutableStateOf<String?>(null) }
    var toolSetRevision by remember { mutableStateOf(0) }
    val toolSetStat = remember(toolSetRevision) { loadToolSetStat() }
    // 无障碍排除的应用：命中后 Agent 无法读取界面/截图/操作该应用
    var showExcludedAppsDialog by remember { mutableStateOf(false) }
    var excludedAppsRevision by remember { mutableStateOf(0) }
    val excludedAppsCount = remember(excludedAppsRevision) {
        ServiceContainer.prefs.agentAccessibilityExcludedPackages.size
    }
    // 自动长期记忆的触发间隔（轮）：写入设置后立即刷新摘要文案
    var showMemoryIntervalDialog by remember { mutableStateOf(false) }
    var memoryInterval by remember { mutableStateOf(ServiceContainer.prefs.agentMemoryInterval) }

    if (showMemoryIntervalDialog) {
        MemoryIntervalDialog(
            current = memoryInterval,
            onSelect = {
                ServiceContainer.prefs.agentMemoryInterval = it
                memoryInterval = ServiceContainer.prefs.agentMemoryInterval
                showMemoryIntervalDialog = false
            },
            onDismiss = { showMemoryIntervalDialog = false }
        )
    }

    if (showExcludedAppsDialog) {
        AccessibilityExcludedAppsDialog(
            onDismiss = { showExcludedAppsDialog = false },
            onChanged = { excludedAppsRevision++ }
        )
    }

    if (showDefaultModeDialog) {
        DefaultModeDialog(
            onDismiss = { showDefaultModeDialog = false },
            onChanged = { toolSetRevision++ }
        )
    }
    if (showDefaultToolSetDialog) {
        DefaultToolSetDialog(
            onDismiss = { showDefaultToolSetDialog = false },
            onChanged = { toolSetRevision++ }
        )
    }
    if (showManageModesDialog) {
        ManageCustomModesDialog(
            onDismiss = { showManageModesDialog = false },
            onChanged = { toolSetRevision++ },
            onEditMode = { modeId ->
                showManageModesDialog = false
                editingCustomModeId = modeId
            }
        )
    }
    editingCustomModeId?.let { modeId ->
        CustomModeToolSetDialog(
            modeId = modeId,
            onDismiss = { editingCustomModeId = null },
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
                AgentSettingRow(
                    icon = Icons.Filled.Shield,
                    tint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.agent_settings_accessibility_excluded),
                    desc = stringResource(
                        R.string.agent_settings_accessibility_excluded_desc,
                        excludedAppsCount
                    ),
                    trailing = {
                        TextButton(onClick = { showExcludedAppsDialog = true }) {
                            Text(stringResource(R.string.agent_settings_accessibility_excluded_action))
                        }
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
                // 记忆抽取频率：只在自动长期记忆开启时才有意义，关闭时整行淡出并禁用。
                AgentSettingRow(
                    icon = Icons.Filled.Timeline,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.agent_settings_memory_interval),
                    desc = stringResource(R.string.agent_settings_memory_interval_desc),
                    trailing = {
                        TextButton(
                            onClick = { showMemoryIntervalDialog = true },
                            enabled = autoMemory
                        ) {
                            Text(stringResource(R.string.agent_settings_memory_interval_value, memoryInterval))
                        }
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

            // 浏览器与沙盒分组：browser_use 工具配置入口 + Linux 沙盒管理入口。
            if (ServiceContainer.prefs.isLocalMode) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.agent_settings_group_browser_sandbox),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                    )
                    AgentNavigateRow(
                        icon = Icons.Filled.Language,
                        tint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.agent_settings_browser),
                        desc = stringResource(R.string.agent_settings_browser_desc),
                        onClick = { onNavigate(Routes.BROWSER_SETTINGS) }
                    )
                    AgentNavigateRow(
                        icon = Icons.Filled.Terminal,
                        tint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.agent_settings_sandbox),
                        desc = stringResource(R.string.agent_settings_sandbox_desc),
                        onClick = { onNavigate(Routes.SANDBOX_MANAGEMENT) }
                    )
                }
            }

            // 工具集模式分组：内置模式一键套用 + 默认工具集明细 + 自定义模式管理。
            if (ServiceContainer.prefs.isLocalMode) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.agent_settings_group_toolset_mode),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                    )
                    AgentSettingRow(
                        icon = Icons.Filled.Extension,
                        tint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.agent_settings_default_mode),
                        desc = defaultModeLabel(toolSetStat),
                        trailing = {
                            TextButton(onClick = { showDefaultModeDialog = true }) {
                                Text(stringResource(R.string.agent_settings_default_mode_pick))
                            }
                        }
                    )
                    AgentSettingRow(
                        icon = Icons.Filled.Tune,
                        tint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.agent_settings_default_toolset),
                        desc = if (toolSetStat.customized) {
                            stringResource(
                                R.string.agent_settings_default_toolset_customized,
                                toolSetStat.enabledCategories,
                                toolSetStat.totalCategories
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
                    AgentSettingRow(
                        icon = Icons.Filled.Bookmarks,
                        tint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.agent_settings_custom_modes),
                        desc = stringResource(
                            R.string.agent_settings_custom_modes_count,
                            toolSetStat.customModeCount
                        ),
                        trailing = {
                            TextButton(onClick = { showManageModesDialog = true }) {
                                Text(stringResource(R.string.agent_settings_custom_modes_manage))
                            }
                        }
                    )
                    // 作用范围说明：新会话与未单独自定义的会话都跟随默认
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

/**
 * 「无障碍排除的应用」弹窗：勾选后，Agent 在这些应用处于前台时会被拒绝读取界面树、
 * 截图、点击与输入。用于把银行、支付、密码管理类应用挡在自动化之外。
 *
 * 只列举桌面可启动应用（Manifest 已声明 LAUNCHER 的 `<queries>`，无需 QUERY_ALL_PACKAGES）。
 */
@Composable
private fun AccessibilityExcludedAppsDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val apps = remember(context) {
        runCatching {
            val manager = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            manager.queryIntentActivities(intent, 0)
                .mapNotNull { info ->
                    val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (packageName == context.packageName) return@mapNotNull null
                    val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault(packageName)
                    packageName to label
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }
    var excluded by remember {
        mutableStateOf(ServiceContainer.prefs.agentAccessibilityExcludedPackages)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.agent_settings_accessibility_excluded_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.agent_settings_accessibility_excluded_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.agent_settings_accessibility_excluded_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(apps) { _, app ->
                        val selected = app.first in excluded
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                                    }
                                )
                                .clickable {
                                    excluded = if (selected) excluded - app.first else excluded + app.first
                                    ServiceContainer.prefs.agentAccessibilityExcludedPackages = excluded
                                    onChanged()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.second,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.first,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

/** Agent 设置行：彩色图标底座 + 标题/描述 + 右侧控件（沿用拓展功能单行布局）。 */
@Composable
private fun AgentSettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = modifier
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

/** 可点击进入子界面的设置行：与 [AgentSettingRow] 同布局，右侧固定为右箭头。 */
@Composable
private fun AgentNavigateRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    AgentSettingRow(
        icon = icon,
        tint = tint,
        title = title,
        desc = desc,
        modifier = Modifier.clickable(onClick = onClick),
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/** 工具集模式分组摘要：当前默认模式 + 默认工具集统计 + 自定义模式数量。 */
private data class ToolSetStat(
    /** 当前默认工具集匹配到的模式 id；null 表示自定义（不对应任何模式）。 */
    val modeId: String?,
    /** 匹配到自定义模式时的名称（内置模式为 null，由字符串资源提供）。 */
    val customModeName: String?,
    val customized: Boolean,
    val enabledCategories: Int,
    val totalCategories: Int,
    val customModeCount: Int
)

/** 读取当前默认工具集状态并统计（未设置默认时视为全部启用）。 */
private fun loadToolSetStat(): ToolSetStat {
    val ids = ServiceContainer.unified.defaultSessionToolIds()
    val categories = ServiceContainer.unified.toolCategories()
    val modes = ServiceContainer.unified.toolSetModes()
    val modeId = ServiceContainer.unified.defaultToolSetModeId()
    val matched = modes.firstOrNull { it.id == modeId }
    return ToolSetStat(
        modeId = modeId,
        customModeName = matched?.takeIf { !it.builtin }?.name,
        customized = ids != null,
        enabledCategories = if (ids == null) {
            categories.size
        } else {
            categories.count { (_, toolIds) -> toolIds.all { it in ids } }
        },
        totalCategories = categories.size,
        customModeCount = modes.count { !it.builtin }
    )
}

/**
 * 当前默认模式的展示名：匹配到内置模式走字符串资源，
 * 匹配到自定义模式用用户填写的名称，都没匹配上显示「自定义」。
 */
@Composable
private fun defaultModeLabel(stat: ToolSetStat): String {
    val modeId = stat.modeId
    if (modeId == null) return stringResource(R.string.agent_settings_default_mode_custom)
    stat.customModeName?.let { return it }
    val resId = toolSetModeNameResId(modeId)
    return if (resId != 0) stringResource(resId) else modeId
}

/** 自定义模式管理弹窗里正在进行的操作。 */
private sealed interface CustomModeAction {
    /** 以当前默认工具集为模板新建模式。 */
    object Create : CustomModeAction

    /** 重命名指定模式。 */
    data class Rename(val id: String) : CustomModeAction

    /** 删除指定模式。 */
    data class Delete(val id: String, val name: String) : CustomModeAction
}

/**
 * 「记忆间隔」选择弹窗：列出可选轮数，点选即写入设置。
 *
 * 首轮一定会抽取，这里选的是之后每隔多少轮再抽一次。
 */
@Composable
private fun MemoryIntervalDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.agent_settings_memory_interval_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PrefsManager.AGENT_MEMORY_INTERVAL_OPTIONS.forEach { option ->
                    val selected = option == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                                }
                            )
                            .clickable { onSelect(option) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.agent_settings_memory_interval_value, option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 「新会话默认模式」选择弹窗：列出全部内置与自定义模式，点选即套用为默认工具集。
 */
@Composable
private fun DefaultModeDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val modes = remember { ServiceContainer.unified.toolSetModes() }
    val activeModeId = remember { ServiceContainer.unified.defaultToolSetModeId() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.agent_settings_default_mode_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.agent_settings_default_mode_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(modes) { _, mode ->
                    val selected = mode.id == activeModeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                                }
                            )
                            .clickable {
                                ServiceContainer.unified.applyDefaultToolSetMode(mode.id)
                                onChanged()
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                toolSetModeLabel(mode),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                toolSetModeDescription(mode),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        }
    )
}

/**
 * 「新会话默认工具集」明细编辑弹窗。
 *
 * 顶部可一键换成某个模式，下方仍可按大类、按单个工具细化；
 * 细则调整后当前默认就不再对应任何模式（显示为「自定义」）。
 */
@Composable
private fun DefaultToolSetDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val categories = remember { ServiceContainer.unified.toolCategories() }
    val modes = remember { ServiceContainer.unified.toolSetModes() }
    var enabled by remember {
        mutableStateOf(ServiceContainer.unified.effectiveDefaultSessionToolIds())
    }
    var activeModeId by remember { mutableStateOf(ServiceContainer.unified.defaultToolSetModeId()) }

    // 任何改动后从仓库重读：动态大类（MCP）的默认放行由注册表决定，手写增删易失真。
    fun reload() {
        enabled = ServiceContainer.unified.effectiveDefaultSessionToolIds()
        activeModeId = ServiceContainer.unified.defaultToolSetModeId()
        onChanged()
    }

    AgentToolSetPickerDialog(
        title = stringResource(R.string.agent_settings_default_toolset_dialog_title),
        subtitle = stringResource(R.string.agent_settings_default_toolset_dialog_subtitle),
        resetLabel = stringResource(R.string.toolset_reset_all),
        categories = categories,
        enabled = enabled,
        onToggleCategory = { categoryId, on ->
            ServiceContainer.unified.setDefaultSessionCategoryEnabled(categoryId, on)
            reload()
        },
        onToggleTool = { toolId, on ->
            ServiceContainer.unified.setDefaultSessionToolEnabled(toolId, on)
            reload()
        },
        onReset = {
            ServiceContainer.unified.resetDefaultSessionToolSet()
            reload()
        },
        onDismiss = onDismiss,
        modes = modes,
        activeModeId = activeModeId,
        onSelectMode = { modeId ->
            ServiceContainer.unified.applyDefaultToolSetMode(modeId)
            reload()
        }
    )
}

/**
 * 自定义模式管理弹窗：新建（以当前默认工具集为模板）/ 重命名 / 删除 / 进入明细编辑。
 * 三个操作都在同一个弹窗内切换内容，避免多层弹窗叠加。
 */
@Composable
private fun ManageCustomModesDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onEditMode: (String) -> Unit
) {
    var modes by remember { mutableStateOf(ServiceContainer.unified.customToolSetModes()) }
    var pendingAction by remember { mutableStateOf<CustomModeAction?>(null) }
    var nameInput by remember { mutableStateOf("") }

    fun reload() {
        modes = ServiceContainer.unified.customToolSetModes()
        onChanged()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.agent_settings_custom_mode_manage_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.agent_settings_custom_mode_manage_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            when (val action = pendingAction) {
                null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (modes.isEmpty()) {
                            Text(
                                text = stringResource(R.string.agent_settings_custom_mode_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                            )
                        }
                        modes.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        mode.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        stringResource(
                                            R.string.toolset_mode_tools_count,
                                            mode.toolIds.size
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onEditMode(mode.id) }) {
                                    Icon(
                                        Icons.Filled.Tune,
                                        contentDescription = stringResource(R.string.agent_settings_custom_mode_edit),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = {
                                    nameInput = mode.name
                                    pendingAction = CustomModeAction.Rename(mode.id)
                                }) {
                                    Icon(
                                        Icons.Filled.DriveFileRenameOutline,
                                        contentDescription = stringResource(R.string.agent_settings_custom_mode_rename),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = {
                                    pendingAction = CustomModeAction.Delete(mode.id, mode.name)
                                }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(R.string.agent_settings_custom_mode_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                CustomModeAction.Create, is CustomModeAction.Rename -> {
                    Column {
                        Text(
                            text = stringResource(R.string.agent_settings_custom_mode_name_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it.take(30) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (action == CustomModeAction.Create) {
                            Text(
                                text = stringResource(R.string.agent_settings_custom_mode_create_from_default),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                            )
                        }
                    }
                }

                is CustomModeAction.Delete -> {
                    Text(
                        text = stringResource(
                            R.string.agent_settings_custom_mode_delete_confirm,
                            action.name
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            when (pendingAction) {
                null -> TextButton(onClick = {
                    nameInput = ""
                    pendingAction = CustomModeAction.Create
                }) {
                    Text(stringResource(R.string.agent_settings_custom_mode_new))
                }

                CustomModeAction.Create -> TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        // 以当前默认工具集为模板创建，随后直接进入明细编辑微调。
                        val newId = ServiceContainer.unified.saveCustomToolSetMode(
                            id = null,
                            name = nameInput,
                            toolIds = ServiceContainer.unified.effectiveDefaultSessionToolIds(),
                            includeDynamic = ServiceContainer.unified.defaultToolSetDynamicOn()
                        )
                        pendingAction = null
                        reload()
                        if (newId != null) onEditMode(newId)
                    }
                ) {
                    Text(stringResource(R.string.agent_settings_custom_mode_create))
                }

                is CustomModeAction.Rename -> TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        val action = pendingAction as? CustomModeAction.Rename
                        if (action != null) {
                            ServiceContainer.unified.renameCustomToolSetMode(action.id, nameInput)
                        }
                        pendingAction = null
                        reload()
                    }
                ) {
                    Text(stringResource(R.string.common_save))
                }

                is CustomModeAction.Delete -> TextButton(
                    onClick = {
                        val action = pendingAction as? CustomModeAction.Delete
                        if (action != null) {
                            ServiceContainer.unified.deleteCustomToolSetMode(action.id)
                        }
                        pendingAction = null
                        reload()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.agent_settings_custom_mode_delete))
                }
            }
        },
        dismissButton = {
            if (pendingAction == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
            } else {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )
}

/**
 * 自定义模式明细编辑弹窗：改动即时保存到该模式。
 * 动态大类（MCP）用 [ToolSetMode.includeDynamic] 单独表达，
 * 因此即使当前还没连上任何 MCP 服务器，也能记住“这个模式要用 MCP 工具”。
 */
@Composable
private fun CustomModeToolSetDialog(
    modeId: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val categories = remember { ServiceContainer.unified.toolCategories() }
    val mode = remember(modeId) {
        ServiceContainer.unified.customToolSetModes().firstOrNull { it.id == modeId }
    }
    if (mode == null) return

    var name by remember(modeId) { mutableStateOf(mode.name) }
    var enabled by remember(modeId) { mutableStateOf(mode.toolIds) }
    var includeDynamic by remember(modeId) { mutableStateOf(mode.includeDynamic) }

    fun persist() {
        ServiceContainer.unified.saveCustomToolSetMode(modeId, name, enabled, includeDynamic)
        onChanged()
    }

    AgentToolSetPickerDialog(
        title = stringResource(R.string.agent_settings_custom_mode_edit_title, name),
        subtitle = stringResource(R.string.agent_settings_custom_mode_edit_subtitle),
        resetLabel = stringResource(R.string.toolset_reset_all),
        categories = categories,
        enabled = enabled,
        onToggleCategory = { categoryId, on ->
            val toolIds = categories.firstOrNull { it.first == categoryId }?.second.orEmpty()
            enabled = if (on) enabled + toolIds else enabled - toolIds.toSet()
            if (categoryId == SessionToolCatalog.MCP_CATEGORY_ID) includeDynamic = on
            persist()
        },
        onToggleTool = { toolId, on ->
            enabled = if (on) enabled + toolId else enabled - toolId
            persist()
        },
        onReset = {
            enabled = SessionToolCatalog.staticToolIds
            includeDynamic = true
            persist()
        },
        onDismiss = onDismiss
    )
}