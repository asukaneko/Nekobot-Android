package com.nekobot.app.integration

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nekobot.app.R
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 由用户在系统设置中显式开启的 Agent 辅助功能桥接层。
 * 密码节点始终脱敏，树遍历有深度和节点数上限，所有调用方仍需自行完成会话授权。
 */
class NekobotAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    internal fun snapshot(maxNodes: Int = DEFAULT_MAX_NODES, maxInteractive: Int = MAX_INTERACTIVE): AccessibilitySnapshot {
        val windows = windows.orEmpty().sortedByDescending { it.layer }
        val result = mutableListOf<Map<String, Any?>>()
        var truncated = false
        for (window in windows) {
            val root = window.root ?: continue
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty()) {
                if (result.size >= maxNodes.coerceIn(1, MAX_NODES)) {
                    truncated = true
                    queue.clear()
                    break
                }
                val (node, depth) = queue.removeFirst()
                result += node.toSnapshot(depth, window.id)
                if (depth < MAX_DEPTH) {
                    repeat(node.childCount) { index ->
                        node.getChild(index)?.let { queue.add(it to depth + 1) }
                    }
                }
            }
            if (truncated) break
        }
        return AccessibilitySnapshot(
            packageName = rootInActiveWindow?.packageName?.toString().orEmpty(),
            windowCount = windows.size,
            nodes = result,
            interactive = collectInteractive(windows, maxInteractive),
            truncated = truncated
        )
    }

    internal fun click(selector: String, field: String, exact: Boolean): AccessibilityActionResult {
        val node = findNode(selector, field, exact)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_element_not_found))
        return clickNode(node)
    }

    /** 按 ui_tree 返回的 interactive 编号点击元素。 */
    internal fun clickByIndex(index: Int): AccessibilityActionResult {
        val node = findNodeByIndex(index)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_element_not_found))
        return clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo): AccessibilityActionResult {
        val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_element_not_clickable))
        return AccessibilityActionResult(
            success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            message = getString(R.string.accessibility_click_requested),
            matched = node.summary()
        )
    }

    internal fun setText(selector: String, text: String, field: String, exact: Boolean): AccessibilityActionResult {
        val node = findNode(selector, field, exact)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_input_not_found))
        return setTextOn(node, text)
    }

    /** 按 ui_tree 返回的 interactive 编号写入文本。 */
    internal fun setTextByIndex(index: Int, text: String): AccessibilityActionResult {
        val node = findNodeByIndex(index)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_input_not_found))
        return setTextOn(node, text)
    }

    private fun setTextOn(node: AccessibilityNodeInfo, text: String): AccessibilityActionResult {
        if (!node.isEditable) return AccessibilityActionResult(
            false,
            getString(R.string.accessibility_input_not_editable),
            node.summary()
        )
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return AccessibilityActionResult(
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
            message = getString(R.string.accessibility_input_requested),
            matched = node.summary()
        )
    }

    /** 对指定节点或当前焦点/首个可编辑节点执行 IME 回车（触发搜索/确认）。 */
    internal fun imeEnter(target: AccessibilityNodeInfo?): AccessibilityActionResult {
        val node = target
            ?: findFirst { it.isFocused }
            ?: findFirst { it.isEditable }
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_ime_target_not_found))
        val performed = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return if (performed) {
            AccessibilityActionResult(true, getString(R.string.accessibility_ime_enter_requested), node.summary())
        } else {
            AccessibilityActionResult(false, getString(R.string.accessibility_ime_enter_failed))
        }
    }

    /** 聚焦目标节点并粘贴当前剪贴板文本（ACTION_SET_TEXT 不可用时的回退输入）。 */
    internal fun pasteInto(target: AccessibilityNodeInfo?): AccessibilityActionResult {
        val node = target
            ?: findFirst { it.isFocused && it.isEditable }
            ?: findFirst { it.isEditable }
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_input_not_found))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return AccessibilityActionResult(
            success = pasted,
            message = if (pasted) getString(R.string.accessibility_paste_requested) else getString(R.string.accessibility_paste_failed),
            matched = node.summary()
        )
    }

    // ------------------------------------------------------------------
    // 公开的编号/选择器定位动作（供 LocalAndroidToolExecutor 调用）
    // ------------------------------------------------------------------

    /** 按 interactive 编号在其 bounds 中心执行手势点击。 */
    internal fun tapByIndex(index: Int): AccessibilityActionResult {
        val node = findNodeByIndex(index)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_element_not_found))
        return tapAtBounds(node)
    }

    /** 按文字/描述/ID 定位并在其 bounds 中心执行手势点击。 */
    internal fun tapBySelector(selector: String, field: String, exact: Boolean): AccessibilityActionResult {
        val node = findNode(selector, field, exact)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_element_not_found))
        return tapAtBounds(node)
    }

    /** 按 interactive 编号在其 bounds 内沿方向滑动。 */
    internal fun swipeByIndex(index: Int, direction: String): AccessibilityActionResult {
        val node = findNodeByIndex(index)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_no_scrollable_area))
        return swipeAtBounds(node, direction)
    }

    /** 按文字/描述/ID 定位并在其 bounds 内沿方向滑动。 */
    internal fun swipeBySelector(selector: String, field: String, exact: Boolean, direction: String): AccessibilityActionResult {
        val node = findNode(selector, field, exact)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_no_scrollable_area))
        return swipeAtBounds(node, direction)
    }

    /** 按 interactive 编号定位输入框并执行 IME 回车。 */
    internal fun imeEnterByIndex(index: Int): AccessibilityActionResult {
        val node = findNodeByIndex(index)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_ime_target_not_found))
        return imeEnter(node)
    }

    /** 按文字/描述/ID 定位输入框并执行 IME 回车。 */
    internal fun imeEnterBySelector(selector: String, field: String, exact: Boolean): AccessibilityActionResult {
        val node = findNode(selector, field, exact)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_ime_target_not_found))
        return imeEnter(node)
    }

    /** 按 interactive 编号定位输入框并粘贴当前剪贴板内容。 */
    internal fun pasteIntoByIndex(index: Int): AccessibilityActionResult {
        val node = findNodeByIndex(index)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_input_not_found))
        return pasteInto(node)
    }

    /** 按文字/描述/ID 定位输入框并粘贴当前剪贴板内容。 */
    internal fun pasteIntoBySelector(selector: String, field: String, exact: Boolean): AccessibilityActionResult {
        val node = findNode(selector, field, exact)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_input_not_found))
        return pasteInto(node)
    }

    internal fun scroll(direction: String, selector: String?, field: String, exact: Boolean): AccessibilityActionResult {
        val preferred = selector?.takeIf(String::isNotBlank)?.let { findNode(it, field, exact) }
        return scrollOn(preferred, direction)
    }

    /** 按 ui_tree 返回的 interactive 编号滚动指定可滚动元素。 */
    internal fun scrollByIndex(index: Int, direction: String): AccessibilityActionResult {
        val node = findNodeByIndex(index)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_no_scrollable_area))
        return scrollOn(node, direction)
    }

    private fun scrollOn(preferred: AccessibilityNodeInfo?, direction: String): AccessibilityActionResult {
        val scrollable = generateSequence(preferred) { it.parent }.firstOrNull { it.isScrollable }
            ?: findFirst { it.isScrollable }
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_no_scrollable_area))
        val action = when (direction.lowercase()) {
            "down", "forward", "next", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "backward", "previous", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return AccessibilityActionResult(false, getString(R.string.accessibility_scroll_directions))
        }
        return AccessibilityActionResult(
            success = scrollable.performAction(action),
            message = getString(R.string.accessibility_scroll_requested),
            matched = scrollable.summary()
        )
    }

    internal fun runGlobalAction(action: String): AccessibilityActionResult {
        val actionId = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "overview" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return AccessibilityActionResult(false, getString(R.string.accessibility_global_actions))
        }
        return AccessibilityActionResult(
            performGlobalAction(actionId),
            getString(R.string.accessibility_global_action_requested)
        )
    }

    internal fun captureScreenshot(output: File): AccessibilityActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AccessibilityActionResult(false, getString(R.string.accessibility_screenshot_api_required))
        }
        output.parentFile?.mkdirs()
        val latch = CountDownLatch(1)
        val result = AtomicReference<AccessibilityActionResult>()
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        if (bitmap == null) {
                            result.set(AccessibilityActionResult(false, getString(R.string.accessibility_screenshot_buffer_failed)))
                        } else {
                            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            result.set(
                                AccessibilityActionResult(
                                    success = true,
                                    message = getString(R.string.accessibility_screenshot_saved),
                                    metadata = mapOf(
                                        "absolute_path" to output.absolutePath,
                                        "width" to bitmap.width,
                                        "height" to bitmap.height,
                                        "size" to output.length()
                                    )
                                )
                            )
                            bitmap.recycle()
                        }
                    } finally {
                        buffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    result.set(AccessibilityActionResult(false, getString(R.string.accessibility_screenshot_failed, errorCode)))
                    latch.countDown()
                }
            }
        )
        if (!latch.await(SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return AccessibilityActionResult(false, getString(R.string.accessibility_screenshot_timeout))
        }
        return result.get() ?: AccessibilityActionResult(false, getString(R.string.accessibility_screenshot_no_result))
    }

    // ------------------------------------------------------------------
    // 手势层：dispatchGesture（坐标点击 / 滑动 / 长按）
    // 与 ACTION_CLICK 等语义动作互补，覆盖游戏、自绘 View 等不暴露
    // 可点击节点或对 performAction 无响应的应用。
    // ------------------------------------------------------------------

    internal fun gestureTap(x: Float, y: Float, durationMs: Long = GESTURE_TAP_DURATION_MS): AccessibilityActionResult {
        if (x < 0f || y < 0f) return AccessibilityActionResult(false, getString(R.string.accessibility_invalid_coordinates))
        return dispatchGestureInternal(Path().apply { moveTo(x, y) }, durationMs.coerceIn(1L, 10000L))
    }

    internal fun gestureLongPress(x: Float, y: Float): AccessibilityActionResult =
        dispatchGestureInternal(Path().apply { moveTo(x, y) }, GESTURE_LONG_PRESS_DURATION_MS)

    internal fun gestureSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = GESTURE_SWIPE_DURATION_MS
    ): AccessibilityActionResult {
        if (x1 < 0f || y1 < 0f || x2 < 0f || y2 < 0f) {
            return AccessibilityActionResult(false, getString(R.string.accessibility_invalid_coordinates))
        }
        return dispatchGestureInternal(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs.coerceIn(1L, 10000L))
    }

    /** 在节点 bounds 中心执行坐标点击（作为 ACTION_CLICK 无响应时的回退）。 */
    internal fun tapAtBounds(node: AccessibilityNodeInfo): AccessibilityActionResult {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return AccessibilityActionResult(false, getString(R.string.accessibility_element_bounds_empty))
        return gestureTap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /** 在节点 bounds 内沿指定方向滑动一段距离（列表项内滑动或手势翻页）。 */
    internal fun swipeAtBounds(
        node: AccessibilityNodeInfo,
        direction: String,
        durationMs: Long = GESTURE_SWIPE_DURATION_MS
    ): AccessibilityActionResult {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return AccessibilityActionResult(false, getString(R.string.accessibility_element_bounds_empty))
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val delta = minOf(bounds.width(), bounds.height()).coerceAtLeast(120).toFloat() * 0.5f
        return when (direction.lowercase()) {
            "up", "backward", "previous" -> gestureSwipe(cx, cy, cx, cy - delta, durationMs)
            "down", "forward", "next" -> gestureSwipe(cx, cy, cx, cy + delta, durationMs)
            "left" -> gestureSwipe(cx, cy, cx - delta, cy, durationMs)
            "right" -> gestureSwipe(cx, cy, cx + delta, cy, durationMs)
            else -> AccessibilityActionResult(false, getString(R.string.accessibility_scroll_directions))
        }
    }

    private fun dispatchGestureInternal(path: Path, durationMs: Long): AccessibilityActionResult {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<AccessibilityActionResult>()
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result.set(AccessibilityActionResult(true, getString(R.string.accessibility_gesture_completed)))
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result.set(AccessibilityActionResult(false, getString(R.string.accessibility_gesture_cancelled)))
                    latch.countDown()
                }
            },
            null
        )
        if (!latch.await(GESTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return AccessibilityActionResult(false, getString(R.string.accessibility_gesture_timeout))
        }
        return result.get() ?: AccessibilityActionResult(false, getString(R.string.accessibility_gesture_no_result))
    }

    // ------------------------------------------------------------------
    // 交互元素编号：与 snapshot().interactive 完全相同的遍历与跳过规则。
    // 编号在两次调用之间可能因界面变化而失效，操作前应重新读取 ui_tree。
    // ------------------------------------------------------------------

    private fun isInteractiveNode(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isEditable || node.isScrollable

    /** 按窗口层序 BFS 收集可交互元素（自身可交互且祖先不含可交互节点），返回带稳定编号的紧凑列表。 */
    private fun collectInteractive(
        windows: List<android.view.accessibility.AccessibilityWindowInfo>,
        maxInteractive: Int
    ): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        val limit = maxInteractive.coerceIn(1, MAX_INTERACTIVE)
        for (window in windows) {
            val root = window.root ?: continue
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
            queue.add(root to false)
            while (queue.isNotEmpty() && out.size < limit) {
                val (node, ancestorInteractive) = queue.removeFirst()
                val interactive = isInteractiveNode(node)
                if (interactive && !ancestorInteractive) {
                    out += mapOf(
                        "index" to out.size,
                        "role" to node.interactiveRole(),
                        "text" to node.safeText(),
                        "content_description" to node.contentDescription?.toString().orEmpty().take(MAX_TEXT_CHARS),
                        "view_id" to node.viewIdResourceName.orEmpty(),
                        "class" to node.className?.toString().orEmpty(),
                        "bounds" to node.boundsMap()
                    )
                }
                val nextAncestor = ancestorInteractive || interactive
                repeat(node.childCount) { childIndex ->
                    node.getChild(childIndex)?.let { queue.add(it to nextAncestor) }
                }
            }
            if (out.size >= limit) break
        }
        return out
    }

    /** 按 interactive 编号查找节点：编号规则与 collectInteractive 一致。 */
    private fun findNodeByIndex(index: Int): AccessibilityNodeInfo? {
        if (index < 0) return null
        val roots = windows.orEmpty().sortedByDescending { it.layer }.mapNotNull { it.root }
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        roots.forEach { queue.add(it to false) }
        var seen = 0
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val (node, ancestorInteractive) = queue.removeFirst()
            val interactive = isInteractiveNode(node)
            if (interactive && !ancestorInteractive) {
                if (seen == index) return node
                seen++
            }
            val nextAncestor = ancestorInteractive || interactive
            repeat(node.childCount) { childIndex ->
                node.getChild(childIndex)?.let { queue.add(it to nextAncestor) }
            }
        }
        return null
    }

    /** 等待界面稳定：轮询窗口树签名，连续 minStableMs 不变视为稳定。 */
    internal fun waitForStable(timeoutMs: Long, minStableMs: Long = WAIT_MIN_STABLE_MS): Map<String, Any> {
        val timeout = timeoutMs.coerceIn(100L, 15000L)
        val minStable = minStableMs.coerceIn(50L, timeout)
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeout
        var last = windowSignature()
        var stableSince = startedAt
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(WAIT_POLL_MS)
            val now = System.currentTimeMillis()
            val current = windowSignature()
            if (current == last) {
                if (now - stableSince >= minStable) {
                    return mapOf("stable" to true, "elapsed_ms" to (now - startedAt))
                }
            } else {
                last = current
                stableSince = now
            }
        }
        return mapOf("stable" to false, "elapsed_ms" to (System.currentTimeMillis() - startedAt))
    }

    /** 轻量窗口树签名：有限预算内收集 (class, text, 可交互标志) 拼接。 */
    private fun windowSignature(): String {
        val parts = mutableListOf<String>()
        var budget = 120
        for (window in windows.orEmpty().sortedByDescending { it.layer }) {
            val root = window.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && budget-- > 0) {
                val node = queue.removeFirst()
                parts += "${node.className?.toString().orEmpty()}|${node.safeText()}|${node.isClickable}|${node.isEditable}|${node.isScrollable}"
                repeat(node.childCount) { index -> node.getChild(index)?.let(queue::add) }
            }
            if (budget <= 0) break
        }
        return parts.joinToString(";")
    }

    private fun AccessibilityNodeInfo.interactiveRole(): String = buildList {
        if (isClickable) add("clickable")
        if (isEditable) add("editable")
        if (isScrollable) add("scrollable")
    }.joinToString("+")

    private fun AccessibilityNodeInfo.boundsMap(): Map<String, Any> {
        val bounds = android.graphics.Rect().also(::getBoundsInScreen)
        return mapOf("left" to bounds.left, "top" to bounds.top, "right" to bounds.right, "bottom" to bounds.bottom)
    }

    private fun findNode(selector: String, field: String, exact: Boolean): AccessibilityNodeInfo? {
        val needle = selector.trim()
        if (needle.isEmpty()) return null
        return findFirst { node ->
            val candidates = when (field.lowercase()) {
                "text" -> listOf(node.safeText())
                "description", "content_description" -> listOf(node.contentDescription?.toString().orEmpty())
                "view_id", "id" -> listOf(node.viewIdResourceName.orEmpty())
                "class", "class_name" -> listOf(node.className?.toString().orEmpty())
                else -> listOf(
                    node.safeText(),
                    node.contentDescription?.toString().orEmpty(),
                    node.viewIdResourceName.orEmpty()
                )
            }
            candidates.any { value ->
                if (exact) value.equals(needle, ignoreCase = true)
                else value.contains(needle, ignoreCase = true)
            }
        }
    }

    private inline fun findFirst(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val roots = windows.orEmpty().sortedByDescending { it.layer }.mapNotNull { it.root }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { roots.forEach(::add) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::add) }
        }
        return null
    }

    private fun AccessibilityNodeInfo.toSnapshot(depth: Int, windowId: Int): Map<String, Any?> = buildMap {
        put("window_id", windowId)
        put("depth", depth)
        put("class", className?.toString().orEmpty())
        put("text", safeText())
        put("content_description", contentDescription?.toString().orEmpty().take(MAX_TEXT_CHARS))
        put("view_id", viewIdResourceName.orEmpty())
        put("clickable", isClickable)
        put("editable", isEditable)
        put("scrollable", isScrollable)
        put("enabled", isEnabled)
        put("checked", isChecked)
        put("selected", isSelected)
        put("password", isPassword)
        val bounds = android.graphics.Rect().also(::getBoundsInScreen)
        put("bounds", mapOf("left" to bounds.left, "top" to bounds.top, "right" to bounds.right, "bottom" to bounds.bottom))
    }

    private fun AccessibilityNodeInfo.safeText(): String =
        if (isPassword) "<redacted>" else text?.toString().orEmpty().take(MAX_TEXT_CHARS)

    private fun AccessibilityNodeInfo.summary(): Map<String, Any?> = mapOf(
        "text" to safeText(),
        "content_description" to contentDescription?.toString().orEmpty().take(MAX_TEXT_CHARS),
        "view_id" to viewIdResourceName.orEmpty(),
        "class" to className?.toString().orEmpty()
    )

    internal companion object {
        @Volatile
        var instance: NekobotAccessibilityService? = null
            private set

        const val DEFAULT_MAX_NODES = 250
        const val MAX_NODES = 800
        const val MAX_INTERACTIVE = 100
        private const val MAX_DEPTH = 30
        private const val MAX_TEXT_CHARS = 500
        private const val SCREENSHOT_TIMEOUT_SECONDS = 5L
        private const val GESTURE_TAP_DURATION_MS = 80L
        private const val GESTURE_LONG_PRESS_DURATION_MS = 600L
        private const val GESTURE_SWIPE_DURATION_MS = 300L
        private const val GESTURE_TIMEOUT_SECONDS = 5L
        private const val WAIT_POLL_MS = 150L
        private const val WAIT_MIN_STABLE_MS = 300L
    }
}

internal data class AccessibilitySnapshot(
    val packageName: String,
    val windowCount: Int,
    val nodes: List<Map<String, Any?>>,
    /** 可交互元素编号列表（index/role/text/view_id/bounds），供点击、输入、滚动按编号定位。 */
    val interactive: List<Map<String, Any?>> = emptyList(),
    val truncated: Boolean
)

internal data class AccessibilityActionResult(
    val success: Boolean,
    val message: String,
    val matched: Map<String, Any?>? = null,
    val metadata: Map<String, Any?> = emptyMap()
)
