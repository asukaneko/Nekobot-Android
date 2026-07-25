package com.nekobot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekobot.app.R

/**
 * 自定义 Markdown 渲染器，参考原仓库 nbot-methods.js 的 renderMarkdown。
 *
 * 订制实现：
 * 1. 内心独白折叠块：`（内心：...）` 转为灰色可折叠块
 * 2. 全角括号内容斜体：`（...）` 转为斜体
 * 3. 禁用删除线：`~~text~~` 不渲染为删除线（与原仓库一致）
 * 4. 代码块带语言标签和复制按钮
 * 5. 表格可横向滚动
 * 6. 流式渲染友好：每次 recomposition 重新解析
 *
 * 上述 1/2/3 项预处理仅在 [chatMode] = true 时生效，仅用于聊天对话气泡；
 * 其他界面（角色卡、状态历史等）使用标准 Markdown 渲染。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    chatMode: Boolean = false,
    processParens: Boolean = true
) {
    if (text.isBlank()) return
    // 预处理保留为兼容入口；区块和行内语义直接解析原文，不再注入可泄漏的占位字符。
    val preprocessed = remember(text, chatMode, processParens) { preprocessMarkdown(text, chatMode, processParens) }
    val blocks = remember(preprocessed, chatMode) { parseBlocks(preprocessed, chatMode) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            RenderBlock(
                block = block,
                color = color,
                style = style,
                styleParentheses = chatMode && processParens
            )
        }
    }
}

// ==================== 预处理 ====================

/**
 * 保留旧入口以兼容调用方。Markdown 现在直接解析原文，不再插入控制字符或私有区字符，
 * 从根源上避免 `PAREN`、方框或自定义字体图标泄漏到最终回复。
 */
@Suppress("UNUSED_PARAMETER")
fun preprocessMarkdown(text: String, chatMode: Boolean = false, processParens: Boolean = true): String = text

// ==================== 区块解析 ====================

/** Markdown 区块类型 */
sealed class MdBlock {
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class Header(val level: Int, val content: String) : MdBlock()
    data class ListItem(val ordered: Boolean, val items: List<String>) : MdBlock()
    data class Blockquote(val content: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    object HorizontalRule : MdBlock()
    data class InnerMonologue(val content: String) : MdBlock()
    data class Paragraph(val content: String) : MdBlock()
}

/**
 * 将文本解析为区块列表。
 */
fun parseBlocks(text: String, chatMode: Boolean = false): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.split("\n")
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 空行跳过
        if (line.isBlank()) { i++; continue }

        // 代码块
        if (line.startsWith("```")) {
            val language = line.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            i++ // 跳过结束的 ```
            blocks.add(MdBlock.CodeBlock(language, codeLines.joinToString("\n")))
            continue
        }

        // 水平分割线
        if (line.matches(Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$"))) {
            blocks.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // 标题
        val headerMatch = Regex("^(#{1,6})\\s+(.*)").find(line)
        if (headerMatch != null) {
            blocks.add(MdBlock.Header(headerMatch.groupValues[1].length, headerMatch.groupValues[2]))
            i++
            continue
        }

        // 引用块
        if (line.startsWith("> ")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].startsWith("> ")) {
                quoteLines.add(lines[i].removePrefix("> "))
                i++
            }
            blocks.add(MdBlock.Blockquote(quoteLines.joinToString("\n")))
            continue
        }

