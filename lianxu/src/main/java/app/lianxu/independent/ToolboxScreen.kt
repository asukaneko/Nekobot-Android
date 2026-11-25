package app.lianxu.independent

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ToolboxCard(val title:String,val subtitle:String,val icon:String)
private val toolboxCards=mapOf(
    "image" to ToolboxCard("图片理解","设置图片的理解方式","landscape"),
    "video" to ToolboxCard("视频理解","设置视频的理解方式","camera"),
    "memory" to ToolboxCard("记忆工具","管理回想与记录能力","brain"),
    "contact" to ToolboxCard("主动联系","按角色设置主动联系","chat")
)

@Composable internal fun ToolboxScreen(vm:AppState){
    var order by remember{mutableStateOf(ToolboxPreferences.defaultOrder)}
    var saved by remember{mutableStateOf(order)}
    var ready by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var reload by remember{mutableIntStateOf(0)}
    val list=rememberLazyListState();val scope=rememberCoroutineScope()
    var dragged by remember{mutableStateOf<String?>(null)}
    var dragTop by remember{mutableFloatStateOf(0f)}
    var pointerY by remember{mutableFloatStateOf(0f)}
    var settling by remember{mutableStateOf<String?>(null)}
    var multiTouch by remember{mutableStateOf(false)}
    val settle=remember{Animatable(0f)}
    val edge=with(LocalDensity.current){48.dp.toPx()}
    LaunchedEffect(vm,reload){ready=false;try{val value=ToolboxPreferences.loadOrder(vm.processing.store);order=value;saved=value;ready=true;error=""}catch(e:CancellationException){throw e}catch(_:Exception){error="暂时读不到工具排序，请重试。"}}
    fun save(){val value=order.toList();busy=true;vm.saveToolboxOrder(value){success->if(success){saved=value;error=""}else{order=saved;error="排序没能保存，已回到原顺序，请重试。"};busy=false}}
    fun move(id:String,target:Int){
        val from=order.indexOf(id)
        if(from<0||target !in order.indices||from==target)return
        order=order.toMutableList().apply{removeAt(from);add(target,id)}
    }
    fun reorder(){
        val id=dragged?:return
        val info=list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==id}?:return
        val center=dragTop+info.size/2f
        val target=list.layoutInfo.visibleItemsInfo.firstOrNull{it.key!=id&&center>=it.offset&&center<=it.offset+it.size}?:return
        move(id,order.indexOf(target.key))
    }
    LaunchedEffect(dragged){
        while(dragged!=null){
            val end=list.layoutInfo.viewportEndOffset.toFloat()
            val speed=when{pointerY<edge->-((edge-pointerY)/4).coerceAtMost(24f);pointerY>end-edge->((pointerY-end+edge)/4).coerceAtMost(24f);else->0f}
            if(speed!=0f){list.scrollBy(speed);reorder()}
            delay(16)
        }
    }
    Column(Modifier.fillMaxSize().background(Chrome).testTag("toolbox-page")){
        AppTopBar("工具包",{vm.back()})
        Text("为陪伴增添更多可能",Modifier.padding(start=68.dp,end=UiSpace.page,bottom=4.dp).testTag("toolbox-subtitle"),style=MaterialTheme.typography.bodySmall,color=Muted)
        if(error.isNotEmpty())Row(Modifier.padding(horizontal=UiSpace.page),verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);if(!ready)TextButton({reload++}){Text("重试")}}
        if(ready)LazyColumn(Modifier.fillMaxSize().testTag("toolbox-list").pointerInput(Unit){
            awaitEachGesture{
                awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial);multiTouch=false
                do{val event=awaitPointerEvent(PointerEventPass.Initial)
                    if(event.changes.count{it.pressed}>1){multiTouch=true;if(dragged!=null){dragged=null;order=saved}}
                }while(event.changes.any{it.pressed})
            }
        }.pointerInput(ready){
            awaitEachGesture{
                val down=awaitFirstDown(requireUnconsumed=false)
                if(!ready||busy||multiTouch||settling!=null)return@awaitEachGesture
                val held=awaitLongPressOrCancellation(down.id)?:return@awaitEachGesture
                if(multiTouch)return@awaitEachGesture
                val item=list.layoutInfo.visibleItemsInfo.firstOrNull{held.position.y>=it.offset&&held.position.y<=it.offset+it.size}?:return@awaitEachGesture
                dragged=item.key as String;dragTop=item.offset.toFloat();pointerY=held.position.y
                try{while(dragged!=null){
                    // Claim movement before LazyColumn's scroll handler; ordinary drags still scroll.
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    val change=event.changes.firstOrNull{it.id==down.id}?:break
                    if(multiTouch||change.isConsumed){event.changes.forEach{it.consume()};break}
                    dragTop+=change.positionChange().y;pointerY=change.position.y;change.consume();reorder()
                    if(!change.pressed){
                        val id=dragged?:break
                        val offset=dragTop-(list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==id}?.offset?:dragTop.toInt())
                        dragged=null;settling=id;save()
                        scope.launch{try{settle.snapTo(offset);settle.animateTo(0f,spring(dampingRatio=1f,stiffness=500f))}finally{settling=null}}
                        break
                    }
                }}finally{if(dragged!=null){dragged=null;order=saved}}
            }
        },state=list,contentPadding=PaddingValues(horizontal=UiSpace.page,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            items(order,key={it}){id->
                val card=toolboxCards.getValue(id);val lifted=id==dragged||id==settling
                val elevation by animateDpAsState(if(lifted)7.dp else 1.dp,label="toolbox-lift")
                Surface(onClick={if(!lifted&&dragged==null&&settling==null)vm.open("toolbox-$id")},enabled=ready&&!busy,
                    modifier=Modifier.fillMaxWidth().animateItem(placementSpec=if(lifted)null else spring(dampingRatio=1f,stiffness=500f)).zIndex(if(lifted)1f else 0f).graphicsLayer{
                        translationY=when(id){dragged->dragTop-(list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==id}?.offset?:dragTop.toInt());settling->settle.value;else->0f}
                    }.testTag("tool-card-$id").semantics{
                        stateDescription="第 ${order.indexOf(id)+1} 项，可长按拖动排序"
                        customActions=if(ready&&!busy&&dragged==null&&settling==null)buildList{
                            val index=order.indexOf(id)
                            if(index>0)add(CustomAccessibilityAction("向上移动"){move(id,index-1);save();true})
                            if(index<order.lastIndex)add(CustomAccessibilityAction("向下移动"){move(id,index+1);save();true})
                        }else emptyList()
                    },shape=RoundedCornerShape(UiSpace.radius),color=Paper,shadowElevation=elevation){
                    Row(Modifier.heightIn(min=84.dp).padding(horizontal=18.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                        Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(13.dp)){Box(Modifier.size(40.dp),contentAlignment=Alignment.Center){ControlIcon(card.icon,GreenText,23.dp)}}
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(card.title,style=MaterialTheme.typography.titleSmall,color=Ink);Text(card.subtitle,style=MaterialTheme.typography.bodySmall,color=Muted)}
                    }
                }
            }
        }else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){if(error.isEmpty())CircularProgressIndicator()}
    }
}

