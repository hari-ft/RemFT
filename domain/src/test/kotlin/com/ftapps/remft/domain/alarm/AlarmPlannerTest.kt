package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlarmPlannerTest {
    private val now = utc(2026, 7, 15, 12)
    private val planner = EnglishAlarmTexts.planner

    private fun alarm(
        id: String = "R1",
        status: String = "scheduled",
        urgent: Boolean = false,
        requiresAck: Boolean = false,
        remindAt: Instant? = null,
        snoozedUntil: Instant? = null,
        title: String = "Task",
        kind: String = "remind",
        snoozeRound: Int = 0,
    ) = AlarmInput(
        reminderId = pad26(id),
        taskId = pad26("T$id"),
        taskTitle = title,
        kind = kind,
        snoozeRound = snoozeRound,
        remindAt = remindAt ?: now.plus(Duration.ofHours(1)),
        status = status,
        urgent = urgent,
        requiresAcknowledgement = requiresAck,
        snoozedUntil = snoozedUntil,
    )

    @Test
    fun aNormalReminderPlansExactlyOneExactNotification() {
        val plan = planner.planNotifications(listOf(alarm(title = "Buy milk")), now, privacyMode = false)
        assertEquals(1, plan.size)
        assertEquals("Buy milk", plan.single().title)
        assertFalse(plan.single().urgent)
        assertEquals(now.plus(Duration.ofHours(1)), plan.single().fireAt)
        assertTrue(plan.single().payload.contains("\"chainIndex\":0"))
    }

    @Test
    fun urgentAckRemindersPreScheduleTheFullChain() {
        val plan = planner.planNotifications(listOf(alarm(urgent = true, requiresAck = true)), now, privacyMode = false)
        assertEquals(ReminderProfile.Factory.offsets.size, plan.size)
        val base = now.plus(Duration.ofHours(1))
        assertEquals(ReminderProfile.Factory.offsets.map { base.plus(it) }, plan.map { it.fireAt })
        assertTrue(plan.first().body.contains("waiting for acknowledgement"))
        assertTrue(plan.last().body.contains("alert 3"))
        assertEquals(1, planner.planNotifications(listOf(alarm(urgent = true)), now, privacyMode = false).size)
    }

    @Test
    fun snoozedAlarmsFireAtSnoozedUntilAndPastInstantsAreSkipped() {
        val plan = planner.planNotifications(
            listOf(
                alarm(id = "R1", status = "snoozed", remindAt = now.minus(Duration.ofHours(2)), snoozedUntil = now.plus(Duration.ofMinutes(30))),
                alarm(id = "R2", remindAt = now.minus(Duration.ofMinutes(1))),
                alarm(id = "R3", status = "acknowledged"),
                alarm(id = "R4", status = "cancelled"),
            ),
            now,
            privacyMode = false,
        )
        assertEquals(1, plan.size)
        assertEquals(now.plus(Duration.ofMinutes(30)), plan.single().fireAt)
    }

    @Test
    fun windowsToTheSoonestMaxPendingFireTimes() {
        val plan = planner.planNotifications(
            (0 until 60).map { alarm(id = "R$it", remindAt = now.plus(Duration.ofMinutes(10L + it))) },
            now,
            privacyMode = false,
        )
        assertEquals(40, plan.size)
        assertEquals(now.plus(Duration.ofMinutes(10)), plan.first().fireAt)
        assertEquals(now.plus(Duration.ofMinutes(49)), plan.last().fireAt)
    }

    @Test
    fun privacyModeHidesTaskTitlesEverywhere() {
        val plan = planner.planNotifications(
            listOf(alarm(title = "Top secret task", urgent = true, requiresAck = true)),
            now,
            privacyMode = true,
        )
        for (n in plan) {
            assertEquals("RemFT", n.title)
            assertEquals("You have a reminder", n.body)
            assertFalse(n.payload.contains("secret"))
        }
    }

    @Test
    fun idsAreStableForIdenticalInputAndChangeWithContent() {
        fun ids(privacy: Boolean) = planner.planNotifications(listOf(alarm()), now, privacy).map { it.id }
        assertEquals(ids(false), ids(false))
        assertNotEquals(ids(false), ids(true))
    }

    @Test
    fun aDeadlineAlarmReadsDifferentlyFromAReminder() {
        val plan = planner.planNotifications(
            listOf(
                alarm(id = "R1", urgent = true, requiresAck = true, kind = "remind"),
                alarm(id = "R2", urgent = true, requiresAck = true, kind = "due", remindAt = now.plus(Duration.ofMinutes(63))),
            ),
            now,
            privacyMode = false,
        )
        val bodies = plan.filter { it.payload.contains("\"chainIndex\":0") }.map { it.body }
        assertEquals(2, bodies.size)
        assertEquals(1, bodies.count { it.contains("time you set") })
        assertEquals(1, bodies.count { it.contains("waiting for acknowledgement") })
        assertEquals(2, plan.count { it.body.contains("alert 2") })
    }

    @Test
    fun privacyModeHidesWhichAlarmItIsForBothKinds() {
        val plan = planner.planNotifications(listOf(alarm(urgent = true, kind = "due")), now, privacyMode = true)
        assertEquals("RemFT", plan.single().title)
        assertFalse(plan.single().body.contains("time you set"))
    }

    @Test
    fun aPostSnoozeRingSaysWhichRoundItIs() {
        val plan = planner.planNotifications(
            listOf(alarm(urgent = true, requiresAck = true, status = "snoozed", snoozeRound = 3, snoozedUntil = now.plus(Duration.ofMinutes(5)))),
            now,
            privacyMode = false,
        )
        assertTrue(plan.first().body.contains("round 3"))
        val firstRound = planner.planNotifications(
            listOf(alarm(urgent = true, requiresAck = true, status = "snoozed", snoozedUntil = now.plus(Duration.ofMinutes(5)))),
            now,
            privacyMode = false,
        )
        assertTrue(firstRound.first().body.contains("round 1"))
    }

    @Test
    fun ringIdsStayClearOfTheReservedIds() {
        assertTrue(NotificationIds.TEST_ALARM in 1 until NotificationIds.FIRST_RING)
        assertEquals(NotificationIds.TEST_ALARM, AlarmPlanner.TEST_NOTIFICATION_ID)
        val many = (1..30).map { alarm(id = "R$it", urgent = true, requiresAck = true, title = "Task $it") }
        val plan = planner.planNotifications(many, now, privacyMode = false)
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { NotificationIds.isRing(it.id) })
        assertFalse(NotificationIds.isRing(NotificationIds.TEST_ALARM))
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
    }

    @Test
    fun ringIdsAreDeterministic() {
        val feed = listOf(alarm(urgent = true, requiresAck = true), alarm(id = "R2", title = "Other"))
        val first = planner.planNotifications(feed, now, privacyMode = false).map { it.id }
        val second = planner.planNotifications(feed.reversed(), now, privacyMode = false).map { it.id }
        assertEquals(first, second)
        assertEquals(NotificationIds.ring("k"), NotificationIds.ring("k"))
        assertNotEquals(NotificationIds.ring("k"), NotificationIds.ring("k", attempt = 1))
    }

    @Test
    fun clashingKeysGetDistinctIdsTheMatcherStillRecognises() {
        val ids = NotificationIds.assign(listOf("same", "same", "same", "other"))
        assertEquals(4, ids.toSet().size)
        assertEquals(NotificationIds.ring("same"), ids[0])
        assertEquals(NotificationIds.ring("other"), ids[3])
        assertTrue(NotificationIds.matchedRings("same").containsAll(ids.take(3)))
        assertTrue(ids.all { NotificationIds.isRing(it) })
    }

    @Test
    fun whatTheNotificationShowsIsPartOfItsId() {
        val plain = planner.planNotifications(listOf(alarm(title = "Buy milk")), now, privacyMode = false).single()
        val renamed = planner.planNotifications(listOf(alarm(title = "Buy bread")), now, privacyMode = false).single()
        val private = planner.planNotifications(listOf(alarm(title = "Buy milk")), now, privacyMode = true).single()
        val chimed = planner.planNotifications(listOf(alarm(title = "Buy milk")), now, privacyMode = false, reminderSoundName = "remft_chime").single()
        assertEquals(4, setOf(plain.id, renamed.id, private.id, chimed.id).size)
        val key = AlarmPlanner.ringKey(plain.reminderId!!, 0, plain.fireAt.toEpochMilli(), plain.title, plain.body, false, null)
        assertEquals(NotificationIds.ring(key), plain.id)
    }

    @Test
    fun theRingKeySpellsOutEveryField() {
        assertEquals("ring/1|R|0|5|plain|-1:|1:T|1:B", AlarmPlanner.ringKey("R", 0, 5, "T", "B", false, null))
        assertEquals(
            "ring/1|R|1|5|urgent|11:remft_alarm|3:a|b|1:c",
            AlarmPlanner.ringKey("R", 1, 5, "a|b", "c", true, "remft_alarm"),
        )
        assertNotEquals(
            AlarmPlanner.ringKey("R", 1, 5, "a|b", "c", true, null),
            AlarmPlanner.ringKey("R", 1, 5, "a", "b|c", true, null),
        )
    }

    @Test
    fun thePayloadIsCompactJson() {
        val normal = planner.planNotifications(listOf(alarm(title = "Buy milk")), now, privacyMode = false).single()
        assertEquals(
            "{\"taskId\":\"TR100000000000000000000000\",\"reminderId\":\"R1000000000000000000000000\",\"kind\":\"remind\"," +
                "\"at\":1784120400000,\"chainIndex\":0,\"planned\":1784120400000,\"fireAt\":1784120400000}",
            normal.payload,
        )
    }

    @Test
    fun payloadStringsSurviveQuotesAndControlCharacters() {
        val odd = "a\"b\\c\n\u0001é😀"
        val payload = AlarmPlanner.encodePayload(odd, odd, "remind", 1, 0, 2, 3)
        val parsed = AlarmPlanner.parsePayload(payload)!!
        assertEquals(odd, parsed.taskId)
        assertEquals(odd, parsed.reminderId)
        assertEquals(3L, parsed.fireAt)
    }

    @Test
    fun parsePayloadReadsWhatThePlannerWrote() {
        val n = planner.planNotifications(listOf(alarm()), now, privacyMode = false).single()
        val payload = AlarmPlanner.parsePayload(n.payload)!!
        assertEquals(pad26("TR1"), payload.taskId)
        assertEquals(pad26("R1"), payload.reminderId)
        assertEquals("remind", payload.kind)
        assertEquals(now.plus(Duration.ofHours(1)), payload.at)
        assertEquals(0, payload.chainIndex)
        assertEquals(null, AlarmPlanner.parsePayload("not json"))
        assertEquals(null, AlarmPlanner.parsePayload("{\"reminderId\":\"x\"}"))
    }

    @Test
    fun testAlarmUsesTheFixedIdDelayAndTexts() {
        val test = planner.testAlarm(now)
        assertEquals(NotificationIds.TEST_ALARM, test.id)
        assertEquals(now.plusSeconds(15), test.fireAt)
        assertEquals("⏰ Test alarm", test.title)
        assertEquals("If you can hear this, urgent alarms work on this device.", test.body)
        assertTrue(test.urgent)
        assertEquals("{\"test\":true}", test.payload)
    }
}
