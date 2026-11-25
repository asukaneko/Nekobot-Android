package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

internal fun parameterTitle(name:String)=name+"想换一种方式找你"
internal fun parameterLines(old:RelationParameters,proposal:RelationParameterProposal):List<String>{
    if(!RelationParameterRules.activeProposal(proposal.values))return emptyList()
    return listOf("主动找你的语气：${old.tone} → ${RelationParameterRules.apply(old,proposal.values).tone}")
}
internal class ParameterCards {
    var proposals by mutableStateOf<List<RelationParameterProposal>>(emptyList())
    var receipts by mutableStateOf<List<ParameterReceipt>>(emptyList())
    var parameters by mutableStateOf(RelationTemplate.STEADY.parameters)
    var label by mutableStateOf("")
}
@Composable internal fun rememberParameterCards(vm:AppState,role:String):ParameterCards {
    val state=remember(role){ParameterCards()}
    LaunchedEffect(role){CompanionRuntime.revision.collect{
        state.proposals=vm.companion.relationshipParameterProposals(role)
        state.receipts=vm.companion.relationshipParameterReceipts(role)
        val view=vm.companion.relationship(role);state.parameters=view.parameters;state.label=view.label
    }}
    return state
}
@Composable internal fun RelationshipParameterCard(vm:AppState,friend:Friend,message:Message,state:ParameterCards){
    val proposal=state.proposals.firstOrNull{it.source?.turnId==message.id&&RelationParameterRules.activeProposal(it.values)}
    val receipt=state.receipts.lastOrNull{it.source.turnId==message.id}
    val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf(false)}
    fun act(action:suspend ()->Unit){if(!busy){busy=true;scope.launch{try{action();error=false}catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{busy=false}}}}
    Column(Modifier.widthIn(max=300.dp),horizontalAlignment=Alignment.End){
        if(proposal!=null)RelationPaper {
            Text(parameterTitle(friend.name),style=MaterialTheme.typography.titleSmall)
            parameterLines(state.parameters,proposal).forEach{line->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(line.substringBefore("："),Modifier.weight(1f),color=Muted);Text(line.substringAfter("："),color=Ink)}}
            Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                PrimaryAction("就这样",Modifier.weight(1f).fillMaxHeight(),enabled=!busy,onClick={act{vm.companion.confirmRelationshipParameters(friend.id,proposal,true)}})
                OutlinedButton(enabled=!busy,modifier=Modifier.weight(1f).fillMaxHeight(),border=BorderStroke(.5.dp,Line),onClick={act{vm.companion.confirmRelationshipParameters(friend.id,proposal,false)}}){Text("不用改",color=Ink)}
            }
        }else if(receipt!=null){
            if(receipt.undone)Text("已撤销",color=Muted,style=MaterialTheme.typography.bodySmall)
            else Surface(color=Paper,shape=RoundedCornerShape(20.dp),border=BorderStroke(.5.dp,Line)){
                Row(Modifier.padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Text("✓ 已改，从现在起生效 ·",style=MaterialTheme.typography.bodySmall);TextButton(enabled=!busy,onClick={act{vm.companion.changeRelationshipParameters(friend.id,receipt.old,state.label,receipt.source)}}){Text("撤销",color=GreenText)}}
            }
        }
        if(error)Text("保存失败，请重试。")
    }
}
