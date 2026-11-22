package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlarmBookingTest {

    private class FakeOs {
        val pending = LinkedHashMap<Int, PlannedNotification>()
        val cancelled = mutableListOf<Int>()
        val scheduleCalls = mutableListOf<Int>()
        var failSchedule: (PlannedNotification) -> Boolean = { false }
    }

    private class LaneState {
        val announced = LinkedHashMap<Int, AnnouncedNotification>()
        val policy = RebookPolicy()
    }

    private class FakeScheduler(
        val os: FakeOs,
        val privacyMode: Boolean = false,
        val lane: LaneState = LaneState(),
        val planner: AlarmPlanner = EnglishAlarmTexts.planner,
        val profile: ReminderProfile = ReminderProfile.Factory,
        val alarmSoundName: String? = null,
        val reminderSoundName: String? = null,
    ) {
        val events = mutableListOf<Pair<String, Int>>()

        fun refill() = lane.policy.requestRebook()

        fun desired(alarms: List<AlarmInput>, now: Instant): List<PlannedNotification> = planner.planNotifications(
            alarms = alarms,
            now = now,
            privacyMode = privacyMode,
            profile = profile,
            alarmSoundName = alarmSoundName,
            reminderSoundName = reminderSoundName,
            held = AlarmPlanner.heldRingsFromPayloads(os.pending.values.map { it.payload }, now),
        )

        fun pass(alarms: List<AlarmInput>, now: Instant, stopAfterBooking: Int? = null): BookingPlan {
            val pendingIds = os.pending.keys.toSet()
            for (result in AlarmBooking.reconcile(lane.announced, pendingIds, now)) {
                lane.announced.remove(result.id)
                events.add(result.outcome.name.lowercase() to result.id)
            }
            val desired = desired(alarms, now)
            val rebook = lane.policy.beginPass()
            val plan = AlarmBooking.plan(desired, pendingIds, rebook)
            var failures = 0
            for (id in plan.toCancel) {
                os.pending.remove(id)
                os.cancelled.add(id)
                lane.announced.remove(id)
                events.add("cancelled" to id)
            }
            var booked = 0
            for (notification in plan.toBook) {
                if (stopAfterBooking != null && booked == stopAfterBooking) return plan
                os.scheduleCalls.add(notification.id)
                booked++
                if (os.failSchedule(notification)) {
                    failures++
                    continue
                }
                os.pending[notification.id] = notification
                lane.announced[notification.id] = AlarmBooking.announce(notification)
            }
            lane.policy.endPass(rebook, failures)
            return plan
        }

        fun count(event: String): Int = events.count { it.first == event }
    }

    private val now = utc(2026, 7, 15, 12)

    private fun alarm(
        id: String,
        urgent: Boolean = false,
        title: String = "Task",
        after: Duration = Duration.ofHours(1),
    ) = AlarmInput(
        reminderId = pad26(id),
        taskId = pad26("T$id"),
        taskTitle = title,
        remindAt = now.plus(after),
        status = ReminderStatus.SCHEDULED,
        urgent = urgent,
        requiresAcknowledgement = urgent,
    )

    @Test
    fun schedulesThePlanThenCancelsWhatDisappears() {
        val os = FakeOs()
        val scheduler = FakeScheduler(os)
        scheduler.pass(listOf(alarm("R1"), alarm("R2", urgent = true, after = Duration.ofHours(2))), now)
        assertEquals(1 + ReminderProfile.Factory.offsets.size, os.pending.size)

        scheduler.pass(listOf(alarm("R1")), now)
        assertEquals(1, os.pending.size)
        assertEquals(ReminderProfile.Factory.offsets.size, os.cancelled.size)
    }

    @Test
    fun aNewProcessRebooksEveryAlarmThePluginStillListsThenDiffs() {
        val os = FakeOs()
        FakeScheduler(os).pass(listOf(alarm("R1")), now)
        val listed = os.pending.keys.toSet()
        assertTrue(listed.isNotEmpty())

        os.scheduleCalls.clear()
        val fresh = FakeScheduler(os)
        val first = fresh.pass(listOf(alarm("R1")), now)
        assertTrue(first.rebooking)
        assertTrue(first.toCancel.isEmpty())
        assertEquals(listed, os.scheduleCalls.toSet())

        os.scheduleCalls.clear()
        val second = fresh.pass(listOf(alarm("R1")), now)
        assertFalse(second.rebooking)
        assertTrue(os.scheduleCalls.isEmpty())

        fresh.refill()
        fresh.pass(listOf(alarm("R1")), now)
        assertEquals(listed, os.scheduleCalls.toSet())
    }

    @Test
    fun contentChangesRescheduleUnderANewIdentity() {
        val os = FakeOs()
        val scheduler = FakeScheduler(os)
        scheduler.pass(listOf(alarm("R1", title = "Eski ad")), now)
        val oldId = os.pending.keys.single()

        scheduler.pass(listOf(alarm("R1", title = "Yeni ad")), now)
        val newId = os.pending.keys.single()
        assertNotEquals(oldId, newId)
        assertTrue(oldId in os.cancelled)
        assertEquals("Yeni ad", os.pending.getValue(newId).title)
    }

    @Test
    fun anUnchangedPlanIsANoOp() {
        val os = FakeOs()
        val scheduler = FakeScheduler(os)
        scheduler.pass(listOf(alarm("R1")), now)
        val before = os.pending.size
        os.scheduleCalls.clear()

        val plan = scheduler.pass(listOf(alarm("R1")), now)
        assertEquals(before, os.pending.size)
        assertTrue(os.cancelled.isEmpty())
        assertTrue(os.scheduleCalls.isEmpty())
        assertTrue(plan.toCancel.isEmpty())
        assertTrue(plan.toBook.isEmpty())
    }

    @Test
    fun theTestAlarmIsTheFixedRehearsal() {
        val test = EnglishAlarmTexts.planner.testAlarm(now)
        assertEquals(NotificationIds.TEST_ALARM, test.id)
        assertEquals(AlarmPlanner.TEST_NOTIFICATION_ID, test.id)
        assertEquals(now.plus(Duration.ofSeconds(15)), test.fireAt)
        assertEquals("⏰ Test alarm", test.title)
        assertEquals("If you can hear this, urgent alarms work on this device.", test.body)
        assertTrue(test.urgent)
    }

    @Test
    fun aTestAlarmSurvivesTheNextPlanningPass() {
        val os = FakeOs()
        val scheduler = FakeScheduler(os)
        val test = EnglishAlarmTexts.planner.testAlarm(now)
        os.pending[test.id] = test

        val plan = scheduler.pass(listOf(alarm("R1")), now)
        assertFalse(AlarmPlanner.TEST_NOTIFICATION_ID in plan.toCancel)
        assertFalse(AlarmPlanner.TEST_NOTIFICATION_ID in os.cancelled)
        assertTrue(os.pending.containsKey(AlarmPlanner.TEST_NOTIFICATION_ID))

        scheduler.pass(emptyList(), now)
        assertTrue(os.pending.containsKey(AlarmPlanner.TEST_NOTIFICATION_ID))
    }

    @Test
    fun oneFailingScheduleDoesNotCancelTheRestOfThePass() {
        val os = FakeOs()
        os.failSchedule = { it.reminderId == pad26("R2") }
        val scheduler = FakeScheduler(os)
        scheduler.pass(
            listOf(alarm("R1"), alarm("R2", after = Duration.ofHours(2)), alarm("R3", after = Duration.ofHours(3))),
            now,
        )
        val booked = os.pending.values.map { it.reminderId }.toSet()
        assertEquals(setOf(pad26("R1"), pad26("R3")), booked)
    }

    @Test
    fun theRebookFlagStaysOnAfterAFailedPass() {
        val os = FakeOs()
        val lane = LaneState()
        os.failSchedule = { it.reminderId == pad26("R2") }
        val scheduler = FakeScheduler(os, lane = lane)
        val alarms = listOf(alarm("R1"), alarm("R2", after = Duration.ofHours(2)))
        scheduler.pass(alarms, now)
        assertTrue(lane.policy.rebookAll)

        os.failSchedule = { false }
        os.scheduleCalls.clear()
        val second = scheduler.pass(alarms, now)
        assertTrue(second.rebooking)
        assertEquals(2, os.scheduleCalls.size)
        assertFalse(lane.policy.rebookAll)

        os.scheduleCalls.clear()
        val third = scheduler.pass(alarms, now)
        assertFalse(third.rebooking)
        assertTrue(os.scheduleCalls.isEmpty())
    }

    @Test
    fun rebookPolicyRebooksOnTheFirstPassAndOnRequest() {
        val policy = RebookPolicy()
        assertTrue(policy.rebookAll)
        assertTrue(policy.beginPass())
        policy.endPass(rebooked = true, failures = 0)
        assertFalse(policy.rebookAll)
        assertFalse(policy.beginPass())
        policy.requestRebook()
        assertTrue(policy.beginPass())
        policy.endPass(rebooked = true, failures = 0)
        assertFalse(policy.beginPass())
    }

    @Test
    fun rebookPolicyNeverClearsOnAPassThatDidNotRebook() {
        val policy = RebookPolicy()
        policy.endPass(rebooked = false, failures = 0)
        assertTrue(policy.rebookAll)
        policy.endPass(rebooked = true, failures = 1)
        assertTrue(policy.rebookAll)
    }

    @Test
    fun aRequestThatLeavesTheQueueAfterItsTimeWasDelivered() {
        val os = FakeOs()
        val scheduler = FakeScheduler(os)
        scheduler.pass(listOf(alarm("R1", after = Duration.ofMinutes(30))), now)
        val id = os.pending.keys.single()

        os.pending.remove(id)
        scheduler.pass(listOf(alarm("R1", after = Duration.ofMinutes(30))), now.plus(Duration.ofHours(2)))

        assertEquals(listOf("delivered" to id), scheduler.events)
        assertEquals(0, scheduler.count("dropped"))
    }

    @Test
    fun aRequestThatVanishesBeforeItsTimeWasDroppedByTheOs() {
        val os = FakeOs()
        val scheduler = FakeScheduler(os)
        scheduler.pass(listOf(alarm("R1", after = Duration.ofHours(6))), now)
        val id = os.pending.keys.single()

        os.pending.remove(id)
        scheduler.pass(listOf(alarm("R1", after = Duration.ofHours(6))), now)

        assertEquals(1, scheduler.count("dropped"))
        assertEquals(0, scheduler.count("delivered"))
    }

    @Test
    fun ourOwnCancellationIsNotReportedAsADelivery() {
        val os = FakeOs()
        val scheduler = FakeScheduler(os)
        scheduler.pass(listOf(alarm("R1", after = Duration.ofHours(6))), now)

        scheduler.pass(emptyList(), now)

        assertEquals(0, scheduler.count("delivered"))
        assertEquals(0, scheduler.count("dropped"))
        assertEquals(1, scheduler.count("cancelled"))

        scheduler.pass(emptyList(), now.plus(Duration.ofHours(7)))
        assertEquals(0, scheduler.count("delivered"))
        assertEquals(0, scheduler.count("dropped"))
    }

    @Test
    fun reconcileSplitsDeliveredFromDroppedAndSkipsWhatIsStillPending() {
        val announced = linkedMapOf(
            1 to AnnouncedNotification(now.minusSeconds(60), urgent = true, reminderId = "A"),
            2 to AnnouncedNotification(now.plusSeconds(60), urgent = false, reminderId = "B"),
            3 to AnnouncedNotification(now.plusSeconds(60), urgent = false, reminderId = "C"),
            4 to AnnouncedNotification(now, urgent = false, reminderId = null),
        )
        val results = AlarmBooking.reconcile(announced, pending = setOf(3), now = now)
        assertEquals(
            listOf(1 to BookingOutcome.DELIVERED, 2 to BookingOutcome.DROPPED, 4 to BookingOutcome.DELIVERED),
            results.map { it.id to it.outcome },
        )
        assertEquals("A", results.first().announced.reminderId)
    }

    @Test
    fun comingBackToTheForegroundRefillsTheWindow() {
        val os = FakeOs()
        val scheduler = FakeScheduler(os)
        scheduler.pass(listOf(alarm("R1")), now)

        os.pending.clear()
        scheduler.refill()
        scheduler.pass(listOf(alarm("R1")), now)
        assertEquals(1, os.pending.size)
    }

    @Test
    fun aSchedulerRebuiltMidPassBooksItsOwnPlanAndCancelsOnlyWhatTheOldOneBooked() {
        val os = FakeOs()
        val lane = LaneState()
        val feed = listOf(
            taskAlarm("R1", Duration.ofHours(1)),
            taskAlarm("R2", Duration.ofHours(2)),
            taskAlarm("R3", Duration.ofHours(3)),
        )
        val oldIds = EnglishAlarmTexts.planner.planNotifications(feed, now, privacyMode = false, maxPending = 40)
            .map { it.id }.toSet()
        val newIds = EnglishAlarmTexts.planner.planNotifications(feed, now, privacyMode = true, maxPending = 40)
            .map { it.id }.toSet()
        assertTrue(oldIds.intersect(newIds).isEmpty())

        FakeScheduler(os, privacyMode = false, lane = lane).pass(feed, now, stopAfterBooking = 1)
        assertEquals(1, os.scheduleCalls.size)
        val oldFirst = os.scheduleCalls.first()
        assertTrue(oldFirst in oldIds)

        FakeScheduler(os, privacyMode = true, lane = lane).pass(feed, now)

        assertEquals(listOf(oldFirst), os.scheduleCalls.filter { it in oldIds })
        assertEquals(listOf(oldFirst), os.cancelled)
        assertEquals(newIds, os.pending.keys.toSet())
        val booked = os.scheduleCalls.filter { it in newIds }
        assertEquals(newIds.size, booked.size)
        assertEquals(booked.size, booked.toSet().size)
    }

    @Test
    fun aRebuiltSchedulerKeepsWhatTheOldOneAnnounced() {
        val os = FakeOs()
        val lane = LaneState()
        FakeScheduler(os, lane = lane).pass(listOf(taskAlarm("R1", Duration.ofMinutes(30))), now)
        val id = os.pending.keys.single()

        os.pending.remove(id)
        val rebuilt = FakeScheduler(os, lane = lane)
        rebuilt.pass(listOf(taskAlarm("R1", Duration.ofMinutes(30))), now.plus(Duration.ofHours(2)))

        assertEquals(1, rebuilt.count("delivered"))
    }

    private fun taskAlarm(id: String, after: Duration) = AlarmInput(
        reminderId = pad26(id),
        taskId = pad26("T$id"),
        taskTitle = "Task $id",
        remindAt = now.plus(after),
        status = ReminderStatus.SCHEDULED,
        urgent = false,
        requiresAcknowledgement = false,
    )

    @Test
    fun theSchedulerLeavesTheMovedRingBookedAfterTheOtherAlarmIsAnswered() {
        val nine = utc(2030, 6, 1, 9)
        val quarterPast = nine.plus(Duration.ofMinutes(15))
        val twoPast = nine.plus(Duration.ofMinutes(2))
        fun heldAlarm(id: String) = AlarmInput(
            reminderId = pad26(id),
            taskId = pad26("T$id"),
            taskTitle = "Task $id",
            remindAt = nine,
            status = ReminderStatus.SCHEDULED,
            urgent = false,
            requiresAcknowledgement = false,
        )
        val r1 = heldAlarm("R1")
        val r2 = heldAlarm("R2")
        val os = FakeOs()
        val scheduler = FakeScheduler(os)

        scheduler.pass(listOf(r1, r2), nine.minus(Duration.ofHours(1)))
        val moved = os.pending.values.single { it.fireAt == quarterPast }
        assertEquals(r2.reminderId, moved.reminderId)

        scheduler.pass(listOf(r2), twoPast)
        assertEquals(listOf(moved.id), os.pending.keys.toList())
        assertFalse(moved.id in os.cancelled)
    }

    @Test
    fun aColdStartBooksOnePassUnderTheUsersSettings() {
        val at = now.plus(Duration.ofHours(1))
        val profile = ReminderProfile(slots = listOf(0, 1, 3))
        val installed = listOf("mine.mp3")
        val alarmSound = AlarmSounds.resolve(SoundChoice.parse("file:mine"), installed).name
        val reminderSound = AlarmSounds.resolve(SoundChoice.parse("bundled:ping"), installed).name
        val alarms = listOf(
            AlarmInput(
                reminderId = pad26("R1"),
                taskId = pad26("T1"),
                taskTitle = "Take pills",
                remindAt = at,
                status = ReminderStatus.SCHEDULED,
                urgent = true,
                requiresAcknowledgement = true,
            ),
            AlarmInput(
                reminderId = pad26("R2"),
                taskId = pad26("T2"),
                taskTitle = "Water plants",
                remindAt = at.plus(Duration.ofHours(1)),
                status = ReminderStatus.SCHEDULED,
                urgent = false,
                requiresAcknowledgement = false,
            ),
        )
        val os = FakeOs()
        val scheduler = FakeScheduler(
            os,
            privacyMode = true,
            profile = profile,
            alarmSoundName = alarmSound,
            reminderSoundName = reminderSound,
        )
        scheduler.pass(alarms, now)

        assertEquals(4, os.scheduleCalls.size)
        assertEquals(4, os.scheduleCalls.toSet().size)
        assertTrue(os.cancelled.isEmpty())
        val booked = os.pending.values
        assertTrue(booked.all { it.title == "RemFT" })
        assertEquals(
            mapOf(true to AlarmSounds.contentUri("mine.mp3"), false to "remft_ping"),
            booked.associate { it.urgent to it.soundName },
        )
        assertEquals(
            setOf("alarm.file.mine", "reminder.raw.remft_ping"),
            AlarmSounds.channelsToKeep(alarmSound, reminderSound),
        )

        os.scheduleCalls.clear()
        scheduler.pass(alarms, now)
        assertTrue(os.scheduleCalls.isEmpty())
        assertTrue(os.cancelled.isEmpty())
    }

    @Test
    fun twoPlannersWithTheSameSettingsBookIdenticalIdsOnIdenticalChannels() {
        val at = now.plus(Duration.ofHours(1))
        val profile = ReminderProfile(slots = listOf(0, 1, 3))
        val alarmSound = AlarmSounds.contentUri("mine.mp3")
        val reminderSound = "remft_ping"
        fun parityAlarm(id: String, urgent: Boolean, after: Duration) = AlarmInput(
            reminderId = pad26(id),
            taskId = pad26("T$id"),
            taskTitle = "Task $id",
            remindAt = at.plus(after),
            status = ReminderStatus.SCHEDULED,
            urgent = urgent,
            requiresAcknowledgement = urgent,
        )
        val alarms = listOf(
            parityAlarm("R1", urgent = true, after = Duration.ZERO),
            parityAlarm("R2", urgent = true, after = Duration.ofHours(1)),
            parityAlarm("R3", urgent = false, after = Duration.ofHours(2)),
        )
        fun pass(planner: AlarmPlanner): Map<Int, PlannedNotification> {
            val os = FakeOs()
            FakeScheduler(
                os,
                privacyMode = true,
                planner = planner,
                profile = profile,
                alarmSoundName = alarmSound,
                reminderSoundName = reminderSound,
            ).pass(alarms, now)
            return os.pending
        }
        fun channels(booked: Map<Int, PlannedNotification>): Set<String> =
            booked.values.map { AlarmSounds.channelIdFor(it.urgent, it.soundName) }.toSet()

        val app = pass(EnglishAlarmTexts.planner)
        val headless = pass(AlarmPlanner(EnglishAlarmTexts.texts))

        assertEquals(7, app.size)
        assertEquals(app.keys, headless.keys)
        assertEquals(channels(app), channels(headless))
        assertTrue(headless.values.all { it.title == "RemFT" })
        assertEquals(
            mapOf(true to alarmSound, false to reminderSound),
            headless.values.associate { it.urgent to it.soundName },
        )
    }

    @Test
    fun planCancelsPendingNotDesiredExceptTheTestAlarmAndBooksDesiredNotPending() {
        val desired = EnglishAlarmTexts.planner.planNotifications(
            listOf(alarm("R1"), alarm("R2", after = Duration.ofHours(2))),
            now,
            privacyMode = false,
        )
        val kept = desired.first().id
        val pending = setOf(kept, 7, AlarmPlanner.TEST_NOTIFICATION_ID)

        val diff = AlarmBooking.plan(desired, pending, rebookEverything = false)
        assertEquals(setOf(7), diff.toCancel)
        assertEquals(listOf(desired[1]), diff.toBook)
        assertFalse(diff.rebooking)

        val rebook = AlarmBooking.plan(desired, pending, rebookEverything = true)
        assertEquals(setOf(7), rebook.toCancel)
        assertEquals(desired, rebook.toBook)
        assertTrue(rebook.rebooking)
    }
}
