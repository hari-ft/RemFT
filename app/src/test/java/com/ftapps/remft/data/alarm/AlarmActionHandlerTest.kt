package com.ftapps.remft.data.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AlarmActionHandlerTest {
    private lateinit var t: TestAlarms
    private lateinit var taskId: String

    @Before
    fun setUp() = runBlocking {
        t = TestAlarms()
        taskId = t.tasks.repository.create(
            TaskDraft(title = "Call the plumber", remindAt = t.clock.instant().plus(Duration.ofMinutes(10)), isUrgent = true),
        )
        t.env.scheduler.pass()
        Unit
    }

    @After
    fun tearDown() = t.close()

    private fun first(): PlannedNotification = t.env.pending.read().values.minByOrNull { it.fireAtMillis }!!.toPlanned()

    private fun ringNow(notification: PlannedNotification) {
        t.clock.now = notification.fireAt.plusSeconds(5)
        t.env.gateway.show(notification)
        t.env.pending.remove(notification.id)
    }

    private fun reminderStatuses(): List<String?> = t.tasks.query("SELECT status FROM reminders").map { it["status"] }

    private fun appLogText(): String = File(File(t.tasks.dir, "logs"), AppLog.CURRENT).let {
        t.env.appLog.flush()
        if (it.exists()) it.readText() else ""
    }

    @Test
    fun acknowledgeFromOurNotificationAnswersAndSilences() = runBlocking {
        val ring = first()
        ringNow(ring)
        val result = t.env.actions.handle(ring.id, "acknowledge", ring.payload)
        assertEquals(ActionResult.APPLIED, result)
        assertEquals(listOf<String?>("acknowledged"), reminderStatuses())
        assertTrue(ring.id !in t.env.gateway.shownIds())
        assertTrue(t.env.pending.read().isEmpty())
    }

    @Test
    fun aButtonWhoseIdWeNeverBookedIsIgnoredAndLogged() = runBlocking {
        val ring = first()
        ringNow(ring)
        val result = t.env.actions.handle(12345, "acknowledge", ring.payload)
        assertEquals(ActionResult.NOT_OURS, result)
        assertTrue(reminderStatuses().isEmpty())
        assertTrue(appLogText().contains("alarm.event_not_ours"))
        assertTrue(ring.id in t.env.gateway.shownIds())
    }

    @Test
    fun snoozeWritesASnoozedRow() = runBlocking {
        val ring = first()
        ringNow(ring)
        val result = t.env.actions.handle(ring.id, "snooze:5_min", ring.payload)
        assertEquals(ActionResult.APPLIED, result)
        val row = t.tasks.query("SELECT status, snooze_count, snoozed_until FROM reminders").single()
        assertEquals("snoozed", row["status"])
        assertEquals("1", row["snooze_count"])
        assertEquals(UtcText.format(t.clock.instant().plus(Duration.ofMinutes(5))), row["snoozed_until"])
        val rebooked = t.env.pending.read().values.minByOrNull { it.fireAtMillis }!!
        assertEquals(t.clock.instant().plus(Duration.ofMinutes(5)).toEpochMilli(), rebooked.fireAtMillis)
    }

    @Test
    fun completeFinishesTheWholeTask() = runBlocking {
        val ring = first()
        ringNow(ring)
        val result = t.env.actions.handle(ring.id, "complete", ring.payload)
        assertEquals(ActionResult.APPLIED, result)
        assertEquals("completed", t.tasks.query("SELECT status FROM tasks WHERE id = '$taskId'").single()["status"])
        assertTrue(t.env.pending.read().isEmpty())
    }

    @Test
    fun aButtonFromBeforeTheTimeMovedIsNotOurs() = runBlocking {
        val ring = first()
        ringNow(ring)
        t.tasks.repository.update(taskId, TaskPatch(remindAt = Change(t.clock.instant().plus(Duration.ofHours(2)))))
        val result = t.env.actions.handle(ring.id, "acknowledge", ring.payload)
        assertEquals(ActionResult.NOT_OURS, result)
        assertTrue(reminderStatuses().isEmpty())
    }

    @Test
    fun anAnsweredAlarmPressedAgainIsNotOurs() = runBlocking {
        val ring = first()
        ringNow(ring)
        assertEquals(ActionResult.APPLIED, t.env.actions.handle(ring.id, "acknowledge", ring.payload))
        assertEquals(ActionResult.NOT_OURS, t.env.actions.handle(ring.id, "acknowledge", ring.payload))
    }

    @Test
    fun garbagePayloadIsIgnored() = runBlocking {
        assertEquals(ActionResult.IGNORED, t.env.actions.handle(1, "acknowledge", "{not json"))
        assertEquals(ActionResult.IGNORED, t.env.actions.handle(1, "acknowledge", null))
    }
}
