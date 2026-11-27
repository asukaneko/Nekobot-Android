package app.lianxu.independent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Result tone appears softly; the real label, content and reply never wait for it. */
@Composable internal fun ChatRecallReceipt(group:RecallReceiptGroup,modifier:Modifier=Modifier,loadContents:(suspend(List<ChatToolActivity>)->List<RecallContentPart>)?=null,first:Boolean=true,last:Boolean=true){
    val attempt=group.items.first().attempt
    var expanded by remember(group.id,attempt){mutableStateOf(false)}
    val contents=if(expanded)key(group.items,loadContents){produceState<List<RecallContentPart>?>(null){
        value=try{loadContents?.invoke(group.items)?:unavailableRecallContents(group.items)}
        catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){unavailableRecallContents(group.items)}
    }}.value else null
    val kind = when {
        group.items.any { it.state in setOf("running", "staged") } -> TimelineMarkerKind.TOOL_LIVE
        group.items.any { it.recallOutcome() == RecallDisplayOutcome.ERROR } -> TimelineMarkerKind.TOOL_FAILURE
        group.succeeded -> TimelineMarkerKind.TOOL_SUCCESS
        else -> TimelineMarkerKind.TOOL_NEUTRAL
    }
    val foreground=if(kind==TimelineMarkerKind.TOOL_LIVE) Muted else kind.tone()
    val reveal=chatCapsuleReveal(toolCapsuleKey(group.items.first(),LocalChatCapsuleOwner.current),group.items.none{it.state in setOf("running","staged")})
    ChatTimelineNode("tool-${group.id}",kind,modifier,first,last){
    Column(Modifier.testTag("recall-group-${group.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Box(Modifier.widthIn(max=280.dp).testTag("tool-result-${group.id}")){
            TimelineToolCapsule(group.items.first().tool,expanded,foreground,Modifier.testTag("recall-toggle-${group.id}")
                .semantics{contentDescription=if(expanded)"收起回想结果"else"查看回想结果";stateDescription=if(expanded)"已展开"else"已收起"},
                toggle={expanded=!expanded}){
                ChatCapsuleLabel(group.label(),reveal,MaterialTheme.typography.labelSmall.copy(fontSize=13.sp,lineHeight=18.sp),foreground,Modifier.weight(1f,fill=false),"让我想一想")
            }
        }
        if(expanded)Surface(Modifier.widthIn(max=280.dp).testTag("recall-results-${group.id}"),shape=RoundedCornerShape(14.dp),color=Paper,border=BorderStroke(.5.dp,Line)){
            Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(contents==null)Text("正在读取…",style=MaterialTheme.typography.bodyMedium,color=Muted)
                contents?.forEach{part->when(part){
                    is RecallContentPart.Text->Text(part.value,style=MaterialTheme.typography.bodyMedium,color=Ink)
                    is RecallContentPart.Status->Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(part.value,style=MaterialTheme.typography.bodyMedium,color=foreground)
                        if(part.detail.isNotBlank())Text(part.detail,style=MaterialTheme.typography.bodySmall,color=Muted)
                    }
                    RecallContentPart.Deleted->Text("内容已删除",style=MaterialTheme.typography.bodyMedium,color=Muted)
                    RecallContentPart.Unavailable->Text("详情不可用",style=MaterialTheme.typography.bodyMedium,color=Muted)
                }}
            }
        }
    }
    }
}
