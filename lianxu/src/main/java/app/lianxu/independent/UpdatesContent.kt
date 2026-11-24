package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable internal fun UpdatesContent(build:InstalledBuild?){
    val current=build?.let{LocalReleaseNotes.forVersion(it.versionCode)}
    val history=build?.let{LocalReleaseNotes.history(it.versionCode).filterNot{note->note.code==it.versionCode}}.orEmpty()
    var expanded by rememberSaveable(build?.versionCode){mutableStateOf(true)}
    Column(Modifier.fillMaxWidth().testTag("updates-page"),verticalArrangement=Arrangement.spacedBy(UiSpace.group)){
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper)){
            Row(Modifier.fillMaxWidth().heightIn(min=72.dp).clickable(role=Role.Button,onClickLabel=if(expanded)"收起当前版本" else "展开当前版本"){expanded=!expanded}
                .testTag("updates-current-toggle").semantics{stateDescription=if(expanded)"已展开" else "已收起"}.padding(18.dp),verticalAlignment=Alignment.CenterVertically){
                Text(build?.let{"当前安装版本 ${it.versionName}"}?:"版本信息暂时无法读取",Modifier.weight(1f).testTag("updates-installed-version"),style=MaterialTheme.typography.titleLarge,color=Ink)
                Spacer(Modifier.width(8.dp));Box(Modifier.rotate(if(expanded)0f else 180f)){ControlIcon("up")}
            }
            if(expanded)Column(Modifier.fillMaxWidth().padding(horizontal=18.dp).padding(bottom=16.dp).testTag("updates-current-content"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                HorizontalDivider(color=Line)
                val experience=build?.let{UpdatesPresentation.experienceFor(it.versionCode)}
                if(experience!=null){
                    NumberedUpdates(experience,"experience")
                    current?.let{CurrentReleaseRecord(it)}
                }else if(current!=null){
                    NumberedUpdates(UpdatesPresentation.summary(current),"current")
                    OriginalRecord(current,"current")
                }else Text(if(build==null)"版本信息暂时无法读取，无法匹配随包记录。" else "当前安装版本没有随包更新记录。",Modifier.testTag("updates-current-missing"),style=MaterialTheme.typography.bodyMedium,color=Muted)
            }
        }
        if(history.isNotEmpty()){
            Text("历史版本",style=MaterialTheme.typography.titleMedium,color=Ink)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper)){
                history.forEachIndexed{index,note->key(note.code){
                    HistoricalRelease(note)
                    if(index<history.lastIndex)HorizontalDivider(Modifier.padding(horizontal=18.dp),color=Line)
                }}
            }
        }
    }
}

@Composable private fun CurrentReleaseRecord(note:LocalReleaseNote){
    var open by rememberSaveable(note.code){mutableStateOf(false)}
    TextButton(onClick={open=!open},modifier=Modifier.heightIn(min=UiSpace.action).testTag("updates-published-toggle").semantics{stateDescription=if(open)"已展开" else "已收起"}){
        Text(if(open)"收起 ${note.version} 已发布记录" else "查看 ${note.version} 已发布记录")
    }
    if(open)Column(Modifier.testTag("updates-published-content"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("${note.version} 已发布内容",style=MaterialTheme.typography.titleSmall,color=Ink)
        NumberedUpdates(UpdatesPresentation.summary(note),"published")
        OriginalRecord(note,"published")
    }
}

@Composable private fun HistoricalRelease(note:LocalReleaseNote){
    var open by rememberSaveable(note.code){mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().heightIn(min=72.dp).clickable(role=Role.Button,onClickLabel=if(open)"收起 ${note.version}" else "查看 ${note.version}"){open=!open}
        .testTag("updates-history-${note.code}").semantics{stateDescription=if(open)"已展开" else "已收起"}.padding(18.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(note.version,style=MaterialTheme.typography.titleSmall,color=Ink)
            if(note.title!="恋叙 ${note.version}")Text(note.title,style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Spacer(Modifier.width(8.dp));Box(Modifier.rotate(if(open)0f else 180f)){ControlIcon("up")}
    }
    if(open)Column(Modifier.fillMaxWidth().padding(horizontal=18.dp).padding(bottom=18.dp).testTag("updates-history-content-${note.code}"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("以下为该版本当时的记录，不代表当前版本能力。",style=MaterialTheme.typography.bodySmall,color=Muted)
        NumberedUpdates(UpdatesPresentation.summary(note),"history-${note.code}")
        OriginalRecord(note,"history-${note.code}")
    }
}

@Composable private fun OriginalRecord(note:LocalReleaseNote,prefix:String){
    var open by rememberSaveable(note.code){mutableStateOf(false)}
    TextButton(onClick={open=!open},modifier=Modifier.heightIn(min=UiSpace.action).testTag("updates-details-$prefix").semantics{stateDescription=if(open)"已展开" else "已收起"}){
        Text(if(open)"收起完整记录" else "完整记录与当时验证")
    }
    if(open)Column(Modifier.testTag("updates-original-$prefix"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(UpdatesPresentation.summary(note)!=note.changes)NumberedUpdates(note.changes,"original-$prefix")
        note.limits.forEach{Text(it,style=MaterialTheme.typography.bodySmall,color=Muted)}
    }
}

@Composable internal fun NumberedUpdates(items:List<String>,prefix:String){
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        items.forEachIndexed{index,text->
            Row(Modifier.fillMaxWidth().testTag("updates-item-$prefix-${index+1}").semantics(mergeDescendants=true){},verticalAlignment=Alignment.Top){
                Text("${index+1}、",Modifier.widthIn(min=28.dp),style=MaterialTheme.typography.bodyMedium,color=GreenText)
                Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=Ink)
            }
        }
    }
}
