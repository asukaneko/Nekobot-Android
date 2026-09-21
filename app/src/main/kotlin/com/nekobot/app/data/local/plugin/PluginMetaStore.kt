package com.nekobot.app.data.local.plugin

import android.content.Context
import com.google.gson.Gson

/**
 * 插件兼容级别记录。
 *
 * 不修改插件包格式（避免污染第三方 ZIP），由 App 侧记录 `pluginId → {compat, note}`，
 * 供插件卡片与 Agent 移植报告展示。
 */
class PluginMetaStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nekobot_plugin_meta", Context.MODE_PRIVATE)
    private val gson = Gson()

    data class Meta(
        val compat: PluginCompatLevel = PluginCompatLevel.NATIVE,
        val note: String = ""
    )

    fun meta(pluginId: String): Meta? {
        val raw = prefs.getString(key(pluginId), null) ?: return null
        return runCatching { gson.fromJson(raw, Meta::class.java) }.getOrNull()
    }

    fun setMeta(pluginId: String, compat: PluginCompatLevel, note: String = "") {
        prefs.edit()
            .putString(key(pluginId), gson.toJson(Meta(compat, note.take(MAX_NOTE_CHARS))))
            .apply()
    }

    fun clear(pluginId: String) {
        prefs.edit().remove(key(pluginId)).apply()
    }

    private fun key(pluginId: String) = "meta:$pluginId"

    private companion object {
        const val MAX_NOTE_CHARS = 500
    }
}
