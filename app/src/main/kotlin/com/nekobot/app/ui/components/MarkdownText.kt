package com.nekobot.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekobot.app.R
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 自定义 Markdown 渲染器，参考原仓库 nbot-methods.js 的 renderMarkdown。
 *
 * 订制实现：
 * 1. 内心独白折叠块：`（内心：...）` 转为灰色可折叠块
 * 2. 全角括号内容斜体：`（...）` 转为斜体
 * 3. 禁用删除线：`~~text~~` 不渲染为删除线（与原仓库一致）
 * 4. 代码块和表格支持复制、全屏查看
 * 5. 表格可横向滚动，并可复制单个单元格
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
    processParens: Boolean = true,
    isStreaming: Boolean = false
) {
    if (text.isBlank()) return
    // 预处理保留为兼容入口；区块和行内语义直接解析原文，不再注入可泄漏的占位字符。
    val preprocessed = remember(text, chatMode, processParens) { preprocessMarkdown(text, chatMode, processParens) }
    val showInner = LocalShowInnerMonologue.current
    val blocks = remember(preprocessed, chatMode, showInner, isStreaming) {
        visibleMarkdownBlocks(preprocessed, chatMode, showInner, isStreaming)
    }

    // 先按最长可见文本/折叠标题定宽，再让独白条、引用等填满这一宽度。
    // 图片、代码和表格保留原来的专用测量，避免对含子组合的内容请求固有尺寸。
    Column(modifier = modifier.then(
        if (chatMode && chatMarkdownWrapsWidth(blocks)) Modifier.width(IntrinsicSize.Max) else Modifier
    )) {
        blocks.forEachIndexed { index, block ->
            RenderBlock(
                block = block,
                color = color,
                style = style,
                styleParentheses = chatMode && processParens,
                modifier = blockSpacing(block, isFirst = index == 0, isLast = index == blocks.lastIndex)
            )
        }
    }
}

internal fun chatMarkdownWrapsWidth(blocks: List<MdBlock>): Boolean = blocks.none {
    it is MdBlock.CodeBlock || it is MdBlock.Table || it is MdBlock.Image
}

/** null 表示普通正文；空列表表示已识别、尚未闭合的流式纯独白。 */
internal fun companionMonologueBlocks(text: String, isStreaming: Boolean): List<MdBlock.InnerMonologue>? {
    // token 可能停在「（内」「（内心」处；先暂存可能的独白前缀，避免新泡闪一下再并回正文。
    val prefix = text.trim()
    if (isStreaming && prefix.isNotEmpty() && INNER_LABELS.any { label ->
        listOf("（$label：", "（$label:", "($label：", "($label:").any { it.startsWith(prefix) }
    }) return emptyList()
    val blocks = parseBlocks(text, chatMode = true)
    if (blocks.isNotEmpty() && blocks.all { it is MdBlock.InnerMonologue }) {
        return blocks.filterIsInstance<MdBlock.InnerMonologue>()
    }
    if (isStreaming && text.isNotBlank() && visibleMarkdownBlocks(text, true, false, true).isEmpty()) {
        return emptyList()
    }
    return null
}

internal data class CompanionMarkdownPart(val sourceIndex: Int, val text: String)

internal fun companionBubbleMarkdownBlocks(text: String, showInnerMonologue: Boolean, isStreaming: Boolean): List<MdBlock> =
    if (companionMonologueBlocks(text, isStreaming)?.isEmpty() == true) emptyList()
    else visibleMarkdownBlocks(text, true, showInnerMonologue, isStreaming)

/**
 * 聊天设置「显示内心独白」：false 时聊天气泡完全跳过 `（内心：...）` 段，不留灰色块。
 * 仅影响渲染；消息原文、复制与搜索不变。默认 true 保持原行为。
 */
val LocalShowInnerMonologue = staticCompositionLocalOf { true }
/** 单会话气泡采用紧凑淡灰独白条；正文和复制仍使用原文。 */
val LocalCompactInnerMonologue = staticCompositionLocalOf { false }

