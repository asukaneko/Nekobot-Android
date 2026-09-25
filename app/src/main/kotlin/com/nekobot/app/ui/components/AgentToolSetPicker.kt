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
import com.nekobot.app.data.local.ai.buildLocalAgentToolDefinitions
import com.nekobot.app.data.local.ai.buildLocalDbToolDefinitions
import com.nekobot.app.data.local.ai.buildLocalSkillToolDefinitions
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
    "sticker" -> stringResource(R.string.toolset_cat_sticker)
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
    "understand_image" -> R.string.tool_name_understand_image
    "generate_image" -> R.string.tool_name_generate_image
    "read_image" -> R.string.tool_name_read_image
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
    "list_stickers" -> R.string.tool_name_list_stickers
    "send_sticker" -> R.string.tool_name_send_sticker
    "view_sticker" -> R.string.tool_name_view_sticker
    else -> 0
}

/**
 * 工具 id → 本地化描述字符串资源 id；未收录时返回 0（由调用方回退数据库中的内置描述）。
 *
 * 与 [toolNameResId] 一样，这里只做 id → 资源映射，不写文案本身；
 * 新增工具补一条 `tool_desc_<id>` 即可，四个语言目录同步添加。
 */
internal fun toolDescResId(id: String): Int = when (id) {
    "get_weather" -> R.string.tool_desc_get_weather
    "search_web" -> R.string.tool_desc_search_web
    "http_get" -> R.string.tool_desc_http_get
    "web_fetch" -> R.string.tool_desc_web_fetch
    "grep" -> R.string.tool_desc_grep
    "glob" -> R.string.tool_desc_glob
    "get_date_time" -> R.string.tool_desc_get_date_time
    "download_file" -> R.string.tool_desc_download_file
    "browser_use" -> R.string.tool_desc_browser_use
    "exec_command" -> R.string.tool_desc_exec_command
    "shell_job" -> R.string.tool_desc_shell_job
    "file_read" -> R.string.tool_desc_file_read
    "file_write" -> R.string.tool_desc_file_write
    "file_edit" -> R.string.tool_desc_file_edit
    "workspace_create_file" -> R.string.tool_desc_workspace_create_file
    "workspace_read_file" -> R.string.tool_desc_workspace_read_file
    "workspace_edit_file" -> R.string.tool_desc_workspace_edit_file
    "workspace_delete_file" -> R.string.tool_desc_workspace_delete_file
    "workspace_list_files" -> R.string.tool_desc_workspace_list_files
    "workspace_send_file" -> R.string.tool_desc_workspace_send_file
    "workspace_parse_file" -> R.string.tool_desc_workspace_parse_file
    "workspace_extract_epub" -> R.string.tool_desc_workspace_extract_epub
    "workspace_file_info" -> R.string.tool_desc_workspace_file_info
    "understand_image" -> R.string.tool_desc_understand_image
    "generate_image" -> R.string.tool_desc_generate_image
    "read_image" -> R.string.tool_desc_read_image
    "agent_memory_read" -> R.string.tool_desc_agent_memory_read
    "agent_memory_update" -> R.string.tool_desc_agent_memory_update
    "todo_write" -> R.string.tool_desc_todo_write
    "todo_read" -> R.string.tool_desc_todo_read
    "ask_user_question" -> R.string.tool_desc_ask_user_question
    "plugin_use" -> R.string.tool_desc_plugin_use
    "android_help" -> R.string.tool_desc_android_help
    "android_device_info" -> R.string.tool_desc_android_device_info
    "android_battery_status" -> R.string.tool_desc_android_battery_status
    "android_clipboard_read" -> R.string.tool_desc_android_clipboard_read
    "android_clipboard_write" -> R.string.tool_desc_android_clipboard_write
    "android_open_url" -> R.string.tool_desc_android_open_url
    "android_list_apps" -> R.string.tool_desc_android_list_apps
    "android_open_app" -> R.string.tool_desc_android_open_app
    "android_open_settings" -> R.string.tool_desc_android_open_settings
    "android_create_calendar_event" -> R.string.tool_desc_android_create_calendar_event
    "android_set_alarm" -> R.string.tool_desc_android_set_alarm
    "android_volume" -> R.string.tool_desc_android_volume
    "android_accessibility_status" -> R.string.tool_desc_android_accessibility_status
    "android_ui_tree" -> R.string.tool_desc_android_ui_tree
    "android_ui_click" -> R.string.tool_desc_android_ui_click
    "android_ui_set_text" -> R.string.tool_desc_android_ui_set_text
    "android_ui_scroll" -> R.string.tool_desc_android_ui_scroll
    "android_ui_tap" -> R.string.tool_desc_android_ui_tap
    "android_ui_swipe" -> R.string.tool_desc_android_ui_swipe
    "android_ui_ime_action" -> R.string.tool_desc_android_ui_ime_action
    "android_ui_paste" -> R.string.tool_desc_android_ui_paste
    "android_wait_for_idle" -> R.string.tool_desc_android_wait_for_idle
    "android_global_action" -> R.string.tool_desc_android_global_action
    "android_screenshot" -> R.string.tool_desc_android_screenshot
    "android_step" -> R.string.tool_desc_android_step
    "android_notifications" -> R.string.tool_desc_android_notifications
    "android_notification_action" -> R.string.tool_desc_android_notification_action
    "android_media_control" -> R.string.tool_desc_android_media_control
    "subagent" -> R.string.tool_desc_subagent
    "get_session_thinking_history" -> R.string.tool_desc_get_session_thinking_history
    "send_message" -> R.string.tool_desc_send_message
    "skill_list" -> R.string.tool_desc_skill_list
    "skill_view" -> R.string.tool_desc_skill_view
    "skill_read" -> R.string.tool_desc_skill_read
    "skill_get_info" -> R.string.tool_desc_skill_get_info
    "list_stickers" -> R.string.tool_desc_list_stickers
    "send_sticker" -> R.string.tool_desc_send_sticker
    "view_sticker" -> R.string.tool_desc_view_sticker
    else -> 0
}

