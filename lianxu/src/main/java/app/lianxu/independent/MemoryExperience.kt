package app.lianxu.independent

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Lianxu content-first memory browser. Operational metadata lives in details and maintenance. */
@Composable internal fun MemoryExperience(vm:AppState,friend:Friend,worldTabs:@Composable ()->Unit={},onDetail:(Boolean)->Unit={}){
    val repo=vm.processing;val scope=rememberCoroutineScope()
    val summaryRoles by repo.store.memorySummary.roles.collectAsState();val summarizing=friend.id in summaryRoles
    var memories by remember(friend.id){mutableStateOf(emptyList<StoredMemory>())};var candidates by remember(friend.id){mutableStateOf(emptyList<ProcessingRepository.Candidate>())}
    var reviews by remember(friend.id){mutableStateOf(emptyList<MemoryBatchRepository.Review>())};var budget by remember(friend.id){mutableStateOf<CoreBudgetState?>(null)}
    var task by remember(friend.id){mutableStateOf(ProcessingRepository.TaskStatus())};var policy by remember(friend.id){mutableStateOf(ProcessingPolicy())}
    var mine by rememberSaveable(friend.id){mutableStateOf(false)}
    var listMenu by remember{mutableStateOf(false)}
    var clarity by remember{mutableStateOf(emptyMap<String,Double>())}
    var edges by remember(friend.id,memories){mutableStateOf(emptyList<MemoryRecall.Edge>())}
    LaunchedEffect(memories,vm.processingRevision){
        clarity=repo.memoryUiClarity(memories)
        edges=repo.read{db->visibleSkyEdges(memories,MemoryAssociations(repo).verified(db,friend.id,repo.usableMemories(db,friend.id)))}
    }
    val memoryLists=key(friend.id){listOf(rememberLazyListState(),rememberLazyListState())}

    var detailId by rememberSaveable(friend.id){mutableStateOf<String?>(null)};var mode by rememberSaveable(friend.id){mutableStateOf("browse")}
    var editing by remember(friend.id){mutableStateOf(false)};var busy by remember(friend.id){mutableStateOf(false)};var error by remember(friend.id){mutableStateOf("")}
    var versions by remember(friend.id){mutableStateOf<List<StoredMemory>?>(null)};var evidence by remember(friend.id){mutableStateOf<StoredMemory?>(null)}
    var sourceRows by remember(friend.id){mutableStateOf<List<SourceDialogue>?>(null)};var sourceEdit by remember(friend.id){mutableStateOf<SourceDialogue?>(null)};var demote by remember(friend.id){mutableStateOf<StoredMemory?>(null)}
    var coreGoal by rememberSaveable(friend.id){mutableStateOf("")}
    var failure by remember(friend.id){mutableStateOf<MemoryBatchRepository.Failure?>(null)}
    LaunchedEffect(friend.id,vm.processingRevision){memories=repo.memories(friend.id);candidates=repo.candidates(friend.id);reviews=MemoryBatchRepository(repo).reviews(friend.id);budget=repo.coreBudget(friend.id);task=repo.memoryStatus(friend.id);failure=MemoryBatchRepository(repo).failure(friend.id);policy=repo.policy(friend.id).first;if(coreGoal.isEmpty())coreGoal=policy.coreGoal.toString()}
    fun action(block:suspend()->Unit){if(busy)return;busy=true;error="";scope.launch{try{block();vm.refreshProcessing()}catch(e:CancellationException){throw e}catch(e:Exception){error=processingMessage((e as? ProcessingRejected)?.code?:"FORMAT")}finally{busy=false}}}
    fun back(){if(detailId!=null)detailId=null else if(mode!="browse")mode="browse" else vm.back()}
    LaunchedEffect(detailId,mode){onDetail(detailId!=null||mode!="browse")}
    BackHandler(detailId!=null||mode!="browse"){back()}
    val detail=memories.firstOrNull{it.id==detailId}
    var deleting by remember{mutableStateOf<StoredMemory?>(null)}
    var detailMenu by remember{mutableStateOf(false)}
    var dialogue by remember(detailId){mutableStateOf<SourceDialogue?>(null)}
    LaunchedEffect(detail,vm.processingRevision){dialogue=detail?.evidence?.firstOrNull()?.let{e->repo.sources(friend.id).let{memorySourceDialogue(it,e)}}}
    Column(Modifier.fillMaxSize().background(Chrome)){
        AppTopBar(if(detail!=null)"记忆详情" else if(mode=="maintenance")"总结记忆与维护" else if(mode=="candidates")"待确认" else friend.name,{back()},extra={if(detail!=null)Box{
            TextButton({detailMenu=true},modifier=Modifier.testTag("memory-detail-menu")){Text("⋮")}
            DropdownMenu(detailMenu,{detailMenu=false}){
                DropdownMenuItem({Text(if(detail.core)"改为普通记忆" else "设为核心")},{detailMenu=false;if(detail.core)demote=detail else action{repo.chooseCore(friend.id,detail.id,detail.version,true)}},enabled=!summarizing)
                DropdownMenuItem({Text(if(detail.locked)"解锁" else "锁定")},{detailMenu=false;action{repo.setLock(friend.id,detail.id,!detail.locked)}},enabled=!summarizing,modifier=Modifier.testTag("memory-lock-toggle"))
                DropdownMenuItem({Text("版本历史")},{detailMenu=false;action{versions=repo.history(friend.id,detail.id)}})
                DropdownMenuItem({Text("查看来源快照")},{detailMenu=false;evidence=detail})
            }
        } else if(mode=="browse"&&detail==null)Box{TextButton({listMenu=true}){Text(if(reviews.any{!it.acknowledged}||budget?.overGoal==true)"更多 •" else "更多")};DropdownMenu(listMenu,{listMenu=false}){DropdownMenuItem({Text("总结记忆与维护")},{listMenu=false;mode="maintenance"},modifier=Modifier.testTag("memory-maintenance"));if(candidates.isNotEmpty())DropdownMenuItem({Text("待核对 ${candidates.size} 条")},{listMenu=false;mode="candidates"},modifier=Modifier.testTag("memory-candidates"))}}})
        if(detailId==null && mode=="browse")worldTabs()
        if(summarizing)Text("正在总结记忆，暂不能修改。",Modifier.padding(horizontal=UiSpace.page,vertical=8.dp).testTag("memory-summary-guard"),style=MaterialTheme.typography.bodySmall,color=Muted)
        if(error.isNotEmpty())Text(error,Modifier.padding(horizontal=UiSpace.page,vertical=8.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
        if(mode=="maintenance"&&!summarizing)failure?.let{f->Text(memoryFailureText(f),Modifier.padding(horizontal=UiSpace.page,vertical=8.dp).testTag("memory-batch-failure"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        if(detail!=null){
            Column(Modifier.fillMaxSize().padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Surface(color=Paper,shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp)){
                        Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                            Text(detail.text,style=MaterialTheme.typography.titleLarge)
                            Text(if(detail.manual)"我写的" else "${friend.name}从聊天里记下的",color=Muted,style=MaterialTheme.typography.bodySmall)
                            detail.annualMonthDay?.let{Text("生日 $it",color=Muted,style=MaterialTheme.typography.bodySmall)}
                            detail.dueDate?.let{Text("到期提醒 $it",color=Muted,style=MaterialTheme.typography.bodySmall)}
                        }
                    }
                    Surface(color=Paper,shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp)){
                        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                            Text("记下的时间　"+memoryUiDate(detail.createdAt),style=MaterialTheme.typography.bodyMedium)
                            HorizontalDivider(color=Line)
                            val value=clarity[detail.id]?:100.0
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                Text("记得多清楚　● "+memoryUiClarityLabel(value),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(progress={value.toFloat()/100},modifier=Modifier.width(64.dp).background(MaterialTheme.colorScheme.surfaceVariant,androidx.compose.foundation.shape.CircleShape).padding(2.dp),color=GreenText,trackColor=MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                    if(!detail.manual&&detail.evidence.isNotEmpty()){
                        val e=detail.evidence.first()
                        Surface(color=Paper,shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp)){
                            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Text("来自 "+memoryUiSourceDate(e.sourceAt)+"的聊天",color=Muted,style=MaterialTheme.typography.bodySmall)
                                if(dialogue!=null){
                                    Text(dialogue!!.user.text,Modifier.align(Alignment.End).background(SelectedBackground,androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(14.dp),maxLines=3,overflow=TextOverflow.Ellipsis)
                                    dialogue!!.assistant?.let{Text(it.text,Modifier.align(Alignment.Start).background(Chrome,androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(14.dp),maxLines=3,overflow=TextOverflow.Ellipsis)}
                                }else Text(e.quote.ifBlank{e.snapshot},Modifier.background(Chrome).padding(14.dp),maxLines=3,overflow=TextOverflow.Ellipsis)
                                if(memoryChatIndex(vm.messages,e.source) != null){
                                    HorizontalDivider(color=Line,thickness=.5.dp)
                                    TextButton({scope.launch{if(!vm.jumpToMemoryChat(e.source)){error="那段聊天已经不在了"}}},modifier=Modifier.fillMaxWidth().testTag("memory-jump-chat")){
                                        Text("跳到这段聊天 ›",color=MaterialTheme.colorScheme.primary)
                                    }
                                } else Text("那段聊天已经不在了",color=Muted,style=MaterialTheme.typography.bodySmall)

                            }
                        }
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                if(detail.category.modern)Button({editing=true},enabled=!summarizing,modifier=Modifier.weight(1f).testTag("memory-edit"),colors=ButtonDefaults.buttonColors(containerColor=SelectedBackground,contentColor=GreenText)){Text("修改")}
                    OutlinedButton({deleting=detail},enabled=!summarizing&&!busy,modifier=Modifier.weight(1f).testTag("memory-delete"),border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.error.copy(alpha=.3f)),colors=ButtonDefaults.outlinedButtonColors(containerColor=Paper,contentColor=MaterialTheme.colorScheme.error)){Text("删除")}
                }
            }
        }else if(mode=="maintenance"){
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(18.dp)){
                Text(taskDescription(task),Modifier.testTag("memory-task-status"),style=MaterialTheme.typography.bodyLarge)
                reviews.forEach{r->Text("这条内容在总结记忆后被改动",style=MaterialTheme.typography.titleSmall);Text(r.current.text,style=MaterialTheme.typography.bodyLarge);Text("保留当前版本，不跳过来源检查或完整回执。",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(!r.acknowledged)TextButton({action{MemoryBatchRepository(repo).keepCurrent(friend.id,r.batchId,r.current.id,r.current.version)}},enabled=!summarizing,modifier=Modifier.testTag("batch-keep-${r.current.id}")){Text("保留当前版本，允许本批继续")}else Text("已确认，重试后补齐回执。",color=GreenText,style=MaterialTheme.typography.bodySmall)}
                Row{TextButton({vm.retryMemory(friend.id)},enabled=!summarizing&&!busy&&reviews.none{!it.acknowledged}&&task.state !in setOf("RUNNING","WAITING"),modifier=Modifier.testTag("memory-retry")){Text("总结记忆 / 重试")};TextButton({vm.cancelProcessing(friend.id)}){Text("停止任务")}}
                budget?.let{b->Text("核心 ${b.used} / 维护目标 ${b.goal} Token",Modifier.testTag("core-budget-status"));if(b.overGoal)Text("核心超过维护目标。人工、锁定及进行中的批次保留，请核对后选择；没有静默截断。",Modifier.testTag("core-budget-warning"),style=MaterialTheme.typography.bodySmall,color=Muted)
                    b.entries.forEach{m->Column{Text(m.text,style=MaterialTheme.typography.bodyMedium);TextButton({demote=m},enabled=!summarizing,modifier=Modifier.testTag("core-demote-${m.id}")){Text("改为普通记忆")}}}}
                HorizontalDivider(color=Line)
                Card(onClick={vm.open("settings-memory",friend.id)},modifier=Modifier.fillMaxWidth().testTag("memory-rules-open")){Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("记忆规则",style=MaterialTheme.typography.titleMedium);Text("调整保留数量与回顾间隔",style=MaterialTheme.typography.bodySmall,color=Muted)}}}
                AppField(coreGoal,{coreGoal=it.take(5)},"核心维护目标（Token）","memory-core-goal",true)
                TextButton({action{val p=policy.copy(coreGoal=coreGoal.toIntOrNull()?:0);if(!p.valid())throw ProcessingRejected("CAPACITY");repo.savePolicy(friend.id,p);repo.maintainCore(friend.id)}},enabled=!summarizing){Text("保存核心目标")}
                TextButton({action{sourceRows=repo.sources(friend.id)}},modifier=Modifier.testTag("processing-sources")){Text("回查聊天原话")}
            }
        }else if(mode=="candidates"){
            LazyColumn(Modifier.fillMaxSize().padding(horizontal=UiSpace.page),verticalArrangement=Arrangement.spacedBy(20.dp)){
                item{Text("先核对，再成为记忆",style=MaterialTheme.typography.titleLarge)}
                items(candidates,key={it.id}){c->Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text(c.proposed.text,style=MaterialTheme.typography.bodyLarge);Text(c.reason,Modifier.testTag("candidate-state-${c.id}"),style=MaterialTheme.typography.bodySmall,color=Muted)
                    val target=memories.firstOrNull{it.id==c.targetId};target?.let{Text("当前内容：${it.text}",style=MaterialTheme.typography.bodyMedium)}
                    Row{TextButton({action{repo.resolveCandidate(friend.id,c.id,false)}},enabled=!summarizing&&!busy){Text("保留原内容")};TextButton({action{repo.resolveCandidate(friend.id,c.id,true,target?.version)}},enabled=c.valid&&!busy&&!summarizing,modifier=Modifier.testTag("candidate-accept-${c.id}")){Text("采用并人工确认")}}
                    TextButton({evidence=c.proposed}){Text("查看候选来源快照")};HorizontalDivider(color=Line)
                }}
            }
        }else{
            Column(Modifier.fillMaxSize().padding(horizontal=UiSpace.page).testTag("memory-browser")){
                Text("流年竖脉",Modifier.padding(top=10.dp),fontFamily=androidx.compose.ui.text.font.FontFamily.Serif,style=MaterialTheme.typography.headlineSmall,color=Ink)
                Row(Modifier.fillMaxWidth().selectableGroup().padding(vertical=10.dp).background(Line.copy(alpha=.35f),androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(4.dp)){
                    listOf(false to "她记下的",true to "我写的").forEach{(value,label)->
                        Box(Modifier.weight(1f).background(if(mine==value)Paper else androidx.compose.ui.graphics.Color.Transparent,androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).selectable(mine==value,role=androidx.compose.ui.semantics.Role.Tab){mine=value}.testTag(if(value)"memory-tab-mine"else"memory-tab-hers").padding(vertical=12.dp),contentAlignment=Alignment.Center){Text(label,color=if(mine==value)MaterialTheme.colorScheme.onSurface else Muted)}
                    }
                }
                if(failure!=null&&!summarizing)TextButton({mode="maintenance"},modifier=Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer.copy(alpha=.45f),androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).testTag("memory-batch-failure")){Text("有一段聊天没总结成功　重试 ›",maxLines=1)}
                val shown=remember(memories,mine){memories.filter{it.manual==mine}.sortedByDescending{it.createdAt}}
                MemoryTimeline(shown,memories,edges,clarity,memoryLists[if(mine)1 else 0],mine,!summarizing,
                    {editing=true},{detailId=it})
            }
        }
    }

    deleting?.let{m->AlertDialog({deleting=null},title={Text("删除这条记忆？")},text={Text("删除后她不会再想起这件事。")},confirmButton={TextButton({action{repo.deleteMemory(friend.id,m.id);deleting=null;detailId=null}},enabled=!summarizing&&!busy,modifier=Modifier.testTag("memory-delete-confirm")){Text("删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton({deleting=null}){Text("取消")}})}
    if(editing)MemoryV2Editor(detail,{editing=false},!summarizing){text,c,a,t,d,l,core,lock,birthday->action{repo.manualSave(friend.id,detail?.id,text,c,core,lock,a,t,d,l,birthday);editing=false}}
    demote?.let{m->AlertDialog({demote=null},title={Text("改为普通记忆？")},text={Text("只取消核心标记，正文、来源、锁定和历史保留。")},confirmButton={TextButton({action{repo.chooseCore(friend.id,m.id,m.version,false);demote=null}},enabled=!summarizing,modifier=Modifier.testTag("core-demote-confirm")){Text("确认改为普通")}},dismissButton={TextButton({demote=null}){Text("取消")}})}
    evidence?.let{m->AlertDialog({evidence=null},title={Text("来源快照")},text={Column(Modifier.verticalScroll(rememberScrollState())){if(!m.category.modern)Text("原分类：${m.category.label}，原文与历史保留。 ");if(m.evidence.isEmpty())Text("人工记录，没有模型原话证据。");m.evidence.forEach{e->Text("${e.source.turnId}@${e.source.version} · ${if(e.side=="image")"模型图片或视频观察，非用户原话；原附件消息 ${e.image?.imageSource?.turnId}"else e.side}\n${e.snapshot}\n")};Text("已编辑或撤回的来源仅作历史快照。")}},confirmButton={TextButton({evidence=null}){Text("关闭")}})}
    versions?.let{rows->AlertDialog({versions=null},title={Text("版本历史")},text={Column(Modifier.verticalScroll(rememberScrollState())){rows.forEach{m->Text("v${m.version} · ${m.text}");TextButton({action{repo.restore(friend.id,m.id,m.version);versions=null}},enabled=!summarizing){Text("恢复为新版本")}}}},confirmButton={TextButton({versions=null}){Text("关闭")}})}
    sourceRows?.let{rows->AlertDialog({sourceRows=null},title={Text("聊天原话")},text={Column(Modifier.verticalScroll(rememberScrollState())){rows.forEach{s->Text("${s.source.turnId}@${s.source.version}\n我：${s.user.text}\n${if(s.assistant==null)"" else friend.name+"："+s.assistant.text}");TextButton({sourceRows=null;sourceEdit=s}){Text("编辑 / 撤回 / 历史")}}}},confirmButton={TextButton({sourceRows=null}){Text("关闭")}})}
    sourceEdit?.let{s->SourceEditor(repo,s,{sourceEdit=null}){impact->action{SourceChanges(repo).confirm(impact);sourceEdit=null}}}
}

internal fun lifeLabel(life:MemoryLife)=when(life){MemoryLife.ACTIVE->"进行中";MemoryLife.COMPLETED->"已完成";MemoryLife.CANCELLED->"已取消";MemoryLife.RETRACTED->"已撤回"}
@Composable private fun MemoryV2Editor(old:StoredMemory?,dismiss:()->Unit,editable:Boolean,save:(String,MemoryCategory,MemoryAbout,List<String>,String,MemoryLife,Boolean,Boolean,String?)->Unit){
    var text by rememberSaveable{mutableStateOf(old?.text.orEmpty())};var category by rememberSaveable{mutableStateOf(old?.category?:MemoryCategory.STATE_FACT)};var about by rememberSaveable{mutableStateOf(old?.about?:MemoryAbout.USER)}
    var tags by rememberSaveable{mutableStateOf(old?.tags?.joinToString("，").orEmpty())};var due by rememberSaveable{mutableStateOf(old?.dueDate.orEmpty())};var life by rememberSaveable{mutableStateOf(old?.life?:MemoryLife.ACTIVE)}
    var birthday by rememberSaveable{mutableStateOf(old?.annualMonthDay.orEmpty())}
    var core by rememberSaveable{mutableStateOf(old?.core?:false)};var lock by rememberSaveable{mutableStateOf(old?.locked?:true)};var menu by remember{mutableStateOf(false)};var lifeMenu by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    AlertDialog(dismiss,title={Text(if(old==null)"记下一件小事" else "编辑记忆")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        AppField(text,{text=it.take(8000)},"记忆内容","memory-text",minLines=3)
        Box{TextButton({menu=true},modifier=Modifier.testTag("memory-category-picker")){Text(category.label)};DropdownMenu(menu,{menu=false}){MemoryCategory.entries.filter{it.modern}.forEach{c->DropdownMenuItem({Text(c.label)},{category=c;menu=false},modifier=Modifier.testTag("category-option-${c.name}"))}}}
        Text("关于谁",style=MaterialTheme.typography.labelLarge);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){MemoryAbout.entries.forEach{a->MemoryChoice(about==a,{about=a},{Text(a.label)},modifier=Modifier.testTag("about-${a.name}").heightIn(min=48.dp))}}
        AppField(tags,{tags=it.take(400)},"主题标签（逗号分隔）","memory-tags",true)
        AppField(birthday,{birthday=it.take(5)},"年度生日 MM-DD（可空，不猜年份）","memory-birthday",true)
        AppField(due,{due=it.take(10)},"到期提醒 YYYY-MM-DD（可空）","memory-due",true)
        Text("到期只提醒，不会自动完成。双方也只属于当前AI。",style=MaterialTheme.typography.bodySmall,color=Muted)
        Box{TextButton({lifeMenu=true}){Text("状态："+lifeLabel(life))};DropdownMenu(lifeMenu,{lifeMenu=false}){MemoryLife.entries.forEach{l->DropdownMenuItem({Text(lifeLabel(l))},{life=l;lifeMenu=false},modifier=Modifier.testTag("life-${l.name}"))}}}
        Row(verticalAlignment=Alignment.CenterVertically){Text("核心",Modifier.weight(1f));Switch(core,{core=it},Modifier.testTag("memory-core"));Spacer(Modifier.width(12.dp));Text("锁定");Switch(lock,{lock=it},Modifier.testTag("memory-lock"))}
        if(!editable)Text("正在总结记忆，结束或停止后可保存；草稿保留。",style=MaterialTheme.typography.bodySmall,color=Muted)
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }},modifier=Modifier.imePadding(),confirmButton={TextButton({try{val parsed=MemoryFacets.parseTags(tags);MemoryFacets.dueDate(due);if(birthday.isNotBlank())MemoryDates.monthDay(birthday);save(text,category,about,parsed,due,life,core,lock,birthday.takeUnless{it==old?.annualMonthDay.orEmpty()})}catch(_:Exception){error="请检查标签数量、长度和日期格式。"}},enabled=editable&&text.isNotBlank(),modifier=Modifier.testTag("memory-save")){Text("保存")}},dismissButton={TextButton(dismiss){Text("取消")}})
}

@Composable private fun MemoryChoice(selected:Boolean,onClick:()->Unit,label:@Composable ()->Unit,modifier:Modifier=Modifier){
    FilterChip(selected,onClick,label,modifier=modifier.padding(vertical=2.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp),border=BorderStroke(.5.dp,if(selected)GreenText.copy(alpha=.3f) else Line),colors=FilterChipDefaults.filterChipColors(selectedContainerColor=SelectedBackground,selectedLabelColor=GreenText))
}


@Composable
private fun MemoryClarityDot(level:Int,size:Int){
    val shape=androidx.compose.foundation.shape.CircleShape
    val fill=GreenText.copy(alpha=listOf(1f,.7f,.4f,.12f)[level])
    Box(Modifier.size(size.dp).background(fill,shape).then(if(level==3) Modifier.border(1.dp,GreenText.copy(alpha=.4f),shape) else Modifier))
}
