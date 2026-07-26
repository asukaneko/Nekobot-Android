package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementManagerTest {

    @Test
    fun targetsContainFiveOrderedTiersPerMetric() {
        val targets = AchievementManager.targets

        assertEquals(20, targets.size)
        assertEquals(targets.size, targets.map { it.id }.distinct().size)

        AchievementManager.Target.Metric.entries.forEach { metric ->
            val metricTargets = targets.filter { it.metric == metric }
            assertEquals(5, metricTargets.size)
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
            AchievementManager.Id.FIRST_AFFECTION_100,
            targets.first().id
        )
    }
}
