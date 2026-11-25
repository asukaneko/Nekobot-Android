package app.lianxu.independent

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One live draft and its submission receipt survive an Activity rebuild together. */
internal class ChatDraftState(text:String="",version:Long=0){
    val image=mutableStateOf<PendingChatImage?>(null)
    val importing=mutableStateOf(false)
    val text=mutableStateOf(text)
    val version=mutableLongStateOf(version)
    val submittedVersion=mutableLongStateOf(-1)
    val persistedVersion=mutableLongStateOf(-1)
    val submittedAt=mutableLongStateOf(0)
}

class AppState @JvmOverloads constructor(application: Application, client:TextChatClient=AppServices.createTextClient(),vaultNamespace:String=AppServices.vaultNamespace) : AndroidViewModel(application) {
    internal val chatDrafts=mutableMapOf<String,ChatDraftState>()
    override fun onCleared(){chatBinding.detach(this);chatDrafts.values.forEach{it.image.value?.close()};super.onCleared()}
    internal var imagePickerRole:String?=null
    private val store = LianxuModule.roles.store
    private val ledger=ChatLedger(store)
    private val modelVault=ModelVault(application,vaultNamespace,store)
    private val diagnosticClient=LoggedTextChatClient(client)
    internal val backup=BackupRepository(store,modelVault)
    internal val modelAccess=ModelAccess(modelVault)
    internal val creationPolisher=RoleCreationPolish(viewModelScope,diagnosticClient,{modelAccess.credential(ModelPurpose.CHAT)},{modelAccess.current(ModelPurpose.CHAT,it)})
    internal val processing=ProcessingRepository(store)
    private val roleAssets=RoleAssets(processing)
    internal var roleAvatars by mutableStateOf(emptyMap<String,ByteArray>());private set
    internal var userAvatar by mutableStateOf<ByteArray?>(null);private set
    internal val companion=CompanionRepository(store){AppServices.companionClock()}
    private val companionNotifications=CompanionNotifications(application,store,companion)
    internal var companionReceipts by mutableStateOf(emptyList<CompanionReceipt>());private set
    internal var companionPolicy by mutableStateOf("disabled")
    var notificationTarget by mutableStateOf<Long?>(null);private set
    internal suspend fun settleCompanion(){companionNotifications.reconcile();CompanionScheduler.enqueue(getApplication(),companion)}
    internal suspend fun changeCompanionPermission(role:String,enabled:Boolean){
        LocalStep.COMPANION_PERMISSION.observe { companion.setPolicy(role,enabled,CompanionRuntime.owner) }
        if(!enabled)CompanionScheduler.cancel(getApplication(),role)
        if(activeId==role)companionPolicy=companion.contactPolicies()[role]?:"disabled"
        settleCompanion();reload()
    }
    internal fun setCompanionEnabled(enabled:Boolean,onEnabled:()->Unit={}){val role=activeId;viewModelScope.launch{
        try{changeCompanionPermission(role,enabled);if(activeId==role&&enabled&&companionPolicy=="enabled")onEnabled()}
        catch(e:CancellationException){throw e}catch(_:Exception){error="主动联系操作未完整完成，请核对当前开关后重试。"}
    }}
    internal fun updateCompanionPermission(role:String,enabled:Boolean,done:(Result<String>)->Unit){viewModelScope.launch{
        try{changeCompanionPermission(role,enabled);done(Result.success(requireNotNull(companion.contactPolicies()[role])))}
        catch(e:CancellationException){throw e}catch(e:Exception){done(Result.failure(e))}
    }}
    internal fun retryCompanionContact(role:String,done:(Result<Int>)->Unit){viewModelScope.launch{
        try{companion.retryFailed(role);settleCompanion();done(Result.success(companion.retryable(role)))}
        catch(e:CancellationException){throw e}catch(e:Exception){done(Result.failure(e))}
    }}
    internal fun saveToolboxOrder(order:List<String>,done:(Boolean)->Unit){viewModelScope.launch{
        try{ToolboxPreferences.saveOrder(processing.store,order);done(true)}
        catch(e:CancellationException){throw e}catch(_:Exception){done(false)}
    }}
    internal fun saveVideoMode(mode:ToolboxPreferences.VideoMode,done:(Boolean)->Unit){viewModelScope.launch{
        try{ToolboxPreferences.saveVideoMode(processing.store,mode);done(true)}
        catch(e:CancellationException){throw e}catch(_:Exception){error="保存视频方式失败，请重试。";done(false)}
    }}
    internal fun companionRead(role:String,id:Long,stillVisible:()->Boolean){viewModelScope.launch{companion.markRead(role,id){page=="chat"&&activeId==role&&stillVisible()};companionReceipts=companion.receipts();companionNotifications.reconcile()}}
    internal fun sourceControl(messageId:Long,forget:Boolean){val role=activeId;viewModelScope.launch{
        val id=companion.sourceOfMessage(role,messageId)?:return@launch
        runner.cancel(role,"source_control")
        if(forget)companion.forget(role,id)else companion.stopReminder(role,id)
        settleCompanion();reload()
    }}
    internal var chatHostLifecycle: androidx.lifecycle.Lifecycle? = null
    internal fun isReadingChat(role: String) = page == "chat" && activeId == role &&
        chatHostLifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true

