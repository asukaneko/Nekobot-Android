package com.nekobot.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.update.UpdateChecker
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog

/**
 * 关于页面：顶部居中显示 App 图标 logo，下面展示版本号、检查更新、制作人、
 * 仓库链接、开源许可证、隐私声明、官方网站等信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {},
    onOpenLicense: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {}
) {
    val vm: SettingsViewModel = viewModel()
    val context = LocalContext.current
    val loading by vm.loading.collectAsState()
    val updateState by vm.updateState.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val toast by vm.toast.collectAsState()

    val version = remember(context) { getAppVersion(context) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about), color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
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
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ===== 顶部居中的 App logo =====
                // 使用 Coil AsyncImage 加载 neko.png 位图，避免 painterResource 在某些设备上
                // 因 mipmap 解析路径不同导致的崩溃；Coil 会安全地降级处理。
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = R.mipmap.neko,
                        contentDescription = stringResource(R.string.app_name),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(124.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_about_version, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                // ===== GlassCard: 检查更新 / 制作人 / 仓库 =====
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    AboutRow(
                        icon = Icons.Filled.SystemUpdate,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_check_update),
                        subtitle = "v$version",
                        onClick = { vm.checkForUpdate(version) }
                    )
                    AboutRow(
                        icon = Icons.Filled.Person,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_about_producer),
                        subtitle = "Asukaneko",
                        onClick = { openUrl(context, "https://github.com/asukaneko") }
                    )
                    AboutRow(
                        icon = Icons.Filled.Description,
                        iconColor = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.settings_about_android_repo),
                        subtitle = "github.com/asukaneko/Nekobot-Android",
                        onClick = { openUrl(context, "https://github.com/asukaneko/Nekobot-Android") }
                    )
                    AboutRow(
                        icon = Icons.Filled.Memory,
                        iconColor = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.settings_about_server_repo),
                        subtitle = "github.com/asukaneko/nekobot",
                        onClick = { openUrl(context, "https://github.com/asukaneko/nekobot") }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ===== GlassCard: 开源许可证 / 隐私声明 / 官方网站 =====
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    AboutRow(
                        icon = Icons.Filled.Gavel,
                        iconColor = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.about_open_source_license),
                        subtitle = "GPL-3.0",
                        onClick = onOpenLicense
                    )
                    AboutRow(
                        icon = Icons.Filled.PrivacyTip,
                        iconColor = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.about_privacy_statement),
                        subtitle = stringResource(R.string.about_privacy_statement_subtitle),
                        onClick = onOpenPrivacy
                    )
                    AboutRow(
                        icon = Icons.Filled.Language,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.about_official_website),
                        subtitle = "asukaneko.github.io",
                        onClick = { openUrl(context, "https://asukaneko.github.io/Nekobot-Android/") }
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
            }

            LoadingOverlay(
                visible = loading || updateState is UpdateUiState.Checking,
                message = if (updateState is UpdateUiState.Checking) stringResource(R.string.update_checking) else stringResource(R.string.common_loading)
            )
        }
    }

    // 检查结果：已是最新版本
    if (updateState is UpdateUiState.UpToDate) {
        NekoDialog(
            onDismiss = { vm.dismissUpdateState() },
            title = stringResource(R.string.settings_check_update),
            message = stringResource(R.string.update_no_new),
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { vm.dismissUpdateState() },
            cancelText = null,
            onCancel = null
        )
    }

    // 检查结果：出错
    if (updateState is UpdateUiState.Error) {
        val errMsg = (updateState as UpdateUiState.Error).message
        NekoDialog(
            onDismiss = { vm.dismissUpdateState() },
            title = stringResource(R.string.settings_check_update),
            message = stringResource(R.string.update_check_failed, errMsg),
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { vm.dismissUpdateState() },
            cancelText = null,
            onCancel = null
        )
    }

    // 发现新版本：发布详情 + 下载
    if (updateState is UpdateUiState.Available) {
        val info = (updateState as UpdateUiState.Available).info
        UpdateDetailDialog(
            info = info,
            currentVersion = getAppVersion(context),
            downloadState = downloadState,
            onDismiss = { vm.dismissUpdateState() },
            onDownload = { asset ->
                vm.downloadApk(context, asset) { file ->
                    runCatching {
                        context.startActivity(UpdateChecker.buildInstallIntent(context, file))
                    }.onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_install_failed),
                            Toast.LENGTH_LONG
                        ).show()
                        // 回退：用浏览器打开 release 页面
                        runCatching {
                            context.startActivity(UpdateChecker.buildReleasesPageIntent())
                        }
                    }
                }
            },
            onOpenInBrowser = {
                runCatching { context.startActivity(UpdateChecker.buildReleasesPageIntent()) }
            }
        )
    }
}

/** 关于页面的导航行：图标 + 标题 + 副标题 + 右箭头。 */
@Composable
private fun AboutRow(
    icon: ImageVector,
    iconColor: Color,
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
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.common_cannot_open_link), Toast.LENGTH_SHORT).show()
    }
}

private fun getAppVersion(context: android.content.Context): String {
    return runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        info.versionName ?: "unknown"
    }.getOrDefault("unknown")
}
