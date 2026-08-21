package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.repository.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ContextAnalysisData(
    val breakdown: ContextBreakdown,
    val usedTokens: Long,
    val maxTokens: Int?
)

private data class ContextAnalysisUiState(
    val loading: Boolean = true,
    val data: ContextAnalysisData? = null,
    val error: String? = null
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ContextAnalysisScreen(
    sessionId: String,
    onBack: () -> Unit
) {
    var state by remember(sessionId) { mutableStateOf(ContextAnalysisUiState()) }
    var refreshKey by remember(sessionId) { mutableStateOf(0) }

    suspend fun load() {
        state = ContextAnalysisUiState()
        val result = withContext(Dispatchers.IO) {
            val sessionResult = ServiceContainer.unified.getSession(sessionId)
            val messagesResult = ServiceContainer.unified.listMessages(sessionId)
            val error = when {
                sessionResult is Resource.Error -> sessionResult.message
                messagesResult is Resource.Error -> messagesResult.message
                else -> null
            }
            if (error != null) {
                Result.failure<ContextAnalysisData>(IllegalStateException(error))
            } else {
                val session = when (sessionResult) {
                    is Resource.Success -> sessionResult.data
                    else -> null
                }
                val messages = when (messagesResult) {
                    is Resource.Success -> messagesResult.data
                    else -> emptyList()
                }
                Result.success(
                    ContextAnalysisData(
                        breakdown = buildContextBreakdown(session, messages),
                        usedTokens = ServiceContainer.unified.sessionContextTokenUsage(sessionId),
                        maxTokens = ServiceContainer.unified.getActiveContextLength()
                    )
                )
            }
        }
        state = result.fold(
            onSuccess = { ContextAnalysisUiState(loading = false, data = it) },
            onFailure = { ContextAnalysisUiState(loading = false, error = it.message) }
        )
    }

    LaunchedEffect(sessionId, refreshKey) { load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.chat_context_analysis_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey += 1 }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.common_retry)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
            else -> {
                val data = requireNotNull(state.data)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item { ContextCapacityCard(data.usedTokens, data.maxTokens) }
                    item {
                        Column {
                            Text(
                                stringResource(R.string.chat_context_analysis_type_breakdown),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                stringResource(R.string.chat_context_analysis_basis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (data.breakdown.parts.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.chat_context_analysis_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(data.breakdown.parts, key = { it.type.name }) { part ->
                            ContextTypeRow(part, data.breakdown.estimatedTokens)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextCapacityCard(usedTokens: Long, maxTokens: Int?) {
    val progress = maxTokens
        ?.takeIf { it > 0 }
        ?.let { (usedTokens.toFloat() / it).coerceIn(0f, 1f) }
        ?: 0f
    val percent = (progress * 100).toInt()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    strokeWidth = 6.dp
                )
                Text(
                    if (maxTokens == null) "-" else "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.chat_context_analysis_current),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (maxTokens == null) {
                        stringResource(R.string.chat_context_analysis_tokens, usedTokens)
                    } else {
                        stringResource(R.string.chat_context_analysis_capacity, usedTokens, maxTokens)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ContextTypeRow(part: ContextPart, totalTokens: Int) {
    val color = when (part.type) {
        ContextPartType.SYSTEM_PROMPT -> MaterialTheme.colorScheme.primary
        ContextPartType.USER_MESSAGES -> MaterialTheme.colorScheme.secondary
        ContextPartType.ASSISTANT_MESSAGES -> MaterialTheme.colorScheme.tertiary
        ContextPartType.COMPRESSED_SUMMARY -> MaterialTheme.colorScheme.outline
        ContextPartType.TOOL_CALLS -> MaterialTheme.colorScheme.error
        ContextPartType.OTHER_MESSAGES -> MaterialTheme.colorScheme.outline
    }
    val share = if (totalTokens > 0) part.estimatedTokens.toFloat() / totalTokens else 0f
    val percent = (share * 100).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    contextPartLabel(part.type),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(
                        R.string.chat_context_analysis_part_detail,
                        part.estimatedTokens,
                        part.itemCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "$percent%",
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        LinearProgressIndicator(
            progress = { share },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun contextPartLabel(type: ContextPartType): String = when (type) {
    ContextPartType.SYSTEM_PROMPT -> stringResource(R.string.chat_context_analysis_system_prompt)
    ContextPartType.USER_MESSAGES -> stringResource(R.string.chat_context_analysis_user_messages)
    ContextPartType.ASSISTANT_MESSAGES -> stringResource(R.string.chat_context_analysis_assistant_messages)
    ContextPartType.COMPRESSED_SUMMARY -> stringResource(R.string.chat_context_analysis_summary)
    ContextPartType.TOOL_CALLS -> stringResource(R.string.chat_context_analysis_tool_content)
    ContextPartType.OTHER_MESSAGES -> stringResource(R.string.chat_context_analysis_other_messages)
}
