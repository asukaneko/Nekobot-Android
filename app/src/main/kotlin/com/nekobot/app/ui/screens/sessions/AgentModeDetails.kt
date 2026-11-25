package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.SectionHeader

/** 旧模式仅保留兼容说明；不修改已存设置，也不再开放陪伴功能的旧开关。 */
@Composable
internal fun AgentModeDetailsCard(
    companion: Boolean,
    legacy: Boolean,
    onOpenMemories: () -> Unit
) {
    if (!companion && !legacy) return
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(if (companion) R.string.agent_character_details_title else R.string.agent_legacy_title))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (companion) R.string.agent_character_details_body else R.string.agent_legacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (companion) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenMemories, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.agent_character_memories))
            }
        }
    }
}
