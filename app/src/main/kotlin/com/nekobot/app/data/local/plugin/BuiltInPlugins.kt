package com.nekobot.app.data.local.plugin

import com.nekobot.app.data.local.LocalCommandAction

/** APK 随附的命令插件。它们可停用但不能被 ZIP 插件覆盖或卸载。 */
internal object BuiltInPlugins {
    const val JM_ID = "builtin.jm"
    const val LIGHT_NOVEL_ID = "builtin.light-novel"

    private data class Definition(
        val plugin: InstalledPlugin,
        val actions: Map<String, LocalCommandAction>
    )

    private val definitions = listOf(
        Definition(
            plugin = InstalledPlugin(
                id = JM_ID,
                name = "JM 漫画",
                version = "1.0",
                author = "Nekobot",
                description = "下载、搜索与浏览 JM 漫画排行。",
                entry = "",
                permissions = emptyList(),
                commands = listOf(
                    PluginCommandManifest(
                        name = "jmrank",
                        usage = "/jmrank [周排行|月排行] [数量]",
                        description = "生成带封面和详情链接的 JM 周榜或月榜 HTML，末尾可加数量（1-150）"
                    ),
                    PluginCommandManifest(
                        name = "jm",
                        usage = "/jm <漫画ID> [--force]",
                        description = "下载漫画并保存为当前会话工作区 PDF"
                    ),
                    PluginCommandManifest(
                        name = "jm_search",
                        usage = "/jm_search <关键词或漫画ID> [数量]",
                        description = "搜索 JM 漫画并生成带封面的 HTML，末尾可加数量（1-150）"
                    )
                ),
                enabled = true,
                installedAt = 0L,
                isBuiltIn = true
            ),
            actions = mapOf(
                "jmrank" to LocalCommandAction.JM_RANK,
                "jm" to LocalCommandAction.JM_DOWNLOAD,
                "jm_search" to LocalCommandAction.JM_SEARCH
            )
        ),
        Definition(
            plugin = InstalledPlugin(
                id = LIGHT_NOVEL_ID,
                name = "轻小说",
                version = "1.0",
                author = "Nekobot",
                description = "搜索、下载与管理 Wenku8 轻小说。",
                entry = "",
                permissions = emptyList(),
                commands = listOf(
                    PluginCommandManifest(
                        name = "findbook",
                        aliases = listOf("fb"),
                        usage = "/findbook <书名>",
                        description = "搜索轻小说并生成卡片网格 HTML"
                    ),
                    PluginCommandManifest(
                        name = "fa",
                        usage = "/fa <作者>",
                        description = "按作者搜索轻小说"
                    ),
                    PluginCommandManifest(
                        name = "select",
                        usage = "/select <编号>",
                        description = "选择要下载的轻小说"
                    ),
                    PluginCommandManifest(
                        name = "info",
                        usage = "/info <编号>",
                        description = "获取轻小说详情"
                    ),
                    PluginCommandManifest(
                        name = "hotnovel",
                        usage = "/hotnovel <day|month> [数量]",
                        description = "获取今日或本月热门轻小说榜单"
                    ),
                    PluginCommandManifest(
                        name = "random_novel",
                        aliases = listOf("rn"),
                        usage = "/random_novel",
                        description = "随机推荐一本轻小说"
                    ),
                    PluginCommandManifest(
                        name = "novel_res",
                        usage = "/novel_res <res值>",
                        description = "根据 Wenku8 编号下载轻小说 TXT"
                    ),
                    PluginCommandManifest(
                        name = "wenku8_login",
                        aliases = listOf("wenku_login"),
                        usage = "/wenku8_login",
                        description = "打开 Wenku8 登录界面并自动保存 Cookie 与 UA"
                    ),
                    PluginCommandManifest(
                        name = "set_wenku_cookie",
                        usage = "/set_wenku_cookie <Cookie> || <UA>",
                        description = "手动更新 Wenku8 的 Cookie"
                    )
                ),
                enabled = true,
                installedAt = 0L,
                isBuiltIn = true
            ),
            actions = mapOf(
                "findbook" to LocalCommandAction.NOVEL_SEARCH,
                "fa" to LocalCommandAction.NOVEL_SEARCH_AUTHOR,
                "select" to LocalCommandAction.NOVEL_SELECT,
                "info" to LocalCommandAction.NOVEL_INFO,
                "hotnovel" to LocalCommandAction.NOVEL_HOT,
                "random_novel" to LocalCommandAction.NOVEL_RANDOM,
                "novel_res" to LocalCommandAction.NOVEL_RES,
                "wenku8_login" to LocalCommandAction.WENKU8_LOGIN,
                "set_wenku_cookie" to LocalCommandAction.NOVEL_SET_COOKIE
            )
        )
    )

    fun installed(isEnabled: (String) -> Boolean): List<InstalledPlugin> =
        definitions.map { definition ->
            definition.plugin.copy(enabled = isEnabled(definition.plugin.id))
        }

    fun isBuiltIn(pluginId: String): Boolean = definitions.any { it.plugin.id == pluginId }

    fun actionFor(pluginId: String, commandName: String): LocalCommandAction? =
        definitions.firstOrNull { it.plugin.id == pluginId }?.actions?.get(commandName)

    fun defaultCommandBindings(): List<PluginCommandBinding> =
        pluginCommandBindings(definitions.map(Definition::plugin))

    fun findDefaultCommand(input: String): PluginCommandBinding? {
        val normalized = PluginManifestValidator.normalizeCommand(input)
        return defaultCommandBindings().firstOrNull { it.trigger == normalized }
    }

    fun defaultCommandSuggestions(query: String): List<PluginCommandBinding> {
        val normalized = query.trim().lowercase()
        return defaultCommandBindings().filter { binding ->
            normalized.isBlank() || normalized == "/" ||
                binding.trigger.startsWith(normalized) ||
                binding.trigger.contains(normalized.removePrefix("/"))
        }
    }

    fun reservedCommandAliases(): Set<String> =
        pluginCommandBindings(definitions.map(Definition::plugin), includeDisabled = true)
            .flatMap { listOf(it.trigger) + it.aliases }
            .toSet()
}
