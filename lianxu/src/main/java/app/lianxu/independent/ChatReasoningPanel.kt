package app.lianxu.independent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One piece of the live thinking stream: model text, or the receipt of a tool called inside it. */
internal sealed interface ChatThinkingPart {
    data class Text(val text:String):ChatThinkingPart
    data class Tool(val item:ChatToolActivity):ChatThinkingPart
}
/** Splits accumulated thinking at the recorded receipts. Null means "no receipt yet": one text block. */
internal fun chatThinkingParts(text:String,breaks:List<ChatThinkingBreak>,tools:List<ChatToolActivity>):List<ChatThinkingPart>? {
    if(breaks.isEmpty())return null
    val byId=tools.associateBy{it.id}
    val out=mutableListOf<ChatThinkingPart>();var start=0
    for(b in breaks.sortedBy{it.at}){
        // A superseded pill is not shown, so it does not split the thinking either.
        if(byId[b.toolId]?.state==ChatToolActivity.SUPERSEDED)continue
        val at=b.at.coerceIn(start,text.length)
        if(at>start)out+=ChatThinkingPart.Text(text.substring(start,at))
        byId[b.toolId]?.let{out+=ChatThinkingPart.Tool(it)}
        start=at
    }
    if(start<text.length)out+=ChatThinkingPart.Text(text.substring(start))
    return out.takeIf{parts->parts.any{it is ChatThinkingPart.Tool}}
}

/** Plain message thinking without tool receipts: one independent timeline entry. */
@Composable internal fun ChatReasoningPanel(owner:String,text:String,inProgress:Boolean,modifier:Modifier=Modifier,defaultExpanded:Boolean=false){
    if(text.isBlank())return
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
        ChatThinkingCard(owner,text,inProgress,Modifier.fillMaxWidth(),defaultExpanded)
    }
}

/**
 * One entry per real text segment or tool receipt, in its recorded order. When the model returns
 * no text after a call, no second thinking entry is invented.
 */
@Composable internal fun ChatThinkingStream(owner:String,text:String,inProgress:Boolean,breaks:List<ChatThinkingBreak>,tools:List<ChatToolActivity>,modifier:Modifier=Modifier,loadMemory:(suspend(String)->StoredMemory?)?=null,loadRecall:(suspend(List<ChatToolActivity>)->List<RecallContentPart>)?=null){
    val parts=chatThinkingParts(text,breaks,tools)
    if(parts==null){ChatReasoningPanel(owner,text,inProgress,modifier);return}
    val visible=parts.filter{it !is ChatThinkingPart.Text||it.text.isNotBlank()}
    var textIndex=0
    Column(modifier.testTag("thinking-stream-$owner")){
        visible.forEachIndexed{index,part->
            when(part){
                is ChatThinkingPart.Text->if(part.text.isNotBlank()){
                    ChatThinkingCard("$owner-$textIndex",part.text,inProgress&&index==visible.lastIndex,defaultExpanded=true,first=index==0,last=index==visible.lastIndex)
                    textIndex++
                }
                is ChatThinkingPart.Tool->ChatToolReceipt(part.item,loadMemory=loadMemory,loadRecall=loadRecall,first=index==0,last=index==visible.lastIndex)
            }
        }
    }
}

/** One collapsible piece of native model text. Never creates a message, a memory or a source. */
@Composable private fun ChatThinkingCard(owner:String,text:String,inProgress:Boolean,modifier:Modifier=Modifier,defaultExpanded:Boolean=false,first:Boolean=true,last:Boolean=true){
    if(text.isBlank())return
    // Interleaved cards stay expanded across the live→done switch; a user's collapse is kept.
    // The legacy single card keeps its old behavior (collapses again when the reply finishes).
    var expanded by rememberSaveable(owner,if(defaultExpanded)0 else inProgress,defaultExpanded){mutableStateOf(inProgress||defaultExpanded)}
    val faint=Muted.copy(alpha=.82f)
    ChatTimelineNode("thinking-$owner",modifier,first,last){
    Column(Modifier.testTag("thinking-card-$owner"),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(Modifier.fillMaxWidth().clickable(role=Role.Button){expanded=!expanded}.testTag("thinking-toggle-$owner")
            .semantics{contentDescription=if(expanded)"收起思考过程"else"展开思考过程";stateDescription=if(expanded)"已展开"else"已收起"}
            .padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            Text("思考内容",style=MaterialTheme.typography.labelSmall.copy(fontSize=13.sp,lineHeight=18.sp),color=faint)
            Box(Modifier.rotate(if(expanded)0f else 180f)){ControlIcon("up",faint,14.dp)}
        }
        if(expanded){
            Box(Modifier.fillMaxWidth().heightIn(max=220.dp).testTag("thinking-scroll-$owner")){
                SelectionContainer{
                    Text(text,Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("thinking-text-$owner"),style=MaterialTheme.typography.bodySmall,color=faint)
                }
            }
        }
    }
    }
}

