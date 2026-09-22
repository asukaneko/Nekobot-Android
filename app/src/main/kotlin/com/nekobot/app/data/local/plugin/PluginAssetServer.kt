package com.nekobot.app.data.local.plugin

import android.webkit.WebResourceResponse
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.util.Locale

/** 注入插件页面的主题 token；取值来自当前 Compose 主题。 */
data class PluginThemeTokens(
    val mode: String = "light",
    val colors: Map<String, String> = emptyMap(),
    val locale: String = "zh-CN",
    val appVersion: String = "",
    val apiVersion: Int = PluginManifestValidator.CURRENT_API_VERSION
) {
    fun cssVariables(): String = buildString {
        append(":root{")
        colors.forEach { (name, value) ->
            if (name.isBlank() || value.isBlank()) return@forEach
            append("--neko-").append(name).append(':').append(value).append(';')
        }
        append("--neko-radius:16px;")
        append(
            "--neko-font:system-ui,-apple-system,\"Segoe UI\",Roboto,\"Noto Sans SC\",\"Noto Sans JP\",sans-serif;"
        )
        append('}')
        if (mode == "dark") {
            append("html{color-scheme:dark;}")
        } else {
            append("html{color-scheme:light;}")
        }
    }
}

/**
 * 插件页面资源服务。
 *
 * 页面运行在虚拟源 `https://appassets.androidplatform.net/plugin/<id>/...`，
 * 所有相对引用（CSS/JS/图片）都由 [intercept] 从插件目录读取；越界路径一律拒绝。
 * HTML 响应会在 `<head>` 起始处注入主题变量与 `host.*` 桥，注入先于插件脚本执行。
 */
