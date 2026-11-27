package app.lianxu.independent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

private data class CompanionControlsData(val role:String,val generation:Long,val failure:ContactFailure?,val retryable:Int=0,val policy:String="disabled")

internal fun contactPolicyText(policy:String)=when(policy){
    "enabled"->"已开启"
    "paused_by_user"->"已暂停"
    "awaiting_user_resume"->"恢复后待你确认开启"
    else->"未开启"
}

@Composable internal fun ToolboxContactScreen(vm:AppState){
    var policies by remember{mutableStateOf<Map<String,String>>(emptyMap())}
    var ready by remember{mutableStateOf(false)};var failed by remember{mutableStateOf(false)}
    var reload by remember{mutableIntStateOf(0)}
    LaunchedEffect(vm,vm.friends,vm.processingRevision,reload){
        ready=false
        try{policies=vm.companion.contactPolicies();ready=true;failed=false}
        catch(e:CancellationException){throw e}catch(_:Exception){failed=true}
    }
    ToolboxPage("主动联系",{vm.back()},"toolbox-contact-page"){
        Text("按角色分别设置",style=MaterialTheme.typography.titleMedium)
        if(failed)Row(verticalAlignment=Alignment.CenterVertically){Text("暂时读不到联系状态。",Modifier.weight(1f),color=Muted);TextButton({reload++}){Text("重试")}}
        if(vm.friends.isEmpty())Text("还没有角色，创建后可在这里设置。",style=MaterialTheme.typography.bodyMedium,color=Muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper)){
            vm.friends.forEachIndexed{index,friend->
                Row(Modifier.fillMaxWidth().clickable(enabled=ready&&!failed){vm.open("role-companion",friend.id)}.testTag("toolbox-contact-${friend.id}").padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    Avatar(friend);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(friend.name,style=MaterialTheme.typography.titleSmall);Text(if(ready)contactPolicyText(policies[friend.id].orEmpty())else"正在读取…",style=MaterialTheme.typography.bodySmall,color=Muted)}
                }
                if(index<vm.friends.lastIndex)HorizontalDivider(Modifier.padding(start=72.dp,end=18.dp),color=Line)
            }
        }
        ContactHoursSettings(vm,edit=true)
        Text("应用通知",style=MaterialTheme.typography.titleMedium)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper)){GlobalNotificationControl()}
        Text("消息通知是本应用的系统权限，所有角色共用；不会替你开启任何角色的主动联系。",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}

@Composable private fun GlobalNotificationControl(){
    val context=LocalContext.current
    fun allowed()=NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notifications by remember{mutableStateOf(allowed())};var asked by remember{mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){notifications=allowed()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)notifications=allowed()};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    CompanionSwitchRow("允许消息通知",CompanionHints.NOTIFY,"companion-notification",notifications,true){want->
        val canAsk=want&&Build.VERSION.SDK_INT>=33&&!asked&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
        if(canAsk){asked=true;permission.launch(Manifest.permission.POST_NOTIFICATIONS)}
        else openNotificationSettings(context)
    }
}

/** ⓘ 点开后的一句大白话；页面上不常显说明。 */
internal object CompanionHints {
    const val CONTACT="她想你时会自己找你"
    const val NOTIFY="有消息时提醒你"
    const val RETRY="回访卡住时点这里重新安排"
    const val BACKGROUND="为了让她更有机会按约定联系你，建议在手机系统设置中允许本应用后台运行，并将电池限制设为‘不受限制’。不同手机的名称可能不同，需要你自行设置；系统仍可能延迟，不能保证准点。"
}

/** Toolbox role selection enters this page; permission remains bound to this role. */
@Composable internal fun CompanionSettingsScreen(vm:AppState){
    val role=vm.activeId
    val friend=vm.friends.firstOrNull{it.id==role}
    var detail by remember(vm,role){mutableStateOf<CompanionControlsData?>(null)}
    var loadError by remember(vm,role){mutableStateOf(false)}
    var backgroundHint by remember(vm,role){mutableStateOf(false)}
    var saving by remember(vm,role){mutableStateOf(false)}
    var saveError by remember(vm,role){mutableStateOf("")}
    var reload by remember(vm,role){mutableIntStateOf(0)}
    var generation by remember(vm,role){mutableLongStateOf(0L)}
    val loaded=friend!=null&&detail?.role==role&&!loadError
    LaunchedEffect(vm,role,vm.processingRevision,reload){
        val ticket=++generation
        if(friend==null)return@LaunchedEffect
        try{
            val (policy,found)=Diagnostics.span("companion.controls.load",mapOf("role" to role,"generation" to ticket)){
                val policy=requireNotNull(vm.companion.contactPolicies()[role]){"角色已不存在"}.also{Diagnostics.event("companion.controls.policy_read")}
                policy to (vm.companion.latestContactFailure(role) to vm.companion.retryable(role))
            }
            withContext(Dispatchers.Main.immediate){
                if(vm.activeId==role&&generation==ticket){detail=CompanionControlsData(role,ticket,found.first,found.second,policy);loadError=false;Diagnostics.event("companion.controls.published",mapOf("role" to role,"generation" to ticket))}
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){
            Diagnostics.failure("companion.controls.failed",e,mapOf("role" to role,"generation" to ticket))
            withContext(Dispatchers.Main.immediate){if(vm.activeId==role&&generation==ticket)loadError=true}
        }
    }
    LaunchedEffect(detail?.generation,loadError){
        Diagnostics.event("companion.controls.rendered",mapOf("role" to role,"generation" to detail?.generation,"loaded" to loaded,"failed" to loadError))
    }
    Column(Modifier.fillMaxSize().background(Chrome).testTag("companion-page")){
        Header("主动联系",{vm.back()})
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(20.dp)){
            Text(friend?.name?:"角色已不存在",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("companion-role-name"))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper)){
                CompanionSwitchRow("允许主动联系",CompanionHints.CONTACT,"companion-enabled",loaded&&detail?.policy=="enabled",loaded&&!saving){enabled->
                    generation++;saving=true;saveError=""
                    vm.updateCompanionPermission(role,enabled){result->
                        result.onSuccess{policy->if(vm.activeId==role){detail=detail?.copy(policy=policy);if(enabled&&policy=="enabled")backgroundHint=true}}
                            .onFailure{saveError="主动联系操作未完整完成，请核对当前开关后重试。";detail=null;reload++};saving=false
                    }
                }
                val retryable=detail?.retryable?.takeIf{loaded}?:0
                if(retryable>0){
                    HorizontalDivider(Modifier.padding(horizontal=20.dp),color=Line,thickness=.5.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=8.dp,end=16.dp),verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={saving=true;saveError="";vm.retryCompanionContact(role){result->result.onSuccess{count->if(vm.activeId==role)detail=detail?.copy(retryable=count)}.onFailure{saveError="重新安排未完整完成，请核对回访状态后重试。";detail=null;reload++};saving=false}},enabled=!saving,modifier=Modifier.testTag("companion-retry")){Text("重新安排未完成的回访（$retryable）",color=GreenText)}
                        InfoMark("重新安排未完成的回访",CompanionHints.RETRY,"companion-retry")
                    }
                }
            }
            ContactHoursSettings(vm,edit=false){backgroundHint=true}
            detail?.failure?.takeIf{loaded}?.let{ContactFailureCard(it)}
            if(loaded)Text(contactPolicyText(detail?.policy.orEmpty()),style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.testTag("companion-policy-status"))
            if(loadError)Text("读不到本地状态，返回后再试。",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(saveError.isNotEmpty())Text(saveError,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        }
    }
    if(backgroundHint)ContactBatteryGuide{backgroundHint=false}
}

