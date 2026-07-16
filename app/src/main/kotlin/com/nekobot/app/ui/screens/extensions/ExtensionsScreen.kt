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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        ExtensionItem("Hook 管理", "事件钩子与动作自动化", Icons.Filled.Extension, Routes.HOOKS),
        ExtensionItem("任务中心", "定时/周期任务聚合管理", Icons.Filled.Schedule, Routes.TASK_CENTER),
        ExtensionItem("工作流", "多步骤自动化流程", Icons.Filled.AppRegistration, Routes.WORKFLOWS),
        ExtensionItem("知识库", "RAG 文档与向量检索", Icons.Filled.MenuBook, Routes.KNOWLEDGE),
        ExtensionItem("Skills 配置", "技能元数据管理", Icons.Filled.Build, Routes.SKILLS),
        ExtensionItem("Tools 配置", "工具函数与实现", Icons.Filled.Storage, Routes.TOOLS),
        ExtensionItem("MCP 服务", "Model Context Protocol 服务器", Icons.Filled.Hub, Routes.MCP_SERVERS),
        ExtensionItem("频道管理", "Telegram/飞书/QQ/Web 频道", Icons.Filled.Campaign, Routes.CHANNELS),
        ExtensionItem("消息过滤", "关键词/正则过滤规则", Icons.Filled.FilterAlt, Routes.MESSAGE_FILTER),
        ExtensionItem("TTS 试验场", "语音合成预览与音色管理", Icons.Filled.PlayCircle, Routes.TTS_PLAYGROUND),
        ExtensionItem("图片生成", "本地图片生成实验室", Icons.Filled.Image, Routes.IMAGE_GENERATION_PLAYGROUND),
        ExtensionItem("登录令牌", "Web 控制台访问令牌", Icons.Filled.VpnKey, Routes.LOGIN_TOKENS),
        ExtensionItem("API Keys", "外部 API 密钥管理", Icons.Filled.Key, Routes.API_KEYS)
    )
    // 本地模式仅展示本地已实现的模块（含 TTS/图片生成实验室）
    val localSupportedRoutes = setOf(
        Routes.HOOKS, Routes.TASK_CENTER, Routes.WORKFLOWS,
        Routes.SKILLS, Routes.TOOLS, Routes.MCP_SERVERS, Routes.API_KEYS,
        Routes.TTS_PLAYGROUND, Routes.IMAGE_GENERATION_PLAYGROUND
    )
    val items = if (isLocalMode) allItems.filter { it.route in localSupportedRoutes } else allItems

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("扩展功能", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
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
