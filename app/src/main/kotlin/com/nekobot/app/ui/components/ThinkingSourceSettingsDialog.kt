package com.nekobot.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.ThinkingSourceConfig
import com.nekobot.app.data.local.ai.parseThinkingSourceFields

/**
 * 「思考内容来源」编辑弹窗（单会话角色思考设置内入口）。
 *
 * 读写全局偏好：思考字段候选 + 正文兜底标记；保存后下一次请求生效。
 */
@Composable
internal fun ThinkingSourceSettingsDialog(onDismiss: () -> Unit) {
    val textResources = LocalResources.current
    val saved = remember { ServiceContainer.prefs.thinkingSourceConfig() }
    var fieldsInput by remember { mutableStateOf(saved.fields.joinToString(", ")) }
    var markerStartInput by remember { mutableStateOf(saved.markerStart) }
    var markerEndInput by remember { mutableStateOf(saved.markerEnd) }

    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(textResources.getString(R.string.settings_thinking_source_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    textResources.getString(R.string.thinking_source_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = fieldsInput,
                    onValueChange = { fieldsInput = it },
                    label = { Text(textResources.getString(R.string.thinking_source_fields_label)) },
                    supportingText = { Text(textResources.getString(R.string.thinking_source_fields_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("thinking-source-fields")
                )
                OutlinedTextField(
                    value = markerStartInput,
                    onValueChange = { markerStartInput = it },
                    label = { Text(textResources.getString(R.string.thinking_source_marker_start_label)) },
                    supportingText = { Text(textResources.getString(R.string.thinking_source_marker_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("thinking-source-marker-start")
                )
                OutlinedTextField(
                    value = markerEndInput,
                    onValueChange = { markerEndInput = it },
                    label = { Text(textResources.getString(R.string.thinking_source_marker_end_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("thinking-source-marker-end")
                )
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    ServiceContainer.prefs.resetThinkingSourceConfig()
                    val defaults = ThinkingSourceConfig.DEFAULT
                    fieldsInput = defaults.fields.joinToString(", ")
                    markerStartInput = defaults.markerStart
                    markerEndInput = defaults.markerEnd
                }) { Text(textResources.getString(R.string.thinking_source_reset)) }
                TextButton(onClick = {
                    ServiceContainer.prefs.saveThinkingSourceConfig(
                        ThinkingSourceConfig(
                            fields = parseThinkingSourceFields(fieldsInput),
                            markerStart = markerStartInput.trim(),
                            markerEnd = markerEndInput.trim()
                        )
                    )
                    onDismiss()
                }) { Text(textResources.getString(R.string.common_save)) }
            }
        }
    )
}
