package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.BrowserUserAgentMode
import com.nekobot.app.data.local.ai.LocalBrowserConfig
import com.nekobot.app.ui.components.BorderlessFilterChip
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassCard

/**
 * browser_use 工具设置界面。
 *
 * 这里配置的是**默认身份与渲染环境**：User-Agent、视口尺寸、脚本与图片加载、标签页上限。
 * 保存在 [ServiceContainer.prefs] 中，浏览器工具每次动作前读取一次，
 * 因此改完设置无需重开会话；模型在会话内用 `set_user_agent` / `set_viewport`
 * 做的临时调整会在下一次配置变更时被用户设置覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsScreen(onBack: () -> Unit) {
    val prefs = ServiceContainer.prefs

    var uaMode by remember {
        mutableStateOf(BrowserUserAgentMode.fromStorage(prefs.browserUserAgentMode))
    }
    var customUa by remember { mutableStateOf(prefs.browserCustomUserAgent) }
    var javascriptEnabled by remember { mutableStateOf(prefs.browserJavascriptEnabled) }
    var imagesEnabled by remember { mutableStateOf(prefs.browserImagesEnabled) }

    val previewConfig = LocalBrowserConfig(
        userAgentMode = uaMode,
        customUserAgent = customUa,
        viewportWidthCss = prefs.browserViewportWidth,
        viewportHeightCss = prefs.browserViewportHeight,
        javascriptEnabled = javascriptEnabled,
        imagesEnabled = imagesEnabled,
        maxTabs = prefs.browserMaxTabs
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.browser_settings_title),
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
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.browser_settings_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ==================== User-Agent 身份 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                BrowserGroupTitle(stringResource(R.string.browser_settings_group_identity))
                BrowserSettingRow(
                    icon = Icons.Filled.Language,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.browser_settings_ua_mode),
                    desc = stringResource(R.string.browser_settings_ua_mode_desc)
                )
                // 三个预设横排；芯片过宽时 Row 会自动换行
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BrowserUserAgentMode.entries.forEach { mode ->
                        BorderlessFilterChip(
                            selected = uaMode == mode,
                            onClick = {
                                uaMode = mode
                                prefs.browserUserAgentMode = mode.name
                            },
                            label = { Text(browserUserAgentModeLabel(mode)) }
                        )
                    }
                }
                AgentLikeTextField(
                    label = stringResource(R.string.browser_settings_custom_ua),
                    value = customUa,
                    enabled = uaMode == BrowserUserAgentMode.CUSTOM,
                    placeholder = stringResource(R.string.browser_settings_custom_ua_hint),
                    onValueChange = { input ->
                        customUa = input.take(LocalBrowserConfig.MAX_CUSTOM_USER_AGENT_CHARS)
                        prefs.browserCustomUserAgent = customUa
                    }
                )
                Text(
                    text = stringResource(R.string.browser_settings_effective_ua),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 4.dp)
                )
                Text(
                    text = previewConfig.userAgent,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            // ==================== 渲染 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                BrowserGroupTitle(stringResource(R.string.browser_settings_group_render))
                BrowserSettingRow(
                    icon = Icons.Filled.Language,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.browser_settings_javascript),
                    desc = stringResource(R.string.browser_settings_javascript_desc)
                ) {
                    Switch(
                        checked = javascriptEnabled,
                        onCheckedChange = {
                            javascriptEnabled = it
                            prefs.browserJavascriptEnabled = it
                        }
                    )
                }
                BrowserSettingRow(
                    icon = Icons.Filled.Image,
                    tint = MaterialTheme.colorScheme.tertiary,
                    title = stringResource(R.string.browser_settings_images),
                    desc = stringResource(R.string.browser_settings_images_desc)
                ) {
                    Switch(
                        checked = imagesEnabled,
                        onCheckedChange = {
                            imagesEnabled = it
                            prefs.browserImagesEnabled = it
                        }
                    )
                }
                BrowserSettingRow(
                    icon = Icons.Filled.Straighten,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.browser_settings_viewport_width),
                    desc = stringResource(
                        R.string.browser_settings_viewport_width_desc,
                        LocalBrowserConfig.MIN_VIEWPORT_WIDTH_CSS,
                        LocalBrowserConfig.MAX_VIEWPORT_WIDTH_CSS,
                        LocalBrowserConfig.DEFAULT_VIEWPORT_WIDTH_CSS
                    )
                ) {
                    BrowserNumericInput(
                        initial = prefs.browserViewportWidth.toString(),
                        min = LocalBrowserConfig.MIN_VIEWPORT_WIDTH_CSS,
                        max = LocalBrowserConfig.MAX_VIEWPORT_WIDTH_CSS,
                        onValid = { prefs.browserViewportWidth = it }
                    )
                }
                BrowserSettingRow(
                    icon = Icons.Filled.Straighten,
                    tint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.browser_settings_viewport_height),
                    desc = stringResource(
                        R.string.browser_settings_viewport_height_desc,
                        LocalBrowserConfig.MIN_VIEWPORT_HEIGHT_CSS,
                        LocalBrowserConfig.MAX_VIEWPORT_HEIGHT_CSS,
                        LocalBrowserConfig.DEFAULT_VIEWPORT_HEIGHT_CSS
                    )
                ) {
                    BrowserNumericInput(
                        initial = prefs.browserViewportHeight.toString(),
                        min = LocalBrowserConfig.MIN_VIEWPORT_HEIGHT_CSS,
                        max = LocalBrowserConfig.MAX_VIEWPORT_HEIGHT_CSS,
                        onValid = { prefs.browserViewportHeight = it }
                    )
                }
            }

            // ==================== 标签页 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                BrowserGroupTitle(stringResource(R.string.browser_settings_group_tabs))
                BrowserSettingRow(
                    icon = Icons.Filled.Tab,
                    tint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.browser_settings_max_tabs),
                    desc = stringResource(
                        R.string.browser_settings_max_tabs_desc,
                        LocalBrowserConfig.MIN_MAX_TABS,
                        LocalBrowserConfig.MAX_MAX_TABS,
                        LocalBrowserConfig.DEFAULT_MAX_TABS
                    )
                ) {
                    BrowserNumericInput(
                        initial = prefs.browserMaxTabs.toString(),
                        min = LocalBrowserConfig.MIN_MAX_TABS,
                        max = LocalBrowserConfig.MAX_MAX_TABS,
                        onValid = { prefs.browserMaxTabs = it }
                    )
                }
            }

            // ==================== 恢复默认 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                BrowserSettingRow(
                    icon = Icons.Filled.Refresh,
                    tint = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.browser_settings_reset),
                    desc = stringResource(R.string.browser_settings_reset_desc)
                ) {
                    TextButton(onClick = {
                        prefs.browserUserAgentMode = BrowserUserAgentMode.MOBILE.name
                        prefs.browserCustomUserAgent = ""
                        prefs.browserJavascriptEnabled = true
                        prefs.browserImagesEnabled = true
                        prefs.browserViewportWidth = LocalBrowserConfig.DEFAULT_VIEWPORT_WIDTH_CSS
                        prefs.browserViewportHeight = LocalBrowserConfig.DEFAULT_VIEWPORT_HEIGHT_CSS
                        prefs.browserMaxTabs = LocalBrowserConfig.DEFAULT_MAX_TABS
                        // 同步界面本地状态
                        uaMode = BrowserUserAgentMode.MOBILE
                        customUa = ""
                        javascriptEnabled = true
                        imagesEnabled = true
                    }) {
                        Text(stringResource(R.string.browser_settings_reset_action))
                    }
                }
            }

            Spacer(Modifier.size(24.dp))
        }
    }
}

/** 分组标题（与 Agent 设置页保持一致的排版）。 */
@Composable
private fun BrowserGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
    )
}

/** 设置行：彩色图标底座 + 标题/描述 + 右侧控件；右侧控件过宽时换行显示在下方。 */
@Composable
private fun BrowserSettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.width(120.dp), contentAlignment = Alignment.CenterEnd) {
                    trailing()
                }
            }
        }
    }
}

/** 带标题的输入框：自定义 User-Agent 使用。 */
@Composable
private fun AgentLikeTextField(
    label: String,
    value: String,
    enabled: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = false,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 数字输入：仅允许数字，合法范围内即时保存，非法输入只更新显示。 */
@Composable
private fun BrowserNumericInput(
    initial: String,
    min: Int,
    max: Int,
    onValid: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() }
            text = filtered
            filtered.toIntOrNull()?.let { num ->
                if (num in min..max) onValid(num)
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(92.dp)
    )
}

/** UA 模式展示名。 */
@Composable
private fun browserUserAgentModeLabel(mode: BrowserUserAgentMode): String = stringResource(
    when (mode) {
        BrowserUserAgentMode.MOBILE -> R.string.browser_settings_ua_mobile
        BrowserUserAgentMode.DESKTOP -> R.string.browser_settings_ua_desktop
        BrowserUserAgentMode.CUSTOM -> R.string.browser_settings_ua_custom
    }
)
