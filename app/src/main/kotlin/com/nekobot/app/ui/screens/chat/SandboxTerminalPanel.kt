package com.nekobot.app.ui.screens.chat

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.terminal.CursorShape
import com.nekobot.app.data.local.ai.terminal.LocalTerminalSession
import com.nekobot.app.data.local.ai.terminal.TerminalCell
import com.nekobot.app.data.local.ai.terminal.TerminalEmulator
import com.nekobot.app.data.local.ai.terminal.TerminalPalette
import com.nekobot.app.data.local.ai.terminal.TextAttributes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── 配色：沿用聊天页沙箱终端的深色风格 ────────────────────────────────────
private val TerminalTopBarBg = Color(0xFF111820)
private val TerminalPanelBg = Color(0xFF111820)
private val TerminalForeground = Color(0xFFD8DEE9)
private val TerminalMuted = Color(0xFF7F8B99)
private val TerminalAccent = Color(0xFF73D99F)
private val KeyButtonBg = Color(0xFF1E2833)
private val KeyButtonActiveBg = Color(0xFF2F6F4F)
private const val SelectionColor = 0x6633AAFF

private const val TERMINAL_FONT_SIZE_SP = 13f
private const val CURSOR_BLINK_INTERVAL_MS = 500L

/**
 * 全屏沙箱终端。
 *
 * 沿用 OpenMinis 的架构：PTY 里的 shell 输出原始字节 → [TerminalEmulator] 解析
 * ANSI/VT 序列 → Canvas 按字符单元格绘制；用户按键原样写回 PTY。
 * 因此提示符、ANSI 颜色、Ctrl+C、Tab 补全、方向键、vi/top 都由真实终端语义提供，
 * 而不是由 App 自己拼装"命令 + 输出"的列表。
 *
 * [emulator] 由调用方持有（按会话 remember），这样关掉终端界面再打开时，
 * 之前的输出与回滚历史都还在；PTY 输出也一直在喂给它，不会丢内容。
 *
 * 输入层用一个不可见的 EditText 接管输入法（见 [TerminalInputEditText]），
 * 组词中的拼音不下发，只有真正提交的字符才进入 shell。
 */
