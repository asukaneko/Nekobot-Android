package app.lianxu.independent

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first

/** Same light sample, source bounds and stretch as the settled approved door. */
@Composable private fun EncounterWarmLight(modifier:Modifier){
    val resources=LocalContext.current.resources
    val painting=remember(resources){BitmapFactory.decodeResource(resources,R.drawable.first_encounter_approved,BitmapFactory.Options().apply{inScaled=false})}
    val paint=remember{Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)}
    val source=remember{Rect(540,40,1010,630)}
    val target=remember{RectF()}
    Canvas(modifier){drawIntoCanvas{target.set(0f,0f,size.width,size.height);it.nativeCanvas.drawBitmap(painting,source,target,paint)}}
}

@Composable internal fun RoleCreationGuide(
    draft:RoleCreationDraft,onDraft:(RoleCreationDraft)->Unit,busy:Boolean,saved:Boolean=false,
    notice: @Composable () -> Unit,
    polisher:RoleCreationPolish?=null,onConfigure:()->Unit={},
    onExit:()->Unit,onCreate:(RoleCreationDraft)->Unit
){
    CreationColors {
    val scope=rememberCoroutineScope()
    val reduced=(scope.coroutineContext[MotionDurationScale]?.scaleFactor?:1f)<=0f
    val position=remember{Animatable(if(reduced)0f else -1f)}
    var entering by remember{mutableStateOf(!reduced)}
    var targetStep by remember{mutableStateOf<RoleCreationStep?>(null)}
    var problem by remember{mutableStateOf<String?>(null)}
    var exitDialog by rememberSaveable{mutableStateOf(false)}
    var submitting by remember{mutableStateOf(false)}
    val currentDraft by rememberUpdatedState(draft)
    val currentOnDraft by rememberUpdatedState(onDraft)
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val moving=entering||targetStep!=null
    val enabled=!moving&&!busy&&!submitting
    LaunchedEffect(submitting,busy){if(submitting&&!busy)submitting=false}
    LaunchedEffect(draft.step,polisher){if(draft.step==RoleCreationStep.MODEL)polisher?.refreshModel()}
    DisposableEffect(lifecycle,polisher){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)polisher?.cancel()}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);polisher?.cancel()}
    }

    LaunchedEffect(targetStep,reduced){
        if(reduced){
            targetStep?.let{currentOnDraft(currentDraft.copy(step=it))}
            position.snapTo(0f);targetStep=null;entering=false
        }else if(targetStep!=null){
            val next=requireNotNull(targetStep)
            position.animateTo(1f,tween(280,easing=CubicBezierEasing(.4f,0f,.8f,1f)))
            currentOnDraft(currentDraft.copy(step=next))
            position.snapTo(-1f)
            targetStep=null // The new effect lowers the new card from above.
        }else{
            position.animateTo(0f,tween(420,easing=CubicBezierEasing(.2f,0f,.2f,1f)))
            entering=false
        }
    }
    fun locked()=entering||targetStep!=null||busy||saved||submitting
    fun canNavigate()=!locked()&&!exitDialog
    fun moveTo(step:RoleCreationStep){
        if(!canNavigate())return
        if(draft.step==RoleCreationStep.MODEL)polisher?.cancel()
        focus.clearFocus();keyboard?.hide();problem=null;entering=true;targetStep=step
    }
    fun leave(){
        if(!canNavigate())return
        polisher?.cancel()
        focus.clearFocus();keyboard?.hide()
        if(draft.hasContent)exitDialog=true else onExit()
    }
    fun previous(){if(canNavigate()){draft.previous()?.let{moveTo(it)}?:leave()}}
    fun editPolished(change:(RoleCreationText)->RoleCreationText){
        if(canNavigate())currentDraft.polished?.let{currentOnDraft(currentDraft.copy(polished=change(it)))}
    }
    LaunchedEffect(polisher?.result){
        val value=polisher?.result?:return@LaunchedEffect
        snapshotFlow{!locked()&&!exitDialog}.first{it}
        val active=currentDraft
        if(polisher.result===value&&active.step==RoleCreationStep.MODEL&&active.creationId==value.creationId&&active.text==value.source){
            if(polisher.consumeCurrent(value){currentOnDraft(active.copy(polished=value.value,usePolished=true))})moveTo(RoleCreationStep.CONFIRM)
        }
    }
    BackHandler{previous()}

    if(exitDialog)AlertDialog(
        onDismissRequest={if(!locked())exitDialog=false},title={Text("暂时离开？")},
        text={Column(verticalArrangement=Arrangement.spacedBy(UiSpace.small)){
            Text("可以保留本次草稿，回来继续填写。")
            TextButton(onClick={if(!locked()){currentOnDraft(RoleCreationDraft());exitDialog=false;onExit()}},modifier=Modifier.testTag("create-discard")){Text("放弃草稿并退出")}
        }},
        confirmButton={TextButton(onClick={if(!locked()){exitDialog=false;onExit()}},modifier=Modifier.testTag("create-keep-exit")){Text("保留草稿并退出")}},
        dismissButton={TextButton(onClick={if(!locked())exitDialog=false}){Text("继续填写")}}
    )
    Box(Modifier.fillMaxSize().testTag("role-creation-guide")){
        if (draft.step == RoleCreationStep.MODEL) {
            Spacer(Modifier.matchParentSize().background(Color(0xFFFAF6ED)))
        } else {
            EncounterWarmLight(Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()){
            notice()
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=UiSpace.small),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={previous()},enabled=enabled&&!saved,modifier=Modifier.size(UiSpace.action).testTag("create-back").semantics{contentDescription="返回"}){ControlIcon("back",Ink)}
                Text("创建角色",Modifier.weight(1f).padding(horizontal=UiSpace.small),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={leave()},enabled=enabled&&!saved,modifier=Modifier.heightIn(min=UiSpace.action).testTag("create-cancel")){Text("取消")}
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal=UiSpace.page,vertical=UiSpace.small).clipToBounds()){
                val travel=with(LocalDensity.current){maxHeight.toPx()*2f}
                if (draft.step == RoleCreationStep.MODEL) {
                    ModelPolishStep(
                        modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 480.dp)
                            .fillMaxWidth().graphicsLayer { translationY = position.value * travel }
                            .testTag("create-card").semantics { stateDescription = if (moving) "切换中" else "可填写" },
                        draft = draft, polisher = polisher, enabled = enabled && !saved,
                        onConfigure = {
                            if (canNavigate()) {
                                polisher?.cancel()
                                focus.clearFocus()
                                keyboard?.hide()
                                onConfigure()
                            }
                        },
                        onPolish = { if (canNavigate()) polisher?.start(draft) },
                        onPreview = { usePolished ->
                            if (canNavigate()) {
                                onDraft(draft.copy(usePolished = usePolished))
                                moveTo(RoleCreationStep.CONFIRM)
                            }
                        }
                    )
                    return@BoxWithConstraints
                }
                Card(
                    modifier=Modifier.align(Alignment.Center).widthIn(max=480.dp).fillMaxWidth().heightIn(max=maxHeight)
                        .graphicsLayer{translationY=position.value*travel}
                        .testTag("create-card").semantics{stateDescription=if(moving)"切换中"else"可填写"},
                    shape=RoundedCornerShape(UiSpace.radius),colors=CardDefaults.cardColors(containerColor=Paper)
                ){
                    key(draft.step){
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpace.group),verticalArrangement=Arrangement.spacedBy(UiSpace.group)){
                            Text("${draft.step.ordinal+1} / ${RoleCreationStep.entries.size}",style=MaterialTheme.typography.bodySmall,color=Muted)
                            Text(when(draft.step){
                                RoleCreationStep.NAME->"你想怎样称呼这位角色？"
                                RoleCreationStep.DEFINITION->"这是一位怎样的角色？"
                                RoleCreationStep.RELATIONSHIP->"你们之间是什么关系？"
                                RoleCreationStep.MORE->"还有想补充的吗？"
                                RoleCreationStep.MODEL->"润色角色资料"
                                RoleCreationStep.CONFIRM->"准备好开始相遇了吗？"
                            },Modifier.testTag("create-step-${draft.step.name.lowercase()}").semantics{liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.titleLarge)
                            when(draft.step){
                                RoleCreationStep.NAME->{
                                    Text("从一个名字开始，不需要先选性别。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                                    CreationField(draft.name,{onDraft(draft.copy(name=creationText(it,30)));problem=null},"名字（必填）","friend-name",singleLine=true,enabled=enabled&&!saved)
                                }
                                RoleCreationStep.DEFINITION->{
                                    Text("写下性格、经历，或你希望保留的特点。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                                    CreationField(draft.definition,{onDraft(draft.copy(definition=creationText(it,8000)));problem=null},"角色设定（必填）","friend-bio",minLines=3,enabled=enabled&&!saved)
                                }
                                RoleCreationStep.RELATIONSHIP->{
                                    Text("这是你设定的相处起点，留空后点下一步即可继续。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                                    CreationField(draft.relationship,{onDraft(draft.copy(relationship=creationText(it,4000)))},"初始关系背景（可选）","friend-relationship",minLines=2,enabled=enabled&&!saved)
                                }
                                RoleCreationStep.MORE->{
                                    Text("更多设置均可留空，之后也能在角色设定里修改。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                                    CreationField(draft.expression,{onDraft(draft.copy(expression=creationText(it,8000)))},"说话方式（可选）","friend-expression",minLines=2,enabled=enabled&&!saved)
                                    CreationField(draft.opening,{onDraft(draft.copy(opening=creationText(it,8000)))},"开场白（可选）","friend-opening",minLines=2,enabled=enabled&&!saved)
                                    CreationField(draft.extra,{onDraft(draft.copy(extra=creationText(it,4000)))},"自定义提示词（可选）","friend-prompt",minLines=3,enabled=enabled&&!saved)
                                    Text("模型仍在设置中选择；未配置聊天模型时不会生成演示回复。",style=MaterialTheme.typography.bodySmall,color=Muted)
                                }
                                RoleCreationStep.MODEL -> Unit // Rendered separately above.
                                RoleCreationStep.CONFIRM->{
                                    if(draft.polished!=null)Row{
                                        TextButton(onClick={if(canNavigate()){focus.clearFocus();keyboard?.hide();onDraft(draft.copy(usePolished=true))}},enabled=enabled&&!saved,modifier=Modifier.testTag("create-select-polished")){Text(if(draft.usePolished)"已选润色稿"else"使用润色稿")}
                                        TextButton(onClick={if(canNavigate()){focus.clearFocus();keyboard?.hide();onDraft(draft.copy(usePolished=false))}},enabled=enabled&&!saved,modifier=Modifier.testTag("create-select-original")){Text(if(draft.usePolished)"使用原文"else"已选原文")}
                                    }
                                    val value=draft.polished
                                    if(draft.usePolished&&value!=null){
                                        Text("润色稿可逐项修改，请核对是否保留了你的原意。确认后才保存角色。",style=MaterialTheme.typography.bodySmall,color=Muted)
                                        CreationField(value.name,{field->editPolished{it.copy(name=creationText(field,30))};problem=null},"名字（必填）","polish-name",singleLine=true,enabled=enabled&&!saved)
                                        CreationField(value.definition,{field->editPolished{it.copy(definition=creationText(field,8000))};problem=null},"角色设定（必填）","polish-definition",minLines=3,enabled=enabled&&!saved)
                                        CreationField(value.relationship,{field->editPolished{it.copy(relationship=creationText(field,4000))}},"初始关系背景（可选）","polish-relationship",minLines=2,enabled=enabled&&!saved)
                                        CreationField(value.expression,{field->editPolished{it.copy(expression=creationText(field,8000))}},"说话方式（可选）","polish-expression",minLines=2,enabled=enabled&&!saved)
                                        CreationField(value.opening,{field->editPolished{it.copy(opening=creationText(field,8000))}},"开场白（可选）","polish-opening",minLines=2,enabled=enabled&&!saved)
                                        CreationField(value.extra,{field->editPolished{it.copy(extra=creationText(field,4000))}},"自定义提示词（可选）","polish-extra",minLines=3,enabled=enabled&&!saved)
                                    }else{
                                        CreationSummary("名字",draft.name)
                                        CreationSummary("角色设定",draft.definition)
                                        CreationSummary("初始关系背景",draft.relationship.ifBlank{"暂未设置"})
                                        if(draft.expression.isNotBlank())CreationSummary("说话方式",draft.expression)
                                        if(draft.opening.isNotBlank())CreationSummary("开场白",draft.opening)
                                        if(draft.extra.isNotBlank())CreationSummary("自定义提示词",draft.extra)
                                        Text("当前使用原文。长内容简要显示，完整内容会保存，可返回修改。",style=MaterialTheme.typography.bodySmall,color=Muted)
                                    }
                                }
                            }
                            problem?.let{Text(it,Modifier.testTag("create-validation"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}
                            if(draft.step!=RoleCreationStep.MODEL)PrimaryAction(if(draft.step==RoleCreationStep.CONFIRM)if(busy)"正在创建…"else if(saved)"打开已创建的角色"else"确认创建"else"下一步",
                                enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(if(draft.step==RoleCreationStep.CONFIRM)"create-friend"else"create-next")){
                                if(!entering&&targetStep==null&&!busy&&!submitting&&!exitDialog){
                                    problem=draft.problem()
                                    if(problem==null){
                                        if(draft.step==RoleCreationStep.CONFIRM){submitting=true;polisher?.cancel();focus.clearFocus();keyboard?.hide();onCreate(draft.selectedDraft())}
                                        else draft.next()?.let{moveTo(it)}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

}

@Composable private fun ModelPolishStep(
    modifier: Modifier,
    draft: RoleCreationDraft,
    polisher: RoleCreationPolish?,
    enabled: Boolean,
    onConfigure: () -> Unit,
    onPolish: () -> Unit,
    onPreview: (Boolean) -> Unit
) {
    val type = MaterialTheme.typography
    val rule = Color(0xFFE7DECE)
    val status = polisher?.modelLabel ?: when {
        polisher?.modelReadFailed == true -> "全局模型配置暂时无法读取"
        polisher?.checked == true -> "尚未配置"
        else -> "正在读取…"
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = UiSpace.small, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val step = draft.step.ordinal + 1
            val total = RoleCreationStep.entries.size
            Text("$step / $total", style = type.bodySmall, color = Muted)
            Box(Modifier.weight(1f).height(2.dp).background(rule)) {
                Spacer(Modifier.fillMaxWidth(step.toFloat() / total).fillMaxHeight().background(Color(0xFFA78D6D)))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("润色角色资料", Modifier.testTag("create-step-model").semantics { liveRegion = LiveRegionMode.Polite }, style = type.titleLarge)
        Spacer(Modifier.height(UiSpace.small))
        Text("只整理你填写的内容，不补写设定。\n润色后先预览，可自由修改，也可使用原文。", style = type.bodyMedium, color = Muted)
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = rule)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            val buttonMaxWidth = maxWidth * .48f
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiSpace.cluster)) {
                    Text("全局聊天模型", style = type.labelLarge)
                    Text(status, Modifier.testTag("create-global-model"), style = type.bodySmall, color = Muted)
                }
                OutlinedButton(
                    onClick = onConfigure, enabled = enabled,
                    modifier = Modifier.widthIn(max = buttonMaxWidth).heightIn(min = UiSpace.action).testTag("create-model-config"),
                    shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFFCFBEA6)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = UiSpace.small)
                ) { Text(if (polisher?.modelLabel == null) "配置模型" else "管理模型", textAlign = TextAlign.Center) }
            }
        }
        HorizontalDivider(color = rule)
        Spacer(Modifier.height(UiSpace.group))
        Text("润色时会将当前填写的角色资料发送给所选服务商，使用已保存的全局配置，不会发送聊天或记忆。", style = type.bodySmall, color = Muted)
        Spacer(Modifier.height(UiSpace.small))
        Text("在此保存的模型配置会用于全局聊天，不影响记忆整理和识图的用途选择。", style = type.bodySmall, color = Muted)
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
            if (polisher?.running == true) {
                Text("正在润色，可以取消或使用原文继续。", Modifier.testTag("create-polish-running"), style = type.bodyMedium)
                TextButton(onClick = { polisher.cancel() }, enabled = enabled, modifier = Modifier.heightIn(min = UiSpace.action).testTag("create-polish-cancel")) { Text("取消润色") }
            } else if (polisher?.modelLabel != null) {
                PrimaryAction(if (draft.polished == null) "润色并预览" else "重新润色", enabled = enabled && polisher.checked,
                    modifier = Modifier.fillMaxWidth().testTag("create-polish"), onClick = onPolish)
            }
            polisher?.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.testTag("create-polish-message"), style = type.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (draft.polished != null) {
                TextButton(onClick = { onPreview(true) }, enabled = enabled, modifier = Modifier.heightIn(min = UiSpace.action).testTag("create-view-polished")) { Text("查看已有润色稿") }
            }
            Button(
                onClick = { onPreview(false) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("create-use-original"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8D8BF), contentColor = Ink)
            ) { Text("使用原文继续") }
            Text("下一步核对资料，确认后才创建", Modifier.fillMaxWidth(), style = type.bodySmall, color = Muted, textAlign = TextAlign.Center)
        }
    }
}

/** Share the warm card surface with floating-label cutouts in both system themes. */
@Composable private fun CreationColors(content:@Composable ()->Unit){
    val colors=MaterialTheme.colorScheme.copy(
        surface=Color(0xFFF1DFC1).copy(alpha=.88f),onSurface=Color(0xFF57402E),
        onSurfaceVariant=Color(0xFF795F48),primary=Color(0xFF79583D),
        secondaryContainer=Color(0xFFE5CFAD),onSecondaryContainer=Color(0xFF57402E),
        surfaceContainerHigh=Color(0xFFF1DFC1),error=Color(0xFF9A3D2A)
    )
    MaterialTheme(colorScheme=colors){CompositionLocalProvider(LocalContentColor provides colors.onSurface,content=content)}
}

@Composable private fun CreationField(value:String,onChange:(String)->Unit,label:String,tag:String,singleLine:Boolean=false,minLines:Int=1,enabled:Boolean=true){
    val fill=Color(0xFFEEE0CC).copy(alpha=.86f)
    val text=Color(0xFF57402E)
    AppField(value,onChange,label,tag,singleLine,minLines,enabled,colors=OutlinedTextFieldDefaults.colors(
        focusedContainerColor=fill,unfocusedContainerColor=fill,disabledContainerColor=fill.copy(alpha=.60f),
        focusedBorderColor=Color(0xFF916D4D),unfocusedBorderColor=Color(0xFFC2A78D),
        focusedTextColor=text,unfocusedTextColor=text,disabledTextColor=text.copy(alpha=.65f),
        focusedLabelColor=Color(0xFF79583D),unfocusedLabelColor=Color(0xFF79583D),cursorColor=Color(0xFF79583D)
    ))
}

@Composable private fun CreationSummary(label:String,value:String){
    Column(verticalArrangement=Arrangement.spacedBy(UiSpace.cluster)){
        Text(label,style=MaterialTheme.typography.labelLarge,color=Muted)
        Text(value,style=MaterialTheme.typography.bodyMedium,maxLines=4,overflow=TextOverflow.Ellipsis)
    }
}