/**
 * 工具输入参数名 → 本地化参数说明资源 id；未收录时返回 0。
 *
 * 参数名（如 path/url/max_chars）在各工具间含义一致，因此按参数名共用一份说明，
 * 新增工具只要沿用这些参数名即可自动获得多语言说明，无需为每个工具重复登记。
 */
internal fun toolParamResId(paramName: String): Int = when (paramName) {
    "path" -> R.string.tool_param_desc_path
    "url" -> R.string.tool_param_desc_url
    "query" -> R.string.tool_param_desc_query
    "command" -> R.string.tool_param_desc_command
    "content" -> R.string.tool_param_desc_content
    "old_string" -> R.string.tool_param_desc_old_string
    "new_string" -> R.string.tool_param_desc_new_string
    "old_text" -> R.string.tool_param_desc_old_text
    "new_text" -> R.string.tool_param_desc_new_text
    "pattern" -> R.string.tool_param_desc_pattern
    "glob" -> R.string.tool_param_desc_glob
    "root" -> R.string.tool_param_desc_root
    "headers" -> R.string.tool_param_desc_headers
    "timeout" -> R.string.tool_param_desc_timeout
    "timeout_ms" -> R.string.tool_param_desc_timeout_ms
    "max_chars" -> R.string.tool_param_desc_max_chars
    "max_results" -> R.string.tool_param_desc_max_results
    "limit" -> R.string.tool_param_desc_limit
    "start_line" -> R.string.tool_param_desc_start_line
    "end_line" -> R.string.tool_param_desc_end_line
    "start_index" -> R.string.tool_param_desc_start_index
    "append" -> R.string.tool_param_desc_append
    "replace_all" -> R.string.tool_param_desc_replace_all
    "case_sensitive" -> R.string.tool_param_desc_case_sensitive
    "background" -> R.string.tool_param_desc_background
    "job_id" -> R.string.tool_param_desc_job_id
    "mode" -> R.string.tool_param_desc_mode
    "action" -> R.string.tool_param_desc_action
    "action_index" -> R.string.tool_param_desc_action_index
    "index" -> R.string.tool_param_desc_index
    "text" -> R.string.tool_param_desc_text
    "target" -> R.string.tool_param_desc_target
    "field" -> R.string.tool_param_desc_field
    "size" -> R.string.tool_param_desc_size
    "n" -> R.string.tool_param_desc_n
    "prompt" -> R.string.tool_param_desc_prompt
    "question" -> R.string.tool_param_desc_question
    "topic" -> R.string.tool_param_desc_topic
    "level" -> R.string.tool_param_desc_level
    "stream" -> R.string.tool_param_desc_stream
    "direction" -> R.string.tool_param_desc_direction
    "amount" -> R.string.tool_param_desc_amount
    "selector" -> R.string.tool_param_desc_selector
    "tab_id" -> R.string.tool_param_desc_tab_id
    "script" -> R.string.tool_param_desc_script
    "wait_ms" -> R.string.tool_param_desc_wait_ms
    "full_page" -> R.string.tool_param_desc_full_page
    "analyze" -> R.string.tool_param_desc_analyze
    "max_depth" -> R.string.tool_param_desc_max_depth
    "max_nodes" -> R.string.tool_param_desc_max_nodes
    "max_interactive" -> R.string.tool_param_desc_max_interactive
    "interactive_only" -> R.string.tool_param_desc_interactive_only
    "keywords" -> R.string.tool_param_desc_keywords
    "fuzzy" -> R.string.tool_param_desc_fuzzy
    "cookies" -> R.string.tool_param_desc_cookies
    "user_agent" -> R.string.tool_param_desc_user_agent
    "reset" -> R.string.tool_param_desc_reset
    "reload" -> R.string.tool_param_desc_reload
    "viewport_width" -> R.string.tool_param_desc_viewport_width
    "viewport_height" -> R.string.tool_param_desc_viewport_height
    "item_selector" -> R.string.tool_param_desc_item_selector
    "scroll_count" -> R.string.tool_param_desc_scroll_count
    "coordinate_x" -> R.string.tool_param_desc_coordinate_x
    "coordinate_y" -> R.string.tool_param_desc_coordinate_y
    "save_path" -> R.string.tool_param_desc_save_path
    "output_path" -> R.string.tool_param_desc_output_path
    "package_name" -> R.string.tool_param_desc_package_name
    "app_name" -> R.string.tool_param_desc_app_name
    "class_name" -> R.string.tool_param_desc_class_name
    "resource_id" -> R.string.tool_param_desc_resource_id
    "content_desc" -> R.string.tool_param_desc_content_desc
    "exact" -> R.string.tool_param_desc_exact
    "fallback_gesture" -> R.string.tool_param_desc_fallback_gesture
    "min_stable_ms" -> R.string.tool_param_desc_min_stable_ms
    "include_content" -> R.string.tool_param_desc_include_content
    "notification_key" -> R.string.tool_param_desc_notification_key
    "media_action" -> R.string.tool_param_desc_media_action
    "x" -> R.string.tool_param_desc_x
    "y" -> R.string.tool_param_desc_y
    "x1" -> R.string.tool_param_desc_x1
    "y1" -> R.string.tool_param_desc_y1
    "x2" -> R.string.tool_param_desc_x2
    "y2" -> R.string.tool_param_desc_y2
    "duration_ms" -> R.string.tool_param_desc_duration_ms
    "all_day" -> R.string.tool_param_desc_all_day
    "start_time" -> R.string.tool_param_desc_start_time
    "end_time" -> R.string.tool_param_desc_end_time
    "title" -> R.string.tool_param_desc_title
    "location" -> R.string.tool_param_desc_location
    "hour" -> R.string.tool_param_desc_hour
    "minute" -> R.string.tool_param_desc_minute
    "message" -> R.string.tool_param_desc_message
    "city" -> R.string.tool_param_desc_city
    "days" -> R.string.tool_param_desc_days
    "timezone" -> R.string.tool_param_desc_timezone
    "image_url" -> R.string.tool_param_desc_image_url
    "skill_name" -> R.string.tool_param_desc_skill_name
    "file_path" -> R.string.tool_param_desc_file_path
    "id" -> R.string.tool_param_desc_id
    "header" -> R.string.tool_param_desc_header
    "label" -> R.string.tool_param_desc_label
    "description" -> R.string.tool_param_desc_description
    "status" -> R.string.tool_param_desc_status
    "priority" -> R.string.tool_param_desc_priority
    "multi_select" -> R.string.tool_param_desc_multi_select
    "options" -> R.string.tool_param_desc_options
    "questions" -> R.string.tool_param_desc_questions
    "todos" -> R.string.tool_param_desc_todos
    "args" -> R.string.tool_param_desc_args
    "plugin_id" -> R.string.tool_param_desc_plugin_id
    "manifest_json" -> R.string.tool_param_desc_manifest_json
    "main_js" -> R.string.tool_param_desc_main_js
    "extra_files_json" -> R.string.tool_param_desc_extra_files_json
    "files" -> R.string.tool_param_desc_files
    "actions" -> R.string.tool_param_desc_actions
    "active" -> R.string.tool_param_desc_active
    "aliases" -> R.string.tool_param_desc_aliases
    "alternate_greetings" -> R.string.tool_param_desc_alternate_greetings
    "api_key" -> R.string.tool_param_desc_api_key
    "append_base_url_path" -> R.string.tool_param_desc_append_base_url_path
    "base_url" -> R.string.tool_param_desc_base_url
    "basic_info" -> R.string.tool_param_desc_basic_info
    "book_id" -> R.string.tool_param_desc_book_id
    "character_id" -> R.string.tool_param_desc_character_id
    "comment" -> R.string.tool_param_desc_comment
    "condition_logic" -> R.string.tool_param_desc_condition_logic
    "conditions" -> R.string.tool_param_desc_conditions
    "config" -> R.string.tool_param_desc_config
    "constant" -> R.string.tool_param_desc_constant
    "conversation_id" -> R.string.tool_param_desc_conversation_id
    "display_index" -> R.string.tool_param_desc_display_index
    "enabled" -> R.string.tool_param_desc_enabled
    "entry_id" -> R.string.tool_param_desc_entry_id
    "entry_type" -> R.string.tool_param_desc_entry_type
    "event" -> R.string.tool_param_desc_event
    "example_dialogues" -> R.string.tool_param_desc_example_dialogues
    "first_message" -> R.string.tool_param_desc_first_message
    "greeting" -> R.string.tool_param_desc_greeting
    "hook_id" -> R.string.tool_param_desc_hook_id
    "input_price" -> R.string.tool_param_desc_input_price
    "insertion_order" -> R.string.tool_param_desc_insertion_order
    "keys" -> R.string.tool_param_desc_keys
    "match_mode" -> R.string.tool_param_desc_match_mode
    "max_retries" -> R.string.tool_param_desc_max_retries
    "max_tokens" -> R.string.tool_param_desc_max_tokens
    "memory_id" -> R.string.tool_param_desc_memory_id
    "model" -> R.string.tool_param_desc_model
    "model_id" -> R.string.tool_param_desc_model_id
    "name" -> R.string.tool_param_desc_name
    "output_price" -> R.string.tool_param_desc_output_price
    "parameters" -> R.string.tool_param_desc_parameters
    "permissions" -> R.string.tool_param_desc_permissions
    "personality" -> R.string.tool_param_desc_personality
    "position" -> R.string.tool_param_desc_position
    "protocol" -> R.string.tool_param_desc_protocol
    "provider" -> R.string.tool_param_desc_provider
    "proxy_url" -> R.string.tool_param_desc_proxy_url
    "purpose" -> R.string.tool_param_desc_purpose
    "reference_md" -> R.string.tool_param_desc_reference_md
    "response_format" -> R.string.tool_param_desc_response_format
    "rules" -> R.string.tool_param_desc_rules
    "scenario" -> R.string.tool_param_desc_scenario
    "scope" -> R.string.tool_param_desc_scope
    "selective" -> R.string.tool_param_desc_selective
    "session_id" -> R.string.tool_param_desc_session_id
    "skill_id" -> R.string.tool_param_desc_skill_id
    "skill_md" -> R.string.tool_param_desc_skill_md
    "state_triggers" -> R.string.tool_param_desc_state_triggers
    "summary" -> R.string.tool_param_desc_summary
    "supports_stream" -> R.string.tool_param_desc_supports_stream
    "supports_vision" -> R.string.tool_param_desc_supports_vision
    "system_prompt" -> R.string.tool_param_desc_system_prompt
    "tags" -> R.string.tool_param_desc_tags
    "target_session_id" -> R.string.tool_param_desc_target_session_id
    "task_id" -> R.string.tool_param_desc_task_id
    "temperature" -> R.string.tool_param_desc_temperature
    "token_limit_daily" -> R.string.tool_param_desc_token_limit_daily
    "token_limit_weekly" -> R.string.tool_param_desc_token_limit_weekly
    "top_p" -> R.string.tool_param_desc_top_p
    "trigger" -> R.string.tool_param_desc_trigger
    "trigger_mode" -> R.string.tool_param_desc_trigger_mode
    "trigger_sources" -> R.string.tool_param_desc_trigger_sources
    "type" -> R.string.tool_param_desc_type
    "user_id" -> R.string.tool_param_desc_user_id
    "workflow_id" -> R.string.tool_param_desc_workflow_id
    else -> 0
}

