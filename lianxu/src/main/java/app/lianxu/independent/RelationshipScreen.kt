package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 点滴的句子：固定句式拼，不调模型。有原话就带上「你说……」。 */
internal object RelationMomentText {
    private val withQuote=mapOf("warm" to "她心里暖了一下","happy" to "她挺开心","moved" to "她被感动到了","closer" to "她觉得跟你更亲近了",
        "promise" to "你们约好了，她很期待","fulfilled" to "你说到做到，她更信任你了","explained" to "她好受多了","apology" to "她心里松了一些",
        "repaired" to "那个心结松了一半","gap" to "她有点失落","broken" to "她有点失望","broken_big" to "她很失望，心里结了个疙瘩","boundary" to "她觉得被冒犯了")
    private val alone=mapOf("warm" to "你们聊得很好，她心里暖了一下","happy" to "你们聊得很开心","moved" to "她被你感动到了","closer" to "她觉得跟你更亲近了",
        "promise" to "你们约好了一件事，她很期待","fulfilled" to "你说到做到，她更信任你了","explained" to "你解释过了，她好受多了","apology" to "你道了歉，她心里松了一些",
        "repaired" to "你把那件事补上了，她心里的结松了一半","gap" to "事情没照她期待的来，她有点失落","broken" to "约好的事没做到，她有点失望",
        "broken_big" to "约好的事没做到，她心里结了个疙瘩","boundary" to "她觉得被冒犯了","ignored" to "她主动找你没等到回复，有点失落")
    fun sentence(moment:RelationMoment,quote:String?):String {
        val q=quote?.replace('\n',' ')?.trim()?.takeIf{it.isNotEmpty()&&moment.reaction in withQuote}
            ?:return alone[moment.reaction]?:(if(moment.positive)"她对你的感觉好了一点" else "她心里有点不舒服")
        val short=if(q.length>10)q.take(10)+"…" else q
        return "你说「$short」，${withQuote.getValue(moment.reaction)}"
    }
}

private val Warm=Color(0xFFFA9D3B)

