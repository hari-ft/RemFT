package com.ftapps.remft.data.alarm

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class RingReplacementTest {
    private var t: TestAlarms? = null

    @After
    fun tearDown() {
        AlarmRuntime.install(null)
        t?.close()
    }

    private fun fire(alarms: TestAlarms, notification: PlannedNotification) {
        val intent = AndroidAlarmGateway.fireIntent(alarms.context, notification.id)
            .putExtra(AndroidAlarmGateway.EXTRA_ALARM, AndroidAlarmGateway.encode(notification))
        AlarmFireReceiver().onReceive(alarms.context, intent)
    }

    private fun shown(alarms: TestAlarms): Set<Int> =
        alarms.context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id }.toSet()

    @Test
    fun aReRingReplacesTheEarlierRingInsteadOfStackingIt() = runBlocking {
        val alarms = TestAlarms()
        t = alarms
        alarms.tasks.repository.create(
            TaskDraft(title = "Call the plumber", remindAt = alarms.clock.instant().plus(Duration.ofMinutes(10)), isUrgent = true),
        )
        alarms.env.scheduler.pass()
        AlarmRuntime.install(alarms.env)
        alarms.env.snoozeOrder.awaitReady(5_000)
        val rings = alarms.env.pending.read().values.sortedBy { it.fireAtMillis }.map { it.toPlanned() }
        val first = rings[0]
        val second = rings.first { it.reminderId == first.reminderId && it.fireAt.isAfter(first.fireAt) }
        alarms.clock.now = first.fireAt.plusSeconds(5)
        fire(alarms, first)
        assertEquals(setOf(first.id), shown(alarms))
        alarms.clock.now = second.fireAt.plusSeconds(5)
        fire(alarms, second)
        withTimeout(5_000) {
            while (shown(alarms) != setOf(second.id)) delay(10)
        }
        withTimeout(5_000) {
            while (alarms.env.log.recent().none { it.event == AlarmLogEvent.CANCELLED }) delay(10)
        }
        val logged = alarms.env.log.recent().filter { it.event == AlarmLogEvent.CANCELLED }
        assertEquals(listOf("shown id=${first.id} (replaced by id=${second.id})"), logged.map { it.detail })
    }

    @Test
    fun bundledSoundsAreAddressedByNameSoAChannelSurvivesResourceIdChanges() {
        val alarms = TestAlarms()
        t = alarms
        val pkg = alarms.context.packageName
        assertEquals("android.resource://$pkg/raw/remft_alarm", AndroidAlarmGateway.soundUri(alarms.context, "remft_alarm").toString())
        val id = AndroidAlarmGateway.ensureChannel(alarms.context, true, "remft_chime")
        val channel = alarms.context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)
        assertEquals("android.resource://$pkg/raw/remft_chime", channel.sound.toString())
    }
}
