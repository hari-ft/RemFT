package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeepLinksTest {
    private val id = "01HZY0000000000000000000AB"

    @Test
    fun aTaskLinkOpensTheTask() {
        assertEquals(DeepLinkTarget.OpenTask(id), DeepLinks.routeFor("remft://task/$id"))
    }

    @Test
    fun aSectionTheAppDoesNotHaveResolvesToNothing() {
        for (verb in listOf("asset", "ticket", "project")) {
            assertNull(verb, DeepLinks.routeFor("remft://$verb/$id"))
        }
    }

    @Test
    fun aSupportedLinkLandsWhereTheContractSays() {
        assertEquals(DeepLinkTarget.OpenTask(id), DeepLinks.appLinkTarget("remft://task/$id"))
        assertEquals(DeepLinkTarget.Home, DeepLinks.appLinkTarget("remft://open"))
        assertEquals(DeepLinkTarget.Add, DeepLinks.appLinkTarget("remft://add"))
        assertEquals(DeepLinkTarget.Day(LocalDate.of(2026, 10, 20)), DeepLinks.appLinkTarget("remft://day/2026-10-20"))
        assertEquals(DeepLinkTarget.Files, DeepLinks.appLinkTarget("remft://file/$id"))
    }

    @Test
    fun anUnknownLinkOfOursOpensHome() {
        for (link in listOf(
            "remft://open/settings/app-log",
            "remft://settings/app-log",
            "remft:///settings",
            "remft://settings",
            "remft://task/not-an-id",
            "remft://add?title=x",
            "remft://files/extra",
            "remft://task/a b",
        )) {
            assertEquals(link, DeepLinkTarget.Home, DeepLinks.appLinkTarget(link))
        }
    }

    @Test
    fun onlyOurLinksThatLeadNowhereAreUnknown() {
        assertTrue(DeepLinks.isUnknownAppLink("remft://settings/app-log"))
        assertTrue(DeepLinks.isUnknownAppLink("remft://task/not-an-id"))
        assertFalse(DeepLinks.isUnknownAppLink("remft://open"))
        assertFalse(DeepLinks.isUnknownAppLink("remft://task/$id"))
        assertFalse(DeepLinks.isUnknownAppLink("remft://day/2026-10-20"))
        assertFalse(DeepLinks.isUnknownAppLink("https://example.com/settings"))
        assertFalse(DeepLinks.isUnknownAppLink("remft-alarm://ring/7"))
    }

    @Test
    fun aLinkThatIsNotOursIsLeftAlone() {
        assertNull(DeepLinks.appLinkTarget("/settings"))
        assertNull(DeepLinks.appLinkTarget("https://example.com/settings"))
        assertNull(DeepLinks.appLinkTarget(""))
        assertNull(DeepLinks.appLinkTarget("https://exa mple.com"))
        assertNull(DeepLinks.routeFor("https://example.com/task/$id"))
        assertNull(DeepLinks.routeFor("otherapp://task/$id"))
        assertNull(DeepLinks.appLinkTarget("otherapp://day/2026-10-20"))
    }

    @Test
    fun theVerbIsTheFirstSegmentWhetherHostOrPath() {
        assertEquals(DeepLinkTarget.Home, DeepLinks.routeFor("remft:///open"))
        assertEquals(DeepLinkTarget.OpenTask(id), DeepLinks.routeFor("remft:///task/$id"))
        assertEquals(DeepLinkTarget.Home, DeepLinks.routeFor("remft:open"))
        assertEquals(DeepLinkTarget.Add, DeepLinks.routeFor("remft://add/"))
        assertEquals(DeepLinkTarget.Home, DeepLinks.routeFor("REMFT://OPEN"))
        assertNull(DeepLinks.routeFor("remft://"))
        assertNull(DeepLinks.routeFor("remft:"))
    }

    @Test
    fun addTakesNoParameters() {
        assertNull(DeepLinks.routeFor("remft://add?title=x"))
        assertNull(DeepLinks.routeFor("remft://add/extra"))
    }

    @Test
    fun aDayLinkIsValidatedAndExact() {
        assertNull(DeepLinks.routeFor("remft://day/2026-02-31"))
        assertNull(DeepLinks.routeFor("remft://day/2026-2-3"))
        assertNull(DeepLinks.routeFor("remft://day"))
        assertNull(DeepLinks.routeFor("remft://day/2026-10-20/x"))
        assertNull(DeepLinks.routeFor("remft://day/2026-10-20?x=1"))
    }

    @Test
    fun taskAndFileIdsMustBeUlids() {
        assertNull(DeepLinks.routeFor("remft://task/${id.lowercase()}"))
        assertNull(DeepLinks.routeFor("remft://task/01HZY000000000000000000OAB"))
        assertNull(DeepLinks.routeFor("remft://task/$id/extra"))
        assertNull(DeepLinks.routeFor("remft://file/short"))
        assertEquals(DeepLinkTarget.OpenTask(id), DeepLinks.routeFor("remft://task/$id#top"))
    }

    @Test
    fun parseDayAcceptsOnlyRealDates() {
        assertEquals(LocalDate.of(2026, 10, 20), DeepLinks.parseDay("2026-10-20"))
        assertEquals(LocalDate.of(2028, 2, 29), DeepLinks.parseDay("2028-02-29"))
        assertNull(DeepLinks.parseDay("2026-02-29"))
        assertNull(DeepLinks.parseDay("2026-13-01"))
        assertNull(DeepLinks.parseDay("2026-10-20\n"))
        assertNull(DeepLinks.parseDay(" 2026-10-20"))
        assertNull(DeepLinks.parseDay(null))
    }

    @Test
    fun dayParamIsZeroPadded() {
        assertEquals("2026-03-07", DeepLinks.dayParam(LocalDate.of(2026, 3, 7)))
        assertEquals("0999-12-31", DeepLinks.dayParam(LocalDate.of(999, 12, 31)))
        assertEquals(LocalDate.of(2026, 3, 7), DeepLinks.parseDay(DeepLinks.dayParam(LocalDate.of(2026, 3, 7))))
    }

    @Test
    fun alarmRefreshIsRecognised() {
        assertTrue(DeepLinks.isAlarmRefresh("remft://refresh-alarms"))
        assertFalse(DeepLinks.isAlarmRefresh("otherapp://refresh-alarms"))
        assertFalse(DeepLinks.isAlarmRefresh("remft://open"))
    }
}