/** 仅由单会话角色气泡提供；用户消息及其他 Markdown 页面继续沿用原配色。 */
internal data class MarkdownBubbleColors(
    val link: androidx.compose.ui.graphics.Color,
    val surface: androidx.compose.ui.graphics.Color,
    val onSurface: androidx.compose.ui.graphics.Color,
    val surfaceLink: androidx.compose.ui.graphics.Color,
    val parentheses: androidx.compose.ui.graphics.Color
)
internal val LocalMarkdownBubbleColors = staticCompositionLocalOf<MarkdownBubbleColors?> { null }
/** 双方气泡只覆盖正文链接；独白条和代码块继续使用各自表面配色。 */
internal val LocalMarkdownBubbleLinkColor = staticCompositionLocalOf<androidx.compose.ui.graphics.Color?> { null }

internal fun markdownLinkColor(
    colors: MarkdownBubbleColors?,
    bubbleLinkColor: androidx.compose.ui.graphics.Color?,
    onSurface: Boolean = false
): androidx.compose.ui.graphics.Color =
    (if (onSurface) colors?.surfaceLink else bubbleLinkColor ?: colors?.link)
        ?: androidx.compose.ui.graphics.Color(0xFF58A6FF)

@Composable
private fun parseRenderedInline(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    styleParentheses: Boolean = false,
    onSurface: Boolean = false
): AnnotatedString {
    val colors = LocalMarkdownBubbleColors.current
    return parseInline(text, color, style, styleParentheses,
        linkColor = markdownLinkColor(colors, LocalMarkdownBubbleLinkColor.current, onSurface),
        parenthesesColor = if (onSurface) colors?.onSurface ?: color.copy(alpha = 0.62f)
            else colors?.parentheses ?: color.copy(alpha = 0.62f))
}

/** 关闭独白时，流式未闭合尾段也隐藏；生成结束仍未闭合才原样保留。 */
internal fun visibleMarkdownBlocks(
    text: String,
    chatMode: Boolean,
    showInnerMonologue: Boolean,
    isStreaming: Boolean = false
): List<MdBlock> {
    if (!chatMode || showInnerMonologue) return parseBlocks(text, chatMode)
    return parseBlocks(stripHiddenInnerMonologue(text, isStreaming), chatMode)
        .filterNot { it is MdBlock.InnerMonologue }
}

private val INNER_LABELS = arrayOf("内心", "心里", "心想", "内白")

/** 隐藏独白（流式时包括未闭合尾段），全角半角括号和冒号都认。代码块与行内代码原样保留。 */
private fun stripHiddenInnerMonologue(text: String, isStreaming: Boolean): String {
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        if (isMarkdownLineStart(text, i) && text.startsWith("```", i)) {
            val fenceEnd = endOfMarkdownFence(text, i)
            if (fenceEnd < 0) {
                out.append(text.substring(i))
                return out.toString()
            }
            out.append(text.substring(i, fenceEnd))
            i = fenceEnd
            continue
        }
        if (text[i] == '`') {
            val close = text.indexOf('`', i + 1)
            if (close < 0) {
                // 落单反引号是正文，不能让后续真正的独白变成代码。
                out.append(text[i++])
                continue
            }
            out.append(text.substring(i, close + 1))
            i = close + 1
            continue
        }
        val openLen = innerMonologueOpenLength(text, i)
        if (openLen > 0) {
            val close = indexOfCloseParenOutsideCode(text, i + openLen)
            if (close < 0) {
                if (isStreaming) appendCodeRegions(out, text, i, text.length)
                else out.append(text.substring(i))
                return out.toString()
            }
            appendCodeRegions(out, text, i, close + 1)
            i = close + 1
            continue
        }
        out.append(text[i])
        i++
    }
    return out.toString()
}

private fun innerMonologueOpenLength(text: String, index: Int): Int {
    if (index >= text.length || (text[index] != '（' && text[index] != '(')) return 0
    val rest = text.substring(index + 1)
    for (label in INNER_LABELS) {
        if (!rest.startsWith(label)) continue
        val colon = index + 1 + label.length
        if (colon < text.length && (text[colon] == '：' || text[colon] == ':')) return colon - index + 1
    }
    return 0
}

private fun isMarkdownLineStart(text: String, index: Int): Boolean = index == 0 || text[index - 1] == '\n'

