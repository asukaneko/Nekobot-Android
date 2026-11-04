package com.nekobot.app.data.local.ai

import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权三档语义的回归（YOLO / yoloExempt / 「始终允许」）。
 *
 * 背景：Android 系列工具（含 `android_ui_tree`、`android_screenshot`）显式传
 * `yoloExempt = false`，即 YOLO 可以放行读屏。这在代码里是有意为之——无障碍自动化
 * 每一步都要看屏幕，每步弹窗会让 YOLO 完全失去意义；同时它们仍然 `memorizable = false`，
 * 不会被「始终允许」永久记住。
 *
 * 本文件把这三种组合钉死，避免以后有人"顺手对齐"成 `yoloExempt = !memorizable`，
 * 让 YOLO 下的自动化每步都弹窗；也避免相反方向：把真正需要逐次确认的入口误放行。
 */
class ExecAuthorizationYoloTest {

    private fun manager(
        timeoutMs: Long = 50L,
        onPersistYolo: ((String, Boolean) -> Unit)? = null
    ) = LocalExecAuthorizationManager(
        authorizationTimeoutMs = timeoutMs,
        savePersistedRules = { _, _ -> },
        savePersistedYoloEnabled = onPersistYolo
    )

    @Test
    fun `yolo lets android screen reads through without prompting`() = runBlocking {
        val auth = manager()
        auth.enableYolo("s1")
        var prompted = 0

        val decision = auth.requestAuthorization(
            sessionId = "s1",
            command = "android_screenshot: capture current screen",
            mainCommand = "android_screenshot",
            memorizable = false,
            yoloExempt = false,
            onRequest = { prompted++ }
        )

        assertEquals(ExecAuthorization.Once, decision)
        assertEquals("YOLO 生效时不应弹窗", 0, prompted)
    }

    @Test
    fun `a yoloExempt request still requires explicit confirmation under yolo`() = runBlocking {
        val auth = manager()
        auth.enableYolo("s1")
        val prompts = mutableListOf<ExecConfirmationRequest>()

        // 超时 50ms：没人应答 → Reject，用来证明"确实在等用户"而不是被 YOLO 放行
        val decision = auth.requestAuthorization(
            sessionId = "s1",
            command = "danger_reader: read everything",
            mainCommand = "danger_reader",
            memorizable = false,
            yoloExempt = true,
            onRequest = { prompts += it }
        )

        assertEquals(ExecAuthorization.Reject, decision)
        assertEquals(1, prompts.size)
    }

    @Test
    fun `always is remembered for memorizable requests`() = runBlocking {
        val auth = manager()
        val command = "android_ui_click: click index=3"
        var prompted = 0

        val first = auth.requestAuthorization(
            sessionId = "s1",
            command = command,
            mainCommand = "android_ui_click",
            memorizable = true,
            yoloExempt = false,
            onRequest = { request ->
                prompted++
                auth.resolve(request.requestId, "s1", ExecAuthorization.Always)
            }
        )
        assertEquals(ExecAuthorization.Always, first)

        val second = auth.requestAuthorization(
            sessionId = "s1",
            command = command,
            mainCommand = "android_ui_click",
            memorizable = true,
            yoloExempt = false,
            onRequest = { prompted++ }
        )
        assertEquals(ExecAuthorization.Always, second)
        assertEquals("第二次应直接命中已记忆的授权", 1, prompted)
    }

    @Test
    fun `non-memorizable requests are never remembered even when answered with always`() = runBlocking {
        val auth = manager()
        val command = "android_screenshot: capture current screen"
        var prompted = 0

        val first = auth.requestAuthorization(
            sessionId = "s1",
            command = command,
            mainCommand = "android_screenshot",
            memorizable = false,
            yoloExempt = false,
            onRequest = { request ->
                prompted++
                auth.resolve(request.requestId, "s1", ExecAuthorization.Always)
            }
        )
        // 本次仍然放行（用户点了「总是允许」，但语义退化为「仅本次」）
        assertEquals(ExecAuthorization.Always, first)

        // 没有 YOLO 时必须再次询问，不能被上一次的 Always 记住
        val second = auth.requestAuthorization(
            sessionId = "s1",
            command = command,
            mainCommand = "android_screenshot",
            memorizable = false,
            yoloExempt = false,
            onRequest = { prompted++ }
        )
        assertEquals(ExecAuthorization.Reject, second)
        assertEquals("不可记忆的请求每次都要问", 2, prompted)
    }

    @Test
    fun `yolo is persisted through the injected hook`() {
        var persisted: Pair<String, Boolean>? = null
        val auth = manager(onPersistYolo = { session, enabled -> persisted = session to enabled })

        auth.enableYolo("s2")
        assertEquals("s2" to true, persisted)
        assertTrue(auth.isYoloEnabled("s2"))

        auth.disableYolo("s2")
        assertEquals("s2" to false, persisted)
        assertFalse(auth.isYoloEnabled("s2"))
    }
}
