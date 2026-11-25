package app.lianxu.independent

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.FilterOutputStream
import java.io.OutputStream

private class PendingBackup(val archive:BackupRepository.Export?=null,val bytes:ByteArray?=null, val trace:String=Diagnostics.traceId()){
    fun clear(){bytes?.fill(0);archive?.close()}
    override fun toString()="PendingBackup(redacted)"
}

@Composable internal fun RoleArchiveScreen(vm:AppState){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)};var job by remember{mutableStateOf<Job?>(null)};var lease by remember{mutableStateOf<ChatAttemptLease?>(null)}
    var selected by rememberSaveable{mutableStateOf("")}
    var status by remember{mutableStateOf("")};var error by remember{mutableStateOf("")};var output by remember{mutableStateOf<PendingBackup?>(null)}
    var incoming by remember{mutableStateOf<BackupArchive.Package?>(null)};var mode by remember{mutableStateOf("")};var target by remember{mutableStateOf("")}
    var chooseKeys by remember{mutableStateOf(false)};var chooseMode by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf(false)}
    var exportConfirmation by remember { mutableStateOf<Any?>(null) }
    val tabs=rememberPageTabs(2)
    val section=tabs.selected
    var chooseRole by remember{mutableStateOf(false)};var help by remember{mutableStateOf(false)}
    val backupScroll=rememberScrollState();val diagnosticScroll=rememberScrollState()
    // Backup password: set twice when keys are exported, asked again when a sealed backup replaces everything.
    var exportPassword by remember{mutableStateOf(false)};var importPassword by remember{mutableStateOf(false)}
    var preview by remember{mutableStateOf<BackupRepository.Preview?>(null)};var legacyPreview by remember{mutableStateOf<RoleArchiveRepository.Preview?>(null)}
    val impact=preview?.impact?:legacyPreview?.impact
    fun clearPreview(){preview=null;legacyPreview=null;confirm=false}
    var importTrace by remember { mutableStateOf<String?>(null) }
    fun work(trace:String = java.util.UUID.randomUUID().toString(), block:suspend(ChatAttemptLease)->Unit){if(busy)return;busy=true;error="";status="";val active=ChatAttemptLease();lease=active
        job=scope.launch(Diagnostics.context("backup.page", trace)){try{block(active)}catch(e:CancellationException){status=if(active.wasCommitted())"导入已完成，资料已刷新；取消到达时事务已经提交。"else"已取消，已保存资料保留。";throw e}catch(e:OutOfMemoryError){error="本机内存不足，处理未完成；请保留原文件重试。"}catch(e:Exception){Diagnostics.failure("backup.page.failed",e);error=if(e is BackupKeySeal.WrongPassword||e is BackupKeySeal.NeedsPassword)e.message.orEmpty() else if(e.message?.contains("重新预览")==true)"资料、配置或备份内容已变化，请重新预览。"else"处理未完成，请检查文件格式、文件权限与可用空间后重试。"}finally{busy=false;lease=null}}
    }
    BackHandler(busy){lease?.cancel();job?.cancel()}
    DisposableEffect(Unit){onDispose{lease?.cancel();output?.clear();incoming?.close()}}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val pending=output;output=null
        if(uri==null){pending?.clear();status="已取消保存"}else if(pending==null){error="导出资料已过期，请重新导出"}else work(pending.trace){active->
            try{withContext(Dispatchers.IO){LocalStep.DOCUMENT_WRITE.observe(enabled = pending.archive != null) { check(active.isActive());requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{raw->
                val checked=object:FilterOutputStream(raw){
                    override fun write(b:Int){check(active.isActive()){ "保存已取消" };out.write(b)}
                    override fun write(b:ByteArray,off:Int,len:Int){check(active.isActive()){ "保存已取消" };out.write(b,off,len)}
                }
                val current=currentCoroutineContext()
                pending.archive?.let{BackupArchive.write(it.root,it.files,checked){current.ensureActive();check(active.isActive()){ "保存已取消" }}}?:checked.write(requireNotNull(pending.bytes))
            }}};status="已保存。备份和详细日志可能含私密资料，请妥善保管。"}finally{pending.clear()}
        }
    }
    fun export(id:String?,keys:Boolean,password:CharArray?=null){work{active->val snapshot=try{vm.exportBackup(id,keys,password)}finally{password?.fill('\u0000')}
        if(!active.isActive()){snapshot.close();return@work};output=PendingBackup(archive=snapshot)
        save.launch(if(id!=null)"恋叙角色完整备份.lianxu.zip"else if(keys)"恋叙全部备份-含密钥请勿分享.lianxu.zip"else"恋叙全部备份.lianxu.zip")
    }}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)work{active->
        incoming?.close();incoming=null;importTrace=Diagnostics.traceId();clearPreview();mode="";target=""
        var loaded:BackupArchive.Package?=null
        try{withContext(Dispatchers.IO){val current=currentCoroutineContext();loaded=LocalStep.ARCHIVE_READ.observe { requireNotNull(context.contentResolver.openInputStream(uri)).use{BackupArchive.read(it,context.cacheDir){current.ensureActive();check(active.isActive()){ "读取已取消" }}} }};incoming=loaded;loaded=null}finally{loaded?.close()}
        chooseMode=true;status="第 1 步：文件已读取，尚未导入。建议选择新增，保留现有角色。"
    }}
    fun exportDiagnostics(session: Any?) {
        if (busy || output != null || session != null && session !== vm.detailedExportSession) return
        val detailed = session != null
        work { active ->
            val bytes = Diagnostics.export(detailed)
            if (!active.isActive() || detailed && session !== vm.detailedExportSession) {
                bytes.fill(0)
                return@work
            }
            output = PendingBackup(bytes=bytes)
            try {
                save.launch("恋叙诊断-${if (detailed) "详细" else "简版"}.jsonl")
            } catch (failure: Exception) {
                output?.clear()
                output = null
                throw failure
            }
        }
    }
    fun plan(){val pack=incoming?:return;work(importTrace ?: Diagnostics.traceId()){clearPreview();if(pack.legacy!=null)legacyPreview=vm.roleArchive.preview(pack.legacy,if(mode=="REPLACE")target else null)else preview=vm.backup.preview(pack,mode,if(mode=="REPLACE"&&!pack.all)target else null)}}
    fun apply(confirmed:Boolean,password:CharArray?=null){val p=preview;val old=legacyPreview;work(importTrace ?: Diagnostics.traceId()){active->
        if(p!=null)try{vm.importBackup(p,confirmed,active,password)}finally{password?.fill('\u0000')}else if(old!=null)vm.importRoleArchive(old,confirmed,active)else error("预览已过期")
        incoming?.close();incoming=null;clearPreview();mode="";status=if(confirmed)"已按预览范围恢复，未自动请求模型。"else"已新增包内角色，当前角色和模型设置保留。"
    }}
    Column(Modifier.fillMaxSize()){
        AppTopBar("备份与诊断",{if(!busy)vm.back()},enabled=!busy,extra={IconButton({help=true},enabled=!busy,modifier=Modifier.size(UiSpace.action).testTag("backup-page-help").semantics{contentDescription=if(section==0)"备份与恢复说明"else"诊断日志说明"}){ControlIcon("info")}})
        BackupTabs(section,!busy,tabs::select)
        if(busy||error.isNotBlank()||status.isNotBlank())Column(Modifier.fillMaxWidth().padding(horizontal=UiSpace.page),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(busy){LinearProgressIndicator(Modifier.fillMaxWidth().testTag("backup-progress"));TextButton({lease?.cancel();job?.cancel()},modifier=Modifier.heightIn(min=UiSpace.action).testTag("backup-cancel")){Text("取消本次处理")}}
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("archive-error"))
            if(status.isNotBlank())Text(status,modifier=Modifier.testTag("archive-status"))
        }
        PageTabContent(tabs,enabled=!busy){page->
            Column(Modifier.fillMaxSize().verticalScroll(if(page==0)backupScroll else diagnosticScroll).padding(UiSpace.page).testTag("role-archive-page"),verticalArrangement=Arrangement.spacedBy(16.dp)){
                if(page==0){
                BackupActionRow("person","备份单个角色","导出指定角色的数据","backup-role-entry",!busy){chooseRole=true}
                BackupActionRow("database","备份全部数据","导出全部角色、资料和软件设置","backup-export-all",!busy){chooseKeys=true}
                BackupActionRow("import-file","从文件导入","从备份文件恢复数据","archive-open",!busy){open.launch(arrayOf("application/zip","application/octet-stream"))}
                Text("备份可能包含私密资料，请妥善保管。",style=MaterialTheme.typography.bodySmall,color=Muted)
                incoming?.let{pack->
                    Text(if(pack.legacy!=null)"旧格式：${pack.legacy.name}。${pack.legacy.summary}"else if(pack.all)"全部备份：${pack.roles.size()} 个角色；${if(pack.includesKeys&&BackupKeySeal.sealed(pack.root))"包含密钥（已用备份密码加密）"else if(pack.includesKeys)"包含未加密的密钥，请勿分享"else"不包含密钥"}"else"单角色完整备份")
                    Text("导入不自动调用模型，不恢复在途任务。旧格式缺少的资料无法凭空补齐。",style=MaterialTheme.typography.bodySmall,color=Muted)
                    TextButton({chooseMode=true},enabled=!busy,modifier=Modifier.testTag("backup-choose-mode")){Text(if(mode.isBlank())"选择新增或覆盖"else if(mode=="ADD")"导入方式：新增"else"导入方式：覆盖")}
                    if(mode=="REPLACE"&&!pack.all)vm.friends.forEach{f->TextButton({target=f.id;clearPreview()},enabled=!busy,modifier=Modifier.testTag("archive-target-${f.id}")){Text((if(target==f.id)"● "else"○ ")+"覆盖：${f.name}")}}
                    if(mode.isNotBlank())Button({plan()},enabled=!busy&&(mode=="ADD"||pack.all||target.isNotBlank()),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("archive-preview")){Text("下一步：查看导入范围")}
                    if(impact!=null){Text("第 2 步：核对以下范围，确认后才会导入。",style=MaterialTheme.typography.titleSmall);Text(impact,modifier=Modifier.testTag("archive-impact"));Button({if(mode=="REPLACE")confirm=true else apply(false)},enabled=!busy,modifier=Modifier.testTag("archive-import")){Text(if(mode=="REPLACE")"继续确认覆盖"else"确认新增角色")}}
                }
                }else{
                BackupActionRow("document","导出诊断日志","生成并导出诊断日志文件","backup-export-diagnostics",!busy && output == null){
                    val session = vm.detailedExportSession
                    if (session == null) exportDiagnostics(null) else exportConfirmation = session
                }
                BackupDiagnosticToggle(vm.diagnosticDetailed,!busy){vm.setDiagnosticDetailed(it)}
                Text("应用运行时会自动在本机记录详细日志。遇到问题后可直接导出，不会自动上传。",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
        }}
    }
    exportConfirmation?.let { session ->
        DiagnosticExportDialog(
            dismiss = { exportConfirmation = null },
            confirm = {
                if (exportConfirmation === session) {
                    exportConfirmation = null
                    exportDiagnostics(session)
                }
            }
        )
    }
    if(help)BackupHelpDialog(section==1){help=false}
    if(chooseRole)BackupRoleDialog(vm.friends,selected,{selected=it},{chooseRole=false}){chooseRole=false;export(selected,false)}
    if(chooseKeys)AlertDialog(onDismissRequest={chooseKeys=false},title={Text("是否包含 API 密钥？")},text={Text("不包含密钥也会备份服务和模型设置，不用设密码。包含密钥时要设一个备份密码，密钥会用它加密；在其他手机覆盖导入时输入同一密码，就能直接使用你的 API 密钥。密码忘了就取不回密钥，只能重新填。返回可取消。")},confirmButton={TextButton({chooseKeys=false;export(null,false)},modifier=Modifier.testTag("backup-without-keys")){Text("不包含密钥（推荐）")}},dismissButton={TextButton({chooseKeys=false;exportPassword=true},modifier=Modifier.testTag("backup-with-keys")){Text("包含密钥（设密码）")}})
    if(exportPassword)BackupPasswordDialog("设置备份密码","密钥会用这个密码加密。导入时要输入同一个密码，忘了就取不回密钥。",twice=true,dismiss={exportPassword=false}){pw->exportPassword=false;export(null,true,pw)}
    if(importPassword)BackupPasswordDialog("输入备份密码","这份备份里的密钥加了密码，请输入导出时设的密码。",twice=false,dismiss={importPassword=false}){pw->importPassword=false;apply(true,pw)}
    if(chooseMode)AlertDialog(onDismissRequest={chooseMode=false},title={Text("第 1 步：选择导入方式")},text={Text("新增只添加包内角色及其资料，保留当前角色和模型设置，不启用包内凭据。覆盖单角色需明确选择目标；覆盖全部备份会替换整个软件的数据和设置，下一步先预览范围。")},confirmButton={TextButton({chooseMode=false;mode="ADD";target="";clearPreview()},modifier=Modifier.testTag("archive-mode-add")){Text("新增（推荐）")}},dismissButton={TextButton({chooseMode=false;mode="REPLACE";target="";clearPreview()},modifier=Modifier.testTag("archive-mode-replace")){Text("覆盖")}})
    if(confirm&&impact!=null)AlertDialog(onDismissRequest={confirm=false},title={Text("确认按预览范围覆盖？")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(impact);Text("请先保存可用备份。取消不会改动资料；失败时事务回滚，不向任何服务自动发送。")}},confirmButton={TextButton({confirm=false;val p=preview;if(p!=null&&p.pack.all&&BackupKeySeal.sealed(p.pack.root))importPassword=true else apply(true)},modifier=Modifier.testTag("archive-confirm-overwrite")){Text("确认覆盖")}},dismissButton={TextButton({confirm=false}){Text("取消")}})
}

