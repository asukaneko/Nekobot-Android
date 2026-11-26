package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DiagnosticExportDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.fillMaxWidth(.88f).widthIn(max=560.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false),
        containerColor = Paper,
        titleContentColor = Ink,
        textContentColor = Muted,
        title = { Text("导出详细诊断", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("详细日志含聊天原文、模型请求与回复，以及设备运行记录。分享前请先检查。")
                Text("密钥和登录凭据不会写入日志。", color = Ink)
            }
        },
        dismissButton = { TextButton(dismiss) { Text("取消", color = Ink) } },
        confirmButton = {
            Button(confirm, modifier = Modifier.testTag("confirm-detailed-export"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                Text("继续导出", fontWeight = FontWeight.Bold)
            }
        }
    )
}