@Composable internal fun ToolboxImageScreen(vm:AppState,state:ModelConfigState=viewModel()){
    LaunchedEffect(state.vaultVersion){state.refresh()}
    ToolboxPage("图片理解",{vm.back()},"toolbox-image-page"){
        PurposeSelection(state,only=ModelPurpose.AUX_VISION)
        if(state.status.isNotBlank())Text(state.status,style=MaterialTheme.typography.bodySmall,color=if(state.statusError)MaterialTheme.colorScheme.error else GreenText)
    }
}

@Composable internal fun ToolboxMemoryScreen(vm:AppState){
    var recall by rememberSaveable{mutableStateOf(true)};var write by rememberSaveable{mutableStateOf(false)}
    var initialized by rememberSaveable{mutableStateOf(false)};var ready by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf(false)};var status by rememberSaveable{mutableStateOf("")}
    var help by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
    LaunchedEffect(vm){try{val rules=vm.processing.memoryRules().first;if(!initialized){recall=rules.proactiveRecall;write=rules.immediateWrite;initialized=true};ready=true}catch(e:CancellationException){throw e}catch(_:Exception){error=true;status="暂时读不到记忆工具设置，请返回重试。"}}
    Column(Modifier.fillMaxSize().background(Chrome).testTag("toolbox-memory-page")){
        AppTopBar("记忆工具",{vm.back()},extra={TextButton({busy=true;scope.launch{try{vm.processing.saveMemoryTools(recall,write);vm.refreshProcessing();error=false;status="已保存，所有角色共用这两个开关。"}catch(e:CancellationException){throw e}catch(_:Exception){error=true;status="保存未完成，请重试。"}finally{busy=false}}},enabled=ready&&!busy,modifier=Modifier.testTag("memory-tools-save")){Text(if(busy)"保存中"else"保存")}})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(UiSpace.radius)).padding(16.dp)){
                MemoryToolRow("主动回查",recall,ready&&!busy,"rules-recall-enabled",{recall=it;status=""},{help="主动回查"})
                HorizontalDivider(color=Line)
                MemoryToolRow("当场写记忆",write,ready&&!busy,"rules-write-enabled",{write=it;status=""},{help="当场写记忆"})
            }
            Text("按需回查，资料足够就回答；重复查询或没有新内容时停止。程序仍保留超时、上下文与工具步数保护，无需设置回查额度。",Modifier.testTag("recall-adaptive-info"),style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("所有角色共用开关，记忆内容仍各自独立。保存设置不调用模型。",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(status.isNotBlank())Text(status,Modifier.testTag("memory-tools-status"),style=MaterialTheme.typography.bodySmall,color=if(error)MaterialTheme.colorScheme.error else GreenText)
        }
    }
    if(help.isNotEmpty())AlertDialog(onDismissRequest={help=""},title={Text(help)},text={Text(if(help=="主动回查")"聊天时，她可以主动查找相关记忆和过往聊天。"else"聊天时，她可以根据你当下说的话保存或更新记忆，不必等总结；写入成功才算记住，不确定或受保护的内容会留待确认。")},confirmButton={TextButton({help=""},Modifier.testTag("rules-help-close")){Text("知道了")}})
}

