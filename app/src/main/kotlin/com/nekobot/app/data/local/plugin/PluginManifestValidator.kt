package com.nekobot.app.data.local.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** 插件清单和 ZIP 内路径的纯校验逻辑，避免把不可信文件直接交给运行时。 */
object PluginManifestValidator {
    /** 当前 App 支持的插件 API 版本；安装时接受 1..CURRENT_API_VERSION。 */
    const val CURRENT_API_VERSION = 2

    val supportedPermissions: Set<String> = setOf(
        "storage",
        "chat.read",
        "notify",
        "network",
        "chat.progress",
        "characters.read",
        "worldbooks.read",
        "memory.read",
        "chat.write",
        "memory.write",
        "characters.write",
        "ai.call"
    )

    /** 支持的事件钩子：声明后由无界面运行时执行入口脚本注册。 */
    val supportedHooks: Set<String> = setOf(
        "message.beforeSend",
        "app.lifecycle"
    )

    /** 危险权限：安装/授权时默认不勾选，且 AI 创建的插件不会自动获得。 */
    val dangerousPermissions: Set<String> = setOf(
        "network",
        "chat.write",
        "memory.write",
        "characters.write",
        "ai.call"
    )

    /** 权限分组：授权界面按「基础 / 读取 / 网络 / 写入 / AI」渲染。 */
    val permissionGroups: List<Pair<String, List<String>>> = listOf(
        "basic" to listOf("storage", "notify", "chat.progress"),
        "read" to listOf("chat.read", "characters.read", "worldbooks.read", "memory.read"),
        "network" to listOf("network"),
        "write" to listOf("chat.write", "memory.write", "characters.write"),
        "ai" to listOf("ai.call")
    )

    /** 新安装插件的默认授权集合：清单声明 ∩ 非危险权限。 */
    fun defaultGrantedPermissions(declared: Collection<String>): Set<String> =
        declared.filterNot { it in dangerousPermissions }.toSet()

    private val pluginIdPattern = Regex("^[a-zA-Z][a-zA-Z0-9._-]{1,63}$")
    private val commandPattern = Regex("^[a-zA-Z][a-zA-Z0-9_-]{0,31}$")
    private val pageIdPattern = Regex("^[a-z][a-z0-9_-]{0,31}$")

    /**
     * 为清单 JSON 补齐可选字段的默认值。
     *
     * Gson 通过 Unsafe 创建 Kotlin data class 实例，会绕过 Kotlin 默认值：
     * 第三方清单省略 permissions、commands、命令 aliases 等可选字段时，
     * 对应集合字段会保持 null，后续校验触发
     * "parameter specified as non-null is null" 崩溃。
     * 这里在反序列化之前统一补默认值，避免运行时 NPE；
     * 类型不匹配的字段不做修复，交给 Gson 抛出格式错误。
     */
    fun sanitizeManifestJson(raw: String): String {
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() as? JsonObject
            ?: return raw
        // 标量缺省
        fillInt(root, "api_version", CURRENT_API_VERSION)
        fillString(root, "id", "")
        fillString(root, "name", "")
        fillString(root, "version", "")
        fillString(root, "author", "")
        fillString(root, "description", "")
        fillString(root, "entry", "main.js")
        // 集合缺省
        fillArray(root, "permissions")
        fillArray(root, "commands")
        fillArray(root, "pages")
        fillArray(root, "hooks")
        // 命令级缺省
        root.getAsJsonArray("commands")?.forEach { element ->
            val command = element as? JsonObject ?: return@forEach
            fillString(command, "name", "")
            fillString(command, "usage", "")
            fillString(command, "description", "")
            fillString(command, "open_page", "")
            fillArray(command, "aliases")
        }
        // 页面级缺省
        root.getAsJsonArray("pages")?.forEach { element ->
            val page = element as? JsonObject ?: return@forEach
            fillString(page, "id", "")
            fillString(page, "title", "")
            fillString(page, "icon", "")
            fillString(page, "entry", "")
            fillInt(page, "order", 100)
            fillObject(page, "title_i18n")
            fillArray(page, "styles")
            fillArray(page, "scripts")
        }
        return root.toString()
    }

    /** 仅在成员缺失或为 null 时补默认字符串值；类型不匹配时保持原样。 */
    private fun fillString(obj: JsonObject, member: String, default: String) {
        val existing = obj.get(member)
        if (existing == null || existing.isJsonNull) {
            obj.addProperty(member, default)
        }
    }

    /** 仅在成员缺失或为 null 时补默认整数值。 */
    private fun fillInt(obj: JsonObject, member: String, default: Int) {
        val existing = obj.get(member)
        if (existing == null || existing.isJsonNull) {
            obj.addProperty(member, default)
        }
    }

