package com.nekobot.app.data.local.db

import androidx.room.ColumnInfo

/**
 * Token 用量回补只需要极少数字段。
 *
 * 旧实现用 `SELECT *` 读取全部 assistant 消息，正文、思考卡片、工具调用记录
 * 都会被搬进内存；长会话下同一份数据要读两遍（Room 光标 + 实体），
 * 真机上就死在 CursorWindow 分配里。这里只取记账字段，正文只保留前缀，
 * 足够支撑 fork 去重指纹，不会把整段回复读出来。
 */
data class TokenUsageMessageRow(
    val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val model: String?,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int?,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int?,
    val timestamp: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    val content: String
)
