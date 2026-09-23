package com.nekobot.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.nekobot.app.MainActivity
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer

/**
 * 插件页面网格小组件：每个格子是一个插件页面入口。
 *
 * 数据来自已启用插件声明的 `pages[]`；插件安装/停用/卸载后由
 * [ServiceContainer] 监听 `PluginManager.installed` 自动刷新。
 */
class PluginPagesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = buildViews(context)
        appWidgetIds.forEach { widgetId -> appWidgetManager.updateAppWidget(widgetId, views) }
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_plugin_pages_grid)
    }

    /** 网格单元：一个插件页面入口。 */
    data class PageEntry(
        val pluginId: String,
        val pageId: String,
        val title: String,
        /** 插件页面图标的绝对路径；缺省或文件缺失时为 null。 */
        val iconPath: String? = null
    )

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
        const val EXTRA_PAGE_ID = "page_id"

        /** 网格单元上限：组件内可滚动，这里只做防御性封顶。 */
        private const val MAX_ENTRIES = 32

        private const val TEMPLATE_REQUEST_CODE = 20_000
        private const val ROOT_REQUEST_CODE = 20_001

        /** 读取已启用插件的页面入口；App 未初始化时返回空列表。 */
        fun loadEntries(context: Context): List<PageEntry> {
            val plugins = runCatching { ServiceContainer.pluginManager.installed.value }
                .getOrNull()
                .orEmpty()
            val language = context.resources.configuration.locales[0]?.language.orEmpty()
            return plugins
                .filter { it.enabled }
                .flatMap { plugin ->
                    plugin.pages.map { page ->
                        val iconPath = page.icon.takeIf { it.isNotBlank() }?.let { icon ->
                            runCatching {
                                ServiceContainer.pluginManager.resolvePluginAsset(plugin.id, icon)
                            }.getOrNull()?.absolutePath
                        }
                        PageEntry(
                            pluginId = plugin.id,
                            pageId = page.id,
                            title = page.localizedTitle(language),
                            iconPath = iconPath
                        )
                    }
                }
                .take(MAX_ENTRIES)
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, PluginPagesWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { widgetId -> manager.updateAppWidget(widgetId, views) }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_plugin_pages_grid)
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_plugin_pages)
            views.setRemoteAdapter(
                R.id.widget_plugin_pages_grid,
                Intent(context, PluginPagesWidgetService::class.java)
            )
            views.setEmptyView(R.id.widget_plugin_pages_grid, R.id.widget_plugin_pages_empty)
            // 点击模板：网格单元通过 fill-in intent 传入 pluginId / pageId。
            // 必须用 FLAG_MUTABLE：Android 12+ 下不可变的 PendingIntent 会丢弃 fill-in intent，
            // 点击只会带着空 extras 到达，表现为点击无反应。
            // 模板直接指向 MainActivity（而非广播转跳），避免后台启动 Activity 被系统限制拦截。
            views.setPendingIntentTemplate(
                R.id.widget_plugin_pages_grid,
                PendingIntent.getActivity(
                    context,
                    TEMPLATE_REQUEST_CODE,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_plugin_pages_root,
                PendingIntent.getActivity(
                    context,
                    ROOT_REQUEST_CODE,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return views
        }
    }
}
