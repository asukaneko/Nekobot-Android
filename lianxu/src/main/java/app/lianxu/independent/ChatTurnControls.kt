package app.lianxu.independent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable internal fun ChatTurnControls(vm:AppState,turn:ChatTurn){
    if(turn.state in setOf(TurnState.COMPLETED,TurnState.PENDING,TurnState.SUPERSEDED))return
    val ambient = LocalContext.current
    val context = ambient.hostActivity() ?: ambient
    val reveal=remember(turn.id){BringIntoViewRequester()}
    val latest=vm.turns.lastOrNull{it.friendId==turn.friendId}?.id==turn.id
    var reason by remember(turn.id,turn.attempt){mutableStateOf(false)}
    LaunchedEffect(turn.id,turn.attempt,turn.state,latest){
        if(latest&&turn.state==TurnState.FAILED){
            withFrameNanos{}
            reveal.bringIntoView()
            if(vm.claimFailureGuide(turn))reason=true
        }
    }
    Column(Modifier.widthIn(max=292.dp).bringIntoViewRequester(reveal).testTag("turn-state-${turn.id}")){
        if(turn.state==TurnState.FAILED)Text("没发出去，点这里看原因",Modifier.heightIn(min=32.dp).clickable{reason=true}.padding(vertical=6.dp).testTag("turn-failure-${turn.id}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        else Text(when(turn.state){TurnState.PENDING->"正在请求回复…";TurnState.CANCELLED->"已取消，没有自动重发。";TurnState.INTERRUPTED->ChatFailure.INTERRUPTED.display;else->"回复未完成。"},style=MaterialTheme.typography.bodySmall,color=Muted)
        if(reason){
            val background=remember(turn.id,turn.attempt,reason){DiagnosticEnvironment.backgroundState(context)}
            val guidance=remember(turn.failure,turn.failureDetail,turn.failureMeta,background){ChatFailureGuidance.build(turn.failure,turn.failureDetail,background,turn.failureMeta)}
            ChatFailureDialog(
                text=ChatFailureText.full(turn.failure,turn.failureDetail,turn.failureMeta),
                guidance=guidance,
                onAction={action->
                    reason=false
                    when(action){
                        FailureGuidanceAction.OPEN_APP_BACKGROUND_SETTINGS->openBackgroundAccessSettings(context)
                        FailureGuidanceAction.OPEN_NOTIFICATION_SETTINGS->openChatNotificationSettings(context)
                        FailureGuidanceAction.OPEN_MODEL_CONFIG->{
                            val isAux=turn.failureMeta?.route=="aux_vision"||
                                turn.failure==ChatFailure.MISSING_VISION_MODEL||
                                (turn.failureMeta==null&&turn.failureDetail.contains("阶段：辅助看图"))
                            val targetConfigId=turn.failureMeta?.modelConfigId.orEmpty().ifBlank{
                                if(isAux) "" else turn.configId
                            }
                            val targetPurpose=if(isAux) ModelPurpose.AUX_VISION else ModelPurpose.CHAT
                            vm.openModelsFromChat(turn.friendId,targetConfigId,targetPurpose)
                        }
                    }
                },
                dismiss={reason=false}
            )
        }
        if(vm.turns.lastOrNull{it.friendId==turn.friendId}?.id==turn.id)TextButton(onClick={vm.retryChat(turn)},enabled=!vm.chatSending,modifier=Modifier.heightIn(min=48.dp).testTag("retry-turn-${turn.id}")){Text("重试")}
    }
}

/** The full reason for a failed turn: our one-line explanation, optional stage context, then what the service actually returned. */
internal object ChatFailureText {
    fun full(failure:ChatFailure?,detail:String,meta:ChatFailureMeta?=null)=buildString{
        val headline=if(failure==ChatFailure.CONNECTION_INTERRUPTED&&(meta?.stage=="connect_or_write"||(meta==null&&detail.contains("发送请求时")))){
            "发送请求时连接中断了，本次没有采用；原消息保留，可手动重试。"
        } else failure?.display?:"回复未完成。"
        append(headline)
        val stageLine=meta?.summaryLine().orEmpty()
        if(stageLine.isNotBlank())append("\n\n失败阶段：\n").append(stageLine)
        // Label as provider raw text only for real non-2xx HTTP responses; local HTTP-2xx parse/stream notes stay "详细原因" even for legacy records without detailSource.
        val legacyHttpStatus=if(detail.startsWith("HTTP ")) detail.substring(5).takeWhile{it.isDigit()}.toIntOrNull() else null
        val isProviderBody=when(meta?.detailSource){
            "provider_http"->true
            "local"->false
            else->legacyHttpStatus!=null&&legacyHttpStatus !in 200..299
        }
        if(detail.isNotBlank())append(if(isProviderBody)"\n\n服务返回的原文：\n" else "\n\n详细原因：\n").append(detail)
    }
}
@Composable internal fun ChatFailureDialog(text:String,dismiss:()->Unit){
    ChatFailureDialog(text=text,guidance=null,onAction=null,dismiss=dismiss)
}
@Composable internal fun ChatFailureDialog(text:String,guidance:FailureGuidance?,onAction:((FailureGuidanceAction)->Unit)?,dismiss:()->Unit){
    val clipboard=LocalClipboardManager.current
    var copied by remember{mutableStateOf(false)}
    val action=guidance?.action
    AlertDialog(
        onDismissRequest=dismiss,
        title={Text(guidance?.title?:"没发出去的原因")},
        text={
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(guidance!=null && guidance.steps.isNotEmpty()){
                    Text(guidance.summary,style=MaterialTheme.typography.bodyMedium,color=Ink,modifier=Modifier.testTag("turn-failure-guidance-summary"))
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.testTag("turn-failure-guidance-steps")){
                        guidance.steps.forEach{step->Text(step,style=MaterialTheme.typography.bodySmall,color=Muted)}
                    }
                    HorizontalDivider(color=Line,thickness=.5.dp)
                }
                SelectionContainer(Modifier.testTag("turn-failure-detail")){
                    Text(text,style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=Ink)
                }
            }
        },
        confirmButton={
            if(action!=null && onAction!=null && guidance.actionLabel.isNotBlank()){
                TextButton(onClick={onAction(action)},modifier=Modifier.testTag("turn-failure-action")){Text(guidance.actionLabel,color=GreenText)}
            } else {
                TextButton({clipboard.setText(AnnotatedString(text));copied=true},modifier=Modifier.testTag("turn-failure-copy")){Text(if(copied)"已复制" else "复制",color=GreenText)}
            }
        },
        dismissButton={
            Row{
                if(action!=null && onAction!=null && guidance.actionLabel.isNotBlank()){
                    TextButton({clipboard.setText(AnnotatedString(text));copied=true},modifier=Modifier.testTag("turn-failure-copy")){Text(if(copied)"已复制" else "复制",color=Muted)}
                }
                TextButton(dismiss,modifier=Modifier.testTag("turn-failure-dismiss")){Text(if(action!=null)"暂不" else "关闭",color=Muted)}
            }
        }
    )
}
