package app.lianxu.independent

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.UUID

/** User text is durable before draft acknowledgement; only the owning attempt can append a reply. */
class ConversationRunner(private val scope:CoroutineScope,private val store:Store,private val vault:ModelVault,private val client:TextChatClient,private val processing:ProcessingRepository,private val memory:MemoryEngine,private val changed:suspend()->Unit,private val notice:(String)->Unit,private val settled:suspend()->Unit={},private val displayReplies:Boolean=false,private val ownForegroundRequests:Boolean=false,private val isReadingChat:((String)->Boolean)?=null,private val appInForeground:(()->Boolean)?=null,private val onIdle:()->Unit={}) {
    private val ledger=ChatLedger(store)
    private val modelStatus=ModelStatusStore(store,vault.statusNamespace)
    private val companion=CompanionRepository(store){AppServices.companionClock()}
    private val vectors=MemoryVectorIndex(processing,ModelAccess(vault))
    var sending by mutableStateOf(emptySet<String>());private set
    internal var previews by mutableStateOf(emptyMap<String,ChatReplyPreview>());private set
    /** What the panel showed when an external cancel clears the preview before the task records it. */
    private val shownSnapshots=java.util.concurrent.ConcurrentHashMap<String,ChatReplyPreview>()
    internal val foregroundRequests = mutableStateMapOf<String, String>()
    private val jobs=mutableMapOf<String,Job>()
    private val activeTurns=mutableMapOf<String,ChatTurn>()
    private val leases=mutableMapOf<String,ChatAttemptLease>()
    private val attempts=mutableMapOf<String,AttemptState>()
    private val inputs=ChatInputQueue<AttemptState>(scope)
    private data class Auth(val config:ModelConfig,val key:String,val epoch:Long)
    private suspend fun auth():Auth?=withContext(Dispatchers.IO){AppMutationGate.read{
        val id=vault.purposeId(ModelPurpose.CHAT)
        vault.list().find{it.id==id}?.let{config->val key=vault.secret(id);if(key.isBlank())null else Auth(config,key,AppMutationGate.epoch())}
    }}
    fun send(friendId:String,input:String,onPersisted:()->Unit):Boolean=sendImage(friendId,input,null,onPersisted=onPersisted)
    fun sendImage(friendId:String,input:String,image:PendingChatImage?,quote:ChatReplyQuote?=null,onPersisted:()->Unit):Boolean {
        if((input.isBlank()&&image==null)||input.length>2000)return false
        stop(friendId,"new_message")
        launch(friendId,input,null,onPersisted,image,quote=quote);return true
    }
    fun stop(friendId:String,origin:String="user_stop"){
        attempts[friendId]?.let{it.keepSpoken=true;it.delivery.stop()}
        cancel(friendId,origin)
    }
    internal fun acknowledge(friendId:String,turnId:String,attempt:Int,count:Int){
        val st=attempts[friendId]?:return
        val t=st.turn?:return
        val lease=leases[friendId]?:return
        if(!st.current())return
        if(t.id!=turnId||t.attempt!=attempt||leases[friendId]?.isActive()!=true)return
        val before=st.delivery.visible
        st.delivery.acknowledge(count)
        if(st.delivery.visible==before)return
        val spoken=st.delivery.spoken
        val confirmed=st.delivery.visible
        val previous=st.displaySave
        st.displaySave=scope.launch(NonCancellable){
            previous?.join()
            try{
                val saved=ledger.recordSpoken(t.id,t.attempt,spoken,st.epoch,st.current,lease)
                withContext(Dispatchers.Main.immediate){
                    if(saved&&attempts[friendId]===st&&leases[friendId]?.isActive()==true){
                        previews[friendId]?.takeIf{it.turnId==t.id&&it.attempt==t.attempt}?.let{
                            previews=previews+(friendId to it.copy(visible=maxOf(it.visible,confirmed)))
                        }
                    }
                }
            }catch(e:Exception){
                Diagnostics.failure("chat.display.save.failed",e,mapOf("turn" to t.id))
                runCatching{ledger.fail(t.id,t.attempt,ChatFailure.STORAGE)}
                withContext(Dispatchers.Main.immediate){if(attempts[friendId]===st){notice(ChatFailure.STORAGE.display);cancel(friendId)}}
            }
        }
    }
    fun retry(turn:ChatTurn){if(turn.friendId !in sending)launch(turn.friendId,null,turn.id,{})}
    fun cancel(friendId:String,origin:String="caller"){
        val lease=leases.remove(friendId)?:return;lease.cancel()
        // A cancelled transport can return late. Its revoked lease cannot publish or run tools;
        // do not make the next reply wait for that transport to acknowledge cancellation.
        val lock=CompanionRuntime.mutex(friendId)
        runCatching{if(lock.holdsLock(lease))lock.unlock(lease)}
        foregroundRequests.remove(friendId)
        jobs.remove(friendId)?.cancel(ChatRequestCancellation(origin));sending=sending-friendId
        ChatRequestOwner.release(attempts[friendId]?.owner)
        activeTurns.remove(friendId)?.let{turn->scope.launch(NonCancellable+Dispatchers.Main.immediate){
            if(previews[friendId]?.let{it.turnId==turn.id&&it.attempt==turn.attempt}==true){
                // The task's own catch may run later; keep what the panel showed so it can still be logged.
                previews[friendId]?.let{shownSnapshots["${it.turnId}:${it.attempt}"]=it}
                previews=previews-friendId
            }
            val failure=serviceFailure(origin)
            if(failure==null)ledger.cancel(turn.id,turn.attempt)else ledger.fail(turn.id,turn.attempt,failure)
            changed()
        }}
    }
    private fun serviceFailure(origin:String)=when(origin){
        "service_timeout"->ChatFailure.TIMEOUT
        "service_rejected","service_destroyed"->ChatFailure.INTERRUPTED
        else->null
    }
    internal val hasActiveAttempts get()=attempts.isNotEmpty()
    fun cancelAll(){leases.keys.toList().forEach{cancel(it)}}
    /** A failure while settling must never strand the per-role lock, or the role stops answering. */
    private suspend fun releaseRole(friendId:String,turn:ChatTurn?,settled:BooleanArray,lease:ChatAttemptLease,locked:Boolean,lock:kotlinx.coroutines.sync.Mutex){
        try{
            withContext(NonCancellable){turn?.let{ledger.settleActivities(it.id,it.attempt,settled[0]||lease.wasCommitted(),settled[1],settled[2])}}
        }catch(e:Exception){
            Diagnostics.failure("chat.settle.failed",e,mapOf("character" to friendId,"turn" to turn?.id))
        }finally{
            // The page was already reloaded before this settlement, so without a second reload the
            // finished message keeps showing the staged wording until something else refreshes it.
            withContext(NonCancellable){runCatching{changed()}}
            if(locked&&lock.holdsLock(lease))runCatching{lock.unlock(lease)}
            runCatching{CompanionRuntime.leave(friendId)}
        }
    }
    internal suspend fun restoreReceived(){
        ledger.claimReceived().forEach{(turn,result)->launch(turn.friendId,null,null,{},recovered=turn to result)}
    }
    private fun launch(friendId:String,input:String?,retryId:String?,onPersisted:()->Unit,image:PendingChatImage?=null,recovered:Pair<ChatTurn,ReceivedChatReply>?=null,quote:ChatReplyQuote?=null){
        val imageHold=image?.retain()
        CompanionRuntime.foreground(friendId)
        val lease=ChatAttemptLease();leases[friendId]=lease;sending=sending+friendId
        val st=AttemptState();attempts[friendId]=st
        // Registration happens synchronously with the user's click, before media or network preparation.
        if(ownForegroundRequests&&recovered==null){
            st.owner=ChatRequestOwner.begin(store.applicationContext){reason->
                if(attempts[friendId]===st){
                    st.serviceFailure=serviceFailure(reason)
                    if(st.serviceFailure==null)stop(friendId,reason)else cancel(friendId,reason)
                }
            }
        }
        val prepared=inputs.submit(friendId){previous->
            try{
            if(st.epoch!=AppMutationGate.epoch())return@submit st
            previous?.displaySave?.join()
            if(previous?.keepSpoken==true&&previous.epoch==AppMutationGate.epoch())previous.turn?.let{ledger.interrupt(it.id,it.attempt,previous.delivery.spoken)}
            val selected=withContext(Dispatchers.IO){vault.purposeId(ModelPurpose.CHAT)}
            st.turn=recovered?.first?:run{
                companion.beginInput(friendId)
                if(retryId==null)ledger.begin(friendId,input!!,selected,UUID.randomUUID().toString(),st.epoch,image,quote) else ledger.retry(retryId,selected,st.epoch)
            }
            st.turn?.let{t->
                st.initialSpoken=ledger.spoken(t.id)
                st.delivery.restore(st.initialSpoken)
                Diagnostics.event("chat.persisted",mapOf("character" to friendId,"turn" to t.id,"attempt" to t.attempt,"input" to Diagnostics.raw(input),"retry" to (retryId!=null)))
                if(leases[friendId]===lease)activeTurns[friendId]=t
                else if(st.keepSpoken)ledger.interrupt(t.id,t.attempt,st.delivery.spoken) else ledger.cancel(t.id,t.attempt)
                runCatching{onPersisted()}
            }
            runCatching{changed()};st
            }finally{imageHold?.close()}
        }
        val ownerContext=st.owner?.job?:kotlin.coroutines.EmptyCoroutineContext
        jobs[friendId]=scope.launch(ownerContext+Diagnostics.context("chat"),start=CoroutineStart.UNDISPATCHED){
            // The turn body lives in runAttempt: one giant suspend lambda made the debuggable runtime
            // lose this frame's catch/finally once a cancelled reply resumed (the test process crashed,
            // or cleanup was skipped and the role lock stayed held). try/catch/finally stay in this small frame.
            val roleLock=CompanionRuntime.mutex(friendId)
            try {
                prepared.await()
                st.owner?.let{owner->
                    if(withTimeoutOrNull(10_000){owner.started.await()}!=true)throw CompanionModelFailure(ChatFailure.FOREGROUND_UNAVAILABLE)
                    if (leases[friendId] === lease) foregroundRequests[friendId] = owner.id
                }
                if(recovered==null)runAttempt(friendId,retryId,lease,roleLock,st)
                else restoreDelivery(recovered.first,recovered.second,lease,roleLock,st)
            }catch(e:android.database.sqlite.SQLiteException){
                st.turn?.let{shownOnce(st,friendId,it.id,it.attempt,if(lease.wasCommitted())"success" else "storage",mapOf("failure" to ChatFailure.STORAGE.name))}
                Diagnostics.failure("chat.storage.failed",e,mapOf("character" to friendId,"turn" to st.turn?.id))
                withContext(NonCancellable){runCatching{st.turn?.let{ledger.fail(it.id,it.attempt,ChatFailure.STORAGE)}};notice(ChatFailure.STORAGE.display);changed()}
            }catch(e:CompanionModelFailure){
                // The plan path surfaces a failed reply as an exception; what the panel already showed
                // must still be recorded instead of vanishing with the turn.
                st.turn?.let{shownOnce(st,friendId,it.id,it.attempt,if(lease.wasCommitted())"success" else "failure",mapOf("failure" to e.failure.name.uppercase()))}
                st.turn?.let{ledger.fail(it.id,it.attempt,e.failure,e.detail,e.meta)};changed()
            }catch(e:CancellationException){
                // Cancelling the turn clears the preview; record what had already been shown first.
                st.turn?.let{shownOnce(st,friendId,it.id,it.attempt,if(lease.wasCommitted())"success" else "cancelled")}
                Diagnostics.event("chat.cancelled",mapOf("character" to friendId,"turn" to st.turn?.id))
                withContext(NonCancellable){st.turn?.let{if(st.serviceFailure!=null)ledger.fail(it.id,it.attempt,requireNotNull(st.serviceFailure))else if(st.keepSpoken&&st.epoch==AppMutationGate.epoch())ledger.interrupt(it.id,it.attempt,st.delivery.spoken)else ledger.cancel(it.id,it.attempt)};changed()};throw e
            }catch(e:Exception){
                st.turn?.let{shownOnce(st,friendId,it.id,it.attempt,if(lease.wasCommitted())"success" else "error",mapOf("error_type" to e.javaClass.name))}
                Diagnostics.failure("chat.failed",e,mapOf("character" to friendId,"turn" to st.turn?.id))
                // Only a real "cannot reach the service" is 网络连不上; timeouts and other IO errors keep their own words.
                val reason=if(unreachable(e))ChatFailure.NETWORK else ChatFailure.UNKNOWN
                withContext(NonCancellable){st.turn?.let{ledger.fail(it.id,it.attempt,reason,Diagnostics.redact("${e.javaClass.simpleName}: ${e.message.orEmpty()}").take(HttpsTextChatClient.DETAIL_LIMIT))};changed()}
                if(leases[friendId]===lease)notice("本次会话未完成，原消息已保留；可检查配置后手动重试。")
            }finally{
                if (foregroundRequests[friendId] == st.owner?.id) foregroundRequests.remove(friendId)
                ChatRequestOwner.release(st.owner)
                withContext(NonCancellable){
                    st.displaySave?.join()
                    // Also cover failure/cancellation just after a bubble's layout acknowledgement.
                    st.turn?.let{t->runCatching{ledger.recordSpoken(t.id,t.attempt,st.delivery.spoken,st.epoch,st.current,lease)}}
                }
                if(st.gated){ReplyGate.leave(friendId);st.gated=false}
                releaseRole(friendId,st.turn,st.settled,lease,st.locked,roleLock)
                withContext(NonCancellable+Dispatchers.Main.immediate){
                    st.turn?.let{owned->if(previews[friendId]?.let{it.turnId==owned.id&&it.attempt==owned.attempt}==true)previews=previews-friendId;shownSnapshots.remove("${owned.id}:${owned.attempt}")}
                    if(attempts[friendId]===st)attempts.remove(friendId)
                    if(leases[friendId]===lease){leases.remove(friendId);jobs.remove(friendId);activeTurns.remove(friendId);sending=sending-friendId}
                }
                withContext(NonCancellable){runCatching{settled()};runCatching{ReplyAccountingScheduler.enqueue(store,friendId)}}
                if(attempts.isEmpty())onIdle()
            }
        }
    }
    private suspend fun finalTranscript(role:String,st:AttemptState,streamReplies:Boolean,current:()->Boolean):String {
        while(streamReplies && !st.delivery.completed.isCompleted){
            currentCoroutineContext().ensureActive()
            if(!current())throw CompanionModelFailure(ChatFailure.SOURCE_CHANGED)
            // Away from this chat there is no page to acknowledge the durable final bubbles.
            // Keep the visible prefix intact in case stop wins before the transaction commits.
            if (isReadingChat?.invoke(role) == false) break
            withTimeoutOrNull(250){st.delivery.completed.await()}
        }
        st.displaySave?.join()
        return st.delivery.bubbles.joinToString("\n\n")
    }

