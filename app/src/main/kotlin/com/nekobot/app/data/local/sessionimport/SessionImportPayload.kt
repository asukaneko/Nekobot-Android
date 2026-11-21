package com.nekobot.app.data.local.sessionimport

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 统一构造 nekobot 会话导入载荷。
 *
 * 各格式解析器只负责把自身数据转换成会话对象，最终都由这里包装成
 * `{version, type:"nbot_session_export", sessions:[...]}`，交给
 * `UnifiedRepository.importSessions` 分发到本地 Room 或服务器接口。
 */
object SessionImportPayload {

    const val TYPE = "nbot_session_export"
    const val VERSION = 2

    fun build(sessions: List<JsonObject>, exportedAt: Long = System.currentTimeMillis()): JsonObject =
        JsonObject().apply {
            addProperty("version", VERSION)
            addProperty("type", TYPE)
            addProperty("exported_at", exportedAt)
            add("sessions", JsonArray().apply { sessions.forEach(::add) })
        }
}