@Composable
internal fun SandboxTerminalOverlay(
    emulator: TerminalEmulator,
    state: LocalTerminalSession.State,
    onOpenFiles: () -> Unit,
    onRestart: () -> Unit,
    onSendBytes: (ByteArray) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onDismiss: () -> Unit,
    bottomClearance: Dp = 0.dp,
) {
    val inputController = remember { TerminalInputController() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current

    var version by remember { mutableIntStateOf(0) }
    var blinkOn by remember { mutableStateOf(true) }
    var ctrlActive by remember { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(false) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var copiedNotice by remember { mutableStateOf(false) }
    var selectionVersion by remember { mutableIntStateOf(0) }
    var showExitNotice by remember { mutableStateOf(false) }

    // 等宽字体下 "M" 的宽度即列宽，行高取字体升降部之和
    val fontPx = with(density) { TERMINAL_FONT_SIZE_SP.sp.toPx() }
    val glyphPaint = remember(fontPx) {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = fontPx
            isAntiAlias = true
            isSubpixelText = true
        }
    }
    val cellWidth = remember(glyphPaint) { glyphPaint.measureText("M").coerceAtLeast(1f) }
    val cellHeight = remember(glyphPaint) {
        val metrics = glyphPaint.fontMetrics
        (metrics.descent - metrics.ascent).coerceAtLeast(1f)
    }
    val baselineOffset = remember(glyphPaint) { -glyphPaint.fontMetrics.ascent }

    val cols = if (surfaceSize.width > 0) {
        (surfaceSize.width / cellWidth).toInt().coerceIn(MIN_COLS, MAX_COLS)
    } else {
        TerminalEmulator.DEFAULT_COLS
    }
    val rows = if (surfaceSize.height > 0) {
        (surfaceSize.height / cellHeight).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
    } else {
        TerminalEmulator.DEFAULT_ROWS
    }

    // PTY 输出由调用方持续喂入仿真器，这里只负责把终端响应（DSR 等）写回 PTY
    DisposableEffect(emulator) {
        emulator.onChanged = { version++ }
        emulator.onResponse = { bytes -> onSendBytes(bytes) }
        onDispose {
            emulator.onChanged = null
            emulator.onResponse = null
        }
    }

    // 尺寸变化：仿真器先重排，再通知 PTY（内核向 shell 发 SIGWINCH）
    LaunchedEffect(cols, rows) {
        if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return@LaunchedEffect
        emulator.resize(cols, rows)
        onResize(cols, rows)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(CURSOR_BLINK_INTERVAL_MS)
            blinkOn = !blinkOn
        }
    }

    LaunchedEffect(copiedNotice) {
        if (copiedNotice) {
            delay(1200)
            copiedNotice = false
        }
    }

    // shell 退出后给出提示，避免用户对着不动的画面继续敲键盘
    LaunchedEffect(state) {
        showExitNotice = state == LocalTerminalSession.State.STOPPED
    }

    BackHandler(onBack = onDismiss)

    fun copySelection() {
        val text = emulator.selectedText()
        if (text.isNotEmpty()) {
            clipboard.setText(AnnotatedString(text))
            copiedNotice = true
        }
        emulator.clearSelection()
        selectionVersion++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(TerminalPalette.DEFAULT_BACKGROUND))
            // 覆盖层不是独立窗口，拦截空白区点击避免透传到聊天页
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = bottomClearance),
        ) {
            TerminalTopBar(
                state = state,
                onOpenFiles = onOpenFiles,
                onRestart = {
                    emulator.clearScreen()
                    inputController.resetLine()
                    onRestart()
                },
                onClear = {
                    // 先 Ctrl+U 清掉 shell 里半行输入，再清屏，避免残留字符被下一条命令带上
                    onSendBytes(byteArrayOf(CTRL_U))
                    inputController.resetLine()
                    emulator.clearScreen()
                },
                onDismiss = onDismiss,
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { surfaceSize = it },
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(cols, rows, cellWidth, cellHeight) {
                            handleTerminalGestures(
                                emulator = emulator,
                                cellWidth = cellWidth,
                                cellHeight = cellHeight,
                                cols = cols,
                                rows = rows,
                                onTap = {
                                    inputController.requestFocus()
                                    keyboard?.show()
                                },
                                onSelectionChanged = { selectionVersion++ },
                            )
                        },
                ) {
                    // 读版本号：仿真器内容变化时触发重绘
                    @Suppress("UNUSED_EXPRESSION")
                    version
                    selectionVersion
                    drawTerminal(
                        emulator = emulator,
                        paint = glyphPaint,
                        cols = cols,
                        rows = rows,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        baselineOffset = baselineOffset,
                        showCursor = blinkOn && emulator.cursorVisible && emulator.scrollOffset == 0,
                    )
                }

                if (state == LocalTerminalSession.State.STARTING) {
                    TerminalNotice(
                        text = stringResource(R.string.chat_sandbox_terminal_starting),
                        showSpinner = true,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (state == LocalTerminalSession.State.UNAVAILABLE) {
                    TerminalNotice(
                        text = stringResource(R.string.chat_sandbox_terminal_unsupported),
                        showSpinner = false,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (state == LocalTerminalSession.State.STOPPED && showExitNotice) {
                    Text(
                        text = stringResource(
                            R.string.chat_sandbox_terminal_exited,
                            stringResource(R.string.chat_sandbox_terminal_restart_hint),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalMuted,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(TerminalPanelBg)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                if (emulator.selectionRect != null) {
                    SelectionActions(
                        copied = copiedNotice,
                        onCopy = { copySelection() },
                        onCancel = {
                            emulator.clearSelection()
                            selectionVersion++
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                    )
                }
            }

            // 不可见输入框：只负责把键盘输入翻译成终端字节
            TerminalInputView(
                controller = inputController,
                emulator = emulator,
                ctrlActive = ctrlActive,
                onCtrlConsumed = { ctrlActive = false },
                onSendBytes = { bytes ->
                    emulator.scrollOffset = 0
                    onSendBytes(bytes)
                },
                onFocusChanged = { focused -> keyboardVisible = focused },
            )

            TerminalKeyBar(
                ctrlActive = ctrlActive,
                keyboardVisible = keyboardVisible,
                applicationCursorKeys = emulator.applicationCursorKeys,
                onToggleCtrl = { ctrlActive = !ctrlActive },
                onToggleKeyboard = {
                    if (keyboardVisible) inputController.clearFocus() else inputController.requestFocus()
                },
                onSendBytes = { bytes ->
                    emulator.scrollOffset = 0
                    onSendBytes(bytes)
                },
                onClearLine = { inputController.resetLine() },
                onScrollHistory = { delta ->
                    emulator.scrollOffset = (emulator.scrollOffset + delta)
                        .coerceIn(0, emulator.activeBuffer.scrollback.size)
                },
            )
        }
    }
}

// ── 绘制 ──────────────────────────────────────────────────────────────────

/**
 * 按行绘制字符网格：连续同一样式的单元格合并成一次 drawText，
 * 宽字符（CJK/emoji）单独绘制并横向缩放到两格宽，避免整行错位。
 */
private fun DrawScope.drawTerminal(
    emulator: TerminalEmulator,
    paint: Paint,
    cols: Int,
    rows: Int,
    cellWidth: Float,
    cellHeight: Float,
    baselineOffset: Float,
    showCursor: Boolean,
) {
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawColor(TerminalPalette.DEFAULT_BACKGROUND)

    val lines = emulator.visibleLines()
    val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
    val run = StringBuilder()

    for (row in 0 until minOf(rows, lines.size)) {
        val line = lines[row]
        val top = row * cellHeight
        run.setLength(0)
        var runStartCol = -1
        var runForeground = 0
        var runBold = false
        var runItalic = false
        var runUnderline = false
        var runStrike = false
        var runDim = false
        var runHidden = false

        fun flushRun() {
            if (run.isEmpty() || runStartCol < 0) {
                run.setLength(0)
                runStartCol = -1
                return
            }
            paint.color = runForeground
            paint.isFakeBoldText = runBold
            paint.typeface = styledTypeface(runBold, runItalic)
            paint.isUnderlineText = runUnderline
            paint.isStrikeThruText = runStrike
            paint.alpha = if (runHidden) 0 else if (runDim) 128 else 255
            canvas.drawText(run.toString(), runStartCol * cellWidth, top + baselineOffset, paint)
            paint.alpha = 255
            run.setLength(0)
            runStartCol = -1
        }

        for (col in 0 until minOf(cols, line.size)) {
            val cell = line[col]
            if (cell.isWideTrailer) continue

            val attributes = cell.attributes
            val inverse = attributes.has(TextAttributes.INVERSE)
            val bold = attributes.has(TextAttributes.BOLD)
            val foreground = TerminalPalette.resolve(
                if (inverse) cell.background else cell.foreground,
                isForeground = true,
                bold = bold,
            )
            val background = TerminalPalette.resolve(
                if (inverse) cell.foreground else cell.background,
                isForeground = false,
            )
            val cellWidthPx = if (cell.width == 2) cellWidth * 2 else cellWidth

            if (background != TerminalPalette.DEFAULT_BACKGROUND) {
                flushRun()
                backgroundPaint.color = background
                canvas.drawRect(
                    col * cellWidth,
                    top,
                    col * cellWidth + cellWidthPx,
                    top + cellHeight,
                    backgroundPaint,
                )
            }

            val invisible = cell.codePoint == TerminalCell.SPACE &&
                !attributes.has(TextAttributes.UNDERLINE) &&
                !attributes.has(TextAttributes.STRIKETHROUGH) &&
                !inverse
            if (invisible) {
                flushRun()
                continue
            }

            if (cell.width == 2) {
                flushRun()
                drawWideCell(
                    canvas = canvas,
                    paint = paint,
                    cell = cell,
                    foreground = foreground,
                    bold = bold,
                    attributes = attributes,
                    x = col * cellWidth,
                    y = top + baselineOffset,
                    targetWidth = cellWidthPx,
                )
                continue
            }

            val sameStyle = run.isNotEmpty() &&
                runForeground == foreground &&
                runBold == bold &&
                runItalic == attributes.has(TextAttributes.ITALIC) &&
                runUnderline == attributes.has(TextAttributes.UNDERLINE) &&
                runStrike == attributes.has(TextAttributes.STRIKETHROUGH) &&
                runDim == attributes.has(TextAttributes.DIM) &&
                runHidden == attributes.has(TextAttributes.HIDDEN)
            if (!sameStyle) {
                flushRun()
                runStartCol = col
                runForeground = foreground
                runBold = bold
                runItalic = attributes.has(TextAttributes.ITALIC)
                runUnderline = attributes.has(TextAttributes.UNDERLINE)
                runStrike = attributes.has(TextAttributes.STRIKETHROUGH)
                runDim = attributes.has(TextAttributes.DIM)
                runHidden = attributes.has(TextAttributes.HIDDEN)
            }
            run.appendCodePoint(cell.codePoint)
        }
        flushRun()
    }

    // 文本选择高亮
    emulator.selectionRect?.let { selection ->
        val forward = selection[1] < selection[3] ||
            (selection[1] == selection[3] && selection[0] <= selection[2])
        val startCol = if (forward) selection[0] else selection[2]
        val startRow = (if (forward) selection[1] else selection[3]).coerceAtLeast(0)
        val endCol = if (forward) selection[2] else selection[0]
        val endRow = (if (forward) selection[3] else selection[1]).coerceAtMost(rows - 1)
        val selectionPaint = Paint().apply {
            style = Paint.Style.FILL
            color = SelectionColor
        }
        for (row in startRow..endRow) {
            val from = if (row == startRow) startCol.coerceAtLeast(0) else 0
            val to = if (row == endRow) (endCol + 1).coerceAtMost(cols) else cols
            if (to <= from) continue
            canvas.drawRect(
                from * cellWidth,
                row * cellHeight,
                to * cellWidth,
                (row + 1) * cellHeight,
                selectionPaint,
            )
        }
    }

    // 光标
    if (showCursor) {
        val (cursorCol, cursorRow) = emulator.cursorPosition()
        if (cursorRow in 0 until rows && cursorCol in 0 until cols) {
            val x = cursorCol * cellWidth
            val y = cursorRow * cellHeight
            val cursorPaint = Paint().apply {
                style = Paint.Style.FILL
                color = TerminalPalette.DEFAULT_FOREGROUND
            }
            when (emulator.cursorShape) {
                CursorShape.BLOCK -> {
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeight, cursorPaint)
                    val cell = lines.getOrNull(cursorRow)?.getOrNull(cursorCol)
                    if (cell != null && cell.codePoint != TerminalCell.SPACE && !cell.isWideTrailer) {
                        paint.color = TerminalPalette.DEFAULT_BACKGROUND
                        paint.typeface = Typeface.MONOSPACE
                        paint.isFakeBoldText = false
                        paint.isUnderlineText = false
                        paint.isStrikeThruText = false
                        paint.alpha = 255
                        canvas.drawText(
                            String(intArrayOf(cell.codePoint), 0, 1),
                            x,
                            y + baselineOffset,
                            paint,
                        )
                    }
                }
                CursorShape.UNDERLINE -> canvas.drawRect(
                    x,
                    y + cellHeight - 2f,
                    x + cellWidth,
                    y + cellHeight,
                    cursorPaint,
                )
                CursorShape.BAR -> canvas.drawRect(x, y, x + 2f, y + cellHeight, cursorPaint)
            }
        }
    }
}

/** 宽字符横向缩放到目标宽度，保证严格占两格。 */
private fun drawWideCell(
    canvas: android.graphics.Canvas,
    paint: Paint,
    cell: TerminalCell,
    foreground: Int,
    bold: Boolean,
    attributes: TextAttributes,
    x: Float,
    y: Float,
    targetWidth: Float,
) {
    val text = String(intArrayOf(cell.codePoint), 0, 1)
    paint.color = foreground
    paint.isFakeBoldText = bold
    paint.typeface = styledTypeface(bold, attributes.has(TextAttributes.ITALIC))
    paint.isUnderlineText = attributes.has(TextAttributes.UNDERLINE)
    paint.isStrikeThruText = attributes.has(TextAttributes.STRIKETHROUGH)
    paint.alpha = when {
        attributes.has(TextAttributes.HIDDEN) -> 0
        attributes.has(TextAttributes.DIM) -> 128
        else -> 255
    }
    val glyphWidth = paint.measureText(text)
    if (glyphWidth > 0f) {
        val saveCount = canvas.save()
        canvas.translate(x, y)
        canvas.scale(targetWidth / glyphWidth, 1f)
        canvas.drawText(text, 0f, 0f, paint)
        canvas.restoreToCount(saveCount)
    }
    paint.alpha = 255
}

private fun styledTypeface(bold: Boolean, italic: Boolean): Typeface = when {
    bold && italic -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    italic -> Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    else -> Typeface.MONOSPACE
}

// ── 手势 ──────────────────────────────────────────────────────────────────

/**
 * 轻点聚焦键盘，拖动翻看回滚历史，长按进入选择，长按后拖动扩展选区。
 *
 * 三种意图共享同一次按下，用同一个状态机判定，避免多个手势检测器互相抢事件。
 */
private suspend fun PointerInputScope.handleTerminalGestures(
    emulator: TerminalEmulator,
    cellWidth: Float,
    cellHeight: Float,
    cols: Int,
    rows: Int,
    onTap: () -> Unit,
    onSelectionChanged: () -> Unit,
) = coroutineScope {
    fun cellAt(position: Offset): Pair<Int, Int> =
        (position.x / cellWidth).toInt().coerceIn(0, cols - 1) to
            (position.y / cellHeight).toInt().coerceIn(0, rows - 1)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        var selecting = false
        var scrolling = false
        var scrollAccumulator = 0f

        val longPressWatcher = launch {
            delay(longPressTimeout)
            if (!selecting && !scrolling) {
                selecting = true
                val (col, row) = cellAt(down.position)
                val (startCol, endCol) = emulator.wordBounds(col, row)
                emulator.setSelection(col.coerceIn(startCol, endCol), row, endCol, row)
                onSelectionChanged()
            }
        }

        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                if (!selecting && !scrolling) {
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        scrolling = true
                        longPressWatcher.cancel()
                    }
                }

                if (selecting) {
                    val (col, row) = cellAt(change.position)
                    emulator.selectionRect?.let { current ->
                        emulator.setSelection(current[0], current[1], col, row)
                        onSelectionChanged()
                    }
                    change.consume()
                } else if (scrolling) {
                    scrollAccumulator += change.position.y - change.previousPosition.y
                    val rowDelta = (scrollAccumulator / cellHeight).toInt()
                    if (rowDelta != 0) {
                        scrollAccumulator -= rowDelta * cellHeight
                        emulator.scrollOffset = (emulator.scrollOffset + rowDelta)
                            .coerceIn(0, emulator.activeBuffer.scrollback.size)
                    }
                    change.consume()
                }
            }
        } finally {
            longPressWatcher.cancel()
        }

        if (!selecting && !scrolling) {
            emulator.clearSelection()
            onTap()
        }
    }
}

