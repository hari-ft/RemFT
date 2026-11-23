package com.ftapps.remft.data.alarm

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
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

    private suspend fun awaitSnoozeOrder(expected: List<String>) {
        withTimeout(5_000) { while (t.env.snoozeOrder.current() != expected) delay(10) }
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
    fun answeringAlarmsWritesToTheAlarmLogOnlyNotTheAppLog() = runBlocking {
        val ring = first()
        ringNow(ring)
        assertEquals(ActionResult.APPLIED, t.env.actions.handle(ring.id, "snooze:5_min", ring.payload))
        val again = first()
        ringNow(again)
        assertEquals(ActionResult.APPLIED, t.env.actions.handle(again.id, "acknowledge", again.payload))
        t.tasks.repository.update(taskId, TaskPatch(remindAt = Change(t.clock.instant().plus(Duration.ofHours(2)))))
        t.env.scheduler.pass()
        assertTrue(t.env.log.recent().isNotEmpty())
        val text = appLogText()
        assertTrue(text, text.lines().none { it.contains("\"e\":\"alarm.") })
        assertTrue(t.env.appLog.read().none { it.fields.containsKey("taskId") || it.fields.containsKey("reminderId") })
        assertTrue(text, !text.contains("plumber"))
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
    fun aCustomSnoozeOptionSnoozesForItsOwnLength() = runBlocking {
        t.env.settings.set(SettingKeys.SnoozePresets.name, "m:90,tomorrow_morning")
        val ring = first()
        ringNow(ring)
        val result = t.env.actions.handle(ring.id, "snooze:m:90", ring.payload)
        assertEquals(ActionResult.APPLIED, result)
        val row = t.tasks.query("SELECT status, snooze_count, snoozed_until FROM reminders").single()
        assertEquals("snoozed", row["status"])
        assertEquals(UtcText.format(t.clock.instant().plus(Duration.ofMinutes(90))), row["snoozed_until"])
        val rebooked = t.env.pending.read().values.minByOrNull { it.fireAtMillis }!!
        assertEquals(t.clock.instant().plus(Duration.ofMinutes(90)).toEpochMilli(), rebooked.fireAtMillis)
    }

    @Test
    fun theNotificationShowsTheFirstOptionsInTheUsersOrder() = runBlocking {
        t.env.settings.set(SettingKeys.SnoozePresets.name, "m:90,tomorrow_morning,5_min")
        awaitSnoozeOrder(listOf("m:90", "tomorrow_morning", "5_min"))
        val ring = first()
        ringNow(ring)
        val manager = t.context.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.single { it.id == ring.id }.notification
        assertEquals(listOf("Acknowledge", "1 h 30 min"), posted.actions.map { it.title.toString() })
        val actionIds = posted.actions.map { shadowOf(it.actionIntent).savedIntent.getStringExtra(AndroidAlarmGateway.EXTRA_ACTION) }
        assertEquals(listOf("acknowledge", "snooze:m:90"), actionIds)
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

    @Test
    fun aButtonStillAnswersAfterTitlePrivacyProfileAndSoundChanged() = runBlocking {
        val ring = first()
        ringNow(ring)
        t.tasks.repository.update(taskId, TaskPatch(title = "Call the electrician"))
        t.env.settings.set(SettingKeys.NotificationPrivacy.name, "true")
        t.env.settings.set(SettingKeys.ReminderProfile.name, "{\"slots\":[0,5],\"repeatAfterSnooze\":true}")
        t.env.settings.set(SettingKeys.AlarmSound.name, "os")
        val result = t.env.actions.handle(ring.id, "acknowledge", ring.payload)
        assertEquals(ActionResult.APPLIED, result)
        assertEquals(listOf<String?>("acknowledged"), reminderStatuses())
        assertTrue(ring.id !in t.env.gateway.shownIds())
    }

    @Test
    fun aStaleButtonStillDismissesItsNotification() = runBlocking {
        val ring = first()
        ringNow(ring)
        t.tasks.repository.update(taskId, TaskPatch(remindAt = Change(t.clock.instant().plus(Duration.ofHours(2)))))
        assertEquals(ActionResult.NOT_OURS, t.env.actions.handle(ring.id, "acknowledge", ring.payload))
        assertTrue(ring.id !in t.env.gateway.shownIds())
    }

    @Test
    fun inAppSilencingFindsTheShownRingAfterARename() = runBlocking {
        val ring = first()
        ringNow(ring)
        t.tasks.repository.update(taskId, TaskPatch(title = "Call the electrician"))
        t.env.settings.set(SettingKeys.NotificationPrivacy.name, "true")
        val silenced = t.env.silencer.silence(taskId, null, emptyList(), "test")
        assertEquals(1, silenced)
        assertTrue(ring.id !in t.env.gateway.shownIds())
    }

    @Test
    fun theTestAlarmCanBeAnsweredAndTappingItClearsIt() = runBlocking {
        val test = t.env.planner.testAlarm(t.clock.instant(), "remft_alarm")
        t.env.gateway.show(test)
        val manager = t.context.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.single { it.id == test.id }.notification
        assertEquals(listOf("Acknowledge"), posted.actions.map { it.title.toString() })
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val payload = shadowOf(posted.actions.single().actionIntent).savedIntent.getStringExtra(AndroidAlarmGateway.EXTRA_PAYLOAD)
        assertEquals(AlarmPlanner.TEST_PAYLOAD, payload)
        assertEquals(ActionResult.APPLIED, t.env.actions.handle(test.id, "acknowledge", payload))
        assertTrue(test.id !in t.env.gateway.shownIds())
    }
}
