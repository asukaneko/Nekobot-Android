package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize

@Composable
internal fun OrderedAssistantBubbles(parts:List<ReplyPart>,turn:ChatTurn,body:String,playback:ChatVoicePlayback,
    shape:Shape,highlight:Color,manage:()->Unit,visible:(Rect)->Boolean) {
    val context=LocalContext.current
    val fileFor=LocalChatVoiceFile.current
    val revision by ChatVoiceDelivery.revision.collectAsState()
    val pending by ChatVoiceDelivery.pending.collectAsState()
    parts.forEachIndexed { index, part ->
        key(turn.id,turn.attempt,index) {
            val id=remember(body,part.voice){if(part.voice) OrderedChatReply.audioKey(turn,body,index) else null}
            val file=remember(id,revision,fileFor,context){id?.let{fileFor(context,it)}?.takeIf{it.isFile}}
            var bounds by remember { mutableStateOf(Rect.Zero) }
            Box(Modifier.onGloballyPositioned { bounds=Rect(it.localToWindow(Offset.Zero),it.size.toSize()) }) {
                when {
                    file!=null -> ChatVoiceBubble(file,part.text,playback,manage) { status,reason ->
                        ObserveVoiceRow(turn,status,reason,index){ visible(bounds) }
                    }
                    id!=null && id in pending -> Text("正在合成语音…",style=MaterialTheme.typography.bodySmall,color=Muted)
                    else -> {
                        if(part.voice && ChatVoiceDiagnostics.known(turn,index))
                            ObserveVoiceRow(turn,VoiceStatus.TEXT_RENDERED,null,index){ visible(bounds) }
                        Text(part.text,Modifier.testTag("reply-part-$index").clip(shape).background(ChatRoleBubble)
                            .background(highlight).combinedClickable(onClick={},onLongClick=manage).padding(UiSpace.bubble),
                            style=MaterialTheme.typography.bodyLarge,color=ChatRoleText)
                    }
                }
            }
        }
    }
}
