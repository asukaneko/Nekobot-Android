package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.plugin.InstalledPlugin
import com.nekobot.app.data.local.plugin.PluginManager
import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * plugin_use：Agent 的插件管理工具。
 *
 * 与 browser_use 类似，通过 action 驱动不同行为：查看（list/view/help）、
 * 创建安装（create/install_url）、修改（update）、启停/卸载（enable/disable/uninstall）
 * 以及沙盒测试（execute）。
 *
 * 高风险动作（install_url 安装第三方代码、uninstall 删除插件）复用
 * [LocalExecAuthorizationManager] 的用户确认流程；其余操作在插件
 * WebView 沙盒和清单权限体系内保持可逆，直接执行。
 */
internal class LocalPluginTool(
    private val sessionId: String,
    private val authorizationManager: LocalExecAuthorizationManager,
    private val onConfirmationRequired: (ExecConfirmationRequest) -> Unit
) {
    private val gson = Gson()

    suspend fun execute(args: Map<String, Any>): Map<String, Any> {
        val action = args.string("action").trim().lowercase(Locale.ROOT)
        if (action.isBlank()) return failure("plugin_use 缺少 action")
        val pluginManager = runCatching { ServiceContainer.pluginManager }.getOrNull()
            ?: return failure("插件管理器不可用")
        return try {
            when (action) {
                "list" -> listPlugins(pluginManager)
                "view" -> viewPlugin(pluginManager, args)
                "help" -> help()
                "create" -> createPlugin(pluginManager, args)
                "install_url" -> installFromUrl(pluginManager, args)
                "update" -> updatePlugin(pluginManager, args)
                "enable", "disable" -> setPluginEnabled(pluginManager, action == "enable", args)
                "uninstall" -> uninstallPlugin(pluginManager, args)
                "execute" -> executePluginCommand(pluginManager, args)
                else -> failure(
                    "未知 action：$action（支持 list、view、help、create、install_url、" +
                        "update、enable、disable、uninstall、execute）"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e.message ?: "插件工具执行失败")
        }
    }

    // ---- 查看类动作 ----

    private fun listPlugins(pluginManager: PluginManager): Map<String, Any> {
        val plugins = pluginManager.installed.value
        if (plugins.isEmpty()) {
            return success(
                "count" to 0,
                "plugins" to emptyList<Any>(),
                "hint" to "还没有安装插件；先用 help 阅读开发指南，再用 create 创建"
            )
        }
        return success(
            "count" to plugins.size,
            "plugins" to plugins.map(::pluginSummary),
            "hint" to "view 可查看清单与源码；update 可修改；execute 可测试命令"
        )
    }

    private fun viewPlugin(pluginManager: PluginManager, args: Map<String, Any>): Map<String, Any> {
        val pluginId = args.string("plugin_id").trim()
        if (pluginId.isBlank()) return failure("view 需要 plugin_id")
        val plugin = pluginManager.installed.value.firstOrNull { it.id == pluginId }
            ?: return failure("插件不存在：$pluginId（可先 list 查看已安装插件）")
        if (plugin.isBuiltIn) {
            return success(
                "plugin" to pluginSummary(plugin),
                "builtin" to true,
                "note" to "内置插件复用 APK 内的原生命令处理器，没有可查看或修改的 JS 源码"
            )
        }
        val detail = pluginManager.readPluginDetail(pluginId)
            ?: return failure("无法读取插件文件：$pluginId")
        val source = detail.entrySource.orEmpty()
        val maxChars = args.int("max_chars", 20000).coerceIn(1000, 120000)
        val truncated = source.length > maxChars
        return success(
            "plugin" to pluginSummary(plugin),
            "manifest_json" to detail.manifestJson,
            "entry" to plugin.entry,
            "source" to source.take(maxChars),
            "truncated" to truncated,
            "total_chars" to source.length,
            "hint" to if (truncated) {
                "源码已截断（${source.length} 字符只返回前 $maxChars）；如需完整源码请提高 max_chars"
            } else {
                "修改请用 update（manifest_json / main_js）"
            }
        )
    }

    private fun help(): Map<String, Any> = success("content" to PLUGIN_DEV_GUIDE)

    // ---- 创建 / 安装 ----

    private suspend fun createPlugin(
        pluginManager: PluginManager,
        args: Map<String, Any>
    ): Map<String, Any> {
        val manifestJson = args.string("manifest_json").trim()
        if (manifestJson.isBlank()) return failure("create 需要 manifest_json（完整 plugin.json 内容）")
        val entrySource = (args["main_js"] as? String).orEmpty()
        if (entrySource.isBlank()) return failure("create 需要 main_js（入口 main.js 源码）")
        val extraFiles = parseExtraFiles(args.string("extra_files_json"))
        val replaced = pluginManager.installed.value.firstOrNull { it.id == extractManifestId(manifestJson) }
        val plugin = pluginManager.installFromSource(manifestJson, entrySource, extraFiles)
        return success(
            "message" to "插件已创建并安装：${plugin.name}（${plugin.id}）" +
                if (replaced != null) "，已覆盖更新同名旧插件" else "",
            "plugin" to pluginSummary(plugin),
            "note" to "插件默认启用，用户可在输入框使用其斜杠命令；建议立即用 execute 测试"
        )
    }

    private suspend fun installFromUrl(
        pluginManager: PluginManager,
        args: Map<String, Any>
    ): Map<String, Any> {
        val url = args.string("url").trim()
        if (url.isBlank()) return failure("install_url 需要 url")
        if (!url.startsWith("https://", ignoreCase = true)) {
            return failure("install_url 只支持 https:// 地址")
        }
        val authorization = authorizationManager.requestAuthorization(
            sessionId = sessionId,
            command = "plugin_use install_url: $url",
            mainCommand = "plugin_use",
            onRequest = onConfirmationRequired
        )
        if (authorization == ExecAuthorization.Reject) {
            return failure("用户拒绝安装第三方插件", "rejected" to true)
        }
        val plugin = pluginManager.installFromUrl(url, acceptedThirdPartyAgreement = true)
        return success(
            "message" to "插件已下载并安装：${plugin.name}（${plugin.id}）",
            "plugin" to pluginSummary(plugin)
        )
    }

    // ---- 修改 / 启停 / 卸载 ----

    private suspend fun updatePlugin(
        pluginManager: PluginManager,
        args: Map<String, Any>
    ): Map<String, Any> {
        val pluginId = args.string("plugin_id").trim()
        if (pluginId.isBlank()) return failure("update 需要 plugin_id")
        val manifestJson = args.string("manifest_json").trim().takeIf { it.isNotEmpty() }
        val entrySource = (args["main_js"] as? String)?.takeIf { it.isNotEmpty() }
        val extraFiles = parseExtraFiles(args.string("extra_files_json"))
        if (manifestJson == null && entrySource == null && extraFiles.isEmpty()) {
            return failure("update 至少提供 manifest_json、main_js 或 extra_files_json 之一")
        }
        val plugin = pluginManager.updatePlugin(pluginId, manifestJson, entrySource, extraFiles)
        return success(
            "message" to "插件已更新：${plugin.name}（${plugin.id}）",
            "plugin" to pluginSummary(plugin),
            "note" to "建议用 execute 重新测试命令"
        )
    }

    private suspend fun setPluginEnabled(
        pluginManager: PluginManager,
        enabled: Boolean,
        args: Map<String, Any>
    ): Map<String, Any> {
        val action = if (enabled) "enable" else "disable"
        val pluginId = args.string("plugin_id").trim()
        if (pluginId.isBlank()) return failure("$action 需要 plugin_id")
        val existing = pluginManager.installed.value.firstOrNull { it.id == pluginId }
            ?: return failure("插件不存在：$pluginId")
        pluginManager.setEnabled(pluginId, enabled)
        return success(
            "plugin_id" to pluginId,
            "name" to existing.name,
            "enabled" to enabled,
            "message" to "插件已${if (enabled) "启用" else "停用"}：${existing.name}"
        )
    }

    private suspend fun uninstallPlugin(
        pluginManager: PluginManager,
        args: Map<String, Any>
    ): Map<String, Any> {
        val pluginId = args.string("plugin_id").trim()
        if (pluginId.isBlank()) return failure("uninstall 需要 plugin_id")
        val plugin = pluginManager.installed.value.firstOrNull { it.id == pluginId }
            ?: return failure("插件不存在：$pluginId")
        if (plugin.isBuiltIn) return failure("内置插件不能卸载，只能停用")
        val authorization = authorizationManager.requestAuthorization(
            sessionId = sessionId,
            command = "plugin_use uninstall: $pluginId（${plugin.name}）",
            mainCommand = "plugin_use",
            onRequest = onConfirmationRequired
        )
        if (authorization == ExecAuthorization.Reject) {
            return failure("用户拒绝卸载插件：$pluginId", "rejected" to true)
        }
        pluginManager.uninstall(pluginId)
        return success(
            "plugin_id" to pluginId,
            "message" to "插件已卸载：${plugin.name}"
        )
    }

    // ---- 沙盒测试 ----

    private suspend fun executePluginCommand(
        pluginManager: PluginManager,
        args: Map<String, Any>
    ): Map<String, Any> {
        val pluginId = args.string("plugin_id").trim()
        if (pluginId.isBlank()) return failure("execute 需要 plugin_id")
        val command = args.string("command").trim()
        if (command.isBlank()) return failure("execute 需要 command（命令名，不带 /）")
        val commandArgs = args.string("args")
        val (plugin, binding) = pluginManager.findPluginCommand(pluginId, command)
            ?: return failure(
                "插件 $pluginId 没有命令 /${command.removePrefix("/")}；" +
                    "可先 view 查看清单中注册的命令"
            )
        if (plugin.isBuiltIn) {
            return failure("内置插件命令复用原生处理器，不支持在此测试；请让用户直接输入 ${binding.trigger}")
        }
        if (!plugin.enabled) {
            return failure("插件已停用：$pluginId（先用 enable 启用）")
        }
        val repository = runCatching { ServiceContainer.localRepository }.getOrNull()
            ?: return failure("本地仓库不可用")
        val startedAt = System.currentTimeMillis()
        val output = pluginManager.execute(binding, sessionId, commandArgs, repository)
        return success(
            "plugin_id" to pluginId,
            "command" to binding.trigger,
            "args" to commandArgs,
            "output" to output,
            "duration_ms" to (System.currentTimeMillis() - startedAt),
            "note" to "这是插件命令的真实运行结果，与其作为斜杠命令执行时一致"
        )
    }

    // ---- 辅助 ----

    private fun pluginSummary(plugin: InstalledPlugin): Map<String, Any> = mapOf(
        "plugin_id" to plugin.id,
        "name" to plugin.name,
        "version" to plugin.version,
        "author" to plugin.author.ifBlank { "未知" },
        "description" to plugin.description,
        "enabled" to plugin.enabled,
        "builtin" to plugin.isBuiltIn,
        "permissions" to plugin.permissions,
        "commands" to plugin.commands.map { command ->
            mapOf(
                "command" to "/${command.name}",
                "aliases" to command.aliases,
                "usage" to command.usage,
                "description" to command.description
            )
        },
        "installed_at" to (plugin.installedAt.takeIf { it > 0 }?.let(::formatTimestamp) ?: "")
    )

    /** 从 manifest_json 文本中尽力提取 id，仅用于提示是否覆盖旧插件。 */
    private fun extractManifestId(manifestJson: String): String? =
        runCatching { gson.fromJson(manifestJson, Map::class.java)["id"]?.toString() }.getOrNull()

    private fun parseExtraFiles(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val parsed = runCatching { gson.fromJson(raw, Map::class.java) }.getOrNull()
            ?: throw IllegalArgumentException("extra_files_json 不是有效的 JSON 对象")
        @Suppress("UNCHECKED_CAST")
        val map = parsed as? Map<String, Any>
            ?: throw IllegalArgumentException("extra_files_json 必须是 JSON 对象（路径 → 文件内容）")
        return map.mapValues { (_, value) ->
            (value as? String)
                ?: throw IllegalArgumentException("extra_files_json 的文件内容必须是字符串：$value")
        }
    }

    private fun formatTimestamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun Map<String, Any>.string(key: String): String = this[key]?.toString().orEmpty()

    private fun Map<String, Any>.int(key: String, default: Int): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

    private fun success(vararg values: Pair<String, Any>): Map<String, Any> =
        buildMap {
            put("success", true)
            values.forEach { (key, value) -> put(key, value) }
        }

    private fun failure(message: String, vararg values: Pair<String, Any>): Map<String, Any> =
        buildMap {
            put("success", false)
            put("error", message)
            values.forEach { (key, value) -> put(key, value) }
        }

    private companion object {
        /** 插件开发指南；help 动作返回，内容与 PluginManager 的 JS 运行时保持一致。 */
        val PLUGIN_DEV_GUIDE: String = """
            Nekobot 插件开发指南（api_version 1）

            一、插件包结构
            插件由 plugin.json（清单）和入口 JS 文件（默认 main.js）组成，可选附加文件。安装后插件以斜杠命令形式扩展本地会话功能（如 /note）。插件运行在无网络、无文件访问的沙盒 WebView 中，只能通过清单声明的权限调用受控 API。

            plugin.json 必填字段：
            - api_version: 固定为 1
            - id: 插件唯一标识，2-64 位、字母开头，可含数字、点、下划线、连字符；不能与已安装插件或内置插件（builtin.jm、builtin.light-novel）冲突
            - name: 插件名（≤80 字符）
            - version: 版本号（≤32 字符）
            - commands: 命令数组，至少 1 条、最多 64 条，每条 {"name": "...", "aliases": [...], "usage": "/cmd <参数>", "description": "..."}
              - name：1-32 位、字母开头，可含数字、下划线、连字符，不带 /
              - aliases：可选别名，最多 8 个，命名规则同 name
              - 命令名不能与内置斜杠命令或其他插件的命令冲突
            可选字段：
            - author、description
            - entry: 入口文件，默认 "main.js"，必须是安全的 .js 相对路径
            - permissions: 权限数组，可用值：storage（键值存储）、chat.read（读取会话与消息）、notify（Toast 通知）、network（HTTPS GET）。调用未声明权限的 API 会失败。

            二、入口 JS 运行时
            - 用 NekoPlugin.registerCommand(name, handler) 或 NekoPlugin.register({commands: {name: handler}}) 注册命令
            - handler(ctx) 可以是 async 函数；返回值（字符串或可 JSON 序列化的对象）作为该命令的回复
            - ctx 字段：command（命令名，不带 /）、args（按空白切分的参数数组）、argsText（原始参数文本）、raw（完整输入）、sessionId
            - API（全部返回 Promise，需 await，且需声明对应权限）：
              await ctx.api.getSession()            // 当前会话信息（chat.read）
              await ctx.api.getMessages(limit)      // 最近消息，默认 30、最大 100（chat.read）
              await ctx.api.notify(message)         // Toast 提示（notify）
              await ctx.api.httpGet(url)            // 仅 https://，返回 {status, body}，body 上限 512KB（network）
              await ctx.api.storage.get(key)        // 读存储，返回 JSON 值或 null（storage）
              await ctx.api.storage.set(key, value) // 写存储（storage）
              await ctx.api.storage.remove(key)     // 删除键（storage）
              await ctx.api.storage.list()          // 返回 {key: value}（storage）
            - 限制：单次执行超时 20 秒；回复上限 20000 字符；没有 fetch/XHR/DOM 存储；storage key ≤128 字符且不能含换行

            三、最小示例
            plugin.json：
            {"api_version": 1, "id": "demo.notes", "name": "随手记", "version": "1.0.0", "author": "Agent", "description": "保存和查看笔记", "entry": "main.js", "permissions": ["storage"], "commands": [{"name": "note", "usage": "/note <内容>", "description": "保存一条笔记"}, {"name": "notes", "usage": "/notes", "description": "查看全部笔记"}]}
            main.js：
            NekoPlugin.register({
              commands: {
                note: async function (ctx) {
                  if (!ctx.argsText) return "用法：/note <内容>";
                  await ctx.api.storage.set("note:" + Date.now(), ctx.argsText);
                  return "已保存笔记。";
                },
                notes: async function (ctx) {
                  const all = await ctx.api.storage.list();
                  const keys = Object.keys(all).filter(function (k) { return k.indexOf("note:") === 0; }).sort();
                  if (!keys.length) return "还没有笔记。";
                  return keys.map(function (k) { return "- " + all[k]; }).join("\n");
                }
              }
            });

            四、推荐流程
            1. help 阅读本指南 → 2. create 生成插件（manifest_json + main_js，多文件用 extra_files_json）→ 3. execute 测试命令 → 4. 出错时 view 查看实际源码、update 修复 → 5. 交付时告知用户可用的斜杠命令。
            第三方 ZIP 用 install_url 安装（需要用户确认）；不再需要时用 uninstall 卸载（同样需要用户确认）。
        """.trimIndent()
    }
}
