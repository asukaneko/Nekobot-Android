package com.nekobot.app.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.theme.buildTypography
import com.nekobot.app.ui.theme.defaultPrimaryColor
import com.nekobot.app.ui.theme.parseHexColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import java.util.UUID

/**
 * 样式设置：主题色、字体类型、字体大小、字体颜色，带实时预览。
 * 选择后立即保存到 prefs，点击"应用"按钮重建 Activity 使全局生效。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StyleSettingsScreen(onBack: () -> Unit) {
    val prefs = ServiceContainer.prefs

    var themeColorOverride by remember { mutableStateOf(prefs.themeColorOverride) }
    var fontFamily by remember { mutableStateOf(prefs.fontFamily) }
    var customFontPath by remember { mutableStateOf(prefs.customFontPath) }
    var customFontName by remember { mutableStateOf(prefs.customFontName) }
    var fontScale by remember { mutableStateOf(prefs.fontScale) }
    var fontColorOverride by remember { mutableStateOf(prefs.fontColorOverride) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    var showCustomThemeColorDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 字体类型选项
    val fontFamilyOptions = listOf(
        PrefsManager.FONT_FAMILY_SYSTEM to stringResource(R.string.style_font_system),
        PrefsManager.FONT_FAMILY_SERIF to stringResource(R.string.style_font_serif),
        PrefsManager.FONT_FAMILY_MONOSPACE to stringResource(R.string.style_font_monospace),
        PrefsManager.FONT_FAMILY_ROUNDED to stringResource(R.string.style_font_rounded),
        PrefsManager.FONT_FAMILY_CUSTOM to stringResource(R.string.style_custom)
    )

    // 字体大小选项
    val fontScaleOptions = listOf(
        0.85f to stringResource(R.string.style_size_small),
        1.0f to stringResource(R.string.style_size_standard),
        1.15f to stringResource(R.string.style_size_large),
        1.3f to stringResource(R.string.style_size_xlarge),
    )

    // 主题色选项：null 表示默认紫色
    val themeColorOptions = listOf<Pair<String?, String>>(
        null to stringResource(R.string.style_color_purple),
        "#4D96FF" to stringResource(R.string.style_color_blue),
        "#22C1C5" to stringResource(R.string.style_color_cyan),
        "#6BCB77" to stringResource(R.string.style_color_green),
        "#FFB347" to stringResource(R.string.style_color_orange),
        "#FF8FB1" to stringResource(R.string.style_color_pink),
        "#FF6B6B" to stringResource(R.string.style_color_red),
    )

    // 字体颜色选项：null 表示跟随主题
    val colorOptions = listOf<Pair<String?, String>>(
        null to stringResource(R.string.style_color_follow_theme),
        "#FF6B6B" to stringResource(R.string.style_color_red),
        "#FFB347" to stringResource(R.string.style_color_orange),
        "#6BCB77" to stringResource(R.string.style_color_green),
        "#4D96FF" to stringResource(R.string.style_color_blue),
        "#9D4EDD" to stringResource(R.string.style_color_purple),
    )

    fun applyAndRecreate() {
        prefs.themeColorOverride = themeColorOverride
        prefs.fontFamily = fontFamily
        prefs.customFontPath = customFontPath
        prefs.customFontName = customFontName
        prefs.fontScale = fontScale
        prefs.fontColorOverride = fontColorOverride
        (context as Activity).recreate()
    }

    // 字体文件选择器：选择 TTF/OTF 字体文件
    val fontLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { saveFontFile(context, uri) }
                if (saved != null) {
                    customFontPath = saved.first
                    customFontName = saved.second
                    fontFamily = PrefsManager.FONT_FAMILY_CUSTOM
                    // 立即保存，确保 buildTypography 在 recreate 后能读到
                    prefs.customFontPath = saved.first
                    prefs.customFontName = saved.second
                    prefs.fontFamily = PrefsManager.FONT_FAMILY_CUSTOM
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.style_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
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
                val previewPrimary = parseHexColor(themeColorOverride) ?: defaultPrimaryColor()
                val previewColor = fontColorOverride?.let {
                    runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
                } ?: MaterialTheme.colorScheme.onSurface

                Text(stringResource(R.string.style_realtime_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.style_preview_title), style = previewTypography.titleLarge, color = previewColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.style_preview_body), style = previewTypography.bodyLarge, color = previewColor)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 20.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(previewPrimary)
                    )
                    Text(
                        stringResource(R.string.style_preview_bubble),
                        style = previewTypography.labelMedium,
                        color = previewColor
                    )
                }
            }

            // 主题色
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.style_theme_color), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    themeColorOptions.forEach { (hex, label) ->
                        // null 表示默认紫色，用默认紫色展示（不受当前主题色影响）
                        val circleColor = parseHexColor(hex) ?: defaultPrimaryColor()
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
                        color = parseHexColor(themeColorOverride) ?: defaultPrimaryColor(),
                        label = stringResource(R.string.style_custom),
                        selected = customSelected,
                        onClick = { showCustomThemeColorDialog = true }
                    )
                }
            }

            // 字体类型
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.style_font_type), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fontFamilyOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = fontFamily == value,
                            onClick = {
                                if (value == PrefsManager.FONT_FAMILY_CUSTOM) {
                                    if (!customFontPath.isNullOrBlank()) {
                                        // 已有持久化的自定义字体，直接切换使用
                                        fontFamily = PrefsManager.FONT_FAMILY_CUSTOM
                                        prefs.fontFamily = PrefsManager.FONT_FAMILY_CUSTOM
                                    } else {
                                        // 首次选择自定义时触发文件选择器
                                        fontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
                                    }
                                } else {
                                    fontFamily = value
                                    prefs.fontFamily = value
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                // 已上传自定义字体时，展示文件名、更换和删除按钮（不依赖当前字体类型）
                if (customFontPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = customFontName ?: stringResource(R.string.style_custom_font_name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        // 更换字体按钮：重新触发文件选择器
                        TextButton(onClick = {
                            fontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
                        }) {
                            Text(stringResource(R.string.style_change_font), color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            // 删除自定义字体文件（兼容 file:/// 前缀和裸路径）
                            customFontPath?.let { path ->
                                runCatching {
                                    val raw = Uri.parse(path).path ?: path
                                    val file = File(raw)
                                    if (file.exists()) file.delete()
                                }
                            }
                            customFontPath = null
                            customFontName = null
                            prefs.customFontPath = null
                            prefs.customFontName = null
                            // 若当前正在使用自定义字体，则回退到系统默认
                            if (fontFamily == PrefsManager.FONT_FAMILY_CUSTOM) {
                                fontFamily = PrefsManager.FONT_FAMILY_SYSTEM
                                prefs.fontFamily = PrefsManager.FONT_FAMILY_SYSTEM
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.style_delete_custom_font), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // 字体大小
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.style_font_size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
                Text(stringResource(R.string.style_font_color), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
                        label = stringResource(R.string.style_custom),
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
                Text(stringResource(R.string.common_apply), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // 自定义颜色对话框
    if (showCustomColorDialog) {
        var customHex by remember { mutableStateOf(fontColorOverride ?: "#") }
        AlertDialog(
            onDismissRequest = { showCustomColorDialog = false },
            title = { Text(stringResource(R.string.style_custom_color)) },
            text = {
                Column {
                    Text(stringResource(R.string.style_color_hex_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customHex,
                        onValueChange = { customHex = it },
                        label = { Text(stringResource(R.string.style_color_hex_label)) },
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
                    Text(stringResource(R.string.common_ok), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomColorDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // 自定义主题色对话框
    if (showCustomThemeColorDialog) {
        var customHex by remember { mutableStateOf(themeColorOverride ?: "#") }
        AlertDialog(
            onDismissRequest = { showCustomThemeColorDialog = false },
            title = { Text(stringResource(R.string.style_custom_theme_color)) },
            text = {
                Column {
                    Text(stringResource(R.string.style_color_hex_hint_theme), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customHex,
                        onValueChange = { customHex = it },
                        label = { Text(stringResource(R.string.style_color_hex_label)) },
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
                    Text(stringResource(R.string.common_ok), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomThemeColorDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * 将用户选择的字体文件复制到应用内部存储 fonts/ 目录，
 * 返回 (file:/// 绝对路径, 显示文件名)，失败返回 null。
 */
private fun saveFontFile(context: Context, uri: Uri): Pair<String, String>? {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        // 推断扩展名
        val mime = context.contentResolver.getType(uri) ?: "font/ttf"
        val ext = when {
            mime.contains("otf") -> "otf"
            mime.contains("ttf") -> "ttf"
            else -> "ttf"
        }
        val dir = File(context.filesDir, "fonts").apply { if (!exists()) mkdirs() }
        // 用原文件名（若可获取），否则随机命名
        val displayName = runCatching {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && it.moveToFirst()) it.getString(idx) else null
            }
        }.getOrNull() ?: "custom_${UUID.randomUUID().toString().take(8)}.$ext"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._\\-一-龥]"), "_")
        val file = File(dir, safeName)
        file.writeBytes(bytes)
        android.net.Uri.fromFile(file).toString() to displayName
    } catch (e: Exception) {
        null
    }
}