class PluginAssetServer(
    private val pluginDirectoryProvider: (String) -> File?,
    private val themeProvider: () -> PluginThemeTokens = { PluginThemeTokens() }
) {
    private val gson = Gson()

    /** 当前主题 token；页面宿主在 Compose 主题变化时更新。 */
    @Volatile
    var theme: PluginThemeTokens = themeProvider()
        set(value) {
            field = value
        }

    fun virtualOrigin(pluginId: String): String = "https://$VIRTUAL_HOST/plugin/$pluginId/"

    /**
     * 解析同插件虚拟源 URL 的相对路径（已解码）；非本插件地址或越界路径返回 null。
     *
     * 页面导航白名单（相对链接、`location.href`、`host.ui.openPage`）与当前页面标题
     * 匹配都走这里；查询串与锚点不参与匹配。
     */
    fun pluginRelativePath(pluginId: String, url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals(VIRTUAL_HOST, ignoreCase = true)) return null
        val path = uri.path ?: return null
        val prefix = "/plugin/$pluginId/"
        if (!path.startsWith(prefix)) return null
        val relative = path.removePrefix(prefix)
        if (!PluginManifestValidator.isSafeRelativePath(relative)) return null
        return relative
    }


    /** 拦截虚拟源请求；非虚拟源返回 null，交给 WebView 的默认网络策略（已被禁用）。 */
    fun intercept(pluginId: String, url: String): WebResourceResponse? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals(VIRTUAL_HOST, ignoreCase = true)) return null
        val path = uri.path ?: return null
        val prefix = "/plugin/$pluginId/"
        if (!path.startsWith(prefix)) return null
        val relative = path.removePrefix(prefix)
        if (!PluginManifestValidator.isSafeRelativePath(relative)) {
            return errorResponse(403, "Forbidden")
        }
        val root = pluginDirectoryProvider(pluginId) ?: return errorResponse(404, "Not Found")
        val target = File(root, relative).canonicalFile
        val rootPath = root.canonicalFile.path
        if (!target.path.startsWith(rootPath + File.separator) || !target.isFile) {
            return errorResponse(404, "Not Found")
        }
        val mime = mimeTypeFor(relative)
        return try {
            if (mime == "text/html") {
                val html = readTextLimited(target, MAX_HTML_BYTES)
                if (html == null) {
                    errorResponse(413, "Payload Too Large")
                } else {
                    val body = injectIntoHtml(html, buildInjection()).toByteArray(Charsets.UTF_8)
                    response(mime, "UTF-8", 200, "OK", ByteArrayInputStream(body))
                }
            } else {
                response(mime, charsetFor(mime), 200, "OK", FileInputStream(target))
            }
        } catch (_: Exception) {
            errorResponse(500, "Internal Error")
        }
    }

    /** 主题变量 + `window.__NEKO_THEME__` + `host.*` 桥；随 HTML 一起返回。 */
    fun buildInjection(): String {
        val tokens = theme
        val themeJson = gson.toJson(themeMeta(tokens))
        return """
            <style id="neko-theme">${tokens.cssVariables()}</style>
            <script id="neko-host-bridge">
            (function () {
              "use strict";
              var pending = Object.create(null);
              var nextRequestId = 0;
              window.__NEKO_THEME__ = $themeJson;
              function __nekoParam(name) {
                try {
                  var value = new URLSearchParams(location.search).get(name);
                  if (value !== null) return value;
                } catch (_) {}
                var match = new RegExp("[?&]" + name + "=([^&]*)").exec(location.search || "");
                if (!match) return null;
                try { return decodeURIComponent(match[1].replace(/\+/g, " ")); } catch (_) { return match[1]; }
              }
              // 由聊天命令（commands[].open_page）打开时携带的启动信息
              var __nekoArgsText = __nekoParam("args") || "";
              window.__NEKO_LAUNCH__ = {
                sessionId: __nekoParam("sessionId") || null,
                argsText: __nekoArgsText,
                args: __nekoArgsText.split(/\s+/).filter(function (part) { return part.length > 0; })
              };
              function call(name, payload) {
                return new Promise(function (resolve, reject) {
                  var id = String(++nextRequestId);
                  pending[id] = { resolve: resolve, reject: reject };
                  try {
                    NekoHost.call(id, name, JSON.stringify(payload || {}));
                  } catch (error) {
                    delete pending[id];
                    reject(error);
                  }
                });
              }
              window.__nekoHostResult = function (id, payload) {
                var entry = pending[String(id)];
                if (!entry) return;
                delete pending[String(id)];
                var envelope;
                try { envelope = JSON.parse(payload || "null"); } catch (_) { envelope = null; }
                if (envelope && envelope.ok) {
                  entry.resolve(envelope.value);
                } else {
                  var detail = envelope && envelope.error;
                  var error = new Error(String((detail && detail.error) || detail || "调用失败"));
                  if (detail && detail.code) error.code = detail.code;
                  entry.reject(error);
                }
              };
              function callHost(name, payload) { return call(name, payload); }
              function dialogPayload(message, options) {
                if (message && typeof message === "object") {
                  options = message;
                  message = options.message;
                }
                var payload = {};
                if (options && typeof options === "object" && options.title != null) {
                  payload.title = String(options.title);
                }
                payload.message = message == null ? "" : String(message);
                return payload;
              }
              window.host = {
                system: { info: function () { return callHost("system.info", {}); } },
                log: function (level, message) {
                  return callHost("log", { level: level, message: message });
                },
                storage: {
                  get: function (key) { return callHost("storage.get", { key: key }); },
                  set: function (key, value) { return callHost("storage.set", { key: key, value: value }); },
                  remove: function (key) { return callHost("storage.remove", { key: key }); },
                  list: function () { return callHost("storage.list", {}); }
                },
                ui: {
                  toast: function (message) { return callHost("ui.toast", { message: message }); },
                  close: function () { return callHost("ui.close", {}); },
                  render: function (template, data) {
                    return callHost("ui.render", { template: template, data: data });
                  },
                  openPage: function (pageId, args) {
                    return callHost("ui.openPage", { pageId: pageId, args: args });
                  },
                  alert: function (message, options) {
                    return callHost("ui.alert", dialogPayload(message, options));
                  },
                  confirm: function (message, options) {
                    return callHost("ui.confirm", dialogPayload(message, options));
                  },
                  prompt: function (options, defaultValue) {
                    if (typeof options === "string") {
                      var textPayload = { message: options };
                      if (defaultValue != null) textPayload.value = String(defaultValue);
                      return callHost("ui.prompt", textPayload);
                    }
                    options = options || {};
                    var promptPayload = dialogPayload(options.message, options);
                    var value = options.value != null ? options.value : options.defaultValue;
                    if (value != null) promptPayload.value = String(value);
                    return callHost("ui.prompt", promptPayload);
                  },
                  select: function (options) {
                    if (Array.isArray(options)) return callHost("ui.select", { options: options });
                    return callHost("ui.select", options || {});
                  }
                },
                chat: {
                  current: function () { return callHost("chat.current", {}); },
                  context: function (options) { return callHost("chat.context", options || {}); },
                  sessionConfig: function (options) { return callHost("chat.session.config", options || {}); },
                  promptStack: function (options) { return callHost("chat.prompt.stack", options || {}); },
                  toolCalls: function (options) { return callHost("chat.tool.calls", options || {}); },
                  send: function (options) { return callHost("chat.send", options || {}); },
                  sessions: {
                    list: function () { return callHost("chat.sessions.list", {}); },
                    get: function (id) { return callHost("chat.sessions.get", { id: id }); },
                    create: function (options) { return callHost("chat.sessions.create", options || {}); },
                    switch: function (id) { return callHost("chat.sessions.switch", { id: id }); }
                  },
                  messages: {
                    list: function (sessionId, limit) {
                      return callHost("chat.messages.list", { sessionId: sessionId, limit: limit });
                    },
                    append: function (options) { return callHost("chat.messages.append", options || {}); }
                  }
                },
                characters: {
                  list: function () { return callHost("characters.list", {}); },
                  get: function (id) { return callHost("characters.get", { id: id }); },
                  create: function (options) { return callHost("characters.create", options || {}); },
                  update: function (options) { return callHost("characters.update", options || {}); }
                },
                worldbooks: {
                  list: function () { return callHost("worldbooks.list", {}); },
                  get: function (id) { return callHost("worldbooks.get", { id: id }); }
                },
                memory: {
                  read: function () { return callHost("memory.read", {}); },
                  write: function (content) { return callHost("memory.write", { content: content }); },
                  append: function (content) { return callHost("memory.append", { content: content }); },
                  edit: function (oldText, newText) {
                    return callHost("memory.edit", { oldText: oldText, newText: newText });
                  }
                },
                http: { get: function (url) { return callHost("http.get", { url: url }); } },
                ai: {
                  complete: function (options) { return callHost("ai.complete", options || {}); }
                },
                progress: {
                  update: function (options) { return callHost("progress.update", options || {}); }
                }
              };
              // <select> 的原生下拉由 WebView 绘制，样式与应用不一致；统一改走 host.ui.select。
              // 例外：multiple / size>1 / disabled / 带 data-neko-native 的 select 保持原生行为。
              (function () {
                "use strict";
                var active = false;
                function skip(select) {
                  return !select || select.disabled || select.multiple ||
                    (select.size && select.size > 1) || select.hasAttribute("data-neko-native");
                }
                function optionText(option) {
                  var text = String(option.label || option.textContent || "").trim();
                  var group = option.parentNode;
                  if (group && group.tagName === "OPTGROUP" && group.label) {
                    return String(group.label) + " · " + text;
                  }
                  return text;
                }
                function open(select) {
                  if (active || skip(select)) return;
                  var options = [];
                  var indices = [];
                  for (var i = 0; i < select.options.length; i++) {
                    var option = select.options[i];
                    if (option.disabled) continue;
                    options.push({ label: optionText(option) || ("#" + (i + 1)), value: option.value });
                    indices.push(i);
                  }
                  if (options.length === 0) return;
                  active = true;
                  host.ui.select({
                    options: options,
                    selected: indices.indexOf(select.selectedIndex),
                    message: select.getAttribute("data-neko-title") || select.title || ""
                  }).then(function (choice) {
                    if (!choice || choice.index == null) return;
                    var index = indices[choice.index];
                    if (index == null || index === select.selectedIndex) return;
                    select.selectedIndex = index;
                    select.dispatchEvent(new Event("input", { bubbles: true }));
                    select.dispatchEvent(new Event("change", { bubbles: true }));
                  }).catch(function () {}).then(function () { active = false; });
                }
                function onPointer(event) {
                  var target = event.target;
                  if (!target || String(target.tagName).toLowerCase() !== "select") return;
                  if (skip(target)) return;
                  if (event.button != null && event.button > 0) return;
                  event.preventDefault();
                  open(target);
                }
                document.addEventListener("touchstart", onPointer, { capture: true, passive: false });
                document.addEventListener("mousedown", onPointer, true);
                // 兜底：个别版本仍会在 click 阶段拉起原生下拉，这里再拦一次
                document.addEventListener("click", function (event) {
                  var target = event.target;
                  if (!target || String(target.tagName).toLowerCase() !== "select") return;
                  if (skip(target)) return;
                  event.preventDefault();
                }, true);
              })();
            })();
            </script>
        """.trimIndent()
    }

    /**
     * 运行期换肤脚本：就地替换注入的 `<style id="neko-theme">` 并刷新 `window.__NEKO_THEME__`，
     * 不重载页面（页面已渲染后再注入主题时使用）。
     */
    fun buildThemePatchScript(): String {
        val tokens = theme
        val css = gson.toJson(tokens.cssVariables())
        val themeJson = gson.toJson(themeMeta(tokens))
        return "(function(){try{" +
            "var el=document.getElementById('neko-theme');" +
            "if(!el){el=document.createElement('style');el.id='neko-theme';" +
            "(document.head||document.documentElement).appendChild(el);}" +
            "el.textContent=$css;" +
            "window.__NEKO_THEME__=$themeJson;" +
            "}catch(e){}})();"
    }

    private fun themeMeta(tokens: PluginThemeTokens): Map<String, Any> = mapOf(
        "mode" to tokens.mode,
        "locale" to tokens.locale,
        "appVersion" to tokens.appVersion,
        "apiVersion" to tokens.apiVersion
    )

    private fun response(
        mime: String,
        encoding: String?,
        status: Int,
        reason: String,
        body: java.io.InputStream
    ): WebResourceResponse = WebResourceResponse(
        mime,
        encoding,
        status,
        reason,
        mapOf("Cache-Control" to "no-store"),
        body
    )

    private fun errorResponse(status: Int, reason: String): WebResourceResponse =
        response("text/plain", "UTF-8", status, reason, ByteArrayInputStream(reason.toByteArray()))

    private fun readTextLimited(file: File, limit: Long): String? {
        if (file.length() > limit) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun charsetFor(mime: String): String? = when {
        mime.startsWith("text/") -> "UTF-8"
        mime in TEXT_MIME_TYPES -> "UTF-8"
        else -> null
    }

    companion object {
        const val VIRTUAL_HOST = "appassets.androidplatform.net"
        const val MAX_HTML_BYTES = 2L * 1024 * 1024

        private val TEXT_MIME_TYPES = setOf(
            "application/javascript",
            "application/json",
            "application/xml",
            "image/svg+xml"
        )

        private val MIME_BY_EXTENSION = mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "js" to "application/javascript",
            "mjs" to "application/javascript",
            "json" to "application/json",
            "xml" to "application/xml",
            "txt" to "text/plain",
            "md" to "text/plain",
            "csv" to "text/csv",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "wasm" to "application/wasm"
        )

        fun mimeTypeFor(path: String): String {
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MIME_BY_EXTENSION[extension] ?: "application/octet-stream"
        }

        /**
         * 把注入内容插到 HTML 的最前面。
         *
         * 优先插在 `<head>` 起始标签之后，保证先于插件脚本执行；没有 head 时插在
         * `<html>` 之后，两者都没有时直接前置（浏览器会自行补全文档结构）。
         */
        fun injectIntoHtml(html: String, injection: String): String {
            val headOpen = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
            if (headOpen != null) {
                val index = headOpen.range.last + 1
                return html.substring(0, index) + injection + html.substring(index)
            }
            val htmlOpen = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
            if (htmlOpen != null) {
                val index = htmlOpen.range.last + 1
                return html.substring(0, index) + injection + html.substring(index)
            }
            return injection + html
        }
    }
}