/** 以 [col] 为起点按空白边界扩展出单词范围，用于长按选中。 */
private fun TerminalEmulator.wordBounds(col: Int, row: Int): Pair<Int, Int> {
    val line = visibleLines().getOrNull(row) ?: return col to col
    if (col !in line.indices) return col to col

    fun isWordCodePoint(codePoint: Int): Boolean {
        val char = codePoint.toChar()
        return char.isLetterOrDigit() || char in WORD_PUNCTUATION
    }

    if (!isWordCodePoint(line[col].codePoint)) return col to col
    var start = col
    var end = col
    while (start > 0 && isWordCodePoint(line[start - 1].codePoint)) start--
    while (end < line.size - 1 && isWordCodePoint(line[end + 1].codePoint)) end++
    return start to end
}

private const val WORD_PUNCTUATION = "_-./:?&=+%#~@"

// ── 输入 ──────────────────────────────────────────────────────────────────

/** 隐藏输入框的宿主控制器：由界面调用以显示/隐藏键盘、重置当前行。 */
private class TerminalInputController {
    internal var editText: TerminalInputEditText? = null

    fun requestFocus() {
        val view = editText ?: return
        view.requestFocus()
        val manager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun clearFocus() {
        val view = editText ?: return
        val manager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    /** 清掉界面侧对"当前行"的记录，用于 Ctrl+U / Ctrl+C / 重启等会丢弃整行的场景。 */
    fun resetLine() {
        editText?.resetLocalLine()
    }
}

/**
 * 1×1 的不可见 EditText，接管输入法与硬件键盘。
 *
 * 关键点：
 *  - 输入框自身永远不保留文本，所有输入即时转成终端字节；
 *  - 输入法组词（拼音）不下发，只有提交的字符才写进 shell，
 *    否则中文输入会把拼音字母当成命令；
 *  - 退格走 [InputConnection.deleteSurroundingText]，因此即使输入框是空的
 *    也能正确向 shell 发送 0x7F（history 召回的行也能删）。
 */
@Composable
private fun TerminalInputView(
    controller: TerminalInputController,
    emulator: TerminalEmulator,
    ctrlActive: Boolean,
    onCtrlConsumed: () -> Unit,
    onSendBytes: (ByteArray) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val editText = remember {
        TerminalInputEditText(context).apply {
            alpha = 0f
            isCursorVisible = false
            setTextIsSelectable(false)
            setSingleLine()
            isFocusable = true
            isFocusableInTouchMode = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    DisposableEffect(editText) {
        controller.editText = editText
        onDispose {
            controller.editText = null
            editText.clearFocus()
        }
    }

    AndroidView(
        factory = { editText },
        update = { view ->
            view.bind(
                sendBytes = onSendBytes,
                applicationCursorKeys = { emulator.applicationCursorKeys },
                bracketedPaste = { emulator.bracketedPaste },
                ctrlActive = { ctrlActive },
                onCtrlConsumed = onCtrlConsumed,
                onFocusChanged = onFocusChanged,
            )
        },
        modifier = Modifier.size(1.dp),
    )
}

private class TerminalInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    private var sendBytes: (ByteArray) -> Unit = {}
    private var applicationCursorKeys: () -> Boolean = { false }
    private var bracketedPaste: () -> Boolean = { false }
    private var ctrlActive: () -> Boolean = { false }
    private var onCtrlConsumed: () -> Unit = {}
    private var focusListener: (Boolean) -> Unit = {}

    /** 最近一次组词内容；提交后清空，用于兜底那些只用 finishComposingText 的输入法。 */
    private var composing = ""

    fun bind(
        sendBytes: (ByteArray) -> Unit,
        applicationCursorKeys: () -> Boolean,
        bracketedPaste: () -> Boolean,
        ctrlActive: () -> Boolean,
        onCtrlConsumed: () -> Unit,
        onFocusChanged: (Boolean) -> Unit,
    ) {
        this.sendBytes = sendBytes
        this.applicationCursorKeys = applicationCursorKeys
        this.bracketedPaste = bracketedPaste
        this.ctrlActive = ctrlActive
        this.onCtrlConsumed = onCtrlConsumed
        this.focusListener = onFocusChanged
    }

    /** 丢弃界面记录的当前行（不影响 shell，调用方会另行告知 shell）。 */
    fun resetLocalLine() {
        composing = ""
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focusListener(gainFocus)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        // IME_ACTION_NONE：Enter 只是普通按键，不释放焦点，用户可以连续输入多条命令
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                composing = ""
                if (value.isNotEmpty()) emit(value)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // 组词中的拼音留在输入法里，不写进 shell
                composing = text?.toString().orEmpty()
                return true
            }

            override fun finishComposingText(): Boolean {
                // 少数输入法不调用 commitText，只用组词 + 结束；此时把非 ASCII 的最终结果补发，
                // 既不会把拼音字母漏进去，也不会丢掉选中的汉字
                val value = composing
                composing = ""
                if (value.isNotEmpty() && value.none { it.code in 0x20..0x7F }) emit(value)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(0)) { sendBytes(byteArrayOf(BACKSPACE)) }
                repeat(afterLength.coerceAtLeast(0)) { sendBytes("\u001B[3~".toByteArray()) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                event ?: return false
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return handleKey(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(event) || super.onKeyDown(keyCode, event)

    /** 把按键翻译成终端字节；返回 true 表示已消费。 */
    private fun handleKey(event: KeyEvent): Boolean {
        val applicationCursor = applicationCursorKeys()
        val ctrl = event.isCtrlPressed || ctrlActive()

        val bytes = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> byteArrayOf(CARRIAGE_RETURN)
            KeyEvent.KEYCODE_DEL -> byteArrayOf(BACKSPACE)
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~".toByteArray()
            KeyEvent.KEYCODE_TAB -> byteArrayOf(TAB)
            KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(ESCAPE)
            KeyEvent.KEYCODE_DPAD_UP -> arrow('A', applicationCursor)
            KeyEvent.KEYCODE_DPAD_DOWN -> arrow('B', applicationCursor)
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrow('C', applicationCursor)
            KeyEvent.KEYCODE_DPAD_LEFT -> arrow('D', applicationCursor)
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H".toByteArray()
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F".toByteArray()
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~".toByteArray()
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~".toByteArray()
            else -> {
                val unicode = event.unicodeChar
                if (unicode == 0) {
                    null
                } else {
                    val char = unicode.toChar()
                    when {
                        ctrl && char.uppercaseChar() in 'A'..'Z' -> {
                            onCtrlConsumed()
                            byteArrayOf((char.uppercaseChar() - 'A' + 1).toByte())
                        }
                        event.isAltPressed ->
                            byteArrayOf(ESCAPE) + char.toString().toByteArray(Charsets.UTF_8)
                        else -> char.toString().toByteArray(Charsets.UTF_8)
                    }
                }
            }
        } ?: return false

        if (ctrl) onCtrlConsumed()
        sendBytes(bytes)
        return true
    }

    /** 粘贴/提交的文本按终端约定换行，并在开启括号粘贴时加上定界序列。 */
    private fun emit(text: String) {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val payload = if (bracketedPaste()) {
            "\u001B[200~$normalized\u001B[201~".toByteArray(Charsets.UTF_8)
        } else {
            normalized.toByteArray(Charsets.UTF_8)
        }
        sendBytes(payload)
    }

    private fun arrow(direction: Char, applicationCursorKeys: Boolean): ByteArray {
        // DECCKM：应用光标模式用 SS3（ESC O x），否则用 CSI（ESC [ x）
        val prefix = if (applicationCursorKeys) {
            byteArrayOf(ESCAPE, 'O'.code.toByte())
        } else {
            byteArrayOf(ESCAPE, '['.code.toByte())
        }
        return prefix + direction.code.toByte()
    }
}

// ── 顶部栏与按键条 ────────────────────────────────────────────────────────

@Composable
private fun TerminalTopBar(
    state: LocalTerminalSession.State,
    onOpenFiles: () -> Unit,
    onRestart: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(TerminalTopBarBg)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Keyboard,
            contentDescription = null,
            tint = TerminalAccent,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_sandbox_terminal_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TerminalForeground,
            )
            Text(
                text = when (state) {
                    LocalTerminalSession.State.RUNNING ->
                        stringResource(R.string.chat_sandbox_terminal_subtitle)
                    LocalTerminalSession.State.STARTING ->
                        stringResource(R.string.chat_sandbox_terminal_starting)
                    LocalTerminalSession.State.UNAVAILABLE ->
                        stringResource(R.string.chat_sandbox_terminal_unsupported_short)
                    else -> stringResource(R.string.chat_sandbox_terminal_exited_short)
                },
                style = MaterialTheme.typography.labelSmall,
                color = TerminalMuted,
                maxLines = 1,
            )
        }
        IconButton(onClick = onOpenFiles) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = stringResource(R.string.chat_sandbox_files_open),
                tint = TerminalForeground,
            )
        }
        IconButton(onClick = onRestart) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.chat_sandbox_terminal_restart),
                tint = TerminalForeground,
            )
        }
        IconButton(onClick = onClear) {
            Icon(
                Icons.Filled.CleaningServices,
                contentDescription = stringResource(R.string.chat_sandbox_terminal_clear),
                tint = TerminalForeground,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = TerminalForeground,
            )
        }
    }
}

