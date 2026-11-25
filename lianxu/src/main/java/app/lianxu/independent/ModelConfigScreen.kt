package app.lianxu.independent

import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import kotlinx.coroutines.launch

private val ConfigBackground:Color @Composable get()=Chrome
private val ConfigInk:Color @Composable get()=Ink
private val ConfigMuted:Color @Composable get()=Muted
private val ConfigGreen:Color @Composable get()=GreenText
/** 已连接的成功状态：沿用聊天发送与回想回执已验证可读的柔和绿，按明暗主题分别取值，不改全局棕色。 */
internal val ConfigSuccess:Color @Composable get()=if(Paper.luminance()<.5f)Color(0xFFAFC9B5) else Color(0xFF4E7058)
private val ConfigLine:Color @Composable get()=Line
private val ConfigErrorColor:Color @Composable get()=MaterialTheme.colorScheme.error
private fun providerName(p:ProviderPreset)=p.label.substringBefore(" · ")
private fun regionName(p:ProviderPreset)=p.label.substringAfter(" · ","")
private fun authLabel(p:ProviderPreset)=if(p.authHeader=="api-key")"api-key 鉴权" else "Bearer 鉴权"
private fun providerDetail(p:ProviderPreset)=providerName(p).let{name->regionName(p).let{region->(if(region==name||region.isBlank())"OpenAI 兼容配置" else region)+" · "+authLabel(p)}}
private fun thinkingModeLabel(mode:ThinkingMode)=when(mode){ThinkingMode.DEFAULT->"跟随模型默认";ThinkingMode.OFF->"不推理";else->mode.label}
/** 推理强度下拉的选中行：纸色向墨色混约 8%，暖中性，和未选中项轻轻分开。 */
private val ThinkingSelected:Color @Composable get()=lerp(Paper,Ink,.08f)

