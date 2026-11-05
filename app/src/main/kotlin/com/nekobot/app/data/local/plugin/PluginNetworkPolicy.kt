package com.nekobot.app.data.local.plugin

/** PRoot 终端也能联网，必须与 HTTP、模型调用共用网络总开关。 */
internal fun checkPluginNetworkAccess(api: String, networkAllowed: () -> Boolean) {
    if (api in setOf("http.get", "http.post", "ai.complete", "terminal.exec") && !networkAllowed()) {
        throw PluginApiException("Agent 网络访问已在设置中关闭，插件无法发起网络请求", "network_disabled")
    }
}
