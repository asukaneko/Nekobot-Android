package com.nekobot.app.data.model

/** 列表查询不携带完整提示词和运行时配置。分页仅限制展示，统计使用独立摘要快照。 */
data class SessionListQuery(
    val filter: String = "ALL",
    val channel: String? = null,
    val characterId: String? = null,
    val search: String = "",
    val includeArchived: Boolean = true,
    val limit: Int = 80,
    val offset: Int = 0
)

data class SessionListPage(val sessions: List<Session>, val hasMore: Boolean, val totalCount: Int)
