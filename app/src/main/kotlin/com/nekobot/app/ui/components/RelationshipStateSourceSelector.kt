package com.nekobot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.model.RELATIONSHIP_STATE_SOURCE_INHERIT
import com.nekobot.app.data.model.RELATIONSHIP_STATE_SOURCE_INITIAL

/** 新建本地角色会话时选择六维关系状态的来源。 */
@Composable
fun RelationshipStateSourceSelector(
    selectedSource: String,
    onSourceSelected: (String) -> Unit,
    initialState: JsonObject?,
    modifier: Modifier = Modifier
) {
    val useInitial = selectedSource == RELATIONSHIP_STATE_SOURCE_INITIAL
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.sessions_relationship_state_source),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RelationshipSourceChip(
                text = stringResource(R.string.sessions_relationship_state_initial),
                selected = useInitial,
                onClick = { onSourceSelected(RELATIONSHIP_STATE_SOURCE_INITIAL) },
                modifier = Modifier.weight(1f)
            )
            RelationshipSourceChip(
                text = stringResource(R.string.sessions_relationship_state_inherit),
                selected = !useInitial,
                onClick = { onSourceSelected(RELATIONSHIP_STATE_SOURCE_INHERIT) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(
                if (useInitial) R.string.sessions_relationship_state_initial_desc
                else R.string.sessions_relationship_state_inherit_desc
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (useInitial) {
            Text(
                text = stringResource(
                    R.string.sessions_relationship_state_initial_values,
                    relationshipValue(initialState, "affection", 50),
                    relationshipValue(initialState, "trust", 50),
                    relationshipValue(initialState, "familiarity", 30),
                    relationshipValue(initialState, "dependency", 30),
                    relationshipValue(initialState, "security", 50),
                    relationshipValue(initialState, "jealousy", 0)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RelationshipSourceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun relationshipValue(state: JsonObject?, key: String, default: Int): Int {
    fun valueFrom(container: JsonObject?): Int? {
        val element = container?.get(key)?.takeIf { it.isJsonPrimitive } ?: return null
        return runCatching { element.asDouble.toInt().coerceIn(0, 100) }.getOrNull()
    }

    valueFrom(state)?.let { return it }
    listOf("relationship", "initial_relationship", "initialRelationship").forEach { containerKey ->
        val container = state?.get(containerKey)?.takeIf { it.isJsonObject }?.asJsonObject
        valueFrom(container)?.let { return it }
    }
    return default
}
