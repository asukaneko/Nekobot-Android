package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ctrl+Enter 发送快捷键的判定规则。 */
class ChatSendShortcutTest {

    private fun isSend(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        ctrl: Boolean = false,
        meta: Boolean = false,
        repeat: Boolean = false
    ) = isCtrlEnterSendShortcut(key, type, ctrlPressed = ctrl, metaPressed = meta, repeat = repeat)

    @Test
    fun `Ctrl+Enter 触发发送`() {
        assertTrue(isSend(Key.Enter, ctrl = true))
    }

    @Test
    fun `小键盘回车同样触发发送`() {
        assertTrue(isSend(Key.NumPadEnter, ctrl = true))
    }

    @Test
    fun `外接键盘 Command+Enter 也触发发送`() {
        assertTrue(isSend(Key.Enter, meta = true))
    }

    @Test
    fun `普通回车保留换行行为`() {
        assertFalse(isSend(Key.Enter))
    }

    @Test
    fun `Ctrl+其他按键不触发发送`() {
        assertFalse(isSend(Key.Spacebar, ctrl = true))
        assertFalse(isSend(Key.A, ctrl = true))
    }

    @Test
    fun `抬起事件不重复发送`() {
        assertFalse(isSend(Key.Enter, type = KeyEventType.KeyUp, ctrl = true))
    }

    @Test
    fun `长按重复事件不重复发送`() {
        assertFalse(isSend(Key.Enter, ctrl = true, repeat = true))
    }
}
