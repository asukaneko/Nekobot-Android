package com.nekobot.app.ui.screens.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AppRegistration
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Key
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.navigation.Routes

/**
 * 扩展功能聚合页：12 个高级配置模块入口。
 * 本地模式仅展示本地可用模块（Hook/任务/工作流/Skills/Tools/MCP/API Keys）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val isLocalMode = com.nekobot.app.ServiceContainer.prefs.isLocalMode
    val allItems = listOf(
        ExtensionItem(stringResource(R.string.extensions_achievements), stringResource(R.string.extensions_achievements_desc), Icons.Filled.EmojiEvents, Routes.ACHIEVEMENTS),
        ExtensionItem(stringResource(R.string.extensions_hook_manage), stringResource(R.string.extensions_hook_desc), Icons.Filled.Extension, Routes.HOOKS),
        ExtensionItem(stringResource(R.string.extensions_task_center), stringResource(R.string.extensions_task_center_desc), Icons.Filled.Schedule, Routes.TASK_CENTER),
        ExtensionItem(stringResource(R.string.extensions_workflows), stringResource(R.string.extensions_workflows_desc), Icons.Filled.AppRegistration, Routes.WORKFLOWS),
        ExtensionItem(stringResource(R.string.extensions_knowledge), stringResource(R.string.extensions_knowledge_desc), Icons.Filled.MenuBook, Routes.KNOWLEDGE),
        ExtensionItem(stringResource(R.string.extensions_skills), stringResource(R.string.extensions_skills_desc), Icons.Filled.Build, Routes.SKILLS),
        ExtensionItem(stringResource(R.string.extensions_tools), stringResource(R.string.extensions_tools_desc), Icons.Filled.Storage, Routes.TOOLS),
        ExtensionItem(stringResource(R.string.extensions_mcp), stringResource(R.string.extensions_mcp_desc), Icons.Filled.Hub, Routes.MCP_SERVERS),
        ExtensionItem(stringResource(R.string.extensions_channels), stringResource(R.string.extensions_channels_desc), Icons.Filled.Campaign, Routes.CHANNELS),
        ExtensionItem(stringResource(R.string.extensions_message_filter), stringResource(R.string.extensions_message_filter_desc), Icons.Filled.FilterAlt, Routes.MESSAGE_FILTER),
        ExtensionItem(stringResource(R.string.extensions_tts_playground), stringResource(R.string.extensions_tts_playground_desc), Icons.Filled.PlayCircle, Routes.TTS_PLAYGROUND),
        ExtensionItem(stringResource(R.string.extensions_image_generation), stringResource(R.string.extensions_image_generation_desc), Icons.Filled.Image, Routes.IMAGE_GENERATION_PLAYGROUND),
        ExtensionItem(stringResource(R.string.extensions_login_tokens), stringResource(R.string.extensions_login_tokens_desc), Icons.Filled.VpnKey, Routes.LOGIN_TOKENS),
        ExtensionItem(stringResource(R.string.extensions_api_keys), stringResource(R.string.extensions_api_keys_desc), Icons.Filled.Key, Routes.API_KEYS)
    )
    // 本地模式仅展示本地已实现的模块（含 TTS/图片生成实验室）
    val localSupportedRoutes = setOf(
        Routes.ACHIEVEMENTS, Routes.HOOKS, Routes.TASK_CENTER, Routes.WORKFLOWS,
        Routes.SKILLS, Routes.TOOLS, Routes.MCP_SERVERS, Routes.API_KEYS,
        Routes.TTS_PLAYGROUND, Routes.IMAGE_GENERATION_PLAYGROUND
    )
    val items = if (isLocalMode) allItems.filter { it.route in localSupportedRoutes } else allItems

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.route }) { item ->
                ExtensionCard(item = item, onClick = { onNavigate(item.route) })
            }
        }
    }
}

private data class ExtensionItem(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val route: String
)

@Composable
private fun ExtensionCard(item: ExtensionItem, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(item.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
