package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollToBottomTest {

    @Test
    fun `视口高度无效时不再产生位移`() {
        assertEquals(0f, chatScrollStepPx(viewportPx = 0f, remainingPx = null, frames = 0), 0.001f)
        assertEquals(0f, chatScrollStepPx(viewportPx = -10f, remainingPx = 500f, frames = 3), 0.001f)
    }

    @Test
    fun `剩余距离为零或负时仍保留最小步进`() {
        val zero = chatScrollStepPx(viewportPx = 2000f, remainingPx = 0f, frames = 2)
        val padding = chatScrollStepPx(viewportPx = 2000f, remainingPx = -48f, frames = 2)

        // 底部内边距会让"剩余距离"算成负数，此时不能直接停手，否则永远差最后一点到不了底
        assertTrue(zero > 0f)
        assertEquals(zero, padding, 0.001f)
        assertTrue(zero <= 8f)
    }

    @Test
    fun `末尾未进入视口时按全速逐帧推进`() {
        val viewport = 2000f
        val steady = chatScrollStepPx(viewportPx = viewport, remainingPx = null, frames = 30)

        assertEquals(viewport * 0.42f, steady, 0.001f)
        // 一帧只推进一部分视口，保证是可见的连续滚动而不是一次跳到底部
        assertTrue(steady < viewport / 2f)
    }

    @Test
    fun `起步阶段速度逐渐加满`() {
        val viewport = 2000f
        val first = chatScrollStepPx(viewportPx = viewport, remainingPx = null, frames = 0)
        val middle = chatScrollStepPx(viewportPx = viewport, remainingPx = null, frames = 3)
        val full = chatScrollStepPx(viewportPx = viewport, remainingPx = null, frames = 8)

        assertTrue(first < middle)
        assertTrue(middle < full)
        assertEquals(viewport * 0.42f * 0.38f, first, 0.001f)
    }

    @Test
    fun `末尾进入视口后按剩余距离减速收尾`() {
        val viewport = 2000f
        val far = chatScrollStepPx(viewportPx = viewport, remainingPx = 900f, frames = 30)
        val near = chatScrollStepPx(viewportPx = viewport, remainingPx = 90f, frames = 30)

        assertEquals(900f * 0.34f, far, 0.001f)
        assertEquals(90f * 0.34f, near, 0.001f)
        assertTrue(near < far)
        assertTrue(near < chatScrollStepPx(viewportPx = viewport, remainingPx = null, frames = 30))
    }

    @Test
    fun `剩余距离极小时仍推进至少一像素`() {
        val step = chatScrollStepPx(viewportPx = 2000f, remainingPx = 2f, frames = 30)
        assertTrue(step >= 1f)
    }
}
