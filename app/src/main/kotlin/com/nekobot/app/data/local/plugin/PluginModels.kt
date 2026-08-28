package com.nekobot.app.data.local.plugin

import com.google.gson.annotations.SerializedName
import com.nekobot.app.data.local.LocalCommandAction

/** 插件包的入口清单。插件包必须在根目录提供 plugin.json。 */
data class PluginManifest(
    @SerializedName("api_version") val apiVersion: Int = 1,
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val entry: String = "main.js",
    val permissions: List<String> = emptyList(),
    val commands: List<PluginCommandManifest> = emptyList()
)

/** 插件向 Nekobot 注册的一条斜杠命令。name 不带 /，aliases 可带 /。 */
data class PluginCommandManifest(
    val name: String = "",
    val aliases: List<String> = emptyList(),
    val usage: String = "",
    val description: String = ""
)

/** 提供给界面展示的已安装插件信息。 */
data class InstalledPlugin(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val entry: String,
    val permissions: List<String>,
    val commands: List<PluginCommandManifest>,
    val enabled: Boolean,
    val installedAt: Long,
    val isBuiltIn: Boolean = false
)

/** 供 Agent 插件工具读取的插件详情：清单原文与入口源码。 */
data class PluginDetail(
    val plugin: InstalledPlugin,
    val manifestJson: String,
    val entrySource: String?
)

/** 解析器和运行时使用的命令绑定；trigger 是用户实际输入的命令。 */
internal data class PluginCommandBinding(
    val pluginId: String,
    val name: String,
    val trigger: String,
    val aliases: List<String>,
    val usage: String,
    val description: String,
    /** 内置插件直接复用 APK 内的命令处理器，不进入第三方 JS 运行时。 */
    val builtInAction: LocalCommandAction? = null
)

/** 只返回已启用插件的命令；安装校验可通过 [includeDisabled] 保留停用插件的命令占位。 */
internal fun pluginCommandBindings(
    plugins: Iterable<InstalledPlugin>,
    includeDisabled: Boolean = false
): List<PluginCommandBinding> = plugins
    .asSequence()
    .filter { includeDisabled || it.enabled }
    .flatMap { plugin ->
        plugin.commands.asSequence().flatMap { command ->
            val canonical = PluginManifestValidator.normalizeCommand(command.name)
            val aliases = (listOf(canonical) + command.aliases.map(PluginManifestValidator::normalizeCommand)).distinct()
            aliases.asSequence().map { trigger ->
                PluginCommandBinding(
                    pluginId = plugin.id,
                    name = canonical.removePrefix("/"),
                    trigger = trigger,
                    aliases = aliases,
                    usage = command.usage.ifBlank { canonical },
                    description = command.description.ifBlank { "插件命令" },
                    builtInAction = BuiltInPlugins.actionFor(plugin.id, canonical.removePrefix("/"))
                )
            }
        }
    }
    .toList()