        // 表格（含 | 分隔）
        if (line.contains("|") && i + 1 < lines.size && lines[i + 1].matches(Regex("^[|\\s-:]+$"))) {
            val header = parseTableRow(line)
            i += 2 // 跳过分隔行
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) {
                rows.add(parseTableRow(lines[i]))
                i++
            }
            blocks.add(MdBlock.Table(header, rows))
            continue
        }

        // 有序列表（允许项之间有空行：1.\n\n2. 视为同一列表）
        if (line.matches(Regex("^\\d+\\.\\s+.*"))) {
            val items = mutableListOf<String>()
            // 提取起始编号用于显示连续编号（保留 AI 原始编号或用 idx+1）
            val firstNumMatch = Regex("^(\\d+)\\.\\s+").find(line)
            val startNum = firstNumMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            var currentNum = startNum
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.matches(Regex("^\\d+\\.\\s+.*"))) {
                    // 同一编号（如都是 1.）或连续编号都视为列表项
                    val numMatch = Regex("^(\\d+)\\.\\s+").find(cur)
                    val num = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentNum
                    // 编号必须连续或相同才视为同一列表；若跳变（如 1. 然后 5.）则结束列表
                    if (num != currentNum && num != currentNum + 1 && num != startNum) {
                        break
                    }
                    currentNum = num
                    items.add(cur.replaceFirst(Regex("^\\d+\\.\\s+"), ""))
                    i++
                } else if (cur.isBlank()) {
                    // 空行：检查下一行是否还是列表项，是则跳过空行继续，否则结束
                    val next = lines.getOrNull(i + 1)
                    if (next != null && next.matches(Regex("^\\d+\\.\\s+.*"))) {
                        i++
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            blocks.add(MdBlock.ListItem(true, items))
            continue
        }

        // 无序列表（允许项之间有空行）
        if (line.matches(Regex("^[-*+]\\s+.*"))) {
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                if (cur.matches(Regex("^[-*+]\\s+.*"))) {
                    items.add(cur.replaceFirst(Regex("^[-*+]\\s+"), ""))
                    i++
                } else if (cur.isBlank()) {
                    val next = lines.getOrNull(i + 1)
                    if (next != null && next.matches(Regex("^[-*+]\\s+.*"))) {
                        i++
                    } else {
                        break
                    }
                } else {
                    break
                }
            }
            blocks.add(MdBlock.ListItem(false, items))
            continue
        }

        // 聊天模式直接从原文识别内心独白，避免使用任何可能显示出来的中间占位符。
        if (chatMode) {
            val segments = splitInnerSegments(line)
            if (segments.any { it.first == SEG_INNER }) {
                segments.forEach { (type, content) ->
                    if (type == SEG_INNER) {
                        blocks.add(MdBlock.InnerMonologue(content))
                    } else if (content.isNotBlank()) {
                        blocks.add(MdBlock.Paragraph(content))
                    }
                }
                i++
                continue
            }
        }

        // 普通段落（连续非空行合并）
        val paraLines = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].startsWith("```") &&
            !lines[i].startsWith("#") &&
            !lines[i].startsWith("> ") &&
            !lines[i].matches(Regex("^\\d+\\.\\s+.*")) &&
            !lines[i].matches(Regex("^[-*+]\\s+.*")) &&
            !lines[i].matches(Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$"))
        ) {
            paraLines.add(lines[i])
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paraLines.joinToString("\n")))
        }
    }

    return blocks
}

private fun parseTableRow(line: String): List<String> {
    return line.trim().trim('|').split("|").map { it.trim() }
}

/** 分段类型：普通文本 / 内心独白 */
private const val SEG_TEXT = 0
private const val SEG_INNER = 1

/**
 * 将一行原文切分为交替的「普通文本」与「内心独白」段。
 * 行内代码中的相同文字不参与识别，代码内容始终保持原样。
 */
private fun splitInnerSegments(line: String): List<Pair<Int, String>> {
    val result = mutableListOf<Pair<Int, String>>()
    val innerRegex = Regex("[（(](?:内心|心里|心想|内白)[：:]([^（）()]*?)[）)]")
    val matches = innerRegex.findAll(line)
        .filterNot { match -> line.take(match.range.first).count { it == '`' } % 2 == 1 }
        .toList()
    if (matches.isEmpty()) return listOf(SEG_TEXT to line)

    var pos = 0
    matches.forEach { match ->
        val start = match.range.first
        if (start > pos) {
            val before = line.substring(pos, start)
            if (before.isNotEmpty()) result.add(SEG_TEXT to before)
        }
        result.add(SEG_INNER to match.groupValues[1])
        pos = match.range.last + 1
    }
    if (pos < line.length) {
        result.add(SEG_TEXT to line.substring(pos))
    }
    return result
}

// ==================== 区块渲染 ====================