@Composable internal fun RelationshipScreen(vm:AppState,worldTabs:@Composable ()->Unit={}){
    val role=vm.activeId
    val friend=vm.friends.firstOrNull{it.id==role}?:return
    val scope=rememberCoroutineScope()
    var view by remember(role){mutableStateOf<RelationshipView?>(null)}
    var quotes by remember(role){mutableStateOf<Map<Long,String>>(emptyMap())}
    var recording by remember(role){mutableStateOf<RelationRecordingReport?>(null)}
    var audit by remember(role){mutableStateOf<List<RelationAudit>>(emptyList())}
    var templates by remember(role){mutableStateOf(false)}
    var ignored by remember(role){mutableStateOf(false)}
    var tab by androidx.compose.runtime.saveable.rememberSaveable(role){mutableIntStateOf(0)}
    val panelScrolls=key(role){listOf(rememberScrollState(),rememberScrollState(),rememberScrollState())}
    var all by remember(role){mutableStateOf(false)}
    var error by remember(role){mutableStateOf(false)}
    var refresh by remember(role){mutableIntStateOf(0)}

    fun change(action:suspend ()->Unit){scope.launch{try{action();refresh++}catch(e:CancellationException){throw e}catch(_:Exception){error=true}}}
    LaunchedEffect(role,refresh){CompanionRuntime.revision.collect{
        try{
            val current=vm.companion.relationship(role);view=current
            recording=vm.companion.relationshipRecording(role)
            audit=vm.companion.relationshipAudit(role)
            val ids=current.state.ledger.keys.map{it.turnId}+current.state.moments.map{it.source.turnId}+current.state.promises.map{it.source.turnId}+audit.filter{it.status=="已改正"}.map{it.input.source.turnId}
            quotes=vm.companion.relationshipQuotes(role,ids.toSet())
            error=false
        }catch(e:CancellationException){throw e}catch(_:Exception){error=true}
    }}
    fun quote(source:SourceRef)=quotes[source.turnId]
    val date=remember{SimpleDateFormat("M月d日",Locale.CHINA)}
    val page=Chrome
    Column(Modifier.fillMaxSize().background(page).testTag("relationship-page")){
        AppTopBar(friend.name,{vm.back()})
        worldTabs()
        // 一屏装下：不整页滚动，下面三块用标签切换，每次最多 4 条。
        Column(Modifier.fillMaxSize().padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(error)Row(verticalAlignment=Alignment.CenterVertically){Text("暂时无法读取或保存，请重试。",Modifier.weight(1f),color=Muted);TextButton({refresh++}){Text("重试",color=GreenText)}}
            val current=view
            if(current==null){Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(color=GreenText)}}else{
                MoodHero(current,{templates=true}) { source -> change { vm.companion.correctInnerNote(source) } }
                LaunchedEffect(current.innerNote) {
                    current.innerNote?.let {
                        val remaining = RelationshipProjector.DAY - (AppServices.companionClock() - it.at)
                        if (remaining > 0) kotlinx.coroutines.delay(remaining)
                        refresh++
                    }
                }
                val knots=RelationshipQuery.knots(current.state,::quote)
                val moments=current.state.moments
                val promises=current.state.promises.sortedByDescending{it.at}
                RelationTabs(listOf("点滴","心结 · ${knots.size}","约定 · ${promises.size}"),tab){all=false;tab=it}
                key(tab){val panel=tab
                    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    val count=when(panel){0->moments.size;1->knots.size;else->promises.size}
                    RelationCard(Modifier.padding(horizontal=16.dp).weight(1f,fill=false).testTag("relationship-tab-$panel")){Column(Modifier.verticalScroll(panelScrolls[panel])){
                        if(count==0)Text(when(panel){0->"还没有让她感觉变了的事";1->"她现在没有放不下的事";else->"你们还没有约好的事"},Modifier.padding(20.dp),color=Muted)
                        when(panel){
                            0->moments.take(4).forEachIndexed{i,m->if(i>0)RelationDivider();MomentRow(m,quote(m.source),date)}
                            1->knots.take(4).forEachIndexed{i,ref->if(i>0)RelationDivider();KnotRow(quote(ref),current.inputs.firstOrNull{it.source==ref}?.at,date)}
                            else->promises.take(4).forEachIndexed{i,p->if(i>0)RelationDivider();PromiseRow(p,quote(p.source),current.inputs.firstOrNull{it.source==p.fulfilledBy}?.at,date)}
                        }
                        val corrected=audit.filter{it.status=="已改正"}
                        if(count>4||panel==0&&(count>0||corrected.isNotEmpty())){
                            HorizontalDivider(thickness=.5.dp,color=Line)
                            Text("查看全部",Modifier.fillMaxWidth().clickable{all=true}.padding(vertical=14.dp).testTag("relationship-all"),color=Muted,style=MaterialTheme.typography.bodySmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        }
                }}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                    Text(if(current.attributionIncomplete)"旧分值仅作历史查看，不提供给模型"else"这些感受来自相处中的点滴，只供她参考",color=Muted,style=MaterialTheme.typography.bodySmall)
                    recording?.takeIf{it.failed>0}?.let{report->Text(" · 最近${report.failed}轮没算进",Modifier.clickable{ignored=true}.padding(vertical=8.dp),color=GreenText,style=MaterialTheme.typography.bodySmall)}
                }
                }}
                if(all)RelationAllDialog(when(tab){0->"点滴";1->"心结";else->"约定"},{all=false}){
                    when(tab){
                        0->{
                            moments.forEachIndexed{i,m->if(i>0)RelationDivider()
                                val correctable=m.source.turnId>0&&current.inputs.any{it.source==m.source}
                                MomentRow(m,quote(m.source),date){if(correctable)TextButton({change{vm.companion.correctRelationshipRecord(m.source)}}){Text("记错了",color=GreenText,style=MaterialTheme.typography.bodySmall)}}
                            }
                            val corrected=audit.filter{it.status=="已改正"}
                            if(corrected.isNotEmpty()){
                                Text("已改正，不算进感觉",Modifier.padding(start=20.dp,top=16.dp,bottom=4.dp),color=Muted,style=MaterialTheme.typography.bodySmall)
                                corrected.forEach{row->Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
                                    Text("${date.format(Date(row.input.at))} · 你说「${quote(row.input.source)?:"原话已不可用"}」",Modifier.weight(1f),color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    TextButton({change{vm.companion.restoreRelationshipRecord(row.input.source)}}){Text("恢复",color=GreenText)}
                                }}
                            }
                        }
                        1->knots.forEachIndexed{i,ref->if(i>0)RelationDivider();KnotRow(quote(ref),current.inputs.firstOrNull{it.source==ref}?.at,date)}
                        else->promises.forEachIndexed{i,p->if(i>0)RelationDivider();PromiseRow(p,quote(p.source),current.inputs.firstOrNull{it.source==p.fulfilledBy}?.at,date)}
                    }
                }
                if(ignored)recording?.let{report->RelationAllDialog("没算进关系分数的几轮",{ignored=false}){
                    report.ignored.forEachIndexed{i,it->if(i>0)RelationDivider();Text(it.userMessage?:"原话已不可用",Modifier.padding(horizontal=20.dp,vertical=14.dp),color=Ink)}
                }}
            }
        }
    }
    if(templates)AlertDialog(onDismissRequest={templates=false},title={Text("相处模板")},text={Column{RelationTemplate.entries.forEach{template->TextButton({templates=false;change{vm.companion.changeRelationshipTemplate(role,template)}}){Text(template.label,color=Ink)}}}},confirmButton={TextButton({templates=false}){Text("取消",color=GreenText)}})
}

