package app.lianxu.independent

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Opening a journal reads the saved projection without a model request. */
@Composable internal fun LearningRecordsScreen(vm:AppState,worldTabs:@Composable ()->Unit={},onDetail:(Boolean)->Unit={}){
    val role=vm.activeId
    val friend=vm.friends.firstOrNull{it.id==role}
    val revision=vm.processingRevision
    val scope=rememberCoroutineScope()
    val sourceRevision by CompanionRuntime.revision.collectAsState()
    var retry by remember(role){mutableIntStateOf(0)}
    // A new role or source revision must not display the previous snapshot while loading.
    var records by remember(role,revision,retry){mutableStateOf<List<LearningRecord>?>(null)}
    var failed by remember(role,revision,retry){mutableStateOf(false)}
    LaunchedEffect(role,revision,retry,friend!=null,sourceRevision){
        if(friend==null)return@LaunchedEffect
        try{
            val result=withContext(Dispatchers.IO){vm.companion.learningRecords(role)}
            coroutineContext.ensureActive()
            if(vm.activeId==role&&vm.processingRevision==revision)records=result
        }catch(e:CancellationException){throw e}
        catch(_:Exception){
            coroutineContext.ensureActive()
            if(vm.activeId==role&&vm.processingRevision==revision)failed=true
        }
    }
    if(friend==null){
        Column(Modifier.fillMaxSize().background(Chrome).testTag("learning-page")){
            AppTopBar("学习记录",{vm.back()})
            Text("角色不存在或已移除。",Modifier.padding(UiSpace.page),style=MaterialTheme.typography.bodyLarge,color=Muted)
        }
    }else key(role){
        LearningRecordsContent(friend,records,failed,{retry++},{vm.back()},worldTabs,onDetail){id,action->
            scope.launch{try{vm.companion.controlLearning(role,id,action);retry++}catch(e:CancellationException){throw e}catch(_:Exception){failed=true}}
        }
    }
}

