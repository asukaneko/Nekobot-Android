package app.lianxu.independent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Read-only view of saved learning; opening this screen never requests a model. */
@Composable internal fun LearningRecordsScreen(vm:AppState){
    val role=vm.activeId
    val friend=vm.friends.firstOrNull{it.id==role}
    val revision=vm.processingRevision
    var retry by remember(role){mutableIntStateOf(0)}
    // A new role or source revision must not display the previous snapshot while loading.
    var records by remember(role,revision,retry){mutableStateOf<List<LearningRecord>?>(null)}
    var failed by remember(role,revision,retry){mutableStateOf(false)}
    LaunchedEffect(role,revision,retry,friend!=null){
        if(friend==null)return@LaunchedEffect
        try{
            val result=withContext(Dispatchers.IO){vm.companion.learningRecords(role)}
            coroutineContext.ensureActive()
            if(vm.activeId==role&&vm.processingRevision==revision&&vm.page=="role-learning")records=result
        }catch(e:CancellationException){throw e}
        catch(_:Exception){
            coroutineContext.ensureActive()
            if(vm.activeId==role&&vm.processingRevision==revision&&vm.page=="role-learning")failed=true
        }
    }
    if(friend==null){
        Column(Modifier.fillMaxSize().background(Chrome).testTag("learning-page")){
            AppTopBar("学习记录",{vm.back()})
            Text("角色不存在或已移除。",Modifier.padding(UiSpace.page),style=MaterialTheme.typography.bodyLarge,color=Muted)
        }
    }else key(role){
        LearningRecordsContent(friend,records,failed,{retry++},{vm.back()})
    }
}

