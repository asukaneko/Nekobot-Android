package com.nekobot.app.ui.screens.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekobot.app.R
import com.nekobot.app.data.model.KnowledgeSearchResult
import com.nekobot.app.ui.components.GlassCard

/** 匹配文本中的 [1]、[2] 等引用标注 */
private val CITATION_REGEX = Regex("\\[(\\d+)]")

/** AnnotatedString 中引用标注的标签 */
private const val CITATION_TAG = "citation"

/** 引用内容预览的最大字符数 */
private const val CITATION_PREVIEW_MAX = 500

/**
 * 引用角标组件：显示引用编号的小圆角方形角标，
 * 点击弹出 BottomSheet 展示来源详情（GlassCard 风格）。
 *
 * @param index   引用编号（从 1 开始）
 * @param citations 全部引用结果列表
 * @param modifier 修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitationChip(
    index: Int,
    citations: List<KnowledgeSearchResult>,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }

    // 优先按 citationIndex 匹配，回退到列表序号（index-1）
    val citation = remember(index, citations) {
        citations.firstOrNull { it.citationIndex == index }
            ?: citations.getOrNull(index - 1)
    }

    Box(
        modifier = modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            .clickable { showSheet = true },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$index",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
    }

    if (showSheet && citation != null) {
        CitationDetailSheet(
            citation = citation,
            index = index,
            onDismiss = { showSheet = false }
        )
    }
}

/**
 * 引用文本组件：解析文本中的 [1][2] 引用标注，
 * 将其替换为可点击的角标样式片段，使用 AnnotatedString 实现混合渲染。
 * 点击角标弹出来源详情 BottomSheet。
 *
 * @param text      含引用标注的文本
 * @param citations 引用结果列表
 * @param modifier  修饰符
 * @param style     基础文本样式
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitationText(
    text: String,
    citations: List<KnowledgeSearchResult>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    if (text.isBlank()) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    var selectedCitation by remember { mutableStateOf<Int?>(null) }

    // 构建 AnnotatedString：将 [N] 标注渲染为带背景色的小角标片段
    val annotatedString = remember(text, primaryColor) {
        buildCitationAnnotatedString(text, primaryColor, style)
    }

    ClickableText(
        text = annotatedString,
        style = style.copy(color = onSurfaceColor),
        modifier = modifier,
        onClick = { offset ->
            // 根据点击位置查找引用标注注解
            annotatedString
                .getStringAnnotations(tag = CITATION_TAG, start = offset, end = offset)
                .firstOrNull()
                ?.item
                ?.toIntOrNull()
                ?.let { selectedCitation = it }
        }
    )

    // 点击角标后弹出来源详情
    selectedCitation?.let { idx ->
        val citation = citations.firstOrNull { it.citationIndex == idx }
            ?: citations.getOrNull(idx - 1)
        if (citation != null) {
            CitationDetailSheet(
                citation = citation,
                index = idx,
                onDismiss = { selectedCitation = null }
            )
        } else {
            // 没有对应的引用数据，直接清除选中状态
            selectedCitation = null
        }
    }
}

/**
 * 构建 AnnotatedString：普通文本 + 引用角标片段（带背景色、上标、可点击注解）。
 */
private fun buildCitationAnnotatedString(
    text: String,
    primaryColor: Color,
    baseStyle: TextStyle
): AnnotatedString {
    return buildAnnotatedString {
        var lastEnd = 0
        for (match in CITATION_REGEX.findAll(text)) {
            // 追加标注前的普通文本
            if (match.range.first > lastEnd) {
                append(text.substring(lastEnd, match.range.first))
            }
            val index = match.groupValues[1].toIntOrNull() ?: 0
            // 标记可点击的引用注解
            pushStringAnnotation(tag = CITATION_TAG, annotation = index.toString())
            withStyle(
                SpanStyle(
                    background = primaryColor.copy(alpha = 0.2f),
                    color = primaryColor,
                    fontSize = (baseStyle.fontSize.value * 0.75f).sp,
                    fontWeight = FontWeight.Bold,
                    baselineShift = BaselineShift.Superscript
                )
            ) {
                append("[${index}]")
            }
            pop()
            lastEnd = match.range.last + 1
        }
        // 追加最后一段普通文本
        if (lastEnd < text.length) {
            append(text.substring(lastEnd))
        }
    }
}

/**
 * 引用来源详情 BottomSheet，使用 GlassCard 风格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitationDetailSheet(
    citation: KnowledgeSearchResult,
    index: Int,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题行：引用编号 + 文档标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$index",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = citation.title ?: stringResource(R.string.citation_unnamed_document),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 来源（若是 URL 则可点击打开）
                    SourceRow(source = citation.source, context = context)

                    // 段落位置
                    DetailRow(
                        label = stringResource(R.string.citation_chunk_position),
                        value = citation.chunkIndex?.let { stringResource(R.string.citation_chunk_number, it + 1) } ?: "—"
                    )

                    // 相关性得分
                    DetailRow(
                        label = stringResource(R.string.citation_relevance_score),
                        value = citation.score?.let { "%.4f".format(it) } ?: "—"
                    )

                    // 语义 / 词法 / 重排得分明细
                    citation.semanticScore?.let {
                        DetailRow(label = stringResource(R.string.citation_semantic_score), value = "%.4f".format(it))
                    }
                    citation.lexicalScore?.let {
                        DetailRow(label = stringResource(R.string.citation_lexical_score), value = "%.4f".format(it))
                    }
                    citation.rerankScore?.let {
                        DetailRow(label = stringResource(R.string.citation_rerank_score), value = "%.4f".format(it))
                    }
                }
            }

            // 内容预览（最多 500 字）
            citation.content?.let { content ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = stringResource(R.string.citation_content_preview),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = content.take(CITATION_PREVIEW_MAX) +
                            if (content.length > CITATION_PREVIEW_MAX) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** 详情行：标签 + 值 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 来源行：当 source 是 URL 时显示为可点击链接（点击打开浏览器），否则显示为普通文本。
 */
@Composable
private fun SourceRow(source: String?, context: android.content.Context) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.citation_source),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        val displayValue = source ?: "—"
        val isUrl = source != null && (source.startsWith("http://") || source.startsWith("https://"))
        Text(
            text = displayValue,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isUrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = if (isUrl) {
                Modifier.clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source)))
                    }
                }
            } else {
                Modifier
            }
        )
    }
}
