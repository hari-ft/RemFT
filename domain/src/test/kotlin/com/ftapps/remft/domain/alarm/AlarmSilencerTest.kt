package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class AlarmSilencerTest {

    private val fireAt = utc(2030, 6, 1, 9, 58)
    private val now = fireAt.minus(Duration.ofMinutes(5))
    private val planner = EnglishAlarmTexts.planner

    private fun alarm(reminder: String = "R1", task: String = "T1") = AlarmInput(
        reminderId = pad26(reminder),
        taskId = pad26(task),
        taskTitle = "Take pills",
        remindAt = fireAt,
        status = ReminderStatus.SCHEDULED,
        urgent = true,
        requiresAcknowledgement = true,
    )

    private class Screen(val pending: MutableMap<Int, PlannedNotification>, val shown: MutableSet<Int>, val fired: Map<String, Int>)

    private fun scheduleAndFireFirst(alarms: List<AlarmInput>): Screen {
        val plan = planner.planNotifications(alarms, now, privacyMode = false)
        val booking = AlarmBooking.plan(plan, pending = emptySet(), rebookEverything = true)
        val pending = booking.toBook.associateByTo(LinkedHashMap()) { it.id }
        val shown = LinkedHashSet<Int>()
        val fired = LinkedHashMap<String, Int>()
        for (n in pending.values.toList()) {
            if (n.slotIndex == 0) {
                pending.remove(n.id)
                shown.add(n.id)
                fired[n.reminderId!!] = n.id
            }
        }
        return Screen(pending, shown, fired)
    }

    private fun targets(
        taskId: String,
        reminderId: String? = null,
        known: List<AlarmInput> = emptyList(),
        live: List<AlarmInput> = emptyList(),
        shown: Set<Int>,
    ): Set<Int> = AlarmBooking.silenceTargets(
        planner = planner,
        taskId = taskId,
        reminderId = reminderId,
        known = known,
        live = live.filter { it.taskId == taskId },
        shownIds = shown,
        privacyMode = false,
    )

    @Test
    fun theIdsItMatchesAreTheIdsTheSchedulerAskedTheOsFor() {
        val a = alarm()
        val planned = planner.planNotifications(listOf(a), now, privacyMode = false).map { it.id }.toSet()
        assertTrue(planned.isNotEmpty())
        assertTrue(planner.notificationIdsForAlarm(a, privacyMode = false).containsAll(planned))
        assertEquals(planned, targets(a.taskId, a.reminderId, known = listOf(a), shown = planned))
    }

    @Test
    fun handlingAnAlarmTargetsItsShownNotificationAndOnlyThat() {
        val first = alarm()
        val other = alarm(reminder = "R2", task = "T2")
        val screen = scheduleAndFireFirst(listOf(first, other))
        val chain = screen.pending.keys.toSet()

        val hits = targets(first.taskId, first.reminderId, known = listOf(first), shown = screen.shown)

        assertEquals(setOf(screen.fired.getValue(first.reminderId)), hits)
        assertEquals(setOf(screen.fired.getValue(other.reminderId)), screen.shown - hits)
        assertTrue(hits.none { it in chain })
    }

    @Test
    fun onlyTheGivenRemindersAlarmIsTargetedWithinOneTask() {
        val remind = alarm()
        val due = alarm(reminder = "R2")
        val screen = scheduleAndFireFirst(listOf(remind, due))

        val hits = targets(remind.taskId, remind.reminderId, live = listOf(remind, due), shown = screen.shown)

        assertEquals(setOf(screen.fired.getValue(remind.reminderId)), hits)
    }

    @Test
    fun withoutAReminderIdEveryAlarmOfTheTaskIsTargeted() {
        val remind = alarm()
        val due = alarm(reminder = "R2")
        val screen = scheduleAndFireFirst(listOf(remind, due))

        val hits = targets(remind.taskId, live = listOf(remind, due), shown = screen.shown)

        assertEquals(2, hits.size)
        assertEquals(screen.shown.toSet(), hits)
    }

    @Test
    fun aRingMovedByACollisionIsStillFound() {
        val first = alarm()
        val other = alarm(reminder = "R2", task = "T2")
        val screen = scheduleAndFireFirst(listOf(first, other))
        val movedOther = screen.fired.getValue(other.reminderId)

        assertEquals(setOf(movedOther), targets(other.taskId, other.reminderId, known = listOf(other), shown = screen.shown))
    }

    @Test
    fun onlyShownIdsAreReturned() {
        val a = alarm()
        val screen = scheduleAndFireFirst(listOf(a))
        val shown = screen.shown + setOf(12345, 67890)

        assertEquals(screen.shown.toSet(), targets(a.taskId, a.reminderId, known = listOf(a), shown = shown))
    }

    @Test
    fun noMatchingAlarmTargetsNothing() {
        val a = alarm()
        val screen = scheduleAndFireFirst(listOf(a))

        assertTrue(targets(pad26("T9"), known = listOf(a), shown = screen.shown).isEmpty())
        assertTrue(targets(a.taskId, pad26("R9"), known = listOf(a), shown = screen.shown).isEmpty())
        assertTrue(targets(a.taskId, a.reminderId, known = listOf(a), shown = emptySet()).isEmpty())
    }
}
