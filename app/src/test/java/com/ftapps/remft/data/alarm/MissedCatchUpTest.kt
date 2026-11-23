package com.ftapps.remft.data.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class MissedCatchUpTest {
    private lateinit var t: TestAlarms

    @Before
    fun setUp() {
        t = TestAlarms()
    }

    @After
    fun tearDown() = t.close()

    private val notifications: NotificationManager get() = t.context.getSystemService(NotificationManager::class.java)

    private fun phoneOff() {
        val manager = t.context.getSystemService(AlarmManager::class.java)
        shadowOf(manager).scheduledAlarms.toList().forEach { manager.cancel(it.operation!!) }
    }

    private suspend fun create(title: String, urgent: Boolean): String =
        t.tasks.repository.create(TaskDraft(title = title, remindAt = t.clock.instant().plus(Duration.ofMinutes(10)), isUrgent = urgent))

    private fun shown(): List<Notification> = notifications.activeNotifications.map { it.notification }

    @Test
    fun aReminderDueWhileThePhoneWasOffIsShownOnceWithButtons() = runBlocking {
        create("Water plants", urgent = false)
        t.env.scheduler.pass()
        val booked = t.env.pending.read().values.single()
        phoneOff()
        t.clock.advance(Duration.ofHours(1))
        t.env.scheduler.pass(rebook = true, reason = "system")
        val posted = notifications.activeNotifications.single()
        assertEquals(booked.id, posted.id)
        val notification = posted.notification
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertNull(notification.fullScreenIntent)
        assertEquals(listOf("Complete", "15 min", "1 hour"), notification.actions.map { it.title.toString() })
        assertEquals("Missed while the phone was off", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertTrue(t.env.pending.read().isEmpty())

        notifications.cancelAll()
        t.env.scheduler.pass(rebook = true, reason = "system")
        assertTrue(shown().isEmpty())

        val result = t.env.actions.handle(posted.id, "complete", booked.payload)
        assertEquals(ActionResult.APPLIED, result)
    }

    @Test
    fun anUrgentChainMissedWhileOffGetsOneQuietNotification() = runBlocking {
        create("Call the plumber", urgent = true)
        t.env.scheduler.pass()
        assertEquals(3, t.env.pending.read().size)
        phoneOff()
        t.clock.advance(Duration.ofHours(2))
        t.env.scheduler.pass(rebook = true, reason = "system")
        t.env.scheduler.pass(reason = "change")
        val notification = shown().single()
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertNull(notification.fullScreenIntent)
        assertEquals("Acknowledge", notification.actions.first().title.toString())
    }

    @Test
    fun aReminderMissedLongAgoIsNotShown() = runBlocking {
        create("Water plants", urgent = false)
        t.env.scheduler.pass()
        phoneOff()
        t.clock.advance(Duration.ofHours(30))
        t.env.scheduler.pass(rebook = true, reason = "system")
        assertTrue(shown().isEmpty())
    }

    @Test
    fun aReminderFinishedBeforeTheCatchUpIsNotShown() = runBlocking {
        val taskId = create("Water plants", urgent = false)
        t.env.scheduler.pass()
        phoneOff()
        t.env.store.complete(taskId)
        t.clock.advance(Duration.ofHours(1))
        t.env.scheduler.pass(rebook = true, reason = "system")
        assertTrue(shown().isEmpty())
    }
}
