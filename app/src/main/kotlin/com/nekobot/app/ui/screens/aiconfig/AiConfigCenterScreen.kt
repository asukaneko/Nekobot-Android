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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
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
                title = { Text(stringResource(R.string.aiconfig_center_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
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
                    // 本地模式：本地 AI 模型 + AI 配置 + 故障转移队列 + API Key 管理（均基于本地数据）
                    SectionHeader(title = stringResource(R.string.aiconfig_section_local_ai))
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.Memory,
                            title = stringResource(R.string.aiconfig_local_ai_models),
                            subtitle = stringResource(R.string.aiconfig_local_ai_models_subtitle)
                        ) { onNavigate("local_ai_models") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.AutoAwesome,
                            title = stringResource(R.string.aiconfig_title),
                            subtitle = stringResource(R.string.aiconfig_sync_active_params_subtitle)
                        ) { onNavigate("ai_config") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.SwapVert,
                            title = stringResource(R.string.failover_queue_title),
                            subtitle = stringResource(R.string.failover_local_priority_subtitle)
                        ) { onNavigate("ai_failover") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.Key,
                            title = stringResource(R.string.aiconfig_api_key_management),
                            subtitle = stringResource(R.string.aiconfig_manage_local_keys_subtitle)
                        ) { onNavigate("api_keys") }
                    }
                } else {
                    // 服务器模式：AI 配置 / AI 模型 / API Key 管理
                    SectionHeader(title = stringResource(R.string.aiconfig_section_models_config))
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.AutoAwesome,
                            title = stringResource(R.string.aiconfig_title),
                            subtitle = stringResource(R.string.aiconfig_configure_params_subtitle)
                        ) { onNavigate("ai_config") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.Memory,
                            title = stringResource(R.string.aimodels_title),
                            subtitle = stringResource(R.string.aimodels_manage_list_subtitle)
                        ) { onNavigate("ai_models") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.SwapVert,
                            title = stringResource(R.string.failover_queue_title),
                            subtitle = stringResource(R.string.failover_health_subtitle)
                        ) { onNavigate("ai_failover") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        ConfigCenterEntry(
                            icon = Icons.Filled.Key,
                            title = stringResource(R.string.aiconfig_api_key_management),
                            subtitle = stringResource(R.string.aiconfig_manage_keys_subtitle)
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
