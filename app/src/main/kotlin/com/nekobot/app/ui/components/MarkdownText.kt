package com.nekobot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    processParens: Boolean = true
) {
    if (text.isBlank()) return
    // 预处理：内心独白、括号斜体、删除线占位
    val preprocessed = remember(text, processParens) { preprocessMarkdown(text, processParens) }
    val blocks = remember(preprocessed) { parseBlocks(preprocessed) }

    Column(modifier = modifier) {
        blocks.forEach { block -> RenderBlock(block, color, style) }
    }
}

// ==================== 预处理 ====================

/** 删除线占位符（禁用删除线渲染） */
private const val TILDE_PLACEHOLDER = "\u0001TILDE\u0001"
/** 内心独白占位符标记 */
private const val INNER_OPEN = "\u0002INNER:"
private const val INNER_CLOSE = "\u0003"

/**
 * 预处理 Markdown 文本：
 * 1. 将 ~~text~~ 中的 ~~ 替换为占位符（禁用删除线）
 * 2. 将 `（内心：...）` 或 `(内心：...)` 转为内心独白占位符
 * 3. 将全角括号 `（非内心内容）` 转为斜体 *内容*
 */
fun preprocessMarkdown(text: String, processParens: Boolean = true): String {
    var result = text
    // 1. 禁用删除线：把 ~~ 替换为占位符
    result = result.replace("~~", TILDE_PLACEHOLDER)

    // 2. 内心独白：匹配 `（内心：...）` 或 `(内心：...)` 或 `（心里：...）`
    val innerRegex = Regex("[（(](?:内心|心里|心想|内白)[：:].*?[）)]", RegexOption.DOT_MATCHES_ALL)
    result = innerRegex.replace(result) { m ->
        // 提取冒号后的内容
        val content = m.value.substringAfter("：").substringAfter(":").removeSuffix("）").removeSuffix(")")
        "$INNER_OPEN$content$INNER_CLOSE"
    }

    // 3. 全角括号内容斜体：剩余的 `（...）` 转为 *...*
    // 仅处理短括号内容（避免误伤长文本）
    // 用户发送的气泡不处理括号（保留原始括号字符）
    if (processParens) {
        val parenRegex = Regex("[（(]([^（）()]{1,50})[）)]")
        result = parenRegex.replace(result) { m ->
            val content = m.groupValues[1]
            "*$content*"
        }
    }

    return result
}

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
fun parseBlocks(text: String): List<MdBlock> {
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

        // 有序列表
        if (line.matches(Regex("^\\d+\\.\\s+.*"))) {
            val items = mutableListOf<String>()
            while (i < lines.size && lines[i].matches(Regex("^\\d+\\.\\s+.*"))) {
                items.add(lines[i].replaceFirst(Regex("^\\d+\\.\\s+"), ""))
                i++
            }
            blocks.add(MdBlock.ListItem(true, items))
            continue
        }

        // 无序列表
        if (line.matches(Regex("^[-*+]\\s+.*"))) {
            val items = mutableListOf<String>()
            while (i < lines.size && lines[i].matches(Regex("^[-*+]\\s+.*"))) {
                items.add(lines[i].replaceFirst(Regex("^[-*+]\\s+"), ""))
                i++
            }
            blocks.add(MdBlock.ListItem(false, items))
            continue
        }

        // 内心独白占位符（整段）
        if (line.contains(INNER_OPEN)) {
            val content = extractInner(line)
            if (content != null) {
                blocks.add(MdBlock.InnerMonologue(content))
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

private fun extractInner(text: String): String? {
    val start = text.indexOf(INNER_OPEN)
    if (start < 0) return null
    val end = text.indexOf(INNER_CLOSE, start + INNER_OPEN.length)
    if (end < 0) return null
    return text.substring(start + INNER_OPEN.length, end)
}

// ==================== 区块渲染 ====================

@Composable
private fun RenderBlock(block: MdBlock, color: androidx.compose.ui.graphics.Color, style: androidx.compose.ui.text.TextStyle) {
    when (block) {
        is MdBlock.CodeBlock -> CodeBlockRenderer(block)
        is MdBlock.Header -> Text(
            text = parseInline(block.content, color, style),
            style = when (block.level) {
                1 -> style.copy(fontSize = (style.fontSize.value * 1.8).sp, fontWeight = FontWeight.Bold)
                2 -> style.copy(fontSize = (style.fontSize.value * 1.5).sp, fontWeight = FontWeight.Bold)
                3 -> style.copy(fontSize = (style.fontSize.value * 1.3).sp, fontWeight = FontWeight.Bold)
                4 -> style.copy(fontSize = (style.fontSize.value * 1.2).sp, fontWeight = FontWeight.SemiBold)
                5 -> style.copy(fontSize = (style.fontSize.value * 1.1).sp, fontWeight = FontWeight.SemiBold)
                else -> style.copy(fontWeight = FontWeight.SemiBold)
            }
        )
        is MdBlock.ListItem -> ListRenderer(block, color, style)
        is MdBlock.Blockquote -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Text(text = parseInline(block.content, color, style), style = style)
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
            text = parseInline(block.content, color, style),
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
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
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
private fun ListRenderer(block: MdBlock.ListItem, color: androidx.compose.ui.graphics.Color, style: androidx.compose.ui.text.TextStyle) {
    Column(modifier = Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { idx, item ->
            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                Text(
                    text = if (block.ordered) "${idx + 1}. " else "• ",
                    style = style,
                    color = color
                )
                Text(
                    text = parseInline(item, color, style),
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .horizontalScroll(rememberScrollState())
            .padding(4.dp)
    ) {
        // 表头
        Row {
            block.header.forEach { cell ->
                Text(
                    text = cell,
                    style = style.copy(fontWeight = FontWeight.Bold),
                    color = color,
                    modifier = Modifier
                        .widthIn(min = 60.dp)
                        .padding(4.dp)
                )
            }
        }
        // 数据行
        block.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        text = parseInline(cell, color, style),
                        style = style,
                        color = color,
                        modifier = Modifier
                            .widthIn(min = 60.dp)
                            .padding(4.dp)
                    )
                }
            }
        }
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
                text = "内心独白",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
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
 * 删除线已被禁用（~~ 替换为占位符）。
 */
fun parseInline(text: String, baseColor: androidx.compose.ui.graphics.Color, baseStyle: androidx.compose.ui.text.TextStyle): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // 恢复删除线占位符为 ~~
            if (text.startsWith(TILDE_PLACEHOLDER, i)) {
                append("~~")
                i += TILDE_PLACEHOLDER.length
                continue
            }
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
