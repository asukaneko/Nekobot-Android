package app.lianxu.independent

import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VoiceLibrary(
    voices: List<SavedVoice>, connected: Boolean, busy: Boolean, refreshing: Boolean,
    playingVoiceId: String?, status: String, back: () -> Unit, onRefresh: () -> Unit,
    onPlay: (SavedVoice) -> Unit, onStop: () -> Unit, onEdit: (SavedVoice) -> Unit,
    onCreate: (clone: Boolean) -> Unit, readLine: () -> String, saveLine: (String) -> Unit
) {
    var currentLine by remember { mutableStateOf(readLine()) }
    var editLine by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Chrome).testTag("voice-studio-page")) {
        AppTopBar("音色库", back) {
            if (connected) TextButton(onRefresh, enabled = !busy && !refreshing, modifier = Modifier.testTag("voice-library-refresh")) {
                Text("刷新", color = GreenText)
            } else Spacer(Modifier.width(48.dp))
        }
        PullToRefreshBox(refreshing, { if (connected && !busy && !refreshing) onRefresh() }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("voice-library-list"), contentPadding = PaddingValues(UiSpace.page),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.voicePaper(14).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("BreezeBlue", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(6.dp))
                        Row(
                            Modifier.background(if (connected) ConfigSuccess.copy(alpha = .14f) else Chrome, CircleShape).padding(horizontal=7.dp, vertical=3.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (connected) Spacer(Modifier.size(6.dp).background(ConfigSuccess, CircleShape))
                            Text(if (connected) "已连接" else "未连接", style = MaterialTheme.typography.labelSmall,
                                color = if (connected) ConfigSuccess else Muted)
                        }
                        Spacer(Modifier.weight(1f))
                        if (connected) Text("下拉可同步云端音色", style = MaterialTheme.typography.bodySmall, color = Muted)
                        else TextButton(back) { Text("返回去连接 ›", color = GreenText) }
                    }
                }
                item { SectionLabel(if (connected && voices.isNotEmpty()) "我的音色 · ${voices.size}" else "我的音色") }
                if (connected && voices.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { editLine = true }.testTag("voice-audition-line-open").padding(12.dp)) {
                        Text("试听句：「$currentLine」", Modifier.weight(1f), maxLines=1, overflow=TextOverflow.Ellipsis,
                            style=MaterialTheme.typography.bodySmall)
                        Text("改台词", color=GreenText, style=MaterialTheme.typography.labelMedium)
                    }
                }
                if (!connected || voices.isEmpty()) item {
                    Column(Modifier.voicePaper(14).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (connected) "还没有保存的音色" else "请先连接 BreezeBlue", color = Ink)
                        Text(if (connected) "从下方创建，或下拉同步云端音色" else "返回语音设置，点「连接」即可",
                            color=Muted, style=MaterialTheme.typography.bodySmall)
                    }
                }
                if (connected && voices.isNotEmpty()) item {
                    Column(Modifier.voicePaper(18)) {
                        voices.forEachIndexed { index, voice ->
                            val active = voice.id == playingVoiceId
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Line)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable(enabled = !busy) { if (active) onStop() else onPlay(voice) }
                                    .semantics { contentDescription = if (active) "停止试听${voice.name}" else "试听${voice.name}" }
                                    .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    DirectoryBadge(if (active) "playing" else "play")
                                    Column(Modifier.padding(horizontal = 10.dp)) {
                                        Text(voice.name, style = MaterialTheme.typography.titleSmall,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(if (active) "正在试听… 轻触停止" else "轻触试听声线",
                                            color = if (active) ConfigSuccess else Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Box(Modifier.clip(CircleShape).border(.5.dp, Line, CircleShape)
                                    .clickable(enabled = !busy, role = Role.Button) { onEdit(voice) }
                                    .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text("微调", color = if (busy) Muted else Ink, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                item { SectionLabel("新建音色") }
                item {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        listOf(false,true).forEach { clone ->
                            val enabled = connected && !busy
                            val icon = if (clone) "clone" else "design"
                            Column(Modifier.weight(1f).fillMaxHeight().alpha(if (enabled) 1f else .55f).voicePaper(16)
                                .clickable(enabled=enabled) { onCreate(clone) }.testTag("voice-create-$icon").padding(12.dp),
                                verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                DirectoryBadge(icon)
                                Text(if (clone) "录音克隆" else "文字设计", style=MaterialTheme.typography.titleSmall)
                                Text(if (clone) "上传 3 秒以上人声录音，复刻音色" else "用几句话描述想要的声音气质，直接生成",
                                    style=MaterialTheme.typography.bodySmall, color=Muted)
                            }
                        }
                    }
                }
                item { Text("怎么让角色开口说话？音色准备好后，点开角色聊天 → 右上角角色资料 → 语音设置，开启开关并选定这里的音色即可。",
                    style=MaterialTheme.typography.bodySmall, color=Muted) }
                if (busy && !refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (status.isNotEmpty()) item { Text(status, color = Muted) }
            }
        }
    }
    if (editLine) {
        var draft by rememberSaveable { mutableStateOf(readLine()) }
        ModalBottomSheet(onDismissRequest = { editLine = false }, containerColor = Paper) {
            Column(Modifier.fillMaxWidth().padding(horizontal = UiSpace.page).padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("试听台词", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton({ saveLine(draft); currentLine = readLine(); editLine = false }) { Text("完成") }
                }
                OutlinedTextField(draft, { value ->
                    val count = value.codePointCount(0, value.length)
                    draft = if (count <= BreezeBlueSpeechProvider.MAX_TEXT) value
                        else value.substring(0, value.offsetByCodePoints(0, BreezeBlueSpeechProvider.MAX_TEXT))
                }, Modifier.fillMaxWidth().testTag("voice-audition-line").semantics { contentDescription = "试听台词输入框" },
                    minLines = 2, maxLines = 4)
                TextButton({ draft = BreezeVoiceStudio.DEFAULT_LINE }) { Text("恢复默认") }
            }
        }
    }
}

@Composable private fun Modifier.voicePaper(radius: Int): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return fillMaxWidth().clip(shape).background(Paper).border(.5.dp, Line, shape)
}
