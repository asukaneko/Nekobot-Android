package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MemoryRulesScreen(vm:AppState){
    var recent by rememberSaveable{mutableStateOf("")};var summary by rememberSaveable{mutableStateOf("")};var related by rememberSaveable{mutableStateOf("")}
    var reviewRounds by rememberSaveable{mutableIntStateOf(5)}
    var reviewSheet by rememberSaveable{mutableStateOf(false)};var reviewDraft by rememberSaveable{mutableStateOf("5")}
    var loaded by remember{mutableStateOf(MemoryRules())}
    var ready by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var status by rememberSaveable{mutableStateOf("")};var failed by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){val r=vm.processing.memoryRules().first;loaded=r;if(recent.isEmpty()){recent=r.recentRounds.toString();summary=r.summaryRounds.toString();related=r.relatedCount.toString();reviewRounds=r.reviewRounds};ready=true}
    fun save(){if(busy||!ready)return;val r=loaded.copy(recentRounds=recent.toIntOrNull()?:0,summaryRounds=summary.toIntOrNull()?:0,relatedCount=related.toIntOrNull()?:0,reviewRounds=reviewRounds)
        if(!r.valid()){failed=true;status="请填写正整数，总结记忆轮数不能超过近期轮数。";return}
        busy=true;failed=false;status="";scope.launch{try{val saved=vm.processing.saveMemoryCounts(r);if(loaded.reviewRounds!=saved.reviewRounds)ReplyAccountingScheduler.recover(vm.processing.store);loaded=saved;vm.refreshProcessing();status="已保存，所有AI使用这套规则。"}catch(_:Exception){failed=true;status="保存未完成，请重试。"}finally{busy=false}}
    }
    Column(Modifier.fillMaxSize().background(Chrome).testTag("memory-rules-page")){
        AppTopBar("记忆规则",{vm.back()},extra={TextButton({save()},enabled=ready&&!busy,modifier=Modifier.heightIn(min=48.dp).testTag("memory-rules-save")){Text(if(busy)"保存中" else "保存")}})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("按轮次保留，按条数回想",style=MaterialTheme.typography.titleLarge)
            Text("适用于所有AI，记忆内容仍各自独立。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            AppField(recent,{recent=it.take(10);status=""},"近期原话（轮）","rules-recent",true)
            Text("超过这个完整轮数后，总结最早的一批记忆。一轮是用户连续发言及其首条有效回复；正在输入或未完成的回复不计入。",style=MaterialTheme.typography.bodySmall,color=Muted)
            AppField(summary,{summary=it.take(10);status=""},"每批总结记忆（轮）","rules-summary",true)
            Text("仅在整批保存和来源校验成功后移出近期输入，聊天原文不删除。失败保留进度，可重试。",style=MaterialTheme.typography.bodySmall,color=Muted)
            AppField(related,{related=it.take(10);status=""},"相关记忆最多（条）","rules-related",true)
            Text("直接命中与可用关联补充按记忆去重，共用上限，不凑数。核心记忆单独保留。使用关键词、主题标签及有证据的两跳关联；向量仅在已配置时启用。",style=MaterialTheme.typography.bodySmall,color=Muted)
            HorizontalDivider(color=Line)
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=ready&&!busy){reviewDraft=reviewRounds.toString();reviewSheet=true}.testTag("rules-review-open"),verticalAlignment=Alignment.CenterVertically){
                Text("心情与关系回顾",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                Text("每 $reviewRounds 轮",color=Muted,style=MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(color=Line)
            Text("容量不足时会明确提示",style=MaterialTheme.typography.titleSmall)
            Text("模型总容量仍有限。装不下完整原话、相关记忆或总结记忆批次时，请选择更大容量模型，或主动调整上述数量；不会暗中缩小批次或截断核心。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("保存本身不联网。修改会使正在运行的旧请求失效；已开始的固定批次保留，手动重试先补齐该批，再按新规则总结记忆。",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(status.isNotEmpty())Text(status,Modifier.testTag("memory-rules-status"),style=MaterialTheme.typography.bodyMedium,color=if(failed)MaterialTheme.colorScheme.error else GreenText)
        }
    }
    if(reviewSheet)ModalBottomSheet(onDismissRequest={reviewSheet=false},containerColor=Paper){
        val value=reviewDraft.toIntOrNull()
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=UiSpace.page).padding(bottom=24.dp).testTag("rules-review-sheet"),verticalArrangement=Arrangement.spacedBy(20.dp)){
            Text("回顾间隔",style=MaterialTheme.typography.titleLarge)
            Text("聊满这些轮次后，她会一起回顾心情、关系和经历。聊天照常继续；新消息留给下一批。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){
                OutlinedButton({reviewDraft=((value?:5)-1).coerceAtLeast(1).toString()},enabled=value!=null&&value>1,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("rules-review-minus")){Text("−")}
                OutlinedTextField(reviewDraft,{reviewDraft=it.filter(Char::isDigit).take(10)},Modifier.weight(1f).testTag("rules-review-value"),singleLine=true,label={Text("轮")},textStyle=MaterialTheme.typography.titleLarge.copy(textAlign=TextAlign.Center),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=value==null||value<1)
                OutlinedButton({reviewDraft=((value?:5)+1).toString()},enabled=value!=null&&value<Int.MAX_VALUE,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("rules-review-plus")){Text("+")}
            }
            Text("默认 5 轮，与记忆总结分开。已开始的批次保持原样。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Button({reviewRounds=requireNotNull(value);reviewSheet=false;failed=false;status="已调整，点击右上角保存后生效。"},enabled=value!=null&&value>0,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("rules-review-confirm")){Text("完成")}
        }
    }
}
