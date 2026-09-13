package com.nekobot.app.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.LocalSandboxStatus
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.BorderlessFilterChip
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 沙箱管理界面的 ViewModel：读取/应用镜像源与重置 rootfs。 */
class SandboxManagementViewModel : BaseViewModel() {

    private val _status = MutableStateFlow<LocalSandboxStatus?>(null)
    val status: StateFlow<LocalSandboxStatus?> = _status.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        launchResult(
            block = { unified.sandboxStatus() },
            onSuccess = { _status.value = it }
        )
    }

    /** 把当前设置里的镜像源写入沙箱 rootfs。 */
    fun applyMirrors() {
        launchResult(
            block = { unified.applySandboxMirrors() },
            onSuccess = {
                _status.value = it
                showToast(string(R.string.sandbox_settings_applied))
            }
        )
    }

    /** 重置 rootfs：删除已安装软件与 /root 数据，恢复随 APK 附带的初始镜像。 */
    fun resetRootfs() {
        launchResult(
            block = { unified.resetSandboxRootfs() },
            onSuccess = {
                _status.value = it
                showToast(string(R.string.sandbox_settings_reset_done))
            }
        )
    }
}

/**
 * Linux 沙盒管理界面（仅本地模式）。
 *
 * 三件事：查看 rootfs 状态、编辑 apk / pip / npm 镜像源并写进沙箱、重置 rootfs。
 * 镜像源留空表示「不写这份配置」：apk 会还原首次修改前的原始 sources，
 * pip / npm 会删除由本应用写入的配置文件。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SandboxManagementScreen(onBack: () -> Unit) {
    val vm: SandboxManagementViewModel = viewModel()
    val status by vm.status.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = ServiceContainer.prefs

    var apkMirror by remember { mutableStateOf(prefs.sandboxApkMirror) }
    var pipMirror by remember { mutableStateOf(prefs.sandboxPipMirror) }
    var npmMirror by remember { mutableStateOf(prefs.sandboxNpmMirror) }
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.sandbox_settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.sandbox_settings_reset_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        vm.resetRootfs()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.sandbox_settings_reset_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.sandbox_settings_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.sandbox_settings_refresh),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.sandbox_settings_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    ErrorBanner(message = it, onRetry = { vm.clearError() })
                }

                // ==================== 状态 ====================
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SandboxGroupTitle(stringResource(R.string.sandbox_settings_group_status))
                    SandboxStatusRow(status)
                    status?.let { snapshot ->
                        SandboxCodeBlock(
                            title = stringResource(R.string.sandbox_settings_apk_repositories_current),
                            body = snapshot.apkRepositories.ifBlank {
                                stringResource(R.string.sandbox_settings_value_empty)
                            }
                        )
                        SandboxCodeBlock(
                            title = stringResource(R.string.sandbox_settings_pip_conf_current),
                            body = snapshot.pipConf.ifBlank {
                                stringResource(R.string.sandbox_settings_value_empty)
                            }
                        )
                        SandboxCodeBlock(
                            title = stringResource(R.string.sandbox_settings_npmrc_current),
                            body = snapshot.npmrc.ifBlank {
                                stringResource(R.string.sandbox_settings_value_empty)
                            }
                        )
                    }
                }

                // ==================== 镜像源 ====================
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SandboxGroupTitle(stringResource(R.string.sandbox_settings_group_mirrors))

                    MirrorEditor(
                        icon = Icons.Filled.Download,
                        tint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.sandbox_settings_apk_mirror),
                        desc = stringResource(R.string.sandbox_settings_apk_mirror_desc),
                        value = apkMirror,
                        presets = APK_MIRROR_PRESETS,
                        onValueChange = {
                            apkMirror = it
                            prefs.sandboxApkMirror = it
                        }
                    )
                    MirrorEditor(
                        icon = Icons.Filled.Download,
                        tint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.sandbox_settings_pip_mirror),
                        desc = stringResource(R.string.sandbox_settings_pip_mirror_desc),
                        value = pipMirror,
                        presets = PIP_MIRROR_PRESETS,
                        onValueChange = {
                            pipMirror = it
                            prefs.sandboxPipMirror = it
                        }
                    )
                    MirrorEditor(
                        icon = Icons.Filled.Download,
                        tint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.sandbox_settings_npm_mirror),
                        desc = stringResource(R.string.sandbox_settings_npm_mirror_desc),
                        value = npmMirror,
                        presets = NPM_MIRROR_PRESETS,
                        onValueChange = {
                            npmMirror = it
                            prefs.sandboxNpmMirror = it
                        }
                    )

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { vm.applyMirrors() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sandbox_settings_apply))
                    }
                    Text(
                        text = stringResource(R.string.sandbox_settings_apply_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                    )
                }

                // ==================== 危险操作 ====================
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SandboxGroupTitle(stringResource(R.string.sandbox_settings_group_danger))
                    SandboxSettingRow(
                        icon = Icons.Filled.DeleteForever,
                        tint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.sandbox_settings_reset_title),
                        desc = stringResource(R.string.sandbox_settings_reset_desc)
                    )
                    Button(
                        onClick = { showResetDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sandbox_settings_reset_button))
                    }
                }

                Spacer(Modifier.size(24.dp))
            }
            LoadingOverlay(visible = loading)
        }
    }
}

/** 镜像源预设：标签走字符串资源，值为可直接写入沙箱的地址。 */
private data class MirrorPreset(val labelRes: Int, val value: String)

