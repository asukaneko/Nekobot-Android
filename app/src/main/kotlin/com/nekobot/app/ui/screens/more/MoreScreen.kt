package com.nekobot.app.ui.screens.more

import androidx.compose.ui.platform.LocalResources

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.navigation.rememberLiquidGlassBottomBarClearance
import com.nekobot.app.ui.theme.accentSecondary
import com.nekobot.app.ui.theme.accentSuccess
import com.nekobot.app.ui.theme.accentTertiary
import com.nekobot.app.ui.theme.accentWarning

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
    val navigateOnce = rememberMoreNavigateOnce(onNavigate)
    val listState = rememberLazyListState()
    val textResources = LocalResources.current
    // 订阅全局 appMode Flow，模式切换时自动重组
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val bottomBarClearance = rememberLiquidGlassBottomBarClearance()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
            state = listState,
            modifier = Modifier
                .testTag("more-list")
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = bottomBarClearance + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "appearance") {
                // -------------------- 外观 --------------------
                MoreGroupCard(
                    groupTitle = stringResource(R.string.more_group_appearance)
                ) {
                MoreRow(
                    icon = Icons.Filled.Palette,
                    tint = accentWarning(),
                    title = stringResource(R.string.more_style_settings),
                    desc = stringResource(R.string.more_style_settings_desc),
                    onClick = { navigateOnce("style_settings") }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                MoreRow(
                    icon = Icons.Filled.Landscape,
                    tint = MaterialTheme.colorScheme.primary,
                    title = textResources.getString(R.string.single_role_relationship_page_skins),
                    desc = textResources.getString(R.string.single_role_seven_sets_of_backgrounds_buttons_and_icons_independent_of_theme),
                    onClick = { navigateOnce(Routes.RELATIONSHIP_BACKGROUND) }
                )
            }

            }
            item(key = "ai") {
                // -------------------- AI 能力 --------------------
                MoreGroupCard(
                    groupTitle = stringResource(R.string.more_group_ai)
                ) {
                MoreRow(
                    icon = Icons.Filled.AutoAwesome,
                    tint = accentTertiary(),
                    title = stringResource(R.string.more_ai_config_center),
                    desc = if (appMode == AppMode.LOCAL) stringResource(R.string.more_ai_config_desc_local) else stringResource(R.string.more_ai_config_desc_server),
                    onClick = { navigateOnce("ai_config_center") }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                MoreRow(
                    icon = Icons.Filled.Psychology,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.more_role_memory),
                    desc = stringResource(R.string.more_role_memory_desc),
                    onClick = { navigateOnce("memory") }
                )
                if (appMode == AppMode.LOCAL) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                    MoreRow(
                        icon = Icons.Outlined.Book,
                        tint = accentTertiary(),
                        title = stringResource(R.string.agent_character_memory_title),
                        desc = stringResource(R.string.agent_character_memory_desc),
                        onClick = { navigateOnce(Routes.AGENT_CHARACTER_MEMORY) }
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                MoreRow(
                    icon = Icons.Filled.Bookmarks,
                    tint = accentSecondary(),
                    title = stringResource(R.string.more_global_agent_memory),
                    desc = stringResource(R.string.more_global_agent_memory_desc),
                    onClick = { navigateOnce("agent_memory") }
                )
            }

            }
            item(key = "features") {
                // -------------------- 功能 --------------------
                MoreGroupCard(
                    groupTitle = stringResource(R.string.more_group_features)
                ) {
                MoreRow(
                    icon = Icons.Filled.Search,
                    tint = accentSecondary(),
                    title = stringResource(R.string.global_search_title),
                    desc = stringResource(R.string.more_global_search_desc),
                    onClick = { navigateOnce("global_search") }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                MoreRow(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    tint = accentSuccess(),
                    title = stringResource(R.string.more_state_history),
                    desc = stringResource(R.string.more_state_history_desc),
                    onClick = { navigateOnce("state_history") }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                // 自定义表情包：导入图片后聊天中 `[名称]` 直接显示原图，AI 也能发送
                MoreRow(
                    icon = Icons.Filled.EmojiEmotions,
                    tint = accentWarning(),
                    title = stringResource(R.string.more_stickers),
                    desc = stringResource(R.string.more_stickers_desc),
                    onClick = { navigateOnce("stickers") }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                MoreRow(
                    icon = Icons.Filled.Extension,
                    tint = accentTertiary(),
                    title = stringResource(R.string.more_extensions),
                    desc = if (appMode != AppMode.LOCAL) stringResource(R.string.more_extensions_desc_server) else stringResource(R.string.more_extensions_desc_local),
                    onClick = { navigateOnce("extensions") }
                )
            }

            }
            item(key = "system") {
                // -------------------- 系统 --------------------
                MoreGroupCard(
                    groupTitle = stringResource(R.string.more_group_system)
                ) {
                MoreRow(
                    icon = Icons.Filled.Security,
                    tint = accentSecondary(),
                    title = stringResource(R.string.more_system_operations),
                    desc = stringResource(R.string.more_system_operations_desc),
                    onClick = { navigateOnce("system_operations") }
                )
                if (appMode == AppMode.LOCAL) {
                    // 本地模式：DB Profile 管理（从远程导入 nbotcfg / 切换 / 删除）
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                    MoreRow(
                        icon = Icons.Filled.Storage,
                        tint = accentTertiary(),
                        title = stringResource(R.string.more_db_management),
                        desc = stringResource(R.string.more_db_management_desc),
                        onClick = { navigateOnce("db_profile") }
                    )
                }
            }

            }
            // 设置置于底部
            item(key = "settings") {
                MoreGroupCard {
                MoreRow(
                    icon = Icons.Filled.Settings,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.more_settings),
                    desc = stringResource(R.string.more_settings_desc),
                    onClick = { navigateOnce("settings") }
                )
            }

            }
            // 退出登录（仅服务器模式，本地模式无需登录）
            if (appMode != AppMode.LOCAL) {
                item(key = "logout") {
                    MoreGroupCard {
                    MoreRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        tint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.more_logout),
                        desc = stringResource(R.string.more_current_user, ServiceContainer.prefs.username.ifBlank { stringResource(R.string.common_not_logged_in) }),
                        onClick = onLogout
                    )
                    }
                }
            }
        }
    }
}
}

/** 分组卡片：顶部小标题 + 组内入口。 */
@Composable
private fun MoreGroupCard(
    groupTitle: String? = null,
    content: @Composable () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (groupTitle != null) {
            Text(
                text = groupTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
            )
        }
        content()
    }
}

/** 更多页单行：彩色图标底座 + 标题 + 描述，可点击。 */
@Composable
private fun MoreRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    MoreRow(title = title, desc = desc, onClick = onClick, leadingIcon = {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    })
}

/** 自定义彩色图标与普通单色图标共用同一行布局和点击区域。 */
@Composable
private fun MoreRow(
    title: String,
    desc: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
