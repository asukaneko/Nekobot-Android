package com.nekobot.app.ui.screens.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * 跳转前暂存的一句话。
 *
 * 新建会话的页面（例如 Agent 欢迎页）在真正创建会话后把用户输入放进这里，
 * 会话页首屏加载完成时取走并发送，避免「先建会话再跳转」把刚输入的内容丢掉。
 * 只存在内存里：它表达的本来就是「这一次跳转要带过去的一句话」。
 */
object ChatPendingPrompt {

    private val pending = ConcurrentHashMap<String, String>()

    fun put(sessionId: String, text: String) {
        val id = sessionId.trim()
        val content = text.trim()
        if (id.isEmpty() || content.isEmpty()) return
        pending[id] = content
    }

    /** 取走并删除：同一条输入只会发送一次。 */
    fun take(sessionId: String): String? = pending.remove(sessionId.trim())
}
