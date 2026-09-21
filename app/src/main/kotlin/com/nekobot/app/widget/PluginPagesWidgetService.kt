package com.nekobot.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.nekobot.app.R

/** 为 [PluginPagesWidgetProvider] 提供插件页面网格数据。 */
class PluginPagesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        PluginPagesRemoteViewsFactory(applicationContext)

    private class PluginPagesRemoteViewsFactory(
        private val context: Context
    ) : RemoteViewsService.RemoteViewsFactory {
        private var entries: List<PluginPagesWidgetProvider.PageEntry> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            entries = PluginPagesWidgetProvider.loadEntries(context)
        }

        override fun onDestroy() {
            entries = emptyList()
        }

        override fun getCount(): Int = entries.size

        override fun getViewAt(position: Int): RemoteViews? {
            val entry = entries.getOrNull(position) ?: return null
            return RemoteViews(context.packageName, R.layout.widget_plugin_page_item).apply {
                setTextViewText(R.id.widget_plugin_page_item_title, entry.title)
                val icon = entry.iconPath?.let(::decodeSmallIcon)
                if (icon != null) {
                    setImageViewBitmap(R.id.widget_plugin_page_item_icon, icon)
                    setViewVisibility(R.id.widget_plugin_page_item_icon, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.widget_plugin_page_item_icon, View.GONE)
                }
                setOnClickFillInIntent(
                    R.id.widget_plugin_page_item_root,
                    Intent().apply {
                        putExtra(PluginPagesWidgetProvider.EXTRA_PLUGIN_ID, entry.pluginId)
                        putExtra(PluginPagesWidgetProvider.EXTRA_PAGE_ID, entry.pageId)
                    }
                )
            }
        }

        /** 解码小尺寸图标；失败时返回 null（组件退化为纯文字）。 */
        private fun decodeSmallIcon(path: String): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > ICON_MAX_PX * 2 &&
                bounds.outHeight / sampleSize > ICON_MAX_PX * 2
            ) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        }.getOrNull()

        private companion object {
            const val ICON_MAX_PX = 96
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false
    }
}
