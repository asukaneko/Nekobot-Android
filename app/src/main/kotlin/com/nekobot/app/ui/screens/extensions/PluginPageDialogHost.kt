package com.nekobot.app.ui.screens.extensions

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.plugin.PluginPageDialog
import com.nekobot.app.data.local.plugin.PluginPageDialogResult
import com.nekobot.app.ui.components.NekoDialog

/**
 * 插件页面原生弹窗：渲染 alert / confirm / prompt / select 四种形态。
 *
 * 结果通过 [onResult] 回填宿主（关闭或取消都按未确认处理）。
 * window.alert/confirm/prompt 与 host.ui.alert/confirm/prompt/select 共用这里。
 */
@Composable
internal fun PluginPageDialogRenderer(
    request: PluginPageDialog,
    onResult: (PluginPageDialogResult) -> Unit
) {
    when (request.kind) {
        PluginPageDialog.Kind.ALERT -> NekoDialog(
            title = request.title,
            message = request.message,
            confirmText = stringResource(R.string.common_ok),
            onConfirm = { onResult(PluginPageDialogResult(confirmed = true)) },
            cancelText = null,
            onCancel = null,
            onDismiss = { onResult(PluginPageDialogResult.CANCELLED) }
        )

        PluginPageDialog.Kind.CONFIRM -> NekoDialog(
            title = request.title,
            message = request.message,
            onConfirm = { onResult(PluginPageDialogResult(confirmed = true)) },
            onCancel = { onResult(PluginPageDialogResult.CANCELLED) },
            onDismiss = { onResult(PluginPageDialogResult.CANCELLED) }
        )

        PluginPageDialog.Kind.PROMPT -> {
            var text by remember(request.id) { mutableStateOf(request.defaultValue) }
            NekoDialog(
                title = request.title,
                message = request.message,
                onConfirm = { onResult(PluginPageDialogResult(confirmed = true, text = text)) },
                onCancel = { onResult(PluginPageDialogResult.CANCELLED) },
                onDismiss = { onResult(PluginPageDialogResult.CANCELLED) },
                content = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            )
        }

        PluginPageDialog.Kind.SELECT -> {
            var selected by remember(request.id) { mutableIntStateOf(request.selectedIndex) }
            NekoDialog(
                title = request.title,
                message = request.message.ifBlank { null },
                confirmEnabled = selected in request.options.indices,
                onConfirm = {
                    onResult(PluginPageDialogResult(confirmed = true, selectedIndex = selected))
                },
                onCancel = { onResult(PluginPageDialogResult.CANCELLED) },
                onDismiss = { onResult(PluginPageDialogResult.CANCELLED) },
                contentScrollable = true,
                content = {
                    request.options.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == index,
                                    role = Role.RadioButton,
                                    onClick = { selected = index }
                                )
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == index, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        }
    }
}
