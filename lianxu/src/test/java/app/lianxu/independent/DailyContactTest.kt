package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailyContactTest {
    @Test fun agendaWorkSharesTheRoleCancellationTagAndKeepsWakeGenerationsDistinct() {
        val wake = PlanWake("a", 2L, 100L, 90L, 0)
        assertEquals("lianxu_companion:a", ContactWake.roleTag(wake.role))
        assertEquals("lianxu_agenda:a:2:100:90:false", ContactWake.agendaName(wake,false))
        assertEquals("lianxu_agenda:a:2:100:90:true", ContactWake.agendaName(wake,true))
        assertEquals("lianxu_contact_wake_reconcile", ContactWake.RECONCILE_WORK)
    }

    private val zone=ZoneId.of("UTC")
    private fun at(hour:String)=Instant.parse("2026-11-25T"+hour+":00Z").toEpochMilli()
    @Test fun quietCrossesMidnightAndRejectsInvalidBackup(){
        val q=QuietHours()
        assertTrue(q.contains(at("23:00"),zone));assertTrue(q.contains(at("07:59"),zone));assertFalse(q.contains(at("08:00"),zone))
        assertEquals(at("08:00"),q.nextMorning(at("03:00"),zone));assertEquals(q,QuietHours.decode(q.encode()))
        assertThrows(IllegalArgumentException::class.java){QuietHours.decode("0,1440,8")}
    }
    @Test fun supplementalPlanWaitsForIdleAndCooldownWithoutDelayingRegularPlan() {
        val morning = at("08:00")
        val scheduled = at("20:00")
        assertEquals(at("12:00"), DailyAgenda.nextDue(scheduled, true, at("08:40"), morning))
        assertEquals(at("13:15"), DailyAgenda.nextDue(scheduled, true, at("12:45"), morning))
        assertEquals(scheduled, DailyAgenda.nextDue(scheduled, true, at("19:55"), morning))
        assertEquals(scheduled, DailyAgenda.nextDue(scheduled, false, at("08:40"), morning))
        assertEquals(0L, DailyAgenda.nextDue(0L, true, at("08:40"), morning))
    }

    @Test fun malformedPointsDoNotLoseTheDayAndJitterStaysInBounds(){
        val raw="""{"points":[{},{"when":"昨天","reason":"错"},{"when":"今天 09:25","reason":"早上"},{"when":"今天 22:59","reason":"睡前"},{"when":"今天 23:10","reason":"太晚"}]}"""
        val points=DailyAgenda.parse(raw,at("09:00"),QuietHours(),zone){6}
        assertEquals(listOf(at("09:31"),at("22:59")),points.map{it.at})
        assertTrue(DailyAgenda.parse("""{"points":[]}""",at("09:00"),QuietHours(),zone).isEmpty())
        assertThrows(IllegalArgumentException::class.java){DailyAgenda.parse("{}",at("09:00"),QuietHours(),zone)}
    }
    @Test fun batteryGuideRecognizesBrandsFromEitherField(){
        for((brand,menu) in mapOf("POCO" to "省电策略","HUAWEI" to "启动管理","Honor" to "启动管理","OnePlus" to "耗电管理","iQOO" to "后台耗电管理","Samsung" to "休眠","other" to "应用耗电量")){
            assertTrue(backgroundSteps("vendor",brand).contains("本应用"));assertFalse(backgroundSteps("vendor",brand).contains("恋叙"))
            assertTrue(backgroundSteps("vendor",brand).contains(menu));assertTrue(backgroundSteps(brand,"other").contains(menu))
        }
    }
    @Test fun silenceIsCountedInUnansweredSends(){
        assertTrue(ContactPace(0,2,2).skip(at("09:00")))
        assertFalse(ContactPace(0,2,0).skip(at("09:00")))
        assertTrue(ContactPace(0,3,0).skip(at("09:00")))
        assertTrue(ContactPace(at("08:50"),0,0).skip(at("09:00")))
        assertFalse(ContactPace(at("08:45"),0,0).skip(at("09:00")))
    }
    @Test fun retryStopsAfterThreeAndSkipsAuth(){
        assertEquals(listOf(300000L,900000L,1800000L,null),(0..3).map(ContactFailures::retryDelay))
        for(kind in listOf(ChatFailure.NETWORK,ChatFailure.RATE_LIMIT,ChatFailure.SERVER,ChatFailure.STRUCTURED_FORMAT))assertTrue(ContactFailures.transient(CompanionModelFailure(kind)))
        for(kind in listOf(ChatFailure.UNAUTHORIZED,ChatFailure.FORBIDDEN,ChatFailure.INVALID_CONFIG))assertFalse(ContactFailures.transient(CompanionModelFailure(kind)))
    }
}
