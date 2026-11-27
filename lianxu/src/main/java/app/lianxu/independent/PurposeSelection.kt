package app.lianxu.independent

import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PurposeSelection(state: ModelConfigState, only: ModelPurpose? = null) {
    var selecting by remember { mutableStateOf<ModelPurpose?>(null) }
    val purposes = only?.let(::listOf) ?: listOf(ModelPurpose.CHAT, ModelPurpose.REVIEW, ModelPurpose.MEMORY, ModelPurpose.EMBEDDING)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (only == null) Text("所有角色共用下面的模型分工；标“可选”的项目不单独选时，会自动沿用聊天模型。",
            style = MaterialTheme.typography.bodySmall, color = Muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)) {
            if (only == ModelPurpose.AUX_VISION) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("辅助看图", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(state.auxiliaryVision, state::changeAuxiliaryVision, enabled = !state.busy, modifier = Modifier.testTag("auxiliary-vision-switch"))
            }
            purposes.forEachIndexed { index, purpose ->
                val chosen = state.configs.firstOrNull { it.id == state.purposes[purpose] }
                val description = when (purpose) {
                    ModelPurpose.CHAT -> "日常对话与主动发消息"
                    ModelPurpose.REVIEW -> "后台整理当下心情与相处习惯，建议用快模型"
                    ModelPurpose.MEMORY -> "把长对话整理成长期记忆存档"
                    ModelPurpose.EMBEDDING -> "帮助更准确地翻找旧记忆，不配也能正常用"
                    ModelPurpose.AUX_VISION -> if (state.auxiliaryVision) "用辅助模型看图，再交聊天模型" else "直接把图片发给聊天模型"
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Button) { selecting = purpose }
                    .testTag("purpose-select-${purpose.key}").padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    DirectoryBadge(if (purpose == ModelPurpose.AUX_VISION) "tools" else purpose.key)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(purpose.title.substringBefore("（"), style = MaterialTheme.typography.titleSmall)
                            if (purpose != ModelPurpose.CHAT) Text("可选", Modifier.background(Chrome, RoundedCornerShape(8.dp)).padding(horizontal=5.dp),
                                style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                        Text(description, style = MaterialTheme.typography.bodySmall, color = Muted)
                        val shape = RoundedCornerShape(8.dp)
                        Text(chosen?.purposeLabel() ?: if (purpose.inheritsChat()) "沿用聊天模型" else "未选择 · 点击添加",
                            if (chosen == null) Modifier else Modifier.background(Chrome, shape).border(.5.dp, Line, shape).padding(horizontal=8.dp, vertical=3.dp),
                            style = MaterialTheme.typography.bodySmall, color = if (chosen == null) Muted else Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", color = Muted)
                }
                if (index < purposes.lastIndex) HorizontalDivider(Modifier.padding(start=64.dp,end=16.dp), thickness=.5.dp, color=Line)
            }
        }
    }
    selecting?.let { purpose ->
        var includeUnknown by remember(purpose) { mutableStateOf(false) }
        val id = state.purposes[purpose].orEmpty()
        val options = purposeOptions(state.configs, purpose, id, includeUnknown, state.hostPurposes)
        fun choose(next: String) { selecting = null; state.selectPurpose(purpose, next) }
        ModalBottomSheet(onDismissRequest = { selecting = null }, containerColor = Paper,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=UiSpace.page).padding(bottom=16.dp)) {
                Text(purpose.title.substringBefore("（"), style = MaterialTheme.typography.titleLarge)
                PurposeOption(if (purpose.inheritsChat()) "沿用聊天模型" else "不选择", id.isEmpty(), "purpose-clear-${purpose.key}") { choose("") }
                options.visible.forEach { config ->
                    PurposeOption(config.purposeLabel(), id == config.id, "purpose-option-${purpose.key}-${config.id}") { choose(config.id) }
                }
                if (options.hasAdditionalUnknown) PurposeOption("其他已保存配置（能力未核实）", false, "purpose-unknown-embedding") { includeUnknown = true }
            }
        }
    }
}

private fun ModelPurpose.inheritsChat() = this == ModelPurpose.REVIEW || this == ModelPurpose.MEMORY

@Composable private fun PurposeOption(label: String, selected: Boolean, tag: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role=Role.Button, onClick=click).testTag(tag)
        .heightIn(min=UiSpace.action).padding(8.dp), verticalAlignment=Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color=if (selected) ConfigSuccess else Ink)
        if (selected) Text("✓", color=ConfigSuccess)
    }
}

internal fun ModelConfig.purposeLabel() = if (id.isHostModel()) "$name · 来自 NekoBot · $modelId" else "$name · $modelId"
