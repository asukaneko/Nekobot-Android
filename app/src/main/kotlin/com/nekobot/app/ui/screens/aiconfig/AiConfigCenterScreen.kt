package com.nekobot.app.ui.screens.aiconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.components.GlassCard
import kotlinx.coroutines.launch

/**
 * AI 配置中心入口状态：用于在入口右侧展示当前配置概览。
 * 仅在本地模式下从本地数据库加载；服务器模式留空。
 */
private data class AiConfigStatus(
    val modelCount: Int = 0,
    val connectedAccountCount: Int = 0,
    val apiKeyCount: Int = 0,
    val enabledModelCount: Int = 0,
    val activeModelName: String? = null,
)

/**
 * AI 配置中心：合并入口页面。
 *
 * 服务器模式下展示「生成参数」「AI 模型」「模型优先级」「API 密钥」入口；
 * 本地模式下展示「本地模型」「模型账户」「生成参数」「模型优先级」入口。
 *
 * 入口采用分组列表样式：主配置一组、API 密钥单独归入「安全与高级」分组，
 * 每行高度压缩到 80dp，左侧带粉色淡背景图标，右侧展示当前状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigCenterScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val isLocalMode = ServiceContainer.prefs.isLocalMode
    var status by remember { mutableStateOf(AiConfigStatus()) }
    val scope = rememberCoroutineScope()

    // 加载入口状态概览（模型数 / 账户数 / 密钥数 / 当前模型名）
    suspend fun loadStatus() {
        if (!isLocalMode) {
            status = AiConfigStatus()
            return
        }
        val repo = ServiceContainer.localRepository
        val models = repo.listAiModels()
        val accounts = repo.oauthManager.listAccounts()
        val keys = repo.listApiKeys()
        val activeModel = models.firstOrNull { it.active && it.purpose == "chat" }
        status = AiConfigStatus(
            modelCount = models.size,
            connectedAccountCount = accounts.count { it.status == "connected" },
            apiKeyCount = keys.size,
            enabledModelCount = models.count { it.enabled },
            activeModelName = activeModel?.name,
        )
    }

    LaunchedEffect(isLocalMode) { loadStatus() }

    // 从子页面返回时刷新计数，避免新增/删除后显示陈旧数据
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isLocalMode) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch { loadStatus() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ===== 主配置分组 =====
            ConfigSectionTitle(
                text = if (isLocalMode) stringResource(R.string.aiconfig_section_local_ai)
                       else stringResource(R.string.aiconfig_section_models_config),
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp, start = 4.dp)
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20,
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isLocalMode) {
                    ConfigRow(
                        icon = Icons.Filled.Memory,
                        title = stringResource(R.string.aiconfig_local_ai_models),
                        subtitle = stringResource(R.string.aiconfig_local_ai_models_subtitle),
                        status = stringResource(R.string.aiconfig_status_count_models, status.modelCount)
                    ) { onNavigate("local_ai_models") }
                    RowDivider()
                    ConfigRow(
                        icon = Icons.Filled.AccountCircle,
                        title = stringResource(R.string.aiconfig_account_title),
                        subtitle = stringResource(R.string.aiconfig_account_subtitle),
                        status = if (status.connectedAccountCount > 0)
                            stringResource(R.string.aiconfig_status_connected, status.connectedAccountCount)
                        else stringResource(R.string.aiconfig_status_not_connected)
                    ) { onNavigate("oauth_accounts") }
                    RowDivider()
                    ConfigRow(
                        icon = Icons.Filled.AutoAwesome,
                        title = stringResource(R.string.aiconfig_title),
                        subtitle = stringResource(R.string.aiconfig_sync_active_params_subtitle),
                        status = status.activeModelName
                            ?: stringResource(R.string.aiconfig_status_default)
                    ) { onNavigate("ai_config") }
                    RowDivider()
                    ConfigRow(
                        icon = Icons.Filled.SwapVert,
                        title = stringResource(R.string.failover_queue_title),
                        subtitle = stringResource(R.string.failover_local_priority_subtitle),
                        status = stringResource(R.string.aiconfig_status_count_priority, status.enabledModelCount)
                    ) { onNavigate("ai_failover") }
                } else {
                    ConfigRow(
                        icon = Icons.Filled.AutoAwesome,
                        title = stringResource(R.string.aiconfig_title),
                        subtitle = stringResource(R.string.aiconfig_configure_params_subtitle)
                    ) { onNavigate("ai_config") }
                    RowDivider()
                    ConfigRow(
                        icon = Icons.Filled.Memory,
                        title = stringResource(R.string.aimodels_title),
                        subtitle = stringResource(R.string.aimodels_manage_list_subtitle)
                    ) { onNavigate("ai_models") }
                    RowDivider()
                    ConfigRow(
                        icon = Icons.Filled.SwapVert,
                        title = stringResource(R.string.failover_queue_title),
                        subtitle = stringResource(R.string.failover_health_subtitle)
                    ) { onNavigate("ai_failover") }
                }
            }

            // ===== 安全与高级分组 =====
            ConfigSectionTitle(
                text = stringResource(R.string.aiconfig_section_security_advanced),
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp, start = 4.dp)
            )
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20,
                contentPadding = PaddingValues(0.dp)
            ) {
                ConfigRow(
                    icon = Icons.Filled.Key,
                    title = stringResource(R.string.aiconfig_api_key_management),
                    subtitle = if (isLocalMode) stringResource(R.string.aiconfig_manage_local_keys_subtitle)
                               else stringResource(R.string.aiconfig_manage_keys_subtitle),
                    status = if (isLocalMode)
                        stringResource(R.string.aiconfig_status_count_keys, status.apiKeyCount)
                    else ""
                ) { onNavigate("api_keys") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 分组标题：小号中等字重，使用次级文字色，营造系统设置页的分组感。字体跟随全局设置。 */
@Composable
private fun ConfigSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.4.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * 配置入口行：左侧带淡粉色背景的图标，中部标题 + 描述，
 * 右侧状态文案 + 箭头。整行可点击，高度固定 80dp。
 */
@Composable
private fun ConfigRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    status: String = "",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标 + 淡粉色背景容器
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        // 标题 + 描述
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                    lineHeight = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 状态文案：限制最大宽度避免挤压标题，超出省略
        if (status.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 88.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 行间分割线：从文字区域开始（避开图标列），贯穿到右侧。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp, end = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
}
