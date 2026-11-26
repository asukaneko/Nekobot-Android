package app.lianxu.independent

import androidx.compose.foundation.*
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 连接失败分两类：密钥不通过说密钥，服务器连不上说网络。
 * 底层 refresh 只抛一句笼统的 IO 异常，所以失败后再探一次服务端是否可达，
 * 可达就归为密钥问题，可达不了才是网络问题。
 */
internal enum class VoiceConnectError(val text:String) {
    KEY("密钥无效，检查后再试"),
    NETWORK("连不上服务器，检查网络后再试")
}

private suspend fun breezeServerReachable():Boolean = withContext(Dispatchers.IO){
    runCatching {
        val c=(URL("https://api.breeze.blue").openConnection() as HttpURLConnection)
        c.connectTimeout=4000;c.readTimeout=4000;c.requestMethod="GET"
        try { c.responseCode; true } finally { c.disconnect() }
    }.getOrDefault(false)
}

/** 供测试替换：先判断服务器是否可达，再决定报密钥错还是网络错。 */
internal object VoiceConnectProbe {
    @Volatile var reachable:suspend ()->Boolean = { breezeServerReachable() }
}

/**
 * 语音服务连接面板：未连接时从列表点「连接」进入，已连接时点「管理」进入同一个面板。
 * 验证中禁用输入与按钮；失败保留已输入的密钥；换密钥失败不影响原来那一条连接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BreezeVoiceConnectSheet(
    account:BreezeVoiceAccount,
    connected:Boolean,
    onDismiss:()->Unit
) {
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val uri = LocalUriHandler.current
    val focus=LocalFocusManager.current
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<VoiceConnectError?>(null) }
    var notice by remember { mutableStateOf("") }
    var confirmingDrop by remember { mutableStateOf(false) }

    fun submit(){
        val candidate=key.trim()
        if(candidate.isEmpty()){error=VoiceConnectError.KEY;return}
        focus.clearFocus();busy=true;error=null;notice=""
        scope.launch {
            try {
                account.refresh(candidate)
                key=""
                notice="已连接"
            } catch(e:CancellationException) {
                throw e
            } catch(_:IllegalArgumentException) {
                error=VoiceConnectError.KEY
                if(connected) notice="新密钥没通过，原来的还在用"
            } catch(_:Exception) {
                error=if(VoiceConnectProbe.reachable()) VoiceConnectError.KEY else VoiceConnectError.NETWORK
                if(connected) notice="新密钥没通过，原来的还在用"
            } finally {
                busy=false
            }
        }
    }

    fun disconnect(){
        val owner=account.accountId()
        runCatching {
            if(owner.isNotEmpty()) ModelVault(context.applicationContext,"breezeblue-speech").delete("tts-$owner")
        }
        context.applicationContext
            .getSharedPreferences("breezeblue-roles",Context.MODE_PRIVATE)
            .edit().remove("account").remove("voices").remove("catalog").putBoolean("ready",false).apply()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest={ if(!busy) onDismiss() },
        sheetState=sheet,
        containerColor=Paper,
        modifier=Modifier.testTag("voice-connect-sheet")
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal=UiSpace.page).padding(bottom=28.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if(connected) "BreezeBlue 语音" else "连接 BreezeBlue",
                style=MaterialTheme.typography.titleLarge,color=Ink
            )
            Text("用 BreezeBlue 语音，让角色给你发自然语音",style=MaterialTheme.typography.bodySmall,color=Muted)

            if(connected) {
                Text("当前已连接，换一个密钥后原来的会停用。",style=MaterialTheme.typography.bodySmall,color=Muted)
            }

            if (!connected) {
                val shape = RoundedCornerShape(14.dp)
                Column(Modifier.fillMaxWidth().background(Chrome, shape).border(.5.dp, Line, shape)
                    .testTag("voice-connect-guide").padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("怎么获取密钥（3 步）", style = MaterialTheme.typography.titleSmall)
                    listOf("点下方前往 BreezeBlue 官网注册或登录账号", "在开发者后台新建一条 API Key 并复制",
                        "粘贴回下方输入框，点「验证并连接」即可").forEachIndexed { index, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((index + 1).toString(), Modifier.background(Accent, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp),
                                style = MaterialTheme.typography.labelSmall)
                            Text(step, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider(color = Line)
                    VoiceHelpLinks(true, !busy, uri::openUri)
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("API Key",style=MaterialTheme.typography.labelLarge,color=Ink)
                OutlinedTextField(
                    value=key,
                    onValueChange={ key=it.take(8192);error=null;notice="" },
                    singleLine=true,
                    enabled=!busy,
                    placeholder={ Text(if(connected) "留空则保持原来的密钥" else "粘贴从 BreezeBlue 复制的密钥") },
                    visualTransformation=if(reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),
                    keyboardActions=KeyboardActions(onDone={ focus.clearFocus() }),
                    trailingIcon={
                        IconButton(
                            onClick={ reveal=!reveal },
                            modifier=Modifier.testTag("voice-key-reveal")
                                .semantics{ contentDescription=if(reveal) "隐藏密钥" else "显示密钥" }
                        ) { ControlIcon(if(reveal) "eye" else "eye-off") }
                    },
                    shape=RoundedCornerShape(14.dp),
                    colors=OutlinedTextFieldDefaults.colors(
                        focusedContainerColor=Paper,unfocusedContainerColor=Paper,
                        focusedBorderColor=GreenText,unfocusedBorderColor=Line
                    ),
                    modifier=Modifier.fillMaxWidth().testTag("voice-key-input")
                )
                error?.let {
                    Text(
                        it.text,color=MaterialTheme.colorScheme.error,
                        style=MaterialTheme.typography.bodySmall,
                        modifier=Modifier.padding(start=4.dp).testTag("voice-key-error")
                    )
                }
            }

            Text("密钥只存在本机，不会发给别人",style=MaterialTheme.typography.bodySmall,color=Muted)

            if (connected) VoiceHelpLinks(false, !busy, uri::openUri)

            if(notice.isNotEmpty() && error==null) {
                Text(
                    notice,style=MaterialTheme.typography.bodySmall,color=GreenText,
                    modifier=Modifier.testTag("voice-connect-notice")
                )
            }

            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick=onDismiss,
                    enabled=!busy,
                    modifier=Modifier.weight(1f).heightIn(min=UiSpace.action).testTag("voice-cancel")
                ) { Text("取消") }
                Button(
                    onClick={ submit() },
                    enabled=!busy && key.isNotBlank(),
                    colors=freshButtonColors(),
                    modifier=Modifier.weight(1f).heightIn(min=UiSpace.action).testTag("voice-verify")
                ) {
                    Text(if(busy) "正在验证…" else "验证并连接")
                }
            }

            if(connected) {
                if(confirmingDrop) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("断开后角色的语音会停用，确定？",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=Muted)
                        TextButton(
                            onClick={ disconnect() },
                            modifier=Modifier.testTag("voice-disconnect-confirm")
                        ) { Text("断开",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                        TextButton(
                            onClick={ confirmingDrop=false },
                            modifier=Modifier.testTag("voice-disconnect-cancel")
                        ) { Text("算了",style=MaterialTheme.typography.bodySmall) }
                    }
                } else {
                    TextButton(
                        onClick={ confirmingDrop=true },
                        enabled=!busy,
                        modifier=Modifier.testTag("voice-disconnect")
                    ) { Text("断开连接",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable private fun VoiceHelpLinks(guide: Boolean, enabled: Boolean, openUri: (String) -> Unit) {
    val padding = if (guide) ButtonDefaults.TextButtonContentPadding else PaddingValues(horizontal = 0.dp, vertical = 4.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (guide) 0.dp else 16.dp)) {
        TextButton({ openUri("https://breezeblue.ai/app/developer/api-keys") },
            enabled = enabled, modifier = Modifier.testTag("voice-key-help"), contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(containerColor = if (guide) Accent else androidx.compose.ui.graphics.Color.Transparent)) {
            Text("去获取 API Key ↗", color = if (guide) MaterialTheme.colorScheme.onSecondaryContainer else Ink,
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton({ openUri("https://breezeblue.ai/zh") }, enabled = enabled, modifier = Modifier.testTag("voice-site-help"),
            contentPadding = padding) {
            Text("访问中文官网 ↗", color = GreenText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