/** [visible]: how many reply bubbles have been let out so far; the rest wait their turn (she is still typing). */
@Composable internal fun ChatReplyDraft(preview:ChatReplyPreview?,turn:ChatTurn,modifier:Modifier=Modifier,visible:Int=Int.MAX_VALUE,viewport:androidx.compose.ui.geometry.Rect=androidx.compose.ui.geometry.Rect.Zero,loadRecall:(suspend(List<ChatToolActivity>)->List<RecallContentPart>)?=null,onShown:(Int)->Unit={}){
    // Never display an old attempt after retry, cancellation, or a role/page switch.
    val current=preview?.takeIf{turn.state==TurnState.PENDING&&it.turnId==turn.id&&it.attempt==turn.attempt}
    val owner="${turn.id}-${turn.attempt}"
    val tools=current?.tools
    val items=tools?:turn.toolActivities
    // Once there is thinking to cut into, receipts move out of the card and into the chat flow.
    val interleaved=current?.let{it.reasoning.isNotBlank()&&chatThinkingParts(it.reasoning,it.thinkingBreaks,tools.orEmpty())!=null}==true
    Column(modifier.testTag("reply-preview-$owner"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        ChatToolActivities(items,Modifier.fillMaxWidth(),capsules=!interleaved,loadRecall=loadRecall)
        current?.let{
            if(interleaved)ChatThinkingStream(owner,it.reasoning,it.phase!=ChatReplyPhase.REPLYING,it.thinkingBreaks,tools.orEmpty(),Modifier.fillMaxWidth(),loadRecall=loadRecall)
            else ChatReasoningPanel(owner,it.reasoning,it.phase!=ChatReplyPhase.REPLYING,Modifier.fillMaxWidth())
        }
        val shown=current?.bubbles?.take(visible).orEmpty()
        if(shown.isNotEmpty()){
            Column(Modifier.testTag("reply-draft-$owner").semantics(mergeDescendants=true){text=androidx.compose.ui.text.AnnotatedString(shown.joinToString("\n\n"))},verticalArrangement=Arrangement.spacedBy(5.dp)){
                shown.forEachIndexed{index,segment->
                    Surface(modifier=Modifier.testTag("bubble-draft-$owner-$index").onGloballyPositioned{
                        val bounds=androidx.compose.ui.geometry.Rect(it.localToWindow(androidx.compose.ui.geometry.Offset.Zero),androidx.compose.ui.geometry.Size(it.size.width.toFloat(),it.size.height.toFloat()))
                        if(viewport!=androidx.compose.ui.geometry.Rect.Zero&&bounds.overlaps(viewport))onShown(index+1)
                    },shape=RoundedCornerShape(topStart=6.dp,topEnd=18.dp,bottomStart=18.dp,bottomEnd=18.dp),color=ChatRoleBubble){
                        Text(segment,Modifier.padding(UiSpace.bubble).clearAndSetSemantics{},style=MaterialTheme.typography.bodyLarge,color=ChatRoleText)
                    }
                }
            }
        }
        Text(when(current?.phase){
            ChatReplyPhase.REPLYING->"正在回复…"
            ChatReplyPhase.THINKING->"正在接收回复…"
            else->"正在请求回复…"
        },Modifier.testTag("turn-state-${turn.id}"),style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}

/** One independently expandable tool event; stored states and content are unchanged. */
@Composable internal fun ChatToolReceipt(item:ChatToolActivity,modifier:Modifier=Modifier,loadMemory:(suspend(String)->StoredMemory?)?=null,loadRecall:(suspend(List<ChatToolActivity>)->List<RecallContentPart>)?=null,first:Boolean=true,last:Boolean=true){
    if(item.state==ChatToolActivity.SUPERSEDED)return
    ChatTimelineNode("tool-${item.id}",modifier,first,last){
    if(item.tool in ChatToolActivity.RECALL_TOOLS){
        ChatRecallReceipt(RecallReceiptGroup(listOf(item)),loadContents=loadRecall)
    }else{
    val canOpen=item.canOpenMemory&&loadMemory!=null
    val reveal=chatCapsuleReveal(toolCapsuleKey(item,LocalChatCapsuleOwner.current),item.state !in setOf("running","staged"))
    var expanded by remember(item.id,item.attempt){mutableStateOf(false)}
    var peek by remember(item.id){mutableStateOf<ChatMemoryPeek?>(null)}
    LaunchedEffect(expanded,item.memoryId,canOpen){
        if(!expanded||!canOpen)return@LaunchedEffect
        val id=item.memoryId?:return@LaunchedEffect
        peek=ChatMemoryPeek.Loading
        peek=try{memoryPeekOf(loadMemory?.invoke(id))}
        catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){ChatMemoryPeek.Unreadable}
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        val memoryWrite=item.tool=="agent_memory_update"
        val resultColor=when{
            item.state=="failed"->MaterialTheme.colorScheme.error
            item.state in setOf("running","staged","unknown")||(memoryWrite&&!item.canOpenMemory)->Muted
            else->GreenText
        }
        Box(Modifier.testTag("tool-result-${item.id}")){
        Row(Modifier.clickable(role=Role.Button){if(!expanded)peek=null;expanded=!expanded}
            .testTag("tool-receipt-toggle-${item.id}")
            .semantics{contentDescription=if(canOpen){if(expanded)"收起这条记忆"else"查看这条记忆"}else{if(expanded)"收起工具详情"else"查看工具详情"};stateDescription=if(expanded)"已展开"else"已收起"}
            .padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            ChatCapsuleLabel(item.label(),reveal,MaterialTheme.typography.labelSmall.copy(fontSize=13.sp,lineHeight=18.sp),resultColor,Modifier.weight(1f,fill=false),loading=if(memoryWrite)"正在记住"else"正在处理…")
            Box(Modifier.rotate(if(expanded)0f else 180f)){ControlIcon("up",resultColor,14.dp)}
        }
        }
        if(item.state in setOf("running","staged")&&item.detail.isNotBlank())Text(item.detail,style=MaterialTheme.typography.bodySmall,color=Muted)
        if(expanded){
            if(item.state !in setOf("running","staged")&&item.detail.isNotBlank())Text(item.detail,Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall,color=Muted)
            if(canOpen)Surface(Modifier.widthIn(max=280.dp).testTag("memory-peek-${item.id}"),shape=RoundedCornerShape(14.dp),color=Paper,border=BorderStroke(.5.dp,Line)){
                Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    memoryPeekHeader(peek)?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=Muted)}
                    when(val shown=peek){
                        null,ChatMemoryPeek.Loading->Text("正在读取…",style=MaterialTheme.typography.bodySmall,color=Muted)
                        ChatMemoryPeek.Deleted->Text("这条记忆已删除",style=MaterialTheme.typography.bodyMedium,color=Muted)
                        ChatMemoryPeek.Unreadable->Text("暂时读不到这条记忆",style=MaterialTheme.typography.bodyMedium,color=Muted)
                        is ChatMemoryPeek.Saved->Text(shown.text,style=MaterialTheme.typography.bodyMedium,color=Ink)
                    }
                }
            }else if(item.detail.isBlank())Text(if(item.state in setOf("running","staged"))"正在处理…"else"暂无可查看的详情",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
    }
    }
}

/** What one receipt's stored row looks like right now. Read from the database, never from tool arguments. */
internal sealed interface ChatMemoryPeek {
    data object Loading:ChatMemoryPeek
    data class Saved(val text:String):ChatMemoryPeek
    data object Deleted:ChatMemoryPeek
    data object Unreadable:ChatMemoryPeek
}
/** 已删的只显示「这条记忆已删除」，不带「已保存」抬头（11-09 定的规则）。 */
internal fun memoryPeekHeader(peek:ChatMemoryPeek?):String? = if(peek==ChatMemoryPeek.Deleted) null else "已保存"
/** A missing row is honestly "deleted"; the card never falls back to the tool arguments or the reply. */
internal fun memoryPeekOf(saved:StoredMemory?):ChatMemoryPeek = when{
    saved==null||saved.userDeleted->ChatMemoryPeek.Deleted
    saved.text.isBlank()->ChatMemoryPeek.Unreadable
    else->ChatMemoryPeek.Saved(saved.text)
}

@Composable internal fun ChatToolActivities(items:List<ChatToolActivity>,modifier:Modifier=Modifier,capsules:Boolean=true,loadMemory:(suspend(String)->StoredMemory?)?=null,loadRecall:(suspend(List<ChatToolActivity>)->List<RecallContentPart>)?=null){
    if(items.isEmpty())return
    Column(modifier){
        items.forEachIndexed{index,item->
            if(item.before.isNotBlank())Surface(shape=RoundedCornerShape(topStart=6.dp,topEnd=18.dp,bottomStart=18.dp,bottomEnd=18.dp),color=ChatRoleBubble){
                Text(item.before,Modifier.padding(UiSpace.bubble).testTag("tool-before-${item.id}"),style=MaterialTheme.typography.bodyLarge,color=ChatRoleText)
            }
            if(capsules)ChatToolReceipt(item,loadMemory=loadMemory,loadRecall=loadRecall,first=index==0,last=index==items.lastIndex)
        }
    }
}
