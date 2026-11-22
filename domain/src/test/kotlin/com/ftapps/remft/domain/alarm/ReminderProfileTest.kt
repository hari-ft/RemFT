package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ReminderProfileTest {
    private val now = utc(2026, 7, 15, 12)
    private val planner = EnglishAlarmTexts.planner

    private fun urgent(status: String = "scheduled", snoozedUntil: Instant? = null) = AlarmInput(
        reminderId = pad26("R1"),
        taskId = pad26("T1"),
        taskTitle = "Task",
        remindAt = now.plus(Duration.ofHours(1)),
        status = status,
        urgent = true,
        requiresAcknowledgement = true,
        snoozedUntil = snoozedUntil,
    )

    @Test
    fun sortsDeduplicatesAndDropsNegatives() {
        assertEquals(listOf(0, 2, 10, 30), ReminderProfile.normalizeSlots(listOf(30, 2, 2, -5, 0, 10)))
    }

    @Test
    fun enforcesTheMinimumGap() {
        assertEquals(listOf(0, 1, 2), ReminderProfile.normalizeSlots(listOf(0, 0, 1, 1, 2)))
        assertEquals(listOf(7), ReminderProfile.normalizeSlots(List(5) { 7 }))
    }

    @Test
    fun capsTheChainAtTwentyAlerts() {
        val slots = ReminderProfile.normalizeSlots(List(50) { it * 2 })
        assertEquals(ReminderProfile.MAX_SLOTS, slots.size)
        assertEquals(0, slots.first())
    }

    @Test
    fun neverReturnsAnEmptyChain() {
        assertEquals(listOf(0), ReminderProfile.normalizeSlots(emptyList()))
        assertEquals(listOf(0), ReminderProfile.normalizeSlots(listOf(-3, -1)))
    }

    @Test
    fun theStepperFloorKeepsTheGap() {
        assertEquals(0, ReminderProfile.minSlotAfter(null))
        assertEquals(5 + ReminderProfile.MIN_GAP_MINUTES, ReminderProfile.minSlotAfter(5))
    }

    @Test
    fun roundTrips() {
        val profile = ReminderProfile(slots = listOf(0, 3, 9), repeatAfterSnooze = false)
        assertEquals("{\"slots\":[0,3,9],\"repeatAfterSnooze\":false}", profile.encode())
        val parsed = ReminderProfile.parse(profile.encode())
        assertEquals(listOf(0, 3, 9), parsed.slots)
        assertFalse(parsed.repeatAfterSnooze)
    }

    @Test
    fun junkFallsBackToTheFactoryChain() {
        for (raw in listOf(null, "", "   ", "not json", "[]", "{\"slots\":\"nope\"}", "{}")) {
            assertEquals(raw.toString(), ReminderProfile.Factory.slots, ReminderProfile.parse(raw).slots)
        }
    }

    @Test
    fun aStoredChainIsNormalizedOnTheWayIn() {
        assertEquals(listOf(0, 1, 9), ReminderProfile.parse("{\"slots\":[9,9,1,0,-2]}").slots)
        assertEquals(listOf(0, 1, 9), ReminderProfile.parse("{\"slots\":[9.7,\"5\",true,1,0]}").slots)
        assertTrue(ReminderProfile.parse("{\"slots\":[0],\"repeatAfterSnooze\":\"false\"}").repeatAfterSnooze)
    }

    @Test
    fun theFactoryChainAndPresets() {
        assertEquals(listOf(0, 30, 60), ReminderProfile.Factory.slots)
        assertEquals(listOf(0, 30, 60), ReminderProfile.preset("standard")!!.slots)
        assertEquals(listOf(0), ReminderProfile.preset("calm")!!.slots)
        assertEquals(listOf(0, 1, 2, 3, 5, 8, 12, 17, 25, 40), ReminderProfile.preset("insistent")!!.slots)
    }

    @Test
    fun aHandBuiltChainIsKeptAsWritten() {
        for (slots in listOf(listOf(0, 2, 5, 10), listOf(0, 2, 5, 10, 30), listOf(0, 2, 5, 10, 30, 45), listOf(0, 2, 5, 10, 31), listOf(1, 2, 5, 10, 30))) {
            assertEquals(slots, ReminderProfile.parse("{\"slots\":[${slots.joinToString(",")}]}").slots)
        }
    }

    @Test
    fun namesThePresetItMatches() {
        assertEquals("standard", ReminderProfile.Factory.presetId)
        assertEquals("calm", ReminderProfile(slots = listOf(0)).presetId)
        assertNull(ReminderProfile(slots = listOf(0, 4, 40)).presetId)
    }

    @Test
    fun aShorterChainCoversMoreConcurrentAlarms() {
        assertEquals(40, ReminderProfile(slots = listOf(0)).alarmsFullyCovered)
        assertEquals(13, ReminderProfile.Factory.alarmsFullyCovered)
        assertEquals(2, ReminderProfile(slots = List(ReminderProfile.MAX_SLOTS) { it }).alarmsFullyCovered)
    }

    @Test
    fun aCalmProfileRingsOnceAndAnInsistentOneTenTimes() {
        assertEquals(1, planner.planNotifications(listOf(urgent()), now, false, profile = ReminderProfile(slots = listOf(0))).size)
        val insistent = ReminderProfile(slots = ReminderProfile.preset("insistent")!!.slots)
        assertEquals(10, planner.planNotifications(listOf(urgent()), now, false, profile = insistent).size)
    }

    @Test
    fun theOffsetsAreExactlyTheOnesTheUserChose() {
        val plan = planner.planNotifications(listOf(urgent()), now, false, profile = ReminderProfile(slots = listOf(0, 7, 21)))
        val base = now.plus(Duration.ofHours(1))
        assertEquals(listOf(base, base.plus(Duration.ofMinutes(7)), base.plus(Duration.ofMinutes(21))), plan.map { it.fireAt })
    }

    @Test
    fun repeatAfterSnoozeOffMakesASnoozedAlarmRingOnce() {
        val snoozedAt = now.plus(Duration.ofMinutes(5))
        val once = planner.planNotifications(
            listOf(urgent("snoozed", snoozedAt)),
            now,
            false,
            profile = ReminderProfile(slots = listOf(0, 2, 5), repeatAfterSnooze = false),
        )
        assertEquals(1, once.size)
        assertEquals(snoozedAt, once.single().fireAt)
        val again = planner.planNotifications(listOf(urgent("snoozed", snoozedAt)), now, false, profile = ReminderProfile(slots = listOf(0, 2, 5)))
        assertEquals(3, again.size)
    }
}
