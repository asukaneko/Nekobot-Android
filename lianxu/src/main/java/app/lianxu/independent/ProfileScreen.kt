package app.lianxu.independent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable internal fun Profile(vm: AppState) {
    val avatarPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri!=null) vm.setUserAvatar(uri)
    }
    val pickAvatar = { avatarPicker.launch(arrayOf("image/*")) }
    val focus = LocalFocusManager.current
    val window = androidx.compose.ui.platform.LocalWindowInfo.current
    val compact = with(androidx.compose.ui.platform.LocalDensity.current) { window.containerSize.height.toDp() < 780.dp }
    var name by rememberSaveable(vm.persona) { mutableStateOf(vm.persona.name) }
    var address by rememberSaveable(vm.persona) { mutableStateOf(vm.persona.address) }
    var intro by rememberSaveable(vm.persona) { mutableStateOf(vm.persona.introduction) }
    Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())
        .padding(horizontal=26.dp, vertical=12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Box {
                SelfAvatar(vm.userAvatar,66,"profile-avatar",Modifier.clickable(enabled=!vm.busy,
                    role=Role.Button,onClickLabel="选择本人头像",onClick=pickAvatar))
                Box(Modifier.align(Alignment.BottomEnd).size(18.dp).background(Paper,RoundedCornerShape(6.dp))
                    .border(1.dp,Line,RoundedCornerShape(6.dp)),contentAlignment=Alignment.Center) {
                    ControlIcon("photo-camera",GreenText,14.dp)
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(vm.persona.name.ifBlank { "你的名字" },style=MaterialTheme.typography.titleMedium)
                Text("${vm.friends.size} 位角色 · ${vm.messages.count{it.mine}} 条已发送消息",
                    style=MaterialTheme.typography.bodySmall,color=Muted)
                Text("点击更换头像",Modifier.clickable(enabled=!vm.busy,onClick=pickAvatar),
                    style=MaterialTheme.typography.labelSmall,color=Muted)
            }
        }
        Spacer(Modifier.height(if(compact) 16.dp else 26.dp))
        Text("聊天中的你",style=MaterialTheme.typography.labelLarge)
        ProfileInput("我的名字","","nickname",name,{name=it.take(80)},compact)
        ProfileInput("希望被怎样称呼","","persona-address",address,{address=it.take(80)},compact)
        Spacer(Modifier.height(16.dp))
        ProfileInput("自我介绍","写一点关于你的事，让角色更了解你。","persona-introduction",intro,
            {intro=it.take(4000)}, compact, multiline=true)
        Spacer(Modifier.height(18.dp))
        Text("这些资料会用于聊天，发送给你所选的模型服务。\n只填写你愿意分享的内容。",
            style=MaterialTheme.typography.bodySmall,color=Muted)
        Spacer(Modifier.height(20.dp))
        PrimaryAction("保存资料",modifier=Modifier.fillMaxWidth(),enabled=!vm.busy) {
            focus.clearFocus()
            vm.savePersona(UserPersona(name,address,intro))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun ProfileInput(label:String,hint:String,tag:String,value:String,
    change:(String)->Unit,compact:Boolean,multiline:Boolean=false) {
    val align=if(multiline) TextAlign.Start else TextAlign.End
    val style=MaterialTheme.typography.bodyMedium.copy(color=Ink,textAlign=align)
    BasicTextField(value,change,modifier=Modifier.fillMaxWidth().testTag(tag).semantics{contentDescription=label},
        textStyle=style,singleLine=!multiline,minLines=if(multiline && compact)3 else if(multiline)4 else 1,maxLines=if(multiline)6 else 1,
        cursorBrush=SolidColor(GreenText),keyboardOptions=KeyboardOptions(imeAction=if(multiline)ImeAction.Default else ImeAction.Next),
        decorationBox={inner ->
            if(multiline) Column {
                Text(label,style=MaterialTheme.typography.bodyMedium,color=GreenText)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().heightIn(min=if(compact) 68.dp else 96.dp)) {
                    if(value.isEmpty() && hint.isNotEmpty()) Text(hint,style=style,color=Muted)
                    inner()
                }
                Text("${value.length} / 4000",Modifier.align(Alignment.End).padding(vertical=8.dp),
                    style=MaterialTheme.typography.labelSmall,color=Muted)
            } else Row(Modifier.fillMaxWidth().heightIn(min=if(compact) 54.dp else 64.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(label,style=MaterialTheme.typography.bodyMedium,color=GreenText)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f),contentAlignment=Alignment.CenterEnd) {
                    if(value.isEmpty() && hint.isNotEmpty()) Text(hint,style=style,color=Muted)
                    inner()
                }
            }
        })
    HorizontalDivider(color=Line,thickness=1.dp)
}
