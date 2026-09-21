package com.nekobot.app.data.local.plugin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 插件权限授权集合。
 *
 * 与清单声明是两个独立条件：调用宿主 API 必须同时满足「清单已声明」与「用户已授权」。
 * 没有授权记录的插件视为存量插件，按清单声明放行（兼容升级前的安装），
 * 但界面会提示用户确认权限。
 */
class PluginGrants(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nekobot_plugin_grants", Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0L)

    /** 授权记录变化代次；UI 观察它刷新授权状态。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** 是否存在授权记录；不存在表示存量插件，按清单声明放行。 */
    fun hasRecord(pluginId: String): Boolean = prefs.contains(recordKey(pluginId))

    /** 返回已授权集合；返回 null 表示没有记录（存量插件）。 */
    fun granted(pluginId: String): Set<String>? =
        prefs.getStringSet(recordKey(pluginId), null)?.toSet()

    fun grantedAt(pluginId: String): Long = prefs.getLong(grantedAtKey(pluginId), 0L)

    /** 覆盖写入授权集合；空集合也是一条有效记录（表示用户拒绝全部权限）。 */
    fun setGranted(pluginId: String, permissions: Set<String>) {
        prefs.edit()
            .putStringSet(recordKey(pluginId), permissions.toSet())
            .putLong(grantedAtKey(pluginId), System.currentTimeMillis())
            .apply()
        bump()
    }

    /** 新安装插件的默认授权：清单声明 ∩ 非危险权限；危险权限等待用户显式授权。 */
    fun initializeDefaults(pluginId: String, declared: Collection<String>) {
        setGranted(pluginId, PluginManifestValidator.defaultGrantedPermissions(declared))
    }

    /** 撤销授权：删除记录后回到「未确认」状态，而不是「按清单放行」。 */
    fun revoke(pluginId: String) {
        prefs.edit()
            .putStringSet(recordKey(pluginId), emptySet())
            .putLong(grantedAtKey(pluginId), System.currentTimeMillis())
            .apply()
        bump()
    }

    /** 卸载清理：删除授权记录。 */
    fun clear(pluginId: String) {
        prefs.edit().remove(recordKey(pluginId)).remove(grantedAtKey(pluginId)).apply()
        bump()
    }

    private fun bump() {
        _revision.value += 1L
    }

    private fun recordKey(pluginId: String) = "granted:$pluginId"

    private fun grantedAtKey(pluginId: String) = "granted_at:$pluginId"
}

/**
 * 校验一次插件 API 调用的权限条件。
 *
 * @param granted 用户授权集合；null 表示没有记录（存量插件，按清单放行）。
 * @throws PluginApiException 未声明或未授权时抛出，错误码供插件 JS 区分处理。
 */
internal fun checkPluginPermission(
    plugin: InstalledPlugin,
    permission: String,
    granted: Set<String>?
) {
    if (permission !in plugin.permissions) {
        throw PluginApiException("插件未声明权限：$permission", "permission_not_declared")
    }
    if (granted != null && permission !in granted) {
        throw PluginApiException("权限未授权：$permission（请在插件页面授权后重试）", "permission_denied")
    }
}