/** 围栏从开行算到闭合行之后；没闭合就返回 -1，剩下的都算代码。 */
private fun endOfMarkdownFence(text: String, openIndex: Int): Int {
    val lineEnd = text.indexOf('\n', openIndex)
    if (lineEnd < 0) return -1
    var i = lineEnd + 1
    while (i < text.length) {
        if (text.startsWith("```", i)) {
            val closeLineEnd = text.indexOf('\n', i)
            return if (closeLineEnd < 0) text.length else closeLineEnd + 1
        }
        val next = text.indexOf('\n', i)
        if (next < 0) return -1
        i = next + 1
    }
    return -1
}

private fun indexOfCloseParenOutsideCode(text: String, start: Int): Int {
    var i = start
    var depth = 1
    while (i < text.length) {
        if (isMarkdownLineStart(text, i) && text.startsWith("```", i)) {
            val fenceEnd = endOfMarkdownFence(text, i)
            if (fenceEnd < 0) return -1
            i = fenceEnd
            continue
        }
        if (text[i] == '`') {
            val close = text.indexOf('`', i + 1)
            i = if (close < 0) i + 1 else close + 1
            continue
        }
        when (text[i]) {
            '（', '(' -> depth++
            '）', ')' -> if (--depth == 0) return i
        }
        i++
    }
    return -1
}

/** 删掉独白正文时，把夹在中间的代码原样留下。 */
private fun appendCodeRegions(out: StringBuilder, text: String, start: Int, end: Int) {
    var i = start
    while (i < end) {
        if (isMarkdownLineStart(text, i) && text.startsWith("```", i)) {
            val fenceEnd = endOfMarkdownFence(text, i)
            if (fenceEnd < 0 || fenceEnd > end) {
                if (end == text.length) out.append(text.substring(i))
                return
            }
            out.append(text.substring(i, fenceEnd))
            i = fenceEnd
            continue
        }
        if (text[i] == '`') {
            val close = text.indexOf('`', i + 1)
            if (close < 0 || close >= end) {
                i++
                continue
            }
            out.append(text.substring(i, close + 1))
            i = close + 1
            continue
        }
        i++
    }
}

// ==================== 预处理 ====================

/** Markdown 文件扩展名：工作区文件、文件卡片、技能文件等预览统一用它判断渲染方式。 */
val MARKDOWN_FILE_EXTS = setOf("md", "markdown", "mdx")

/** 文件名或 MIME 类型是否为 Markdown 文件。 */
fun isMarkdownFileName(fileName: String, mimeType: String = ""): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in MARKDOWN_FILE_EXTS ||
        mimeType.equals("text/markdown", ignoreCase = true) ||
        mimeType.equals("text/x-markdown", ignoreCase = true)

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
    data class Image(val alt: String, val url: String) : MdBlock()
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

        // 独立图片块：兼容历史 AI 回复中的 ![说明](https://...) / file:// URI。
        val imageMatch = Regex("^!\\[([^]]*)]\\(([^\\s)]+)\\)\\s*$").find(line)
        if (imageMatch != null) {
            blocks.add(MdBlock.Image(imageMatch.groupValues[1], imageMatch.groupValues[2]))
            i++
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
    var pos = 0
    var i = 0
    while (i < line.length) {
        if (line[i] == '`') {
            val close = line.indexOf('`', i + 1)
            i = if (close < 0) i + 1 else close + 1
            continue
        }
        val openLen = innerMonologueOpenLength(line, i)
        if (openLen == 0) { i++; continue }
        val close = indexOfCloseParenOutsideCode(line, i + openLen)
        if (close < 0) break
        if (i > pos) result.add(SEG_TEXT to line.substring(pos, i))
        result.add(SEG_INNER to line.substring(i + openLen, close))
        i = close + 1
        pos = i
    }
    if (pos < line.length) {
        result.add(SEG_TEXT to line.substring(pos))
    }
    return result
}

// ==================== 区块间距与行高 ====================

/**
 * 排版参考主流设计（GitHub markdown-css、ChatGPT / Claude 对话流）：
 * - 正文行高约 1.5~1.65，这里取 1.6，长文本更易读；
 * - 标题行高收紧到 1.35，标题更紧凑、更有力；
 * - 段落之间留约 0.5em 段距；
 * - 标题上方留白大于下方（标题与后文更亲近），层级一目了然。
 */
private const val MARKDOWN_BODY_LINE_HEIGHT_RATIO = 1.6f
private const val MARKDOWN_HEADING_LINE_HEIGHT_RATIO = 1.35f

