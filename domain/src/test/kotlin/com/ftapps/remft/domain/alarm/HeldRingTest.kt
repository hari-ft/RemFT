package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class HeldRingTest {
    private val nine = utc(2030, 6, 1, 9)
    private val quarterPast = nine.plus(Duration.ofMinutes(15))
    private val twoPast = nine.plus(Duration.ofMinutes(2))
    private val planner = EnglishAlarmTexts.planner

    private fun alarm(id: String) = AlarmInput(
        reminderId = pad26(id),
        taskId = pad26("T$id"),
        taskTitle = "Task $id",
        remindAt = nine,
        status = "scheduled",
        urgent = false,
        requiresAcknowledgement = false,
    )

    private val r1 = alarm("R1")
    private val r2 = alarm("R2")

    @Test
    fun aMovedRingKeepsItsTimeWhenTheOtherAlarmIsAnsweredFirst() {
        val before = planner.planNotifications(listOf(r1, r2), nine.minus(Duration.ofHours(1)), privacyMode = false)
        val moved = before.single { it.fireAt == quarterPast }
        assertEquals(r2.reminderId, moved.reminderId)

        assertTrue(planner.planNotifications(listOf(r2), twoPast, privacyMode = false).isEmpty())

        val held = AlarmPlanner.heldRingsFromPayloads(before.map { it.payload }, twoPast)
        val after = planner.planNotifications(listOf(r2), twoPast, privacyMode = false, held = held)
        assertEquals(moved.id, after.single().id)
        assertEquals(quarterPast, after.single().fireAt)
        assertEquals(listOf(quarterPast), AlarmPlanner.plannedRingTimes(listOf(r2), held = held)[r2])
    }

    @Test
    fun aKeptTimeIsOnlyForTheSameAlarmInstant() {
        val before = planner.planNotifications(listOf(r1, r2), nine.minus(Duration.ofHours(1)), privacyMode = false)
        val held = AlarmPlanner.heldRingsFromPayloads(before.map { it.payload }, twoPast)
        val retimed = r2.copy(remindAt = nine.plus(Duration.ofHours(1)))
        assertEquals(
            nine.plus(Duration.ofHours(1)),
            planner.planNotifications(listOf(retimed), twoPast, privacyMode = false, held = held).single().fireAt,
        )
    }

    @Test
    fun heldRingsIgnoreJunkPastAndNonIntegerPayloads() {
        val future = nine.plus(Duration.ofHours(1)).toEpochMilli()
        val past = nine.minus(Duration.ofHours(1)).toEpochMilli()
        val held = AlarmPlanner.heldRingsFromPayloads(
            listOf(
                null,
                "not json",
                "[1]",
                "{\"reminderId\":\"A\",\"chainIndex\":0,\"planned\":1,\"fireAt\":$future}",
                "{\"reminderId\":\"B\",\"chainIndex\":0,\"planned\":1,\"fireAt\":$past}",
                "{\"reminderId\":\"C\",\"chainIndex\":0.0,\"planned\":1,\"fireAt\":$future}",
                "{\"reminderId\":\"D\",\"chainIndex\":\"0\",\"planned\":1,\"fireAt\":$future}",
                "{\"test\":true}",
            ),
            nine,
        )
        assertEquals(mapOf(HeldRing("A", 0, 1) to java.time.Instant.ofEpochMilli(future)), held)
    }
}
