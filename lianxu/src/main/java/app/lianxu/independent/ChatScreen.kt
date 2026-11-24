package app.lianxu.independent

import android.content.Context
import java.io.File
import kotlinx.coroutines.ensureActive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val LocalChatVoiceFile = staticCompositionLocalOf<(Context, String) -> File> { ChatVoiceDelivery::file }

private suspend fun LazyListState.showEndOf(index:Int){
    scrollToItem(index)
    val info=layoutInfo
    val item=info.visibleItemsInfo.lastOrNull{it.index==index}?:return
    val overflow=item.offset+item.size-(info.viewportEndOffset-info.afterContentPadding)
    if(overflow>0)scrollBy(overflow.toFloat())
}

/** Centered history state for a committed memory batch; one line, wraps instead of clipping at large font scales. */
@Composable internal fun MemorySummaryPill(pill:MemoryPill,onClick:(()->Unit)?=null){
    val shape=RoundedCornerShape(50)
    val reveal=chatCapsuleReveal(memoryCapsuleKey(pill))
    Row(Modifier.fillMaxWidth().padding(vertical=2.dp).testTag("memory-pill-${pill.batchId}"),horizontalArrangement=Arrangement.Center){
        Row(Modifier.widthIn(max=280.dp).clip(shape).background(Paper).border(.5.dp,Line,shape).then(if(onClick==null)Modifier else Modifier.clickable(role=Role.Button,onClick=onClick)).padding(horizontal=12.dp,vertical=6.dp).testTag("memory-pill-box-${pill.batchId}"),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            ControlIcon("memory",androidx.compose.ui.graphics.lerp(Muted,GreenText,reveal),16.dp)
            ChatCapsuleLabel(pill.text,reveal,MaterialTheme.typography.labelMedium,GreenText,loading="正在整理…",textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable internal fun ObserveVoiceRow(turn:ChatTurn, status:VoiceStatus, reason:VoiceReason?, part:Int?=null, visible:()->Boolean) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(turn.id,turn.attempt,part,status,reason,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { currentVisible() }.first { it }
            ChatVoiceDiagnostics.shown(turn,status,reason,part)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun Chat(vm:AppState){
    val friend=vm.friends.find{it.id==vm.activeId}?:return
    var memoryBatch by remember(friend.id){mutableStateOf<String?>(null)}
    memoryBatch?.let{batch->ChatMemoryPillDetails(vm,friend.id,batch){memoryBatch=null}}
    val parameterCards=rememberParameterCards(vm,friend.id)
    // Pending prefixes are durable already, but the attempt preview owns their one visible copy.
    val pendingBodies=vm.turns.filter{it.friendId==friend.id&&it.state==TurnState.PENDING&&vm.chatPreview?.let{p->p.turnId==it.id&&p.attempt==it.attempt}==true}.mapNotNull{it.assistantMessageId}.toSet()
    val messages=vm.messages.filter{it.friendId==friend.id&&it.id !in pendingBodies}
    val latestRoleMessageId=messages.lastOrNull{!it.mine}?.id
    val pills=vm.memoryPills.filter{it.friendId==friend.id}
    val timeline=remember(messages,pills){chatTimeline(messages,pills)}
    val openTurn=vm.turns.lastOrNull{it.friendId==friend.id}?.takeIf{it.state!=TurnState.COMPLETED}
    val preview=vm.chatPreview?.takeIf{openTurn?.state==TurnState.PENDING&&it.turnId==openTurn.id&&it.attempt==openTurn.attempt}
    val capsuleFrames=buildMap{
        vm.turns.filter{it.friendId==friend.id}.forEach{turn->
            putAll(chatCapsuleFrames(if(preview?.turnId==turn.id)preview.tools else turn.toolActivities,emptyList(),turn.id))
        }
        putAll(chatCapsuleFrames(emptyList(),pills))
    }
    val capsuleMotion=remember(friend.id){ChatCapsuleMotionTracker(capsuleFrames)}
    val durationScale=rememberCoroutineScope().coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor?:1f
    val capsuleUpdate=capsuleMotion.prepare(capsuleFrames,capsuleMotionNow(),durationScale)
    SideEffect{capsuleMotion.commit(capsuleUpdate)}
    val list=rememberLazyListState()
    val jump = remember(friend.id) { vm.memoryChatRequest.consume() }
    var highlighted by remember(friend.id) { mutableStateOf(false) }
    val highlightAlpha by androidx.compose.animation.core.animateFloatAsState(if(highlighted) .22f else 0f, label="memory-highlight")
    val highlightIds = remember(jump,vm.turns) { vm.turns.firstOrNull { it.friendId==friend.id && it.userMessageId==jump?.turnId }?.let { setOf(it.userMessageId,it.assistantMessageId) }.orEmpty() }
    LaunchedEffect(jump){
        if(jump!=null){
            val index=timeline.indexOfFirst{it is ChatRow.Group && it.value.messages.any{m->m.id==jump.turnId}}
            if(index>=0){
                withFrameNanos{}
                list.scrollToItem(index,-(list.layoutInfo.viewportEndOffset/3))
                highlighted=true
                kotlinx.coroutines.delay(1500)
                highlighted=false
            }
        }
    }
    val draftState=rememberSaveable(friend.id,saver=androidx.compose.runtime.saveable.listSaver(
        save={state:ChatDraftState->listOf(state.text.value,state.version.longValue)},
        restore={saved->vm.chatDrafts.getOrPut(friend.id){ChatDraftState(saved[0] as String,saved[1] as Long)}}
    )){vm.chatDrafts.getOrPut(friend.id){ChatDraftState()}}
    var draft by draftState.text
    var draftVersion by draftState.version
    var submittedVersion by draftState.submittedVersion
    var persistedVersion by draftState.persistedVersion
    var submittedAt by draftState.submittedAt
    var lastSeen by rememberSaveable(friend.id){mutableStateOf<Long?>(null)}
    var selectedImage by draftState.image
    var importingImage by draftState.importing
    var imageError by remember(friend.id){mutableStateOf("")}
    var viewedImage by remember(friend.id){mutableStateOf<ChatMediaPreview?>(null)}
    val pendingVoice by ChatVoiceDelivery.pending.collectAsState()
    val voicePlayback = rememberChatVoicePlayback()
    val voiceFileFor = LocalChatVoiceFile.current
    val voiceRevision by ChatVoiceDelivery.revision.collectAsState()
    val context=LocalContext.current
    val imageScope=rememberCoroutineScope()
    val inputFocus=remember(friend.id){FocusRequester()}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->
        val selectedFor=vm.imagePickerRole;vm.imagePickerRole=null
        if(uri!=null&&selectedFor==friend.id&&!importingImage){
            importingImage=true
            imageScope.launch{var candidate:PendingChatImage?=null;try{
                withContext(kotlinx.coroutines.Dispatchers.IO){
                    val video=VideoPolicy.isVideo(context.contentResolver.getType(uri).orEmpty())
                    val extract=video&&ToolboxPreferences.videoMode(vm.processing.store)==ToolboxPreferences.VideoMode.FRAMES
                    val jobContext=kotlinx.coroutines.currentCoroutineContext()
                    candidate=context.contentResolver.openInputStream(uri)?.let{if(video)ChatVideos.prepare(it,context.cacheDir,extract){jobContext.ensureActive()}else ChatImages.prepare(it,context.cacheDir)}?:error("无法打开图片或视频")
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                selectedImage?.close();selectedImage=requireNotNull(candidate);candidate=null;draftVersion++;imageError=""
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){imageError=e.message?:"无法读取图片或视频，请重新选择"}
            finally{candidate?.close();importingImage=false}}

        }
    }
    var controlMessage by remember{mutableStateOf<Message?>(null)}
    var viewport by remember{mutableStateOf(Rect.Zero)}
    var focused by remember{mutableStateOf(false)}
    val focusManager=LocalFocusManager.current
    val keyboardController=LocalSoftwareKeyboardController.current
    fun openMedia(source:ImageSource,mime:String){
        if(VideoPolicy.isVideo(mime)){focusManager.clearFocus();keyboardController?.hide()}
        viewedImage=ChatMediaPreview(source,mime)
    }
    val imeVisible=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    var keyboardWasVisible by remember(friend.id){mutableStateOf(false)}
    LaunchedEffect(imeVisible,selectedImage){
        if(imeVisible)keyboardWasVisible=true
        else if(keyboardWasVisible){if(selectedImage!=null)focusManager.clearFocus();keyboardWasVisible=false}
    }
    val colors=MaterialTheme.colorScheme
    // A receipt's opened card reads the stored row as it is now; it never replays tool arguments or the reply.
    val memoryReader: suspend (String) -> StoredMemory? = remember(vm, friend.id) {
        { id -> vm.processing.read { db -> vm.processing.memory(db, friend.id, id) } }
    }
    val recallReader:suspend(List<ChatToolActivity>)->List<RecallContentPart> = remember(vm,friend.id,vm.processingRevision){
        {items->vm.processing.recallContents(friend.id,items)}
    }
    val time=remember{SimpleDateFormat("HH:mm",Locale.getDefault())}
    // The attempt owns delivered bubbles, so switching pages never releases an unseen tail.
    val pacedTurn=preview?.turnId
    var pacedShown by remember(friend.id,preview?.turnId,preview?.attempt){mutableIntStateOf(preview?.visible?:0)}
    LaunchedEffect(pacedTurn,preview?.attempt,preview?.bubbles?.size,preview?.visible,pacedShown){
        val p=preview?:return@LaunchedEffect
        if(pacedShown<p.visible)pacedShown=p.visible
        if(pacedShown<p.bubbles.size&&pacedShown<=p.visible){
            kotlinx.coroutines.delay(ReplyPacing.waitBefore(pacedShown,p.bubbles))
            pacedShown++
        }
    }
    val typing=vm.chatSending
    fun send(sent:String=draft,version:Long=draftVersion){if((sent.isNotBlank()||selectedImage!=null)&&!importingImage&&vm.chatInputAvailable&&
        (version>submittedVersion||version==submittedVersion&&!vm.chatSending&&version>persistedVersion)){
        val previous=submittedVersion
        submittedVersion=version
        if(vm.sendImage(sent,selectedImage){persistedVersion=maxOf(persistedVersion,version);if(draftVersion==version){draft="";selectedImage?.close();selectedImage=null}})submittedAt=android.os.SystemClock.uptimeMillis()
        else submittedVersion=previous
    }}
    LaunchedEffect(messages.lastOrNull()?.id,vm.notificationTarget){
        if(jump!=null && lastSeen==null){lastSeen=messages.lastOrNull()?.id;return@LaunchedEffect}
        val id=messages.lastOrNull()?.id
        val target=vm.notificationTarget?:if(lastSeen==null)vm.companionReceipts.firstOrNull{r->r.readAt==null&&messages.any{it.id==r.messageId}}?.messageId else null
        if(target!=null){val index=timeline.indexOfFirst{it is ChatRow.Group&&it.value.messages.any{m->m.id==target}};if(index>=0){list.scrollToItem(index);vm.notificationShown();lastSeen=id}}
        else if(id!=null&&id!=lastSeen&&timeline.isNotEmpty()){list.scrollToItem(timeline.lastIndex);lastSeen=id}
    }
    CompositionLocalProvider(LocalChatCapsuleMotion provides capsuleUpdate.events){
    Column(Modifier.fillMaxSize().background(ChatPageBackground)){
        Row(Modifier.fillMaxWidth().heightIn(min=68.dp).background(ChatBarBackground).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={vm.back()},modifier=Modifier.size(UiSpace.action).testTag("back").semantics{contentDescription="返回"}){ControlIcon("back",Ink)}
            Box(Modifier.weight(1f).padding(horizontal=8.dp,vertical=8.dp)) {
                val titleModifier = Modifier.testTag("chat-title")
                if (typing) {
                    TypingLabel(titleModifier, MaterialTheme.typography.titleMedium, "对方正在输入…", maxLines = 2)
                } else {
                    Text(friend.name,titleModifier,style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick={vm.open("detail",friend.id)},modifier=Modifier.size(UiSpace.action).testTag("chat-role-detail").semantics{contentDescription="角色资料"}){ControlIcon("more")}
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned{val p=it.localToWindow(Offset.Zero);viewport=Rect(p,it.size.toSize())}){
            val width=minOf(320.dp,maxWidth*.78f-32.dp)
            val historyHeight=maxHeight
            // The keyboard can finish opening after the turn state changes. Lazy items outside the shrunken viewport are disposed,
            // so the list itself keeps the latest unfinished turn's status and retry/cancel control at the bottom edge.
            LaunchedEffect(historyHeight,openTurn?.id,openTurn?.state,openTurn?.attempt,timeline.size){
                if(jump==null&&openTurn!=null&&timeline.isNotEmpty()){withFrameNanos{};list.showEndOf(timeline.lastIndex)}
            }
            LaunchedEffect(preview?.text?.length,preview?.phase,preview?.tools){
                if(preview!=null&&!list.isScrollInProgress&&list.layoutInfo.visibleItemsInfo.lastOrNull()?.index==timeline.lastIndex){
                    withFrameNanos{};list.showEndOf(timeline.lastIndex)
                }
            }
            LaunchedEffect(pacedShown){
                if(pacedTurn!=null&&!list.isScrollInProgress&&list.layoutInfo.visibleItemsInfo.lastOrNull()?.index==timeline.lastIndex&&timeline.isNotEmpty()){
                    withFrameNanos{};list.showEndOf(timeline.lastIndex)
                }
            }
            LazyColumn(Modifier.fillMaxSize().then(if(selectedImage==null)Modifier else Modifier.pointerInput(friend.id){detectTapGestures(onTap={focusManager.clearFocus();keyboardController?.hide()})}).testTag("chat-history"),state=list,contentPadding=PaddingValues(horizontal=UiSpace.page,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                items(timeline,key={it.key}){row->when(row){
                    is ChatRow.Date->{val date=LocalDate.ofEpochDay(row.day);Text(if(date==LocalDate.now())"今天" else date.format(DateTimeFormatter.ofPattern("M月d日")),Modifier.fillMaxWidth().padding(bottom=8.dp),style=MaterialTheme.typography.labelSmall,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
                    is ChatRow.Pill->MemorySummaryPill(row.value){memoryBatch=row.value.batchId}
                    is ChatRow.Group->{val group=row.value
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(group.mine)Arrangement.End else Arrangement.Start,verticalAlignment=Alignment.Top){
                            if(!group.mine){Avatar(friend,32);Spacer(Modifier.width(8.dp))}
                            Column(if(group.mine)Modifier.widthIn(max=width)else Modifier,horizontalAlignment=if(group.mine)Alignment.End else Alignment.Start,verticalArrangement=Arrangement.spacedBy(4.dp)){
                                group.messages.forEach{message->
                                    var bounds by remember(message.id){mutableStateOf(Rect.Zero)}
                                    val shape=RoundedCornerShape(topStart=if(!group.mine)6.dp else 18.dp,topEnd=if(group.mine)6.dp else 18.dp,bottomStart=18.dp,bottomEnd=18.dp)
                                    if(!message.mine){
                                        val turn=vm.turns.firstOrNull{it.assistantMessageId==message.id}
                                        val activities=turn?.toolActivities.orEmpty()
                                        // Receipts from the final attempt keep the thinking offset they were
                                        // first shown at; the stored reply carries the same accumulated text,
                                        // so the finished message shows thinking A, receipt, thinking B.
                                        val placed=placedReceipts(activities,turn?.attempt)
                                        val interleaved=placed.isNotEmpty()&&message.displayReasoning.isNotBlank()
                                        val outside=if(interleaved)activities.filterNot{it in placed} else activities
                                        CompositionLocalProvider(LocalChatCapsuleOwner provides turn?.id.orEmpty()){
                                        ChatToolActivities(outside,Modifier.width(width),loadMemory=memoryReader,loadRecall=recallReader)
                                        if(interleaved)ChatThinkingStream("message-${message.id}",message.displayReasoning,false,
                                            placed.sortedBy{it.reasoningAt}.map{ChatThinkingBreak(it.reasoningAt,it.id)},activities,Modifier.width(width),loadMemory=memoryReader,loadRecall=recallReader)
                                        else ChatReasoningPanel("message-${message.id}",message.displayReasoning,false,Modifier.width(width))
                                        }
                                    }
                                    val voiceTurn=if(message.mine)null else vm.turns.firstOrNull{it.assistantMessageId==message.id}
                                    val voiceId=voiceTurn?.let{ChatVoiceDelivery.key(friend.id,it.id,it.attempt,message.text)}
                                    val voiceFile=remember(voiceId,voiceRevision,context,voiceFileFor){voiceId?.let{voiceFileFor(context,it)}?.takeIf{it.isFile}}
                                    val ordered=remember(voiceTurn?.replyLayout,message.text){ OrderedChatReply.decode(message.text,voiceTurn?.replyLayout) }
                                    if(ordered!=null && voiceTurn!=null) {
                                        Column(Modifier.widthIn(max=width).onGloballyPositioned{val p=it.localToWindow(Offset.Zero);bounds=Rect(p,it.size.toSize())}.testTag("message-${message.id}"),verticalArrangement=Arrangement.spacedBy(5.dp)){
                                            OrderedAssistantBubbles(ordered,voiceTurn,message.text,voicePlayback,
                                                shape,colors.primary.copy(alpha=if(message.id in highlightIds)highlightAlpha else 0f),
                                                { controlMessage=message }) { rect -> vm.isReadingChat(friend.id) && viewedImage==null && controlMessage==null && !rect.isEmpty && rect.overlaps(viewport) }
                                        }
                                    } else if(voiceFile!=null) Box(Modifier.widthIn(max=width).onGloballyPositioned{val p=it.localToWindow(Offset.Zero);bounds=Rect(p,it.size.toSize())}.testTag("message-${message.id}")){ChatVoiceBubble(voiceFile,message.text,voicePlayback,manage={controlMessage=message}) { status,reason ->
                                        if(voiceTurn!=null) ObserveVoiceRow(voiceTurn,status,reason){vm.isReadingChat(friend.id) && viewedImage==null && controlMessage==null && !bounds.isEmpty && bounds.overlaps(viewport)}
                                    }}
                                    else if(voiceId!=null && voiceId in pendingVoice) Text("正在合成语音…",Modifier.combinedClickable(onClick={},onLongClick={controlMessage=message}),style=MaterialTheme.typography.bodySmall,color=Muted)
                                    else Column(Modifier.widthIn(max=width).onGloballyPositioned{val p=it.localToWindow(Offset.Zero);bounds=Rect(p,it.size.toSize())}.testTag("message-${message.id}").semantics(mergeDescendants=true){text=androidx.compose.ui.text.AnnotatedString(message.text)}.combinedClickable(onClick={},onLongClick={controlMessage=message}),verticalArrangement=Arrangement.spacedBy(5.dp)){
                                        if(voiceTurn!=null && ChatVoiceDiagnostics.known(voiceTurn))
                                            ObserveVoiceRow(voiceTurn,VoiceStatus.TEXT_RENDERED,null){vm.isReadingChat(friend.id) && viewedImage==null && controlMessage==null && !bounds.isEmpty && bounds.overlaps(viewport)}
                                        message.image?.let{ref->MessagePicture(vm,ref,onControl={controlMessage=message}){data->openMedia(data,ref.mime)}}
                                        val segments=if(message.text.isBlank())emptyList()else ChatBubbleSegments.forMessage(message.text,message.mine)
                                        segments.forEachIndexed{index,segment->
                                            Text(segment,Modifier.widthIn(max=width).clip(shape).background(if(group.mine)ChatUserBubble else ChatRoleBubble).background(colors.primary.copy(alpha=if(message.id in highlightIds)highlightAlpha else 0f)).padding(UiSpace.bubble).clearAndSetSemantics{testTag=if(highlighted && message.id in highlightIds)"chat-turn-highlight" else "bubble-${message.id}-$index"},style=MaterialTheme.typography.bodyLarge,color=if(group.mine)ChatUserText else ChatRoleText)
                                        }
                                    }
                                    if(message.mine)RelationshipParameterCard(vm,friend,message,parameterCards)
                                    if(!message.mine)CompanionReadMarker(vm,message,bounds,viewport,viewedImage==null&&controlMessage==null,message.id==latestRoleMessageId)
                                }
                                if(row.showTime)Text(time.format(Date(group.messages.last().created)),Modifier.padding(horizontal=4.dp),style=MaterialTheme.typography.labelSmall,color=Muted)
                            }
                            if(group.mine){Spacer(Modifier.width(8.dp));SelfAvatar(vm.userAvatar,32,"self-avatar-${group.messages.first().id}")}
                        }
                        group.messages.mapNotNull{message->vm.turns.firstOrNull{it.userMessageId==message.id&&it.state !in setOf(TurnState.COMPLETED,TurnState.SUPERSEDED)}}.forEach{turn->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){
                                Avatar(friend,32);Spacer(Modifier.width(8.dp))
                                CompositionLocalProvider(LocalChatCapsuleOwner provides turn.id){
                                if(turn.state==TurnState.PENDING)ChatReplyDraft(preview,turn,Modifier.width(width),if(pacedTurn==turn.id)pacedShown else 0,viewport,loadRecall=recallReader){count->vm.replyShown(friend.id,turn.id,turn.attempt,count)}
                                else Column(Modifier.width(width),verticalArrangement=Arrangement.spacedBy(8.dp)){if(turn.assistantMessageId==null)ChatToolActivities(turn.toolActivities,loadRecall=recallReader);ChatTurnControls(vm,turn)}
                                }
                            }
                        }
                        }
                    }
                }}
            }
        }
        selectedImage?.let{image->PendingPicture(image,focused,{inputFocus.requestFocus()},{openMedia(image.source,image.mime)},{selectedImage?.close();selectedImage=null;draftVersion++})}
        if(importingImage)Text("正在读取图片或视频…",Modifier.padding(horizontal=56.dp),style=MaterialTheme.typography.bodySmall,color=Muted)
        if(imageError.isNotBlank())Text(imageError,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        vm.foregroundChatRequest?.let { request -> key(request) { ChatNotificationHint(vm) } }
        Surface(modifier=Modifier.testTag("chat-input-bar"),color=ChatBarBackground,shadowElevation=0.dp){Column{
            HorizontalDivider(color=ChatInputBorder,thickness=.5.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                IconButton(onClick={vm.imagePickerRole=friend.id;picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))},enabled=!importingImage,modifier=Modifier.size(UiSpace.action).testTag("chat-attach").semantics{contentDescription="选择图片或视频"}){ControlIcon("plus")}
                val shape=RoundedCornerShape(28.dp)
                val interaction=remember{MutableInteractionSource()}
                val fieldColors=TextFieldDefaults.colors(focusedContainerColor=Paper,unfocusedContainerColor=Paper,disabledContainerColor=Paper,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,cursorColor=ChatSendButton)
                BasicTextField(draft,{draft=it.take(2000);draftVersion++},modifier=Modifier.weight(1f).padding(vertical=6.dp).heightIn(min=36.dp).focusRequester(inputFocus).onFocusChanged{focused=it.hasFocus}.border(if(focused)1.dp else .5.dp,ChatInputBorder,shape).testTag("message-input"),
                    textStyle=MaterialTheme.typography.bodyLarge.copy(color=colors.onSurface),maxLines=4,interactionSource=interaction,cursorBrush=SolidColor(ChatSendButton),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),decorationBox={inner->
                        TextFieldDefaults.DecorationBox(draft,inner,true,false,VisualTransformation.None,interaction,
                            placeholder={Text("说点什么…",style=MaterialTheme.typography.bodyLarge)},shape=shape,colors=fieldColors,contentPadding=PaddingValues(horizontal=16.dp,vertical=6.dp))
                    })
                val stopping=vm.chatSending&&draft.isBlank()&&selectedImage==null
                val enabled=stopping||(draft.isNotBlank()||selectedImage!=null)&&!importingImage&&vm.chatInputAvailable
                val dark=Paper.luminance()<.5f
                val actionColor=when{
                    stopping->if(dark)Color(0xFFE9B6B1)else Color(0xFF915753)
                    draft.isNotBlank()||selectedImage!=null->if(dark)Color(0xFFAFC9B5)else Color(0xFF4E7058)
                    else->Muted
                }
                Box(Modifier.size(width=60.dp,height=48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled=enabled,role=Role.Button){if(stopping){
                        // The second tap of Send must not become Stop when persistence clears the
                        // field. Only this newly converted Stop is guarded; a new draft sends now.
                        if(android.os.SystemClock.uptimeMillis()-submittedAt>android.view.ViewConfiguration.getDoubleTapTimeout())vm.stopReply(friend.id)
                    }else send()}
                    .testTag(if(stopping)"cancel-turn-${openTurn?.id.orEmpty()}"else"send-message")
                    .semantics{contentDescription=if(stopping)"停止回复"else"发送"},contentAlignment=Alignment.Center){
                    Box(Modifier.fillMaxWidth().heightIn(min=36.dp).clip(RoundedCornerShape(8.dp)).background(if(stopping){if(dark)Color(0xFF573A38)else Color(0xFFF1D6D2)}else if(enabled&&!stopping){if(dark)Color(0xFF35513F)else Color(0xFFD9F1DB)}else actionColor.copy(alpha=if(enabled).18f else .07f)).testTag("chat-send-face"),contentAlignment=Alignment.Center){
                        Text(if(stopping)"停止"else"发送",style=MaterialTheme.typography.labelLarge,color=actionColor.copy(alpha=if(enabled)1f else .38f))
                    }
                }
            }
        }}
    }
    }
    controlMessage?.let{m->AlertDialog(onDismissRequest={controlMessage=null},title={Text("这条消息的使用方式")},text={Text("停止提醒会保留记忆。不再使用会停止相关原话、记忆与判断的AI使用，并取消回访；原聊天保留供你查看。")},
        confirmButton={TextButton(onClick={vm.sourceControl(m.id,true);controlMessage=null},modifier=Modifier.testTag("companion-forget")){Text("不再用于记忆和判断")}},
        dismissButton={Row{TextButton(onClick={vm.sourceControl(m.id,false);controlMessage=null},modifier=Modifier.testTag("companion-stop-reminder")){Text("只停关联提醒")};TextButton(onClick={controlMessage=null}){Text("取消")}}})}
    viewedImage?.let{ChatMediaViewer(it){viewedImage=null}}
}