@Composable private fun BackupPasswordDialog(title:String,note:String,twice:Boolean,dismiss:()->Unit,done:(CharArray)->Unit){
    var first by remember{mutableStateOf("")};var second by remember{mutableStateOf("")};var problem by remember{mutableStateOf("")}
    val hidden=PasswordVisualTransformation();val keyboard=KeyboardOptions(keyboardType=KeyboardType.Password)
    AlertDialog(onDismissRequest={first="";second="";dismiss()},title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(note,style=MaterialTheme.typography.bodySmall,color=Muted)
        OutlinedTextField(first,{first=it;problem=""},label={Text("备份密码")},singleLine=true,visualTransformation=hidden,keyboardOptions=keyboard,modifier=Modifier.fillMaxWidth().testTag("backup-password"))
        if(twice)OutlinedTextField(second,{second=it;problem=""},label={Text("再输一次")},singleLine=true,visualTransformation=hidden,keyboardOptions=keyboard,modifier=Modifier.fillMaxWidth().testTag("backup-password-again"))
        if(problem.isNotEmpty())Text(problem,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton({
        val a=first.toCharArray();val b=(if(twice)second else first).toCharArray()
        val issue=if(twice)BackupKeySeal.passwordProblem(a,b) else if(a.isEmpty())"请输入备份密码" else null
        b.fill('\u0000')
        if(issue!=null){a.fill('\u0000');problem=issue}else{first="";second="";done(a)}
    },modifier=Modifier.testTag("backup-password-ok")){Text("确定",color=GreenText)}},dismissButton={TextButton({first="";second="";dismiss()}){Text("取消")}})
}
