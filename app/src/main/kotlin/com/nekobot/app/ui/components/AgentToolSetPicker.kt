package com.nekobot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.ToolSetMode
import com.nekobot.app.data.local.ai.ToolSetModeCatalog
import com.nekobot.app.data.local.ai.toolDisplayFallbackName
import com.nekobot.app.data.local.db.BuiltinTools

/**
 * Agent 工具集选择弹窗（通用实现）。
 *
 * 顶层为「模式」一键套用（[modes]），中间为大类整体开关，点击大类可展开到其中的
 * 单个工具做细化选择。组件本身不负责持久化：读写全部由调用方通过回调完成，因此
 * 「当前会话工具集」「新会话默认工具集」「自定义模式编辑」共用同一套界面与交互。
 *
 * @param enabled 当前启用的工具 id 集合（由调用方持有状态，回调里更新后本弹窗自动重组）
 * @param activeModeId 当前生效的模式 id；null 表示不对应任何模式（自定义）
 * @param onSelectMode 点选模式（null 表示不显示模式行）
 * @param showFollowDefaultChip 是否显示「跟随默认」芯片（会话级才有“跟随默认”的概念）
 * @param onFollowDefault 点选「跟随默认」
 */
@Composable
internal fun AgentToolSetPickerDialog(
    title: String,
    subtitle: String,
    resetLabel: String,
    categories: List<Pair<String, List<String>>>,
    enabled: Set<String>,
    onToggleCategory: (categoryId: String, enabled: Boolean) -> Unit,
    onToggleTool: (toolId: String, enabled: Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modes: List<ToolSetMode> = emptyList(),
    activeModeId: String? = null,
    showFollowDefaultChip: Boolean = false,
    followDefault: Boolean = false,
    onSelectMode: ((modeId: String) -> Unit)? = null,
    onFollowDefault: (() -> Unit)? = null
) {
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    // 工具 id → 内置中文名，用于本地化资源缺失时的兜底展示
    val specByName = remember {
        BuiltinTools.all.associateBy { it.id }
    }

    // 优先使用本地化资源（tool_name_<id>），缺失时回退到内置中文名/id
    @Composable
    fun toolLabel(id: String): String {
        val resId = remember(id) { toolNameResId(id) }
        return if (resId != 0) {
            stringResource(resId)
        } else {
            specByName[id]?.name?.takeIf { it.isNotBlank() }
                ?: toolDisplayFallbackName(id)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                // 模式行：一键套用整套工具集，之后仍可用下方开关继续细化
                if (onSelectMode != null && (modes.isNotEmpty() || showFollowDefaultChip)) {
                    Text(
                        text = stringResource(R.string.toolset_modes_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (showFollowDefaultChip) {
                            ToolSetModeChip(
                                label = stringResource(R.string.toolset_mode_follow_default),
                                selected = followDefault,
                                onClick = { onFollowDefault?.invoke() }
                            )
                        }
                        modes.forEach { mode ->
                            ToolSetModeChip(
                                // 「跟随默认」时同时点亮默认所对应的模式，用户能看出跟随到了哪套工具
                                label = toolSetModeLabel(mode),
                                selected = activeModeId == mode.id,
                                onClick = { onSelectMode(mode.id) }
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            expandedCategory = null
                            onReset()
                        }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        resetLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(categories) { index, (categoryId, toolIds) ->
                        val allOn = toolIds.all { it in enabled }
                        val anyOn = toolIds.any { it in enabled }
                        val expanded = expandedCategory == categoryId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedCategory = if (expanded) null else categoryId }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        toolsetCategoryName(categoryId),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        when {
                                            allOn -> stringResource(R.string.toolset_cat_all_on)
                                            anyOn -> stringResource(R.string.toolset_cat_partial)
                                            else -> stringResource(R.string.toolset_cat_off)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    stringResource(R.string.toolset_tools_count, toolIds.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Switch(
                                    checked = allOn,
                                    onCheckedChange = { onToggleCategory(categoryId, it) }
                                )
                                Icon(
                                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            AnimatedVisibility(visible = expanded) {
                                Column {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                    toolIds.forEach { toolId ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                toolLabel(toolId),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Switch(
                                                checked = toolId in enabled,
                                                onCheckedChange = { on -> onToggleTool(toolId, on) }
                                            )
                                        }
                                    }
                                }
                            }
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

/** 工具集大类 id → 本地化名称；未收录时回退为 id 本身。 */
@Composable
internal fun toolsetCategoryName(categoryId: String): String = when (categoryId) {
    "basic" -> stringResource(R.string.toolset_cat_basic)
    "browser" -> stringResource(R.string.toolset_cat_browser)
    "linux" -> stringResource(R.string.toolset_cat_linux)
    "workspace" -> stringResource(R.string.toolset_cat_workspace)
    "image" -> stringResource(R.string.toolset_cat_image)
    "memory" -> stringResource(R.string.toolset_cat_memory)
    "task" -> stringResource(R.string.toolset_cat_task)
    "plugin" -> stringResource(R.string.toolset_cat_plugin)
    "android" -> stringResource(R.string.toolset_cat_android)
    "db" -> stringResource(R.string.toolset_cat_db)
    "subagent" -> stringResource(R.string.toolset_cat_subagent)
    "mcp" -> stringResource(R.string.toolset_cat_mcp)
    else -> categoryId
}

/** 工具 id → 本地化名称字符串资源 id；未收录时返回 0（由调用方回退）。 */
internal fun toolNameResId(id: String): Int = when (id) {
    "get_weather" -> R.string.tool_name_get_weather
    "search_web" -> R.string.tool_name_search_web
    "http_get" -> R.string.tool_name_http_get
    "web_fetch" -> R.string.tool_name_web_fetch
    "grep" -> R.string.tool_name_grep
    "glob" -> R.string.tool_name_glob
    "subagent_kill" -> R.string.tool_name_subagent_kill
    "todo_read" -> R.string.tool_name_todo_read
    "shell_job" -> R.string.tool_name_shell_job
    "get_date_time" -> R.string.tool_name_get_date_time
    "download_file" -> R.string.tool_name_download_file
    "browser_use" -> R.string.tool_name_browser_use
    "exec_command" -> R.string.tool_name_exec_command
    "file_read" -> R.string.tool_name_file_read
    "file_write" -> R.string.tool_name_file_write
    "file_edit" -> R.string.tool_name_file_edit
    "workspace_create_file" -> R.string.tool_name_workspace_create_file
    "workspace_read_file" -> R.string.tool_name_workspace_read_file
    "workspace_edit_file" -> R.string.tool_name_workspace_edit_file
    "workspace_delete_file" -> R.string.tool_name_workspace_delete_file
    "workspace_list_files" -> R.string.tool_name_workspace_list_files
    "workspace_send_file" -> R.string.tool_name_workspace_send_file
    "workspace_parse_file" -> R.string.tool_name_workspace_parse_file
    "workspace_extract_epub" -> R.string.tool_name_workspace_extract_epub
    "workspace_file_info" -> R.string.tool_name_workspace_file_info
    "workspace_skill_copy" -> R.string.tool_name_workspace_skill_copy
    "understand_image" -> R.string.tool_name_understand_image
    "generate_image" -> R.string.tool_name_generate_image
    "read_image" -> R.string.tool_name_read_image
    "save_to_memory" -> R.string.tool_name_save_to_memory
    "read_memory" -> R.string.tool_name_read_memory
    "agent_memory_read" -> R.string.tool_name_agent_memory_read
    "agent_memory_update" -> R.string.tool_name_agent_memory_update
    "todo_write" -> R.string.tool_name_todo_write
    "ask_user_question" -> R.string.tool_name_ask_user_question
    "get_session_thinking_history" -> R.string.tool_name_get_session_thinking_history
    "send_message" -> R.string.tool_name_send_message
    "plugin_use" -> R.string.tool_name_plugin_use
    "skill_list" -> R.string.tool_name_skill_list
    "skill_view" -> R.string.tool_name_skill_view
    "skill_read" -> R.string.tool_name_skill_read
    "skill_get_info" -> R.string.tool_name_skill_get_info
    "android_help" -> R.string.tool_name_android_help
    "android_device_info" -> R.string.tool_name_android_device_info
    "android_battery_status" -> R.string.tool_name_android_battery_status
    "android_clipboard_read" -> R.string.tool_name_android_clipboard_read
    "android_clipboard_write" -> R.string.tool_name_android_clipboard_write
    "android_open_url" -> R.string.tool_name_android_open_url
    "android_list_apps" -> R.string.tool_name_android_list_apps
    "android_open_app" -> R.string.tool_name_android_open_app
    "android_open_settings" -> R.string.tool_name_android_open_settings
    "android_create_calendar_event" -> R.string.tool_name_android_create_calendar_event
    "android_set_alarm" -> R.string.tool_name_android_set_alarm
    "android_volume" -> R.string.tool_name_android_volume
    "android_accessibility_status" -> R.string.tool_name_android_accessibility_status
    "android_ui_tree" -> R.string.tool_name_android_ui_tree
    "android_ui_click" -> R.string.tool_name_android_ui_click
    "android_ui_set_text" -> R.string.tool_name_android_ui_set_text
    "android_ui_scroll" -> R.string.tool_name_android_ui_scroll
    "android_ui_tap" -> R.string.tool_name_android_ui_tap
    "android_ui_swipe" -> R.string.tool_name_android_ui_swipe
    "android_ui_ime_action" -> R.string.tool_name_android_ui_ime_action
    "android_ui_paste" -> R.string.tool_name_android_ui_paste
    "android_wait_for_idle" -> R.string.tool_name_android_wait_for_idle
    "android_global_action" -> R.string.tool_name_android_global_action
    "android_screenshot" -> R.string.tool_name_android_screenshot
    "android_step" -> R.string.tool_name_android_step
    "android_notifications" -> R.string.tool_name_android_notifications
    "android_notification_action" -> R.string.tool_name_android_notification_action
    "android_media_control" -> R.string.tool_name_android_media_control
    "db_list_characters" -> R.string.tool_name_db_list_characters
    "db_get_character" -> R.string.tool_name_db_get_character
    "db_create_character" -> R.string.tool_name_db_create_character
    "db_update_character" -> R.string.tool_name_db_update_character
    "db_delete_character" -> R.string.tool_name_db_delete_character
    "db_list_world_books" -> R.string.tool_name_db_list_world_books
    "db_get_world_book" -> R.string.tool_name_db_get_world_book
    "db_create_world_book" -> R.string.tool_name_db_create_world_book
    "db_update_world_book" -> R.string.tool_name_db_update_world_book
    "db_delete_world_book" -> R.string.tool_name_db_delete_world_book
    "db_upsert_world_book_entry" -> R.string.tool_name_db_upsert_world_book_entry
    "db_delete_world_book_entry" -> R.string.tool_name_db_delete_world_book_entry
    "db_token_stats" -> R.string.tool_name_db_token_stats
    "db_token_rankings" -> R.string.tool_name_db_token_rankings
    "db_session_token_usage" -> R.string.tool_name_db_session_token_usage
    "db_list_memories" -> R.string.tool_name_db_list_memories
    "db_save_memory" -> R.string.tool_name_db_save_memory
    "db_delete_memory" -> R.string.tool_name_db_delete_memory
    "db_list_state_history" -> R.string.tool_name_db_list_state_history
    "db_get_latest_state" -> R.string.tool_name_db_get_latest_state
    "db_list_hooks" -> R.string.tool_name_db_list_hooks
    "db_create_hook" -> R.string.tool_name_db_create_hook
    "db_update_hook" -> R.string.tool_name_db_update_hook
    "db_delete_hook" -> R.string.tool_name_db_delete_hook
    "db_toggle_hook" -> R.string.tool_name_db_toggle_hook
    "db_list_workflows" -> R.string.tool_name_db_list_workflows
    "db_create_workflow" -> R.string.tool_name_db_create_workflow
    "db_update_workflow" -> R.string.tool_name_db_update_workflow
    "db_delete_workflow" -> R.string.tool_name_db_delete_workflow
    "db_list_tasks" -> R.string.tool_name_db_list_tasks
    "db_create_task" -> R.string.tool_name_db_create_task
    "db_update_task" -> R.string.tool_name_db_update_task
    "db_delete_task" -> R.string.tool_name_db_delete_task
    "db_list_skills" -> R.string.tool_name_db_list_skills
    "db_create_skill" -> R.string.tool_name_db_create_skill
    "db_update_skill" -> R.string.tool_name_db_update_skill
    "db_delete_skill" -> R.string.tool_name_db_delete_skill
    "db_toggle_skill" -> R.string.tool_name_db_toggle_skill
    "db_list_ai_models" -> R.string.tool_name_db_list_ai_models
    "db_get_ai_model" -> R.string.tool_name_db_get_ai_model
    "db_create_ai_model" -> R.string.tool_name_db_create_ai_model
    "db_update_ai_model" -> R.string.tool_name_db_update_ai_model
    "db_delete_ai_model" -> R.string.tool_name_db_delete_ai_model
    "db_set_active_model" -> R.string.tool_name_db_set_active_model
    "db_get_ai_config" -> R.string.tool_name_db_get_ai_config
    "db_update_ai_config" -> R.string.tool_name_db_update_ai_config
    "subagent" -> R.string.tool_name_subagent
    "subagent_list" -> R.string.tool_name_subagent_list
    "subagent_get" -> R.string.tool_name_subagent_get
    else -> 0
}

/** 内置工具集模式名称资源；自定义模式返回 0（名称由用户填写）。 */
internal fun toolSetModeNameResId(modeId: String): Int = when (modeId) {
    ToolSetModeCatalog.MINIMAL_MODE_ID -> R.string.toolset_mode_minimal
    ToolSetModeCatalog.STANDARD_MODE_ID -> R.string.toolset_mode_standard
    ToolSetModeCatalog.ANDROID_MODE_ID -> R.string.toolset_mode_android
    ToolSetModeCatalog.CHARACTER_MODE_ID -> R.string.toolset_mode_character
    ToolSetModeCatalog.ALL_MODE_ID -> R.string.toolset_mode_all
    else -> 0
}

/** 内置工具集模式的一句话说明资源；自定义模式返回 0。 */
internal fun toolSetModeDescResId(modeId: String): Int = when (modeId) {
    ToolSetModeCatalog.MINIMAL_MODE_ID -> R.string.toolset_mode_minimal_desc
    ToolSetModeCatalog.STANDARD_MODE_ID -> R.string.toolset_mode_standard_desc
    ToolSetModeCatalog.ANDROID_MODE_ID -> R.string.toolset_mode_android_desc
    ToolSetModeCatalog.CHARACTER_MODE_ID -> R.string.toolset_mode_character_desc
    ToolSetModeCatalog.ALL_MODE_ID -> R.string.toolset_mode_all_desc
    else -> 0
}

/** 模式展示名：内置模式走字符串资源，自定义模式用用户填写的名称。 */
@Composable
internal fun toolSetModeLabel(mode: ToolSetMode): String {
    if (!mode.builtin) return mode.name
    val resId = toolSetModeNameResId(mode.id)
    return if (resId != 0) stringResource(resId) else mode.id
}

/** 模式展示说明：内置模式有文案，自定义模式回退为工具数。 */
@Composable
internal fun toolSetModeDescription(mode: ToolSetMode): String {
    val resId = if (mode.builtin) toolSetModeDescResId(mode.id) else 0
    return if (resId != 0) {
        stringResource(resId)
    } else {
        stringResource(R.string.toolset_mode_tools_count, mode.toolIds.size)
    }
}

/** 模式芯片：选中态用主色描边 + 淡底，未选中用中性底色。 */
@Composable
private fun ToolSetModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
