package com.nekobot.app.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import com.nekobot.app.ui.components.withoutBorder as border
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
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
import kotlin.math.roundToInt

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
    var bodyFontSizeSp by remember {
        mutableStateOf(PrefsManager.fontScaleToBodySp(prefs.fontScale).roundToInt().toFloat())
    }
    var followSystemFontScale by remember { mutableStateOf(prefs.followSystemFontScale) }
    var fontColorOverride by remember { mutableStateOf(prefs.fontColorOverride) }
    var chatBackgroundMode by remember { mutableStateOf(prefs.chatBackgroundMode) }
    var customChatBackgroundPath by remember { mutableStateOf(prefs.customChatBackgroundPath) }
    var customChatBackgroundName by remember { mutableStateOf(prefs.customChatBackgroundName) }
    var chatBackgroundOpacity by remember { mutableStateOf(prefs.chatBackgroundOpacity) }
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

    val chatBackgroundOptions = listOf(
        PrefsManager.CHAT_BACKGROUND_NONE to stringResource(R.string.style_chat_background_none),
        PrefsManager.CHAT_BACKGROUND_PORTRAIT to stringResource(R.string.style_chat_background_portrait),
        PrefsManager.CHAT_BACKGROUND_CUSTOM to stringResource(R.string.style_chat_background_custom)
    )

    // 主题色选项：null 表示默认粉色
    val themeColorOptions = listOf<Pair<String?, String>>(
        null to stringResource(R.string.style_color_pink),
        "#8B6CFF" to stringResource(R.string.style_color_purple),
        "#4D96FF" to stringResource(R.string.style_color_blue),
        "#22C1C5" to stringResource(R.string.style_color_cyan),
        "#6BCB77" to stringResource(R.string.style_color_green),
        "#FFB347" to stringResource(R.string.style_color_orange),
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
        prefs.followSystemFontScale = followSystemFontScale
        prefs.fontColorOverride = fontColorOverride
        prefs.chatBackgroundMode = chatBackgroundMode
        prefs.customChatBackgroundPath = customChatBackgroundPath
        prefs.customChatBackgroundName = customChatBackgroundName
        prefs.chatBackgroundOpacity = chatBackgroundOpacity
        (context as Activity).recreate()
    }

    fun updateBodyFontSize(value: Float) {
        bodyFontSizeSp = value
            .roundToInt()
            .toFloat()
            .coerceIn(PrefsManager.MIN_BODY_FONT_SP, PrefsManager.MAX_BODY_FONT_SP)
        fontScale = PrefsManager.bodySpToFontScale(bodyFontSizeSp)
        prefs.fontScale = fontScale
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

    // 自定义聊天背景选择器：复制到私有目录，避免外部文档权限被回收后图片失效。
    val backgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { saveChatBackgroundFile(context, uri) }
                if (saved != null) {
                    val previousPath = customChatBackgroundPath
                    customChatBackgroundPath = saved.first
                    customChatBackgroundName = saved.second
                    chatBackgroundMode = PrefsManager.CHAT_BACKGROUND_CUSTOM
                    prefs.customChatBackgroundPath = saved.first
                    prefs.customChatBackgroundName = saved.second
                    prefs.chatBackgroundMode = PrefsManager.CHAT_BACKGROUND_CUSTOM
                    if (previousPath != saved.first) {
                        withContext(Dispatchers.IO) {
                            deletePrivateFile(context, previousPath, "chat_backgrounds")
                        }
                    }
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
                Text(
                    stringResource(R.string.style_font_size_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { updateBodyFontSize(bodyFontSizeSp - 1f) },
                        enabled = bodyFontSizeSp > PrefsManager.MIN_BODY_FONT_SP
                    ) {
                        Icon(
                            Icons.Filled.Remove,
                            contentDescription = stringResource(R.string.style_decrease_font_size)
                        )
                    }
                    Text(
                        stringResource(R.string.style_font_size_value, bodyFontSizeSp.roundToInt()),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { updateBodyFontSize(bodyFontSizeSp + 1f) },
                        enabled = bodyFontSizeSp < PrefsManager.MAX_BODY_FONT_SP
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.style_increase_font_size)
                        )
                    }
                }
                Slider(
                    value = bodyFontSizeSp,
                    onValueChange =(::updateBodyFontSize),
                    valueRange = PrefsManager.MIN_BODY_FONT_SP..PrefsManager.MAX_BODY_FONT_SP,
                    steps = (PrefsManager.MAX_BODY_FONT_SP - PrefsManager.MIN_BODY_FONT_SP).roundToInt() - 1
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                // 跟随系统字号开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.style_follow_system_font_size),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.style_follow_system_font_size_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = followSystemFontScale,
                        onCheckedChange = { value ->
                            followSystemFontScale = value
                            prefs.followSystemFontScale = value
                        }
                    )
                }
            }

            // 聊天界面背景
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.style_chat_background),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.style_chat_background_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    chatBackgroundOptions.forEach { (mode, label) ->
                        FilterChip(
                            selected = chatBackgroundMode == mode,
                            onClick = {
                                if (
                                    mode == PrefsManager.CHAT_BACKGROUND_CUSTOM &&
                                    customChatBackgroundPath.isNullOrBlank()
                                ) {
                                    backgroundLauncher.launch(arrayOf("image/*"))
                                } else {
                                    chatBackgroundMode = mode
                                    prefs.chatBackgroundMode = mode
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                val backgroundModeDescription = when (chatBackgroundMode) {
                    PrefsManager.CHAT_BACKGROUND_PORTRAIT -> R.string.style_chat_background_portrait_desc
                    PrefsManager.CHAT_BACKGROUND_CUSTOM -> R.string.style_chat_background_custom_desc
                    else -> null
                }
                if (backgroundModeDescription != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(backgroundModeDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!customChatBackgroundPath.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    AsyncImage(
                        model = customChatBackgroundPath,
                        contentDescription = stringResource(R.string.style_background_preview),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(156.dp)
                            .clip(MaterialTheme.shapes.medium)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            customChatBackgroundName ?: stringResource(R.string.style_custom_background_name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        TextButton(onClick = { backgroundLauncher.launch(arrayOf("image/*")) }) {
                            Text(stringResource(R.string.style_change_background))
                        }
                        IconButton(
                            onClick = {
                                val oldPath = customChatBackgroundPath
                                customChatBackgroundPath = null
                                customChatBackgroundName = null
                                prefs.customChatBackgroundPath = null
                                prefs.customChatBackgroundName = null
                                if (chatBackgroundMode == PrefsManager.CHAT_BACKGROUND_CUSTOM) {
                                    chatBackgroundMode = PrefsManager.CHAT_BACKGROUND_NONE
                                    prefs.chatBackgroundMode = PrefsManager.CHAT_BACKGROUND_NONE
                                }
                                scope.launch(Dispatchers.IO) {
                                    deletePrivateFile(context, oldPath, "chat_backgrounds")
                                }
                            }
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.style_delete_background),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else if (chatBackgroundMode == PrefsManager.CHAT_BACKGROUND_CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { backgroundLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.style_choose_background))
                    }
                }

                if (chatBackgroundMode != PrefsManager.CHAT_BACKGROUND_NONE) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.style_background_strength),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(
                                R.string.style_background_strength_value,
                                (chatBackgroundOpacity * 100).roundToInt()
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = chatBackgroundOpacity,
                        onValueChange = { value ->
                            chatBackgroundOpacity = value
                            prefs.chatBackgroundOpacity = value
                        },
                        valueRange = PrefsManager.MIN_CHAT_BACKGROUND_OPACITY..PrefsManager.MAX_CHAT_BACKGROUND_OPACITY,
                        steps = 9
                    )
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

/** 将聊天背景复制到应用私有目录，返回 (file URI, 原始显示名)。 */
private fun saveChatBackgroundFile(context: Context, uri: Uri): Pair<String, String>? {
    return try {
        val displayName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: "background_${UUID.randomUUID().toString().take(8)}"
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extension = when {
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            mime.contains("gif", ignoreCase = true) -> "gif"
            else -> "jpg"
        }
        val directory = File(context.filesDir, "chat_backgrounds").apply { mkdirs() }
        val target = File(directory, "background_${UUID.randomUUID()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        Uri.fromFile(target).toString() to displayName
    } catch (_: Exception) {
        null
    }
}

/** 仅删除指定应用私有子目录中的文件，避免异常路径误删其他数据。 */
private fun deletePrivateFile(context: Context, uriPath: String?, directoryName: String) {
    if (uriPath.isNullOrBlank()) return
    runCatching {
        val rawPath = Uri.parse(uriPath).path ?: return@runCatching
        val file = File(rawPath).canonicalFile
        val directory = File(context.filesDir, directoryName).canonicalFile
        if (file.parentFile == directory && file.isFile) file.delete()
    }
}
