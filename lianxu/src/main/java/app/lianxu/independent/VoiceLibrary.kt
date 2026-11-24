package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VoiceLibrary(
    voices: List<SavedVoice>, connected: Boolean, busy: Boolean, refreshing: Boolean,
    playing: Boolean, status: String, back: () -> Unit, onRefresh: () -> Unit,
    onPlay: (SavedVoice) -> Unit, onStop: () -> Unit, onEdit: (SavedVoice) -> Unit,
    onCreate: (clone: Boolean) -> Unit, readLine: () -> String, saveLine: (String) -> Unit
) {
    var createMenu by remember { mutableStateOf(false) }
    var editLine by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Chrome).testTag("voice-studio-page")) {
        AppTopBar("AI 语音", back) {
            Box {
                Button({ createMenu = true }, enabled = !busy) { Text("＋ 创建") }
                DropdownMenu(createMenu, { createMenu = false }) {
                    DropdownMenuItem({ Text("文字设计") }, { createMenu = false; onCreate(false) })
                    DropdownMenuItem({ Text("录音克隆") }, { createMenu = false; onCreate(true) })
                }
            }
        }
        PullToRefreshBox(refreshing, { if (!busy) onRefresh() }, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("voice-library-list"), contentPadding = PaddingValues(UiSpace.page)) {
                item { Text("BreezeBlue", color = Muted, style = MaterialTheme.typography.bodyMedium) }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("我的音色", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        FilledTonalButton({ editLine = true }) { Text("试听台词") }
                    }
                }
                if (!connected || voices.isEmpty()) item {
                    Text(if (connected) "还没有保存的音色" else "请先在「大模型配置 → 语音」连接 BreezeBlue。",
                        Modifier.padding(vertical = 16.dp), color = Muted)
                }
                itemsIndexed(voices, key = { _, voice -> voice.id }) { index, voice ->
                    val top = if (index == 0) UiSpace.radius else 0.dp
                    val bottom = if (index == voices.lastIndex) UiSpace.radius else 0.dp
                    Column(Modifier.background(Paper, RoundedCornerShape(top, top, bottom, bottom)).padding(horizontal = 10.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ onPlay(voice) }, enabled = connected && !busy,
                                modifier = Modifier.semantics { contentDescription = "试听${voice.name}" }) {
                                Box(Modifier.size(30.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                                    Text("▶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                            Text(voice.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall)
                            TextButton({ onEdit(voice) }, enabled = connected && !busy) { Text("改音色") }
                        }
                        if (index < voices.lastIndex) HorizontalDivider(color = Line)
                    }
                }
                if (playing) item { TextButton(onStop) { Text("停止播放") } }
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
                    TextButton({ saveLine(draft); editLine = false }) { Text("完成") }
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