@Composable
private fun RenderBlock(
    block: MdBlock,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    styleParentheses: Boolean
) {
    when (block) {
        is MdBlock.CodeBlock -> CodeBlockRenderer(block)
        is MdBlock.Header -> Text(
            text = parseInline(block.content, color, style, styleParentheses),
            style = when (block.level) {
                1 -> style.copy(fontSize = (style.fontSize.value * 1.8).sp, fontWeight = FontWeight.Bold)
                2 -> style.copy(fontSize = (style.fontSize.value * 1.5).sp, fontWeight = FontWeight.Bold)
                3 -> style.copy(fontSize = (style.fontSize.value * 1.3).sp, fontWeight = FontWeight.Bold)
                4 -> style.copy(fontSize = (style.fontSize.value * 1.2).sp, fontWeight = FontWeight.SemiBold)
                5 -> style.copy(fontSize = (style.fontSize.value * 1.1).sp, fontWeight = FontWeight.SemiBold)
                else -> style.copy(fontWeight = FontWeight.SemiBold)
            }
        )
        is MdBlock.ListItem -> ListRenderer(block, color, style, styleParentheses)
        is MdBlock.Blockquote -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Text(text = parseInline(block.content, color, style, styleParentheses), style = style)
        }
        is MdBlock.Table -> TableRenderer(block, color, style)
        is MdBlock.HorizontalRule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        )
        is MdBlock.InnerMonologue -> InnerMonologueRenderer(block, color, style)
        is MdBlock.Paragraph -> Text(
            text = parseInline(block.content, color, style, styleParentheses),
            style = style,
            color = color
        )
    }
}

@Composable
private fun CodeBlockRenderer(block: MdBlock.CodeBlock) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        // 语言标签 + 复制按钮
        if (block.language.isNotEmpty() || block.code.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.common_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .horizontalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun ListRenderer(
    block: MdBlock.ListItem,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    styleParentheses: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { idx, item ->
            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                Text(
                    text = if (block.ordered) "${idx + 1}. " else "• ",
                    style = style,
                    color = color
                )
                Text(
                    text = parseInline(item, color, style, styleParentheses),
                    style = style,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TableRenderer(block: MdBlock.Table, color: androidx.compose.ui.graphics.Color, style: androidx.compose.ui.text.TextStyle) {
    val borderColor = color.copy(alpha = 0.25f)
    val headerBg = color.copy(alpha = 0.10f)
    val cellBg = color.copy(alpha = 0.03f)

    // 统一列数：不足列的行用空字符串补齐，保证每行列数一致
    val columnCount = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 1)
    val header = block.header + List(maxOf(0, columnCount - block.header.size)) { "" }
    val rows = block.rows.map { it + List(maxOf(0, columnCount - it.size)) { "" } }

    // === 统一列宽方案 ===
    // 目标：所有列等宽，宽度 = 所有单元格中最长内容的宽度，但设上限避免超长单元格撑爆气泡。
    // 实现思路：用 rememberTextMeasurer 预测量每个单元格的内容宽度，取最大值作为统一列宽 W；
    //          每个单元格用 width(W) 固定宽度（替代 weight(1f)，因为 weight 无法基于内容宽度）。
    // 同行等高：Row.height(IntrinsicSize.Max) + 单元格 fillMaxHeight。
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 列宽上限：超过则触发横向滚动，避免气泡被撑爆
    val maxColumnWidthDp = 200.dp
    val horizontalPaddingDp = 16.dp // 左右各 8.dp

    val columnWidth: Dp = remember(block, style, color, maxColumnWidthDp) {
        val headerStyle = style.copy(fontWeight = FontWeight.Bold)
        var maxContentWidthPx = 0f
        // 测量表头
        for (col in 0 until columnCount) {
            val w = textMeasurer.measure(header[col], style = headerStyle).size.width.toFloat()
            if (w > maxContentWidthPx) maxContentWidthPx = w
        }
        // 测量数据行
        for (row in rows) {
            for (col in 0 until columnCount) {
                val w = textMeasurer.measure(row[col], style = style).size.width.toFloat()
                if (w > maxContentWidthPx) maxContentWidthPx = w
            }
        }
        // 加上左右 padding，并施加上限
        val totalPx = with(density) { maxContentWidthPx + horizontalPaddingDp.toPx() }
        val cappedPx = with(density) { maxColumnWidthDp.toPx() }
        val finalPx = minOf(totalPx, cappedPx)
        with(density) { finalPx.toDp() }
    }

    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(6.dp))
            .background(cellBg)
    ) {
        // === 表头 ===
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Max)
                .background(headerBg)
        ) {
            header.forEach { cell ->
                TableCell(
                    text = cell,
                    color = color,
                    style = style.copy(fontWeight = FontWeight.Bold),
                    borderColor = borderColor,
                    width = columnWidth
                )
            }
        }
        // === 数据行 ===
        rows.forEach { row ->
            Row(
                modifier = Modifier.height(IntrinsicSize.Max)
            ) {
                row.forEach { cell ->
                    TableCell(
                        text = cell,
                        color = color,
                        style = style,
                        borderColor = borderColor,
                        width = columnWidth
                    )
                }
            }
        }
    }
}

