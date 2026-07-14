package com.nekobot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.theme.*

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
fun LoadingOverlay(visible: Boolean, message: String = "加载中...") {
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

/** 自定义弹窗：用于错误提示、确认操作等。 */
@Composable
fun NekoDialog(
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    confirmText: String = "确定",
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    cancelText: String? = "取消",
    onCancel: (() -> Unit)? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth(0.9f)
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
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (content != null) {
                Spacer(Modifier.height(8.dp))
                Column(content = content)
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
                TextButton(onClick = onRetry) { Text("重试", color = MaterialTheme.colorScheme.primary) }
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
    if (path.startsWith("fas ") || path.startsWith("fa-") || path.matches(Regex("fas[a-z]+-[a-z-]+"))) return null
    // 服务器相对路径：拼接 baseUrl
    val base = ServiceContainer.network.baseUrl().trimEnd('/')
    val relative = path.trimStart('/')
    return "$base/$relative"
}
