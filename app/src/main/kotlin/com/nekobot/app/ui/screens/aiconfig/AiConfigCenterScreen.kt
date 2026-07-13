package com.nekobot.app.ui.screens.aiconfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.SectionHeader

/**
 * AI 配置中心：合并入口页面。
 *
 * 服务器模式下展示「AI 配置」「AI 模型」「API Key 管理」三个入口；
 * 本地模式下展示「本地 AI 模型」入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigCenterScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val isLocalMode = ServiceContainer.prefs.isLocalMode

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 配置中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLocalMode) {
                    // 本地模式：仅展示本地 AI 模型入口
                    SectionHeader(title = "本地 AI")
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.Memory,
                            title = "本地 AI 模型",
                            subtitle = "管理本地推理模型与参数"
                        ) { onNavigate("local_ai_models") }
                    }
                } else {
                    // 服务器模式：AI 配置 / AI 模型 / API Key 管理
                    SectionHeader(title = "模型与配置")
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.AutoAwesome,
                            title = "AI 配置",
                            subtitle = "配置模型用途与生成参数"
                        ) { onNavigate("ai_config") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.Memory,
                            title = "AI 模型",
                            subtitle = "管理可用的 AI 模型列表"
                        ) { onNavigate("ai_models") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.SwapVert,
                            title = "故障转移队列",
                            subtitle = "查看模型健康状态并调整回退顺序"
                        ) { onNavigate("ai_failover") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.Key,
                            title = "API Key 管理",
                            subtitle = "管理各模型的 API 密钥"
                        ) { onNavigate("api_keys") }
                    }
                }
            }
        }
    }
}

/** 配置中心入口行：图标 + 标题 + 副标题 + 右箭头，整行可点击。 */
@Composable
private fun ConfigCenterEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
