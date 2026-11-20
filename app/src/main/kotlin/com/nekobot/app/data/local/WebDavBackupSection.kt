package com.nekobot.app.data.local

/**
 * WebDAV 全量备份/恢复时可选的内容分组。
 *
 * 数据库本身（会话、角色卡、世界书、记忆、知识库等）是一份不可拆分的 SQLite 文件，
 * 因此 [DATABASE] 始终包含在备份与恢复中；其余分组都可以按需勾选。
 */
internal enum class WebDavBackupSection(val id: String, val required: Boolean = false) {

    /** 当前档案的数据库文件：会话、角色卡、世界书、记忆、知识库等（必需）。 */
    DATABASE("database", required = true),

    /** API Key、代理凭据、OAuth 登录信息的加密凭据包。 */
    CREDENTIALS("credentials"),

    /** 应用设置（主题、偏好等）。 */
    APP_SETTINGS("app_settings"),

    /** Agent 全局长期记忆。 */
    GLOBAL_MEMORY("global_memory"),

    /** 立绘、世界书封面、表情包、聊天背景与字体。 */
    MEDIA("media"),

    /** 技能、插件包与插件文件。 */
    EXTENSIONS("extensions"),

    /** 会话工作区文件。 */
    WORKSPACE("workspace"),

    /** 聊天语音等消息音频。 */
    AUDIO("audio"),

    /** Token 用量统计与成就进度。 */
    STATS("stats");

    companion object {
        /** 默认全选：与升级前的全量备份行为一致。 */
        fun defaultSelection(): Set<String> = entries.mapTo(linkedSetOf()) { it.id }

        fun fromId(id: String?): WebDavBackupSection? = entries.firstOrNull { it.id == id }

        /**
         * 解析请求里的勾选结果。
         *
         * - 旧请求（未携带勾选信息）按全量处理，保持兼容；
         * - 显式勾选时始终补上必需的 [DATABASE]。
         */
        fun resolve(ids: Collection<String>?): Set<WebDavBackupSection> {
            if (ids == null) return entries.toSet()
            return ids.mapNotNullTo(linkedSetOf(), ::fromId) + DATABASE
        }

        /** 可移植文件根目录归属的分组。 */
        fun sectionOfCatalogRoot(categoryId: String): WebDavBackupSection = when (categoryId) {
            PortableDataCategory.WORKSPACE.id -> WORKSPACE
            PortableDataCategory.EXTENSIONS.id -> EXTENSIONS
            else -> MEDIA
        }
    }
}
