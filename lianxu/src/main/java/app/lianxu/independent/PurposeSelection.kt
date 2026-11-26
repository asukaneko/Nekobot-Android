package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun PurposeSelection(state:ModelConfigState,only:ModelPurpose?=null){
    var help by remember {mutableStateOf<ModelPurpose?>(null)}
    val purposes=if(only!=null) listOf(only) else listOf(ModelPurpose.CHAT,ModelPurpose.REVIEW,ModelPurpose.MEMORY,ModelPurpose.EMBEDDING)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
            if(only==ModelPurpose.AUX_VISION)Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text("辅助看图",style=MaterialTheme.typography.titleSmall);Text(if(state.auxiliaryVision)"用辅助模型看图，再交聊天模型"else"直接把图片发给聊天模型",style=MaterialTheme.typography.bodySmall,color=Muted)}
                Switch(checked=state.auxiliaryVision,onCheckedChange=state::changeAuxiliaryVision,enabled=!state.busy,modifier=Modifier.testTag("auxiliary-vision-switch"))
            }
            purposes.forEachIndexed{index,purpose->
                var menu by remember(purpose){mutableStateOf(false)}
                var includeUnknown by remember(purpose){mutableStateOf(false)}
                val chosen=state.configs.firstOrNull{it.id==state.purposes[purpose]}
                val options=purposeOptions(state.configs,purpose,chosen?.id.orEmpty(),includeUnknown,state.hostPurposes)
                Box{
                    Row(Modifier.fillMaxWidth().heightIn(min=72.dp).clickable(enabled=!state.busy,role=Role.Button){menu=true}.testTag("purpose-select-${purpose.key}").padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                        ControlIcon(when(purpose){ModelPurpose.EMBEDDING->"nodes";ModelPurpose.CHAT->"chat";ModelPurpose.MEMORY,ModelPurpose.REVIEW->"memory";ModelPurpose.AUX_VISION->"eye"},GreenText);Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(purpose.title,style=MaterialTheme.typography.titleSmall,color=Ink)
                            val fallback=if(purpose==ModelPurpose.REVIEW || purpose==ModelPurpose.MEMORY)"沿用聊天模型" else "尚未选择"
                            Text(chosen?.let{"${it.name} · ${it.modelId}"}?:fallback,style=MaterialTheme.typography.bodySmall,color=Muted)

                        }
                        IconButton(onClick={help=purpose},modifier=Modifier.size(UiSpace.action).testTag("purpose-help-${purpose.key}").semantics{contentDescription="${purpose.title}说明"}){ControlIcon("info")}
                    }
                    DropdownMenu(menu,{menu=false}){
                        DropdownMenuItem(text={Text("不选择")},onClick={menu=false;state.selectPurpose(purpose,"")},modifier=Modifier.testTag("purpose-clear-${purpose.key}"))
                        options.visible.forEach{config->DropdownMenuItem(text={Column{Text(config.name);Text(if(config.id.isHostModel()) "来自 NekoBot · ${config.modelId}" else config.modelId,style=MaterialTheme.typography.bodySmall)}},onClick={menu=false;state.selectPurpose(purpose,config.id)},modifier=Modifier.testTag("purpose-option-${purpose.key}-${config.id}"))}
                        if(options.hasAdditionalUnknown)DropdownMenuItem(text={Text("其他已保存配置（能力未核实）")},onClick={includeUnknown=true},modifier=Modifier.testTag("purpose-unknown-embedding"))
                    }
                }
                if(index<purposes.lastIndex)HorizontalDivider(Modifier.padding(start=64.dp,end=16.dp),color=Line)
            }
        }
    }
    help?.let{purpose->AlertDialog(onDismissRequest={help=null},title={Text("${purpose.title}说明")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(when(purpose){
            ModelPurpose.EMBEDDING->"用于记忆语义检索，可不选择。先添加并保存向量模型配置，再指定为记忆向量；不用于聊天或总结记忆。"
            ModelPurpose.CHAT->"聊天时使用这个模型；未单独选择回顾模型时，心情与学习回顾也用它。"
            ModelPurpose.REVIEW->"每几轮聊天后，后台用它回顾心情和学到的相处习惯。未选择时沿用聊天模型。"
            ModelPurpose.MEMORY->"主动总结记忆使用所选总结记忆模型；未选择时沿用聊天模型。"
            ModelPurpose.AUX_VISION->"辅助看图开启时，所选辅助模型接收图片并提供观察，聊天模型接收观察与文字；关闭时图片直接发给所选聊天模型。不检测模型能力，不自动换模型或回退。图片只在发送时上传。"
        })
        Text(if(only==ModelPurpose.CHAT)"所有角色共用全局聊天选择，不设角色单独覆盖。从创建引导新建或编辑聊天配置并保存后，会直接用于聊天；也可在此选择已有配置，不改其他用途。保存配置和选择用途不发出请求。"else"所有角色共用用途选择，不设角色单独覆盖。先在“服务商”页添加并保存配置，再选择对应模型。保存配置和选择用途只保存设置，不发出请求。",style=MaterialTheme.typography.bodyMedium,color=Muted)
    }},confirmButton={TextButton(onClick={help=null},modifier=Modifier.testTag("purpose-help-close")){Text("知道了")}})}
}