/** 表格单元格：固定宽度 width + fillMaxHeight 统一行高 + 居中文本 + 行内格式（粗体/斜体/代码） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    borderColor: androidx.compose.ui.graphics.Color,
    width: Dp
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .border(0.5.dp, borderColor)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = parseInline(text, color, style),
            style = style,
            color = color,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InnerMonologueRenderer(block: MdBlock.InnerMonologue, color: androidx.compose.ui.graphics.Color, style: androidx.compose.ui.text.TextStyle) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.markdown_inner_monologue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.markdown_collapse) else stringResource(R.string.markdown_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Text(
                text = parseInline(block.content, color.copy(alpha = 0.6f), style.copy(fontStyle = FontStyle.Italic)),
                style = style.copy(fontStyle = FontStyle.Italic),
                color = color.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ==================== 行内解析 ====================

/**
 * 解析行内格式：粗体 **text**、斜体 *text*、行内代码 `code`、链接 [text](url)。
 * 删除线已被禁用，`~~` 会按原文显示。
 */
@Suppress("UNUSED_PARAMETER")
fun parseInline(
    text: String,
    baseColor: androidx.compose.ui.graphics.Color,
    baseStyle: androidx.compose.ui.text.TextStyle,
    styleParentheses: Boolean = false
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // 粗体 **text**
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            // 斜体 *text*（不与粗体冲突）
            if (text[i] == '*' && (i + 1 >= text.length || text[i + 1] != '*')) {
                val end = text.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            // 行内代码 `code`
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.15f))) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            // 聊天气泡中的普通括号旁白直接从原文识别并着色，不再注入 PAREN/私有区占位符。
            // 行内代码已在上方整体消费，因此代码里的括号不会被改写。
            if (styleParentheses && (text[i] == '（' || text[i] == '(')) {
                val close = if (text[i] == '（') '）' else ')'
                val end = text.indexOf(close, i + 1)
                if (end > i + 1 && end - i - 1 <= 50) {
                    val content = text.substring(i + 1, end)
                    val isSimple = content.none { it == '（' || it == '）' || it == '(' || it == ')' }
                    val isInnerMonologue = Regex("^(?:内心|心里|心想|内白)[：:]")
                        .containsMatchIn(content)
                    if (isSimple && !isInnerMonologue) {
                        withStyle(
                            SpanStyle(
                                fontStyle = FontStyle.Italic,
                                color = baseColor.copy(alpha = 0.62f)
                            )
                        ) {
                            append(text.substring(i, end + 1))
                        }
                        i = end + 1
                        continue
                    }
                }
            }
            // 链接 [text](url)
            if (text[i] == '[') {
                val textEnd = text.indexOf(']', i + 1)
                if (textEnd > 0 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
                    val urlEnd = text.indexOf(')', textEnd + 2)
                    if (urlEnd > 0) {
                        val linkText = text.substring(i + 1, textEnd)
                        val url = text.substring(textEnd + 2, urlEnd)
                        withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF58A6FF))) {
                            append(linkText)
                        }
                        i = urlEnd + 1
                        continue
                    }
                }
            }
            // 普通字符
            append(text[i])
            i++
        }
    }
}