private val APK_MIRROR_PRESETS = listOf(
    MirrorPreset(R.string.sandbox_settings_mirror_none, ""),
    MirrorPreset(R.string.sandbox_settings_mirror_alpine_official, "https://dl-cdn.alpinelinux.org/alpine"),
    MirrorPreset(R.string.sandbox_settings_mirror_tuna, "https://mirrors.tuna.tsinghua.edu.cn/alpine"),
    MirrorPreset(R.string.sandbox_settings_mirror_ustc, "https://mirrors.ustc.edu.cn/alpine"),
    MirrorPreset(R.string.sandbox_settings_mirror_aliyun, "https://mirrors.aliyun.com/alpine")
)

private val PIP_MIRROR_PRESETS = listOf(
    MirrorPreset(R.string.sandbox_settings_mirror_none, ""),
    MirrorPreset(R.string.sandbox_settings_mirror_pypi_official, "https://pypi.org/simple"),
    MirrorPreset(R.string.sandbox_settings_mirror_tuna, "https://pypi.tuna.tsinghua.edu.cn/simple"),
    MirrorPreset(R.string.sandbox_settings_mirror_ustc, "https://mirrors.ustc.edu.cn/pypi/simple"),
    MirrorPreset(R.string.sandbox_settings_mirror_aliyun, "https://mirrors.aliyun.com/pypi/simple")
)

private val NPM_MIRROR_PRESETS = listOf(
    MirrorPreset(R.string.sandbox_settings_mirror_none, ""),
    MirrorPreset(R.string.sandbox_settings_mirror_npm_official, "https://registry.npmjs.org"),
    MirrorPreset(R.string.sandbox_settings_mirror_npmmirror, "https://registry.npmmirror.com"),
    MirrorPreset(R.string.sandbox_settings_mirror_ustc, "https://npmreg.proxy.ustclug.org")
)

/** 镜像源编辑块：预设芯片 + 输入框。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MirrorEditor(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String,
    value: String,
    presets: List<MirrorPreset>,
    onValueChange: (String) -> Unit
) {
    SandboxSettingRow(
        icon = icon,
        tint = tint,
        title = title,
        desc = desc
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        presets.forEach { preset ->
            BorderlessFilterChip(
                selected = value == preset.value,
                onClick = { onValueChange(preset.value) },
                label = { Text(stringResource(preset.labelRes)) }
            )
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        placeholder = {
            Text(
                stringResource(R.string.sandbox_settings_mirror_placeholder),
                style = MaterialTheme.typography.bodySmall
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
}

/** 状态行：rootfs 安装情况、路径、占用空间、Alpine 版本与 ABI 支持。 */
@Composable
private fun SandboxStatusRow(status: LocalSandboxStatus?) {
    if (status == null) {
        Text(
            text = stringResource(R.string.sandbox_settings_status_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (status.installed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (status.installed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (status.installed) {
                    stringResource(R.string.sandbox_settings_status_installed)
                } else {
                    stringResource(R.string.sandbox_settings_status_not_installed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.sandbox_settings_rootfs_size,
                    formatSandboxSize(status.rootfsSizeBytes)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    StatusInfoLine(
        stringResource(R.string.sandbox_settings_rootfs_path),
        status.rootfsPath
    )
    if (status.alpineRelease.isNotBlank()) {
        StatusInfoLine(
            stringResource(R.string.sandbox_settings_alpine_version),
            status.alpineRelease
        )
    }
    if (!status.abiSupported) {
        Text(
            text = stringResource(R.string.sandbox_settings_abi_unsupported),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp)
        )
    }
}

@Composable
private fun StatusInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 只读代码块：展示沙箱内镜像配置文件的真实内容。 */
@Composable
private fun SandboxCodeBlock(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 4.dp)
    )
    Text(
        text = body,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** 设置行：彩色图标底座 + 标题/描述。 */
@Composable
private fun SandboxSettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SandboxGroupTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Storage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

/** 把字节数格式化成人类可读大小。 */
private fun formatSandboxSize(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1024L * 1024L * 1024L ->
        String.format(java.util.Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