@Composable internal fun LearningRecordsContent(
    friend:Friend,
    records:List<LearningRecord>?,
    loadFailed:Boolean,
    onRetry:()->Unit,
    onBack:()->Unit
){
    var query by rememberSaveable(friend.id){mutableStateOf("")}
    val tabs=key(friend.id){rememberPageTabs(4)}
    val kinds=listOf("","viewpoint","interest","habit")
    val lists=key(friend.id){listOf(rememberLazyListState(),rememberLazyListState(),rememberLazyListState(),rememberLazyListState())}

    var detailId by rememberSaveable(friend.id){mutableStateOf<String?>(null)}
    fun back(){if(detailId!=null)detailId=null else onBack()}
    BackHandler(detailId!=null){back()}
    Column(Modifier.fillMaxSize().background(Chrome).testTag("learning-page")){
        AppTopBar(if(detailId==null)"${friend.name} · 学习记录"else"学习详情",{back()},backTag=if(detailId==null)"learning-back"else"detail-back")
        when{
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
            detailId!=null->{
                val detail=records.firstOrNull{it.state.id==detailId}
                if(detail!=null)LearningRecordDetail(friend,detail)
                else Column(Modifier.fillMaxWidth().padding(UiSpace.page).testTag("learning-detail-missing"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("这条学习已不在当前记录中。",style=MaterialTheme.typography.bodyLarge)
                    Text("它的来源可能已被修改或撤回。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                    TextButton({detailId=null},modifier=Modifier.heightIn(min=UiSpace.action)){Text("返回列表")}
                }
            }
            else->{
                PageTabContent(tabs){page->
                    val kind=kinds[page]
                    val search=query.trim()
                    val shown=records.filter{record->
                        (kind.isEmpty()||record.state.kind==kind)&&
                            (search.isEmpty()||record.state.content.contains(search,true)||record.state.scope.contains(search,true))
                    }
                    LazyColumn(Modifier.fillMaxSize().background(Paper).testTag("learning-list"),state=lists[page],contentPadding=PaddingValues(UiSpace.page),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        item("intro"){
                            Text("从相处中留下的看法、兴趣与习惯",style=MaterialTheme.typography.bodyMedium,color=Muted)
                        }
                        item("search"){AppField(query,{query=it},"搜索内容或适用情况","learning-search",singleLine=true)}
                        item("filters"){
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                listOf("" to "全部","viewpoint" to "看法","interest" to "兴趣","habit" to "相处习惯").forEach{(value,label)->
                                    FilterChip(selected=kind==value,onClick={tabs.select(kinds.indexOf(value))},label={Text(label)},
                                        modifier=Modifier.heightIn(min=UiSpace.action).testTag("learning-filter-${value.ifEmpty{"all"}}"),
                                        colors=FilterChipDefaults.filterChipColors(selectedContainerColor=SelectedBackground,selectedLabelColor=GreenText))
                                }
                            }
                        }
                        item("count"){Text("${shown.size} 条学习记录",style=MaterialTheme.typography.labelLarge,color=Muted)}
                        if(shown.isEmpty())item("empty"){
                            Column(Modifier.fillMaxWidth().padding(vertical=24.dp).testTag("learning-empty"),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(if(records.isEmpty())"还没有学习记录"else"没有找到匹配的记录",style=MaterialTheme.typography.titleSmall)
                                Text(if(records.isEmpty())"有了可保留的看法、兴趣或相处习惯后，会在这里显示。聊天不一定每轮都产生新记录。"else"试试其他关键词，或切换到全部分类。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                            }
                        }
                        items(shown,key={it.state.id}){record->
                            Column(Modifier.fillMaxWidth().clickable{detailId=record.state.id}.testTag("learning-item-${record.state.id}").padding(vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
                                    Text(learningKindLabel(record.state.kind),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=GreenText)
                                    Text(learningStateLabel(record.state.state),style=MaterialTheme.typography.labelMedium,color=Muted)
                                    ControlIcon("chevron",Muted)
                                }
                                Text(record.state.content,style=MaterialTheme.typography.bodyLarge,maxLines=3,overflow=TextOverflow.Ellipsis)
                                Text("适用情况：${record.state.scope}",style=MaterialTheme.typography.bodyMedium,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text("更新时间：${learningDate(record.updatedAt)}",style=MaterialTheme.typography.bodySmall,color=Muted)
                            }
                            HorizontalDivider(color=Line,thickness=.5.dp)
                        }
                }}
            }
        }
    }
}

@Composable private fun LearningRecordDetail(friend:Friend,record:LearningRecord){
    LazyColumn(Modifier.fillMaxSize().background(Paper).testTag("learning-detail"),contentPadding=PaddingValues(UiSpace.page),verticalArrangement=Arrangement.spacedBy(20.dp)){
        item("current"){
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("${friend.name} · ${learningKindLabel(record.state.kind)}",style=MaterialTheme.typography.labelLarge,color=GreenText)
                Text(record.state.content,style=MaterialTheme.typography.bodyLarge)
                Text("适用情况",style=MaterialTheme.typography.titleSmall)
                Text(record.state.scope,style=MaterialTheme.typography.bodyMedium)
                Text("当前状态：${learningStateLabel(record.state.state)}",style=MaterialTheme.typography.bodyMedium,color=GreenText)
                Text(learningStateDescription(record.state.state),style=MaterialTheme.typography.bodyMedium,color=Muted)
                Text("更新时间：${learningDate(record.updatedAt)}",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        }
        item("evidence"){
            Column(Modifier.fillMaxWidth().testTag("learning-evidence"),verticalArrangement=Arrangement.spacedBy(14.dp)){
                HorizontalDivider(color=Line)
                Text("依据",style=MaterialTheme.typography.titleSmall)
                if(record.evidence.isEmpty())Text("没有可展示的依据。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                record.evidence.forEach{LearningEvidenceRow(friend,it)}
            }
        }
        item("history-title"){
            Column(Modifier.fillMaxWidth().testTag("learning-history"),verticalArrangement=Arrangement.spacedBy(14.dp)){
                HorizontalDivider(color=Line)
                Text("变化经过",style=MaterialTheme.typography.titleSmall)
                if(record.changes.isEmpty())Text("没有可展示的变化经过。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            }
        }
        record.changes.forEachIndexed{index,change->item("change-$index-${change.sourceId}-${change.phase}"){
            LearningChangeRow(friend,record.state.id,index,change)
        }}
    }
}

@Composable private fun LearningEvidenceRow(friend:Friend,evidence:LearningEvidence){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
        val label=if(evidence.kind=="interpretation")"当时理解"else if(evidence.mine)"你的原话"else"${friend.name}的实际回复"
        Text("$label · ${learningDate(evidence.created)}",style=MaterialTheme.typography.labelMedium,color=Muted)
        Text(evidence.text,style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun LearningChangeRow(friend:Friend,id:String,index:Int,change:LearningChange){
    var showEvidence by rememberSaveable(friend.id,id,index,change.sourceId,change.phase){mutableStateOf(false)}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("${learningPhaseLabel(change.phase)} · ${learningDate(change.created)}",style=MaterialTheme.typography.labelLarge,color=GreenText)
        Text(change.content,style=MaterialTheme.typography.bodyMedium)
        if(change.reason.isNotBlank())Text(change.reason,style=MaterialTheme.typography.bodyMedium,color=Muted)
        if(change.evidence.isNotEmpty()){
            TextButton({showEvidence=!showEvidence},modifier=Modifier.heightIn(min=UiSpace.action).testTag("learning-change-evidence-$index")){
                Text(if(showEvidence)"收起这次依据"else"查看这次依据（${change.evidence.size} 条）")
            }
            if(showEvidence)change.evidence.forEach{LearningEvidenceRow(friend,it)}
        }
        HorizontalDivider(color=Line,thickness=.5.dp)
    }
}

private fun learningKindLabel(value:String)=when(value){"viewpoint"->"看法";"interest"->"兴趣";"habit"->"相处习惯";else->"其他学习"}
private fun learningStateLabel(value:String)=when(value){"candidate"->"待验证";"developing"->"调整中";"active"->"可使用";"paused"->"已暂停";else->"状态未知"}
private fun learningStateDescription(value:String)=when(value){
    "candidate"->"刚形成的想法，还需要后续相处检验。"
    "developing"->"已经有所调整，现有依据还不足以确定。"
    "active"->"已有后续经历支持，可以作为参考；仍可能继续调整。"
    "paused"->"暂时不再采用这个想法或做法。"
    else->"当前状态暂时无法识别。"
}
private fun learningPhaseLabel(value:String)=when(value){"propose"->"提出新想法";"revise"->"修订内容";"pause"->"暂停使用";else->"记录变化"}
private fun learningDate(value:Long?)=if(value==null)"时间未记录"else SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(value))