/** 兜底基准字号：调用方未指定 fontSize 时按 14sp 计算行高。 */
private const val MARKDOWN_DEFAULT_FONT_SIZE_SP = 14f

private fun markdownBaseFontSize(style: androidx.compose.ui.text.TextStyle): Float =
    style.fontSize.value.takeIf { it > 0f } ?: MARKDOWN_DEFAULT_FONT_SIZE_SP

/** 正文行高：fontSize * 1.6 */
private fun markdownBodyLineHeight(style: androidx.compose.ui.text.TextStyle): TextUnit =
    (markdownBaseFontSize(style) * MARKDOWN_BODY_LINE_HEIGHT_RATIO).sp

/** 标题行高：标题字号 * 1.35 */
private fun markdownHeadingLineHeight(fontSize: TextUnit): TextUnit =
    (fontSize.value * MARKDOWN_HEADING_LINE_HEIGHT_RATIO).sp

/**
 * 每个区块的垂直间距。上边距主要给标题（拉开标题与上文的间隔），
 * 下边距给所有区块统一的段距；首块不加顶部、末块不加底部，避免与气泡内边距叠加。
 */
private fun blockSpacing(block: MdBlock, isFirst: Boolean, isLast: Boolean): Modifier {
    val top = when (block) {
        is MdBlock.Header -> when (block.level) {
            1 -> 14.dp
            2 -> 12.dp
            3 -> 11.dp
            else -> 10.dp
        }
        else -> 0.dp
    }
    val bottom = when (block) {
        is MdBlock.Header -> when (block.level) {
            1 -> 9.dp
            2, 3 -> 7.dp
            else -> 6.dp
        }
        // 水平分割线自带上下 padding，不需要额外段距
        is MdBlock.HorizontalRule -> 0.dp
        else -> 8.dp
    }
    return Modifier
        .then(if (!isFirst && top > 0.dp) Modifier.padding(top = top) else Modifier)
        .then(if (!isLast && bottom > 0.dp) Modifier.padding(bottom = bottom) else Modifier)
}

/**
 * 标题样式：按层级放大字号并收紧行高。
 * 倍率参考对话类产品的窄屏排版（ChatGPT / Claude），比 GitHub 文档站的 2em 收敛得多：
 * 手机屏宽有限，过大的 H1 稍长即折行，反而压迫正文。
 */
private fun headerStyle(style: androidx.compose.ui.text.TextStyle, level: Int): androidx.compose.ui.text.TextStyle {
    val base = markdownBaseFontSize(style)
    val (size, weight) = when (level) {
        1 -> (base * 1.45f) to FontWeight.Bold
        2 -> (base * 1.3f) to FontWeight.Bold
        3 -> (base * 1.2f) to FontWeight.Bold
        4 -> (base * 1.1f) to FontWeight.SemiBold
        5 -> (base * 1.05f) to FontWeight.SemiBold
        else -> base to FontWeight.SemiBold
    }
    val fontSize = size.sp
    return style.copy(
        fontSize = fontSize,
        lineHeight = markdownHeadingLineHeight(fontSize),
        fontWeight = weight
    )
}

// ==================== 区块渲染 ====================

@Composable
private fun RenderBlock(
    block: MdBlock,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    styleParentheses: Boolean,
    modifier: Modifier = Modifier
) {
    when (block) {
        is MdBlock.CodeBlock -> CodeBlockRenderer(block, modifier)
        is MdBlock.Header -> MarkdownInlineText(
            text = parseRenderedInline(block.content, color, style, styleParentheses),
            style = headerStyle(style, block.level),
            color = color,
            modifier = modifier
        )
        is MdBlock.ListItem -> ListRenderer(block, color, style, styleParentheses, modifier)
        is MdBlock.Blockquote -> Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 8.dp)
                .background(LocalMarkdownBubbleColors.current?.surface
                    ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            MarkdownInlineText(
                text = parseRenderedInline(block.content, LocalMarkdownBubbleColors.current?.onSurface ?: color,
                    style, styleParentheses, onSurface = true),
                style = style.copy(lineHeight = markdownBodyLineHeight(style)),
                color = LocalMarkdownBubbleColors.current?.onSurface ?: color
            )
        }
        is MdBlock.Table -> TableRenderer(block, color, style, modifier = modifier)
        is MdBlock.Image -> MarkdownImageRenderer(block, modifier)
        is MdBlock.HorizontalRule -> Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        )
        is MdBlock.InnerMonologue -> InnerMonologueRenderer(block, color, style, modifier)
        is MdBlock.Paragraph -> MarkdownInlineText(
            text = parseRenderedInline(block.content, color, style, styleParentheses),
            style = style.copy(lineHeight = markdownBodyLineHeight(style)),
            color = color,
            modifier = modifier
        )
    }
}

