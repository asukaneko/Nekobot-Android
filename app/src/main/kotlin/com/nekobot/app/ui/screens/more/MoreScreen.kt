package com.nekobot.app.ui.screens.more

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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.OnPrimary
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary

/**
 * 「更多」页：放置其他功能入口（状态历程、AI 配置/模型、设置、退出登录等）。
 * 设置入口置于底部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更多", color = OnSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgDark,
                    titleContentColor = OnSurface
                )
            )
        },
        containerColor = BgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 功能区
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                MoreRow(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    title = "状态历程",
                    desc = "查看角色状态随对话推进的变化",
                    onClick = { onNavigate("state_history") }
                )
                Spacer(Modifier.height(8.dp))
                MoreRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = "AI 配置",
                    desc = "温度、最大 token 等参数",
                    onClick = { onNavigate("ai_config") }
                )
                Spacer(Modifier.height(8.dp))
                MoreRow(
                    icon = Icons.Filled.Memory,
                    title = "AI 模型",
                    desc = "管理可用的 AI 模型与供应商",
                    onClick = { onNavigate("ai_models") }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 设置置于底部
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                MoreRow(
                    icon = Icons.Filled.Settings,
                    title = "设置",
                    desc = "服务器、系统配置、日志、账号",
                    onClick = { onNavigate("settings") }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 退出登录
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
                            .background(ErrorRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = OnPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "退出登录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ErrorRed,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "当前：${ServiceContainer.prefs.username.ifBlank { "未登录" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant
                        )
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
                .background(BgSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
        }
    }
}