@Composable fun ModelConfigScreen(
    onBack:()->Unit,
    state:ModelConfigState=viewModel(),
    saveForPurpose:ModelPurpose?=null,
    initialEditConfigId:String?=null,
    initialPurpose:ModelPurpose?=null,
    returnToChatOnDone:Boolean=false,
    onInitialHandled:()->Unit={},
) {
    LaunchedEffect(state.vaultVersion){state.refresh()}
    var screen by remember {mutableStateOf("overview")}
    var openedDirectlyToEdit by remember {mutableStateOf(false)}
    SideEffect { ProcessDiagnostics.view(when(screen) {
        "edit" -> "model-editor"; "providers" -> "model-providers"; "voice-studio" -> "voice-studio"; else -> "models"
    }) }
    val tabs=rememberPageTabs(4)
    val overviewTab=tabs.selected
    val servicesScroll=rememberLazyListState()
    val purposesScroll=rememberLazyListState()
    val voiceScroll=rememberLazyListState()
    val imageScroll=rememberLazyListState()
    var imageNotice by remember{mutableStateOf(false)}
    var embeddingEditor by remember {mutableStateOf(false)}
    var allProviders by remember {mutableStateOf(false)}
    var regionGroup by remember {mutableStateOf<ProviderGroup?>(null)}
    var showUnknownModels by remember {mutableStateOf(false)}
    var providerReturn by remember {mutableStateOf("overview")}
    var original by remember {mutableStateOf<ModelConfig?>(null)}
    var originalRevision by remember {mutableStateOf<String?>(null)}
    var draftId by remember {mutableStateOf(UUID.randomUUID().toString())}
    var name by remember {mutableStateOf("")};var provider by remember {mutableStateOf("silicon-cn")}
    var base by remember {mutableStateOf("")};var model by remember {mutableStateOf("")}
    var key by remember {mutableStateOf("")};var reveal by remember {mutableStateOf(false)}
    var prefilledKey by remember {mutableStateOf("")}
    var budgets by remember {mutableStateOf(ModelBudgetDraft())}
    var thinkingMode by remember {mutableStateOf(ThinkingMode.DEFAULT)}
    var streamReplies by remember { mutableStateOf(true) }
    var thinkingMenu by remember {mutableStateOf(false)}
    var thinkingCustom by remember {mutableStateOf("")}
    fun enteredKey()=if(key==prefilledKey)"" else key
    fun resetThinking(){thinkingMode=ThinkingMode.DEFAULT;thinkingMenu=false}
    val keyDraft=remember {ModelKeyDraft()}
    val editorScope=rememberCoroutineScope()
    var query by remember {mutableStateOf("")};var advanced by remember {mutableStateOf(false)}
    var modelQuery by remember {mutableStateOf("")}
    var deleting by remember {mutableStateOf<ModelConfig?>(null)}
    val activity = LocalContext.current.hostActivity() ?: return
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun draft():ModelConfig{
        val config=ModelConfig(draftId,name.trim(),provider,base.trim().trimEnd('/'),model.trim(),budgets.context.toIntOrNull()?:0,budgets.chat.toIntOrNull()?:0,budgets.memory.toIntOrNull()?:-1,thinkingMode.key,thinkingCustom.trim(),streamReplies)
        // 界面只显示当前服务与模型允许的档位；保存值跟界面一致，不把旧档位带过去。
        return config.copy(thinkingMode=ThinkingFields.effectiveMode(config).key)
    }
    fun back(){
        if(state.saving)return
        keyDraft.invalidate()
        state.clearResult()
        if(screen=="providers"){screen=providerReturn;return}
        key="";reveal=false
        if(screen=="edit"){
            val directReturn=returnToChatOnDone&&openedDirectlyToEdit
            openedDirectlyToEdit=false
            if(directReturn)onBack() else screen="overview"
        } else onBack()
    }
    fun add(embedding:Boolean=false){if(state.saving||saveForPurpose==ModelPurpose.CHAT&&embedding)return;ProcessDiagnostics.action(ProcessAction.ADD_MODEL);prefilledKey="";keyDraft.invalidate();embeddingEditor=embedding;original=null;originalRevision=null;streamReplies=true;draftId=UUID.randomUUID().toString();name="";key="";reveal=false;model="";budgets=ModelBudgetDraft();resetThinking();thinkingCustom="";query="";allProviders=false;providerReturn="overview";state.clearResult();screen="providers"}
    fun sharedWithOtherPurposes(id:String)=saveForPurpose==ModelPurpose.CHAT&&state.purposes.any{(purpose,value)->purpose!=ModelPurpose.CHAT&&value==id}
    fun edit(config:ModelConfig,embedding:Boolean){if(config.id.isHostModel() || sharedWithOtherPurposes(config.id))return;ProcessDiagnostics.action(ProcessAction.EDIT_MODEL);prefilledKey="";embeddingEditor=embedding;original=config;streamReplies=config.streamReplies;originalRevision=state.configRevisions[config.id];draftId=config.id;name=config.name;provider=config.providerId;base=config.baseUrl;model=config.modelId;budgets=ModelBudgetDraft.existing(config);key="";reveal=false;advanced=false;thinkingMode=ThinkingFields.effectiveMode(config);thinkingMenu=false;thinkingCustom=config.thinkingCustom;state.clearResult();screen="edit"
        keyDraft.invalidate()
        val ticket=keyDraft.ticket()
        editorScope.launch {
            val saved=state.savedKey(config)
            if(keyDraft.accepts(ticket)&&screen=="edit"&&original==config){prefilledKey=saved;key=saved}
        }
    }
    fun changed(){modelQuery="";state.clearResult()}
    fun chooseModel(id:String){model=id;budgets=budgets.forModel(provider,base,model);resetThinking();changed()}
    LaunchedEffect(state.loaded,initialEditConfigId,initialPurpose){
        if(!state.loaded||(initialEditConfigId==null&&initialPurpose==null))return@LaunchedEffect
        val targetId=initialEditConfigId?.takeIf{it.isNotBlank()}?:initialPurpose?.let{state.purposes[it]}
        val target=targetId?.let{id->state.configs.firstOrNull{it.id==id}}
        onInitialHandled()
        if(target!=null && !target.id.isHostModel()){
            openedDirectlyToEdit=true
            edit(target,embedding=ProviderCatalog.modelKind(target)==ProviderModelKind.TEXT_EMBEDDING)
        }
    }
    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP){reveal=false;state.clearResult()}}
        lifecycle.addObserver(observer)
        onDispose{keyDraft.invalidate();lifecycle.removeObserver(observer);state.clearResult();activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    SideEffect{if(reveal)activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    if (screen == "voice-studio") {
        BreezeStudioScreen { screen = "overview" }
        return
    }
    BackHandler{back()}
    val preset=ProviderPresets.get(provider)
    Surface(Modifier.fillMaxSize(),color=ConfigBackground){Column {
        AppTopBar(
            title=when(screen){"providers"->if(embeddingEditor)"选择向量服务商" else "选择聊天服务商";"edit"->if(embeddingEditor)"向量模型配置" else if(original==null)"添加 ${providerName(preset)}" else "编辑配置";else->"大模型配置"},
            onBack={back()},backTag="config-back",enabled=!state.saving,
            extra={when(screen){
                "edit"->SaveAction(!state.busy&&!sharedWithOtherPurposes(draftId)&&ThinkingFields.customError(thinkingCustom)==null){ProcessDiagnostics.action(ProcessAction.SAVE_MODEL);reveal=false;if(saveForPurpose==ModelPurpose.CHAT)state.saveChatFromCreation(draft(),enteredKey(),original,originalRevision){key="";screen="overview"}else state.save(draft(),enteredKey(),if(embeddingEditor)ModelPurpose.EMBEDDING else null,original,originalRevision){key="";if(returnToChatOnDone&&openedDirectlyToEdit){openedDirectlyToEdit=false;onBack()}else screen="overview"}}
                else->Spacer(Modifier.width(48.dp))
            }}
        )
        if(saveForPurpose==ModelPurpose.CHAT&&!embeddingEditor)Text("这里只设置全局聊天。其他用途正在使用的配置可直接选用，但不能在这里修改或删除；可另加全局聊天配置。",Modifier.padding(horizontal=UiSpace.page,vertical=8.dp).testTag("config-chat-context"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
        if(screen=="overview"){
            Row(Modifier.padding(horizontal=UiSpace.page,vertical=12.dp).fillMaxWidth().selectableGroup().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp)){
                listOf("聊天模型","向量","语音","画图").forEachIndexed{index,label->
                    Tab(selected=overviewTab==index,onClick={tabs.select(index)},
                        modifier=Modifier.weight(1f).heightIn(min=UiSpace.action).clip(RoundedCornerShape(24.dp)).background(if(overviewTab==index)Paper else Color.Transparent).testTag(listOf("config-tab-chat","config-tab-embedding","config-tab-voice","config-tab-image")[index]),
                        selectedContentColor=ConfigInk,unselectedContentColor=ConfigGreen,text={Text(label,style=MaterialTheme.typography.labelMedium,maxLines=1)})
                }
            }
            if(saveForPurpose!=null){
                LazyColumn(Modifier.fillMaxSize().testTag("config-overview"),state=purposesScroll,contentPadding=PaddingValues(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    if(state.status.isNotBlank())item("status"){Notice(state.status,state.statusError)}
                    item("purposes"){PurposeSelection(state,only=saveForPurpose)}
                }
            }else{
            // 四个用途各占一个 tab，每个 tab 只列这个用途的服务商；互相不看对方的列表。
            PageTabContent(tabs){panel->LazyColumn(Modifier.fillMaxSize().testTag("config-overview"),state=when(panel){0->servicesScroll;1->purposesScroll;2->voiceScroll;else->imageScroll},contentPadding=PaddingValues(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
                if(state.status.isNotBlank())item("status"){Notice(state.status,state.statusError)}
                when(panel){
                0->{
                    val rows=state.configs.filter{config -> state.hostPurposes[config.id]?.let { it == "chat" } ?: (ProviderCatalog.modelKind(config)!=ProviderModelKind.TEXT_EMBEDDING)}
                    item("chat-label"){SectionLabel("聊天模型")}
                    if(rows.isEmpty())item("chat-empty"){Column(Modifier.fillMaxWidth().heightIn(min=180.dp).testTag("config-services-empty"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                        ControlIcon("services",ConfigMuted,56.dp);Spacer(Modifier.height(16.dp))
                        Text("还没有聊天服务",style=MaterialTheme.typography.titleMedium,color=ConfigMuted)
                    }}
                    item("chat-list"){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
                        rows.forEachIndexed{index,config->
                            ChatModelServiceRow(config, state.tests[config.id]?.ok == true,
                                selected = config.id == state.defaultId, canSelect = !state.busy,
                                onOpen = { edit(config,embedding=false) }, onSelect = { state.makeDefault(config) })
                            if(index<rows.lastIndex)HorizontalDivider(Modifier.padding(horizontal=16.dp),color=ConfigLine)
                        }
                        if(rows.isNotEmpty())HorizontalDivider(Modifier.padding(horizontal=16.dp),color=ConfigLine)
                        AddServiceRow("+ 添加聊天服务商","config-add-chat"){add()}
                    }}
                    item("chat-hint"){Text("用于和角色聊天",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
                }
                1->{
                    val rows=state.configs.filter{config -> state.hostPurposes[config.id]?.let { it == "embedding" } ?: (ProviderCatalog.modelKind(config)==ProviderModelKind.TEXT_EMBEDDING)}
                    item("emb-label"){SectionLabel("向量模型")}
                    item("emb-list"){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
                        rows.forEachIndexed{index,config->
                            ModelServiceRow(config,state.tests[config.id]?.ok==true,state.purposes[ModelPurpose.EMBEDDING]==config.id){if(config.id.isHostModel()) state.selectPurpose(ModelPurpose.EMBEDDING,config.id) else edit(config,embedding=true)}
                            if(index<rows.lastIndex)HorizontalDivider(Modifier.padding(horizontal=16.dp),color=ConfigLine)
                        }
                        if(rows.isNotEmpty())HorizontalDivider(Modifier.padding(horizontal=16.dp),color=ConfigLine)
                        AddServiceRow("+ 添加向量服务商","config-add-embedding"){add(true)}
                    }}
                    item("emb-hint"){Text("用于记住和回忆，不影响聊天",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
                }
                2->item("voice"){BreezeVoiceServiceSection { screen = "voice-studio" }}
                else->{
                    item("img-label"){SectionLabel("画图服务")}
                    item("img-list"){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
                        AddServiceRow("+ 添加画图服务商","config-add-image"){imageNotice=true}
                    }}
                    item("img-hint"){Text("还没有配置，先接一家才能让角色发图",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
                }
                }
            }}
            }
        }else if(screen=="providers"){
            OutlinedTextField(query,{query=it.take(80)},placeholder={Text("搜索服务商")},singleLine=true,leadingIcon={ControlIcon("search")},modifier=Modifier.fillMaxWidth().padding(horizontal=UiSpace.page,vertical=12.dp).testTag("provider-search"),shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=Paper,focusedContainerColor=Paper,unfocusedBorderColor=Color.Transparent))
            val groups=ProviderCatalog.groups(embeddingEditor,query,allProviders)
            fun choose(p:ProviderPreset){
                val old=ProviderPresets.get(provider)
                if(original==null||name.isBlank()||name==providerName(old))name=providerName(p)+(if(embeddingEditor)" · 向量" else "")
                prefilledKey="";keyDraft.invalidate();provider=p.id;base=p.base;model="";budgets=budgets.forModel(provider,base,model);key="";reveal=false;advanced=p.base.isBlank();showUnknownModels=false;resetThinking();changed();screen="edit"
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal=UiSpace.page).clip(RoundedCornerShape(18.dp)).background(Paper).testTag("provider-list"),contentPadding=PaddingValues(vertical=4.dp)){
                if(groups.isEmpty())item{Text("没有找到，可选择自定义兼容服务。",Modifier.padding(20.dp),style=MaterialTheme.typography.bodyMedium,color=ConfigMuted);TextButton({choose(ProviderPresets.get("custom"))}){Text("手动填写")}}
                items(groups,key={it.name}){group->val p=group.regions.first()
                    Row(Modifier.fillMaxWidth().clickable{if(group.regions.size==1)choose(p) else regionGroup=group}.testTag("provider-${p.id}").padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                        ProviderMark(p);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(group.name,style=MaterialTheme.typography.titleSmall);val detail=if(p.id=="custom")"手填地址与模型 ID" else group.regions.map{regionName(it)}.filter{it.isNotBlank()}.joinToString(" / ");if(detail.isNotBlank())Text(detail,style=MaterialTheme.typography.labelSmall,color=ConfigMuted,modifier=Modifier.padding(top=3.dp))}
                    };HorizontalDivider(Modifier.padding(start=72.dp,end=18.dp),color=ConfigLine)
                }
                if(!allProviders&&query.isBlank())item{TextButton({allProviders=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("providers-all")){Text("全部服务")}}
            }
            regionGroup?.let{group->AlertDialog(onDismissRequest={regionGroup=null},title={Text("${group.name} · 选择地域")},text={Column{group.regions.forEach{p->TextButton({regionGroup=null;choose(p)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("provider-region-${p.id}")){Text(regionName(p))}}}},confirmButton={TextButton({regionGroup=null}){Text("取消")}})}
            Text(if(embeddingEditor)"默认显示已核对文本向量接口；其他服务可手填。" else "预设不代表已连接；请选择对应地域的密钥。",Modifier.padding(20.dp),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
        }else{
            ModelEditorForm {
                if(embeddingEditor)Text("用于记忆语义检索，保存后指定为记忆向量；不会用于聊天或总结记忆。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                Row(Modifier.fillMaxWidth().clickable(enabled=!state.saving){keyDraft.invalidate();reveal=false;state.clearResult();modelQuery="";query="";providerReturn="edit";screen="providers"}.testTag("provider-picker").padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){ProviderMark(preset);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(providerName(preset),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium);Text(providerDetail(preset),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)};Text("更换",color=ConfigGreen,style=MaterialTheme.typography.bodySmall)}
                if(preset.id=="opencode-go")Notice(preset.hint,false) else if(preset.hint.isNotBlank())Text(preset.hint,style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                if(state.status.isNotBlank()&&!state.fetching&&state.models.isEmpty())Notice(state.status,state.statusError)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper).padding(horizontal=16.dp,vertical=6.dp)){
                    ConfigField(name,{name=it.take(60);changed()},"配置名称","config-name")
                    val retained=original?.baseUrl==base.trim().trimEnd('/')&&original?.providerId==provider
                    TextField(key,{keyDraft.invalidate();key=it.take(4096);changed()},label={Text("API Key")},placeholder={Text(if(retained)"留空沿用已保存的密钥" else "输入你的密钥")},singleLine=true,visualTransformation=if(reveal)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={IconButton(onClick={reveal=!reveal},modifier=Modifier.testTag("config-key-reveal").semantics{contentDescription=if(reveal)"隐藏密钥" else "显示密钥"}){ControlIcon(if(reveal)"eye" else "eye-off")}},colors=fieldColors(),modifier=Modifier.fillMaxWidth().testTag("config-key"))
                    if(original!=null&&!retained)Text("更换地址或服务商后需要重新输入密钥。",Modifier.padding(horizontal=4.dp,vertical=4.dp).testTag("config-key-reentry"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                    if(reveal)Text("显示密钥时禁止系统截图。",Modifier.padding(horizontal=4.dp,vertical=4.dp).testTag("config-key-secure"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                    ConfigField(model,{chooseModel(it.take(200))},if(embeddingEditor)"向量模型 ID" else "模型 ID / Endpoint ID","config-model",placeholder="选择或填写模型")
                    if(preset.modelsPath!=null||preset.modelsUrl!=null){
                        Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically){
                            if(state.fetching)TextButton(onClick={state.cancelFetch()},modifier=Modifier.testTag("config-fetch-cancel")){Text("取消",style=MaterialTheme.typography.bodySmall)}
                            TextButton(onClick={ProcessDiagnostics.action(ProcessAction.FETCH_MODELS);reveal=false;modelQuery="";state.request(draft(),enteredKey(),true)},enabled=!state.busy,modifier=Modifier.testTag("config-fetch")){
                                if(state.fetching){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                                Text(if(state.fetching)"正在获取模型…" else if(state.statusError)"重新获取" else "获取模型",color=if(state.fetching)ConfigMuted else Color.Unspecified,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        if(preset.modelsUrl!=null)Text(preset.modelsHint,Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("config-models-note"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                    }
                    else Text(preset.modelsHint,Modifier.padding(vertical=12.dp,horizontal=16.dp).testTag("config-models-unavailable"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                }
                if(state.models.isNotEmpty()){
                    Notice(state.status,state.statusError)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper).padding(12.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("选择模型（含向量等类型）",style=MaterialTheme.typography.labelLarge);Text("点选填入，保存后生效",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
                        OutlinedTextField(modelQuery,{modelQuery=it.take(200)},placeholder={Text("筛选已获取目录（不填入模型）",style=MaterialTheme.typography.bodyMedium)},singleLine=true,leadingIcon={ControlIcon("search")},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp).testTag("model-search"),shape=RoundedCornerShape(12.dp))
                        val typed=state.models.filter{!embeddingEditor||showUnknownModels||ProviderCatalog.modelKind(draft().copy(modelId=it))==ProviderModelKind.TEXT_EMBEDDING}
                        if(embeddingEditor)TextButton({showUnknownModels=!showUnknownModels}){Text(if(showUnknownModels)"仅显示已核对文本向量" else "显示未核实目录项（可手填）")}
                        val filtered=typed.filter{it.contains(modelQuery,ignoreCase=true)}
                        if(filtered.isEmpty())Text("没有匹配的模型，可继续手填。",Modifier.padding(12.dp).testTag("model-options-empty"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max=220.dp).testTag("model-options")){
                            items(filtered,key={it}){id->val selected=id==model
                                Row(Modifier.fillMaxWidth().heightIn(min=UiSpace.action).clip(RoundedCornerShape(9.dp)).background(if(selected)SelectedBackground else Paper).clickable{chooseModel(id)}.semantics{this.selected=selected}.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Text(id,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=if(selected)ConfigGreen else ConfigInk);if(selected)Text("✓ 当前",Modifier.padding(start=8.dp),style=MaterialTheme.typography.bodySmall,color=ConfigGreen)}
                            }
                        }
                    }
                }
                if(embeddingEditor&&ProviderCatalog.modelKind(draft())!=ProviderModelKind.TEXT_EMBEDDING)Text("此 ID 的文本向量兼容性未核实；可手填保存，多模态专用接口尚未适配。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                ProviderRequestMetadata.compatibilityNotice(draft())?.let{Notice(it,true)}
                if (!embeddingEditor) {
                    Column {
                        SectionLabel("回复方式")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = streamReplies, onClick = { if(!streamReplies){streamReplies = true;changed()} }, label = { Text("流式") },
                                modifier = Modifier.testTag("reply-mode-stream"))
                            FilterChip(selected = !streamReplies, onClick = { if(streamReplies){streamReplies = false;changed()} }, label = { Text("非流式") },
                                modifier = Modifier.testTag("reply-mode-complete"))
                        }
                    }
                }
                if(embeddingEditor)Text("向量维度：自动，以模型实际返回为准。无需填写 4096，不向服务强发 dimensions 参数；不同维度不会混合计算。",Modifier.testTag("embedding-dimensions"),style=MaterialTheme.typography.bodyMedium,color=ConfigMuted)
                Column(Modifier.fillMaxWidth()){
                    if(embeddingEditor){
                        TextButton(onClick={reveal=false;state.testEmbedding(draft(),enteredKey())},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("config-test")){Text(if(state.testing)"正在测试向量…" else "测试向量连接（返回维度）")}
                    }else Row(Modifier.heightIn(min=48.dp).clickable(enabled=!state.busy,role=Role.Button){ProcessDiagnostics.action(ProcessAction.TEST_MODEL);reveal=false;state.request(draft(),enteredKey(),false)}.testTag("config-test").padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){ControlIcon("plug");Spacer(Modifier.width(8.dp));Text(if(state.probe.phase==ProbePhase.TESTING)"正在等模型回话…" else if(state.probe.phase==ProbePhase.FAILED)"重新测试" else "测试连接",color=ConfigGreen,style=MaterialTheme.typography.bodyMedium);if(state.probe.phase==ProbePhase.TESTING)CircularProgressIndicator(Modifier.padding(start=12.dp).size(18.dp),strokeWidth=2.dp)}
                    val result=state.probe
                    Text(when(result.phase){ProbePhase.UNTESTED->"未测试";ProbePhase.TESTING->"正在等模型回话…";else->result.text},Modifier.padding(start=4.dp,top=2.dp).testTag("config-probe-inline"),style=MaterialTheme.typography.bodySmall,color=when(result.phase){ProbePhase.REACHABLE->ConfigGreen;ProbePhase.FAILED->ConfigErrorColor;else->ConfigMuted})
                    Text(if(embeddingEditor)"点击测试才向 /embeddings 发送一句合成文本，可能按服务商规则计费；不会发送聊天或记忆。" else "点击测试会真发一句话让模型回，可能按服务商规则计费；不会发送聊天记录或记忆。",Modifier.padding(start=4.dp,top=8.dp).testTag("config-test-note"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                }
                ModelAdvancedSettings(advanced, onToggle = { advanced = !advanced }) {
                        ConfigField(base,{prefilledKey="";keyDraft.invalidate();base=it.take(500);budgets=budgets.forModel(provider,base,model);key="";reveal=false;resetThinking();changed()},"服务地址（HTTPS）","config-url",keyboard=KeyboardType.Uri)
                        if(!embeddingEditor){
                        ConfigField(budgets.context,{budgets=budgets.manual(context=it.filter(Char::isDigit).take(7));changed()},"上下文容量（按模型文档）","config-context",keyboard=KeyboardType.Number)
                        ConfigField(budgets.chat,{budgets=budgets.manual(chat=it.filter(Char::isDigit).take(6));changed()},"聊天生成上限 Token","config-output",keyboard=KeyboardType.Number)
                        ConfigField(budgets.memory,{budgets=budgets.manual(memory=it.filter(Char::isDigit).take(6));changed()},"记忆生成上限（Token）","config-memory-output",keyboard=KeyboardType.Number)
                        Text("填 0，沿用聊天上限。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        val known=ModelTokenPolicy.capability(provider,base,model)
                        if(known!=null){
                            Text("已核对：上下文 ${known.context}，生成最多 ${known.maxOutput}。"+(if(known.reasoning)"当前模型默认推理，推理与正文共用额度。"else"当前模型为非推理模型。"),Modifier.testTag("config-budget-known"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                            TextButton(onClick={budgets=budgets.apply(provider,base,model).copy(automatic=false);changed()},enabled=!state.busy,modifier=Modifier.testTag("config-budget-apply")){Text("使用模型建议（仅填入草稿）")}
                        }else Text("尚未核对该模型容量，请按服务文档填写；保留手动输入。",Modifier.testTag("config-budget-unknown"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        val modes=ThinkingFields.options(draft())
                        if(preset.id!="custom"){
                            val shownMode=ThinkingFields.effectiveMode(draft())
                            Column(Modifier.fillMaxWidth().padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text("推理强度", style=MaterialTheme.typography.labelLarge)
                                Box(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth().heightIn(min=54.dp).clip(RoundedCornerShape(14.dp)).background(Paper).border(.5.dp,ConfigLine,RoundedCornerShape(14.dp)).then(if(modes.size>1)Modifier.clickable{thinkingMenu=true} else Modifier).testTag("thinking-picker").padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                                        Text(thinkingModeLabel(shownMode),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=ConfigInk,maxLines=1)
                                        if(modes.size>1)Text("⌄",color=ConfigMuted)
                                    }
                                    DropdownMenu(expanded=thinkingMenu,onDismissRequest={thinkingMenu=false},containerColor=Paper,tonalElevation=0.dp){
                                        modes.forEach{mode->DropdownMenuItem(
                                            text={Column{Text(thinkingModeLabel(mode),style=MaterialTheme.typography.bodyMedium,color=ConfigInk);if(mode==ThinkingMode.DEFAULT)Text(mode.hint,style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}},
                                            trailingIcon={if(shownMode==mode)Text("✓",style=MaterialTheme.typography.bodyMedium,color=ConfigInk)},
                                            onClick={thinkingMode=mode;thinkingMenu=false;changed()},
                                            modifier=Modifier.testTag("thinking-${mode.key}").background(if(shownMode==mode)ThinkingSelected else Color.Transparent)
                                        )}
                                    }
                                }
                            }
                        }
                        if(preset.id=="custom"){
                            val customProblem=ThinkingFields.customError(thinkingCustom)
                            ConfigField(thinkingCustom,{thinkingCustom=it.take(4000);changed()},"自定义思考字段（可选）","config-thinking-custom")
                            Text(customProblem?:"填一段花括号设置，原样带进请求，例如 {\"reasoning_effort\":\"high\"}。model、messages 这些关键字段不能写。",Modifier.testTag("config-thinking-custom-note"),style=MaterialTheme.typography.bodySmall,color=if(customProblem!=null)ConfigErrorColor else ConfigMuted)
                        }
                        }
                }
                if(saveForPurpose==null)original?.let{saved->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={state.makeDefault(saved)},enabled=!state.busy&&state.defaultId!=saved.id,modifier=Modifier.testTag("config-default-${saved.id}")){Text(if(state.defaultId==saved.id)"已为默认" else "设为默认",style=MaterialTheme.typography.bodySmall)};TextButton(onClick={deleting=saved},enabled=!state.busy,modifier=Modifier.testTag("config-delete-${saved.id}")){Text("删除配置",style=MaterialTheme.typography.bodySmall,color=ConfigErrorColor)}}}
                Spacer(Modifier.height(12.dp))
            }
        }
    }}
    if(imageNotice)AlertDialog(onDismissRequest={imageNotice=false},title={Text("画图功能还没接入")},
        text={Text("现在还不能添加画图服务商。等画图接上以后，这里会列出可以选的服务。")},
        confirmButton={TextButton({imageNotice=false}){Text("知道了")}})
    deleting?.let{config->AlertDialog(onDismissRequest={deleting=null},title={Text("删除这份配置？")},text={Text("同时清除保存的密钥。聊天记录不受影响。")},confirmButton={TextButton(onClick={state.delete(config);deleting=null;key="";reveal=false;screen="overview"}){Text("确认删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}

// Keep the editor and advanced fields in separate DEX methods from the navigation branches.
@Composable
private fun ModelEditorForm(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("config-editor")
            .padding(horizontal = UiSpace.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun ModelAdvancedSettings(expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onToggle)
            .testTag("config-advanced").padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("高级设置", style = MaterialTheme.typography.labelLarge)
                Text("服务地址与兼容方式", style = MaterialTheme.typography.bodySmall,
                    color = ConfigMuted, modifier = Modifier.padding(top = 4.dp))
            }
            ControlIcon(if (expanded) "up" else "chevron")
        }
        if (expanded) Column(Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun ChatModelServiceRow(
    config: ModelConfig, testOk: Boolean, selected: Boolean, canSelect: Boolean,
    onOpen: () -> Unit, onSelect: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val actionWidth = maxWidth * 0.45f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth().clickable(enabled = !config.id.isHostModel(), role = Role.Button, onClick = onOpen)
                    .testTag("config-edit-${config.id}").heightIn(min = 48.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(config.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(if (config.id.isHostModel()) "来自 NekoBot（在 NekoBot 中修改） · ${config.modelId}" else "${providerName(ProviderPresets.get(config.providerId))} · ${config.modelId}",
                        style = MaterialTheme.typography.labelSmall, color = ConfigMuted)
                }
                if (!config.id.isHostModel()) FlowRow(Modifier.clickable(role = Role.Button, onClick = onOpen)
                    .testTag("config-manage-${config.id}").sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    Box(contentAlignment = Alignment.CenterStart) {
                        Text(if (testOk) "已连接" else "未连接", style = MaterialTheme.typography.labelSmall,
                            color = if (testOk) ConfigSuccess else ConfigMuted)
                        if (testOk) ConnectedLight(Modifier.offset(x = (-10).dp))
                    }
                    Text(if (testOk) "管理" else "连接", style = MaterialTheme.typography.labelSmall, color = ConfigInk)
                }
            }
            val end = Modifier.widthIn(max = actionWidth)
            if (selected) {
                Row(end.testTag("config-current-chat-${config.id}").semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✓", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge, color = ConfigInk)
                    Text("当前聊天模型", style = MaterialTheme.typography.labelLarge, color = ConfigInk)
                }
            } else {
                Box(end.clickable(enabled = canSelect, role = Role.Button,
                    onClickLabel = "将${config.name}设为聊天模型", onClick = onSelect)
                    .testTag("config-use-chat-${config.id}").sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentAlignment = Alignment.CenterEnd) {
                    Text("设为聊天模型", style = MaterialTheme.typography.labelLarge, color = ConfigInk)
                }
            }
        }
    }
}

@Composable private fun ConnectedLight(modifier: Modifier) {
    val reduced = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val brightness = if (reduced) null else rememberInfiniteTransition(label = "连接状态").animateFloat(
        initialValue = .55f, targetValue = .9f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "亮度")
    Box(modifier.size(6.dp).graphicsLayer { alpha = brightness?.value ?: .85f }
        .background(ConfigSuccess, CircleShape).testTag("chat-connection-light"))
}

/** 一个用途下的一行：名字 + 状态 + 小胶囊，行高 44dp，和语音服务那一行一致。 */
@Composable private fun ModelServiceRow(config:ModelConfig,testOk:Boolean,isDefault:Boolean,onOpen:()->Unit){
    val p=ProviderPresets.get(config.providerId)
    Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clickable(role=Role.Button,onClick=onOpen)
        .testTag("config-edit-${config.id}").padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text(config.name,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(if (config.id.isHostModel()) "来自 NekoBot（在 NekoBot 中修改） · ${config.modelId}" else "${providerName(p)} · ${config.modelId}",style=MaterialTheme.typography.labelSmall,color=ConfigMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        if(testOk){
            Box(Modifier.size(7.dp).clip(CircleShape).background(ConfigSuccess));Spacer(Modifier.width(5.dp))
            Text("已连接",style=MaterialTheme.typography.labelSmall,color=ConfigSuccess)
        }else Text("未连接",style=MaterialTheme.typography.labelSmall,color=ConfigMuted)
        if(isDefault)Text("默认",Modifier.padding(start=6.dp),style=MaterialTheme.typography.labelSmall,color=ConfigGreen)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(CircleShape).background(Accent).clickable(onClick=onOpen)
            .padding(horizontal=12.dp,vertical=5.dp).testTag("config-manage-${config.id}"),contentAlignment=Alignment.Center){
            Text(if(testOk)"管理" else "连接",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** 卡片最下面那一行「+ 添加…」。 */
@Composable private fun AddServiceRow(label:String,tag:String,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=44.dp).clickable(role=Role.Button,onClick=onClick)
        .testTag(tag).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
        Text(label,style=MaterialTheme.typography.bodyMedium,color=ConfigGreen)
    }
}

@Composable private fun Notice(text:String,error:Boolean){Text(text,Modifier.fillMaxWidth().testTag("config-status"),style=MaterialTheme.typography.bodySmall,color=if(error)ConfigErrorColor else ConfigGreen)}
@Composable private fun fieldColors()=TextFieldDefaults.colors(focusedContainerColor=Paper,unfocusedContainerColor=Paper,focusedIndicatorColor=ConfigGreen,unfocusedIndicatorColor=ConfigLine)
@Composable private fun ConfigField(value:String,onChange:(String)->Unit,label:String,tag:String,placeholder:String="",keyboard:KeyboardType=KeyboardType.Text){TextField(value,onChange,label={Text(label)},placeholder={if(placeholder.isNotEmpty())Text(placeholder)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=keyboard),modifier=Modifier.fillMaxWidth().testTag(tag),colors=fieldColors())}
@Composable private fun ProviderMark(p:ProviderPreset){Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),contentAlignment=Alignment.Center){if(p.id=="custom")NavigationIcon(2,false) else Text(providerName(p).take(1),style=MaterialTheme.typography.titleMedium,color=Muted,fontWeight=FontWeight.Medium)}}
@Composable private fun SaveAction(enabled:Boolean,onClick:()->Unit){CompactSaveAction(enabled,onClick)}
