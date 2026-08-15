package com.nekobot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.theme.*

private val FONT_AWESOME_ICON_PATTERN = Regex("fas[a-z]+-[a-z-]+")

/**
 * 玻璃拟态卡片：半透明 + 渐变描边 + 柔和光晕。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 20,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    borderWidth: Int = 1,
    borderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(
                color = containerColor,
                shape = RoundedCornerShape(cornerRadius.dp)
            )
            .border(
                width = borderWidth.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        borderColor,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                    )
                ),
                shape = RoundedCornerShape(cornerRadius.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * 统一的磨砂玻璃选项菜单。
 *
 * Popup 无法直接采样宿主窗口做实时背景模糊，因此使用半透明表面、玻璃高光描边和柔和阴影，
 * 在保证菜单文字对比度的同时与应用内的玻璃卡片保持一致。
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(24.dp)
    val containerColor = if (dark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
    } else {
        Color.White.copy(alpha = 0.93f)
    }
    val borderBrush = if (dark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.06f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.96f),
                MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
            )
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 1.dp,
            brush = borderBrush
        ),
        content = content
    )
}

/**
 * 表单下拉选择器使用与三点选项菜单相同的磨砂玻璃样式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExposedDropdownMenuBoxScope.GlassExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(24.dp)
    val containerColor = if (dark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)
    } else {
        Color.White.copy(alpha = 0.93f)
    }
    val borderBrush = if (dark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.06f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.96f),
                MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
            )
        )
    }
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 1.dp,
            brush = borderBrush
        ),
        content = content
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) Row(content = trailing)
    }
}

@Composable
fun LoadingOverlay(visible: Boolean, message: String = stringResource(R.string.common_loading)) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier.padding(32.dp),
                cornerRadius = 24,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 自定义弹窗：用于错误提示、确认操作等。
 *  内容过长时自动限制弹窗高度（屏幕 85%）；需要滚动的内容由调用方显式开启。 */
@Composable
fun NekoDialog(
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    confirmText: String = stringResource(R.string.common_confirm),
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    cancelText: String? = stringResource(R.string.common_cancel),
    onCancel: (() -> Unit)? = null,
    confirmIcon: ImageVector? = null,
    confirmIconContentDescription: String? = null,
    contentScrollable: Boolean = false,
    content: @Composable (ColumnScope.() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 限制弹窗最大高度为屏幕 85%，避免内容过长把按钮挤出屏幕
            val maxDialogHeight = maxHeight * 0.85f
            val contentScrollState = rememberScrollState()
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .heightIn(max = maxDialogHeight)
                    .padding(8.dp),
                cornerRadius = 24,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!message.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (content != null) {
                    Spacer(Modifier.height(8.dp))
                    // weight(1f, fill = false)：内容小时保持自然高度，内容超出时占用剩余空间并滚动
                    Column(
                        modifier = (if (contentScrollable) {
                            Modifier.verticalScroll(contentScrollState)
                        } else {
                            Modifier
                        })
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        content()
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (cancelText != null && onCancel != null) {
                        TextButton(onClick = onCancel) {
                            Text(cancelText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (confirmIcon != null) {
                        FilledIconButton(
                            onClick = { onConfirm?.invoke() ?: onDismiss() },
                            enabled = confirmEnabled,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = confirmIcon,
                                contentDescription = confirmIconContentDescription ?: confirmText
                            )
                        }
                    } else {
                        Button(
                            onClick = { onConfirm?.invoke() ?: onDismiss() },
                            enabled = confirmEnabled,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(confirmText, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, hint: String? = null, icon: @Composable (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorBanner(message: String, onRetry: (() -> Unit)? = null) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16,
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

/** 展示一个数据/标签的统计卡片 */
@Composable
fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadius = 16) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    }
}

/**
 * 解析头像图片 URL：若是相对路径则拼接当前服务器 baseUrl，避免双斜杠。
 * 返回 null 表示无可用图片。
 * 支持 http(s):// 绝对 URL、file:// 本地路径、服务器相对路径。
 * 图标类名（如 fas fa-cat）返回 null，由调用方显示图标。
 */
fun resolveAvatarUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    // 本地文件路径：直接返回，Coil 原生支持（兼容 file:// file:/ file:/// 三种写法）
    if (path.startsWith("file:") || path.startsWith("content://")) return path
    // http(s) 绝对 URL：直接返回
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    // 图标类名（如 fas fa-cat、fasfa-cat）：非图片路径，返回 null 由调用方显示图标
    if (path.startsWith("fas ") || path.startsWith("fa-") || path.matches(FONT_AWESOME_ICON_PATTERN)) return null
    // 服务器相对路径：拼接 baseUrl
    val base = ServiceContainer.network.baseUrl().trimEnd('/')
    val relative = path.trimStart('/')
    return "$base/$relative"
}
