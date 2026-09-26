package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.plugin.InstalledPlugin
import com.nekobot.app.data.local.plugin.PluginCompatLevel
import com.nekobot.app.data.local.plugin.PluginManager
import com.nekobot.app.data.local.plugin.PluginPortInspector
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
 * 创建安装（create/install_url/install_zip）、修改（update）、启停/卸载（enable/disable/uninstall）
 * 以及沙盒测试（execute）。
 *
 * 高风险动作（install_url / install_zip 安装第三方代码）必须经过
 * [LocalPluginInstallConfirmationManager] 弹出第三方插件同意弹窗（协议 + 权限勾选），
 * 不受 YOLO 影响；uninstall 删除插件复用 [LocalExecAuthorizationManager] 的用户确认流程；
 * 其余操作在插件 WebView 沙盒和清单权限体系内保持可逆，直接执行。
 */
internal class LocalPluginTool(
    private val sessionId: String,
    private val authorizationManager: LocalExecAuthorizationManager,
    private val onConfirmationRequired: (ExecConfirmationRequest) -> Unit,
    /** 安装确认管理器：install_zip 时挂起等待用户勾选协议与权限。 */
    private val installConfirmationManager: LocalPluginInstallConfirmationManager? = null,
    /** 安装确认请求回调：转发到会话界面第三方插件同意弹窗。 */
    private val onInstallConfirmationRequired: (PluginInstallConfirmationRequest) -> Unit = {}
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
                "install_zip" -> installWorkspaceZip(pluginManager, args)
                "update" -> updatePlugin(pluginManager, args)
                "enable", "disable" -> setPluginEnabled(pluginManager, action == "enable", args)
                "uninstall" -> uninstallPlugin(pluginManager, args)
                "execute" -> executePluginCommand(pluginManager, args)
                "inspect" -> inspectPortSource(args)
                "check" -> checkPlugin(pluginManager, args)
                else -> failure(
                    "未知 action：$action（支持 list、view、help、create、install_url、install_zip、" +
                        "update、enable、disable、uninstall、execute、inspect、check）"
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
        val limit = AgentToolLimits.toolOutputChars()
        val maxChars = AgentToolLimits.resolveRequestedMaxChars(args.int("max_chars", 0), limit)
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

    private fun help(): Map<String, Any> {
        // 优先读取打包在 assets 中的完整开发文档；读取失败时回退到内置精简指南。
        val bundled = runCatching { ServiceContainer.appContext }.getOrNull()?.let { appContext ->
            runCatching {
                appContext.assets.open(PLUGIN_GUIDE_ASSET).use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
        }
        val usingBundled = !bundled.isNullOrBlank()
        val content = (if (usingBundled) bundled else PLUGIN_DEV_GUIDE) + PLUGIN_USE_WORKFLOW
        return success(
            "content" to content,
            "guide_source" to (if (usingBundled) PLUGIN_GUIDE_ASSET else "内置精简指南"),
            "hint" to "请先通读本文档与附录的推荐流程，再按流程编写并安装插件；文档会随版本更新"
        )
    }

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
        applyCompat(plugin.id, args)
        return success(
            "message" to "插件已创建并安装：${plugin.name}（${plugin.id}）" +
                if (replaced != null) "，已覆盖更新同名旧插件" else "",
            "plugin" to pluginSummary(pluginManager.installed.value.firstOrNull { it.id == plugin.id } ?: plugin),
            "note" to "插件默认启用，用户可在输入框使用其斜杠命令；建议立即用 execute 测试" +
                "；危险权限（网络/写入/AI）需要用户在插件页授权"
        )
    }

    /**
     * 从 HTTPS 地址安装插件：先下载 ZIP 并读取清单，再弹出第三方插件同意弹窗，
     * 用户勾选协议与启用权限后才安装；不可记忆、不受 YOLO 影响。
     */
    private suspend fun installFromUrl(
        pluginManager: PluginManager,
        args: Map<String, Any>
    ): Map<String, Any> {
        val url = args.string("url").trim()
        if (url.isBlank()) return failure("install_url 需要 url")
        if (!url.startsWith("https://", ignoreCase = true)) {
            return failure("install_url 只支持 https:// 地址")
        }
        val confirmationManager = installConfirmationManager
            ?: return failure("插件安装确认不可用（当前链路不支持用户确认）")
        val zip = runCatching { pluginManager.downloadFromUrl(url) }
            .getOrElse { return failure("插件下载失败：${it.message ?: "未知错误"}") }
        try {
            val manifest = pluginManager.peekManifest(android.net.Uri.fromFile(zip))
                ?: return failure("无法读取插件清单（ZIP 根目录需要有效的 plugin.json）")
            if (manifest.id.isBlank() || manifest.name.isBlank()) {
                return failure("插件清单无效：缺少 id 或 name")
            }
            val decision = confirmationManager.requestConfirmation(
                sessionId = sessionId,
                sourceLabel = url,
                manifest = manifest,
                onRequest = onInstallConfirmationRequired
            )
            if (!decision.approved) {
                return failure(
                    "用户拒绝或未确认安装第三方插件（安装必须由用户在同意弹窗中确认）",
                    "rejected" to true
                )
            }
            val plugin = pluginManager.installZipFile(zip, decision.grantedPermissions)
            applyCompat(plugin.id, args)
            return success(
                "message" to "插件已安装：${plugin.name}（${plugin.id}）",
                "plugin" to pluginSummary(pluginManager.installed.value.firstOrNull { it.id == plugin.id } ?: plugin),
                "granted_permissions" to decision.grantedPermissions.sorted(),
                "note" to "仅用户勾选的权限被授予，其余权限调用会被拒绝；" +
                    "危险权限是否勾选由用户在弹窗中自行决定"
            )
        } finally {
            zip.delete()
        }
    }

    /**
     * 安装会话工作区内的插件 ZIP。
     *
     * 与 install_url 不同：先读取 ZIP 内清单，弹出现有的第三方插件同意弹窗，
     * 由用户勾选协议与启用权限后才落盘安装。此确认不可记忆、不受 YOLO 影响，
     * 用户拒绝或超时则放弃安装。
     */
    private suspend fun installWorkspaceZip(
        pluginManager: PluginManager,
        args: Map<String, Any>
    ): Map<String, Any> {
        val raw = args.string("path").trim()
        if (raw.isBlank()) return failure("install_zip 需要 path（会话工作区内的 .zip 插件包）")
        if (!raw.endsWith(".zip", ignoreCase = true)) {
            return failure("install_zip 只支持 .zip 文件：$raw")
        }
        val appContext = runCatching { ServiceContainer.appContext }.getOrNull()
            ?: return failure("应用上下文不可用")
        val workspace = com.nekobot.app.data.local.LocalWorkspaceStorage
            .resolve(appContext.filesDir, sessionId)
            ?: return failure("会话工作区不可用")
        val zip = resolveWorkspacePath(workspace, raw)
            ?: return failure("路径无效或不在会话工作区内：$raw")
        if (!zip.isFile) return failure("文件不存在：$raw")
        val manifest = pluginManager.peekManifest(android.net.Uri.fromFile(zip))
            ?: return failure("无法读取插件清单（ZIP 根目录需要有效的 plugin.json）")
        if (manifest.id.isBlank() || manifest.name.isBlank()) {
            return failure("插件清单无效：缺少 id 或 name")
        }
        val confirmationManager = installConfirmationManager
            ?: return failure("插件安装确认不可用（当前链路不支持用户确认）")
        val decision = confirmationManager.requestConfirmation(
            sessionId = sessionId,
            sourceLabel = runCatching {
                zip.relativeTo(workspace).path.replace('\\', '/')
            }.getOrDefault(zip.name),
            manifest = manifest,
            onRequest = onInstallConfirmationRequired
        )
        if (!decision.approved) {
            return failure(
                "用户拒绝或未确认安装第三方插件（安装必须由用户在同意弹窗中确认）",
                "rejected" to true
            )
        }
        val plugin = pluginManager.install(
            uri = android.net.Uri.fromFile(zip),
            acceptedThirdPartyAgreement = true,
            grantedPermissions = decision.grantedPermissions
        )
        applyCompat(plugin.id, args)
        return success(
            "message" to "插件已安装：${plugin.name}（${plugin.id}）",
            "plugin" to pluginSummary(pluginManager.installed.value.firstOrNull { it.id == plugin.id } ?: plugin),
            "granted_permissions" to decision.grantedPermissions.sorted(),
            "note" to "仅用户勾选的权限被授予，其余权限调用会被拒绝；" +
                "危险权限是否勾选由用户在弹窗中自行决定"
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
        applyCompat(plugin.id, args)
        return success(
            "message" to "插件已更新：${plugin.name}（${plugin.id}）",
            "plugin" to pluginSummary(pluginManager.installed.value.firstOrNull { it.id == plugin.id } ?: plugin),
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
        if (binding.openPage.isNotBlank()) {
            return success(
                "plugin_id" to pluginId,
                "command" to binding.trigger,
                "open_page" to binding.openPage,
                "output" to "该命令用于打开插件页面，不执行 JS，无法在沙盒中测试；" +
                    "请让用户在 App 内输入 ${binding.trigger} 验证页面入口",
                "note" to "页面入口也出现在「更多 → 扩展功能 → 插件页面」"
            )
        }
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

    // ---- 跨生态移植辅助 ----

    /**
     * 确定性解压 + 生态识别 + 文件清单：把「解压 + 阅读目录」从模型手工操作
     * 变成一步，省 token 且不会踩路径坑。解压目录：工作区 plugin-port/<名称>/。
     */
    private fun inspectPortSource(args: Map<String, Any>): Map<String, Any> {
        val raw = args.string("path").trim()
        if (raw.isBlank()) return failure("inspect 需要 path（会话工作区内的 .zip 文件或目录）")
        val appContext = runCatching { ServiceContainer.appContext }.getOrNull()
            ?: return failure("应用上下文不可用")
        val workspace = com.nekobot.app.data.local.LocalWorkspaceStorage
            .resolve(appContext.filesDir, sessionId)
            ?: return failure("会话工作区不可用")
        val source = resolveWorkspacePath(workspace, raw)
            ?: return failure("路径无效或不在会话工作区内：$raw")
        if (!source.exists()) return failure("路径不存在：$raw")
        val name = source.nameWithoutExtension.ifBlank { source.name }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
            .ifBlank { "plugin" }
        val outputDir = java.io.File(workspace, "plugin-port/$name")
        val inspection = runCatching { PluginPortInspector.inspect(source, outputDir) }
            .getOrElse { return failure("解压或分析失败：${it.message ?: "未知错误"}") }
        return success(
            "ecosystem" to inspection.ecosystem.wire,
            "ecosystem_label" to inspection.ecosystem.label,
            "manifest_file" to inspection.manifestFile.orEmpty(),
            "manifest_json" to inspection.manifestJson.orEmpty()
                .take(AgentToolLimits.toolOutputChars() / 2),
            "extracted_dir" to workspaceRelative(workspace, outputDir),
            "file_count" to inspection.files.size,
            "total_bytes" to inspection.totalBytes,
            "files" to inspection.files.take(200).map { mapOf("path" to it.path, "size" to it.size) },
            "warnings" to inspection.warnings,
            "suggested_permissions" to inspection.suggestedPermissions,
            "next_steps" to "用 workspace_read / grep 阅读源码，按 plugin-development.md 第 10 章改写，" +
                "再用 plugin_use create 安装（页面文件放 extra_files_json）"
        )
    }

    /** 移植后静态自检：清单、页面入口、API 名称与大小限额；不执行插件代码。 */
    private fun checkPlugin(pluginManager: PluginManager, args: Map<String, Any>): Map<String, Any> {
        val pluginId = args.string("plugin_id").trim()
        if (pluginId.isBlank()) return failure("check 需要 plugin_id")
        val plugin = pluginManager.installed.value.firstOrNull { it.id == pluginId }
            ?: return failure("插件不存在：$pluginId（可先 list）")
        if (plugin.isBuiltIn) return failure("内置插件没有可自检的 JS 源码")
        val directory = pluginManager.pluginDirectoryPath(pluginId)
            ?: return failure("插件目录不存在：$pluginId")
        val detail = pluginManager.readPluginDetail(pluginId)
            ?: return failure("无法读取插件清单：$pluginId")
        val result = PluginPortInspector.check(directory, detail.manifestJson)
        return success(
            "plugin_id" to pluginId,
            "ready" to result.ok,
            "manifest_errors" to result.manifestErrors,
            "page_errors" to result.pageErrors,
            "unsupported_apis" to result.unsupportedApis,
            "warnings" to result.warnings,
            "hint" to if (result.ok) {
                "静态自检通过；建议用 execute 测试命令，并告知用户页面入口与权限清单"
            } else {
                "按上面的问题修复后重新 check；不要向用户声称完全兼容"
            }
        )
    }

    /** 记录移植兼容级别（native / ported-full / ported-partial / unsupported）。 */
    private fun applyCompat(pluginId: String, args: Map<String, Any>) {
        val compat = args.string("compat").trim()
        val note = args.string("compat_note").trim()
        if (compat.isBlank() && note.isBlank()) return
        val store = runCatching { ServiceContainer.pluginMetaStore }.getOrNull() ?: return
        store.setMeta(pluginId, PluginCompatLevel.fromWire(compat), note)
    }

    private fun resolveWorkspacePath(workspace: java.io.File, raw: String): java.io.File? {
        var relative = raw.trim().replace('\\', '/')
        if (relative.startsWith("/workspace/")) relative = relative.removePrefix("/workspace/")
        if (relative.startsWith("/") || relative.isBlank()) return null
        if (!com.nekobot.app.data.local.plugin.PluginManifestValidator.isSafeRelativePath(relative)) return null
        val root = workspace.canonicalFile
        val target = java.io.File(root, relative).canonicalFile
        return target.takeIf { it.path.startsWith(root.path + java.io.File.separator) }
    }

    private fun workspaceRelative(workspace: java.io.File, file: java.io.File): String =
        "/workspace/" + runCatching { file.relativeTo(workspace).path.replace('\\', '/') }
            .getOrDefault(file.name)

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
        "compat" to plugin.compat.wire,
        "compat_note" to plugin.compatNote,
        "pages" to plugin.pages.map { page ->
            mapOf(
                "page_id" to page.id,
                "title" to page.title,
                "entry" to page.entry
            )
        },
        "commands" to plugin.commands.map { command ->
            mapOf(
                "command" to "/${command.name}",
                "aliases" to command.aliases,
                "usage" to command.usage,
                "description" to command.description,
                "open_page" to command.openPage
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
        /** assets 中打包的完整插件开发文档；help 动作优先返回它。 */
        const val PLUGIN_GUIDE_ASSET = "plugin-development.md"

        /** plugin_use 专用附录：把文档规范映射到本工具的推荐工作流。 */
        val PLUGIN_USE_WORKFLOW: String = """

            ————————————————
            plugin_use 工具推荐流程（AI 附录）
            1. 通读本文档，理解清单规范、权限与运行时限制
            2. list 查看已安装插件，避免 id 与命令名冲突（内置 builtin.jm、builtin.light-novel 不可占用）
            3. 移植别家插件：inspect（path 指向会话工作区内的 .zip 或目录）→ 确定性解压 + 生态识别 + 文件清单
            3.1 工作区里已有现成的插件 ZIP（无需改写）时用 install_zip（path 指向会话工作区内的 .zip）直接安装：
                会弹出第三方插件同意弹窗，用户勾选协议与权限后才安装；该确认不可记忆、YOLO 也不能跳过，
                用户拒绝后不要反复重试，改为告知用户拒绝结果
            3.2 install_url（https 地址）同样会先下载并弹出第三方插件同意弹窗，用户拒绝时不要反复重试
            4. create 编写完整 manifest_json + main_js 并安装（多文件用 extra_files_json，遵守第 7 节大小限制；页面文件也放 extra_files_json）
            5. check 静态自检（清单 / 页面入口 / API 名称 / 大小），修复到 ready=true
            6. execute 逐条测试命令（用 args 模拟用户输入）
            7. 出错时 view 读取实际落盘源码，update 修复后复测
            8. create/update 可带 compat（native/ported-full/ported-partial/unsupported）与 compat_note 记录移植差异
            9. enable/disable/uninstall 管理生命周期；uninstall 需用户确认；
                安装第三方代码（install_url / install_zip）必须经过第三方插件同意弹窗（协议 + 权限勾选），
                YOLO 也不能放行
            10. 交付时告知用户：插件 id、可用命令、页面入口、权限申请清单、与原插件的行为差异

            注意：插件命令只在本地模式执行；命令运行时只加载 entry 指定的一个 JS 文件，页面运行时可加载插件目录内的相对资源；不要调用 NekoAndroid 等运行时内部对象，它们不是稳定的插件 API。危险权限（network/chat.write/memory.write/characters.write/ai.call/workspace）需要用户在插件页手动授权，未授权调用会被拒绝。workspace 权限可把插件生成的内容保存到工作区（有会话时为会话工作区，否则共享工作区）的 plugins/<插件id>/ 专属文件夹，返回值里的 file_reference 可直接用于 [File: ...] 文件卡片。files 权限（基础，默认勾选）让插件页面用 <input type="file"> 上传文件到插件私有目录，插件可用 files.list/read/delete 访问，私有文件 URL 形如 /plugin/<插件id>/@files/<文件名>。
        """.trimIndent()

        /** 插件开发指南回退版本；仅在 assets 文档读取失败时使用。 */
        val PLUGIN_DEV_GUIDE: String = """
            Nekobot 插件开发指南（api_version 1-2）

            一、插件包结构
            插件由 plugin.json（清单）和入口 JS 文件（默认 main.js）组成，可选附加文件与页面（pages[]）。安装后插件以斜杠命令形式扩展本地会话功能（如 /note），声明了 pages 的插件还会在「扩展功能 → 插件页面」出现独立页面入口。插件运行在无网络、无文件访问的沙盒 WebView 中，只能通过清单声明且用户已授权的权限调用受控 API。

            plugin.json 必填字段：
            - api_version: 1 或 2（当前支持 1-2）
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
            - permissions: 权限数组。基础/读取：storage、notify、chat.progress、files、chat.read、characters.read、worldbooks.read、memory.read；危险权限（network、chat.write、memory.write、characters.write、ai.call、workspace）需用户在插件页手动授权，AI 创建时不会自动授予。ai.call 开放 aiComplete（走聊天故障转移队列，每插件每分钟 10 次、每小时 20 万 token 上限）；chat.write 开放 appendMessage/sendMessage/createSession/switchSession；characters.write 开放 createCharacter/updateCharacter（无删除）；workspace 开放 workspace.save/list/read/delete（工作区 plugins/<插件id>/ 专属文件夹，有会话时在会话工作区，否则共享工作区；单文件 ≤2 MiB、≤500 个文件、总量 ≤32 MiB，返回的 file_reference 可用于 [File: ...] 卡片）；files 开放页面文件上传（`<input type="file">`，副本存插件私有目录）与 files.list/read/delete（单文件 ≤16 MiB、≤100 个文件、总量 ≤64 MiB）。
            - hooks: 事件钩子数组（≤4，可省略）：message.beforeSend（发送前改写用户消息）、app.lifecycle（app.start/chat.open/chat.close）。声明后用 NekoPlugin.on(name, handler) 注册；声明 hooks 时 commands 可以为空。
            - pages: 页面数组（≤8 个），每项 {"id": "小写id", "title": "标题", "title_i18n": {...}, "entry": "pages/x.html", "styles": [...], "scripts": [...], "order": 100}；entry 必须是以 .html 结尾的安全相对路径且文件真实存在。页面用 host.* API（host.storage.*、host.chat.*（含 chat.messages.append/send、chat.sessions.create/switch、chat.context/sessionConfig/promptStack/toolCalls）、host.characters.*（含 create/update）、host.worldbooks.*、host.memory.read/write/append/edit、host.workspace.save/list/read/delete、host.ui.render、host.http.get/post、host.ui.toast、host.system.info）；页面内可用相对链接或 host.ui.openPage(pageId, args) 在同插件目录的多个 HTML 之间切换（返回键逐页回退，外部跳转仍被拦截）；原生弹窗：window.alert/confirm/prompt 与 host.ui.alert/confirm/prompt/select（select 传 {options: [...]}，取消返回 null），与 ctx.api 共用权限与存储。

            二、入口 JS 运行时
            - 用 NekoPlugin.registerCommand(name, handler) 或 NekoPlugin.register({commands: {name: handler}}) 注册命令
            - handler(ctx) 可以是 async 函数；返回值（字符串或可 JSON 序列化的对象）作为该命令的回复
            - ctx 字段：command（命令名，不带 /）、args（按空白切分的参数数组）、argsText（原始参数文本）、raw（完整输入）、sessionId
            - API（全部返回 Promise，需 await，且需声明对应权限）：
              await ctx.api.getSession()            // 当前会话信息（chat.read）
              await ctx.api.getMessages(limit)      // 最近消息，默认 30、最大 100（chat.read）；页面侧返回含 reasoningContent/model/token 等字段
              await ctx.api.contextUsage({sessionId})   // 上下文占比：{usedTokens, maxTokens, usagePercent, parts:[{part, tokens, count, percent}]}（chat.read）
              await ctx.api.sessionConfig({sessionId})  // 会话配置启用情况：features/intervals/prompt/plot/agent（chat.read）
              await ctx.api.promptStack({sessionId, includeContent, includeComposedPrompt})  // 提示词注入栈（chat.read）
              await ctx.api.toolCalls({sessionId, limit})    // 工具调用记录：{records:[{callId, name, arguments, result, status, messageId}]}（chat.read）
              await ctx.api.notify(message)         // Toast 提示（notify）
              await ctx.api.progress(options)       // 更新进度卡片（chat.progress），耗时命令用
              await ctx.api.httpGet(url, {headers})   // 仅 https://，返回 {status, body}，body 上限 512KB（network）
              await ctx.api.httpPost(url, {headers, body})  // POST，body 为字符串（默认 application/json），上限 512KB（network）
              await ctx.api.storage.get(key)        // 读存储，返回 JSON 值或 null（storage）
              await ctx.api.storage.set(key, value) // 写存储（storage）
              await ctx.api.storage.remove(key)     // 删除键（storage）
              await ctx.api.storage.list()          // 返回 {key: value}（storage）
              await ctx.api.aiComplete({messages: [{role: "user", content: "你好"}], maxTokens: 512})  // 返回 {content, model, usage}（ai.call）
              await ctx.api.appendMessage({role: "user", content: "文本"})  // 往当前会话追加消息，返回 {id, sessionId, role, createdAt}（chat.write）
              await ctx.api.memoryRead()            // 读 Agent 长期记忆，返回 {content, charCount}（memory.read）
              await ctx.api.memoryAppend("要记住的事")  // 追加记忆（memory.write）；也有 memoryWrite(content) 覆盖、memoryEdit(oldText, newText) 替换
              await ctx.api.workspace.save("notes/a.md", "内容")  // 保存文本到插件专属工作区文件夹（workspace），返回 {scope, path, file_reference}；也有 workspace.list/read/delete
              await ctx.api.files.list()            // 插件私有文件（页面上传，files）：{count, total_bytes, files:[{name,size,mime_type,updated_at,url}]}
              await ctx.api.files.read("a.png", "base64")  // 读私有文件（text 默认 / base64，≤128 KiB）；也有 files.delete(name)
              await ctx.api.sendMessage({content: "文本"})   // 发消息并触发后台回复（chat.write）
              await ctx.api.createCharacter({name: "角色名", personality: "..."})  // 创建角色卡（characters.write，无删除）
              await ctx.api.render("{{#each this}}{{name}}{{/each}}", list)        // 模板渲染（免权限）
            - 限制：单次执行超时 20 秒（声明 ai.call 时 120 秒）；回复上限 20000 字符；没有 fetch/XHR/DOM 存储；storage key ≤128 字符且不能含换行；appendMessage 单条 ≤8000 字符且只能追加不能删改；Agent 记忆整体 ≤32000 字符

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