    private fun submitVoiceParts(turn:ChatTurn,reply:ReceivedChatReply,body:String) {
        val target=reply.voiceTarget ?: return
        val parts=OrderedChatReply.decode(body,reply.replyLayout) ?: return
        val app=store.applicationContext
        val account=BreezeVoiceAccount(app)
        val lastVoice=parts.indexOfLast { it.voice }
        parts.forEachIndexed { index,part ->
            if(part.voice){
                val id=OrderedChatReply.audioKey(turn,body,index)
                ChatVoiceDelivery.submit(app,turn.friendId,id,valid={
                    account.target(turn.friendId)==target && store.readableDatabase.rawQuery(
                        "SELECT 1 FROM chat_turns t JOIN messages m ON m.id=t.assistant_message_id WHERE t.id=? AND t.attempt=? AND t.state='COMPLETED' AND m.body=? AND NOT EXISTS(SELECT 1 FROM message_state s WHERE s.message_id=m.id AND s.deleted=1)",
                        arrayOf(turn.id,turn.attempt.toString(),body)).use { it.moveToFirst() }
                },trace=VoiceTrace(turn.id,turn.attempt,target.provider,index),visibility={
                    when(appInForeground?.invoke()){
                        false->VoiceStatus.APP_BACKGROUND
                        true->if(isReadingChat?.invoke(turn.friendId)==true) VoiceStatus.CHAT_VISIBLE else VoiceStatus.OTHER_PAGE
                        null->VoiceStatus.VISIBILITY_UNKNOWN
                    }
                }){ account.synthesize(turn.friendId,RequestedVoiceReply(part.text,target),moreVoiceParts=index<lastVoice) }
            }
        }
    }

