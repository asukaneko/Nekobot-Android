package com.nekobot.app.ui.screens.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.nekobot.app.R
import com.nekobot.app.data.local.ChatHistoryWindow
import com.nekobot.app.data.local.ChatHistoryPage
import com.nekobot.app.data.local.ExperienceSourcePage
import com.nekobot.app.data.local.db.LocalExperienceArchiveEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExperienceSourceScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val original = LocalMessageEntity(
        id = "original", sessionId = "s", role = "user", content = "这是逐句原话，不是经历摘要。",
        timestamp = "2026-10-02 18:02:00", createdAt = "2026-10-02 18:02:00"
    )
    private val archive = LocalExperienceArchiveEntity(
        id = "episode", sessionId = "s", startMessageId = "original", endMessageId = "original",
        sourceStartedAt = "2026-10-02 18:02:00", sourceEndedAt = "2026-10-02 18:02:00",
        summary = "这一段的摘要", sourceFingerprint = "f", createdAt = "now", updatedAt = "now"
    )

    private fun uiText(resourceId: Int, vararg args: Any) = compose.activity.getString(resourceId, *args)

    private fun captureFixture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val folder = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun conversationRows(range: IntRange) = range.map {
        original.copy(id = "m$it", role = if (it % 2 == 1) "user" else "assistant", content = "原话 $it")
    }

    private fun scrollToOriginal(id: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("source-message:$id"))
        compose.onNodeWithTag("source-message:$id").assertIsDisplayed()
    }

    @Test fun sourceRetainsLoadedPagesAndScrollOnResumeAndBackStackReturn() {
        var firstPageReads = 0
        val visible = mutableStateOf(true)
        val vm = ExperienceSourceViewModel { _, _, cursor ->
            if (cursor == null) firstPageReads++
            ExperienceSourcePage(archive, "原会话", conversationRows(if (cursor == null) 1..20 else 21..40),
                40, 40, cursor == null)
        }
        compose.setContent {
            MaterialTheme {
                val savedState = rememberSaveableStateHolder()
                if (visible.value) savedState.SaveableStateProvider("source") {
                    ExperienceSourceScreen("s", "episode", {}, {}, vm)
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(uiText(R.string.experience_source_load_more)))
        compose.onNodeWithText(uiText(R.string.experience_source_load_more)).performClick()
        scrollToOriginal("m39")
        val previousTop = compose.onNodeWithTag("source-message:m39").fetchSemanticsNode().boundsInRoot.top
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, firstPageReads)
            assertEquals(40, vm.page.value!!.messages.size)
        }
        compose.onNodeWithTag("source-message:m39").assertIsDisplayed()
        assertEquals(previousTop, compose.onNodeWithTag("source-message:m39").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("source-message:m39").assertIsDisplayed()
        assertEquals(previousTop, compose.onNodeWithTag("source-message:m39").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { assertEquals(1, firstPageReads) }
    }

    @Test fun historyRetainsLoadedPagesAndBrowsedPositionOnResumeAndReturn() {
        var windowReads = 0
        val visible = mutableStateOf(true)
        val vm = ChatLocationViewModel(
            readWindow = { _, _, _ ->
                windowReads++
                ChatHistoryWindow("s", "原会话", "m10", conversationRows(1..40), false, true)
            },
            readPage = { _, _, _, _ -> ChatHistoryPage(conversationRows(41..60), false) }
        )
        compose.setContent {
            MaterialTheme {
                val savedState = rememberSaveableStateHolder()
                if (visible.value) savedState.SaveableStateProvider("history") {
                    ChatLocationScreen("s", "m10", {}, {}, vm)
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(uiText(R.string.chat_location_newer)))
        compose.onNodeWithText(uiText(R.string.chat_location_newer)).performClick()
        scrollToOriginal("m59")
        val previousTop = compose.onNodeWithTag("source-message:m59").fetchSemanticsNode().boundsInRoot.top
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, windowReads)
            assertEquals(60, vm.window.value!!.messages.size)
        }
        compose.onNodeWithTag("source-message:m59").assertIsDisplayed()
        assertEquals(previousTop, compose.onNodeWithTag("source-message:m59").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("source-message:m59").assertIsDisplayed()
        assertEquals(previousTop, compose.onNodeWithTag("source-message:m59").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { assertEquals(1, windowReads) }
    }

    @Test fun sourceReloadsForExplicitRefreshAndEachChangedTarget() {
        val target = mutableStateOf("s" to "episode")
        val reads = mutableListOf<Pair<String, String>>()
        val vm = ExperienceSourceViewModel { session, episode, _ ->
            reads += session to episode
            ExperienceSourcePage(archive.copy(id = episode, sessionId = session), "原会话",
                listOf(original.copy(content = "$session/$episode")), 1, 1, false)
        }
        compose.setContent { MaterialTheme { ExperienceSourceScreen(target.value.first, target.value.second, {}, {}, vm) } }
        compose.onNodeWithText("s/episode").assertIsDisplayed()
        compose.onNodeWithContentDescription(uiText(R.string.experience_source_refresh)).performClick()
        compose.runOnIdle { assertEquals(listOf("s" to "episode", "s" to "episode"), reads) }
        compose.runOnIdle { target.value = "s" to "other-episode" }
        compose.onNodeWithText("s/other-episode").assertIsDisplayed()
        compose.runOnIdle { target.value = "other-session" to "other-episode" }
        compose.onNodeWithText("other-session/other-episode").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, reads.size) }
    }

    @Test fun historyReloadsAndLocatesForEachChangedTarget() {
        val target = mutableStateOf(Triple("s", "m10", "episode"))
        val reads = mutableListOf<Triple<String, String, String?>>()
        val vm = ChatLocationViewModel(readWindow = { session, message, episode ->
            reads += Triple(session, message, episode)
            ChatHistoryWindow(session, "原会话", message, conversationRows(1..40), false, false,
                archiveId = episode, sourceMessageIds = setOf(message))
        })
        compose.setContent { MaterialTheme {
            ChatLocationScreen(target.value.first, target.value.second, {}, {}, vm, target.value.third)
        } }
        compose.onNodeWithTag("source-message:m10").assertIsDisplayed()
        compose.runOnIdle { target.value = Triple("s", "m20", "episode") }
        compose.onNodeWithTag("source-message:m20").assertIsDisplayed()
        compose.runOnIdle { target.value = Triple("s", "m20", "other-episode") }
        compose.onNodeWithTag("source-message:m20").assertIsDisplayed()
        compose.runOnIdle { target.value = Triple("other-session", "m20", "other-episode") }
        compose.onNodeWithTag("source-message:m20").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(Triple("s", "m10", "episode"), Triple("s", "m20", "episode"),
                Triple("s", "m20", "other-episode"), Triple("other-session", "m20", "other-episode")), reads)
        }
    }

    @Test fun sourcePageShowsConversationAndOriginalAndLocatesById() {
        var located: String? = null
        val vm = ExperienceSourceViewModel { _, _, _ -> ExperienceSourcePage(archive, "姜晚星原会话", listOf(original), 1, 1, false) }
        compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, { located = it }, vm) } }
        compose.onNodeWithText(uiText(R.string.experience_source_session, "姜晚星原会话")).assertIsDisplayed()
        compose.onNodeWithText(original.content).assertIsDisplayed()
        compose.onNodeWithText(uiText(R.string.experience_source_jump)).performClick()
        compose.runOnIdle { assertEquals("original", located) }
    }

    @Test fun locatedMessageIsVisibleHighlightedAndLatestReturnsToSameConversation() {
        var latest = false
        val rows = (1..100).map { original.copy(id = "m$it", content = "周边原话 $it") }
        val vm = ChatLocationViewModel(readWindow = { _, _, _ -> ChatHistoryWindow("s", "姜晚星原会话", "m60", rows, true, true) })
        compose.setContent { MaterialTheme { ChatLocationScreen("s", "m60", {}, { latest = true }, vm) } }
        compose.onNodeWithText("周边原话 60").assertIsDisplayed()
        compose.onNodeWithText(uiText(R.string.chat_location_target)).assertIsDisplayed()
        compose.onNodeWithText(uiText(R.string.chat_location_latest)).performClick()
        compose.runOnIdle { assertEquals(true, latest) }
    }

    @Test fun returningToEvictedAnchorRecentersAndBrowsingRemainsBounded() {
        val vm = ChatLocationViewModel(
            readWindow = { _, _, _ ->
                ChatHistoryWindow("s", "原会话", "m60", (1..120).map { original.copy(id = "m$it", content = "周边原话 $it") }, true, true)
            },
            readPage = { _, cursor, older, _ ->
                val index = cursor.removePrefix("m").toInt()
                val range = if (older) (index - 60 until index) else (index + 1..index + 60)
                ChatHistoryPage(range.map { original.copy(id = "m$it", content = "周边原话 $it") }, true)
            }
        )
        compose.setContent { MaterialTheme { ChatLocationScreen("s", "m60", {}, {}, vm) } }
        compose.onNodeWithText("周边原话 60").assertIsDisplayed()
        repeat(5) {
            compose.runOnIdle { vm.loadMore(false) }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(300, vm.window.value!!.messages.size) }
        compose.onNodeWithText(uiText(R.string.chat_location_return_target)).performClick()
        compose.onNodeWithText("周边原话 60").assertIsDisplayed()
        compose.onNodeWithText(uiText(R.string.chat_location_target)).assertIsDisplayed()
    }

    @Test fun importedOtherCharacterShowsOwnNameWithCompactRepliesAndOneSegmentButton() {
        var located: String? = null
        val replies = listOf(original, original.copy(id = "a1", role = "assistant", content = "第一句回复"),
            original.copy(id = "a2", role = "assistant", content = "第二句回复"))
        val vm = ExperienceSourceViewModel { _, _, _ ->
            ExperienceSourcePage(archive, "另一个玩家的会话", replies, 3, 3, false, assistantName = "苏雨")
        }
        compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, { located = it }, vm) } }
        compose.onAllNodesWithText(uiText(R.string.experience_source_jump)).assertCountEquals(1)
        compose.onNodeWithTag("source-message:a2").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("苏雨").assertCountEquals(1)
        compose.onNodeWithText("助手").assertDoesNotExist()
        compose.onNodeWithTag("source-turn:a1").assertDoesNotExist()
        compose.onNodeWithTag("source-turn:a2").assertDoesNotExist()
        compose.onNodeWithTag("source-turn:original").assertIsDisplayed()
        val userBounds = compose.onNodeWithTag("source-bubble:original").fetchSemanticsNode().boundsInRoot
        val roleBounds = compose.onNodeWithTag("source-bubble:a1").fetchSemanticsNode().boundsInRoot
        val nextBounds = compose.onNodeWithTag("source-bubble:a2").fetchSemanticsNode().boundsInRoot
        assertTrue("User replies align right of role replies", userBounds.left > roleBounds.left)
        assertTrue("Consecutive replies have a small gap", nextBounds.top - roleBounds.bottom <= 16f)
        captureFixture("source-bubbles-other-role.png")
        compose.onNodeWithText(uiText(R.string.experience_source_jump)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("original", located) }
    }

    @Test fun sourcePaginationMergesContinuousReplyIntoExistingTurnAndKeepsSingleAnchor() {
        var located: String? = null
        val a1 = original.copy(id = "a1", role = "assistant", content = "前页的回复")
        val a2 = a1.copy(id = "a2", content = "后页的连续回复")
        val vm = ExperienceSourceViewModel { _, _, cursor ->
            if (cursor == null) ExperienceSourcePage(archive, "原会话", listOf(original, a1), 3, 3, true, "苏雨")
            else ExperienceSourcePage(archive, "原会话", listOf(a2), 3, 3, false, "苏雨")
        }
        compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, { located = it }, vm) } }
        compose.onNodeWithText(uiText(R.string.experience_source_load_more)).performScrollTo().performClick()
        compose.onNodeWithTag("source-message:a2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("source-turn:a2").assertDoesNotExist()
        compose.onNodeWithText(uiText(R.string.experience_source_jump)).performScrollTo().performClick()
        compose.onAllNodesWithText(uiText(R.string.experience_source_jump)).assertCountEquals(1)
        compose.runOnIdle { assertEquals("original", located) }
    }

    @Test fun segmentLocationKeepsRoleNameAndReturnsAfterSegmentLeavesBuffer() {
        var requestedArchive: String? = null
        val vm = ChatLocationViewModel(
            readWindow = { _, _, archiveId ->
                requestedArchive = archiveId
                ChatHistoryWindow("s", "苏雨原会话", "m60",
                    (1..120).map { original.copy(id = "m$it", role = "assistant", content = "连续原话 $it") },
                    true, true, "苏雨", archiveId, (60..70).map { "m$it" }.toSet())
            },
            readPage = { _, cursor, _, archiveId ->
                assertEquals("episode", archiveId)
                val index = cursor.removePrefix("m").toInt()
                ChatHistoryPage((index + 1..index + 60).map { original.copy(id = "m$it", role = "assistant", content = "连续原话 $it") }, true)
            }
        )
        compose.setContent { MaterialTheme { ChatLocationScreen("s", "m60", {}, {}, vm, "episode") } }
        compose.onNodeWithText(uiText(R.string.chat_location_segment_target)).assertIsDisplayed()
        compose.onNodeWithText("连续原话 60").assertIsDisplayed()
        compose.onNodeWithText("助手").assertDoesNotExist()
        compose.runOnIdle { assertEquals("episode", requestedArchive) }
        captureFixture("location-bubbles-fixture.png")
        repeat(5) {
            compose.runOnIdle { vm.loadMore(false) }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(300, vm.window.value!!.messages.size)
            assertEquals(emptySet<String>(), vm.window.value!!.sourceMessageIds)
        }
        compose.onNodeWithText(uiText(R.string.chat_location_return_segment)).performClick()
        compose.onNodeWithText(uiText(R.string.chat_location_segment_target)).assertIsDisplayed()
        compose.onNodeWithText("连续原话 60").assertIsDisplayed()
        compose.runOnIdle { assertEquals((60..70).map { "m$it" }.toSet(), vm.window.value!!.sourceMessageIds) }
    }

    @Test fun longOriginalCanExpandAndCollapseWithoutChangingBodyOrLocator() {
        var located: String? = null
        val longBody = (1..12).joinToString("\n") { "第${it}句原话：这是保留在原聊天里的完整文字。" }
        val longReply = original.copy(id = "long", role = "assistant", content = longBody)
        val vm = ExperienceSourceViewModel { _, _, _ ->
            ExperienceSourcePage(archive, "原会话", listOf(original, longReply), 2, 2, false, "苏雨")
        }
        compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, { located = it }, vm) } }
        compose.onNodeWithTag("source-message:long").performScrollTo().assertTextEquals(longBody)
        val collapsedHeight = compose.onNodeWithTag("source-message:long").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("source-expand:long").performScrollTo().performClick()
        val expandedHeight = compose.onNodeWithTag("source-message:long").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Expanded original has more visible lines", expandedHeight > collapsedHeight)
        compose.onNodeWithText(uiText(R.string.experience_source_original_collapse)).performScrollTo().performClick()
        val restoredHeight = compose.onNodeWithTag("source-message:long").fetchSemanticsNode().boundsInRoot.height
        assertEquals(collapsedHeight, restoredHeight, 1f)
        compose.onNodeWithText(uiText(R.string.experience_source_original_expand)).assertIsDisplayed()
        compose.onNodeWithText(uiText(R.string.experience_source_jump)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(longBody, vm.page.value!!.messages.last().content)
            assertEquals("original", located)
        }
    }

    @Test fun compactHeaderStillOffersCompleteExperienceSummary() {
        val vm = ExperienceSourceViewModel { _, _, _ -> ExperienceSourcePage(archive, "原会话", listOf(original), 1, 1, false) }
        compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, {}, vm) } }
        compose.onNodeWithTag("source-summary").assertDoesNotExist()
        compose.onNodeWithTag("source-summary-toggle").performClick()
        compose.onNodeWithTag("source-summary").assertTextEquals(archive.summary).assertIsDisplayed()
        compose.onNodeWithText(uiText(R.string.experience_source_summary_collapse)).performClick()
        compose.onNodeWithTag("source-summary").assertDoesNotExist()
        compose.onAllNodesWithText(uiText(R.string.experience_source_jump)).assertCountEquals(1)
    }

    @Test fun bubbleLayoutAtLargeFontKeepsShortEmotionsCompactAndOriginalExpandable() {
        val previewFontScale = mutableStateOf(1.3f)
        val replyBodies = listOf(
            "*(这个小坏蛋！吃了一堆零食还要囤着吃，就是不肯好好吃饭！还跟我“哼”！一副你能拿我怎样的臭屁样子！气死我了可是又没办法，隔着屏幕凶也凶不着他，手也够不着他的耳朵)*",
            "你！还！哼！", "[气鼓鼓]", "零食当饭吃还理直气壮，小嘴叭叭的就会气老婆～", "[生气_1]",
            "哼哼唧唧的，是不是还觉得自己挺有道理呢", "[歪头看你]", "行吧行吧，看在你吃饱了的份上，零食就零食吧……但是！饿了必须给我吃热乎的。", "[打手手]"
        )
        val input = original.copy(content = "我还要好多零食饿了再吃")
        val rows = listOf(input, input.copy(id = "emotion", content = "[哼]")) + replyBodies.mapIndexed { index, body ->
            original.copy(id = "reply-$index", role = "assistant", content = body)
        }
        val vm = ExperienceSourceViewModel { _, _, _ ->
            ExperienceSourcePage(archive.copy(summary = "零食与饿了再吃的饮食回应"), "姜晚星｜爱语完整对话", rows, rows.size, rows.size, false, "姜晚星")
        }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, previewFontScale.value)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFFF5F96), primaryContainer = Color(0xFFFFDFEB),
                    background = Color(0xFFF9F7FC), surface = Color.White), typography = com.nekobot.app.ui.theme.NekobotTypography) {
                    ExperienceSourceScreen("s", "episode", {}, {}, vm)
                }
            }
        }
        compose.onNodeWithText(uiText(R.string.experience_source_jump)).assertIsDisplayed()
        compose.onNodeWithTag("source-message:emotion").assertTextEquals("[哼]")
        val emotion = compose.onNodeWithTag("source-bubble:emotion").fetchSemanticsNode().boundsInRoot
        val user = compose.onNodeWithTag("source-bubble:original").fetchSemanticsNode().boundsInRoot
        assertTrue("Short emotion bubble fits its own content", emotion.width < user.width)
        compose.onNodeWithTag("source-expand:reply-0").performScrollTo().assertIsDisplayed()
        captureFixture("source-bubbles-large-font.png")
        compose.onNodeWithTag("source-expand:reply-0").performClick()
        compose.onNodeWithTag("source-message:reply-0").assertTextEquals(replyBodies.first())
        compose.onNodeWithText(uiText(R.string.experience_source_original_collapse)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(rows, vm.page.value!!.messages) }
        compose.runOnIdle { previewFontScale.value = 1f }
        compose.onNodeWithText(uiText(R.string.experience_source_jump)).performScrollTo().assertIsDisplayed()
        captureFixture("source-bubbles-normal-font.png")
    }
}
