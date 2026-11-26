package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Composable internal fun BackupTabs(tabs: PageTabs, enabled: Boolean) {
    SlidingPageTabs(tabs, listOf("备份与恢复", "诊断日志"), listOf("backup-tab-data", "backup-tab-diagnostics"),
        "backup-tab-indicator", enabled)
}

@Composable internal fun SlidingPageTabs(tabs: PageTabs, labels: List<String>, tags: List<String>,
    indicator: String, enabled: Boolean = true, inactive: Color = Muted,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = UiSpace.page, vertical = 12.dp)
        .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp)) {
        val width = maxWidth / labels.size
        val position = (tabs.pager.currentPage + tabs.pager.currentPageOffsetFraction).coerceIn(0f, labels.lastIndex.toFloat())
        Box(Modifier.matchParentSize()) {
            Box(Modifier.offset(x = width * position).width(width).fillMaxHeight()
                .background(Paper, RoundedCornerShape(24.dp)).testTag(indicator))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
            labels.forEachIndexed { index, title ->
                Tab(tabs.selected == index, { tabs.select(index) }, enabled = enabled,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = UiSpace.action).clip(RoundedCornerShape(24.dp)).testTag(tags[index]),
                    selectedContentColor = Ink, unselectedContentColor = inactive,
                    text = { Text(title, color = softColor(if (tabs.selected == index) Ink else inactive),
                        style = labelStyle, maxLines = 1) })
            }
        }
    }
}

@Composable internal fun BackupActionRow(icon:String,title:String,description:String,tag:String,enabled:Boolean,action:()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper).clickable(enabled=enabled,role=Role.Button,onClick=action).testTag(tag).padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        if(icon=="person")NavigationIcon(3,false)else ControlIcon(icon,Muted,26.dp)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(title,style=MaterialTheme.typography.titleSmall,color=Ink)
            Text(description,style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
}

@Composable internal fun BackupDiagnosticToggle(value:Boolean,enabled:Boolean,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper).toggleable(value=value,enabled=enabled,role=Role.Switch,onValueChange=change).testTag("backup-detailed").padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        NavigationIcon(2,false)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("导出详细内容",style=MaterialTheme.typography.titleSmall,color=Ink)
            Text(if(value) "导出前会再次确认，文件可能含聊天原文" else "简版仅含排错摘要",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Switch(value,null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BackupHelpDialog(diagnostics:Boolean,close:()->Unit) {
    val sections = if (diagnostics) listOf(
        "只留在本机，绝不自动上传" to "仅滚动保留最近 7 天（最多 10 MB），所有 API 密钥会自动遮盖打码。",
        "简版与详细内容的区别" to "默认导出简版排错摘要；打开「导出详细内容」才附带聊天原文，导出前会再次请你确认。"
    ) else listOf(
        "备份单个角色" to "只导出这一个角色的人设、聊天、记忆、心情与学习记录，不会带上本人头像和 API 密钥。",
        "备份全部数据" to "适合换手机使用，打包全部角色、设置与模型配置。勾选包含密钥时需设密码，只把密钥加密锁好。",
        "从文件导入" to "导入前会先预览影响范围再确认。选「新增」不碰现有资料，选「覆盖」才替换对应内容。"
    )
    ModalBottomSheet(onDismissRequest=close, containerColor=Paper, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=UiSpace.page).padding(bottom=12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).testTag("backup-help-content"),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(diagnostics) "诊断日志说明" else "备份与恢复说明", style=MaterialTheme.typography.titleLarge, color=Ink)
                Text(if(diagnostics) "遇到报错或卡顿时，用来帮你定位原因" else "数据都在你本机，按需要选一种打包", style=MaterialTheme.typography.bodySmall,color=Muted)
                sections.forEach { (title,body) ->
                    HorizontalDivider(Modifier.padding(vertical=6.dp), thickness=.5.dp,color=Line)
                    Text(title,style=MaterialTheme.typography.titleSmall,color=Ink)
                    Text(body,style=MaterialTheme.typography.bodyMedium,color=Muted)
                }
            }
            Button(close,Modifier.fillMaxWidth().heightIn(min=UiSpace.action).testTag("backup-help-close"),
                colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=MaterialTheme.colorScheme.onSecondaryContainer)) { Text("知道了") }
        }
    }
}

@Composable internal fun BackupRoleDialog(friends:List<Friend>,selected:String,select:(String)->Unit,close:()->Unit,export:()->Unit){
    AlertDialog(onDismissRequest=close,title={Text("备份单个角色")},text={Column(Modifier.heightIn(max=360.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("选择要备份的角色。导出该角色全部已保存资料和资源，不含其他角色、本人头像或全局密钥。",style=MaterialTheme.typography.bodySmall,color=Muted)
        if(friends.isEmpty())Text("还没有可备份的角色。",style=MaterialTheme.typography.bodyMedium,color=Muted)
        if(friends.isNotEmpty())LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).selectableGroup().testTag("backup-role-list"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        itemsIndexed(friends,key={_,friend->friend.id}){index,friend->val chosen=selected==friend.id
            Row(Modifier.fillMaxWidth().heightIn(min=UiSpace.action).selectable(selected=chosen,role=Role.RadioButton,onClick={select(friend.id)}).testTag("archive-export-${friend.id}").padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                Avatar(friend,44,Modifier.clearAndSetSemantics{})
                Text(friend.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=Ink,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                RadioButton(chosen,null)
            }
            if(index<friends.lastIndex)HorizontalDivider(color=Line,thickness=.5.dp)
        }
        }
    }},confirmButton={TextButton(export,enabled=friends.any{it.id==selected},modifier=Modifier.heightIn(min=UiSpace.action).testTag("archive-export")){Text("导出")}},dismissButton={TextButton(close,modifier=Modifier.heightIn(min=UiSpace.action)){Text("取消")}})
}
