package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

enum class SessionModeLabel { CHARACTER, AGENT, COMPANION, GROUP }

internal fun SessionModeLabel.icon(): ImageVector = when (this) {
    SessionModeLabel.CHARACTER -> SessionModeIcons.Character
    SessionModeLabel.AGENT -> SessionModeIcons.Agent
    SessionModeLabel.COMPANION -> SessionModeIcons.Companion
    SessionModeLabel.GROUP -> Icons.Filled.Group
}

@Composable
internal fun SessionModeLabel.title(): String = stringResource(when (this) {
    SessionModeLabel.CHARACTER -> R.string.sessions_filter_character_sessions
    SessionModeLabel.AGENT -> R.string.sessions_filter_agent_sessions
    SessionModeLabel.COMPANION -> R.string.sessions_filter_agent_characters
    SessionModeLabel.GROUP -> R.string.session_mode_group_label
})

@Composable
internal fun SessionModeLabel.color(): Color {
    // 只用三种克制的色相区分会话类型：陶土红（角色）/ 墨绿（Agent）/ 蓝灰（单角色），
    // 群聊回到中性色。色相不抢正文，也不会在深色底上发光。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (this) {
        SessionModeLabel.CHARACTER -> MaterialTheme.colorScheme.primary
        SessionModeLabel.AGENT -> if (dark) Color(0xFFA8C5B2) else Color(0xFF42715C)
        SessionModeLabel.COMPANION -> if (dark) Color(0xFF9FB4C2) else Color(0xFF5F788C)
        SessionModeLabel.GROUP -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun SessionModeBadge(mode: SessionModeLabel, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(mode.icon(), null, Modifier.size(17.dp), tint = mode.color())
        Text(mode.title(), color = mode.color(), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SessionModeMenuText(mode: SessionModeLabel) {
    Text(mode.title(), style = MaterialTheme.typography.titleSmall)
}
