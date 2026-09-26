package com.nekobot.app.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import com.nekobot.app.ui.components.toolNameResId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Agent 悬浮窗：在其他应用上方显示 AI 思考内容与当前工具名。
 *
 * - 可拖动：按住卡片或圆角气泡拖动即可改变位置，位置与折叠状态跨运行保留。
 * - 可折叠：卡片右上角按钮收起为圆角方形气泡，点击气泡重新展开。
 * - 授权确认：Agent 需要用户授权时直接展示「拒绝 / 仅本次 / 始终允许」，无需回到应用。
 * - `FLAG_SECURE`：窗口内容不进入任何截图（包括 Agent 自己的 android_screenshot）。
 * - 截图前 [hideForCaptureBlocking] 临时移除视图；Agent 执行坐标手势前
 *   [setTouchable] 临时取消触摸，保证点击/滑动落到目标应用。
 *
 * 所有视图操作都在主线程执行；[setVisible] / [update] 由前台服务在主线程调用。
 */
internal class AgentOverlayWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var root: View? = null
    private var card: View? = null
    private var bubble: View? = null
    private var bubbleDot: View? = null
    private var confirmView: View? = null
    private var confirmTextView: TextView? = null
    private var confirmAlwaysButton: View? = null
    private var toolView: TextView? = null
    private var bodyView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var desiredVisible = false
    private var hiddenForCapture = false
    private var touchable = true
    private var collapsed = false
    private var placementRestored = false
    private var currentConfirmation: ExecConfirmationRequest? = null
    private var confirmationResponder: ((ExecConfirmationRequest, ExecAuthorization) -> Unit)? = null

    private var lastToolName: String? = null
    private var lastToolRunning = false
    private var lastText = ""

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var windowStartX = 0
    private var windowStartY = 0
    private var dragging = false

    /** 服务根据「运行中 + 应用后台 + 开关 + 权限」计算出的期望可见性。 */
    fun setVisible(visible: Boolean) {
        desiredVisible = visible
        if (visible) show() else hide()
    }

    fun update(toolName: String?, toolRunning: Boolean, text: String) {
        lastToolName = toolName
        lastToolRunning = toolRunning
        lastText = text
        render()
    }

    /**
     * 坐标手势执行期间临时取消触摸，避免悬浮窗拦截 Agent 的点击/滑动。
     * 可从任意线程调用。
     */
    fun setTouchable(touchable: Boolean) {
        if (this.touchable == touchable) return
        this.touchable = touchable
        postToMainAndWait { applyWindowFlags() }
    }

    private fun show() {
        if (!desiredVisible || hiddenForCapture || root != null) return
        if (!Settings.canDrawOverlays(context)) return
        val view = LayoutInflater.from(context).inflate(R.layout.agent_overlay_window, null)
        val screenW = screenWidth()
        val margin = dp(8)
        val cardWidth = min(screenW - margin * 2, dp(CARD_MAX_WIDTH_DP)).coerceAtLeast(dp(180))
        for (id in intArrayOf(R.id.agent_overlay_card, R.id.agent_overlay_confirm)) {
            view.findViewById<View>(id)?.let { container ->
                container.layoutParams = container.layoutParams.apply { width = cardWidth }
            }
        }
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenW - cardWidth) / 2).coerceAtLeast(0)
            y = dp(6)
        }
        restorePlacement(windowParams)
        if (runCatching { windowManager.addView(view, windowParams) }.isFailure) return
        root = view
        params = windowParams
        card = view.findViewById(R.id.agent_overlay_card)
        bubble = view.findViewById(R.id.agent_overlay_bubble)
        bubbleDot = view.findViewById(R.id.agent_overlay_bubble_dot)
        confirmView = view.findViewById(R.id.agent_overlay_confirm)
        confirmTextView = view.findViewById(R.id.agent_overlay_confirm_text)
        confirmAlwaysButton = view.findViewById(R.id.agent_overlay_confirm_always)
        toolView = view.findViewById(R.id.agent_overlay_tool)
        bodyView = view.findViewById(R.id.agent_overlay_text)
        attachDrag(card)
        attachDrag(bubble)
        attachDrag(confirmView)
        bubble?.setOnClickListener { setCollapsed(false) }
        view.findViewById<View>(R.id.agent_overlay_collapse)?.setOnClickListener { setCollapsed(true) }
        view.findViewById<View>(R.id.agent_overlay_confirm_reject)
            ?.setOnClickListener { respondCurrent(ExecAuthorization.Reject) }
        view.findViewById<View>(R.id.agent_overlay_confirm_once)
            ?.setOnClickListener { respondCurrent(ExecAuthorization.Once) }
        confirmAlwaysButton?.setOnClickListener { respondCurrent(ExecAuthorization.Always) }
        currentConfirmation?.let(::renderConfirmation)
        applyMode()
        render()
        view.post {
            clampPosition()
            updateWindowLayout()
        }
    }

    /** 移除悬浮窗（保留期望可见性，截图结束后可恢复）。 */
    fun hide() {
        val view = root ?: return
        root = null
        card = null
        bubble = null
        bubbleDot = null
        confirmView = null
        confirmTextView = null
        confirmAlwaysButton = null
        toolView = null
        bodyView = null
        params = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /** 截图前同步移除悬浮窗，可从任意线程调用（主线程直接执行，避免死锁）。 */
    fun hideForCaptureBlocking(timeoutMs: Long = 400L) {
        hiddenForCapture = true
        postToMainAndWait(timeoutMs) { hide() }
    }

    /** 截图结束后按期望可见性恢复显示。 */
    fun restoreAfterCapture() {
        mainHandler.post {
            hiddenForCapture = false
            if (desiredVisible) show()
        }
    }

    // ---- 授权确认 ----

    /** 展示待授权的工具请求：卡片切换为「拒绝 / 仅本次 / 始终允许」按钮。 */
    fun showConfirmation(
        request: ExecConfirmationRequest,
        onRespond: (ExecConfirmationRequest, ExecAuthorization) -> Unit
    ) {
        currentConfirmation = request
        confirmationResponder = onRespond
        renderConfirmation(request)
        applyMode()
    }

    /** 授权请求已被用户（聊天界面/通知/悬浮窗）处理，恢复正常展示。 */
    fun clearConfirmation() {
        if (currentConfirmation == null) return
        currentConfirmation = null
        applyMode()
    }

    private fun renderConfirmation(request: ExecConfirmationRequest) {
        confirmTextView?.text = request.command.take(CONFIRM_TEXT_MAX_CHARS)
        confirmAlwaysButton?.visibility = if (request.memorizable) View.VISIBLE else View.GONE
    }

    private fun respondCurrent(authorization: ExecAuthorization) {
        val request = currentConfirmation ?: return
        // 先收起确认卡片，避免等待回调期间重复点击
        clearConfirmation()
        confirmationResponder?.invoke(request, authorization)
    }

    // ---- 折叠 / 拖动 ----

    private fun setCollapsed(value: Boolean) {
        if (collapsed == value) return
        val view = root ?: return
        val previousWidth = view.width
        collapsed = value
        applyMode()
        view.post {
            val p = params
            if (p != null && previousWidth > 0 && view.width > 0) {
                // 折叠/展开时保持视觉中心不动
                p.x += (previousWidth - view.width) / 2
            }
            clampPosition()
            updateWindowLayout()
            savePlacement()
        }
    }

    /** 三种形态互斥：授权确认 > 折叠气泡 > 信息卡片。 */
    private fun applyMode() {
        val confirming = currentConfirmation != null
        card?.visibility = if (!confirming && !collapsed) View.VISIBLE else View.GONE
        bubble?.visibility = if (!confirming && collapsed) View.VISIBLE else View.GONE
        confirmView?.visibility = if (confirming) View.VISIBLE else View.GONE
    }

    private fun attachDrag(target: View?) {
        target ?: return
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    windowStartX = params?.x ?: 0
                    windowStartY = params?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params?.let { p ->
                            p.x = windowStartX + dx.roundToInt()
                            p.y = windowStartY + dy.roundToInt()
                            clampPosition()
                            updateWindowLayout()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) savePlacement() else v.performClick()
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun clampPosition() {
        val view = root ?: return
        val p = params ?: return
        val screenW = screenWidth()
        val screenH = screenHeight()
        val width = if (view.width > 0) view.width else min(screenW, dp(CARD_MAX_WIDTH_DP))
        val height = if (view.height > 0) view.height else 0
        p.x = p.x.coerceIn(0, (screenW - width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screenH - height).coerceAtLeast(0))
    }

    private fun updateWindowLayout() {
        val view = root ?: return
        val p = params ?: return
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    // ---- 位置持久化 ----

    private fun restorePlacement(target: WindowManager.LayoutParams) {
        if (placementRestored) return
        placementRestored = true
        val parts = ServiceContainer.prefs.agentOverlayPlacement.split(',')
        val x = parts.getOrNull(0)?.toIntOrNull() ?: return
        val y = parts.getOrNull(1)?.toIntOrNull() ?: return
        target.x = x
        target.y = y
        collapsed = parts.getOrNull(2)?.toIntOrNull() == 1
    }

    private fun savePlacement() {
        val p = params ?: return
        runCatching {
            ServiceContainer.prefs.agentOverlayPlacement = "${p.x},${p.y},${if (collapsed) 1 else 0}"
        }
    }

    // ---- 渲染与窗口参数 ----

    private fun render() {
        val tool = toolView ?: return
        val body = bodyView ?: return
        val toolLabel = lastToolName?.let { name ->
            val resId = toolNameResId(name)
            if (resId != 0) localizedString(resId) else name
        }
        tool.text = when {
            toolLabel == null -> localizedString(R.string.agent_overlay_thinking)
            lastToolRunning -> localizedString(R.string.agent_overlay_tool_running, toolLabel)
            else -> localizedString(R.string.agent_overlay_tool_done, toolLabel)
        }
        val excerpt = lastText.trim().takeLast(MAX_TEXT_CHARS).trimStart()
        body.text = excerpt
        body.visibility = if (excerpt.isEmpty()) View.GONE else View.VISIBLE
        bubbleDot?.backgroundTintList = ColorStateList.valueOf(
            if (lastToolRunning) BUBBLE_RUNNING_COLOR else BUBBLE_IDLE_COLOR
        )
    }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SECURE or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

    private fun applyWindowFlags() {
        val p = params ?: return
        p.flags = baseFlags()
        if (root != null) updateWindowLayout()
    }

    private fun postToMainAndWait(timeoutMs: Long = 300L, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            runCatching { block() }
            latch.countDown()
        }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun localizedString(resId: Int, vararg args: Any): String {
        val ctx = ServiceContainer.localizedContext ?: context
        return if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun screenWidth(): Int = screenBounds().first

    private fun screenHeight(): Int = screenBounds().second

    private fun screenBounds(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getSize(point)
            point.x to point.y
        }

    private companion object {
        /** 悬浮窗只展示思考正文的末尾片段。 */
        const val MAX_TEXT_CHARS = 220

        /** 授权确认里展示的命令/请求文本上限。 */
        const val CONFIRM_TEXT_MAX_CHARS = 240
        const val CARD_MAX_WIDTH_DP = 380
        const val BUBBLE_RUNNING_COLOR = 0xFF7DD3FC.toInt()
        const val BUBBLE_IDLE_COLOR = 0xFF94A3B8.toInt()
    }
}

/** 悬浮窗全局句柄：供 Agent 工具在截图 / 坐标手势前临时调整悬浮窗。 */
internal object AgentOverlayRegistry {

    @Volatile
    private var window: AgentOverlayWindow? = null

    fun attach(target: AgentOverlayWindow?) {
        window = target
    }

    fun current(): AgentOverlayWindow? = window
}
