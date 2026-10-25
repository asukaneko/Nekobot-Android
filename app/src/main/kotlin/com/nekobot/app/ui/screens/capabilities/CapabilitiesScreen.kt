package com.nekobot.app.ui.screens.capabilities

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.components.NekoChevron
import com.nekobot.app.ui.components.NekoTone
import com.nekobot.app.ui.components.NekoCountBadge
import com.nekobot.app.ui.components.NekoDivider
import com.nekobot.app.ui.components.NekoGroup
import com.nekobot.app.ui.components.NekoIconTile
import com.nekobot.app.ui.components.NekoPageHorizontalPadding
import com.nekobot.app.ui.components.NekoPageTopBar
import com.nekobot.app.ui.components.NekoSectionHeader
import com.nekobot.app.ui.components.NekoSettingRow
import com.nekobot.app.ui.components.NekoStatusLine
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.navigation.rememberBottomBarClearance

/**
 * 「能力」一级页面。
 *
 * 结构：一张当前模型状态卡 + 四张能力入口卡（Agent / 记忆 / 扩展 / 用量），
 * 需要更细配置的项目收在下方分组列表里——只保留入口，不把配置项平铺到一级页面。
 */
@Composable
fun CapabilitiesScreen(onNavigate: (String) -> Unit) {
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val bottomBarClearance = rememberBottomBarClearance()
    val isLocal = appMode == AppMode.LOCAL

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NekoPageTopBar(title = stringResource(R.string.nav_capabilities))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = NekoPageHorizontalPadding,
                end = NekoPageHorizontalPadding,
                top = 4.dp,
                bottom = bottomBarClearance + 24.dp
            )
        ) {
            // 当前运行状态：模型与工具的总入口
            item(key = "models-hero") {
                ModelsHeroCard(
                    statusText = stringResource(
                        if (isLocal) R.string.shell_cap_status_local else R.string.shell_cap_status_server
                    ),
                    onClick = { onNavigate(Routes.AI_CONFIG_CENTER) }
                )
            }

            item(key = "expand-header") {
                NekoSectionHeader(title = stringResource(R.string.shell_cap_expand))
            }

            item(key = "cap-grid") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CapabilityCard(
                            icon = Icons.Outlined.Psychology,
                            title = stringResource(R.string.shell_cap_agent),
                            description = stringResource(R.string.shell_cap_agent_desc),
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.AGENT_SETTINGS) }
                        )
                        CapabilityCard(
                            icon = Icons.Outlined.School,
                            title = stringResource(R.string.shell_cap_skills),
                            description = stringResource(R.string.shell_cap_skills_desc),
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.SKILLS) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CapabilityCard(
                            icon = Icons.Outlined.Extension,
                            title = stringResource(R.string.shell_cap_extensions),
                            description = stringResource(R.string.shell_cap_extensions_desc),
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.EXTENSIONS) }
                        )
                        CapabilityCard(
                            icon = Icons.Outlined.BarChart,
                            title = stringResource(R.string.shell_cap_usage),
                            description = stringResource(R.string.shell_cap_usage_desc),
                            modifier = Modifier.weight(1f),
                            onClick = { onNavigate(Routes.TOKENS) }
                        )
                    }
                }
            }

            item(key = "memory-header") {
                NekoSectionHeader(title = stringResource(R.string.shell_cap_group_memory))
            }

            item(key = "memory-rows") {
                NekoGroup {
                    NekoSettingRow(
                        title = stringResource(R.string.more_role_memory),
                        subtitle = stringResource(R.string.more_role_memory_desc),
                        icon = Icons.Outlined.Bookmarks,
                        tone = NekoTone.Accent,
                        showChevron = true,
                        onClick = { onNavigate(Routes.MEMORY) }
                    )
                    NekoDivider(startInset = 61.dp)
                    NekoSettingRow(
                        title = stringResource(R.string.more_global_agent_memory),
                        subtitle = stringResource(R.string.more_global_agent_memory_desc),
                        icon = Icons.Outlined.Storage,
                        tone = NekoTone.Green,
                        showChevron = true,
                        onClick = { onNavigate(Routes.AGENT_MEMORY) }
                    )
                    if (isLocal) {
                        NekoDivider(startInset = 61.dp)
                        NekoSettingRow(
                            title = stringResource(R.string.agent_character_memory_title),
                            subtitle = stringResource(R.string.agent_character_memory_desc),
                            icon = Icons.Outlined.Bookmarks,
                            tone = NekoTone.Blue,
                            showChevron = true,
                            onClick = { onNavigate(Routes.AGENT_CHARACTER_MEMORY) }
                        )
                    }
                }
            }
        }
    }
}

/** 顶部模型状态卡：能一眼看到当前跑在什么模式，点进去才是配置。 */
@Composable
private fun ModelsHeroCard(statusText: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NekoIconTile(icon = Icons.Outlined.AutoAwesome, size = 40.dp)
            Spacer(Modifier.width(11.dp))
            Text(
                text = stringResource(R.string.shell_cap_models),
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            NekoStatusLine(text = statusText)
            Spacer(Modifier.width(4.dp))
            NekoChevron()
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text = stringResource(R.string.shell_cap_models_desc),
            style = nekoRowSubtitleStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 能力入口卡：图标 + 名称 + 一句说明，整块可点。 */
@Composable
private fun CapabilityCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(17.dp)
    Column(
        modifier = modifier
            .heightIn(min = 108.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(15.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(23.dp)
        )
        Spacer(Modifier.size(13.dp))
        Text(
            text = title,
            style = nekoRowTitleStyle(),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.size(5.dp))
        Text(
            text = description,
            style = nekoCaptionStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