@Composable
private fun CodeBlockRenderer(block: MdBlock.CodeBlock, modifier: Modifier = Modifier) {
    var fullscreen by remember { mutableStateOf(false) }

    CodeBlockContent(
        block = block,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        showToolbar = true,
        fullscreen = false,
        onFullscreen = { fullscreen = true }
    )

    if (fullscreen) {
        MarkdownFullscreenDialog(
            title = block.language.ifEmpty { stringResource(R.string.markdown_code_block) },
            copyText = block.code,
            onDismissRequest = { fullscreen = false }
        ) { contentModifier ->
            CodeBlockContent(
                block = block,
                modifier = contentModifier,
                showToolbar = false,
                fullscreen = true
            )
        }
    }
}

@Composable
private fun MarkdownImageRenderer(block: MdBlock.Image, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(block.url)
            .crossfade(true)
            .build(),
        contentDescription = block.alt.takeIf(String::isNotBlank),
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun CodeBlockContent(
    block: MdBlock.CodeBlock,
    modifier: Modifier,
    showToolbar: Boolean,
    fullscreen: Boolean,
    onFullscreen: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val colors = if (fullscreen) null else LocalMarkdownBubbleColors.current

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors?.surface ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        if (showToolbar) {
            MarkdownBlockToolbar(
                title = block.language.ifEmpty { stringResource(R.string.markdown_code_block) },
                onCopy = {
                    copyMarkdownText(context, clipboard, block.code)
                },
                onFullscreen = onFullscreen,
                contentColor = colors?.onSurface ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = block.code,
                style = MaterialTheme.typography.bodySmall,
                color = colors?.onSurface ?: MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun ListRenderer(
    block: MdBlock.ListItem,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    styleParentheses: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        block.items.forEachIndexed { idx, item ->
            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = if (block.ordered) "${idx + 1}. " else "• ",
                    style = style.copy(lineHeight = markdownBodyLineHeight(style)),
                    color = color
                )
                MarkdownInlineText(
                    text = parseRenderedInline(item, color, style, styleParentheses),
                    style = style.copy(lineHeight = markdownBodyLineHeight(style)),
                    color = color,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TableRenderer(
    block: MdBlock.Table,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false
) {
    var showFullscreen by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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

    Column(modifier = modifier.then(if (fullscreen) Modifier else Modifier.padding(vertical = 4.dp))) {
        if (!fullscreen) {
            MarkdownBlockToolbar(
                title = stringResource(R.string.markdown_table),
                onCopy = {
                    copyMarkdownText(context, clipboard, tableToTsv(block, color, style))
                },
                onFullscreen = { showFullscreen = true }
            )
        }

        val tableModifier = Modifier
            .then(if (fullscreen) Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier)
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(6.dp))
            .background(cellBg)
        Column(modifier = tableModifier) {
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

    val fullscreenColor = if (LocalMarkdownBubbleColors.current != null) MaterialTheme.colorScheme.onSurface else color
    if (showFullscreen) {
        MarkdownFullscreenDialog(
            title = stringResource(R.string.markdown_table),
            copyText = tableToTsv(block, color, style),
            onDismissRequest = { showFullscreen = false }
        ) { contentModifier ->
            LandscapeTableFullscreenContent(modifier = contentModifier) {
                TableRenderer(
                    block = block,
                    color = fullscreenColor,
                    style = style,
                    modifier = Modifier.fillMaxSize(),
                    fullscreen = true
                )
            }
        }
    }
}

/** 仅旋转表格内容区，以竖屏弹层承载横屏尺寸的表格，不改变设备方向。 */
@Composable
private fun LandscapeTableFullscreenContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .requiredSize(width = maxHeight, height = maxWidth)
                .graphicsLayer { rotationZ = 90f },
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/** 表格单元格：点击复制，长按可使用系统文本选择菜单选取局部内容。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    borderColor: androidx.compose.ui.graphics.Color,
    width: Dp
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyText = parseInline(text, color, style).text
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .border(0.5.dp, borderColor)
            .clickable { copyMarkdownText(context, clipboard, copyText) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        SelectionContainer(modifier = Modifier.fillMaxWidth()) {
            MarkdownInlineText(
                text = parseRenderedInline(text, color, style),
                style = style.copy(textAlign = TextAlign.Center),
                color = color,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MarkdownBlockToolbar(
    title: String,
    onCopy: () -> Unit,
    onFullscreen: (() -> Unit)?,
    contentColor: androidx.compose.ui.graphics.Color = LocalMarkdownBubbleColors.current?.parentheses
        ?: MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.common_copy),
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
        }
        onFullscreen?.let { openFullscreen ->
            IconButton(onClick = openFullscreen, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.markdown_fullscreen),
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MarkdownFullscreenDialog(
    title: String,
    copyText: String,
    onDismissRequest: () -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    IconButton(
                        onClick = { copyMarkdownText(context, clipboard, copyText) }
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.common_copy),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.markdown_exit_fullscreen),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                CompositionLocalProvider(
                    LocalMarkdownBubbleColors provides null,
                    LocalMarkdownBubbleLinkColor provides null
                ) {
                    content(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

private fun copyMarkdownText(context: Context, clipboard: androidx.compose.ui.platform.ClipboardManager, text: String) {
    clipboard.setText(AnnotatedString(text))
    Toast.makeText(context, context.getString(R.string.common_copy_success), Toast.LENGTH_SHORT).show()
}

internal fun tableToTsv(
    block: MdBlock.Table,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle
): String {
    val columnCount = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 1)
    val rows = listOf(block.header) + block.rows
    return rows.joinToString("\n") { row ->
        (row + List(maxOf(0, columnCount - row.size)) { "" })
            .take(columnCount)
            .joinToString("\t") { cell -> parseInline(cell, color, style).text }
    }
}

@Composable
private fun InnerMonologueRenderer(
    block: MdBlock.InnerMonologue,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val compact = LocalCompactInnerMonologue.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = LocalMarkdownBubbleColors.current
    val stripColor = colors?.surface ?: if (compact && !dark) androidx.compose.ui.graphics.Color(0xFFEDF0EF)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (compact) 0.65f else 0.35f)
    val stripInk = colors?.onSurface ?: MaterialTheme.colorScheme.onSurfaceVariant
    val expandedInk = colors?.onSurface ?: color.copy(alpha = 0.6f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(stripColor)
            .clickable { expanded = !expanded }
            .padding(horizontal = if (compact) 10.dp else 8.dp, vertical = if (compact) 8.dp else 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.markdown_inner_monologue),
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                color = stripInk,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.markdown_collapse) else stringResource(R.string.markdown_expand),
                tint = stripInk,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            MarkdownInlineText(
                text = parseRenderedInline(block.content, expandedInk, style.copy(fontStyle = FontStyle.Italic), onSurface = true),
                style = style.copy(fontStyle = FontStyle.Italic),
                color = expandedInk,
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
private const val URL_ANNOTATION_TAG = "URL"

@Composable
private fun MarkdownInlineText(
    text: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val links = text.getStringAnnotations(URL_ANNOTATION_TAG, 0, text.length)
    if (links.isEmpty()) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }

    val context = LocalContext.current
    ClickableText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        onClick = { offset ->
            text.getStringAnnotations(URL_ANNOTATION_TAG, offset, offset)
                .firstOrNull()
                ?.item
                ?.let { openExternalLink(context, it) }
        }
    )
}

private fun openExternalLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

@Suppress("UNUSED_PARAMETER")
fun parseInline(
    text: String,
    baseColor: androidx.compose.ui.graphics.Color,
    baseStyle: androidx.compose.ui.text.TextStyle,
    styleParentheses: Boolean = false,
    linkColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF58A6FF),
    parenthesesColor: androidx.compose.ui.graphics.Color = baseColor.copy(alpha = 0.62f)
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
                                color = parenthesesColor
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
                        val url = text.substring(textEnd + 2, urlEnd).trim()
                        val linkStart = length
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(linkText)
                        }
                        if (url.isHttpUrl()) {
                            addStringAnnotation(URL_ANNOTATION_TAG, url, linkStart, length)
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

private fun String.isHttpUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)