/** 工具输入参数条目：参数名 + 是否必填。 */
internal data class ToolParameterEntry(val name: String, val required: Boolean)

/**
 * 本地可执行工具的全部 function-calling 定义，与 Agent 注入给模型的口径一致。
 *
 * 覆盖内置工具、Agent 工具、Skill 工具与数据库工具；新增本地工具只要并入这里
 * 就能在「工具详情 / 进度卡片步骤详情」中展示说明与输入参数。
 */
internal fun allLocalToolDefinitions(): List<Map<String, Any>> =
    buildLocalAgentToolDefinitions() + buildLocalSkillToolDefinitions() + buildLocalDbToolDefinitions()

/** 从一份 function-calling 定义中取出 function 节点。 */
private fun functionNode(definition: Map<String, Any>): Map<String, Any>? {
    @Suppress("UNCHECKED_CAST")
    return definition["function"] as? Map<String, Any>
}

/** 本地工具定义的 function 名称。 */
internal fun localToolDefinitionName(definition: Map<String, Any>): String? =
    functionNode(definition)?.get("name")?.toString()

/**
 * 本地工具定义中的说明文本（与模型看到的描述一致，含动态注入的当前上限）。
 */
internal fun localToolDefinitionDescription(name: String): String? =
    allLocalToolDefinitions()
        .firstOrNull { localToolDefinitionName(it) == name }
        ?.let { functionNode(it)?.get("description")?.toString() }
        ?.takeIf { it.isNotBlank() }

