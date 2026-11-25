package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun RoleSearch(vm:AppState){
    val focus=LocalFocusManager.current
    val base=Chrome
    val warmth=Accent
    val ink=Muted
    val send={if(vm.searchRequirements.isNotBlank()&&!vm.busy&&!vm.searching){focus.clearFocus();vm.findAgain()}}
    Box(Modifier.fillMaxSize().background(base)){
        // Static native color field, continuous behind the content and composer.
        androidx.compose.foundation.Canvas(Modifier.matchParentSize().testTag("modern-search-background")){
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(base,androidx.compose.ui.graphics.lerp(base,warmth,.32f),base)))
            val center=androidx.compose.ui.geometry.Offset(size.width*.83f,size.height*.34f)
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(androidx.compose.ui.graphics.Color.White.copy(alpha=.54f),androidx.compose.ui.graphics.Color.Transparent),center,size.width*.64f),size.width*.64f,center)
            drawArc(ink.copy(alpha=.09f),130f,195f,false,androidx.compose.ui.geometry.Offset(size.width*.49f,size.height*.23f),androidx.compose.ui.geometry.Size(size.width*.48f,size.width*.48f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Column(Modifier.fillMaxSize()){
            Header("寻找角色",{vm.back()})
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("role-search-content"),contentPadding=PaddingValues(UiSpace.page),verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(vm.searching)item(key="generation-waiting"){RoleGenerationWaiting(compact=vm.candidates.isNotEmpty())}
                if(vm.candidates.isNotEmpty()){
                    item{Text("点开资料，认识一下。",style=MaterialTheme.typography.bodyMedium,color=Muted,modifier=Modifier.padding(bottom=8.dp))}

                    items(vm.candidates,key={it.id}){friend->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Paper.copy(alpha=.78f)).clickable(enabled=!vm.searching,role=Role.Button){vm.openCandidate(friend.id)}.testTag("candidate-${friend.id}").padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            Avatar(friend)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Text(friend.name,style=MaterialTheme.typography.titleSmall)
                                Text(friend.bio,style=MaterialTheme.typography.bodyMedium,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                                if(vm.friends.any{it.id==friend.id})Text("已在通讯录",style=MaterialTheme.typography.labelSmall,color=GreenText)
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=UiSpace.page,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){

                Surface(Modifier.fillMaxWidth().testTag("role-composer"),shape=RoundedCornerShape(UiSpace.radius),color=Paper,
                    border=androidx.compose.foundation.BorderStroke(1.dp,Outline.copy(alpha=.5f)),shadowElevation=1.dp){
                    Row(Modifier.padding(4.dp),verticalAlignment=Alignment.Bottom){
                        TextField(vm.searchRequirements,{vm.updateSearchRequirements(it)},enabled=!vm.searching,
                            placeholder={Text(if(vm.candidates.isEmpty())"聊聊你想认识的人…" else "再补充一点想法…")},
                            minLines=1,maxLines=3,modifier=Modifier.weight(1f).testTag("role-requirements"),
                            shape=RoundedCornerShape(UiSpace.radius),textStyle=MaterialTheme.typography.bodyLarge,
                            colors=TextFieldDefaults.colors(focusedContainerColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedContainerColor=androidx.compose.ui.graphics.Color.Transparent,disabledContainerColor=androidx.compose.ui.graphics.Color.Transparent,
                                focusedTextColor=Ink,unfocusedTextColor=Ink,disabledTextColor=Ink,focusedPlaceholderColor=Muted,unfocusedPlaceholderColor=Muted,disabledPlaceholderColor=Muted,
                                focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,disabledIndicatorColor=androidx.compose.ui.graphics.Color.Transparent),
                            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send),
                            keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={send()}))
                        val actionEnabled=vm.searching||(vm.searchRequirements.isNotBlank()&&!vm.busy)
                        IconButton({if(vm.searching)vm.cancelSearch() else send()},enabled=actionEnabled,
                            modifier=Modifier.padding(start=4.dp,bottom=4.dp,end=4.dp).size(UiSpace.action).testTag(if(vm.searching)"search-cancel" else "generate-roles").semantics{contentDescription=if(vm.searching)"停止生成角色" else "发送要求，生成角色"},
                            colors=IconButtonDefaults.iconButtonColors(contentColor=MaterialTheme.colorScheme.onSecondaryContainer,disabledContentColor=Muted.copy(alpha=.38f))){
                            val symbol=LocalContentColor.current
                            val ring=Outline.copy(alpha=if(actionEnabled).72f else .3f)
                            androidx.compose.foundation.Canvas(Modifier.size(32.dp)){
                                val unit=size.width/32f
                                val stroke=1.5.dp.toPx()
                                drawCircle(ring,radius=15.dp.toPx(),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                                if(vm.searching){
                                    drawRoundRect(symbol,topLeft=androidx.compose.ui.geometry.Offset(11f*unit,11f*unit),size=androidx.compose.ui.geometry.Size(10f*unit,10f*unit),cornerRadius=androidx.compose.ui.geometry.CornerRadius(1.5f*unit),style=androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                                }else{
                                    val cap=androidx.compose.ui.graphics.StrokeCap.Round
                                    drawLine(symbol,androidx.compose.ui.geometry.Offset(16f*unit,23f*unit),androidx.compose.ui.geometry.Offset(16f*unit,9f*unit),stroke,cap)
                                    drawLine(symbol,androidx.compose.ui.geometry.Offset(10f*unit,15f*unit),androidx.compose.ui.geometry.Offset(16f*unit,9f*unit),stroke,cap)
                                    drawLine(symbol,androidx.compose.ui.geometry.Offset(16f*unit,9f*unit),androidx.compose.ui.geometry.Offset(22f*unit,15f*unit),stroke,cap)
                                }
                            }
                        }
                    }
                }
                Text("仅发送此处要求 · 由你的模型生成，可能产生费用",style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.padding(horizontal=4.dp))
            }
        }
    }
}
@Composable internal fun GeneratedRoleDetail(vm:AppState){
    val role=vm.generatedRoles.firstOrNull{it.friend.id==vm.candidateId}?:return
    val added=vm.friends.any{it.id==role.friend.id}
    Column(Modifier.fillMaxSize().background(Chrome)){
        Header("角色资料",{vm.back()})
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(UiSpace.group)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Avatar(role.friend,64)
                Column(Modifier.weight(1f)){Text(role.friend.name,style=PageTitleStyle);Text("AI 原创虚构角色",style=MaterialTheme.typography.bodySmall,color=Muted)}
            }
            Text(role.friend.bio,style=MaterialTheme.typography.bodyLarge)
            SectionLabel("角色设定");Text(role.definition,style=MaterialTheme.typography.bodyLarge)
            SectionLabel("说话方式");Text(role.expression,style=MaterialTheme.typography.bodyLarge)
            if(role.opening.isNotBlank()){SectionLabel("开场白");Text(role.opening,style=MaterialTheme.typography.bodyLarge)}
        }
        PrimaryAction(if(added)"已添加到通讯录" else "添加到通讯录",enabled=!added&&!vm.busy,modifier=Modifier.fillMaxWidth().padding(UiSpace.page).testTag("add-candidate")){vm.addCandidate()}
    }
}
