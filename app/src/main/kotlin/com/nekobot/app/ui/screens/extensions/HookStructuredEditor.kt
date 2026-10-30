package com.nekobot.app.ui.screens.extensions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassCard

/**
 * Hook 条件编辑区：每行「条件项 + 值」，无需手写 JSON。
 *
 * 条件为空表示每次都触发；未识别的原始条件键由 [HookConditionsDraft.extras] 保留。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HookConditionsEditor(rows: SnapshotStateList<HookConditionDraft>) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorSectionHeader(
            title = stringResource(R.string.hook_form_section_conditions),
            count = rows.size
        )
        if (rows.isEmpty()) {
            EditorHint(stringResource(R.string.hook_form_conditions_empty))
        }
        rows.forEachIndexed { index, row ->
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HookDropdown(
                            options = HookConditionCatalog.specs.map { spec ->
                                spec.key to conditionLabel(spec)
                            },
                            selectedKey = row.spec.key,
                            onSelect = { key ->
                                HookConditionCatalog.byKey(key)?.let { spec ->
                                    rows[index] = row.copy(spec = spec, value = normalizeValue(spec, row.value))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { rows.removeAt(index) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (row.spec.kind == HookValueKind.BOOLEAN) {
                        BooleanChoice(
                            value = row.value.toBoolean(),
                            onChange = { rows[index] = row.copy(value = it.toString()) }
                        )
                    } else {
                        EditorField(
                            label = null,
                            value = row.value,
                            onChange = { rows[index] = row.copy(value = it) },
                            placeholder = when (row.spec.kind) {
                                HookValueKind.NUMBER -> stringResource(R.string.hook_value_number)
                                HookValueKind.STRING_LIST -> stringResource(R.string.hook_value_list)
                                else -> stringResource(R.string.hook_value_text)
                            },
                            numeric = row.spec.kind == HookValueKind.NUMBER
                        )
                    }
                }
            }
        }
        TextButton(onClick = {
            rows.add(HookConditionDraft(HookConditionCatalog.specs.first(), ""))
        }) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.hook_form_add_condition), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Hook 动作编辑区：每条动作一张卡片，按类型展示对应参数。
 */
