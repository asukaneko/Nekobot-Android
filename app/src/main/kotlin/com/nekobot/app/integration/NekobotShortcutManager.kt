package com.nekobot.app.integration

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nekobot.app.MainActivity
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.repository.Resource

/** 将置顶、收藏和最近会话发布为 Android 桌面动态快捷方式。 */
object NekobotShortcutManager {
    suspend fun refresh(context: Context) {
        val sessions = when (val result = ServiceContainer.unified.listSessions()) {
            is Resource.Success -> result.data.orEmpty()
            else -> return
        }
        val selected = sessions
            .filter { !it.id.isNullOrBlank() && it.archived != true }
            .sortedWith(
                compareByDescending<com.nekobot.app.data.model.Session> { it.pinned == true }
                    .thenByDescending { it.favorite == true }
                    .thenByDescending { it.updatedAt.orEmpty() }
            )
            .take(4)
        val shortcuts = selected.mapIndexed { index, session ->
            val id = requireNotNull(session.id)
            val label = session.characterName
                ?.takeIf(String::isNotBlank)
                ?: session.displayName
            ShortcutInfoCompat.Builder(context, "chat_$id")
                .setShortLabel(label.take(18))
                .setLongLabel("打开会话：$label")
                .setRank(index)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = Uri.parse("nekobot://chat/${Uri.encode(id)}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                )
                .build()
        }
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        if (shortcuts.isNotEmpty()) {
            ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts)
        }
    }
}
