package com.nekobot.app.ui.screens.profile

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.ui.components.NekoCheckMark
import com.nekobot.app.ui.components.NekoChoiceRow
import com.nekobot.app.ui.components.NekoDivider
import com.nekobot.app.ui.components.NekoGroup
import com.nekobot.app.ui.components.NekoTone
import com.nekobot.app.ui.components.NekoIconTile
import com.nekobot.app.ui.components.NekoPageHorizontalPadding
import com.nekobot.app.ui.components.NekoPageTopBar
import com.nekobot.app.ui.components.NekoSectionHeader
import com.nekobot.app.ui.components.NekoSettingRow
import com.nekobot.app.ui.components.NekoSheet
import com.nekobot.app.ui.components.NekoSheetHeader
import com.nekobot.app.ui.components.NekoStatusLine
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.components.nekoWelcomeTitleStyle
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.navigation.rememberBottomBarClearance
import com.nekobot.app.ui.theme.accentSecondary
import com.nekobot.app.ui.theme.accentSuccess
import com.nekobot.app.ui.theme.accentTertiary
import com.nekobot.app.ui.theme.accentWarning
import com.nekobot.app.ui.theme.nekoAccentSoft

/**
 * 「我的」一级页面。
 *
 * 设计取向：先给一个能被认出的身份块，再把设置按「偏好 / 内容 / 数据 / 系统」分组，
 * 每组只留必要入口；真正繁杂的配置继续留在「高级设置」里，避免一级页面变成功能清单。
 */
