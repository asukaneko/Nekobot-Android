package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.AskUserQuestionAnswer
import com.nekobot.app.data.local.ai.AskUserQuestionItem
import com.nekobot.app.data.local.ai.AskUserQuestionRequest
import com.nekobot.app.ui.components.NekoDialog

/**
 * ask_user_question 回答弹窗。
 *
 * 参考 Claude Code AskUserQuestion 的交互：每个问题带短标题、预设选项
 * （单选/多选）与自由输入；用户提交后答案作为工具结果回传给 AI。
 * 关闭/跳过时 AI 收到 cancelled 结果并自行继续。
 */
@Composable
fun AskUserQuestionDialog(
    request: AskUserQuestionRequest,
    onAnswer: (List<AskUserQuestionAnswer>) -> Unit,
    onSkip: () -> Unit
) {
    // 每个问题的勾选状态（问题序号 → 已选选项标签集合）与自由输入文本
    val selections = remember(request.requestId) { mutableStateMapOf<Int, Set<String>>() }
    val customTexts = remember(request.requestId) { mutableStateMapOf<Int, String>() }

    val allAnswered = request.questions.indices.all { index ->
        !(selections[index].isNullOrEmpty() && customTexts[index].orEmpty().isBlank())
    }

    NekoDialog(
        onDismiss = onSkip,
        title = stringResource(R.string.chat_ask_question_title),
        confirmText = stringResource(R.string.chat_ask_question_submit),
        confirmEnabled = allAnswered,
        onConfirm = {
            val answers = request.questions.mapIndexed { index, item ->
                AskUserQuestionAnswer(
                    id = item.id,
                    question = item.question,
                    selected = orderedSelection(item, selections[index].orEmpty()),
                    text = customTexts[index]?.trim().orEmpty()
                )
            }
            onAnswer(answers)
        },
        cancelText = stringResource(R.string.chat_ask_question_skip),
        onCancel = onSkip,
        contentScrollable = true
    ) {
        request.questions.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(16.dp))
            AskQuestionBlock(
                index = index,
                item = item,
                selected = selections[index].orEmpty(),
                customText = customTexts[index].orEmpty(),
                onToggleOption = { label ->
                    val current = selections[index].orEmpty()
                    selections[index] = if (item.multiSelect) {
                        if (label in current) current - label else current + label
                    } else {
                        if (label in current) emptySet() else setOf(label)
                    }
                    // 单选题选择选项后清空自由输入，避免答案重复
                    if (!item.multiSelect && selections[index]!!.isNotEmpty()) {
                        customTexts.remove(index)
                    }
                },
                onCustomTextChange = { text ->
                    if (text.isBlank()) {
                        customTexts.remove(index)
                    } else {
                        customTexts[index] = text
                        // 开始自由输入时清除单选勾选，保持答案语义清晰
                        if (!item.multiSelect && selections[index].orEmpty().isNotEmpty()) {
                            selections[index] = emptySet()
                        }
                    }
                }
            )
        }
    }
}

/** 按选项定义顺序输出勾选结果，保证与 AI 提供的选项一致。 */
private fun orderedSelection(item: AskUserQuestionItem, selected: Set<String>): List<String> {
    val ordered = item.options.map { it.label }.filter { it in selected }
    // 自由输入期间可能存在不在预设里的标签（防御），追加在末尾
    return ordered + selected.filterNot { it in item.options.map { option -> option.label } }
}

@Composable
private fun AskQuestionBlock(
    index: Int,
    item: AskUserQuestionItem,
    selected: Set<String>,
    customText: String,
    onToggleOption: (String) -> Unit,
    onCustomTextChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ) {
            Text(
                text = item.header.ifBlank {
                    stringResource(R.string.chat_ask_question_number, index + 1)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.question,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (item.multiSelect && item.options.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.chat_ask_question_multi_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.options.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            // 选项较多时独立滚动：标题与自由输入框固定，长选项列表在受限高度内滚动
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                item.options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleOption(option.label) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.multiSelect) {
                        Checkbox(
                            checked = option.label in selected,
                            onCheckedChange = { onToggleOption(option.label) }
                        )
                    } else {
                        RadioButton(
                            selected = option.label in selected,
                            onClick = { onToggleOption(option.label) }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (option.description.isNotBlank()) {
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }   // forEach
            }       // options Column
        }           // if (item.options.isNotEmpty())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customText,
            onValueChange = onCustomTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            placeholder = {
                Text(
                    text = if (item.options.isEmpty()) {
                        stringResource(R.string.chat_ask_question_custom_hint_open)
                    } else {
                        stringResource(R.string.chat_ask_question_custom_hint)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = false,
            maxLines = 4
        )
    }
}
}
