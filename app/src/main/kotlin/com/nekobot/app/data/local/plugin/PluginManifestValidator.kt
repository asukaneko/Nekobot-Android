package com.nekobot.app.data.local.plugin

import java.util.Locale

/** 插件清单和 ZIP 内路径的纯校验逻辑，避免把不可信文件直接交给运行时。 */
object PluginManifestValidator {
    const val CURRENT_API_VERSION = 1

    val supportedPermissions: Set<String> = setOf(
        "storage",
        "chat.read",
        "notify",
        "network"
    )

    private val pluginIdPattern = Regex("^[a-zA-Z][a-zA-Z0-9._-]{1,63}$")
    private val commandPattern = Regex("^[a-zA-Z][a-zA-Z0-9_-]{0,31}$")

    /** 返回全部问题；空列表表示清单可以安装。 */
    fun validate(
        manifest: PluginManifest,
        reservedCommands: Set<String> = emptySet()
    ): List<String> {
        val errors = mutableListOf<String>()
        if (manifest.apiVersion != CURRENT_API_VERSION) {
            errors += "不支持的插件 API 版本：${manifest.apiVersion}"
        }
        if (!pluginIdPattern.matches(manifest.id)) {
            errors += "插件 id 必须是 2-64 位字母、数字、点、下划线或连字符"
        }
        if (manifest.name.isBlank() || manifest.name.length > 80) {
            errors += "插件名称不能为空且不能超过 80 个字符"
        }
        if (manifest.version.isBlank() || manifest.version.length > 32) {
            errors += "插件版本不能为空且不能超过 32 个字符"
        }
        if (!isSafeRelativePath(manifest.entry) || !manifest.entry.endsWith(".js", ignoreCase = true)) {
            errors += "插件 entry 必须是安全的 .js 相对路径"
        }
        if (manifest.permissions.any { it !in supportedPermissions }) {
            val unknown = manifest.permissions.filter { it !in supportedPermissions }.distinct()
            errors += "未知插件权限：${unknown.joinToString(", ")}"
        }
        if (manifest.commands.isEmpty()) {
            errors += "插件至少需要注册一条命令"
        }
        if (manifest.commands.size > 64) {
            errors += "单个插件最多注册 64 条命令"
        }

        val reserved = reservedCommands.map(::commandToken).toSet()
        val seen = mutableSetOf<String>()
        manifest.commands.forEachIndexed { index, command ->
            val allNames = listOf(command.name) + command.aliases
            if (allNames.isEmpty() || command.name.isBlank()) {
                errors += "第 ${index + 1} 条命令缺少 name"
            }
            allNames.forEach { rawName ->
                val token = commandToken(rawName)
                if (!commandPattern.matches(token)) {
                    errors += "命令名无效：$rawName"
                } else if (!seen.add(token)) {
                    errors += "插件内重复的命令名：/$token"
                } else if (token in reserved) {
                    errors += "命令名已被占用：/$token"
                }
            }
            if (command.usage.length > 160 || command.description.length > 500) {
                errors += "第 ${index + 1} 条命令的 usage 或 description 过长"
            }
            if (command.aliases.size > 8) {
                errors += "每条命令最多 8 个别名"
            }
        }
        return errors.distinct()
    }

    /** 规范化为带 / 的用户命令形式。 */
    fun normalizeCommand(raw: String): String = "/${commandToken(raw)}"

    /** 规范化为不带 / 的命令 token。 */
    fun commandToken(raw: String): String =
        raw.trim().removePrefix("/").lowercase(Locale.ROOT)

    /** 拒绝绝对路径、..、.、反斜杠和 Windows 驱动器路径。 */
    fun isSafeRelativePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/') || normalized.contains(':')) return false
        return normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
    }
}
