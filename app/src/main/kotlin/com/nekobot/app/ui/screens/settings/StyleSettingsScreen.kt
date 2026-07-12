package com.nekobot.app.ui.screens.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.theme.buildTypography
import com.nekobot.app.ui.theme.parseHexColor

/**
 * 样式设置：主题色、字体类型、字体大小、字体颜色，带实时预览。
 * 选择后立即保存到 prefs，点击"应用"按钮重建 Activity 使全局生效。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StyleSettingsScreen(onBack: () -> Unit) {
    val prefs = ServiceContainer.prefs
    val context = LocalContext.current

    var themeColorOverride by remember { mutableStateOf(prefs.themeColorOverride) }
    var fontFamily by remember { mutableStateOf(prefs.fontFamily) }
    var fontScale by remember { mutableStateOf(prefs.fontScale) }
    var fontColorOverride by remember { mutableStateOf(prefs.fontColorOverride) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var showCustomThemeColorDialog by remember { mutableStateOf(false) }

    // 字体类型选项
    val fontFamilyOptions = listOf(
        "system" to "系统默认",
        "serif" to "衬线",
        "monospace" to "等宽",
        "rounded" to "圆角",
    )

    // 字体大小选项
    val fontScaleOptions = listOf(
        0.85f to "小",
        1.0f to "标准",
        1.15f to "大",
        1.3f to "超大",
    )

    // 主题色选项：null 表示默认紫色
    val themeColorOptions = listOf<Pair<String?, String>>(
        null to "紫色",
        "#4D96FF" to "蓝色",
        "#22C1C5" to "青色",
        "#6BCB77" to "绿色",
        "#FFB347" to "橙色",
        "#FF8FB1" to "粉色",
        "#FF6B6B" to "红色",
    )

    // 字体颜色选项：null 表示跟随主题
    val colorOptions = listOf<Pair<String?, String>>(
        null to "跟随主题",
        "#FF6B6B" to "红色",
        "#FFB347" to "橙色",
        "#6BCB77" to "绿色",
        "#4D96FF" to "蓝色",
        "#9D4EDD" to "紫色",
    )

    fun applyAndRecreate() {
        prefs.themeColorOverride = themeColorOverride
        prefs.fontFamily = fontFamily
        prefs.fontScale = fontScale
        prefs.fontColorOverride = fontColorOverride
        (context as Activity).recreate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("样式设置", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 实时预览
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val previewTypography = buildTypography(fontFamily, fontScale)
                val previewPrimary = parseHexColor(themeColorOverride) ?: MaterialTheme.colorScheme.primary
                val previewColor = fontColorOverride?.let {
                    runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
                } ?: MaterialTheme.colorScheme.onSurface

                Text("实时预览", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("标题文字示例", style = previewTypography.titleLarge, color = previewColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("这是正文内容示例，用于展示当前字体类型、大小与颜色配置的效果。", style = previewTypography.bodyLarge, color = previewColor)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 20.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(previewPrimary)
                    )
                    Text(
                        "主题色 / 气泡预览",
                        style = previewTypography.labelMedium,
                        color = previewColor
                    )
                }
            }

            // 主题色
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("主题色", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    themeColorOptions.forEach { (hex, label) ->
                        // null 表示默认紫色，用当前主题的 primary 展示
                        val circleColor = parseHexColor(hex) ?: MaterialTheme.colorScheme.primary
                        val selected = themeColorOverride == hex
                        ColorCircleButton(
                            color = circleColor,
                            label = label,
                            selected = selected,
                            onClick = {
                                themeColorOverride = hex
                                prefs.themeColorOverride = hex
                            }
                        )
                    }
                    // 自定义主题色按钮
                    val presetHexes = themeColorOptions.map { it.first }
                    val customSelected = themeColorOverride != null && themeColorOverride !in presetHexes
                    ColorCircleButton(
                        color = parseHexColor(themeColorOverride) ?: MaterialTheme.colorScheme.primary,
                        label = "自定义",
                        selected = customSelected,
                        onClick = { showCustomThemeColorDialog = true }
                    )
                }
            }

            // 字体类型
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("字体类型", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fontFamilyOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = fontFamily == value,
                            onClick = {
                                fontFamily = value
                                prefs.fontFamily = value
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // 字体大小
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("字体大小", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fontScaleOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = fontScale == value,
                            onClick = {
                                fontScale = value
                                prefs.fontScale = value
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // 字体颜色
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("字体颜色", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    colorOptions.forEach { (hex, label) ->
                        val circleColor = hex?.let {
                            runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
                        } ?: MaterialTheme.colorScheme.onSurface
                        val selected = fontColorOverride == hex
                        ColorCircleButton(
                            color = circleColor,
                            label = label,
                            selected = selected,
                            onClick = {
                                fontColorOverride = hex
                                prefs.fontColorOverride = hex
                            }
                        )
                    }
                    // 自定义颜色按钮
                    val presetHexes = colorOptions.map { it.first }
                    val customSelected = fontColorOverride != null && fontColorOverride !in presetHexes
                    ColorCircleButton(
                        color = MaterialTheme.colorScheme.primary,
                        label = "自定义",
                        selected = customSelected,
                        onClick = { showCustomColorDialog = true }
                    )
                }
            }

            // 应用按钮
            Button(
                onClick = { applyAndRecreate() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("应用", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // 自定义颜色对话框
    if (showCustomColorDialog) {
        var customHex by remember { mutableStateOf(fontColorOverride ?: "#") }
        AlertDialog(
            onDismissRequest = { showCustomColorDialog = false },
            title = { Text("自定义颜色") },
            text = {
                Column {
                    Text("请输入颜色 hex 值，例如 #FF6B6B", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customHex,
                        onValueChange = { customHex = it },
                        label = { Text("颜色 hex") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = customHex.trim().let { if (it.startsWith("#")) it else "#$it" }
                    fontColorOverride = normalized
                    prefs.fontColorOverride = normalized
                    showCustomColorDialog = false
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomColorDialog = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // 自定义主题色对话框
    if (showCustomThemeColorDialog) {
        var customHex by remember { mutableStateOf(themeColorOverride ?: "#") }
        AlertDialog(
            onDismissRequest = { showCustomThemeColorDialog = false },
            title = { Text("自定义主题色") },
            text = {
                Column {
                    Text("请输入颜色 hex 值，例如 #4D96FF", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customHex,
                        onValueChange = { customHex = it },
                        label = { Text("颜色 hex") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = customHex.trim().let { if (it.startsWith("#")) it else "#$it" }
                    themeColorOverride = normalized
                    prefs.themeColorOverride = normalized
                    showCustomThemeColorDialog = false
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomThemeColorDialog = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/** 颜色圆形按钮：选中时有边框高亮。 */
@Composable
private fun ColorCircleButton(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    shape = CircleShape
                )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