@Composable internal fun LearningRecordsContent(
    friend:Friend,
    records:List<LearningRecord>?,
    loadFailed:Boolean,
    onRetry:()->Unit,
    onBack:()->Unit,
    worldTabs:@Composable ()->Unit={},onDetail:(Boolean)->Unit={},onControl:(String,String)->Unit={_,_->}
){
    var query by rememberSaveable(friend.id){mutableStateOf("")}
    var page by rememberSaveable(friend.id){mutableIntStateOf(0)}
    var searching by rememberSaveable(friend.id){mutableStateOf(false)}
    var archived by rememberSaveable(friend.id){mutableStateOf(false)}
    val kinds=listOf("","habit","interest","viewpoint")
    val lists=key(friend.id){listOf(rememberLazyListState(),rememberLazyListState(),rememberLazyListState(),rememberLazyListState())}

    var detailId by rememberSaveable(friend.id){mutableStateOf<String?>(null)}
    fun back(){if(detailId!=null)detailId=null else onBack()}
    LaunchedEffect(detailId){onDetail(detailId!=null)}
    BackHandler(detailId!=null){back()}
    Column(Modifier.fillMaxSize().background(Chrome).testTag("learning-page")){
        AppTopBar(if(detailId==null)friend.name else "学习详情",{back()},backTag=if(detailId==null)"learning-back"else"detail-back",extra={
            if(detailId==null)IconButton({searching=!searching;if(!searching)query=""},modifier=Modifier.testTag("learning-open-search").semantics{contentDescription="搜索学习"}){ControlIcon("search",Muted)}
        })
        if(detailId==null)worldTabs()
        SoftPage(detailId,{_,to->if(to==null)-1 else 1}){shownId->when{
            loadFailed->Column(Modifier.fillMaxWidth().padding(UiSpace.page).testTag("learning-error"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("学习记录暂时无法读取。",style=MaterialTheme.typography.bodyLarge)
                Text("可以重试，已保存的内容不会因此修改。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                PrimaryAction("重试",Modifier.testTag("learning-retry"),onClick=onRetry)
            }
            records==null->Box(Modifier.fillMaxSize().testTag("learning-loading"),contentAlignment=Alignment.Center){
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
                    CircularProgressIndicator()
                    Text("正在读取学习记录…",style=MaterialTheme.typography.bodyMedium,color=Muted)
                }
            }
            shownId!=null->{
                val detail=records.firstOrNull{it.state.id==shownId}
                if(detail!=null)LearningRecordDetail(friend,detail){action->onControl(detail.state.id,action);if(action=="delete")detailId=null}
                else Column(Modifier.fillMaxWidth().padding(UiSpace.page).testTag("learning-detail-missing"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("这条学习已不在当前记录中。",style=MaterialTheme.typography.bodyLarge)
                    Text("它的来源可能已被修改或撤回。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                    TextButton({detailId=null},modifier=Modifier.heightIn(min=UiSpace.action)){Text("返回列表")}
                }
            }
            else->{
                Column(Modifier.fillMaxSize().padding(horizontal=UiSpace.page)){
                    SoftVisibility(searching){
                        AppField(query,{query=it},"搜索内容或适用情况","learning-search",singleLine=true)
                    }
                    val archivedCount=records.count{it.state.archived}
                    LearningFilters(kinds,page,{page=it},archived,archivedCount,{archived=!archived})
                    SoftPage(page to archived){(shownPage,showArchived)->
                        val kind=kinds[shownPage]
                        val search=query.trim()
                        val shown=records.filter{record->
                            record.state.archived==showArchived && (kind.isEmpty() || record.state.kind==kind) &&
                                (record.state.content.contains(search,true) || record.state.scope.contains(search,true))
                        }.sortedByDescending{it.state.pinned}
                        LazyColumn(Modifier.fillMaxSize().testTag("learning-list"),state=lists[shownPage],
                            contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                            if(showArchived)item{
                                Text("暂时停用、或很久没再提起已自然淡忘的记录会收在这里，聊天时不会参考。",
                                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,RoundedCornerShape(12.dp)).padding(14.dp),
                                    style=MaterialTheme.typography.bodySmall,color=Muted)
                            }
                            if(shown.isEmpty())item{
                                val label=when{
                                    showArchived && archivedCount==0 -> "还没有收起的记录"
                                    records.isEmpty() -> "还没有学习记录"
                                    else -> "没有找到匹配的记录"
                                }
                                Text(label,Modifier.padding(vertical=24.dp).testTag("learning-empty"),color=Muted)
                            }
                            items(shown,key={it.state.id}){record->
                                LearningCard(record){detailId=record.state.id}
                            }
                            if(shown.isNotEmpty() && !showArchived)item{
                                Box(Modifier.fillMaxWidth().padding(vertical=8.dp),contentAlignment=Alignment.Center){
                                    Text("她从平时聊天里慢慢攒下的印象，点开可以调整",style=MaterialTheme.typography.bodySmall,color=Muted)
                                }
                            }
                        }
                    }
                }
            }
        }}
    }
}

private val learningGold=Color(0xFFC49352)
private val cardShape=RoundedCornerShape(18.dp)
private val RoleLearningState.archived get()=state=="paused" || state=="faded"
private val RoleLearningState.remembered get()=pinned && !archived
private val RoleLearningState.displayStatus get()=when{
    state=="paused" -> "已暂停"
    state=="faded" -> "已自然淡忘"
    pinned -> "一直记住"
    dots==1 -> "刚记下"
    else -> familiarity
}

@Composable private fun LearningFilters(kinds:List<String>,page:Int,onPage:(Int)->Unit,archived:Boolean,count:Int,onArchive:()->Unit){
    Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh,CircleShape)
            .selectableGroup().padding(horizontal=3.dp)){
            kinds.forEachIndexed{index,kind->
                val selected=page==index
                Box(Modifier.weight(1f).heightIn(min=48.dp).clip(CircleShape)
                    .selectable(selected,role=Role.Tab,onClick={onPage(index)}).testTag("learning-filter-${kind.ifEmpty{"all"}}"),
                    contentAlignment=Alignment.Center){
                    Text(when(kind){""->"全部";"habit"->"习惯";else->learningKindLabel(kind)},
                        Modifier.background(softColor(if(selected)Paper else Color.Transparent),CircleShape).padding(horizontal=8.dp,vertical=5.dp),
                        style=MaterialTheme.typography.labelLarge,color=softColor(if(selected)Ink else Muted))
                }
            }
        }
        Box(Modifier.heightIn(min=48.dp).clip(CircleShape).selectable(archived,role=Role.Button,onClick=onArchive)
            .testTag("learning-archived"),contentAlignment=Alignment.Center){
            Text(if(count>0)"已收起 $count" else "已收起",Modifier.background(softColor(if(archived)SelectedBackground else Color.Transparent),CircleShape)
                .padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium,color=if(archived)GreenText else Muted)
        }
    }
}