@Composable private fun RelationCard(modifier:Modifier=Modifier,content:@Composable ()->Unit){
    Surface(modifier.fillMaxWidth(),color=Paper,shape=RoundedCornerShape(20.dp)){content()}
}
@Composable private fun RelationDivider(){HorizontalDivider(Modifier.padding(horizontal=20.dp),thickness=.5.dp,color=Line)}
@Composable private fun RelationTabs(labels:List<String>,selected:Int,onSelect:(Int)->Unit){
    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(3.dp).selectableGroup()){
        labels.forEachIndexed{i,label->
            val on=i==selected
            Surface(Modifier.weight(1f).selectable(on,role=Role.Tab){onSelect(i)}.testTag("relationship-tab-button-$i"),color=if(on)Paper else Color.Transparent,shape=RoundedCornerShape(10.dp)){
                Text(label,Modifier.padding(vertical=9.dp),color=Ink,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=if(on)FontWeight.Bold else FontWeight.Normal),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }}
}
@Composable private fun Dot(color:Color){Box(Modifier.padding(top=8.dp).size(8.dp).background(color,CircleShape))}
@Composable private fun MomentRow(m:RelationMoment,quote:String?,date:SimpleDateFormat,action:(@Composable ()->Unit)?=null){
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Dot(if(m.positive)GreenText else Warm)
        Column(Modifier.weight(1f)){
            Text(RelationMomentText.sentence(m,quote),color=Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(date.format(Date(m.at)),color=Muted,style=MaterialTheme.typography.bodySmall)
        }
        action?.invoke()
    }
}
@Composable private fun KnotRow(quote:String?,at:Long?,date:SimpleDateFormat){
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top){
        Column(Modifier.weight(1f)){
            Text("你说：「${quote?:"原话已不可用"}」",color=Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(at!=null)Text(date.format(Date(at)),color=Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun PromiseRow(p:RelationPromise,quote:String?,doneAt:Long?,date:SimpleDateFormat){
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top){
        Column(Modifier.weight(1f)){
            Text("「${quote?:"原话已不可用"}」",color=Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(date.format(Date(p.at))+" 约好"+(doneAt?.let{" · ${date.format(Date(it))} 兑现"}?:""),color=Muted,style=MaterialTheme.typography.bodySmall)
        }
        RelationBadge(if(p.fulfilledBy==null)"还没兑现" else "已兑现",p.fulfilledBy!=null)
    }
}
@Composable private fun RelationAllDialog(title:String,dismiss:()->Unit,content:@Composable ColumnScope.()->Unit){
    Dialog(dismiss,DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth(.92f).fillMaxHeight(.8f),color=Paper,shape=RoundedCornerShape(20.dp)){Column{
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically){
                Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Bold),color=Ink)
                TextButton(dismiss){Text("关闭",color=GreenText)}
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("relationship-detail-scroll").padding(bottom=12.dp),content=content)
        }}
    }
}

@Composable internal fun RelationPaper(content:@Composable ColumnScope.()->Unit){
    Surface(Modifier.fillMaxWidth(),color=Paper,shape=RoundedCornerShape(20.dp),border=BorderStroke(.5.dp,Line)){Column(Modifier.padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}
}
@Composable internal fun RelationBadge(text:String,outlined:Boolean=false){
    Surface(color=if(outlined)Paper else Accent,shape=RoundedCornerShape(8.dp),border=if(outlined)BorderStroke(.5.dp,Line)else null){Text(text,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,color=if(outlined)Muted else MaterialTheme.colorScheme.onSecondaryContainer)}
}
