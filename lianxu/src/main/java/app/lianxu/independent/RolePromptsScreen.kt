package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable internal fun RolePromptsScreen(vm:AppState) {
    val friend = vm.friends.firstOrNull { it.id == vm.activeId } ?: return
    key(friend.id) { RoleEditor(vm, friend) }
}

@Composable private fun RoleEditor(vm:AppState, friend:Friend) {
    var loaded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(friend.name) }
    var definition by rememberSaveable { mutableStateOf("") }
    var expression by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf("") }
    var extra by rememberSaveable { mutableStateOf("") }
    var relationship by rememberSaveable { mutableStateOf("") }
    var thinking by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(friend.id) {
        if (!loaded) {
            val p = vm.rolePrompts(friend.id)
            definition = p.definition(friend)
            expression = p.expression
            opening = p.opening
            extra = p.extra
            relationship = p.initialRelationship
            thinking = p.thinkingPrompt.takeIf { it.isNotBlank() }
            loaded = true
        }
    }
    val tabs = rememberPageTabs(4)
    Column(Modifier.fillMaxSize().background(Paper)) {
        AppTopBar("${friend.name} · 角色设定", { vm.back() }, extra = {
            CompactSaveAction(loaded && !vm.busy) {
                vm.saveCharacter(friend.id,name,definition,expression,opening,extra,relationship,thinking.orEmpty())
            }
        })
        RoleTabs(tabs)
        if (loaded) PageTabContent(tabs, Modifier.weight(1f)) { page ->
            Column(Modifier.fillMaxSize().testTag("role-page-$page").verticalScroll(rememberScrollState())
                .padding(horizontal=UiSpace.page, vertical=16.dp),
                verticalArrangement=Arrangement.spacedBy(20.dp)) {
                when (page) {
                    0 -> {
                        RoleField("名字", "role-name", name, { name=it }, 30, singleLine=true)
                        RoleField("角色设定", "role-definition", definition, { definition=it }, 8000, minLines=5)
                    }
                    1 -> {
                        RoleField("说话方式", "role-expression", expression, { expression=it }, 8000, hint="留空跟随默认")
                        RoleField("开场白", "role-opening", opening, { opening=it }, 8000, hint="可选")
                        RoleField("思考提示词", "role-thinking", thinking ?: CompanionPrompt.DEFAULT_THINKING_BODY,
                            { thinking=it }, 8000, minLines=3, action={
                                TextButton(onClick={ thinking=null }, modifier=Modifier.testTag("role-thinking-reset")) {
                                    Text("恢复默认",style=MaterialTheme.typography.labelSmall,color=Muted)
                                }
                            })
                    }
                    2 -> RoleField("初始关系背景", "role-initial-relationship", relationship, { relationship=it }, 4000, minLines=3, hint="可选")
                    3 -> RoleField("聊天约定", "role-extra", extra, { extra=it }, 8000, minLines=4,
                        hint="在这里写下你们聊天时的小约定。比如：我说想静一静时，先别追问。")
                }
            }
        }
    }
}

@Composable private fun RoleTabs(tabs:PageTabs) {
    val labels = listOf("角色","对话","关系","聊天约定")
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=UiSpace.page, vertical=6.dp)
        .height(48.dp).clip(RoundedCornerShape(12.dp)).background(Chrome).padding(3.dp)) {
        val width = maxWidth / labels.size
        val position = tabs.pager.currentPage + tabs.pager.currentPageOffsetFraction
        Box(Modifier.offset(x=width*position).width(width).fillMaxHeight()
            .testTag("role-tab-indicator").background(SelectedBackground,RoundedCornerShape(9.dp)))
        Row(Modifier.fillMaxSize().selectableGroup()) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).fillMaxHeight().testTag("role-tab-$index")
                    .selectable(tabs.selected==index, role=Role.Tab, onClick={tabs.select(index)}),
                    contentAlignment=Alignment.Center) {
                    Text(label,style=MaterialTheme.typography.labelLarge,
                        color=if (tabs.selected==index) Ink else Muted)
                }
            }
        }
    }
}

@Composable private fun RoleField(label:String, tag:String, value:String, change:(String)->Unit,
    limit:Int, minLines:Int=1, singleLine:Boolean=false, hint:String="", action:(@Composable ()->Unit)?=null) {
    val style = MaterialTheme.typography.bodyMedium.copy(color=Ink,
        textAlign=if (singleLine) TextAlign.End else TextAlign.Start)
    val input: @Composable (Modifier)->Unit = { modifier ->
        BasicTextField(value,{change(it.take(limit))},modifier.testTag(tag).semantics { contentDescription=label },
            singleLine=singleLine, minLines=minLines, textStyle=style, cursorBrush=SolidColor(GreenText),
            decorationBox={ inner -> Box(Modifier.fillMaxWidth(),contentAlignment=if (singleLine) Alignment.CenterEnd else Alignment.TopStart) {
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint,style=style,color=Muted)
                inner()
            } })
    }
    Column {
        if (singleLine) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(label,style=MaterialTheme.typography.bodyMedium,color=GreenText)
                Spacer(Modifier.width(12.dp))
                input(Modifier.weight(1f))
            }
        } else {
            Column {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=GreenText)
                    action?.invoke()
                }
                Spacer(Modifier.height(10.dp))
                input(Modifier.fillMaxWidth())
                Text("${value.length} / $limit",Modifier.align(Alignment.End).padding(vertical=8.dp),
                    style=MaterialTheme.typography.labelSmall,color=Muted)
            }
        }
        HorizontalDivider(color=Line,thickness=1.dp)
    }
}
