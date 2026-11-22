package com.ftapps.remft.ui.alarm

import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.AlarmLogEvent
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.alarm.ReminderStatus
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class InAppRingTest {
    private class FakeFeedback : AlarmFeedback {
        override val state = MutableStateFlow(RingFeedback())
        var starts = 0
        var stops = 0

        override fun start() {
            starts++
            state.value = RingFeedback(playing = true)
        }

        override fun stop() {
            stops++
            state.value = state.value.copy(playing = false)
        }

        override fun pulse() = Unit
    }

    private var t: TestAlarms? = null
    private val made = mutableListOf<FakeFeedback>()

    @After
    fun tearDown() {
        t?.close()
    }

    private fun rig(): Pair<AlarmInput, PlannedNotification> = runBlocking {
        val alarms = TestAlarms()
        t = alarms
        alarms.tasks.repository.create(
            TaskDraft(title = "Call the plumber", remindAt = alarms.clock.instant().plus(Duration.ofMinutes(10)), isUrgent = true),
        )
        alarms.env.scheduler.pass()
        val first = alarms.env.pending.read().values.minByOrNull { it.fireAtMillis }!!.toPlanned()
        alarms.clock.now = first.fireAt.plusSeconds(5)
        alarms.env.gateway.show(first)
        alarms.env.workspaceFeed().first { it.reminderId == first.reminderId } to first
    }

    private fun ring(): InAppRing = InAppRing(t!!.env, sweepMs = 50) { FakeFeedback().also { made += it } }

    private fun posted(id: Int): Notification? =
        t!!.context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id }?.notification

    private fun events(event: String): Int = runBlocking { t!!.env.log.recent().count { it.event == event } }

    private fun waitUntil(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!what()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun idleFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun aRebuiltRingScreenKeepsTheOnePlayerAndIsLoggedOnce() {
        val (alarm, _) = rig()
        val ring = ring()
        ring.attach(alarm, volumeOff = false)
        ring.shown(true)
        waitUntil { made.singleOrNull()?.starts == 1 }
        ring.attach(alarm, volumeOff = false)
        ring.shown(true)
        ring.attach(alarm, volumeOff = false)
        ring.shown(true)
        waitUntil { events(AlarmLogEvent.RING_SHOWN) >= 1 }
        idleFor(200)
        assertEquals(1, made.size)
        assertEquals(1, made.single().starts)
        assertEquals(0, made.single().stops)
        assertEquals(1, events(AlarmLogEvent.RING_SHOWN))
    }

    @Test
    fun aRingThatGoesAwayBeforeItsSoundStartsNeverPlays() {
        val (alarm, _) = rig()
        val ring = ring()
        ring.attach(alarm, volumeOff = false)
        ring.shown(true)
        ring.keepOnly(null)
        idleFor(300)
        assertEquals(0, made.single().starts)
        assertNull(ring.ringingKey)
    }

    @Test
    fun leavingTheAppDuringARingHandsItBackToTheNotificationAndReturningTakesItAgain() {
        val (alarm, first) = rig()
        val ring = ring()
        ring.attach(alarm, volumeOff = false)
        ring.shown(true)
        waitUntil { made.single().state.value.playing && posted(first.id) == null }

        ring.shown(false)
        assertTrue(!made.single().state.value.playing)
        waitUntil { posted(first.id) != null }
        val restored = posted(first.id)!!
        assertEquals(listOf("Acknowledge", "5 min"), restored.actions.map { it.title.toString() })
        assertNull(restored.fullScreenIntent)
        assertTrue(restored.flags and Notification.FLAG_INSISTENT != 0)
        idleFor(200)
        assertNotNull(posted(first.id))

        ring.shown(true)
        waitUntil { made.single().starts == 2 && posted(first.id) == null }
        assertEquals(1, made.size)
    }

    @Test
    fun anAnswerFinishesEvenWhenTheRingScreenGoesAwayMidway() {
        val (alarm, first) = rig()
        val ring = ring()
        ring.attach(alarm, volumeOff = false)
        ring.shown(true)
        waitUntil { made.single().state.value.playing }
        ring.shown(false)
        waitUntil { posted(first.id) != null }
        var opened: String? = null
        val actions = HostActions(t!!.env, alarm, missed = false, inApp = ring) { opened = it }
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { actions.acknowledge() }
            job.cancel()
            job.join()
        }
        waitUntil { opened != null }
        assertEquals(0, events(AlarmLogEvent.DEGRADED))
        assertNull(posted(first.id))
        assertNull(ring.ringingKey)
        val after = runBlocking { t!!.env.store.alarmsOfTask(alarm.taskId) }
        assertTrue(after.none { it.reminderId == alarm.reminderId && ReminderStatus.isActive(it.status) })
    }
}
