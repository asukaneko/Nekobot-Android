package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactWakeAndroidTest {
    @Test fun cancelRoleCancelsBothWakeGenerationsAndPreservesOtherRoles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        val role = UUID.randomUUID().toString()
        val wake = PlanWake(role, 3L, 120L, 90L, 0)
        val other = wake.copy(role = UUID.randomUUID().toString())
        fun work(plan: PlanWake, alarm: Boolean) = manager.getWorkInfosForUniqueWork(
            "lianxu_agenda:${plan.role}:3:120:90:$alarm"
        ).get(10, TimeUnit.SECONDS).single()

        try {
            for ((plan, alarm) in listOf(wake to false, wake to true, other to false)) {
                ContactWake.plan(context, plan, TimeUnit.DAYS.toMillis(1), alarm).result.get(10, TimeUnit.SECONDS)
                val info = work(plan, alarm)
                assertTrue("lianxu_companion:${plan.role}" in info.tags)
                assertEquals(WorkInfo.State.ENQUEUED, info.state)
            }
            CompanionScheduler.cancel(context, role).result.get(10, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.CANCELLED, work(wake, false).state)
            assertEquals(WorkInfo.State.CANCELLED, work(wake, true).state)
            assertEquals(WorkInfo.State.ENQUEUED, work(other, false).state)
        } finally {
            for (id in listOf(role, other.role)) {
                CompanionScheduler.cancel(context, id).result.get(10, TimeUnit.SECONDS)
            }
        }
    }
}
