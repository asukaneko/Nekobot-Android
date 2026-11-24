package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DiagnosticExportDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    val ink = Color(0xFF514235)
    val red = Color(0xFFA83D38)
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.fillMaxWidth(.88f).widthIn(max=560.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false),
        containerColor = Color(0xFFFFFCFA),
        titleContentColor = ink,
        textContentColor = ink,
        title = { Text("导出详细诊断", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("日志可能包含以下隐私信息：")
                Text("聊天内容、模型请求与回复、\n设备信息及运行记录。")
                Text("密钥和登录凭据不会写入日志。", color = red)
                Text("分享给他人前，请先检查导出内容。日志仅保存在本机，不会自动上传或发送。")
            }
        },
        dismissButton = { TextButton(dismiss) { Text("取消", color = ink) } },
        confirmButton = {
            Button(confirm, modifier = Modifier.testTag("confirm-detailed-export"),
                colors = ButtonDefaults.buttonColors(containerColor = red, contentColor = Color.White)) {
                Text("继续导出", fontWeight = FontWeight.Bold)
            }
        }
    )
}
