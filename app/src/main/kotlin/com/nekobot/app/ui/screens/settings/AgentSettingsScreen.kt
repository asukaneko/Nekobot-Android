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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Timeline
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