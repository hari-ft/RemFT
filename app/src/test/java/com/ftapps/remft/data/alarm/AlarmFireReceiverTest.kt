package com.ftapps.remft.data.alarm

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AlarmFireReceiverTest {
    private var t: TestAlarms? = null

    @After
    fun tearDown() {
        AlarmRuntime.install(null)
        t?.close()
    }

    private fun setUp(source: Flow<String?>): Pair<TestAlarms, PlannedNotification> = runBlocking {
        val alarms = TestAlarms(snoozeSource = source)
        t = alarms
        alarms.tasks.repository.create(
            TaskDraft(title = "Call the plumber", remindAt = alarms.clock.instant().plus(Duration.ofMinutes(10)), isUrgent = true),
        )
        alarms.env.scheduler.pass()
        AlarmRuntime.install(alarms.env)
        alarms to alarms.env.pending.read().values.minByOrNull { it.fireAtMillis }!!.toPlanned()
    }

    private fun fire(alarms: TestAlarms, notification: PlannedNotification) {
        val intent = AndroidAlarmGateway.fireIntent(alarms.context, notification.id)
            .putExtra(AndroidAlarmGateway.EXTRA_ALARM, AndroidAlarmGateway.encode(notification))
        AlarmFireReceiver().onReceive(alarms.context, intent)
    }

    private fun posted(alarms: TestAlarms, id: Int) =
        alarms.context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id }?.notification

    @Test
    fun aKnownOrderPostsStraightAwayInThatOrder() = runBlocking {
        val (alarms, ring) = setUp(flowOf("m:90,tomorrow_morning,5_min"))
        alarms.env.snoozeOrder.awaitReady(5_000)
        alarms.clock.now = ring.fireAt.plusSeconds(5)
        fire(alarms, ring)
        val notification = posted(alarms, ring.id)
        assertEquals(listOf("Acknowledge", "1 h 30 min"), notification!!.actions.map { it.title.toString() })
    }

    @Test
    fun anUnreadOrderIsAwaitedOffTheMainThreadBeforePosting() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val (alarms, ring) = setUp(flow { gate.await(); emit("m:90,5_min") })
        assertFalse(alarms.env.snoozeOrder.ready)
        alarms.clock.now = ring.fireAt.plusSeconds(5)
        fire(alarms, ring)
        assertNull(posted(alarms, ring.id))
        gate.complete(Unit)
        val notification = withTimeout(5_000) {
            var found = posted(alarms, ring.id)
            while (found == null) {
                delay(10)
                found = posted(alarms, ring.id)
            }
            found
        }
        assertTrue(alarms.env.snoozeOrder.ready)
        assertEquals(listOf("Acknowledge", "1 h 30 min"), notification.actions.map { it.title.toString() })
    }
}