@Composable
fun ProfileScreen(
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val isLocal = appMode == AppMode.LOCAL
    val bottomBarClearance = rememberBottomBarClearance()
    var showLanguageSheet by remember { mutableStateOf(false) }
    val userName = ServiceContainer.prefs.username.ifBlank {
        stringResource(R.string.common_not_logged_in)
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    // 语言：与设置页保持一致的处理方式（写偏好 → 刷新 Locale → 重建 Activity）
    val languageLabel = stringResource(
        when (ServiceContainer.prefs.language) {
            PrefsManager.LANGUAGE_ZH -> R.string.language_chinese
            PrefsManager.LANGUAGE_ZH_TW -> R.string.language_chinese_traditional
            PrefsManager.LANGUAGE_EN -> R.string.language_english
            PrefsManager.LANGUAGE_JA -> R.string.language_japanese
            PrefsManager.LANGUAGE_KO -> R.string.language_korean
            else -> R.string.language_system
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NekoPageTopBar(title = stringResource(R.string.nav_profile))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = NekoPageHorizontalPadding,
                end = NekoPageHorizontalPadding,
                top = 2.dp,
                bottom = bottomBarClearance + 24.dp
            )
        ) {
            item(key = "profile-card") {
                ProfileIdentityCard(
                    name = if (isLocal) stringResource(R.string.shell_profile_name) else userName,
                    status = if (isLocal) {
                        stringResource(R.string.shell_profile_local)
                    } else {
                        stringResource(R.string.shell_profile_server, userName)
                    }
                )
            }

            item(key = "preferences-header") {
                NekoSectionHeader(title = stringResource(R.string.shell_profile_preferences))
            }
            item(key = "preferences") {
                NekoGroup {
                    NekoSettingRow(
                        title = stringResource(R.string.more_style_settings),
                        subtitle = stringResource(R.string.more_style_settings_desc),
                        icon = Icons.Outlined.Palette,
                        tone = NekoTone.Accent,
                        showChevron = true,
                        onClick = { onNavigate(Routes.STYLE_SETTINGS) }
                    )
                    NekoDivider(startInset = 61.dp)
                    NekoSettingRow(
                        title = stringResource(R.string.shell_profile_skin),
                        icon = Icons.Outlined.EmojiEmotions,
                        tone = NekoTone.Green,
                        showChevron = true,
                        onClick = { onNavigate(Routes.RELATIONSHIP_BACKGROUND) }
                    )
                    NekoDivider(startInset = 61.dp)
                    NekoSettingRow(
                        title = stringResource(R.string.shell_profile_language),
                        value = languageLabel,
                        icon = Icons.Outlined.Language,
                        tone = NekoTone.Blue,
                        onClick = { showLanguageSheet = true }
                    )
                    NekoDivider(startInset = 61.dp)
                    NekoSettingRow(
                        title = stringResource(R.string.shell_profile_notifications),
                        icon = Icons.Outlined.Notifications,
                        tone = NekoTone.Amber,
                        showChevron = true,
                        onClick = {
                            // 直接跳到系统通知设置：应用内不再提供一层无效开关
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            }
                        }
                    )
                }
            }

            item(key = "content-header") {
                NekoSectionHeader(title = stringResource(R.string.shell_profile_content))
            }
            item(key = "content") {
                NekoGroup {
                    NekoSettingRow(
                        title = stringResource(R.string.more_stickers),
                        subtitle = stringResource(R.string.more_stickers_desc),
                        icon = Icons.Outlined.EmojiEmotions,
                        tone = NekoTone.Accent,
                        showChevron = true,
                        onClick = { onNavigate(Routes.STICKERS) }
                    )
                    NekoDivider(startInset = 61.dp)
                    NekoSettingRow(
                        title = stringResource(R.string.more_state_history),
                        subtitle = stringResource(R.string.more_state_history_desc),
                        icon = Icons.AutoMirrored.Outlined.ShowChart,
                        tone = NekoTone.Green,
                        showChevron = true,
                        onClick = { onNavigate(Routes.STATE_HISTORY) }
                    )
                }
            }

            item(key = "data-header") {
                NekoSectionHeader(title = stringResource(R.string.shell_profile_data))
            }
            item(key = "data") {
                NekoGroup {
                    if (isLocal) {
                        NekoSettingRow(
                            title = stringResource(R.string.more_db_management),
                            subtitle = stringResource(R.string.more_db_management_desc),
                            icon = Icons.Outlined.Storage,
                        tone = NekoTone.Blue,
                            showChevron = true,
                            onClick = { onNavigate(Routes.DB_PROFILE) }
                        )
                        NekoDivider(startInset = 61.dp)
                    }
                    NekoSettingRow(
                        title = stringResource(R.string.settings_webdav_backup),
                        icon = Icons.Outlined.Backup,
                        tone = NekoTone.Amber,
                        showChevron = true,
                        onClick = { onNavigate(Routes.WEBDAV_BACKUP) }
                    )
                    NekoDivider(startInset = 61.dp)
                    // 配置迁移只处理服务器配置，本地模式没有可迁移的内容
                    if (!isLocal) {
                        NekoSettingRow(
                            title = stringResource(R.string.settings_config_transfer),
                            icon = Icons.Outlined.SwapHoriz,
                        tone = NekoTone.Accent,
                            showChevron = true,
                            onClick = { onNavigate(Routes.CONFIG_TRANSFER) }
                        )
                        NekoDivider(startInset = 61.dp)
                    }
                    NekoSettingRow(
                        title = stringResource(R.string.settings_data_portability),
                        icon = Icons.Outlined.FileDownload,
                        tone = NekoTone.Green,
                        showChevron = true,
                        onClick = { onNavigate(Routes.DATA_PORTABILITY) }
                    )
                    NekoDivider(startInset = 61.dp)
                    NekoSettingRow(
                        title = stringResource(R.string.settings_data_maintenance),
                        icon = Icons.Outlined.Build,
                        tone = NekoTone.Blue,
                        showChevron = true,
                        onClick = { onNavigate(Routes.DATA_MAINTENANCE) }
                    )
                }
            }

            item(key = "system-header") {
                NekoSectionHeader(title = stringResource(R.string.shell_profile_system))
            }
            item(key = "system") {
                NekoGroup {
                    // 系统设置页编辑的是服务器侧配置，本地模式不展示
                    if (!isLocal) {
                        NekoSettingRow(
                            title = stringResource(R.string.settings_system_settings),
                            icon = Icons.Outlined.Settings,
                        tone = NekoTone.Amber,
                            showChevron = true,
                            onClick = { onNavigate(Routes.SYSTEM_SETTINGS) }
                        )
                        NekoDivider(startInset = 61.dp)
                    }
                    NekoSettingRow(
                        title = stringResource(R.string.more_system_operations),
                        subtitle = stringResource(R.string.more_system_operations_desc),
                        icon = Icons.Outlined.Build,
                        tone = NekoTone.Accent,
                        showChevron = true,
                        onClick = { onNavigate(Routes.SYSTEM_OPERATIONS) }
                    )
                    NekoDivider(startInset = 61.dp)
                    NekoSettingRow(
                        title = stringResource(R.string.shell_profile_advanced),
                        subtitle = stringResource(R.string.more_settings_desc),
                        icon = Icons.Outlined.Settings,
                        tone = NekoTone.Green,
                        showChevron = true,
                        onClick = { onNavigate(Routes.SETTINGS) }
                    )
                }
            }

            item(key = "about-header") {
                NekoSectionHeader(title = stringResource(R.string.settings_about))
            }
            item(key = "about") {
                NekoGroup {
                    NekoSettingRow(
                        title = stringResource(R.string.settings_about),
                        value = stringResource(R.string.shell_profile_version, versionName),
                        icon = Icons.Outlined.Info,
                        tone = NekoTone.Blue,
                        showChevron = true,
                        onClick = { onNavigate(Routes.ABOUT) }
                    )
                    if (!isLocal) {
                        NekoDivider(startInset = 61.dp)
                        NekoSettingRow(
                            title = stringResource(R.string.more_logout),
                            subtitle = stringResource(R.string.more_current_user, userName),
                            icon = Icons.AutoMirrored.Outlined.Logout,
                        tone = NekoTone.Amber,
                            iconTint = MaterialTheme.colorScheme.error,
                            iconBackground = MaterialTheme.colorScheme.errorContainer,
                            onClick = onLogout
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (showLanguageSheet) {
        LanguageSheet(
            currentLanguage = ServiceContainer.prefs.language,
            onDismiss = { showLanguageSheet = false },
            onSelect = { tag ->
                ServiceContainer.prefs.language = tag
                ServiceContainer.refreshLocale()
                showLanguageSheet = false
                (context as? Activity)?.recreate()
            }
        )
    }
}

/** 身份块：应用图标 + 名称 + 当前模式，说明数据放在哪里。 */
@Composable
private fun ProfileIdentityCard(name: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.mipmap.neko),
            contentDescription = null,
            modifier = Modifier
                .size(55.dp)
                .clip(RoundedCornerShape(17.dp))
        )
        Spacer(Modifier.size(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = nekoWelcomeTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.height(5.dp))
            NekoStatusLine(text = status)
        }
    }
}

/** 语言选择抽屉：跟随系统 / 简体 / 繁體 / English / 日本語 / 한국어。 */
@Composable
private fun LanguageSheet(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        PrefsManager.LANGUAGE_SYSTEM to stringResource(R.string.language_system),
        PrefsManager.LANGUAGE_ZH to stringResource(R.string.language_chinese),
        PrefsManager.LANGUAGE_ZH_TW to stringResource(R.string.language_chinese_traditional),
        PrefsManager.LANGUAGE_EN to stringResource(R.string.language_english),
        PrefsManager.LANGUAGE_JA to stringResource(R.string.language_japanese),
        PrefsManager.LANGUAGE_KO to stringResource(R.string.language_korean)
    )
    NekoSheet(onDismissRequest = onDismiss) {
        NekoSheetHeader(
            title = stringResource(R.string.settings_language),
            onClose = onDismiss
        )
        options.forEachIndexed { index, (tag, label) ->
            NekoChoiceRow(
                title = label,
                onClick = { onSelect(tag) },
                trailing = { NekoCheckMark(selected = tag == currentLanguage) },
                showChevron = false,
                showDivider = index != options.lastIndex,
                leading = {
                    NekoIconTile(
                        icon = Icons.Outlined.Language,
                        tone = NekoTone.Accent,
                        size = 36.dp,
                        tint = MaterialTheme.colorScheme.primary,
                        background = nekoAccentSoft()
                    )
                }
            )
        }
    }
}
