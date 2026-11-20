package com.nekobot.app.data.model

/** 工作区搜索条目：会话内文件 / 文件夹，供全局搜索定位使用。 */
data class WorkspaceSearchEntry(
    val sessionId: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean = false
)
