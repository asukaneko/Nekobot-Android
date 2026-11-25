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
import java.time.Instant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun processingMessage(code:String)=when(code){
    "MEMORY_SUMMARIZING"->"正在总结记忆，暂不能手动修改；结束或停止后恢复。"
    "SUMMARY_TIMEOUT"->"总结记忆已达 5 分钟上限，已停止并恢复手动修改；原话和已保存条目保留，可手动重试。"
    "BATCH_REVIEW_REQUIRED"->"本批已保存的条目发生变化，请先核对并保留当前版本，再重试补齐回执。"
    "SOURCE_EDITED"->"引用的原话在总结期间被编辑或撤回，整批作废，已按最新内容重新总结；原话保留。"
    "MEMORY_CHANGED"->"关联的已有记忆在总结期间被改动，整批作废；原话保留，可重试。"
    "LATE_DISCARDED"->"超时后晚到的结果已丢弃，没有写入。"
    "CANDIDATE_SOURCE_INVALID"->"候选来源已失效，不能采用；可查看保留的来源快照。"
    ""->"尚未运行";"NO_MEMORY_MODEL"->"请先在大模型配置选择记忆模型。"
    "HISTORY_CONFIRMATION"->"需要先选择如何处理已有聊天。";"SOURCE_CHANGED","STALE_TASK"->"来源或设置已变化，结果没有覆盖当前记录。"
    "CANCELLED"->"已停止，原话与已保存条目保留。";"INTERRUPTED"->"上次任务已中断。";"CAPACITY","TURN_TOO_LARGE","INPUT_TOO_LARGE"->"模型装不下完整总结记忆批次。请选择更大容量的记忆模型，或在记忆规则主动调整每批轮数；本批未缩小、原话未移出。"
    "MANUAL_RETRY"->"上次总结记忆未完成，请检查配置后手动重试。";"BUSY"->"任务正在进行。";"MODEL_UNAUTHORIZED"->"认证失败，请检查密钥与地域。"
    "MODEL_PAYMENT"->"记忆服务余额不足（HTTP 402），请充值或更换记忆模型后手动重试；原话未截断。"
    "MODEL_STREAM_LIMIT"->"记忆模型这次回的内容太长，超过读取上限，本批没有保存；原话未截断，可手动重试。"
    "MODEL_STREAM_PROTOCOL"->"记忆模型返回的不是正常的事件流，本批没有保存；原话未截断，可手动重试。"
    "MODEL_RATE_LIMIT"->"服务限流，请稍后手动重试。";"MODEL_TIMEOUT"->"本次超时，原有状态和待处理进度保留。";"MODEL_TLS"->"HTTPS 证书校验失败。"
    else->"本次未完整完成，原有记录保留。请检查配置后重试。"
}
/** Names the failed batch (rounds + short id), the reason, the attempts and that its rounds stayed in the recent window. */
internal fun memoryFailureText(f:MemoryBatchRepository.Failure):String {
    val rounds=if(f.firstRound>0)"第 ${f.firstRound}—${f.lastRound} 轮" else "一批"
    val issues=MemoryRepairFeedback.decode(f.detail).take(2).joinToString("；"){"${it.operation}：${it.detail}"}
    return "${rounds}这批（编号 ${f.batchId.removePrefix("batch-").take(8)}）总结失败：${MemoryRepairFeedback.reason(f.code)}。已尝试 ${f.attempts} 次，本批没有保存，原话仍留在最近窗口，可在「总结记忆与维护」手动重试。"+(if(issues.isBlank())"" else "具体：$issues")
}
internal fun taskDescription(s:ProcessingRepository.TaskStatus)=when(s.state){
    "ARCHIVED"->"导入的历史快照已归档，不恢复旧任务"
    "IDLE"->"尚未运行";"RUNNING"->"处理中";"WAITING"->"等待有界重试（第 ${s.attempt} 次）";"COMMITTED","COMPLETED"->"已完整保存";else->processingMessage(s.error)
}
@Composable internal fun ProcessingDirectory(vm:AppState){
    Column(Modifier.fillMaxSize().background(Chrome)){
        AppTopBar("记忆",{vm.back()})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("选择角色，查看记住的内容、原话来源与待确认记忆。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            Card(onClick={vm.open("settings-memory")},modifier=Modifier.fillMaxWidth().testTag("settings-memory-rules")){Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("记忆规则",style=MaterialTheme.typography.titleMedium);Text("保留、总结数量与回顾间隔",style=MaterialTheme.typography.bodySmall,color=Muted)}}}
            if(vm.friends.isEmpty())Text("先创建一位角色，再留下你们的记忆。",color=Muted)
            Column(Modifier.fillMaxWidth().background(Paper,androidx.compose.foundation.shape.RoundedCornerShape(UiSpace.radius))){vm.friends.forEachIndexed{index,friend->
                Row(Modifier.fillMaxWidth().clickable{vm.open("processing-role",friend.id)}.padding(horizontal=18.dp,vertical=16.dp).testTag("processing-friend-${friend.id}"),verticalAlignment=Alignment.CenterVertically){Avatar(friend);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(friend.name,style=MaterialTheme.typography.titleSmall);Text("查看记忆",style=MaterialTheme.typography.bodySmall,color=Muted)}}
                if(index<vm.friends.lastIndex)HorizontalDivider(Modifier.padding(start=80.dp,end=18.dp),color=Line)
            }}
        }
    }
}

@Composable internal fun ProcessingRole(vm:AppState){
    val friend=vm.friends.firstOrNull{it.id==vm.activeId}?:return
    MemoryExperience(vm,friend)
}
