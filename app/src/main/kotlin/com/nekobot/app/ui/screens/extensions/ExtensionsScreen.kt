package com.nekobot.app.ui.screens.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AppRegistration
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.theme.accentSecondary
import com.nekobot.app.ui.theme.accentSuccess
import com.nekobot.app.ui.theme.accentTertiary
import com.nekobot.app.ui.theme.accentWarning

/**
 * 扩展功能聚合页：14 个高级配置模块入口，按「自动化 / AI 扩展 / 消息 / 实验室 / 成长与安全」分组。
 * 本地模式仅展示本地可用模块（Hook/任务/工作流/Skills/Tools/MCP/API Keys）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val isLocalMode = com.nekobot.app.ServiceContainer.prefs.isLocalMode
    val allGroups = buildExtensionGroups()
    // 本地模式仅展示本地已实现的模块（含 TTS/图片生成实验室）
    val localSupportedRoutes = setOf(
        Routes.ACHIEVEMENTS, Routes.HOOKS, Routes.TASK_CENTER, Routes.WORKFLOWS,
        Routes.KNOWLEDGE, Routes.SKILLS, Routes.TOOLS, Routes.MCP_SERVERS,
        Routes.TTS_PLAYGROUND, Routes.IMAGE_GENERATION_PLAYGROUND, Routes.PLUGINS
    )
    val groups = if (isLocalMode) {
        allGroups.mapNotNull { group ->
            val filtered = group.items.filter { it.route in localSupportedRoutes }
            if (filtered.isEmpty()) null else group.copy(items = filtered)
        }
    } else {
        allGroups
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extensions_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
            groups.forEach { group ->
                ExtensionGroupCard(groupTitle = group.title) {
                    group.items.forEachIndexed { index, item ->
                        if (index > 0) Spacer(Modifier.height(4.dp))
                        ExtensionRow(item = item, onClick = { onNavigate(item.route) })
                    }
                }
            }
        }
    }
}

private data class ExtensionItem(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val tint: Color,
    val route: String
)

private data class ExtensionGroup(
    val title: String,
    val items: List<ExtensionItem>
)

/** 构建扩展功能分组：自动化 / AI 扩展 / 消息 / 实验室 / 成长与安全。 */
@Composable
private fun buildExtensionGroups(): List<ExtensionGroup> = listOf(
    ExtensionGroup(
        title = stringResource(R.string.extensions_group_automation),
        items = listOf(
            ExtensionItem(
                stringResource(R.string.extensions_hook_manage), stringResource(R.string.extensions_hook_desc),
                Icons.Filled.Extension, accentTertiary(), Routes.HOOKS
            ),
            ExtensionItem(
                stringResource(R.string.extensions_task_center), stringResource(R.string.extensions_task_center_desc),
                Icons.Filled.Schedule, accentSecondary(), Routes.TASK_CENTER
            ),
            ExtensionItem(
                stringResource(R.string.extensions_workflows), stringResource(R.string.extensions_workflows_desc),
                Icons.Filled.AppRegistration, accentSuccess(), Routes.WORKFLOWS
            ),
            ExtensionItem(
                stringResource(R.string.extensions_plugins), stringResource(R.string.extensions_plugins_desc),
                Icons.Filled.Extension, accentWarning(), Routes.PLUGINS
            )
        )
    ),
    ExtensionGroup(
        title = stringResource(R.string.extensions_group_ai),
        items = listOf(
            ExtensionItem(
                stringResource(R.string.extensions_knowledge), stringResource(R.string.extensions_knowledge_desc),
                Icons.Filled.MenuBook, accentSuccess(), Routes.KNOWLEDGE
            ),
            ExtensionItem(
                stringResource(R.string.extensions_skills), stringResource(R.string.extensions_skills_desc),
                Icons.Filled.Build, accentSecondary(), Routes.SKILLS
            ),
            ExtensionItem(
                stringResource(R.string.extensions_tools), stringResource(R.string.extensions_tools_desc),
                Icons.Filled.Storage, MaterialTheme.colorScheme.primary, Routes.TOOLS
            ),
            ExtensionItem(
                stringResource(R.string.extensions_mcp), stringResource(R.string.extensions_mcp_desc),
                Icons.Filled.Hub, accentTertiary(), Routes.MCP_SERVERS
            )
        )
    ),
    ExtensionGroup(
        title = stringResource(R.string.extensions_group_messaging),
        items = listOf(
            ExtensionItem(
                stringResource(R.string.extensions_channels), stringResource(R.string.extensions_channels_desc),
                Icons.Filled.Campaign, accentSecondary(), Routes.CHANNELS
            ),
            ExtensionItem(
                stringResource(R.string.extensions_message_filter), stringResource(R.string.extensions_message_filter_desc),
                Icons.Filled.FilterAlt, accentWarning(), Routes.MESSAGE_FILTER
            )
        )
    ),
    ExtensionGroup(
        title = stringResource(R.string.extensions_group_labs),
        items = listOf(
            ExtensionItem(
                stringResource(R.string.extensions_tts_playground), stringResource(R.string.extensions_tts_playground_desc),
                Icons.Filled.PlayCircle, MaterialTheme.colorScheme.primary, Routes.TTS_PLAYGROUND
            ),
            ExtensionItem(
                stringResource(R.string.extensions_image_generation), stringResource(R.string.extensions_image_generation_desc),
                Icons.Filled.Image, accentSuccess(), Routes.IMAGE_GENERATION_PLAYGROUND
            )
        )
    ),
    ExtensionGroup(
        title = stringResource(R.string.extensions_group_growth),
        items = listOf(
            ExtensionItem(
                stringResource(R.string.extensions_achievements), stringResource(R.string.extensions_achievements_desc),
                Icons.Filled.EmojiEvents, accentWarning(), Routes.ACHIEVEMENTS
            ),
            ExtensionItem(
                stringResource(R.string.extensions_login_tokens), stringResource(R.string.extensions_login_tokens_desc),
                Icons.Filled.VpnKey, accentSecondary(), Routes.LOGIN_TOKENS
            )
        )
    )
)

/** 分组卡片：顶部小标题 + 组内入口列表。 */
@Composable
private fun ExtensionGroupCard(
    groupTitle: String,
    content: @Composable () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = groupTitle,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
        )
        content()
    }
}

/** 扩展功能单行：彩色图标底座 + 标题 + 描述，可点击。 */
@Composable
private fun ExtensionRow(item: ExtensionItem, onClick: () -> Unit) {
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
                .background(item.tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(item.icon, contentDescription = null, tint = item.tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(item.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