@Composable private fun MemoryToolRow(title:String,value:Boolean,enabled:Boolean,tag:String,onValue:(Boolean)->Unit,help:()->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,style=MaterialTheme.typography.bodyLarge);IconButton(help,Modifier.size(48.dp).testTag(if(tag=="rules-recall-enabled")"rules-recall-info"else"rules-write-info").semantics{contentDescription="${title}说明"}){ControlIcon("info")};Spacer(Modifier.weight(1f));Switch(value,onValue,enabled=enabled,modifier=Modifier.testTag(tag))}
}

@Composable internal fun ToolboxVideoScreen(vm:AppState){
    var mode by remember{mutableStateOf<ToolboxPreferences.VideoMode?>(null)};var ready by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    LaunchedEffect(vm){try{mode=ToolboxPreferences.videoMode(vm.processing.store);ready=true}catch(e:CancellationException){throw e}catch(_:Exception){error="暂时读不到视频设置，请返回重试。"}}
    ToolboxPage("视频理解",{vm.back()},"toolbox-video-page"){
        Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(UiSpace.radius)).selectableGroup()){
            ToolboxPreferences.VideoMode.entries.forEach{value->Row(Modifier.fillMaxWidth().selectable(mode==value,enabled=ready&&!busy,role=Role.RadioButton){busy=true;vm.saveVideoMode(value){saved->if(saved){mode=value;error=""}else error="保存视频方式失败，请重试。";busy=false}}.testTag("video-mode-${value.name.lowercase()}").padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                RadioButton(mode==value,null,enabled=ready&&!busy);Spacer(Modifier.width(12.dp));Column{Text(if(value==ToolboxPreferences.VideoMode.ORIGINAL)"直传原视频"else"每秒抽取一帧",style=MaterialTheme.typography.titleSmall);Text(if(value==ToolboxPreferences.VideoMode.ORIGINAL)"完整原件，原有音轨也会一并发送"else"每秒一张画面，不发送音轨",style=MaterialTheme.typography.bodySmall,color=Muted)}
            }}
        }
        Text("直传原视频最多 70 MB。抽帧按秒生成高质量 JPEG，大画面按比例缩至 1080p 范围。应用不单独提取或处理声音；直传时服务商可能理解原件中的声音。服务商也可能有更小上限。",style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("沿用图片理解中的接收方：辅助看图开启时交所选辅助模型，关闭时交聊天模型。服务商或模型不支持时如实报错，不自动换模式、换模型、压缩、分批或减少画面。",style=MaterialTheme.typography.bodySmall,color=Muted)
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun ToolboxPage(title:String,onBack:()->Unit,tag:String,content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxSize().background(Chrome).testTag(tag)){AppTopBar(title,onBack);Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)}
}
