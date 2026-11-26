package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 一个语音服务商在列表里的样子。以后接第二家只往列表里加一项，界面不用重写。 */
internal data class VoiceProviderSummary(
    val id: String,
    val title: String,
    val connected: Boolean,
    val subtitle: String = ""
)

/** 列表里的一行：名字 + 右侧状态 + 小胶囊按钮。 */
@Composable internal fun VoiceProviderRow(
    provider: VoiceProviderSummary,
    onAction: () -> Unit
) {
    val action = if (provider.connected) "管理" else "连接"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(provider.title, style = MaterialTheme.typography.titleSmall, color = Ink)
            if (provider.subtitle.isNotEmpty()) Text(provider.subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (provider.connected) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(ConfigSuccess))
            Spacer(Modifier.width(5.dp))
            Text("已连接", style = MaterialTheme.typography.bodySmall, color = ConfigSuccess)
        } else {
            Text("未连接", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.clip(CircleShape).background(Accent)
                .clickable(onClick = onAction)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("voice-action-${provider.id}")
                .semantics { contentDescription = "${provider.title} $action" },
            contentAlignment = Alignment.Center
        ) {
            Text(action, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** 连接面板与音色工作室各自独立进入。 */
@Composable internal fun BreezeVoiceServiceSection(openStudio: () -> Unit) {
    val account = rememberBreezeAccount()
    val catalog by account.catalogChanges.collectAsState(Triple(account.accountId(), 0L, false))
    val connected = catalog.third
    var sheetOpen by remember { mutableStateOf(false) }

    val providers = remember(connected) {
        listOf(VoiceProviderSummary("breezeblue", "BreezeBlue", connected, "breezeblue.ai"))
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
        SectionLabel("API 语音")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)
        ) {
            providers.forEachIndexed { index, provider ->
                VoiceProviderRow(provider) { sheetOpen = true }
                if (index < providers.lastIndex) HorizontalDivider(color = Line, thickness = .5.dp)
            }
        }
        Text(if (connected) "用于角色发语音；去角色聊天设置里打开开关即可生效。" else "首次使用点「连接」，内附 BreezeBlue 官网与密钥获取指引。",
            style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.height(UiSpace.small))
        SectionLabel("音色管理")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)
            .clickable(onClick = openStudio).testTag("voice-studio-open").padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DirectoryBadge("voice")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("音色库", style = MaterialTheme.typography.titleSmall, color = Ink)
                Text("试听、创建与管理音色", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text("打开", Modifier.background(Accent, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }

    if (sheetOpen) {
        BreezeVoiceConnectSheet(account, connected) { sheetOpen = false }
    }
}
