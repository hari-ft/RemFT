package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RingCollisionTest {
    private val now = utc(2026, 11, 7, 8)
    private val planner = EnglishAlarmTexts.planner

    private fun alarm(task: String, at: Instant, kind: String = "remind", urgent: Boolean = true) = AlarmInput(
        reminderId = "task:$kind:$task",
        taskId = task,
        taskTitle = "Task $task",
        kind = kind,
        remindAt = at,
        status = "scheduled",
        urgent = urgent,
        requiresAcknowledgement = urgent,
    )

    private fun plan(alarms: List<AlarmInput>, at: Instant = now) =
        planner.planNotifications(alarms, at, privacyMode = false, profile = ReminderProfile.Factory)

    private fun t(h: Int, m: Int) = utc(2026, 11, 7, h, m)

    private fun t8(h: Int, m: Int) = utc(2026, 11, 8, h, m)

    private fun ring(n: PlannedNotification) = "${n.kind}#${n.slotIndex}@${n.fireAt.hourOfDay}:${n.fireAt.minuteOfHour.toString().padStart(2, '0')}"

    @Test
    fun reminderRepeatYieldsToTheDeadlineFirstRing() {
        val remind = alarm("T1", t(22, 35))
        val due = alarm("T1", t(23, 5), kind = "due")
        val p = plan(listOf(remind, due))
        assertEquals(
            listOf("remind#0@22:35", "due#0@23:05", "remind#1@23:20", "due#1@23:35", "remind#2@23:50", "due#2@0:05"),
            p.map(::ring),
        )
        val moved = p.first { it.kind == "remind" && it.slotIndex == 1 }
        val payload = AlarmPlanner.parsePayload(moved.payload)!!
        assertEquals(t(22, 35), payload.at)
        assertEquals(1, payload.chainIndex)
        assertEquals("remind", payload.kind)
        assertEquals(p.size, p.map { it.fireAt.toEpochMilli() / 60000 }.toSet().size)
        assertEquals(t8(0, 5), p.last().fireAt)
    }

    @Test
    fun twoRepeatsInOneMinuteTheDeadlineStays() {
        val p = plan(listOf(alarm("T1", t(22, 35)), alarm("T2", t(23, 5), kind = "due")))
        assertEquals(listOf("due"), p.filter { it.fireAt == t(23, 35) }.map { it.kind })
    }

    @Test
    fun twoFirstRingsAtNineOneMovesByTheRule() {
        val a = alarm("T-A", t(9, 0), urgent = false)
        val b = alarm("T-B", t(9, 0), urgent = false)
        assertEquals(listOf("T-A@0", "T-B@15"), plan(listOf(b, a)).map { "${it.taskId}@${it.fireAt.minuteOfHour}" })

        val due = alarm("T-Z", t(9, 0), kind = "due", urgent = false)
        val p2 = plan(listOf(a, due))
        assertEquals("T-Z", p2.first().taskId)
        assertEquals(t(9, 0), p2.first().fireAt)
        assertEquals(t(9, 15), p2.last().fireAt)

        val urgent = alarm("T-Y", t(9, 0))
        val p3 = plan(listOf(a, urgent)).filter { it.slotIndex == 0 }
        assertEquals(t(9, 0), p3.first { it.taskId == "T-Y" }.fireAt)
        assertEquals(t(9, 15), p3.first { it.taskId == "T-A" }.fireAt)
    }

    @Test
    fun aFirstRingIsNeverDelayedByAnotherAlarmsRepeat() {
        val p = plan(listOf(alarm("T1", t(9, 0)), alarm("T2", t(9, 30), urgent = false)))
        assertEquals(t(9, 30), p.first { it.taskId == "T2" }.fireAt)
        assertEquals(t(9, 45), p.first { it.taskId == "T1" && it.slotIndex == 1 }.fireAt)
    }

    @Test
    fun aCascadeMovesAnotherFifteenMinutes() {
        val p = plan(listOf("T1", "T2", "T3").map { alarm(it, t(9, 0), urgent = false) } + alarm("T4", t(9, 15), urgent = false))
        assertEquals(listOf("T1@0", "T4@15", "T2@30", "T3@45"), p.map { "${it.taskId}@${it.fireAt.minuteOfHour}" })
    }

    @Test
    fun ringsCollideByTheMinuteNotTheMillisecond() {
        val p = plan(
            listOf(
                alarm("T1", t(9, 0).plusSeconds(10)),
                alarm("T2", t(9, 0).plusSeconds(50), urgent = false),
            ),
        ).filter { it.slotIndex == 0 }
        assertEquals(0, p.first { it.taskId == "T1" }.fireAt.minuteOfHour)
        assertEquals(t(9, 15).plusSeconds(50), p.first { it.taskId == "T2" }.fireAt)
    }

    @Test
    fun idsAreStableAcrossPassesAndAsTimePasses() {
        val alarms = listOf(
            alarm("T1", t(22, 35)),
            alarm("T1", t(23, 5), kind = "due"),
            alarm("T2", t(23, 5), urgent = false),
        )
        val first = plan(alarms).map { it.id }
        assertEquals(first, plan(alarms).map { it.id })
        assertEquals(first.size, first.toSet().size)
        val before = plan(alarms).associate { it.id to it.fireAt }
        val later = plan(alarms, t(23, 6))
        assertTrue(later.isNotEmpty())
        for (n in later) assertEquals(ring(n), before[n.id], n.fireAt)
        assertTrue(later.any { it.kind == "remind" && it.slotIndex == 1 })
    }

    @Test
    fun aMovedRingIsStillFoundByTheSilencerIdMatch() {
        val remind = alarm("T1", t(22, 35))
        val due = alarm("T1", t(23, 5), kind = "due")
        val moved = plan(listOf(remind, due)).first { it.kind == "remind" && it.slotIndex == 1 }
        assertEquals(t(23, 20), moved.fireAt)
        assertTrue(moved.id in planner.notificationIdsForAlarm(remind, privacyMode = false))
    }

    @Test
    fun onlyFutureRingsArePlannedAndAMovedRingIsAfterNow() {
        val p = plan(listOf(alarm("T1", t(9, 0), urgent = false), alarm("T2", t(9, 0), urgent = false)), t(9, 5))
        assertEquals(listOf("T2@15"), p.map { "${it.taskId}@${it.fireAt.minuteOfHour}" })
    }
}
