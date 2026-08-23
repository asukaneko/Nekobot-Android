package com.nekobot.app.ui.screens.chat

import com.google.gson.GsonBuilder

/** 将工具参数和结果格式化为适合用户阅读的 JSON，保留原始符号而不是 HTML 转义。 */
internal fun formatJsonForDisplay(value: Any): String {
    return runCatching {
        GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create()
            .toJson(value)
    }.getOrElse { value.toString() }
}