@Composable
private fun TerminalNotice(
    text: String,
    showSpinner: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = TerminalAccent,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TerminalMuted,
        )
    }
}

@Composable
private fun SelectionActions(
    copied: Boolean,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = TerminalPanelBg,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton(
                label = stringResource(
                    if (copied) R.string.chat_sandbox_terminal_copied
                    else R.string.chat_sandbox_terminal_copy
                ),
                onClick = onCopy,
            )
            Spacer(Modifier.width(6.dp))
            KeyButton(
                label = stringResource(R.string.common_cancel),
                onClick = onCancel,
            )
        }
    }
}

/**
 * 终端按键条：软键盘没有的键都在这里。
 *
 * 点 Ctrl 再按字母即发送控制码；方向键按 DECCKM 状态自动选择 CSI/SS3。
 */
@Composable
private fun TerminalKeyBar(
    ctrlActive: Boolean,
    keyboardVisible: Boolean,
    applicationCursorKeys: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onSendBytes: (ByteArray) -> Unit,
    onClearLine: () -> Unit,
    onScrollHistory: (Int) -> Unit,
) {
    val arrowPrefix = if (applicationCursorKeys) {
        byteArrayOf(ESCAPE, 'O'.code.toByte())
    } else {
        byteArrayOf(ESCAPE, '['.code.toByte())
    }

    Surface(modifier = Modifier.fillMaxWidth(), color = TerminalPanelBg) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton(
                label = stringResource(
                    if (keyboardVisible) R.string.chat_sandbox_terminal_hide_keyboard
                    else R.string.chat_sandbox_terminal_show_keyboard
                ),
                icon = if (keyboardVisible) Icons.Filled.KeyboardHide else Icons.Outlined.Keyboard,
                onClick = onToggleKeyboard,
            )
            KeyButton(label = "Esc", onClick = { onSendBytes(byteArrayOf(ESCAPE)) })
            KeyButton(
                label = "Tab",
                icon = Icons.AutoMirrored.Filled.KeyboardTab,
                onClick = { onSendBytes(byteArrayOf(TAB)) },
            )
            KeyButton(label = "⏎", onClick = { onSendBytes(byteArrayOf(CARRIAGE_RETURN)) })
            KeyButton(label = "Ctrl", isActive = ctrlActive, onClick = onToggleCtrl)
            KeyButton(
                label = "↑",
                icon = Icons.Filled.KeyboardArrowUp,
                onClick = { onSendBytes(arrowPrefix + 'A'.code.toByte()) },
            )
            KeyButton(
                label = "↓",
                icon = Icons.Filled.KeyboardArrowDown,
                onClick = { onSendBytes(arrowPrefix + 'B'.code.toByte()) },
            )
            KeyButton(
                label = "←",
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                onClick = { onSendBytes(arrowPrefix + 'D'.code.toByte()) },
            )
            KeyButton(
                label = "→",
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                onClick = { onSendBytes(arrowPrefix + 'C'.code.toByte()) },
            )
            KeyButton(
                label = "PgUp",
                onClick = {
                    onScrollHistory(HISTORY_PAGE_ROWS)
                    onSendBytes("\u001B[5~".toByteArray())
                },
            )
            KeyButton(
                label = "PgDn",
                onClick = {
                    onScrollHistory(-HISTORY_PAGE_ROWS)
                    onSendBytes("\u001B[6~".toByteArray())
                },
            )
            KeyButton(
                label = "C-c",
                icon = Icons.Outlined.Cancel,
                onClick = {
                    onClearLine()
                    onSendBytes(byteArrayOf(CTRL_C))
                },
            )
            KeyButton(
                label = "C-d",
                icon = Icons.Outlined.Eject,
                onClick = { onSendBytes(byteArrayOf(CTRL_D)) },
            )
            KeyButton(
                label = "C-z",
                icon = Icons.Outlined.PauseCircle,
                onClick = { onSendBytes(byteArrayOf(CTRL_Z)) },
            )
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    icon: ImageVector? = null,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (isActive) KeyButtonActiveBg else KeyButtonBg
    val contentColor = if (isActive) Color.White else TerminalAccent
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
        }
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

private const val ESCAPE = 0x1B.toByte()
private const val TAB = 0x09.toByte()
private const val CARRIAGE_RETURN = 0x0D.toByte()
private const val BACKSPACE = 0x7F.toByte()
private const val CTRL_C = 0x03.toByte()
private const val CTRL_D = 0x04.toByte()
private const val CTRL_U = 0x15.toByte()
private const val CTRL_Z = 0x1A.toByte()
private const val HISTORY_PAGE_ROWS = 8
private const val MIN_COLS = 20
private const val MAX_COLS = 400
private const val MIN_ROWS = 4
private const val MAX_ROWS = 200
