package app.lianxu.independent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

@Composable internal fun SourceEditor(repo:ProcessingRepository,s:SourceDialogue,dismiss:()->Unit,confirm:(SourceImpact)->Unit){
    val summaryRoles by repo.store.memorySummary.roles.collectAsState();val editable=s.source.characterId !in summaryRoles
    val scope=rememberCoroutineScope();var side by rememberSaveable{mutableStateOf("user")};var text by rememberSaveable{mutableStateOf(s.user.text)}
    var choice by rememberSaveable{mutableStateOf(SourceMemoryChoice.KEEP)};var impact by remember{mutableStateOf<SourceImpact?>(null)};var error by remember{mutableStateOf("")};var oldVersions by remember{mutableStateOf<List<SourceVersion>>(emptyList())}
    LaunchedEffect(side){oldVersions=SourceChanges(repo).history(s.source.characterId,if(side=="user")s.user.id else requireNotNull(s.assistant).id)}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(impact==null)"编辑原话" else "确认影响范围")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(!editable)Text("正在总结记忆，结束或停止后可保存修改。",color=Muted)
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        if(impact==null){Row{TextButton({side="user";text=s.user.text}){Text("我的原话")};s.assistant?.let{a->TextButton({side="assistant";text=a.text}){Text("角色原话")}}};AppField(text,{text=it.take(8000)},"原话内容","source-text",minLines=4)
            TextButton({scope.launch{try{SourceChanges(repo).preview(s.source.characterId,s.source.turnId,side,text,true).let{if(it.memories.isEmpty()&&it.candidates.isEmpty())confirm(it) else impact=it}}catch(_:Exception){error="来源已变化，请重新打开。"}}},enabled=editable){Text("撤回原话")}
            oldVersions.forEach{v->Text("历史 v${v.revision}"+(if(v.deleted)"（撤回）" else "")+"：${v.body}",style=MaterialTheme.typography.bodySmall,color=Muted)}
        }else{Row{TextButton({choice=SourceMemoryChoice.KEEP}){Text(if(choice==SourceMemoryChoice.KEEP)"✓ 整体保留文字" else "整体保留文字")};TextButton({choice=SourceMemoryChoice.INVALIDATE}){Text(if(choice==SourceMemoryChoice.INVALIDATE)"✓ 整体作废" else "整体作废")}};Text("两种选择都会使旧证据失效。保留文字不会恢复来源；人工与锁定内容保留并复核。");Text("${if(impact!!.retract)"撤回" else "编辑"}会影响 ${impact!!.memories.size} 条记忆、${impact!!.candidates.size} 条候选。原话历史保留，旧证据和候选失效，不能再注入或采用。人工 / 锁定项产生待确认冲突。已保存关系分数保留历史，不作未经定义的逐维回滚。");Text("将保存：${if(impact!!.retract)"【已撤回】" else impact!!.text}");impact!!.memories.forEach{Text(it.text,style=MaterialTheme.typography.bodyMedium)};impact!!.candidates.forEach{Text("候选：${it.proposed.text}",style=MaterialTheme.typography.bodyMedium)}}
    }},confirmButton={TextButton({if(impact!=null)confirm(impact!!.copy(choice=choice)) else scope.launch{try{SourceChanges(repo).preview(s.source.characterId,s.source.turnId,side,text,false).let{if(it.memories.isEmpty()&&it.candidates.isEmpty())confirm(it) else impact=it}}catch(_:Exception){error="来源已变化，请重新打开。"}}},enabled=editable&&text.isNotBlank()){Text(if(impact==null)"保存修改" else "确认修改")}},dismissButton={TextButton({if(impact!=null)impact=null else dismiss()}){Text("取消")}})
}
