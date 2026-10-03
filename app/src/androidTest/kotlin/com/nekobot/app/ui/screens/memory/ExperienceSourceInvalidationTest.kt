package com.nekobot.app.ui.screens.memory

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ChatHistoryPage
import com.nekobot.app.data.local.ChatHistoryWindow
import com.nekobot.app.data.local.ExperienceSourcePage
import com.nekobot.app.data.local.ExperienceSourceReader
import com.nekobot.app.data.local.db.LocalExperienceArchiveEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ExperienceSourceInvalidationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java).build()
    private fun rows(prefix: String, range: IntRange) = range.map { LocalMessageEntity(
        id = "m$it", sessionId = "s", role = if (it % 2 == 1) "user" else "assistant",
        content = "$prefix 原话 $it", timestamp = "2026-10-02 18:02:00", createdAt = "2026-10-02 18:02:00"
    ) }
    private fun episode() = LocalExperienceArchiveEntity(
        id = "episode", sessionId = "s", startMessageId = "m1", endMessageId = "m160",
        sourceStartedAt = "now", sourceEndedAt = "now", summary = "摘要", sourceFingerprint = "f", createdAt = "now", updatedAt = "now"
    )
    private fun seed(db: NekobotDatabase, prefix: String, size: Int = 160) = runBlocking {
        db.sessionDao().upsert(LocalSessionEntity(id = "s", name = "$prefix 会话", createdAt = "now", updatedAt = "now"))
        db.messageDao().upsertAll(rows(prefix, 1..size))
        db.experienceArchiveDao().saveGenerated(episode().copy(endMessageId = "m$size"), (1..size).map { "m$it" })
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun scrollTo(id: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("source-message:$id"))
        compose.onNodeWithTag("source-message:$id").assertIsDisplayed()
    }
    private fun clickPage(id: Int) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(id)))
        compose.onNodeWithText(text(id)).performClick()
    }

    /** Real writes from a separate chat reproduce table-wide Room invalidation. */
    private fun writeOtherChatProgress(db: NekobotDatabase) = runBlocking {
        db.sessionDao().upsert(LocalSessionEntity(id = "other", name = "后台会话", createdAt = "now", updatedAt = "now"))
        repeat(8) { step ->
            db.messageDao().upsert(rows("后台", 1..1).single().copy(
                id = "other-progress", sessionId = "other", thinkingCards = "[{\"title\":\"step-$step\"}]"
            ))
            delay(25)
        }
    }

    @Test fun otherChatWritesDoNotCancelSourcePaginationAndOwnEditsAreRevalidated() = sourcePaginationSurvivesWrites(fail = false)
    @Test fun backgroundRefreshDoesNotHideSourcePaginationFailure() = sourcePaginationSurvivesWrites(fail = true)

    private fun sourcePaginationSurvivesWrites(fail: Boolean) {
        val db = database(); seed(db, "A", 180)
        val reader = ExperienceSourceReader(db)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false); val pageReads = AtomicInteger()
        val invalidations = AtomicInteger()
        val observer = object : InvalidationTracker.Observer("local_messages") {
            override fun onInvalidated(tables: Set<String>) { invalidations.incrementAndGet() }
        }
        db.invalidationTracker.addObserver(observer)
        val vm = ExperienceSourceViewModel(MutableStateFlow(0L), { reader }) { session, archive, cursor ->
            val result = reader.sourcePage(session, archive, cursor)
            if (cursor != null) {
                pageReads.incrementAndGet(); started.complete(Unit)
                try { release.await() } catch (error: CancellationException) { cancelled.set(true); throw error }
                if (fail) error("Synthetic pagination failure")
            }
            result
        }
        try {
            compose.runOnIdle { vm.load("s", "episode") }
            compose.waitUntil(10_000) { vm.page.value?.messages?.size == 60 && !vm.loading.value }
            compose.runOnIdle { vm.loadMore() }
            compose.waitUntil(5000) { started.isCompleted }
            writeOtherChatProgress(db)
            runBlocking {
                db.messageDao().updateContent("m1", "已修改 原话 1")
                db.messageDao().updateDeleted("m2", true)
            }
            compose.waitUntil(5000) { invalidations.get() > 0 }
            compose.runOnIdle {
                assertFalse("Background writes cancelled source pagination", cancelled.get())
                assertTrue("The user read must remain in progress", vm.loading.value)
            }
            release.complete(Unit)
            compose.waitUntil(10_000) {
                val completed = if (fail) vm.error.value != null && vm.page.value?.messages?.size == 59
                    else vm.page.value?.messages?.any { it.id == "m120" } == true
                completed && !vm.loading.value
            }
            compose.runOnIdle {
                val page = vm.page.value!!
                val end = if (fail) 60 else 120
                assertEquals((1..end).filter { it != 2 }.map { "m$it" }, page.messages.map { it.id })
                assertEquals("已修改 原话 1", page.messages.first().content)
                assertEquals(1, pageReads.get())
                assertTrue(page.hasMore)
                if (fail) assertNotNull(vm.error.value) else assertNull(vm.error.value)
            }
        } finally {
            release.complete(Unit); compose.runOnIdle { vm.viewModelScope.cancel() }
            db.invalidationTracker.removeObserver(observer); db.close()
        }
    }

    @Test fun otherChatWritesDoNotCancelNewerHistoryPagination() = historyPaginationSurvivesWrites(older = false)
    @Test fun otherChatWritesDoNotCancelOlderHistoryPagination() = historyPaginationSurvivesWrites(older = true)

    private fun historyPaginationSurvivesWrites(older: Boolean) {
        val db = database(); seed(db, "A", 300)
        val reader = ExperienceSourceReader(db)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false); val pageReads = AtomicInteger()
        val invalidations = AtomicInteger()
        val observer = object : InvalidationTracker.Observer("local_messages") {
            override fun onInvalidated(tables: Set<String>) { invalidations.incrementAndGet() }
        }
        db.invalidationTracker.addObserver(observer)
        val vm = ChatLocationViewModel(MutableStateFlow(0L), { reader },
            readWindow = { session, message, archive -> reader.historyWindow(session, message, archive) },
            readPage = { session, cursor, direction, archive ->
                val result = reader.historyPage(session, cursor, direction, archive)
                pageReads.incrementAndGet(); started.complete(Unit)
                try { release.await() } catch (error: CancellationException) { cancelled.set(true); throw error }
                result
            })
        try {
            compose.runOnIdle { vm.load("s", "m120", "episode") }
            compose.waitUntil(10_000) { vm.window.value?.messages?.size == 121 && !vm.loading.value }
            compose.runOnIdle { vm.loadMore(older) }
            compose.waitUntil(5000) { started.isCompleted }
            writeOtherChatProgress(db)
            runBlocking {
                db.messageDao().updateContent("m60", "已修改 原话 60")
                db.messageDao().updateDeleted("m61", true)
            }
            compose.waitUntil(5000) { invalidations.get() > 0 }
            compose.runOnIdle {
                assertFalse("Background writes cancelled history pagination", cancelled.get())
                assertTrue("The user read must remain in progress", vm.loading.value)
            }
            release.complete(Unit)
            val endpoint = if (older) "m1" else "m240"
            compose.waitUntil(10_000) { vm.window.value?.messages?.any { it.id == endpoint } == true && !vm.loading.value }
            compose.runOnIdle {
                val window = vm.window.value!!
                val expected = (if (older) 1..180 else 60..240).filter { it != 61 }.map { "m$it" }
                assertEquals(expected, window.messages.map { it.id })
                assertEquals("已修改 原话 60", window.messages.single { it.id == "m60" }.content)
                assertFalse("m61" in window.sourceMessageIds)
                assertEquals(1, pageReads.get())
                assertNull(vm.error.value)
            }
        } finally {
            release.complete(Unit); compose.runOnIdle { vm.viewModelScope.cancel() }
            db.invalidationTracker.removeObserver(observer); db.close()
        }
    }

    @Test fun retiredArchiveListClearsPrivateMetadataAndRejectsQueuedSourceClickAndSave() {
        val suffix = System.nanoTime().toString()
        val sessionId = "parent-session-$suffix"
        val archiveId = "parent-episode-$suffix"
        val messageId = "parent-message-$suffix"
        val db = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
        runBlocking {
            db.sessionDao().upsert(LocalSessionEntity(id = sessionId, name = "父页测试会话", createdAt = "now", updatedAt = "now"))
            db.messageDao().upsert(rows("父页", 1..1).single().copy(id = messageId, sessionId = sessionId))
            db.experienceArchiveDao().saveGenerated(episode().copy(id = archiveId, sessionId = sessionId,
                startMessageId = messageId, endMessageId = messageId, summary = "父页档案原摘要"), listOf(messageId))
        }
        val revisions = MutableStateFlow(0L)
        val vm = ExperienceArchiveViewModel(revisions)
        var opened = 0
        try {
            compose.setContent { MaterialTheme { ExperienceArchiveScreen(sessionId, {}, { opened++ }, vm) } }
            compose.waitUntil(10_000) { vm.archives.value.size == 1 }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text(R.string.experience_source_open)))
            val oldClick = compose.onNodeWithText(text(R.string.experience_source_open))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle {
                revisions.value++
                oldClick.invoke()
                vm.save(archiveId, "不应写入", emptyList())
                vm.load(sessionId)
                vm.loadMore()
            }
            compose.waitUntil(5000) { vm.archives.value.isEmpty() && vm.backfillInfo.value == null }
            compose.onNodeWithText(text(R.string.experience_source_database_changed)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.experience_source_open)).assertDoesNotExist()
            compose.onNodeWithText("父页档案原摘要").assertDoesNotExist()
            assertEquals(0, opened)
            runBlocking { assertEquals("父页档案原摘要", db.experienceArchiveDao().getById(archiveId)!!.summary) }
        } finally {
            compose.runOnIdle { vm.viewModelScope.cancel() }
            runBlocking { db.sessionDao().deleteById(sessionId) }
        }
    }

    @Test fun applicationProfileSwitchRetiresAllThreeViewsBeforeOpeningMatchingIdsInTheNewProfile() {
        val originalProfile = ServiceContainer.prefs.activeDbName
        val prefix = "source-cache-test-${System.nanoTime()}"
        val profileA = "$prefix-a"; val profileB = "$prefix-b"
        var archive: ExperienceArchiveViewModel? = null
        var history: ChatLocationViewModel? = null
        val models = mutableListOf<ExperienceSourceViewModel>()
        val shown = mutableStateOf<ExperienceSourceViewModel?>(null)
        try {
            compose.runOnIdle { ServiceContainer.switchLocalDb(profileA) }
            seed(NekobotDatabase.get(compose.activity, profileA), "ProfileA", 10)
            compose.runOnIdle {
                archive = ExperienceArchiveViewModel().also { it.load("s") }
                history = ChatLocationViewModel().also { it.load("s", "m1", "episode") }
                shown.value = ExperienceSourceViewModel().also { models.add(it) }
            }
            compose.setContent { MaterialTheme {
                // A fresh navigation entry has its own composition even when route IDs match.
                shown.value?.let { model -> key(model) { ExperienceSourceScreen("s", "episode", {}, {}, model) } }
            } }
            compose.waitUntil(10_000) { shown.value?.page?.value?.messages?.size == 10 &&
                archive?.archives?.value?.size == 1 && history?.window?.value?.messages?.size == 10 }
            compose.onNodeWithText("ProfileA 原话 1").assertIsDisplayed()
            compose.runOnIdle { ServiceContainer.switchLocalDb(profileB) }
            seed(NekobotDatabase.get(compose.activity, profileB), "ProfileB", 10)
            compose.waitUntil(10_000) { models.first().page.value == null &&
                archive?.archives?.value?.isEmpty() == true && history?.window?.value == null }
            compose.onNodeWithText(text(R.string.experience_source_database_changed)).assertIsDisplayed()
            compose.onNodeWithText("ProfileA 原话 1").assertDoesNotExist()
            compose.runOnIdle {
                models.first().load("s", "episode"); archive!!.load("s"); history!!.load("s", "m1", "episode")
                assertNull(models.first().page.value); assertTrue(archive!!.archives.value.isEmpty()); assertNull(history!!.window.value)
                shown.value = ExperienceSourceViewModel().also { models.add(it) }
            }
            compose.waitUntil(10_000) { models.last().page.value?.messages?.size == 10 }
            compose.onNodeWithText("ProfileB 原话 1").assertIsDisplayed()
            compose.onNodeWithText("ProfileA 原话 1").assertDoesNotExist()
        } finally {
            compose.runOnIdle {
                models.forEach { it.viewModelScope.cancel() }
                archive?.viewModelScope?.cancel(); history?.viewModelScope?.cancel()
                ServiceContainer.switchLocalDb(originalProfile)
            }
            NekobotDatabase.deleteProfileFile(compose.activity, profileA)
            NekobotDatabase.deleteProfileFile(compose.activity, profileB)
        }
    }

    @Test fun sourceUpdatesEditedDeletedAndUnmappedOriginalsWhileKeepingLoadedPagesAndPosition() {
        val db = database()
        seed(db, "A", 120)
        val vm = ExperienceSourceViewModel(readerFactory = { ExperienceSourceReader(db) })
        try {
            compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, {}, vm) } }
            compose.waitUntil(10_000) { vm.page.value?.messages?.size == 60 }
            clickPage(R.string.experience_source_load_more)
            compose.waitUntil(10_000) { vm.page.value?.messages?.size == 120 }
            scrollTo("m119")
            val top = compose.onNodeWithTag("source-message:m119").fetchSemanticsNode().boundsInRoot.top
            runBlocking { db.withTransaction {
                db.messageDao().updateContent("m110", "A 已编辑 110")
                db.messageDao().updateDeleted("m20", true)
                db.experienceArchiveDao().replaceSources("episode", (1..120).filter { it != 30 }.map { "m$it" })
            } }
            compose.waitUntil(10_000) { vm.page.value?.messages?.size == 118 &&
                vm.page.value?.messages?.any { it.content == "A 已编辑 110" } == true }
            compose.onNodeWithTag("source-message:m119").assertIsDisplayed()
            assertEquals(top, compose.onNodeWithTag("source-message:m119").fetchSemanticsNode().boundsInRoot.top, 1f)
            assertFalse(vm.page.value!!.messages.any { it.id == "m20" || it.id == "m30" })
            assertEquals(119, vm.page.value!!.sourceCount)
            assertEquals(118, vm.page.value!!.availableCount)
            runBlocking { db.sessionDao().deleteById("s") }
            compose.waitUntil(10_000) { vm.page.value == null }
            compose.onNodeWithTag("source-message:m119").assertDoesNotExist()
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() }; db.close() }
    }

    @Test fun historyUpdatesBrowsableTextAndHighlightsWithoutRecenteringAndClearsDeletedAnchor() {
        val db = database()
        seed(db, "A")
        val vm = ChatLocationViewModel(readerFactory = { ExperienceSourceReader(db) })
        try {
            compose.setContent { MaterialTheme { ChatLocationScreen("s", "m30", {}, {}, vm, "episode") } }
            compose.waitUntil(10_000) { vm.window.value?.messages?.size == 90 }
            clickPage(R.string.chat_location_newer)
            compose.waitUntil(10_000) { vm.window.value?.messages?.size == 150 }
            scrollTo("m149")
            val top = compose.onNodeWithTag("source-message:m149").fetchSemanticsNode().boundsInRoot.top
            val locationRevision = vm.locationRevision.value
            runBlocking { db.withTransaction {
                db.messageDao().updateContent("m140", "A 已编辑 140")
                db.messageDao().updateDeleted("m20", true)
                db.experienceArchiveDao().replaceSources("episode", (1..160).filter { it != 130 }.map { "m$it" })
            } }
            compose.waitUntil(10_000) { vm.window.value?.messages?.size == 149 &&
                vm.window.value?.messages?.any { it.content == "A 已编辑 140" } == true }
            compose.onNodeWithTag("source-message:m149").assertIsDisplayed()
            assertEquals(top, compose.onNodeWithTag("source-message:m149").fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(locationRevision, vm.locationRevision.value)
            assertFalse("m130" in vm.window.value!!.sourceMessageIds)
            assertTrue(vm.window.value!!.messages.any { it.id == "m130" })
            runBlocking { db.messageDao().updateDeleted("m30", true) }
            compose.waitUntil(10_000) { vm.window.value == null }
            compose.onNodeWithTag("source-message:m149").assertDoesNotExist()
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() }; db.close() }
    }

    @Test fun switchingRealDatabasesWithMatchingIdsClearsBothReadersAndRequiresFreshEntry() {
        val dbA = database(); val dbB = database()
        seed(dbA, "A", 10); seed(dbB, "B", 10)
        val revisions = MutableStateFlow(0L)
        var active = dbA
        val sourceA = ExperienceSourceViewModel(revisions, { ExperienceSourceReader(active) })
        val historyA = ChatLocationViewModel(revisions, { ExperienceSourceReader(active) })
        val unopenedSource = ExperienceSourceViewModel(revisions, { ExperienceSourceReader(active) })
        val unopenedHistory = ChatLocationViewModel(revisions, { ExperienceSourceReader(active) })
        val source = mutableStateOf(sourceA)
        val showHistory = mutableStateOf(false)
        var located = 0; var latest = 0
        var sourceB: ExperienceSourceViewModel? = null
        try {
            compose.setContent { MaterialTheme {
                if (showHistory.value) ChatLocationScreen("s", "m1", {}, { latest++ }, historyA, "episode")
                else ExperienceSourceScreen("s", "episode", {}, { located++ }, source.value)
            } }
            compose.waitUntil(10_000) { sourceA.page.value?.messages?.size == 10 }
            compose.runOnIdle { historyA.load("s", "m1", "episode") }
            compose.waitUntil(10_000) { historyA.window.value?.messages?.size == 10 }
            compose.onNodeWithText("A 原话 1").assertIsDisplayed()
            val queuedLocate = compose.onNodeWithText(text(R.string.experience_source_jump))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { showHistory.value = true }
            val queuedLatest = compose.onNodeWithText(text(R.string.chat_location_latest))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { showHistory.value = false }
            // Repository replacement is detectable even before its revision signal arrives.
            compose.runOnIdle {
                active = dbB; sourceA.loadMore(); historyA.loadMore(false)
                unopenedSource.load("s", "episode"); unopenedHistory.load("s", "m1", "episode")
                queuedLocate.invoke(); queuedLatest.invoke()
                assertNull(unopenedSource.page.value); assertNull(unopenedHistory.window.value)
                assertFalse(unopenedSource.ownsCurrentDatabase()); assertFalse(unopenedHistory.ownsCurrentDatabase())
                assertEquals(0, located); assertEquals(0, latest)
            }
            compose.runOnIdle { assertNull(sourceA.page.value); assertNull(historyA.window.value); revisions.value++ }
            compose.waitUntil(10_000) { sourceA.page.value == null && historyA.window.value == null }
            compose.onNodeWithText("A 原话 1").assertDoesNotExist()
            compose.onNodeWithText("B 原话 1").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.experience_source_database_changed)).assertIsDisplayed()
            compose.onNodeWithContentDescription(text(R.string.experience_source_refresh)).assertIsNotEnabled()
            compose.runOnIdle {
                sourceA.load("s", "episode"); sourceA.loadMore()
                historyA.load("s", "m1", "episode"); historyA.loadMore(false)
                showHistory.value = true
            }
            compose.onNodeWithText(text(R.string.experience_source_database_changed)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.chat_location_latest)).assertIsNotEnabled()
            assertNull(sourceA.page.value); assertNull(historyA.window.value)
            compose.runOnIdle {
                sourceB = ExperienceSourceViewModel(revisions, { ExperienceSourceReader(active) })
                source.value = sourceB!!
                showHistory.value = false
            }
            compose.waitUntil(10_000) { sourceB?.page?.value?.messages?.size == 10 }
            compose.onNodeWithText("B 原话 1").assertIsDisplayed()
            compose.onNodeWithText("A 原话 1").assertDoesNotExist()
        } finally {
            compose.runOnIdle { sourceA.viewModelScope.cancel(); historyA.viewModelScope.cancel(); sourceB?.viewModelScope?.cancel() }
            compose.runOnIdle { unopenedSource.viewModelScope.cancel(); unopenedHistory.viewModelScope.cancel() }
            dbA.close(); dbB.close()
        }
    }

    @Test fun lateSourcePaginationCannotRestoreTextAfterDatabaseChange() {
        val revisions = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val returned = AtomicBoolean(false)
        var reads = 0
        val vm = ExperienceSourceViewModel(dataSourceRevision = revisions) { _, _, cursor ->
            reads++
            if (cursor == null) ExperienceSourcePage(episode(), "A 会话", rows("A", 1..5), 10, 10, true)
            else withContext(NonCancellable) {
                started.complete(Unit); release.await(); returned.set(true)
                ExperienceSourcePage(episode(), "A 会话", rows("A", 6..10), 10, 10, false)
            }
        }
        try {
            compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, {}, vm) } }
            compose.onNodeWithText("A 原话 1").assertIsDisplayed()
            clickPage(R.string.experience_source_load_more)
            compose.waitUntil(5000) { started.isCompleted }
            compose.runOnIdle { revisions.value++ }
            compose.waitUntil(5000) { vm.page.value == null }
            release.complete(Unit)
            compose.waitUntil(5000) { returned.get() }
            compose.runOnIdle { vm.load("s", "episode"); vm.loadMore(); assertNull(vm.page.value); assertEquals(2, reads) }
            compose.onNodeWithText("A 原话 6").assertDoesNotExist()
        } finally { release.complete(Unit); compose.runOnIdle { vm.viewModelScope.cancel() } }
    }

    @Test fun lateInitialHistoryReadCannotRestoreTextAfterDatabaseChange() {
        val revisions = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val returned = AtomicBoolean(false)
        val vm = ChatLocationViewModel(dataSourceRevision = revisions, readWindow = { _, _, _ ->
            withContext(NonCancellable) {
                started.complete(Unit); release.await(); returned.set(true)
                ChatHistoryWindow("s", "A 会话", "m1", rows("A", 1..10), false, false)
            }
        })
        try {
            compose.setContent { MaterialTheme { ChatLocationScreen("s", "m1", {}, {}, vm) } }
            compose.waitUntil(5000) { started.isCompleted }
            compose.runOnIdle { revisions.value++ }
            release.complete(Unit)
            compose.waitUntil(5000) { returned.get() }
            compose.runOnIdle { assertNull(vm.window.value) }
            compose.onNodeWithText("A 原话 1").assertDoesNotExist()
        } finally { release.complete(Unit); compose.runOnIdle { vm.viewModelScope.cancel() } }
    }

    @Test fun lateInitialSourceReadCannotRestoreTextAfterDatabaseChange() {
        val revisions = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val returned = AtomicBoolean(false)
        val vm = ExperienceSourceViewModel(dataSourceRevision = revisions) { _, _, _ ->
            withContext(NonCancellable) {
                started.complete(Unit); release.await(); returned.set(true)
                ExperienceSourcePage(episode(), "A 会话", rows("A", 1..10), 10, 10, false)
            }
        }
        try {
            compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, {}, vm) } }
            compose.waitUntil(5000) { started.isCompleted }
            compose.runOnIdle { revisions.value++ }
            release.complete(Unit)
            compose.waitUntil(5000) { returned.get() }
            compose.runOnIdle { assertNull(vm.page.value) }
            compose.onNodeWithText("A 原话 1").assertDoesNotExist()
        } finally { release.complete(Unit); compose.runOnIdle { vm.viewModelScope.cancel() } }
    }

    @Test fun retainedUnopenedReadersCannotBindToTheNextDatabase() {
        val revisions = MutableStateFlow(0L)
        var sourceReads = 0; var historyReads = 0
        val source = ExperienceSourceViewModel(dataSourceRevision = revisions) { _, _, _ ->
            sourceReads++; ExperienceSourcePage(episode(), "B 会话", rows("B", 1..10), 10, 10, false)
        }
        val history = ChatLocationViewModel(dataSourceRevision = revisions, readWindow = { _, _, _ ->
            historyReads++; ChatHistoryWindow("s", "B 会话", "m1", rows("B", 1..10), false, false)
        })
        try {
            compose.runOnIdle { revisions.value++; source.load("s", "episode"); history.load("s", "m1", "episode") }
            compose.setContent { MaterialTheme { ExperienceSourceScreen("s", "episode", {}, {}, source) } }
            compose.onNodeWithText(text(R.string.experience_source_database_changed)).assertIsDisplayed()
            compose.runOnIdle { assertNull(source.page.value); assertNull(history.window.value); assertEquals(0, sourceReads); assertEquals(0, historyReads) }
        } finally { compose.runOnIdle { source.viewModelScope.cancel(); history.viewModelScope.cancel() } }
    }

    @Test fun lateHistoryPaginationCannotMixRowsWithAnotherDatabase() {
        val revisions = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val returned = AtomicBoolean(false)
        var pageReads = 0
        val vm = ChatLocationViewModel(dataSourceRevision = revisions,
            readWindow = { _, _, _ -> ChatHistoryWindow("s", "A 会话", "m1", rows("A", 1..5), false, true) },
            readPage = { _, _, _, _ ->
                pageReads++
                withContext(NonCancellable) {
                    started.complete(Unit); release.await(); returned.set(true)
                    ChatHistoryPage(rows("A", 6..10), false)
                }
            })
        try {
            compose.setContent { MaterialTheme { ChatLocationScreen("s", "m1", {}, {}, vm) } }
            compose.onNodeWithText("A 原话 1").assertIsDisplayed()
            clickPage(R.string.chat_location_newer)
            compose.waitUntil(5000) { started.isCompleted }
            compose.runOnIdle { revisions.value++ }
            compose.waitUntil(5000) { vm.window.value == null }
            release.complete(Unit)
            compose.waitUntil(5000) { returned.get() }
            compose.runOnIdle { vm.loadMore(false); assertNull(vm.window.value); assertEquals(1, pageReads) }
            compose.onNodeWithText("A 原话 6").assertDoesNotExist()
        } finally { release.complete(Unit); compose.runOnIdle { vm.viewModelScope.cancel() } }
    }
}