/**
 * 解析工具的输入参数 Schema，按声明顺序返回参数名与必填状态。
 *
 * 数据来源与 Agent 实际注入给模型的定义一致（见 [allLocalToolDefinitions]）；
 * 非本地定义的动态工具（MCP 等）返回空列表。
 */
internal fun builtinToolParameterEntries(toolId: String): List<ToolParameterEntry> {
    val parametersJson = BuiltinTools.all.firstOrNull { it.id == toolId }?.parametersJson
    val schema: Map<*, *> = if (parametersJson != null) {
        runCatching {
            com.google.gson.Gson().fromJson(parametersJson, Map::class.java) as Map<*, *>
        }.getOrNull() ?: return emptyList()
    } else {
        allLocalToolDefinitions()
            .firstOrNull { localToolDefinitionName(it) == toolId }
            ?.let { functionNode(it)?.get("parameters") as? Map<*, *> }
            ?: return emptyList()
    }
    val properties = schema["properties"] as? Map<*, *> ?: return emptyList()
    val required = (schema["required"] as? List<*>)
        .orEmpty()
        .mapNotNull { it?.toString() }
        .toSet()
    return properties.keys.mapNotNull { key ->
        key?.toString()?.let { ToolParameterEntry(it, it in required) }
    }
}

/**
 * 工具输入参数列表：参数名 + 多语言参数说明 + 必填标记，无参数时显示占位文案。
 *
 * 进度卡片步骤详情弹窗与工具详情弹窗共用，保证两处口径一致。
 */
@Composable
internal fun ToolParameterList(toolId: String) {
    val params = remember(toolId) { builtinToolParameterEntries(toolId) }
    if (params.isEmpty()) {
        Text(
            text = stringResource(R.string.chat_step_tool_params_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    params.forEach { entry ->
        val resId = remember(entry.name) { toolParamResId(entry.name) }
        val description = if (resId != 0) stringResource(resId) else null
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.7f)
                )
            }
            if (entry.required) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.tool_param_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
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
