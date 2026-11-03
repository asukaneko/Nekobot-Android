package com.nekobot.app.ui.screens.memory

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.nekobot.app.data.local.ai.CompanionLockedReviewItem
import com.nekobot.app.data.local.ai.CompanionMemorySourceView
import com.nekobot.app.data.local.db.CompanionLockedReviewEntity
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.CompanionSourceEntity
import com.nekobot.app.ui.screens.chat.CompanionMessageChange
import com.nekobot.app.ui.screens.chat.CompanionMessageDecisionState
import com.nekobot.app.ui.theme.DarkColors
import com.nekobot.app.ui.theme.LightColors

/** 真实Compose组件的本地预览入口，仅使用虚构数据，不初始化数据库或模型。 */
@Preview(name = "浅色", locale = "zh", widthDp = 360, heightDp = 800, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "深色", locale = "zh", widthDp = 360, heightDp = 800, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class MemoryPhonePreviews

@Composable
private fun PreviewHost(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        CompanionMemoryTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content) }
    }
}

private const val saved = "2026-10-23T02:23:00Z"
private fun memory(id: String, content: String) = CompanionMemoryEntity(id, "preview", "work", 1, "fact", "虚构角色", content,
    "active", eventDate = "2026-10-23", createdAt = saved, updatedAt = saved)

@MemoryPhonePreviews
@Composable
private fun OriginalMessagePreview() = PreviewHost {
    val row = memory("current", "小舟准备周末去书店。")
    CompanionMemorySources(listOf(CompanionMemorySourceView(
        CompanionSourceEntity(row.id, "message", "preview", "周末去书店", "2026-10-23", "user"),
        "我想周末去书店看看。最近开始读小说，想找一本适合慢慢读的。要是天气不错，回来的路上还可以散步。", false, false)), row, "林小满", {}, {})
}

@MemoryPhonePreviews
@Composable
private fun DeletedOriginalPreview() = PreviewHost {
    val row = memory("current", "小舟准备周末去书店。")
    CompanionMemorySources(listOf(CompanionMemorySourceView(
        CompanionSourceEntity(row.id, "message", "preview", "周末去书店", "2026-10-23", "user"),
        "我想周末去书店看看。最近开始读小说，想找一本适合慢慢读的。", true, true)), row, "林小满", {}, {})
}

@MemoryPhonePreviews
@Composable
private fun VoidedVersionsPreview() = PreviewHost {
    CompanionMemoryChanges(listOf(memory("new", "小舟搬到上海上班了。"),
        memory("old", "我记得小舟在杭州上班。").copy(current = false, voided = true, voidedAt = saved, voidedReason = "update")),
        false, null, {}, {}, {})
}

@MemoryPhonePreviews
@Composable
private fun MessageDecisionPreview() = PreviewHost {
    CompanionMessageMemoryDialog(CompanionMessageDecisionState(CompanionMessageChange("session", "message", "新消息", true),
        listOf("message", "reply"), listOf("我记得小舟在杭州上班。", "小舟说他喜欢靠窗的位置。", "我们约好周末去书店。",
            "我记得他最近开始练习做饭。", "小舟下周三要和朋友吃饭。", "他喜欢无糖的茶。", "他想学摄影。")
            .mapIndexed { i, text -> memory("memory-$i", text) }), false, null, {})
}

private fun review() = CompanionLockedReviewItem(CompanionLockedReviewEntity("review", "preview", "session", "locked", "confirmed", saved),
    memory("locked", "我记得小舟在杭州上班。").copy(locked = true), memory("confirmed", "小舟上个月搬到上海上班，新住处离公司很近。"))

@MemoryPhonePreviews
@Composable
private fun LockedMemoryPreview() = PreviewHost { CompanionLockedMemoryDialog(review(), false, null, {}, { _, _ -> }) }

@MemoryPhonePreviews
@Composable
private fun LockedMemoryEditingPreview() = PreviewHost {
    CompanionLockedMemoryDialog(review(), false, null, {}, { _, _ -> }, initiallyEditing = true)
}
