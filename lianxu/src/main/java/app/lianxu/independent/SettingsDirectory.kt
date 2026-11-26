package app.lianxu.independent

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val SettingsPaper:androidx.compose.ui.graphics.Color @Composable get()=Chrome
private val SettingsInk:androidx.compose.ui.graphics.Color @Composable get()=Ink
private val SettingsMuted:androidx.compose.ui.graphics.Color @Composable get()=Muted

@Composable internal fun SettingsDirectory(vm:AppState,models:ModelConfigState=viewModel()) {
    val current=models.configs.firstOrNull {it.id==models.defaultId}
    val summary=when {
        current!=null->"当前默认：${current.name} · ${current.modelId}"
        models.statusError&&models.status.isNotBlank()&&models.configs.isEmpty()->"请进入查看配置提示"
        models.configs.isNotEmpty()->"尚未选择默认模型"
        else->"添加服务商并选择默认模型"
    }
    Column(Modifier.fillMaxSize().background(SettingsPaper).verticalScroll(rememberScrollState()).padding(UiSpace.page).testTag("settings-home"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionLabel("聊天与陪伴")
        DirectoryGroup {
            DirectoryRow("model","大模型配置",summary,"settings-models","settings-model-summary"){vm.open("models")}
            DirectoryDivider()
            DirectoryRow("purposes","模型用途","聊天、记忆、轻回顾与向量分工","settings-purposes"){vm.open("models-purposes")}
            DirectoryDivider()
            DirectoryRow("memory","记忆规则","保留轮数、总结节奏与轻回顾间隔","settings-memory-rules"){vm.open("settings-memory")}
            DirectoryDivider()
            DirectoryRow("tools","工具包","看图、视频、记忆工具与主动联系","settings-toolbox"){vm.open("toolbox")}
        }
        SectionLabel("数据")
        DirectoryGroup {
            DirectoryRow("archive","备份与诊断","角色导出、全量加密备份与日志","settings-role-archive"){vm.open("role-archive")}
        }
        SectionLabel("应用")
        DirectoryGroup {
            DirectoryRow("updates","更新说明","随安装包保存，可随时回看","settings-updates"){vm.open("settings-updates")}
            DirectoryDivider()
            DirectoryRow("about","关于日常陪伴","作者、版本、许可与致谢","settings-info"){vm.open("settings-info")}
        }
    }
}

@Composable internal fun DirectoryGroup(content:@Composable ColumnScope.()->Unit){
    val shape=RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Paper).border(.5.dp,Line,shape),content=content)
}

@Composable internal fun DirectoryDivider(){
    HorizontalDivider(Modifier.padding(start=64.dp,end=14.dp),thickness=.5.dp,color=Line)
}

@Composable internal fun DirectoryRow(icon:String,title:String,description:String,tag:String,descriptionTag:String="",onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(role=Role.Button,onClick=onClick).testTag(tag).padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        DirectoryBadge(icon);Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,color=SettingsInk,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium)
            Text(description,Modifier.then(if(descriptionTag.isEmpty())Modifier else Modifier.testTag(descriptionTag)),style=MaterialTheme.typography.bodySmall,color=SettingsMuted,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp));Text("›",color=Muted,fontSize=19.sp)
    }
}

@Composable private fun SettingsPage(title:String,onBack:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().background(SettingsPaper)) {
        AppTopBar(title,onBack,"settings-back","返回设置")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
    }
}

@Composable internal fun DataAndVersion(onBack:()->Unit) {
    val context=LocalContext.current
    val build=remember(context){runCatching{InstalledBuild.read(context)}.getOrNull()}
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    SettingsPage("关于日常陪伴",onBack) {
        Column(Modifier.fillMaxWidth().testTag("settings-info-page").padding(vertical=24.dp),
            horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("恋叙", style=BrandStyle, color=Ink)
            Text("安静陪伴，数据留在自己手里",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text(build?.versionLabel ?: "暂时无法读取", Modifier.testTag("settings-version")
                .background(MaterialTheme.colorScheme.surfaceContainer,RoundedCornerShape(50)).padding(horizontal=12.dp,vertical=4.dp),
                style=MaterialTheme.typography.labelMedium,color=Ink)
        }
        Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(18.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("隐私与存储",style=MaterialTheme.typography.labelSmall,color=Muted)
            Text("角色、聊天、记忆与加密后的 API 密钥均保存在本机，不会自动上传。卸载应用会清除数据，换手机前请先做好备份。",style=MaterialTheme.typography.bodyMedium,color=Ink)
        }
        DirectoryGroup {
            InformationRow("作者", AppInformation.AUTHOR)
            InformationRow("开源许可", AppInformation.LICENSE)
            InformationRow("项目源码", "›") { uri.openUri(AppInformation.REPOSITORY) }
            InformationRow("查看许可", "›") { uri.openUri(AppInformation.LICENSE_URL) }
            InformationRow("第三方致谢", "›", last=true) { uri.openUri(AppInformation.NOTICES_URL) }
        }
    }
}

@Composable internal fun UpdatesScreen(onBack:()->Unit){
    val context=LocalContext.current
    val build=remember(context){runCatching{InstalledBuild.read(context)}.getOrNull()}
    SettingsPage("更新说明",onBack){
        UpdatesContent(build)
    }
}

@Composable private fun InformationRow(title:String,value:String,last:Boolean=false,open:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().then(if(open==null) Modifier else Modifier.clickable(role=Role.Button,onClick=open))
        .heightIn(min=UiSpace.action).padding(horizontal=16.dp), verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=Ink)
        Text(value,style=MaterialTheme.typography.bodyMedium,color=Muted)
    }
    if (!last) HorizontalDivider(Modifier.padding(horizontal=16.dp), thickness=.5.dp, color=Line)
}
