package com.nekobot.app.integration

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
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

    internal fun snapshot(maxNodes: Int = DEFAULT_MAX_NODES): AccessibilitySnapshot {
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
            truncated = truncated
        )
    }

    internal fun click(selector: String, field: String, exact: Boolean): AccessibilityActionResult {
        val node = findNode(selector, field, exact)
            ?: return AccessibilityActionResult(false, getString(R.string.accessibility_element_not_found))
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

    internal fun scroll(direction: String, selector: String?, field: String, exact: Boolean): AccessibilityActionResult {
        val preferred = selector?.takeIf(String::isNotBlank)?.let { findNode(it, field, exact) }
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
        private const val MAX_DEPTH = 30
        private const val MAX_TEXT_CHARS = 500
        private const val SCREENSHOT_TIMEOUT_SECONDS = 5L
    }
}

internal data class AccessibilitySnapshot(
    val packageName: String,
    val windowCount: Int,
    val nodes: List<Map<String, Any?>>,
    val truncated: Boolean
)

internal data class AccessibilityActionResult(
    val success: Boolean,
    val message: String,
    val matched: Map<String, Any?>? = null,
    val metadata: Map<String, Any?> = emptyMap()
)
