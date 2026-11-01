package com.nekobot.app.ui.screens.chat

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 硬件键盘发送快捷键判定：Ctrl（或外接键盘的 Command）+ 回车，含小键盘回车。
 *
 * 只认按下（KeyDown）且非长按重复事件，避免一次按键发送两次。
 */
internal fun isCtrlEnterSendShortcut(
    key: Key,
    type: KeyEventType,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
    repeat: Boolean = false
): Boolean {
    if (type != KeyEventType.KeyDown) return false
    if (repeat) return false
    if (key != Key.Enter && key != Key.NumPadEnter) return false
    return ctrlPressed || metaPressed
}

/** Compose 按键事件适配：取事件里的键码、修饰键与长按标记。 */
internal fun KeyEvent.isCtrlEnterSendShortcut(): Boolean = isCtrlEnterSendShortcut(
    key = key,
    type = type,
    ctrlPressed = isCtrlPressed,
    metaPressed = isMetaPressed,
    repeat = nativeKeyEvent.repeatCount > 0
)

/**
 * 原生按键事件适配（Activity 的 pre-IME 阶段使用）。
 *
 * 有些输入法会先一步消费硬件键盘事件，Compose 输入框根本收不到按键；
 * 在 pre-IME 阶段拦截才能稳定拿到 Ctrl+Enter。
 */
internal fun isCtrlEnterHardwareShortcut(event: AndroidKeyEvent): Boolean {
    if (event.action != AndroidKeyEvent.ACTION_DOWN) return false
    if (event.repeatCount > 0) return false
    val enter = event.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        event.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    return enter && (event.isCtrlPressed || event.isMetaPressed)
}

/**
 * 聊天输入框的 Ctrl+Enter 直接发送。
 *
 * 用 Preview 阶段消费按键：BasicTextField 自己会在普通回车时插入换行，
 * 只有抢在它之前拿到事件，Ctrl+Enter 才不会既发送又换行。
 * 能否发送交给 [canSend] 实时判断：闭包里读到的可能是上一帧的状态。
 */
internal fun Modifier.onCtrlEnterSend(canSend: () -> Boolean, onSend: () -> Unit): Modifier =
    this.onPreviewKeyEvent { event ->
        if (event.isCtrlEnterSendShortcut() && canSend()) {
            onSend()
            true
        } else {
            false
        }
    }

/** 聊天输入区注册的发送入口。 */
internal class ChatSendShortcutHandler(
    val canSend: () -> Boolean,
    val send: () -> Unit
)

/**
 * 硬件键盘 Ctrl+Enter 的全局兜底入口。
 *
 * Activity 在 pre-IME 阶段先问这里：当前屏幕有聊天输入区、且有可发送草稿时直接发送并吞掉按键。
 * 这样即使输入法吞了按键、或输入框一时没有焦点，快捷键也不会失效。
 */
internal object ChatSendShortcutHost {
    private val handlers = mutableListOf<ChatSendShortcutHandler>()

    /** 注册当前可见输入区的发送入口，返回反注册函数。 */
    fun register(handler: ChatSendShortcutHandler): () -> Unit {
        handlers += handler
        return { handlers.remove(handler) }
    }

    /** 由 Activity 调用：已发送返回 true（按键已被消费，不再交给输入法/输入框）。 */
    fun handleHardwareEnter(event: AndroidKeyEvent): Boolean {
        if (!isCtrlEnterHardwareShortcut(event)) return false
        val handler = handlers.lastOrNull() ?: return false
        if (!handler.canSend()) return false
        handler.send()
        return true
    }
}

/**
 * 把当前输入区的「能否发送 / 发送」注册到 [ChatSendShortcutHost]，离开页面时自动注销。
 *
 * 两个回调都通过 rememberUpdatedState 跟随重组更新，避免窗口期拿到旧的发送逻辑。
 */
@Composable
internal fun RegisterChatSendShortcut(
    sessionId: String,
    canSend: () -> Boolean,
    send: () -> Unit
) {
    val latestCanSend = rememberUpdatedState(canSend)
    val latestSend = rememberUpdatedState(send)
    DisposableEffect(sessionId) {
        val unregister = ChatSendShortcutHost.register(
            ChatSendShortcutHandler(
                canSend = { latestCanSend.value() },
                send = { latestSend.value() }
            )
        )
        onDispose { unregister() }
    }
}
