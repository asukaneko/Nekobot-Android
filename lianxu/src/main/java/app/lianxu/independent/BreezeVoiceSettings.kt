package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun rememberBreezeAccount():BreezeVoiceAccount {
    val app = LocalContext.current.applicationContext
    return remember(app) { BreezeVoiceAccount(app) }
}

@Composable internal fun VoiceSection(title:String, content:@Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(UiSpace.radius), color=Paper, modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title, style=MaterialTheme.typography.titleSmall, color=Ink)
            content()
        }
    }
}

@Composable internal fun BreezeRoleSettings(vm:AppState) {
    val role=vm.friends.find { it.id==vm.activeId } ?: return
    val account=rememberBreezeAccount()
    val catalog by account.catalogChanges.collectAsState(Triple("",0L,false))
    key(role.id,catalog) {
        var enabled by remember { mutableStateOf(account.enabled(role.id)) }
        val voices=remember { account.voices() }
        var selected by remember { mutableStateOf(account.selected(role.id)) }
        var picking by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        ToolboxPage("聊天设置",{vm.back()},"role-voice-page") {
            VoiceSection("当前角色"){Text(role.name)}
            VoiceSection("语音") {
                Row(Modifier.fillMaxWidth().toggleable(enabled,role=Role.Switch,onValueChange={
                    account.enable(role.id,it);enabled=it
                }).testTag("voice-allow"),verticalAlignment=Alignment.CenterVertically) {
                    Text("允许发语音",Modifier.weight(1f))
                    Switch(enabled,null)
                }
                HorizontalDivider(color=Line)
                Text("绑定音色")
                OutlinedButton({picking=true},enabled=catalog.third && voices.isNotEmpty(),modifier=Modifier.testTag("voice-choose")) {
                    Text(voices.find{it.id==selected}?.name ?: "选择音色")
                }
                if(!catalog.third || voices.isEmpty()) Text("请前往「大模型配置 → 语音」连接 BreezeBlue，再打开「AI 语音」准备音色。",color=Muted)
                if(error.isNotEmpty()) Text(error,color=Muted)
            }
        }
        if(picking) AlertDialog(onDismissRequest={picking=false},title={Text("选择音色")}, text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                voices.forEach { voice ->
                    Row(Modifier.fillMaxWidth().selectable(voice.id==selected,role=Role.RadioButton,onClick={
                        try {account.bind(role.id,voice,catalog.first);selected=voice.id}
                        catch(_:IllegalArgumentException){error="音色列表已更新，请重新选择"}
                        picking=false
                    }).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(voice.id==selected,null);Spacer(Modifier.width(10.dp));Text(voice.name)
                    }
                }
            }
        },confirmButton={TextButton({picking=false}){Text("关闭")}})
    }
}
