package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalFailoverHealthEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FailoverCoordinatorTest {

    @Test
    fun executeSwitchesToNextModelAndRecordsActualAttempt() = runBlocking {
        val store = FakeHealthStore()
        val coordinator = FailoverCoordinator(store, FakeUsageReader())
        val models = listOf(model("primary"), model("backup"))

        val execution = coordinator.execute(models, "chat") { current ->
            if (current.id == "primary") {
                throw FailoverHttpException(503, "primary unavailable")
            }
            "generated-by-${current.id}"
        }

        assertEquals("generated-by-backup", execution.value)
        assertEquals("backup", execution.model.id)
        assertEquals(listOf("primary", "backup"), execution.attempts)
        assertEquals(1, store.values["primary"]?.consecutiveFailures)
        assertEquals(503, store.values["primary"]?.lastFailureCode)
        assertEquals(0, store.values["backup"]?.consecutiveFailures)
    }

    @Test
    fun executeSkipsModelAtDailyTokenLimit() = runBlocking {
        val store = FakeHealthStore()
        val usage = FakeUsageReader(
            mapOf("limited" to FailoverUsage(dailyTokens = 100))
        )
        val coordinator = FailoverCoordinator(store, usage)
        val models = listOf(
            model("limited", tokenLimitDaily = 100),
            model("backup")
        )

        val execution = coordinator.execute(models, "chat") { it.id }

        assertEquals("backup", execution.value)
        assertEquals(listOf("backup"), execution.attempts)
    }

    @Test
    fun executeSkipsModelStillInCooldown() = runBlocking {
        val now = 1_000L
        val store = FakeHealthStore().apply {
            values["cooling"] = LocalFailoverHealthEntity(
                modelId = "cooling",
                consecutiveFailures = 1,
                cooldownUntilMs = now + 60_000
            )
        }
        val coordinator = FailoverCoordinator(store, FakeUsageReader()) { now }

        val execution = coordinator.execute(
            listOf(model("cooling"), model("backup")),
            "chat"
        ) { it.id }

        assertEquals("backup", execution.value)
        assertEquals(listOf("backup"), execution.attempts)
        assertNotNull(store.values["cooling"])
    }

    @Test
    fun executeSkipsSmallerContextModelsAfterFailover() = runBlocking {
        val store = FakeHealthStore()
        val coordinator = FailoverCoordinator(store, FakeUsageReader())
        val models = listOf(
            model("primary", context = 4_096),
            model("too-small", context = 1_024),
            model("large-enough", context = 8_192)
        )

        val execution = coordinator.execute(
            models = models,
            purpose = "chat",
            requiredContextTokens = 2_048
        ) { current ->
            if (current.id == "primary") {
                throw FailoverHttpException(503, "primary unavailable")
            }
            current.id
        }

        assertEquals("large-enough", execution.value)
        assertEquals(listOf("primary", "large-enough"), execution.attempts)
    }

    private fun model(
        id: String,
        tokenLimitDaily: Long = 0,
        context: Int? = null
    ): LocalAiModelEntity = LocalAiModelEntity(
        id = id,
        name = id,
        protocol = "openai_chat",
        apiKey = "test-key",
        baseUrl = "https://example.com/v1",
        model = id,
        purpose = "chat",
        createdAt = "2026-07-27",
        tokenLimitDaily = tokenLimitDaily,
        maxContextLength = context
    )

    private class FakeHealthStore : FailoverHealthStore {
        val values = linkedMapOf<String, LocalFailoverHealthEntity>()

        override suspend fun get(modelId: String): LocalFailoverHealthEntity? = values[modelId]

        override suspend fun upsert(entity: LocalFailoverHealthEntity) {
            values[entity.modelId] = entity
        }

        override suspend fun listAll(): List<LocalFailoverHealthEntity> = values.values.toList()

        override suspend fun delete(modelId: String) {
            values.remove(modelId)
        }

        override suspend fun clear() {
            values.clear()
        }
    }

    private class FakeUsageReader(
        private val values: Map<String, FailoverUsage> = emptyMap()
    ) : FailoverUsageReader {
        override suspend fun getUsage(modelId: String): FailoverUsage =
            values[modelId] ?: FailoverUsage()
    }
}