    /** 仅在成员缺失或为 null 时补默认数组；已有非数组值保持原样交给 Gson 报错。 */
    private fun fillArray(obj: JsonObject, member: String) {
        val existing = obj.get(member)
        if (existing == null || existing.isJsonNull) {
            obj.add(member, JsonArray())
        }
    }

    /** 仅在成员缺失或为 null 时补默认对象；已有非对象值保持原样交给 Gson 报错。 */
    private fun fillObject(obj: JsonObject, member: String) {
        val existing = obj.get(member)
        if (existing == null || existing.isJsonNull) {
            obj.add(member, JsonObject())
        }
    }

    /** 返回全部问题；空列表表示清单可以安装。 */
    fun validate(
        manifest: PluginManifest,
        reservedCommands: Set<String> = emptySet()
    ): List<String> {
        val errors = mutableListOf<String>()
        if (manifest.apiVersion < 1 || manifest.apiVersion > CURRENT_API_VERSION) {
            errors += "不支持的插件 API 版本：${manifest.apiVersion}（支持 1-$CURRENT_API_VERSION）"
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
        if (manifest.hooks.any { it !in supportedHooks }) {
            val unknown = manifest.hooks.filter { it !in supportedHooks }.distinct()
            errors += "未知事件钩子：${unknown.joinToString(", ")}（支持 ${supportedHooks.joinToString(", ")}）"
        }
        if (manifest.hooks.isNotEmpty() && manifest.hooks.size > 4) {
            errors += "单个插件最多声明 4 个事件钩子"
        }
        if (manifest.commands.isEmpty() && manifest.hooks.isEmpty()) {
            errors += "插件至少需要注册一条命令或声明一个事件钩子"
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
            val openPage = command.openPage.trim()
            if (openPage.isNotEmpty()) {
                if (!pageIdPattern.matches(openPage)) {
                    errors += "第 ${index + 1} 条命令的 open_page 必须是合法的页面 id：$openPage"
                } else if (manifest.pages.none { it.id == openPage }) {
                    errors += "第 ${index + 1} 条命令的 open_page 引用了未声明的页面：$openPage"
                }
            }
        }
        errors += validatePages(manifest.pages)
        return errors.distinct()
    }

    /** 校验插件页面声明；返回全部问题，空列表表示可以安装。 */
    fun validatePages(pages: List<PluginPageManifest>): List<String> {
        val errors = mutableListOf<String>()
        if (pages.size > MAX_PAGES) {
            errors += "单个插件最多声明 $MAX_PAGES 个页面"
        }
        val seen = mutableSetOf<String>()
        pages.forEachIndexed { index, page ->
            val label = "第 ${index + 1} 个页面"
            if (!pageIdPattern.matches(page.id)) {
                errors += "$label 的 id 必须是 1-32 位小写字母、数字、下划线或连字符"
            } else if (!seen.add(page.id)) {
                errors += "插件内重复的页面 id：${page.id}"
            }
            if (page.title.isBlank() || page.title.length > MAX_PAGE_TITLE_CHARS) {
                errors += "$label 的 title 不能为空且不能超过 $MAX_PAGE_TITLE_CHARS 个字符"
            }
            if (!isSafeRelativePath(page.entry) || !page.entry.endsWith(".html", ignoreCase = true)) {
                errors += "$label 的 entry 必须是安全的 .html 相对路径"
            }
            if (page.icon.isNotBlank() && !isSafeRelativePath(page.icon)) {
                errors += "$label 的 icon 必须是不含 .. 的相对路径"
            }
            if (page.styles.size > MAX_PAGE_ASSETS) {
                errors += "$label 的 styles 最多 $MAX_PAGE_ASSETS 项"
            }
            if (page.scripts.size > MAX_PAGE_ASSETS) {
                errors += "$label 的 scripts 最多 $MAX_PAGE_ASSETS 项"
            }
            (page.styles + page.scripts).forEach { asset ->
                if (!isSafeRelativePath(asset)) {
                    errors += "$label 的样式/脚本路径不安全：$asset"
                }
            }
            page.titleI18n.forEach { (language, title) ->
                if (language.lowercase() !in SUPPORTED_PAGE_LANGUAGES || title.isBlank()) {
                    errors += "$label 的 title_i18n 含有不支持的语言或空标题：$language"
                }
            }
        }
        return errors
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

    const val MAX_PAGES = 8
    const val MAX_PAGE_ASSETS = 8
    const val MAX_PAGE_TITLE_CHARS = 40

    /** 页面标题支持的语言；与 App 现有 4 语言一致。 */
    val SUPPORTED_PAGE_LANGUAGES: Set<String> = setOf("zh", "en", "ja", "ko")
}