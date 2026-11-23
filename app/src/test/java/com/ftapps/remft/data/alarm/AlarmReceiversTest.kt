package com.ftapps.remft.data.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AlarmReceiversTest {
    private lateinit var t: TestAlarms

    @Before
    fun setUp() {
        t = TestAlarms()
        AlarmRuntime.install(t.env)
    }

    @After
    fun tearDown() {
        AlarmRuntime.install(null)
        t.close()
    }

    private val notifications: NotificationManager get() = t.context.getSystemService(NotificationManager::class.java)

    private val alarmManager: AlarmManager get() = t.context.getSystemService(AlarmManager::class.java)

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met")
            Thread.sleep(20)
        }
    }

    private suspend fun bookedFor(title: String, urgent: Boolean): PlannedNotification {
        val now = t.clock.instant()
        t.tasks.repository.create(TaskDraft(title = title, remindAt = now.plus(Duration.ofHours(1)), isUrgent = urgent))
        t.env.scheduler.pass()
        return t.env.pending.read().values.sortedBy { it.fireAtMillis }.first().toPlanned()
    }

    private fun fire(notification: PlannedNotification) {
        t.clock.now = notification.fireAt.plusSeconds(1)
        val intent = AndroidAlarmGateway.fireIntent(t.context, notification.id)
            .putExtra(AndroidAlarmGateway.EXTRA_ALARM, AndroidAlarmGateway.encode(notification))
        t.context.sendBroadcast(intent)
    }

    @Test
    fun urgentFirePostsAnInsistentFullScreenAlarm() = runBlocking {
        val booked = bookedFor("Call the plumber", urgent = true)
        fire(booked)
        waitFor { notifications.activeNotifications.any { it.id == booked.id } }
        val posted = notifications.activeNotifications.single { it.id == booked.id }.notification
        assertEquals("alarm.raw.remft_alarm", posted.channelId)
        assertEquals(Notification.CATEGORY_ALARM, posted.category)
        assertTrue(posted.flags and Notification.FLAG_INSISTENT != 0)
        assertNotNull(posted.fullScreenIntent)
        assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility)
        assertEquals(listOf("Acknowledge", "5 min"), posted.actions.map { it.title.toString() })
        assertEquals("⏰ Call the plumber", posted.extras.getString(Notification.EXTRA_TITLE))
        val channel = notifications.getNotificationChannel("alarm.raw.remft_alarm")
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
        waitFor { booked.id !in t.env.pending.readOrEmpty() }
        waitFor { runBlocking { t.env.log.recent().any { it.event == AlarmLogEvent.DELIVERED } } }
    }

    @Test
    fun normalFirePostsAReminderWithThreeButtons() = runBlocking {
        val booked = bookedFor("Water plants", urgent = false)
        fire(booked)
        waitFor { notifications.activeNotifications.any { it.id == booked.id } }
        val posted = notifications.activeNotifications.single { it.id == booked.id }.notification
        assertEquals("reminder.system", posted.channelId)
        assertEquals(Notification.CATEGORY_REMINDER, posted.category)
        assertEquals(0, posted.flags and Notification.FLAG_INSISTENT)
        assertNull(posted.fullScreenIntent)
        assertEquals(listOf("Complete", "15 min", "1 hour"), posted.actions.map { it.title.toString() })
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, notifications.getNotificationChannel("reminder.system").audioAttributes.usage)
    }

    @Test
    fun actionButtonBroadcastAcknowledgesAndDismisses() = runBlocking {
        val booked = bookedFor("Call the plumber", urgent = true)
        fire(booked)
        waitFor { notifications.activeNotifications.any { it.id == booked.id } }
        val intent = Intent(t.context, AlarmActionReceiver::class.java)
            .setAction(AndroidAlarmGateway.ACTION_BUTTON)
            .putExtra(AndroidAlarmGateway.EXTRA_ID, booked.id)
            .putExtra(AndroidAlarmGateway.EXTRA_ACTION, "acknowledge")
            .putExtra(AndroidAlarmGateway.EXTRA_PAYLOAD, booked.payload)
        t.context.sendBroadcast(intent)
        waitFor { t.tasks.query("SELECT status FROM reminders").any { it["status"] == "acknowledged" } }
        waitFor { notifications.activeNotifications.none { it.id == booked.id } }
    }

    @Test
    fun bootRebooksEverything() = runBlocking {
        bookedFor("Call the plumber", urgent = true)
        val count = shadowOf(alarmManager).scheduledAlarms.size
        shadowOf(alarmManager).scheduledAlarms.toList().forEach { alarmManager.cancel(it.operation!!) }
        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
        t.context.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setClass(t.context, AlarmSystemReceiver::class.java))
        waitFor { shadowOf(alarmManager).scheduledAlarms.size == count }
        assertEquals(3, count)
    }

    @Test
    fun unknownSystemActionsAreIgnored() = runBlocking {
        bookedFor("Call the plumber", urgent = true)
        shadowOf(alarmManager).scheduledAlarms.toList().forEach { alarmManager.cancel(it.operation!!) }
        t.context.sendBroadcast(Intent("com.example.SOMETHING").setClass(t.context, AlarmSystemReceiver::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        assertFalse(shadowOf(alarmManager).scheduledAlarms.isNotEmpty())
    }

    @Test
    fun exactAlarmPermissionGrantedRebooksEverything() = runBlocking {
        bookedFor("Call the plumber", urgent = true)
        val count = shadowOf(alarmManager).scheduledAlarms.size
        shadowOf(alarmManager).scheduledAlarms.toList().forEach { alarmManager.cancel(it.operation!!) }
        t.context.sendBroadcast(
            Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED").setClass(t.context, AlarmSystemReceiver::class.java),
        )
        waitFor { shadowOf(alarmManager).scheduledAlarms.size == count }
        assertEquals(3, count)
    }

    @Test
    fun comingBackWithExactAlarmsAllowedAgainAsksForARebook() {
        val startup = AlarmStartup(t.env)
        t.health.current = t.health.current.copy(exactAlarms = false)
        assertFalse(startup.exactAlarmsRegained())
        assertFalse(startup.exactAlarmsRegained())
        t.health.current = t.health.current.copy(exactAlarms = true)
        assertTrue(startup.exactAlarmsRegained())
        assertFalse(startup.exactAlarmsRegained())
    }
}