@Composable
internal fun HookActionsEditor(
    rows: SnapshotStateList<HookActionDraft>,
    allowBlock: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorSectionHeader(
            title = stringResource(R.string.hook_form_section_actions),
            count = rows.size
        )
        if (rows.isEmpty()) {
            EditorHint(stringResource(R.string.hook_form_actions_empty))
        }
        rows.forEachIndexed { index, row ->
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HookDropdown(
                            options = actionTypeOptions(allowBlock).let { options ->
                                // 既有 hook 可能带有当前事件不可用的动作类型，仍需能正常展示
                                if (options.any { it.first == row.type }) options
                                else options + (row.type to actionTypeLabel(row.type))
                            },
                            selectedKey = row.type,
                            onSelect = { type ->
                                // 切换类型时保留 extra（未知参数），其余参数回到该类型的默认值
                                rows[index] = defaultActionDraft(type).copy(extra = row.extra)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (index > 0) {
                            IconButton(onClick = { swapRow(rows, index, index - 1) }) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.common_move_up),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (index < rows.lastIndex) {
                            IconButton(onClick = { swapRow(rows, index, index + 1) }) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.common_move_down),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { rows.removeAt(index) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    ActionParams(
                        draft = row,
                        onChange = { rows[index] = it }
                    )
                }
            }
        }
        TextButton(onClick = { rows.add(defaultActionDraft(HookActionType.LOG.id)) }) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.hook_form_add_action), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ActionParams(draft: HookActionDraft, onChange: (HookActionDraft) -> Unit) {
    when (draft.type) {
        HookActionType.LOG.id -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorField(
                label = stringResource(R.string.hook_param_level),
                value = draft.level,
                onChange = { onChange(draft.copy(level = it)) },
                placeholder = "info"
            )
            EditorField(
                label = stringResource(R.string.hook_param_message),
                value = draft.message,
                onChange = { onChange(draft.copy(message = it)) }
            )
        }
        HookActionType.NOTIFY.id -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorField(
                label = stringResource(R.string.hook_param_title_optional),
                value = draft.title,
                onChange = { onChange(draft.copy(title = it)) }
            )
            EditorField(
                label = stringResource(R.string.hook_param_message),
                value = draft.message,
                onChange = { onChange(draft.copy(message = it)) }
            )
        }
        HookActionType.MEMORY_WRITE.id -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledDropdown(
                label = stringResource(R.string.hook_param_category),
                options = HookMemoryCategories.map { it to it },
                selectedKey = draft.category,
                onSelect = { onChange(draft.copy(category = it)) }
            )
            EditorField(
                label = stringResource(R.string.hook_param_title),
                value = draft.title,
                onChange = { onChange(draft.copy(title = it)) }
            )
            EditorField(
                label = stringResource(R.string.hook_param_content),
                value = draft.content,
                onChange = { onChange(draft.copy(content = it)) },
                singleLine = false
            )
            EditorField(
                label = stringResource(R.string.hook_param_importance),
                value = draft.importance,
                onChange = { onChange(draft.copy(importance = it)) },
                numeric = true,
                decimal = true
            )
        }
        HookActionType.PROMPT_INJECT.id -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorField(
                label = stringResource(R.string.hook_param_content),
                value = draft.content,
                onChange = { onChange(draft.copy(content = it)) },
                singleLine = false
            )
            EditorField(
                label = stringResource(R.string.hook_param_priority),
                value = draft.priority,
                onChange = { onChange(draft.copy(priority = it)) },
                numeric = true
            )
            LabeledDropdown(
                label = stringResource(R.string.hook_param_scope),
                options = listOf("turn", "session", "global").map { it to it },
                selectedKey = draft.scope,
                onSelect = { onChange(draft.copy(scope = it)) }
            )
        }
        HookActionType.STATE_DELTA.id -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorField(
                label = stringResource(R.string.hook_param_mood),
                value = draft.mood,
                onChange = { onChange(draft.copy(mood = it)) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EditorField(
                    label = stringResource(R.string.hook_param_delta_of, stringResource(R.string.hook_field_energy)),
                    value = draft.energyDelta,
                    onChange = { onChange(draft.copy(energyDelta = it)) },
                    numeric = true,
                    signed = true,
                    modifier = Modifier.weight(1f)
                )
                EditorField(
                    label = stringResource(R.string.hook_param_mood_intensity),
                    value = draft.moodIntensityDelta,
                    onChange = { onChange(draft.copy(moodIntensityDelta = it)) },
                    numeric = true,
                    decimal = true,
                    signed = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HookActionType.RELATIONSHIP_DELTA.id -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DeltaRow(Label(R.string.hook_field_affection), draft.affectionDelta) {
                onChange(draft.copy(affectionDelta = it))
            }
            DeltaRow(Label(R.string.hook_field_trust), draft.trustDelta) {
                onChange(draft.copy(trustDelta = it))
            }
            DeltaRow(Label(R.string.hook_field_familiarity), draft.familiarityDelta) {
                onChange(draft.copy(familiarityDelta = it))
            }
            DeltaRow(Label(R.string.hook_field_dependency), draft.dependencyDelta) {
                onChange(draft.copy(dependencyDelta = it))
            }
            DeltaRow(Label(R.string.hook_field_security), draft.securityDelta) {
                onChange(draft.copy(securityDelta = it))
            }
            DeltaRow(Label(R.string.hook_field_jealousy), draft.jealousyDelta) {
                onChange(draft.copy(jealousyDelta = it))
            }
        }
        HookActionType.MESSAGE.id -> EditorField(
            label = stringResource(R.string.hook_param_content),
            value = draft.content,
            onChange = { onChange(draft.copy(content = it)) },
            singleLine = false
        )
        HookActionType.WORLD_BOOK_ADD.id -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorField(
                label = stringResource(R.string.hook_param_entry_name),
                value = draft.name,
                onChange = { onChange(draft.copy(name = it)) }
            )
            EditorField(
                label = stringResource(R.string.hook_param_content),
                value = draft.content,
                onChange = { onChange(draft.copy(content = it)) },
                singleLine = false
            )
            EditorField(
                label = stringResource(R.string.hook_param_keywords),
                value = draft.keywords,
                onChange = { onChange(draft.copy(keywords = it)) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorHint(stringResource(R.string.hook_param_always_on))
                Spacer(Modifier.width(8.dp))
                BooleanChoice(value = draft.alwaysOn, onChange = { onChange(draft.copy(alwaysOn = it)) })
            }
        }
        HookActionType.TOOL_BLOCK.id -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorHint(stringResource(R.string.hook_action_tool_block_hint))
            EditorField(
                label = stringResource(R.string.hook_param_reason),
                value = draft.reason,
                onChange = { onChange(draft.copy(reason = it)) }
            )
        }
        else -> EditorHint(stringResource(R.string.hook_action_custom_hint, draft.type))
    }
}