@Composable private fun LearningCategory(state:RoleLearningState,modifier:Modifier=Modifier){
    Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
        Text(learningKindLabel(state.kind),Modifier.background(SelectedBackground,CircleShape).padding(horizontal=8.dp,vertical=3.dp),
            style=MaterialTheme.typography.labelSmall,color=GreenText)
        Text(state.scope,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=Muted)
    }
}

@Composable private fun HeatDots(state:RoleLearningState){
    val color=if(state.remembered)learningGold else GreenText
    Row(horizontalArrangement=Arrangement.spacedBy(3.dp)){
        repeat(3){index->
            val filled=!state.archived && index<state.dots
            Spacer(Modifier.size(6.dp).then(if(filled)Modifier.background(color,CircleShape) else Modifier.border(1.dp,Muted.copy(alpha=.45f),CircleShape)))
        }
    }
}

@Composable private fun LearningCard(record:LearningRecord,open:()->Unit){
    val state=record.state
    val press=remember{MutableInteractionSource()}
    val tint=if(state.remembered)lerp(Paper,learningGold,.08f) else Paper
    Column(Modifier.fillMaxWidth().alpha(if(state.archived).82f else 1f).pressScale(press)
        .clip(cardShape).background(tint).border(.5.dp,if(state.remembered)learningGold.copy(alpha=.65f) else Line,cardShape)
        .clickable(interactionSource=press,indication=LocalIndication.current,onClick=open)
        .testTag("learning-item-${state.id}").padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            LearningCategory(state,Modifier.weight(1f))
            HeatDots(state)
            Text(state.displayStatus,color=Muted,style=MaterialTheme.typography.labelSmall)
        }
        Text(state.content,fontFamily=FontFamily.Serif,style=MaterialTheme.typography.titleMedium,lineHeight=26.sp,color=Ink)
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(record.dateLabel(),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=Muted)
            if(state.archived || state.remembered)Text(if(state.archived)"点开可重新启用" else "不会淡忘",style=MaterialTheme.typography.labelSmall,color=Muted)
        }
    }
}

