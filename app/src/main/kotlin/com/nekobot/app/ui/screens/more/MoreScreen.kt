package com.nekobot.app.ui.screens.more

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.components.GlassCard

/**
 * 「更多」页：放置其他功能入口（角色记忆、状态历程、AI 配置/模型、设置、退出登录等）。
 * 设置入口置于底部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit
) {
    // 订阅全局 appMode Flow，模式切换时自动重组
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
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
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 功能区
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                MoreRow(
                    icon = Icons.Filled.Search,
                    title = "全局搜索与命令",
                    desc = "搜索会话、消息、角色、世界书、记忆、文件和功能入口",
                    onClick = { onNavigate("global_search") }
                )
                Spacer(Modifier.height(8.dp))
                MoreRow(
                    icon = Icons.Filled.Psychology,
                    title = stringResource(R.string.more_role_memory),
                    desc = stringResource(R.string.more_role_memory_desc),
                    onClick = { onNavigate("memory") }
                )
                Spacer(Modifier.height(8.dp))
                MoreRow(
                    icon = Icons.Filled.Palette,
                    title = stringResource(R.string.more_style_settings),
                    desc = stringResource(R.string.more_style_settings_desc),
                    onClick = { onNavigate("style_settings") }
                )
                Spacer(Modifier.height(8.dp))
                MoreRow(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    title = stringResource(R.string.more_state_history),
                    desc = stringResource(R.string.more_state_history_desc),
                    onClick = { onNavigate("state_history") }
                )
                Spacer(Modifier.height(8.dp))
                MoreRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.more_ai_config_center),
                    desc = if (appMode == AppMode.LOCAL) stringResource(R.string.more_ai_config_desc_local) else stringResource(R.string.more_ai_config_desc_server),
                    onClick = { onNavigate("ai_config_center") }
                )
                if (appMode == AppMode.LOCAL) {
                    // 本地模式：DB Profile 管理（从远程导入 nbotcfg / 切换 / 删除）
                    Spacer(Modifier.height(8.dp))
                    MoreRow(
                        icon = Icons.Filled.Storage,
                        title = stringResource(R.string.more_db_management),
                        desc = stringResource(R.string.more_db_management_desc),
                        onClick = { onNavigate("db_profile") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                MoreRow(
                    icon = Icons.Filled.Security,
                    title = "Agent 系统操作",
                    desc = "辅助功能、通知读取、截图和媒体控制授权",
                    onClick = { onNavigate("system_operations") }
                )
                if (appMode != AppMode.LOCAL) {
                    // 扩展功能（仅远程模式）：Hook/任务/工作流/知识库/Skills/Tools/MCP/频道/消息过滤/TTS/令牌
                    Spacer(Modifier.height(8.dp))
                    MoreRow(
                        icon = Icons.Filled.Extension,
                        title = stringResource(R.string.more_extensions),
                        desc = stringResource(R.string.more_extensions_desc_server),
                        onClick = { onNavigate("extensions") }
                    )
                } else {
                    // 本地模式：开放本地可用的扩展功能（Hook/任务/工作流/Skills/Tools/MCP/API Keys）
                    Spacer(Modifier.height(8.dp))
                    MoreRow(
                        icon = Icons.Filled.Extension,
                        title = stringResource(R.string.more_extensions),
                        desc = stringResource(R.string.more_extensions_desc_local),
                        onClick = { onNavigate("extensions") }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 设置置于底部
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                MoreRow(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.more_settings),
                    desc = stringResource(R.string.more_settings_desc),
                    onClick = { onNavigate("settings") }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 退出登录（仅服务器模式，本地模式无需登录）
            if (appMode != AppMode.LOCAL) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onLogout)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.more_logout),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.more_current_user, ServiceContainer.prefs.username.ifBlank { stringResource(R.string.common_not_logged_in) }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 更多页单行：图标 + 标题 + 描述，可点击。 */
@Composable
private fun MoreRow(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