/** 放法 B：只在真失败过时出现的浅灰提示卡，点开看完整原始报错。 */
@Composable private fun ContactFailureCard(f:ContactFailure){
    var open by remember(f.at){mutableStateOf(false)}
    val shape=RoundedCornerShape(UiSpace.radius)
    Row(Modifier.fillMaxWidth().clip(shape).background(Chrome).border(.5.dp,Line,shape).clickable{open=true}.testTag("companion-last-failure")){
        Box(Modifier.padding(top=22.dp).width(3.dp).height(22.dp).background(Color(0xFFE6A3A3),RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f).padding(start=17.dp,end=20.dp,top=18.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("上次没发出去",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=Ink)
                Text(ContactFailureText.time(f.at),style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            Text(ContactFailureText.summary(f),Modifier.testTag("companion-last-failure-summary"),style=MaterialTheme.typography.bodyMedium,color=Ink)
            if(f.detail.isNotBlank())Text(ContactFailureText.preview(f.detail),style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("查看完整内容",Modifier.testTag("companion-last-failure-open"),style=MaterialTheme.typography.bodyMedium,color=GreenText)
        }
    }
    if(open)ChatFailureDialog(ContactFailureText.full(f)){open=false}
}

@Composable private fun CompanionSwitchRow(title:String,hint:String,tag:String,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(start=20.dp,end=16.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,style=MaterialTheme.typography.bodyLarge,color=Ink)
        InfoMark(title,hint,tag)
        Spacer(Modifier.weight(1f))
        Switch(checked,onChange,Modifier.testTag(tag),enabled=enabled)
    }
}

/** 灰色小 ⓘ：点一下在下方弹一句解释，点别处收起。 */
@Composable private fun InfoMark(title:String,hint:String,tag:String){
    var open by remember{mutableStateOf(false)}
    Box{
        IconButton(onClick={open=!open},modifier=Modifier.size(40.dp).testTag("$tag-info").semantics{contentDescription="${title}是什么意思"}){ControlIcon("info",Muted,18.dp)}
        if(open){
            val below=with(LocalDensity.current){38.dp.roundToPx()}
            Popup(alignment=Alignment.TopCenter,offset=IntOffset(0,below),onDismissRequest={open=false},properties=PopupProperties(focusable=true)){
                Text(hint,Modifier.shadow(3.dp,RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Paper).border(.5.dp,Line,RoundedCornerShape(10.dp)).padding(horizontal=14.dp,vertical=10.dp).testTag("$tag-hint"),style=MaterialTheme.typography.bodyMedium,color=Ink)
            }
        }
    }
}

@Composable internal fun CompanionReadMarker(vm:AppState,message:Message,bounds:Rect,viewport:Rect,enabled:Boolean,showLabel:Boolean){
    val receipt=vm.companionReceipts.firstOrNull{it.messageId==message.id}?:return
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val focused=LocalWindowInfo.current.isWindowFocused
    val visible=enabled&&MessageVisibility.eligible(bounds.top,bounds.bottom,bounds.left,bounds.right,viewport.top,viewport.bottom,viewport.left,viewport.right,lifecycle.isAtLeast(Lifecycle.State.RESUMED),focused)
    val currentlyVisible=rememberUpdatedState(visible)
    LaunchedEffect(message.id,visible,bounds,viewport,receipt.readAt){
        if(visible&&receipt.readAt==null){delay(600);vm.companionRead(message.friendId,message.id){currentlyVisible.value}}
    }
    // Older messages still become read when visible; only their label is hidden.
    if(showLabel)Text(if(receipt.readAt==null)"未读"else"已读",Modifier.testTag("read-${message.id}"),style=MaterialTheme.typography.labelSmall,color=Muted)
}