    private suspend fun notifySavedReply(turn: ChatTurn) {
        savedReplyNotification(turn, isReadingChat, { ledger.completedMessageId(turn) },
            { id -> ChatReplyNotifications.post(store.applicationContext, turn, id) },
            { error -> Diagnostics.failure("chat.reply.notification.failed", error) })
    }

    private suspend fun restoreDelivery(turn:ChatTurn,reply:ReceivedChatReply,lease:ChatAttemptLease,lock:kotlinx.coroutines.sync.Mutex,st:AttemptState){
        lock.lock(lease);st.locked=true
        ReplyGate.enter(turn.friendId);st.gated=true
        val context=ledger.context(turn.id)
        val config=withContext(Dispatchers.IO){vault.list().find{it.id==turn.configId}}?:throw CompanionModelFailure(ChatFailure.SOURCE_CHANGED)
        val route=ChatImageRoute(store,vault,client,turn,config,st.epoch){lease.isActive()}
        fun current()=lease.isActive()&&AppMutationGate.epoch()==st.epoch&&vault.purposeId(ModelPurpose.CHAT)==turn.configId&&
            config.matchesReceivedStamp(reply.configStamp)&&vault.matchesCached(config)&&ledger.hasReceived(turn.id,turn.attempt)&&route.validReceived(store.readableDatabase,reply.images)&&
            companion.current(store.readableDatabase,reply.companion)&&processing.referenceCurrent(turn.friendId,reply.referencesStamp)
        if(context.stamp!=reply.contextStamp||!current())throw CompanionModelFailure(ChatFailure.SOURCE_CHANGED)
        st.current=::current
        st.settled[1]=reply.recordAccepted
        val tools=MemoryTools(processing,turn,context.input,ProcessingRepository.References(emptyList(),emptyList(),reply.referencesStamp,reply.memory.at),active=::current)
        val activities=ledger.activities(turn.id)
        st.delivery.restoreFinal(reply.bubbles)
        if (config.streamReplies && reply.replyLayout==null) previews=previews+(turn.friendId to ChatReplyPreview(turn.id,turn.attempt,text=reply.bubbles.joinToString("\n\n"),reasoning=reply.reasoning,
            phase=ChatReplyPhase.REPLYING,tools=activities,bubbles=st.delivery.bubbles,visible=st.delivery.visible))
        if(!displayReplies || reply.replyLayout!=null)st.delivery.acknowledge(st.delivery.bubbles.size)
        val transcript=finalTranscript(turn.friendId,st,config.streamReplies,::current)
        val saved=ledger.finishWithMemory(turn.id,turn.attempt,transcript,lease,reply.contextStamp,{db->
            route.commit(db,reply.images);tools.commit(db,reply.memory);companion.finishReply(db,turn,reply.companion);OrderedChatReply.save(db,turn.id,reply.replyLayout)
        },reply.reasoning,::current)
        if(!saved)throw CompanionModelFailure(ChatFailure.SOURCE_CHANGED)
        st.settled[0]=true;st.settled[2]=true
        notifySavedReply(turn)
        submitVoiceParts(turn,reply,transcript)
        changed()
    }
    /** What one attempt shares with its cleanup; allocated up front so the cancellation path allocates nothing new. */
    private class AttemptState {
        var current:()->Boolean={true}
        var turn:ChatTurn?=null
        var owner:ChatRequestOwner.Attempt?=null
        var serviceFailure:ChatFailure?=null
        val epoch=AppMutationGate.epoch()
        val delivery=ReplyDelivery()
        var initialSpoken=""
        var displaySave:Job?=null
        var keepSpoken=false
        var locked=false
        // A staged write is only a success once this turn really commits it; the record also
        // needs its own acceptance to survive the post-control re-validation.
        val settled=BooleanArray(3) // 0 committed, 1 record accepted, 2 memory committed
        var shownEmitted=false
        // A summary saved mid-reply would void this reply's snapshot; while this flag is set the summary's save waits.
        var gated=false
    }
    /** One panel record per attempt: the turn ends once, so the log must not contradict itself. */
    private fun shownOnce(st:AttemptState,friendId:String,turnId:String,attempt:Int,outcome:String,extra:Map<String,Any?> = emptyMap()){
        if(st.shownEmitted)return
        st.shownEmitted=true
        val key="$turnId:$attempt"
        val preview=previews[friendId]?.takeIf{it.turnId==turnId&&it.attempt==attempt}?:shownSnapshots.remove(key)
        val base=mapOf<String,Any?>("character" to friendId,"turn" to turnId,"attempt" to attempt,"outcome" to outcome,
            "breaks" to preview?.thinkingBreaks?.map{mapOf("at" to it.at,"tool" to it.toolId)}.orEmpty())
        // "partial" means the generation was cut short, not that persistence refused the reply.
        val incomplete=outcome in setOf("failure","cancelled","error","storage")
        Diagnostics.event("chat.reasoning.shown",base+Diagnostics.reasoning(preview?.reasoning.orEmpty(),incomplete=incomplete)+extra)
    }
    private suspend fun runAttempt(friendId:String,retryId:String?,lease:ChatAttemptLease,roleLock:kotlinx.coroutines.sync.Mutex,st:AttemptState){
        roleLock.lock(lease);st.locked=true
        ReplyGate.enter(friendId);st.gated=true
        currentCoroutineContext().ensureActive()
        val current=st.turn?:run{notice("这一轮已有后续消息或正在处理，未重复发送。");return}
        val previousTools=ledger.activities(current.id)
        withContext(Dispatchers.Main.immediate){
            if(leases[friendId]===lease&&lease.isActive())previews=previews+(friendId to ChatReplyPreview(current.id,current.attempt,tools=previousTools,bubbles=st.delivery.bubbles,visible=st.delivery.visible))
        }
        val credentials=auth()
        if(credentials==null){ledger.fail(current.id,current.attempt,ChatFailure.MISSING_CONFIG);changed();return}
        val imageRoute=ChatImageRoute(store,vault,client,current,credentials.config,credentials.epoch){lease.isActive()}
        fun modelCurrent()=imageRoute.current()&&lease.isActive()&&AppMutationGate.epoch()==credentials.epoch&&vault.purposeId(ModelPurpose.CHAT)==credentials.config.id&&vault.matchesCached(credentials.config)
        st.current=::modelCurrent
        suspend fun resetPreview()=withContext(Dispatchers.Main.immediate){
            if(leases[friendId]===lease&&modelCurrent()){
                // Thinking is truncated by a tool call, never wiped: keep what she already thought
                // (and the receipts inserted into it) so the next round continues the same thought.
                val old=previews[friendId]?.takeIf{it.turnId==current.id&&it.attempt==current.attempt}
                st.delivery.nextResponse()
                previews=previews+(friendId to ChatReplyPreview(current.id,current.attempt,tools=old?.tools.orEmpty(),reasoning=old?.reasoning.orEmpty(),thinkingBreaks=old?.thinkingBreaks.orEmpty(),bubbles=st.delivery.bubbles,visible=old?.visible?:0))
            }
        }
        suspend fun receive(event:TextStreamEvent,decoder:ChatDisplayDecoder?=null,plainText:Boolean=false)=withContext(Dispatchers.Main.immediate){
            if(leases[friendId]!==lease||!modelCurrent())return@withContext
            val old=previews[friendId]?.takeIf{it.turnId==current.id&&it.attempt==current.attempt}?:return@withContext
            val next=when(event){
                is TextStreamEvent.Reasoning->old.copy(reasoning=old.reasoning+event.text,phase=ChatReplyPhase.THINKING)
                is TextStreamEvent.Content->{val part=if(plainText)event.text else decoder?.append(event.text).orEmpty();if(part.isEmpty())old else old.copy(text=old.text+part,phase=ChatReplyPhase.REPLYING)}
            }
            st.delivery.update(next.text)
            previews=previews+(friendId to next.copy(bubbles=st.delivery.bubbles,visible=old.visible))
        }
        suspend fun showTool(item:ChatToolActivity){
            // Remember the thinking offset this receipt first appeared at. The stored reply keeps
            // the same accumulated thinking, so a reopened message can put the receipt back in place.
            val placed=withContext(Dispatchers.Main.immediate){
                if(leases[friendId]!==lease||!modelCurrent())item else{
                    val old=previews[friendId]?.takeIf{it.turnId==current.id&&it.attempt==current.attempt}
                    val at=old?.thinkingBreaks?.firstOrNull{it.toolId==item.id}?.at
                        ?:old?.tools?.firstOrNull{it.id==item.id}?.reasoningAt
                        ?:old?.reasoning?.length?:0
                    item.copy(reasoningAt=at,reasoningKnown=true)
                }
            }
            if(!ledger.recordActivity(current.id,current.attempt,placed))return
            withContext(Dispatchers.Main.immediate){
                if(leases[friendId]===lease&&modelCurrent()){
                    val old=previews[friendId]?:ChatReplyPreview(current.id,current.attempt)
                    val items=old.tools.toMutableList();val index=items.indexOfFirst{it.id==placed.id}
                    val inserted=index<0
                    if(inserted)items+=placed else items[index]=placed
                    // The receipt is cut into the thinking stream at the point the tool was called;
                    // later reasoning keeps appending after it.
                    val breaks=if(inserted)old.thinkingBreaks+ChatThinkingBreak(placed.reasoningAt,placed.id)else old.thinkingBreaks
                    previews=previews+(friendId to old.copy(text="",tools=items,thinkingBreaks=breaks,phase=ChatReplyPhase.REQUESTING))
                }
            }
            changed()
        }
        val originalInput=ledger.context(current.id).input
        val queryCache=QueryEmbeddingCache()
        val memoryScores=vectors.scores(friendId,originalInput,queryCache)
        val initialRules=processing.memoryRules().first
        val stateBudget=((credentials.config.contextTokens-credentials.config.outputTokens)*(if(initialRules.adaptiveContext)0.35 else 1.0)).toInt().coerceAtLeast(0)
        var companionState=companion.snapshot(friendId,originalInput,tokenBudget=stateBudget)
        val profile=companionState.roleProfile
        val profileStamp=RoleConsistency.appraisalStamp(profile)
        suspend fun replySnapshot(budget:Int=stateBudget)=companion.snapshot(friendId,originalInput,tokenBudget=budget).also{
            if(it.roleProfile!=profile)throw CompanionModelFailure(ChatFailure.SOURCE_CHANGED)
        }
        val cached=companion.cached(current)
        var executedControl=cached?.takeIf{it.controlChecked&&it.control!="none"}
        var interpretation=cached?.takeUnless{it.interpretationUnknown||it.controlChecked}?:ExperienceProposal(interpretationUnknown=true)
        val source=companion.sourceFor(current)
        var roleState=companion.roleContext(friendId,source)
        val reusable=interpretation.understanding?.takeIf{flow->interpretation.appraisalProfileStamp==profileStamp&&roleState.entries.any{it.source==source&&it.flow==flow}}
        val needsPlan=interpretation.control=="none"&&!interpretation.controlChecked&&reusable==null
        if(needsPlan)interpretation=interpretation.copy(understanding=null)
        val planner=RolePlanner(imageRoute)
        val appraisalTime=AppServices.companionClock()
        val contactZone=java.time.ZoneId.systemDefault()
        if(executedControl==null&&!interpretation.interpretationUnknown&&!companion.accept(current,interpretation,companionState,::modelCurrent)){ledger.fail(current.id,current.attempt,ChatFailure.SOURCE_CHANGED);changed();return}
        companionState=replySnapshot()
        settled()
        processing.maintainCore(friendId)
        var context=ledger.context(current.id);var prompts=RolePromptStore.decode(context.rawPrompts)
        var rules=processing.memoryRules().first
        var refs=processing.references(friendId,context.input,current.userMessageId,memoryScores)
        companionState=replySnapshot()
        val interpretationRefs=interpretation.understanding?.let{flow->listOf(PromptReference("本轮角色选择",buildString{
                flow.appraisal?.let{append("当时的理解：${it.meaning}；")}
                flow.judgment?.let{append("你的判断：${it.position}（${when(it.kind){"evidence_based"->"有依据";"inference"->"仍是推测";else->"还不确定"}}）；")}
                if(flow.feelings.isNotEmpty())append("你当时的感受：${flow.feelings.joinToString{ExperiencePresentation.emotion(it.kind)+"，因为"+it.reason}}；")
                flow.goal?.let{append("你想做的是：${it.description}；")}
                flow.selectedChoice()?.let{append("选择这样回应，是因为${it.reason}。\n")}
                append("按已选回应自然表达，忠于原话及仍有效的依据，不扩大工具权限。\n${ExperiencePresentation.REFS}\n这次理解：${ExperiencePresentation.reference(source)}")
                flow.selectedChoice()?.let{append("；回应方式 ${it.action}")}
            },true))}.orEmpty()
        fun controlReferences()=listOfNotNull(ControlPresentation.reference(executedControl))+(if(executedControl==null)interpretationRefs else emptyList())
        fun references():List<PromptReference>{
            // The template tone is a proactive style; a reply must not be steered by it.
            val replyRefs=CompanionPrompt.replyReferences(companionState.references)
            val companionRefs=if(needsPlan)replyRefs.filter{it.mandatory}else if(rules.adaptiveContext)replyRefs
                else listOf(PromptReference("可撤回的经历与状态",companionState.replyPrompt.ifBlank{companionState.prompt},true,remembers=companionState.hasExperiences))
            val continuation=if(st.initialSpoken.isBlank())emptyList()else listOf(PromptReference("已经说过的话",
                if(CompanionRepository.aiAllowed(store.readableDatabase,friendId,current.userMessageId))"下面是你已经发出的原话。接着说，不要从头重复，也不要再发一遍：\n${st.initialSpoken}"else "前面已经发出过回复，相关内容已停止使用。只接着确认结果，不重复先前内容。",true))
            val values=refs.valid+companionRefs+continuation+controlReferences()
            val used=interpretation.understanding?.dependencies.orEmpty().map{it.source}.filter{it.turnId!=current.userMessageId}.toSet()
            // Evidence selected during appraisal must remain available to the reply, even after summarizing.
            return if(rules.adaptiveContext)values.map{if(it.sources.any{r->r in used})it.copy(mandatory=true)else it}else values
        }
        var freshControlContext:(suspend()->Pair<TextChatRequest,ProcessingRepository.References>)?=null
        val controlTool:suspend(org.json.JSONObject)->Map<String,Any> = {args->
            val proposal=JointControl.parse(args,originalInput,appraisalTime,companionState.sources.map{it.turnId}.toSet(),executedControl)
            if(executedControl!=null){
                require(proposal.control==executedControl!!.control&&proposal.targets==executedControl!!.targets){"different_control_already_applied"}
            }else{
                check(modelCurrent())
                check(companion.accept(current,proposal,companionState,::modelCurrent)){"source_changed"}
                executedControl=proposal
            }
            JointControl.receipt(requireNotNull(executedControl))
        }
        // Freeze this request's exposure. If the switch turns off after it is sent, contact() still rejects the old schedule.
        val contactScheduling=companionState.policy=="enabled"
        val contactTool:suspend(String)->Map<String,Any> = {raw->
            val request=ImmediateContact.parse(raw,originalInput,companion.roleContext(friendId,source),appraisalTime,contactZone,contactScheduling)
            companion.contact(current,request,companionState,appraisalTime,::modelCurrent).also{
                companionState=replySnapshot();roleState=companion.roleContext(friendId,source)
                settled()
            }
        }
        val voiceAccount=BreezeVoiceAccount(store.applicationContext)
        fun toolRunner()=MemoryTools(processing,current,context.input,refs,memoryVectors=vectors,queryCache=queryCache,relationEnabled=needsPlan,controlTool=controlTool,freshControlContext={requireNotNull(freshControlContext).invoke()},contactTool=contactTool,contactTime=appraisalTime,contactZone=contactZone,contactScheduling=contactScheduling,voiceEligibility={voiceAccount.eligibility(friendId)}){modelCurrent()&&companion.current(store.readableDatabase,companionState)&&processing.referenceCurrent(friendId,refs.stamp)}
        val accountRecord=false // Mood/relationship review waits for the configured batch, never the reply.
        fun toolReserve()=toolRunner().reservedTokens()
        fun jointReserve()=if(needsPlan)planner.replyFixedTokens(interpretation,roleState,appraisalTime,rules.proactiveRecall,accountRecord,context.persona)+(context.recent.size+6)*8 else 0
        suspend fun build():PromptBuild {
            if(CompanionPrompt.profile(context.friend,prompts,context.persona)!=profile)throw CompanionModelFailure(ChatFailure.SOURCE_CHANGED)
            val base=CompanionPrompt.build(context.friend,prompts,context.recent,context.input,references(),refs.pending,capacity=credentials.config.contextTokens,outputLimit=credentials.config.outputTokens,recentLimit=rules.recentRounds,relatedLimit=rules.relatedCount,persona=context.persona,openingEligible=context.openingEligible,inputImage=context.image,quote=context.quote,recentQuotes=context.recentQuotes,currentMessageId=current.userMessageId,adaptive=rules.adaptiveContext,toolReserve=jointReserve()+if(rules.adaptiveContext)toolReserve()else 0)
            val prepared=if(needsPlan&&base is PromptBuild.Ready)planner.prepareReply(base,credentials.config,originalInput,interpretation,roleState,appraisalTime,rules.proactiveRecall,emptyMap(),toolReserve(),accountRecord,context.persona)else base
            if(prepared is PromptBuild.Ready&&toolRunner().initialCapacityFailure(credentials.config,prepared.request)!=null){
                return PromptBuild.Blocked(if(prepared.summaryCanShrink)ChatFailure.CONTEXT_NEEDS_MEMORY else ChatFailure.CONTEXT_CAPACITY)
            }
            return prepared
        }
        freshControlContext={
            context=ledger.context(current.id);refs=processing.references(friendId,context.input,current.userMessageId,memoryScores)
            companionState=replySnapshot();roleState=companion.roleContext(friendId,source)
            val fresh=build()
            check(fresh is PromptBuild.Ready){"context_capacity_after_control"}
            fresh.request to refs
        }
        suspend fun fitExperiences(){
            if(needsPlan)return
            if(rules.adaptiveContext){companionState=replySnapshot();return}
            val base=CompanionPrompt.build(context.friend,prompts,context.recent,context.input,refs.valid+controlReferences(),refs.pending,capacity=credentials.config.contextTokens,outputLimit=credentials.config.outputTokens,recentLimit=rules.recentRounds,relatedLimit=rules.relatedCount,persona=context.persona,openingEligible=context.openingEligible,inputImage=context.image,quote=context.quote,recentQuotes=context.recentQuotes,currentMessageId=current.userMessageId)
            val empty=replySnapshot(0)
            val available=if(base is PromptBuild.Ready)stateBudget-base.request.messages.sumOf{ConservativeTokens.tokens(it.content)+8}-ConservativeTokens.tokens(empty.prompt)-8 else 0
            companionState=replySnapshot(available.coerceAtLeast(0))
        }
        fitExperiences()
        var built=build()
        Diagnostics.event("prompt.assembled",mapOf("character" to friendId,"turn" to current.id,"recent_rounds" to context.recent.size,"reference_count" to refs.valid.size,"pending_count" to refs.pending.size,"result" to built.javaClass.simpleName))
        val fixedOnly=CompanionPrompt.build(context.friend,prompts,emptyList(),context.input,refs.valid.filter{it.mandatory},capacity=credentials.config.contextTokens,outputLimit=credentials.config.outputTokens,persona=context.persona,openingEligible=context.openingEligible,inputImage=context.image,quote=context.quote,recentQuotes=context.recentQuotes,currentMessageId=current.userMessageId)
        if(fixedOnly is PromptBuild.Blocked&&processing.coreBudget(friendId).overGoal){ledger.fail(current.id,current.attempt,ChatFailure.CORE_CAPACITY);changed();return}
        while(built.waitsForSummary()&&context.recent.isNotEmpty()){
            val fixed=CompanionPrompt.build(context.friend,prompts,emptyList(),context.input,references().filter{it.mandatory},capacity=credentials.config.contextTokens,outputLimit=credentials.config.outputTokens,persona=context.persona,openingEligible=context.openingEligible,inputImage=context.image,quote=context.quote,recentQuotes=context.recentQuotes,currentMessageId=current.userMessageId)
            val available=AdaptiveContext.inputLimit(credentials.config.contextTokens,credentials.config.outputTokens,toolReserve()+jointReserve())-((fixed as? PromptBuild.Ready)?.estimatedInputTokens?:credentials.config.contextTokens)
            val before=context.recent.map{it.id}
            // This turn itself waits for the summary here, so it must not hold the summary's save back.
            ReplyGate.leave(friendId);st.gated=false
            val run=awaitMemoryCapacity(friendId,store.memorySummary){memory.ensureRoom(friendId,context.input,forceOne=rules.adaptiveContext,manualRetry=retryId!=null,historyBudget=if(rules.adaptiveContext)available else null)}
            ReplyGate.enter(friendId);st.gated=true
            if(!run.success){ledger.fail(current.id,current.attempt,memoryFailureFor(run.code));changed();return}
            context=ledger.context(current.id);prompts=RolePromptStore.decode(context.rawPrompts);rules=processing.memoryRules().first
            refs=processing.references(friendId,context.input,current.userMessageId,memoryScores);roleState=companion.roleContext(friendId,source);fitExperiences();built=build()
            if(context.recent.map{it.id}==before){ledger.fail(current.id,current.attempt,ChatFailure.MEMORY_CAPACITY);changed();return}
        }
        if(built is PromptBuild.Blocked){ledger.fail(current.id,current.attempt,built.reason);changed();return}
        var tools=toolRunner()
        val result=run {
            // Plain reply branch: the preview and the stored body go through the same decoder,
            // so an old JSON envelope can never be shown as its reply and saved as the whole shell.
            var decoder=ChatDisplayDecoder()
            val raw=withContext(Dispatchers.IO){tools.run(credentials.config,credentials.key,(built as PromptBuild.Ready).request.copy(conversationId=ProviderRequestMetadata.conversation(friendId)),imageRoute,
                onResponseStart={decoder=ChatDisplayDecoder();resetPreview()},onEvent=if(credentials.config.streamReplies) { event -> receive(event,decoder) } else null,onToolActivity={showTool(it)})}
            if(raw is TextChatResult.Success){
                val text=if(tools.orderedParts!=null) raw.text else ChatDisplayDecoder().append(raw.text)
                if(text.isBlank()||text.length>16000)TextChatResult.Failure(ChatFailure.REPLY_TEXT_INVALID)else raw.copy(text=text)
            }else raw
        }
        interpretation=executedControl?:interpretation
        currentCoroutineContext().ensureActive()
        modelStatus.recordCall(credentials.config.id,"聊天",result is TextChatResult.Success,(result as? TextChatResult.Failure)?.reason?.display.orEmpty())
        when(result){
            is TextChatResult.Success->{
                val parts=withContext(Dispatchers.Main.immediate){
                    currentCoroutineContext().ensureActive()
                    val ordered=tools.orderedParts
                    if(ordered==null){st.delivery.update(result.text,final=true);null}
                    else st.delivery.finishOrdered(ordered)?:throw CompanionModelFailure(ChatFailure.REPLY_TEXT_INVALID)
                }
                val receipt=ReceivedChatReply(st.delivery.bubbles,result.displayReasoning,context.stamp,refs.stamp,
                    stableDigest(credentials.config.toString()),companionState.copy(prompt="",overview="",learning=emptyList(),references=emptyList(),roleProfile="",replyPrompt="",proactivePrompt=""),
                    imageRoute.receivedEffects(),tools.receivedEffects(),st.settled[1],parts?.let(OrderedChatReply::encode),tools.spokenReply?.target)
                if(!ledger.saveReceived(current,receipt,lease){modelCurrent()&&companion.current(store.readableDatabase,companionState)&&processing.referenceCurrent(friendId,refs.stamp)})
                    throw CompanionModelFailure(ChatFailure.SOURCE_CHANGED)
                ChatRequestOwner.release(st.owner)
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main.immediate){
                    if(!displayReplies || parts!=null)st.delivery.acknowledge(st.delivery.bubbles.size)
                    if(credentials.config.streamReplies && parts==null) previews[friendId]?.takeIf{it.turnId==current.id&&it.attempt==current.attempt}?.let{
                        previews=previews+(friendId to it.copy(text=result.text,phase=ChatReplyPhase.REPLYING,bubbles=st.delivery.bubbles,visible=if(displayReplies)it.visible else st.delivery.visible))
                    }
                }

                fun receivedCurrent()=modelCurrent()&&ledger.hasReceived(current.id,current.attempt)
                st.current=::receivedCurrent
                val transcript=finalTranscript(friendId,st,credentials.config.streamReplies,::receivedCurrent)
                val saved=ledger.finishWithMemory(current.id,current.attempt,transcript,lease,context.stamp,{db->imageRoute.commit(db);tools.commit(db);companion.finishReply(db,current,companionState);OrderedChatReply.save(db,current.id,receipt.replyLayout)},displayReasoning=result.displayReasoning){
                    receivedCurrent()&&companion.current(store.readableDatabase,companionState)&&processing.referenceCurrent(friendId,refs.stamp)
                }
                if(!saved)ledger.fail(current.id,current.attempt,ChatFailure.SOURCE_CHANGED)
                else {
                    st.settled[0]=true;st.settled[2]=true
                    notifySavedReply(current)
                    submitVoiceParts(current,receipt,transcript)
                    memory.afterChat(friendId)
                    settled()
                }
                // The panel and the stored answer can differ (thinking continues across tool rounds):
                // keep both, labelled, so a reply can be traced either way. One record per attempt.
                shownOnce(st,friendId,current.id,current.attempt,if(saved)"success" else "conflict",
                    mapOf("final_reasoning_chars" to result.displayReasoning.length)+
                        Diagnostics.reasoning(result.displayReasoning).mapKeys{"final_"+it.key})
                Diagnostics.event("chat.result",mapOf("character" to friendId,"turn" to current.id,"attempt" to current.attempt,"saved" to saved,"reply_chars" to result.text.length))
            }
            is TextChatResult.Failure->{
                // A failed turn still showed whatever had streamed in before the failure.
                shownOnce(st,friendId,current.id,current.attempt,"failure",mapOf("failure" to result.reason.name))
                val meta=(result.meta?:ChatFailureMeta()).let{m->
                    if(m.modelConfigId.isBlank()) m.copy(
                        route=m.route.ifBlank{"chat"},
                        modelConfigId=credentials.config.id,
                        modelId=Diagnostics.redact(credentials.config.modelId,listOf(credentials.key)),
                        outputTokens=m.outputTokens?:credentials.config.outputTokens,
                        streamReplies=m.streamReplies?:credentials.config.streamReplies,
                    ) else m.copy(route=m.route.ifBlank{"chat"})
                }
                ledger.fail(current.id,current.attempt,result.reason,result.detail,meta)
            }
        }
        changed()
    }
}