    internal fun notificationOpen(role:String,id:Long){viewModelScope.launch{
        while(!ready)delay(20)
        reload()
        if(messages.any{it.id==id&&it.friendId==role}&&friends.any{it.id==role}){
            open("chat",role);notificationTarget=id;companion.clicked(role,id)
        }
    }}
    internal val memoryChatRequest = MemoryChatRequest()
    private var memoryChatReturn = false
    internal suspend fun jumpToMemoryChat(source: SourceRef): Boolean {
        val current = processing.sources(source.characterId).firstOrNull { it.source.turnId == source.turnId } ?: return false
        reload() // Store.messages loads the complete history; there is no paging boundary.
        if (memoryChatIndex(messages, current.source) == null) return false
        memoryChatRequest.pending = current.source
        notificationTarget = null
        open("chat", source.characterId)
        memoryChatReturn = true
        return true
    }
    internal fun notificationShown(){notificationTarget=null}
    internal val roleArchive=RoleArchiveRepository(store)
    internal suspend fun importRoleArchive(preview:RoleArchiveRepository.Preview,confirmed:Boolean,lease:ChatAttemptLease=ChatAttemptLease()):String {
        return withContext(NonCancellable){val id=Diagnostics.span("backup.legacy.import"){roleArchive.commit(preview,confirmed,lease)};LianxuModule.roles.sync();avatarCache.reset();messagePrints=emptyMap();reload();id}
    }
    internal suspend fun exportBackup(id:String?,includeKeys:Boolean,password:CharArray?=null)=Diagnostics.span("backup.export",mapOf("scope" to if(id==null)"all" else "role","include_keys" to includeKeys)) {backup.export(id,includeKeys,password)}
    internal suspend fun importBackup(plan:BackupRepository.Preview,confirmed:Boolean,lease:ChatAttemptLease,password:CharArray?=null):List<String> = withContext(NonCancellable){Diagnostics.span("backup.import",mapOf("mode" to plan.mode,"scope" to if(plan.pack.all)"all" else "role")) {
        val ids=LianxuModule.roles.duringRestore { if(plan.mode=="REPLACE"&&plan.pack.all)diagnosticSave.withLock{
            val detailed=plan.pack.root["preferences"].asJsonObject[EXPORT_DETAIL_PREF]?.asString!="0"
            val restored=backup.commit(plan,confirmed,lease,password=password)
            diagnosticChoice++;selectDetailedExport(detailed);savedDiagnosticDetailed=detailed
            restored
        }else backup.commit(plan,confirmed,lease,password=password) }
        if(plan.mode=="REPLACE"){
            val replaced=if(plan.pack.all)friends.map{it.id}else listOf(requireNotNull(plan.target))
            replaced.forEach{runner.cancel(it,"backup_replacement");memory.cancel(it)}
        }
        LianxuModule.roles.sync();avatarCache.reset();messagePrints=emptyMap();reload();markFailureGuidesSeen(turns);persona=store.persona();refreshUserAvatar();ids
    }}
    internal val memory:MemoryEngine get()=chatBinding.memory
    private val manualMemoryJobs=mutableMapOf<String,Job>()
    private val chatBinding=ChatRunnerBinding.acquire(store,modelVault,diagnosticClient,processing,this)
    private val runner get()=chatBinding.runner
    internal suspend fun refreshChat(){reload()}
    internal fun chatNotice(message:String){error=message}
    var processingRevision by mutableIntStateOf(0);private set
    var turns by mutableStateOf(emptyList<ChatTurn>());private set
    internal fun isReplying(role: String) = role in runner.sending
    val chatSending get()=isReplying(activeId)
    internal val foregroundChatRequest get() = runner.foregroundRequests[activeId]
    private var notificationHintShown = false
    internal fun claimNotificationHint(): Boolean {
        if (notificationHintShown) return false
        notificationHintShown = true
        return true
    }
    private val handledFailureGuides = mutableSetOf<String>()
    internal fun markFailureGuidesSeen(list: List<ChatTurn>) {
        list.filter { it.state == TurnState.FAILED }.forEach { handledFailureGuides += "${it.id}:${it.attempt}" }
    }
    internal fun claimFailureGuide(turn: ChatTurn): Boolean {
        if (turn.state != TurnState.FAILED || !ChatFailureGuidance.shouldAutoPrompt(turn.failure)) return false
        return handledFailureGuides.add("${turn.id}:${turn.attempt}")
    }
    internal val chatPreview get()=runner.previews[activeId]
    val busy get()=saving||chatSending
    val chatInputAvailable get()=!saving
    internal fun replyShown(role:String,turn:String,attempt:Int,count:Int)=runner.acknowledge(role,turn,attempt,count)
    fun stopReply(role:String)=runner.stop(role)
    private val generator=OriginalRoleGenerator(diagnosticClient,{modelAccess.credential(ModelPurpose.CHAT)},{modelAccess.current(ModelPurpose.CHAT,it)})
    private val gate = SearchGate()
    private var searchJob: Job? = null
    var friends by mutableStateOf(emptyList<Friend>()); private set
    var messages by mutableStateOf(emptyList<Message>()); private set
    /** One row per committed memory batch; placed in the chat where that batch finished. */
    internal var memoryPills by mutableStateOf(emptyList<MemoryPill>()); private set
    var tab by mutableIntStateOf(0)
    var page by mutableStateOf("home"); private set
    var activeId by mutableStateOf(""); private set
    private var detailReturn = "home"
    private var processingReturn = "processing"
    private var memorySettingsReturn = "processing"
    var searching by mutableStateOf(false); private set
    var candidates by mutableStateOf(emptyList<Friend>()); private set
    var generatedRoles by mutableStateOf(emptyList<GeneratedRole>()); private set
    var searchRequirements by mutableStateOf(""); private set
    var candidateId by mutableStateOf(""); private set
    private var saving by mutableStateOf(false)
    var error by mutableStateOf(""); private set
    var persona by mutableStateOf(UserPersona());private set
    val nickname get()=persona.displayName
    var ready by mutableStateOf(false); private set
    /** Changing format invalidates a pending detailed export, even after switching back. */
    internal var detailedExportSession: Any? by mutableStateOf(Any()); private set
    internal val diagnosticDetailed get() = detailedExportSession != null
    private var savedDiagnosticDetailed=true
    private fun selectDetailedExport(value:Boolean) {
        if(value != diagnosticDetailed) detailedExportSession = if(value) Any() else null
    }
    private var diagnosticChoice=0L
    private val diagnosticSave=Mutex()
    /** Test seam: lets device tests inject a slow or failing save without touching real preferences. */
    internal var diagnosticPersist: (suspend (String)->Unit)?=null
    internal fun setDiagnosticDetailed(value:Boolean){
        val choice=++diagnosticChoice
        selectDetailedExport(value)
        viewModelScope.launch{
            // Saves are serialized and write the value current at write time, so a fast on→off cannot
            // resurrect "on" after restart; a failed save rolls the switch back to what is really stored.
            val written=withContext(Dispatchers.IO){runCatching{diagnosticSave.withLock{
                val current=diagnosticDetailed
                val raw=if(current)"1" else "0"
                LocalStep.DIAGNOSTIC_FORMAT.observe(result = LocalResult::insert) {
                    diagnosticPersist?.invoke(raw)
                    store.savePreference(EXPORT_DETAIL_PREF,raw)
                }
                current
            }}.getOrNull()}
            // The disk always reflects the newest completed write, whatever the switch shows now.
            if(written!=null)savedDiagnosticDetailed=written
            // A newer choice owns the switch now: an older save may not roll it back.
            if(choice!=diagnosticChoice)return@launch
            if(written==null){Diagnostics.event("diagnostic.preference.failed");selectDetailedExport(savedDiagnosticDetailed)}
        }
    }
    init { viewModelScope.launch { runCatching { val savedDetailed=withContext(Dispatchers.IO){
        Diagnostics.startLocalRecording(application, vaultNamespace)
        LianxuModule.roles.sync()
        HostModelSync.refresh(store,modelVault)
        modelVault.list() // Register persisted model credentials before accepting user input.
        store.preference(EXPORT_DETAIL_PREF,"1")=="1"
    };selectDetailedExport(savedDetailed);savedDiagnosticDetailed=savedDetailed;ledger.recoverInterrupted();companion.checkContinuity(CompanionRuntime.owner);runner.restoreReceived();reload(syncModels=false);markFailureGuidesSeen(turns);  persona=store.persona();refreshUserAvatar(); ready=true;settleCompanion();recoverProcessing() }.onFailure { error="本地数据读取失败，请重试。" } } }
    private var reloadSerial=0L
    private val avatarCache=RoleAvatarCache()
    private var messagePrints=emptyMap<String,MessageFingerprint>()
    init {viewModelScope.launch{CompanionRuntime.revision.collect{if(ready)reload()}}}
    private suspend fun reload(syncModels:Boolean=true) {val id=++reloadSerial;if(syncModels)HostModelSync.refresh(store,modelVault);val f=store.friends();val (m,prints)=loadMessages();val t=ledger.all();val receipts=companion.receipts();val pills=withContext(Dispatchers.IO){ChatMemoryPills.read(store.readableDatabase)};val avatars=loadAvatars(f);if(id==reloadSerial){friends=f;messages=m;messagePrints=prints;turns=t;companionReceipts=receipts;memoryPills=pills;roleAvatars=avatars;processingRevision++}}
    private suspend fun loadMessages():Pair<List<Message>,Map<String,MessageFingerprint>>{
        // A discarded reload must not publish fingerprints for rows the UI never received.
        // Capture the accepted pair before suspension; publish the new pair together in reload.
        val currentMessages=messages;val previousPrints=messagePrints
        val prints=store.messageFingerprints()
        if(currentMessages.isEmpty())return store.messages() to prints
        val stale=MessageReload.friendsToReload(previousPrints,prints)
        val reloaded=stale.associateWith{store.messagesFor(it)}
        return MessageReload.merge(currentMessages,reloaded,prints) to prints
    }
    private suspend fun loadAvatars(f:List<Friend>):Map<String,ByteArray> = withContext(Dispatchers.IO){
        avatarCache.retain(f.map{it.id}.toSet())
        for(friend in f){
            if(avatarCache.cached(friend.id)!=null||avatarCache.knownMissing(friend.id))continue
            avatarCache.put(friend.id,roleAssets.avatar(friend.id)?.bytes)
        }
        avatarCache.snapshot()
    }
    internal suspend fun refreshProcessing(){reload()}
    private suspend fun recoverProcessing(){memory.recover();ReplyAccountingScheduler.recover(store)}
    fun foreground(){if(ready)viewModelScope.launch{runCatching{LianxuModule.roles.sync();avatarCache.reset();HostModelSync.refresh(store,modelVault);companion.checkContinuity(CompanionRuntime.owner);settleCompanion();reload(syncModels=false);recoverProcessing()}}}
    internal var modelsReturnChatId by mutableStateOf<String?>(null); private set
    internal var modelsTargetConfigId by mutableStateOf<String?>(null); private set
    internal var modelsTargetPurpose by mutableStateOf<ModelPurpose?>(null); private set
    internal fun openModelsFromChat(friendId:String,configId:String="",purpose:ModelPurpose?=null){
        open("models")
        modelsReturnChatId=friendId.ifBlank{null}
        modelsTargetConfigId=configId.ifBlank{null}
        modelsTargetPurpose=purpose
    }
    internal fun clearModelsTarget(){
        modelsTargetConfigId=null
        modelsTargetPurpose=null
    }
    // A chat attempt belongs to its role, not to the currently visible page.
    fun open(page: String, id: String = "") {
        ProcessDiagnostics.action(if(page=="models") ProcessAction.OPEN_MODELS else ProcessAction.OPEN)
        if (page == "detail") detailReturn = this.page
        if (page == "settings-memory") memorySettingsReturn = this.page
        if (page == "processing-role") processingReturn = if (this.page == "detail") "detail" else "processing"
        modelsReturnChatId = null
        modelsTargetConfigId = null
        modelsTargetPurpose = null
        cancelSearch()
        this.page = page
        activeId = id
        error = ""
    }
    fun back() {
        if(page=="models" && !modelsReturnChatId.isNullOrBlank()){
            val returnChat=modelsReturnChatId!!
            open("chat",returnChat)
            return
        }
        if(page=="chat" && memoryChatReturn){memoryChatReturn=false;memoryChatRequest.consume();page="processing-role";return}
        if(page=="search-detail")page="search" else if(page=="settings-memory")page=memorySettingsReturn else if(page=="role-companion")page="toolbox-contact" else if(page.startsWith("toolbox-"))page="toolbox" else if(page=="role-voice"||page=="role-prompts"||page=="role-learning"||page=="role-relationship")page="detail" else if(page=="processing-role")page=processingReturn else if(page=="detail") page=detailReturn else { cancelSearch(); page="home" }; error=""
    }
    fun home() {cancelSearch(); page="home"; error="" }
    fun startSearch(){cancelSearch();page="search";error=""}
    fun updateSearchRequirements(value:String){searchRequirements=value.take(2000)}
    fun findAgain(){
        if(searching||busy)return
        val requirements=searchRequirements.trim()
        if(requirements.isBlank()){error="先说说你想认识什么样的角色。";return}
        searchJob?.cancel();val id=gate.begin();syncSearch();error=""
        searchJob=viewModelScope.launch {
            try{val result=generator.generate(requirements);if(page=="search"&&gate.complete(id,result))syncSearch()}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(id==gate.generation){gate.cancel();syncSearch();error=if(e is RoleGenerationFailure)e.message.orEmpty() else "生成未完成，请重试；原候选已保留。"}}
        }
    }
    fun cancelSearch(){searchJob?.cancel();gate.cancel();syncSearch()}
    private fun syncSearch(){searching=gate.searching;generatedRoles=gate.candidates;candidates=generatedRoles.map{it.friend}}
    fun openCandidate(id:String){if(searching||generatedRoles.none{it.friend.id==id})return;candidateId=id;page="search-detail";error=""}
    fun addCandidate(){
        if(busy)return
        val role=generatedRoles.firstOrNull{it.friend.id==candidateId}?:return
        if(friends.any{it.id==role.friend.id})return
        save{store.addConfigured(role.friend,RolePrompts(characterDefinition=role.definition,expression=role.expression,opening=role.opening));LianxuModule.roles.sync();reload()}
    }
    fun create(name: String,bio: String,prompt: String) {
        if(busy) return
        if(name.isBlank() || bio.isBlank()) { error="请填写名字和角色介绍。"; return }
        val f=Friend(UUID.randomUUID().toString(),name.trim(),bio.trim(),prompt.trim())
        save { store.add(listOf(f),demoGreeting=false); LianxuModule.roles.sync();reload(); open("chat",f.id) }
    }
    fun send(text:String,onPersisted:()->Unit={}):Boolean=sendImage(text,null,onPersisted)
    fun sendImage(text:String,image:PendingChatImage?,onPersisted:()->Unit={}):Boolean {
        if(saving||friends.none{it.id==activeId})return false
        return runner.sendImage(activeId,text,image,onPersisted)
    }
    fun cancelChat(friendId:String){runner.cancel(friendId)}
    fun cancelProcessing(id:String){memory.cancel(id)}
    fun retryMemory(id:String){
        if(id in store.memorySummary.roles.value||friends.none{it.id==id})return
        error=""
        val job=viewModelScope.launch(start=CoroutineStart.LAZY){try{val result=memory.ensureRoom(id,forceOne=true,manualRetry=true);if(!result.success)error=processingMessage(result.code);reload()}finally{if(manualMemoryJobs[id]===currentCoroutineContext().job)manualMemoryJobs.remove(id)}}
        manualMemoryJobs[id]=job;job.start()
    }
    internal fun stopRoleJobs(id:String){manualMemoryJobs[id]?.cancel()}
    internal suspend fun previewRoleDeletion(id:String)=backup.previewRoleDeletion(id).copy(link=LianxuModule.roles.friendLink(id))
    internal suspend fun deleteRole(plan:BackupRepository.RoleDeletion,confirmed:Boolean){
        require(confirmed){"删除角色需要再次确认"}
        withContext(NonCancellable){
            val id=plan.friendId
            store.memorySummary.requireEditable(id)
            LianxuModule.roles.deleteFriend(plan)
            if(activeId==id){activeId="";page="home"}
            reload()
        }
    }
    fun retryChat(turn:ChatTurn){runner.retry(turn)}
    suspend fun rolePrompts(id:String)=RolePromptStore.decode(store.preference(RolePromptStore.key(id),""))
    fun saveRolePrompts(id:String,expression:String,extra:String,initialRelationship:String=""){
        save{store.updateRolePrompts(id){old->old.copy(expression=expression,extra=extra,initialRelationship=initialRelationship)};back()}
    }
    fun saveProfile(name:String){if(name.isBlank()){error="昵称不能为空。";return};savePersona(persona.copy(name=name))}
    fun savePersona(value:UserPersona){if(!value.valid()){error="请检查名字、称呼及自我介绍的长度；名字和称呼不要换行。";return};save{store.savePersona(value);persona=store.persona()}}
    internal var savedCreationId by mutableStateOf<String?>(null);private set
    fun createCompanion(name:String,definition:String,expression:String,opening:String,extra:String="",initialRelationship:String="",creationId:String?=null){
        if(busy)return
        val id=creationId?:UUID.randomUUID().toString()
        if(savedCreationId==id){save{openSavedCompanion(id)};return}
        if(name.isBlank()||definition.isBlank()){error="请填写名字和角色设定。";return}
        if(name.trim().length>30||initialRelationship.length>4000||listOf(definition,expression,opening,extra).any{it.length>8000}){error="请检查角色资料长度。";return}
        val body=definition.trim();val summary=body.take(500).let{if(it.lastOrNull()?.isHighSurrogate()==true)it.dropLast(1)else it}
        val f=Friend(id,name.trim(),summary,"")
        save{
            if(store.friends().none{it.id==f.id})store.addConfigured(f,RolePrompts(expression=expression,extra=extra,characterDefinition=body,opening=opening,initialRelationship=initialRelationship.trim()))
            savedCreationId=f.id
            openSavedCompanion(f.id)
        }
    }
    private suspend fun openSavedCompanion(id:String){
        try{LianxuModule.roles.sync();reload();check(friends.any{it.id==id});open("chat",id);savedCreationId=null}
        catch(e:CancellationException){throw e}
        catch(e:Exception){error="角色已保存，打开未完成，请重试。"}
    }
    fun saveCharacter(id:String,name:String,definition:String,expression:String,opening:String,extra:String,initialRelationship:String,thinkingPrompt:String){
        if(name.isBlank()||definition.isBlank()){error="请填写名字和角色设定。";return}
        save{store.saveConfigured(id,name){old->old.copy(characterDefinition=definition,expression=expression,opening=opening,extra=extra,initialRelationship=initialRelationship,thinkingPrompt=thinkingPrompt.ifBlank{""})};LianxuModule.roles.sync();reload();back()}
    }
    fun setRoleAvatar(id:String,mime:String,bytes:ByteArray){
        if(friends.none{it.id==id})return
        save{
            val prepared=prepareRoleAvatar(bytes)
            roleAssets.put(id,RoleAssets.Asset("avatar.jpg","avatar",prepared.first,prepared.second))
            LianxuModule.roles.sync()
            avatarCache.put(id,prepared.second)
            roleAvatars=roleAvatars+(id to prepared.second)
        }
    }
    private suspend fun refreshUserAvatar(){
        userAvatar=withContext(Dispatchers.IO){runCatching{UserAvatarData.checked(store.preference(UserAvatarData.KEY,""))}.getOrNull()}
    }
    fun setUserAvatar(uri:android.net.Uri){
        save{
            val raw=withContext(Dispatchers.IO){
                val resolver=getApplication<Application>().contentResolver
                requireNotNull(resolver.openInputStream(uri)).use{UserAvatarData.prepare(BackupArchive.bounded(it,BackupArchive.MAX_FILE))}
            }
            LocalStep.AVATAR.observe(result = LocalResult::insert) { store.savePreference(UserAvatarData.KEY,raw) }
            refreshUserAvatar()
        }
    }
    private fun save(block: suspend () -> Unit) { saving=true; error=""; viewModelScope.launch { try { block() } catch(e: Exception) {error="保存未完成，请重试。"} finally {saving=false} } }
}

