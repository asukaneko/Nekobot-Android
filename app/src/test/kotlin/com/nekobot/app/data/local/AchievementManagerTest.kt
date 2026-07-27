package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementManagerTest {

    @Test
    fun targetsContainFiveOrderedTiersPerMetric() {
        val targets = AchievementManager.targets

        assertEquals(35, targets.size)
        assertEquals(targets.size, targets.map { it.id }.distinct().size)

        val expectedTargets = mapOf(
            AchievementManager.Target.Metric.TOKENS to listOf(1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L),
            AchievementManager.Target.Metric.MESSAGES to listOf(10L, 100L, 1_000L, 5_000L, 10_000L),
            AchievementManager.Target.Metric.SESSIONS to listOf(1L, 10L, 50L, 100L, 500L),
            AchievementManager.Target.Metric.HIGH_AFFECTION_CHARACTERS to listOf(1L, 3L, 5L, 10L, 20L),
            AchievementManager.Target.Metric.CHARACTERS to listOf(1L, 3L, 10L, 30L, 100L),
            AchievementManager.Target.Metric.WORLD_BOOKS to listOf(1L, 3L, 10L, 30L, 100L),
            AchievementManager.Target.Metric.FAVORITE_SESSIONS to listOf(1L, 5L, 20L, 50L, 100L)
        )
        expectedTargets.forEach { (metric, expected) ->
            val metricTargets = targets.filter { it.metric == metric }
            assertEquals(5, metricTargets.size)
            assertEquals(expected, metricTargets.map { it.target })
            assertEquals(
                AchievementManager.Target.Tier.entries,
                metricTargets.map { it.tier }
            )
            assertTrue(metricTargets.zipWithNext().all { (left, right) ->
                left.target < right.target
            })
        }
    }

    @Test
    fun highAffectionAchievementsCountCharactersAtNinetyOrAbove() {
        val targets = AchievementManager.targets.filter {
            it.metric == AchievementManager.Target.Metric.HIGH_AFFECTION_CHARACTERS
        }

        assertEquals(listOf(1L, 3L, 5L, 10L, 20L), targets.map { it.target })
        assertEquals(
            AchievementManager.Id.HIGH_AFFECTION_CHARACTERS_1,
            targets.first().id
        )
    }

    @Test
    fun snapshotsUseTheCorrectValueForEveryMetric() {
        val snapshots = AchievementManager.getSnapshots(
            totalTokens = 11,
            totalMessages = 22,
            totalSessions = 33,
            highAffectionCharacterCount = 44,
            characterCount = 55,
            worldBookCount = 66,
            favoriteSessionCount = 77
        )
        val currentByMetric = snapshots
            .groupBy { AchievementManager.targetFor(it.id)!!.metric }
            .mapValues { (_, metricSnapshots) -> metricSnapshots.map { it.current }.distinct().single() }

        assertEquals(11L, currentByMetric[AchievementManager.Target.Metric.TOKENS])
        assertEquals(22L, currentByMetric[AchievementManager.Target.Metric.MESSAGES])
        assertEquals(33L, currentByMetric[AchievementManager.Target.Metric.SESSIONS])
        assertEquals(44L, currentByMetric[AchievementManager.Target.Metric.HIGH_AFFECTION_CHARACTERS])
        assertEquals(55L, currentByMetric[AchievementManager.Target.Metric.CHARACTERS])
        assertEquals(66L, currentByMetric[AchievementManager.Target.Metric.WORLD_BOOKS])
        assertEquals(77L, currentByMetric[AchievementManager.Target.Metric.FAVORITE_SESSIONS])
    }

    @Test
    fun remoteLegacyUnlocksAreRemovedFromPollutedLocalV2Data() {
        val migrated = AchievementManager.resolveV3MigrationValue(
            scopeId = "local:${PrefsManager.DEFAULT_DB_NAME}",
            previousScopedValue = null,
            v2ScopedValue = """{"token_1000":123,"sessions_1":789}""",
            legacyGlobalValue = """{"token_1000":123}""",
            legacyMigratedScope = "server"
        )

        assertEquals("""{"sessions_1":789}""", migrated)
    }

    @Test
    fun legacyOwnerKeepsGlobalAndScopedUnlocks() {
        assertEquals(
            """{"token_1000":123,"messages_10":456,"sessions_1":789}""",
            AchievementManager.resolveV3MigrationValue(
                scopeId = "server",
                previousScopedValue = """{"messages_10":456}""",
                v2ScopedValue = """{"token_1000":123,"sessions_1":789}""",
                legacyGlobalValue = """{"token_1000":123}""",
                legacyMigratedScope = "server"
            )
        )
    }

    @Test
    fun ambiguousGlobalCopiesAreRemovedButIndependentUnlocksRemain() {
        assertEquals(
            """{"messages_10":456}""",
            AchievementManager.resolveV3MigrationValue(
                scopeId = "local:${PrefsManager.DEFAULT_DB_NAME}",
                previousScopedValue = null,
                v2ScopedValue = """{"token_1000":123,"messages_10":456}""",
                legacyGlobalValue = """{"token_1000":123}""",
                legacyMigratedScope = null
            )
        )
        assertNull(
            AchievementManager.resolveV3MigrationValue(
                scopeId = "local:empty",
                previousScopedValue = null,
                v2ScopedValue = null,
                legacyGlobalValue = null,
                legacyMigratedScope = null
            )
        )
        assertTrue(
            AchievementManager.storageKeyForScope("local:second_story")
                .startsWith("unlocked_v3::local:second_story")
        )
    }
}