/** 数据类包装，避免在 Composable 参数里直接传 @StringRes。 */
private data class Label(@StringRes val res: Int)

@Composable
private fun DeltaRow(label: Label, value: String, onChange: (String) -> Unit) {
    EditorField(
        label = stringResource(R.string.hook_param_delta_of, stringResource(label.res)),
        value = value,
        onChange = onChange,
        numeric = true,
        signed = true
    )
}

@Composable
private fun BooleanChoice(value: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = value,
            onClick = { onChange(true) },
            label = { Text(stringResource(R.string.hook_value_true)) }
        )
        FilterChip(
            selected = !value,
            onClick = { onChange(false) },
            label = { Text(stringResource(R.string.hook_value_false)) }
        )
    }
}

@Composable
internal fun EditorSectionHeader(title: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun EditorHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 带标签的输入框（[label] 为空表示不显示标签）。 */
@Composable
internal fun EditorField(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    numeric: Boolean = false,
    decimal: Boolean = false,
    signed: Boolean = false,
    singleLine: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
        }
        val placeholderContent: (@Composable () -> Unit)? = placeholder?.let { hint -> { Text(hint) } }
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                onChange(
                    if (numeric) filterNumber(input, decimal = decimal, signed = signed) else input
                )
            },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            maxLines = if (singleLine) 1 else 4,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = editorFieldColors(),
            placeholder = placeholderContent,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            }
        )
    }
}

/** 标签 + 下拉框。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabeledDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        HookDropdown(
            options = options,
            selectedKey = selectedKey,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 通用下拉框：[options] 为 (键, 显示名) 列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HookDropdown(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second ?: selectedKey
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        @Suppress("DEPRECATION")
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = editorFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
)

@Composable
internal fun conditionLabel(spec: HookConditionSpec): String = stringResource(
    R.string.hook_condition_label,
    stringResource(spec.field.labelRes),
    stringResource(spec.op.labelRes)
)

@Composable
internal fun actionTypeLabel(type: String): String =
    HookActionType.fromId(type)?.let { stringResource(it.labelRes) }
        ?: stringResource(R.string.hook_action_custom, type)

/** 动作类型下拉项；tool_block 只在可拦截事件下提供。 */
@Composable
private fun actionTypeOptions(allowBlock: Boolean): List<Pair<String, String>> =
    HookActionType.entries
        .filter { allowBlock || it != HookActionType.TOOL_BLOCK }
        .map { it.id to stringResource(it.labelRes) }

private fun normalizeValue(spec: HookConditionSpec, current: String): String = when (spec.kind) {
    HookValueKind.BOOLEAN -> current.ifBlank { "false" }
    HookValueKind.NUMBER -> if (current.toDoubleOrNull() != null) current else ""
    else -> current
}

private fun swapRow(rows: SnapshotStateList<HookActionDraft>, from: Int, to: Int) {
    val item = rows[from]
    rows[from] = rows[to]
    rows[to] = item
}

/** 数字输入过滤：保留数字、可选负号与小数点。 */
private fun filterNumber(input: String, decimal: Boolean, signed: Boolean): String {
    val builder = StringBuilder()
    for ((index, ch) in input.withIndex()) {
        when {
            ch.isDigit() -> builder.append(ch)
            decimal && ch == '.' && !builder.contains('.') -> builder.append(ch)
            signed && ch == '-' && index == 0 -> builder.append(ch)
        }
    }
    return builder.toString()
}
