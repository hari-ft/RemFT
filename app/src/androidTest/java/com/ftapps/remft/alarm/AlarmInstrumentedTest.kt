package com.ftapps.remft.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.AlarmActionReceiver
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmRuntime
import com.ftapps.remft.data.alarm.AlarmSystemReceiver
import com.ftapps.remft.data.alarm.AndroidAlarmGateway
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.tasks.LiveZoneClock
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.tasks.TaskDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AlarmInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var holder: DatabaseHolder
    private lateinit var env: AlarmEnvironment
    private lateinit var tasks: TasksRepository

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "alarm-it-${System.nanoTime()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        holder = DatabaseHolder(context, File(dir, "remft.sqlite"))
        val identity = LocalIdentityRepository({ holder.db.identityDao() }, settings)
        tasks = TasksRepository({ holder.db }, identity, settings, LiveZoneClock, File(dir, "files"))
        env = AlarmEnvironment(context, { holder.db }, identity, settings, tasks, LiveZoneClock, filesDir = dir, scope = scope)
        AlarmRuntime.install(env)
    }

    @After
    fun tearDown() {
        runBlocking { env.pending.read().keys.forEach { env.gateway.cancel(it); env.gateway.cancelShown(it) } }
        AlarmRuntime.install(null)
        holder.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun poll(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met")
            Thread.sleep(100)
        }
    }

    private fun urgent(at: Instant): PlannedNotification = PlannedNotification(
        id = 777_001,
        title = "⏰ Instrumented alarm",
        body = "Urgent reminder — waiting for acknowledgement",
        fireAt = at,
        urgent = true,
        payload = "{\"taskId\":\"01JTESTTASK00000000000000\",\"reminderId\":\"task:remind:01JTESTTASK00000000000000\",\"kind\":\"remind\",\"at\":${at.toEpochMilli()},\"chainIndex\":0,\"planned\":${at.toEpochMilli()},\"fireAt\":${at.toEpochMilli()}}",
        kind = "remind",
        slotIndex = 0,
        taskId = "01JTESTTASK00000000000000",
        reminderId = "task:remind:01JTESTTASK00000000000000",
        soundName = "remft_alarm",
    )

    @Test
    fun urgentAlarmIsBookedAsAnExactAlarmClock() {
        val at = Instant.now().plus(Duration.ofMinutes(30))
        val gateway = AndroidAlarmGateway(context)
        gateway.book(urgent(at))
        try {
            val next = alarmManager.nextAlarmClock
            assertNotNull(next)
            assertTrue(next!!.triggerTime <= at.toEpochMilli())
        } finally {
            gateway.cancel(777_001)
        }
    }

    @Test
    fun urgentNotificationCarriesAFullScreenIntent() {
        val gateway = AndroidAlarmGateway(context)
        val notification = urgent(Instant.now())
        gateway.show(notification)
        try {
            poll { notifications.activeNotifications.any { it.id == notification.id } }
            val posted = notifications.activeNotifications.single { it.id == notification.id }.notification
            assertNotNull(posted.fullScreenIntent)
            assertEquals(Notification.CATEGORY_ALARM, posted.category)
            assertTrue(posted.flags and Notification.FLAG_INSISTENT != 0)
        } finally {
            gateway.cancelShown(notification.id)
        }
    }

    @Test
    fun actionReceiverAcknowledgesTheRingingAlarm() = runBlocking {
        tasks.create(TaskDraft(title = "Instrumented task", remindAt = Instant.now().plus(Duration.ofMinutes(20)), isUrgent = true))
        env.scheduler.pass()
        val first = env.pending.read().values.minByOrNull { it.fireAtMillis }!!.toPlanned()
        env.gateway.show(first)
        context.sendBroadcast(
            Intent(context, AlarmActionReceiver::class.java)
                .setAction(AndroidAlarmGateway.ACTION_BUTTON)
                .putExtra(AndroidAlarmGateway.EXTRA_ID, first.id)
                .putExtra(AndroidAlarmGateway.EXTRA_ACTION, "acknowledge")
                .putExtra(AndroidAlarmGateway.EXTRA_PAYLOAD, first.payload),
        )
        poll { runBlocking { holder.db.alarmDao().allReminders().any { it.status == "acknowledged" } } }
        poll { notifications.activeNotifications.none { it.id == first.id } }
    }

    @Test
    fun bootRebooksTheAlarms() = runBlocking {
        tasks.create(TaskDraft(title = "Instrumented task", remindAt = Instant.now().plus(Duration.ofMinutes(40)), isUrgent = true))
        env.scheduler.pass()
        val booked = env.pending.read().values.map { it.toPlanned() }
        booked.forEach { env.gateway.cancel(it.id) }
        AlarmSystemReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        poll { alarmManager.nextAlarmClock?.triggerTime == booked.minOf { it.fireAt.toEpochMilli() } }
    }
}
