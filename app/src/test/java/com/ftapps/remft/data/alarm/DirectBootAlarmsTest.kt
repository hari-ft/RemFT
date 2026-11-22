package com.ftapps.remft.data.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.MutableClock
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class DirectBootAlarmsTest {
    private lateinit var t: TestAlarms
    private var locked = false
    private var lockedDatabaseUse = 0

    @Before
    fun setUp() {
        t = TestAlarms(
            clock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS)),
            onDatabase = { if (locked) lockedDatabaseUse++ },
        )
        AlarmRuntime.install(t.env)
    }

    @After
    fun tearDown() {
        setLocked(false)
        AlarmRuntime.install(null)
        t.close()
    }

    private fun setLocked(value: Boolean) {
        locked = value
        shadowOf(t.context.getSystemService(UserManager::class.java)).setUserUnlocked(!value)
    }

    private val alarmManager: AlarmManager get() = t.context.getSystemService(AlarmManager::class.java)

    private val notifications: NotificationManager get() = t.context.getSystemService(NotificationManager::class.java)

    private fun scheduled() = shadowOf(alarmManager).scheduledAlarms

    private fun clearAlarmManager() {
        scheduled().toList().forEach { alarmManager.cancel(it.operation!!) }
        assertEquals(0, scheduled().size)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met")
            Thread.sleep(20)
        }
    }

    private suspend fun book(title: String, urgent: Boolean = true) {
        t.tasks.repository.create(TaskDraft(title = title, remindAt = t.clock.instant().plus(Duration.ofHours(1)), isUrgent = urgent))
        t.env.scheduler.pass()
    }

    private fun mirrorFile(): File = File(File(DirectBoot.storage(t.context).filesDir, DirectBootMirror.DIRECTORY), DirectBootMirror.FILE_NAME)

    @Test
    fun aPassMirrorsWhatIsBookedIntoDeviceProtectedStorage() = runBlocking {
        book("Sample urgent task")
        val pending = t.env.pending.read()
        val mirrored = DirectBootMirror.of(t.context).read()
        assertTrue(mirrorFile().exists())
        assertEquals(pending.keys, mirrored.alarms.map { it.id }.toSet())
        assertEquals(pending.values.map { it.title }.toSet(), mirrored.alarms.map { it.title }.toSet())
        assertFalse(mirrored.privacy)
        assertTrue(mirrored.alarms.all { it.title.contains("Sample urgent task") })
    }

    @Test
    fun privacyModeMirrorsOnlyGenericText() = runBlocking {
        book("Sample secret task")
        assertTrue(mirrorFile().readText().contains("Sample secret task"))
        t.tasks.settings.set(SettingKeys.NotificationPrivacy.name, "true")
        t.env.scheduler.pass()
        val mirrored = DirectBootMirror.of(t.context).read()
        assertTrue(mirrored.privacy)
        assertTrue(mirrored.alarms.isNotEmpty())
        assertTrue(mirrored.alarms.all { it.title == t.env.texts.privacyTitle && it.body == t.env.texts.privateBody })
        assertFalse(mirrorFile().readText().contains("Sample secret task"))
    }

    @Test
    fun theMirrorHoldsOnlyTheNextFortyEightHoursAndIsCapped() {
        val now = Instant.parse("2026-11-20T04:30:00Z")
        val alarms = (0 until 60).map { hour ->
            BookedAlarm(id = hour + 1, title = "t", body = "b", fireAtMillis = now.plus(Duration.ofHours(hour.toLong())).toEpochMilli(), urgent = true, payload = "p")
        } + BookedAlarm(id = 999, title = "t", body = "b", fireAtMillis = now.minus(Duration.ofHours(1)).toEpochMilli(), urgent = true, payload = "p")
        val kept = DirectBootMirror.bounded(alarms, now)
        assertEquals(DirectBootMirror.MAX_ALARMS, kept.size)
        assertTrue(kept.none { it.id == 999 })
        assertTrue(kept.all { !it.fireAt.isAfter(now.plus(DirectBootMirror.WINDOW)) })
        assertEquals((1..DirectBootMirror.MAX_ALARMS).toList(), kept.map { it.id })
    }

    @Test
    fun uploadedSoundsAreNotMirroredBecauseTheirFilesAreLockedUntilUnlock() {
        val alarm = BookedAlarm(id = 7, title = "t", body = "b", fireAtMillis = 0, urgent = true, payload = "p", soundName = "content://sounds/file.1")
        val texts = t.env.texts
        assertEquals(null, DirectBootMirror.mirrored(alarm, false, texts).soundName)
        assertEquals("raw.remft_alarm", DirectBootMirror.mirrored(alarm.copy(soundName = "raw.remft_alarm"), false, texts).soundName)
    }

    @Test
    fun lockedBootRebooksFromTheMirrorWithoutTheDatabaseAndUnlockLeavesNoDuplicates() = runBlocking {
        book("Sample urgent task")
        val count = scheduled().size
        assertEquals(3, count)
        clearAlarmManager()

        setLocked(true)
        t.context.sendBroadcast(Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setClass(t.context, AlarmSystemReceiver::class.java))
        waitFor { scheduled().size == count }
        assertEquals(0, lockedDatabaseUse)

        setLocked(false)
        val passes = t.env.scheduler.passes.value
        t.context.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setClass(t.context, AlarmSystemReceiver::class.java))
        waitFor { t.env.scheduler.passes.value > passes }
        assertEquals(count, scheduled().size)
        assertEquals(count, scheduled().map { it.operation }.toSet().size)
    }

    @Test
    fun aMirrorEntryThePlanNoLongerWantsIsCancelledAfterUnlock() = runBlocking {
        book("Sample urgent task")
        val count = scheduled().size
        clearAlarmManager()
        setLocked(true)
        DirectBootAlarms.rebook(t.context, t.clock.instant())
        assertEquals(count, scheduled().size)
        setLocked(false)
        t.env.pending.replaceAll(emptyList())
        t.tasks.query("SELECT id FROM tasks").forEach { row -> t.tasks.repository.setCompleted(row["id"]!!, true) }
        t.env.scheduler.pass()
        assertEquals(0, scheduled().size)
        assertTrue(DirectBootMirror.of(t.context).read().alarms.isEmpty())
    }

    @Test
    fun aLockedFirePostsTheRingingAlarmAndALockedAnswerIsAppliedAfterUnlock() = runBlocking {
        book("Sample urgent task")
        val ring = DirectBootMirror.of(t.context).read().alarms.minBy { it.fireAtMillis }.toPlanned()
        setLocked(true)
        t.clock.now = ring.fireAt.plusSeconds(1)
        val fire = AndroidAlarmGateway.fireIntent(t.context, ring.id).putExtra(AndroidAlarmGateway.EXTRA_ALARM, AndroidAlarmGateway.encode(ring))
        AlarmFireReceiver().onReceive(t.context, fire)
        val posted = notifications.activeNotifications.single { it.id == ring.id }.notification
        assertTrue(posted.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(Notification.CATEGORY_ALARM, posted.category)
        assertNotNull(posted.fullScreenIntent)
        assertTrue(posted.extras.getString(Notification.EXTRA_TITLE)!!.contains("Sample urgent task"))
        assertEquals(listOf(ring.id), DirectBootMirror.of(t.context).read().delivered)

        val answer = Intent(t.context, AlarmActionReceiver::class.java)
            .setAction(AndroidAlarmGateway.ACTION_BUTTON)
            .putExtra(AndroidAlarmGateway.EXTRA_ID, ring.id)
            .putExtra(AndroidAlarmGateway.EXTRA_ACTION, "acknowledge")
            .putExtra(AndroidAlarmGateway.EXTRA_PAYLOAD, ring.payload)
        AlarmActionReceiver().onReceive(t.context, answer)
        assertTrue(notifications.activeNotifications.none { it.id == ring.id })
        assertEquals(1, DirectBootMirror.of(t.context).read().answers.size)
        assertEquals(0, lockedDatabaseUse)

        setLocked(false)
        DirectBootAlarms.settle(t.env)
        assertTrue(t.tasks.query("SELECT status FROM reminders").any { it["status"] == "acknowledged" })
        assertTrue(ring.id !in t.env.pending.read())
        val after = DirectBootMirror.of(t.context).read()
        assertTrue(after.answers.isEmpty())
        assertTrue(after.delivered.isEmpty())
    }
}