@Composable private fun LearningRecordDetail(friend:Friend,record:LearningRecord,onControl:(String)->Unit){
    val state=record.state
    var deleting by rememberSaveable(state.id){mutableStateOf(false)}
    var expanded by rememberSaveable(state.id){mutableStateOf(false)}
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("忘掉这条学习？")},text={Text("原聊天记录会完整保留。")},
        confirmButton={TextButton({deleting=false;onControl("delete")},modifier=Modifier.testTag("learning-delete-confirm")){Text("忘掉")}},
        dismissButton={TextButton({deleting=false}){Text("取消")}})
    Column(Modifier.fillMaxSize().background(Chrome).testTag("learning-detail").verticalScroll(rememberScrollState())
        .padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(14.dp)){
        PaperCard{
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    LearningCategory(state,Modifier.weight(1f))
                    Text(record.dateLabel(),color=Muted,style=MaterialTheme.typography.labelSmall)
                }
                Text(state.content,fontFamily=FontFamily.Serif,fontSize=18.sp,lineHeight=28.sp,color=Ink)
                val (status,note)=when{
                    state.state=="paused" -> "已暂时停用 · 聊天时不参考" to "已收进「已收起」，随时可以重新启用"
                    state.state=="faded" -> "已自然淡忘 · 聊天时不参考" to "很久没再提起，点下方可重新启用或一直记住"
                    state.remembered -> "已一直记住 · 聊天时会参考" to "不会随时间淡忘，也会置顶在学习列表里"
                    else -> "${state.displayStatus} · 聊天时会参考" to "常提到会记更牢，很久不提会慢慢淡忘"
                }
                val tint=when{state.archived->MaterialTheme.colorScheme.surfaceContainer;state.remembered->lerp(Paper,learningGold,.12f);else->MaterialTheme.colorScheme.surfaceContainerLow}
                Row(Modifier.fillMaxWidth().background(tint,RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    HeatDots(state)
                    Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Text(status,style=MaterialTheme.typography.bodyMedium,color=Ink)
                        Text(note,style=MaterialTheme.typography.bodySmall,color=Muted)
                    }
                }
            }
        }
        PaperCard{
            ControlRow("一直记住","锁住这条印象，不会随时间慢慢淡忘","learning-pin",checked=state.remembered){
                onControl(if(state.remembered)"unpin" else "pin")
            }
            HorizontalDivider(color=Line,thickness=.5.dp)
            if(state.state=="faded"){
                ControlRow("重新启用","放回学习列表，聊天时会重新参考","learning-pause"){onControl("resume")}
            }else{
                val paused=state.state=="paused"
                ControlRow("暂时不用",if(paused)"已收进「已收起」，关闭即可恢复使用" else "先收进「已收起」，聊天时不参考",
                    "learning-pause",checked=paused){onControl(if(paused)"resume" else "pause")}
            }
            HorizontalDivider(color=Line,thickness=.5.dp)
            ControlRow("忘掉这条","从学习记录移除，原聊天记录不受影响","learning-delete",
                titleColor=if(Paper.luminance()<.3f)Color(0xFFE4A098) else Color(0xFF9E473D)){deleting=true}
        }
        PaperCard{
            val changes=record.changes.count{it.phase!="propose"}
            val summary="${record.evidence.count{it.kind!="interpretation"}} 句聊天"+if(changes>0)" · 改过 $changes 次" else ""
            Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.testTag("learning-origin-toggle").heightIn(min=56.dp)
                .padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Text("她是怎么记住这条的",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=Ink)
                Text(summary,style=MaterialTheme.typography.labelSmall,color=Muted)
                Text(if(expanded)"▴" else "▾",color=Muted)
            }
            SoftVisibility(expanded){
                Column(Modifier.padding(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Column(Modifier.fillMaxWidth().testTag("learning-evidence"),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text("相关聊天原话",style=MaterialTheme.typography.titleSmall,color=Ink)
                        if(record.evidence.isEmpty())Text("还没有可展示的原话",style=MaterialTheme.typography.bodySmall,color=Muted)
                        record.evidence.forEach{source->
                            Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                Spacer(Modifier.width(2.dp).fillMaxHeight().background(Line))
                                Column{
                                    val who=when{source.kind=="interpretation"->"当时理解";source.mine->"你说";else->"${friend.name}回"}
                                    Text("$who · ${learningDate(source.created,true)}",style=MaterialTheme.typography.labelSmall,color=Muted)
                                    Text(source.text,style=MaterialTheme.typography.bodyMedium,color=Ink)
                                }
                            }
                        }
                    }
                    if(record.changes.isNotEmpty())Column(Modifier.testTag("learning-history"),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("印象的变化",style=MaterialTheme.typography.titleSmall,color=Ink)
                        record.changes.forEach{change->
                            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow,RoundedCornerShape(12.dp))
                                .padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Text("${learningDate(change.created,true)} · ${learningPhaseLabel(change.phase)}",style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(change.content,style=MaterialTheme.typography.bodyMedium,color=Ink)
                                if(change.reason.startsWith("反馈：“"))Text(change.reason.substringBeforeLast("”")+"”",style=MaterialTheme.typography.bodySmall,color=Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PaperCard(content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxWidth().clip(cardShape).background(Paper).border(.5.dp,Line,cardShape),content=content)
}

@Composable private fun ControlRow(title:String,note:String,tag:String,checked:Boolean?=null,titleColor:Color=Ink,action:()->Unit){
    val interaction=if(checked==null)Modifier.clickable(onClick=action)
        else Modifier.toggleable(checked,role=Role.Switch,onValueChange={action()})
    Row(Modifier.fillMaxWidth().then(interaction).heightIn(min=56.dp).testTag(tag).padding(horizontal=16.dp,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Text(title,style=MaterialTheme.typography.titleSmall,color=titleColor)
            Text(note,style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        if(checked==null)Text("›",color=Muted,fontSize=20.sp)
        else Switch(checked=checked,onCheckedChange=null,colors=SwitchDefaults.colors(checkedTrackColor=if(tag=="learning-pin")learningGold else GreenText))
    }
}

private fun learningKindLabel(value:String)=when(value){"viewpoint"->"看法";"interest"->"兴趣";"habit"->"相处习惯";else->"其他学习"}
private fun learningPhaseLabel(value:String)=when(value){"propose"->"最初记下";"revise"->"补充调整";"confirm"->"再次印证";"pause"->"暂停使用";else->"记录变化"}
private fun LearningRecord.dateLabel()=learningDate(updatedAt,false)+if(updatedAt==null)"" else if(changes.size>1)" 更新" else " 记下"
private fun learningDate(value:Long?,time:Boolean):String{
    if(value==null)return "时间未记录"
    val date=Date(value)
    val year=Calendar.getInstance().get(Calendar.YEAR)
    val sameYear=Calendar.getInstance().apply{this.time=date}.get(Calendar.YEAR)==year
    val format=(if(sameYear)"" else "yyyy年")+"M月d日"+if(time)" HH:mm" else ""
    return SimpleDateFormat(format,Locale.getDefault()).format(date)
}
