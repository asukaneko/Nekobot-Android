package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书命中调试（P1-4）与 position 归一化（P1-2）的回归测试。
 *
 * 重点保证两件事：
 * 1. `diagnoseEntriesV2` 覆盖**全部**条目并给出未命中原因，而 `matchEntriesV2` 的行为不变；
 * 2. 酒馆卡的驼峰 position 写法能落到正确的注入位置。
 */
class WorldBookMatcherDiagnosticsTest {
    private val gson = Gson()
    private val book = LocalWorldBookEntity(
        id = "book",
        name = "设定集",
        createdAt = "2026-07-30T00:00:00Z",
        updatedAt = "2026-07-30T00:00:00Z"
    )

    @Test
    fun diagnoseKeepsMatchEntriesOnlyInMatchEntriesV2() {
        val hit = entry(id = "hit", keys = listOf("白塔"), content = "白塔设定")
        val miss = entry(id = "miss", keys = listOf("黑塔"), content = "黑塔设定")

        val matched = WorldBookMatcher.matchEntriesV2(
            context = WorldBookRecallContext(latestUserMessage = "前往白塔"),
            worldBooks = listOf(book),
            entriesByBook = mapOf(book.id to listOf(hit, miss))
        )
        assertEquals(listOf("hit"), matched.map { it.entry.id })

        val diagnostics = WorldBookMatcher.diagnoseEntriesV2(
            context = WorldBookRecallContext(latestUserMessage = "前往白塔"),
            worldBooks = listOf(book),
            entriesByBook = mapOf(book.id to listOf(hit, miss))
        )
        assertEquals(2, diagnostics.size)
        assertNotNull(diagnostics.first { it.entry.id == "hit" }.result)
        val missReason = diagnostics.first { it.entry.id == "miss" }.skipReason
        assertNotNull(missReason)
        assertTrue(missReason!!.contains("黑塔"))
    }

    @Test
    fun diagnoseExplainsDisabledEntryAndCharacterMismatch() {
        val mineBook = book.copy(id = "book-mine", characterId = "me")
        val otherBook = book.copy(id = "book-other", characterId = "other-character")
        val disabled = entry(id = "off", keys = listOf("白塔"), content = "x", bookId = mineBook.id)
            .copy(enabled = false)
        val mismatch = entry(id = "bound", keys = listOf("白塔"), content = "y", bookId = otherBook.id)

        val diagnostics = WorldBookMatcher.diagnoseEntriesV2(
            context = WorldBookRecallContext(latestUserMessage = "前往白塔", characterId = "me"),
            worldBooks = listOf(mineBook, otherBook),
            entriesByBook = mapOf(
                mineBook.id to listOf(disabled),
                otherBook.id to listOf(mismatch)
            ),
            characterId = "me"
        )

        assertEquals(
            "条目已禁用",
            diagnostics.first { it.entry.id == "off" }.skipReason
        )
        assertEquals(
            "世界书未绑定当前角色",
            diagnostics.first { it.entry.id == "bound" }.skipReason
        )
    }

    @Test
    fun diagnoseReportsAllMatchModePartialHits() {
        val partial = entry(id = "partial", keys = listOf("白塔", "夜晚"), content = "z")
        val diagnostics = WorldBookMatcher.diagnoseEntriesV2(
            context = WorldBookRecallContext(latestUserMessage = "前往白塔"),
            worldBooks = listOf(book),
            entriesByBook = mapOf(book.id to listOf(partial.copy(matchMode = "all")))
        )
        assertTrue(diagnostics.single().skipReason!!.contains("全匹配模式"))
    }

    @Test
    fun positionNormalizeAcceptsSillyTavernSpellings() {
        assertEquals(CharacterRuntime.Position.BEFORE_CHAR, CharacterRuntime.Position.normalize(null))
        assertEquals(CharacterRuntime.Position.BEFORE_CHAR, CharacterRuntime.Position.normalize("before_char"))
        assertEquals(CharacterRuntime.Position.AFTER_CHAR, CharacterRuntime.Position.normalize("afterChar"))
        assertEquals(CharacterRuntime.Position.AT_DEPTH, CharacterRuntime.Position.normalize("at_depth"))
        assertEquals(CharacterRuntime.Position.AT_DEPTH, CharacterRuntime.Position.normalize("atDepth"))
        assertEquals(CharacterRuntime.Position.BEFORE_AN, CharacterRuntime.Position.normalize("before_an"))
        assertEquals(CharacterRuntime.Position.AFTER_AN, CharacterRuntime.Position.normalize("AFTER_AN"))
        // 未知值回落到 UI 默认值，而不是静默丢进 system 顶部
        assertEquals(CharacterRuntime.Position.BEFORE_CHAR, CharacterRuntime.Position.normalize("nonsense"))
    }

    @Test
    fun characterRuntimeSplitsWorldBookByPosition() = runBlocking {
        val store = object : CharacterRuntime.WorldBookStore {
            override suspend fun match(
                characterId: String,
                userMessage: String,
                state: CharacterState?,
                relationship: RelationshipState?,
                scopeId: String,
                recentMessages: List<Map<String, String>>
            ): List<CharacterRuntime.WorldBookMatch> = listOf(
                CharacterRuntime.WorldBookMatch(
                    content = "前置设定",
                    comment = "before",
                    position = CharacterRuntime.Position.BEFORE_CHAR
                ),
                CharacterRuntime.WorldBookMatch(
                    content = "后置设定",
                    comment = "after",
                    position = CharacterRuntime.Position.AFTER_CHAR
                ),
                CharacterRuntime.WorldBookMatch(
                    content = "提示词末尾设定",
                    comment = "an",
                    position = CharacterRuntime.Position.BEFORE_AN
                ),
                CharacterRuntime.WorldBookMatch(
                    content = "按深度插入的设定",
                    comment = "depth",
                    position = CharacterRuntime.Position.AT_DEPTH,
                    depth = 2
                )
            )
        }
        val runtime = CharacterRuntime(worldBookStore = store)

        val turn = runtime.beforeTurn(
            chatRequest = ChatRequest.forLocal(sessionId = "s1", content = "你好", userId = "u1"),
            identity = CharacterIdentity(characterId = "c1", targetId = "u1", scopeId = "s1"),
            recentMessages = emptyList()
        )

        val prompt = turn.promptText
        assertTrue(prompt.contains("前置设定"))
        assertTrue(prompt.contains("后置设定"))
        assertTrue(prompt.contains("提示词末尾设定"))
        // at_depth 不能进 system 顶部，否则「按消息深度插入」就失效了
        assertFalse(prompt.contains("按深度插入的设定"))
        assertEquals(1, turn.worldBookDepthInjections.size)
        assertEquals(2, turn.worldBookDepthInjections.single().depth)
    }

    private fun entry(
        id: String,
        keys: List<String>,
        content: String,
        bookId: String = book.id
    ) = LocalWorldBookEntryEntity(
        id = id,
        bookId = bookId,
        keys = gson.toJson(keys),
        content = content,
        entryType = "lore"
    )
}
