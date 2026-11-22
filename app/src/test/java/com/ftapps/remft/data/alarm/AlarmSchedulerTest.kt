package com.ftapps.remft.data.alarm

import android.app.AlarmManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.tasks.Change
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPatch
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.io.IOException
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AlarmSchedulerTest {
    private val opened = mutableListOf<TestAlarms>()

    private fun open(): TestAlarms = TestAlarms().also { opened += it }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun alarmManager(t: TestAlarms): AlarmManager = t.context.getSystemService(AlarmManager::class.java)

    private fun scheduled(t: TestAlarms): List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(alarmManager(t)).scheduledAlarms

    private fun forceStop(t: TestAlarms) {
        scheduled(t).toList().forEach { alarmManager(t).cancel(it.operation!!) }
    }

    private suspend fun seed(t: TestAlarms): Pair<String, String> {
        val now = t.clock.instant()
        val urgent = t.tasks.repository.create(
            TaskDraft(title = "Call the plumber", remindAt = now.plus(Duration.ofHours(1)), isUrgent = true),
        )
        val normal = t.tasks.repository.create(TaskDraft(title = "Water plants", remindAt = now.plus(Duration.ofHours(2))))
        return urgent to normal
    }

    private suspend fun planned(t: TestAlarms) = t.env.planner.planNotifications(
        alarms = t.env.workspaceFeed(),
        now = t.clock.instant(),
        privacyMode = false,
        alarmSoundName = "remft_alarm",
    )

    @Test
    fun firstPassBooksExactlyThePlannedSet() = runBlocking {
        val t = open()
        seed(t)
        val outcome = t.env.scheduler.pass()
        val desired = planned(t)
        assertTrue(outcome is PassOutcome.Done)
        assertEquals(4, desired.size)
        val alarms = scheduled(t)
        assertEquals(desired.map { it.fireAt.toEpochMilli() }.sorted(), alarms.map { it.triggerAtMs }.sorted())
        assertEquals(3, alarms.count { it.alarmClockInfo != null })
        assertEquals(1, alarms.count { it.alarmClockInfo == null && it.allowWhileIdle })
        assertEquals(desired.map { it.id }.toSet(), t.env.pending.read().keys)
        assertTrue((outcome as PassOutcome.Done).rebook)
    }

    @Test
    fun laterPassesDiffAndChangesRebookOnlyTheDifference() = runBlocking {
        val t = open()
        val (urgent, _) = seed(t)
        t.env.scheduler.pass()
        val quiet = t.env.scheduler.pass() as PassOutcome.Done
        assertEquals(0, quiet.scheduled)
        assertEquals(0, quiet.rebooked)
        assertEquals(0, quiet.cancelled)
        assertTrue(!quiet.rebook)
        t.tasks.repository.update(urgent, TaskPatch(remindAt = Change(t.clock.instant().plus(Duration.ofHours(3)))))
        val moved = t.env.scheduler.pass() as PassOutcome.Done
        assertEquals(3, moved.cancelled)
        assertEquals(3, moved.scheduled)
        assertEquals(planned(t).map { it.fireAt.toEpochMilli() }.sorted(), scheduled(t).map { it.triggerAtMs }.sorted())
    }

    @Test
    fun completingTheTaskCancelsItsChain() = runBlocking {
        val t = open()
        val (urgent, _) = seed(t)
        t.env.scheduler.pass()
        t.tasks.repository.setCompleted(urgent, true)
        val outcome = t.env.scheduler.pass() as PassOutcome.Done
        assertEquals(3, outcome.cancelled)
        assertEquals(1, scheduled(t).size)
    }

    @Test
    fun aNewProcessRebooksEverythingAfterAForceStop() = runBlocking {
        val t = open()
        seed(t)
        t.env.scheduler.pass()
        val booked = t.env.pending.read().keys
        forceStop(t)
        assertEquals(0, scheduled(t).size)
        val restarted = AlarmEnvironment(
            context = t.context,
            database = { t.tasks.holder.db },
            identity = t.tasks.identity,
            settings = t.tasks.settings,
            tasks = t.tasks.repository,
            clock = t.clock,
            filesDir = t.tasks.dir,
            scope = t.tasks.scope,
            health = t.health,
        )
        val outcome = restarted.scheduler.pass() as PassOutcome.Done
        assertEquals(4, outcome.rebooked)
        assertEquals(4, scheduled(t).size)
        assertEquals(booked, restarted.pending.read().keys)
    }

    @Test
    fun pullToRefreshRebooksEverything() = runBlocking {
        val t = open()
        seed(t)
        t.env.scheduler.pass()
        forceStop(t)
        val diff = t.env.scheduler.pass() as PassOutcome.Done
        assertEquals(0, diff.rebooked)
        val refreshed = t.env.scheduler.pass(rebook = true) as PassOutcome.Done
        assertEquals(4, refreshed.rebooked)
        assertEquals(4, scheduled(t).size)
    }

    @Test
    fun theTestAlarmIsNeverCancelled() = runBlocking {
        val t = open()
        assertTrue(t.env.scheduler.bookTest())
        t.env.scheduler.pass()
        t.env.scheduler.pass(rebook = true)
        assertNotNull(t.env.pending.read()[AlarmPlanner.TEST_NOTIFICATION_ID])
        assertEquals(1, scheduled(t).size)
        assertEquals(t.clock.instant().plusSeconds(15).toEpochMilli(), scheduled(t).single().triggerAtMs)
    }

    @Test
    fun unreadableSettingsSkipThePassAndBookNothing() = runBlocking {
        val t = open()
        seed(t)
        val broken = SettingsStore(object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("unreadable") }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("unreadable")
        })
        val env = AlarmEnvironment(
            context = t.context,
            database = { t.tasks.holder.db },
            identity = t.tasks.identity,
            settings = broken,
            tasks = t.tasks.repository,
            clock = t.clock,
            filesDir = java.io.File(t.tasks.dir, "other"),
            scope = t.tasks.scope,
            health = t.health,
        )
        val outcome = env.scheduler.pass(rebook = true)
        assertEquals(PassOutcome.Skipped("prefs_unreadable"), outcome)
        assertEquals(0, scheduled(t).size)
        assertTrue(env.pending.read().isEmpty())
    }

    @Test
    fun privacyModeBooksPrivateTexts() = runBlocking {
        val t = open()
        seed(t)
        t.tasks.settings.set("notification_privacy", "true")
        t.env.scheduler.pass()
        val booked = t.env.pending.read().values
        assertTrue(booked.all { it.title == "RemFT" && it.body == "You have a reminder" })
    }

    @Test
    fun missedFireIsLoggedAsDroppedAndForgotten() = runBlocking {
        val t = open()
        seed(t)
        t.env.scheduler.pass()
        forceStop(t)
        t.clock.advanceMinutes(65)
        val outcome = t.env.scheduler.pass() as PassOutcome.Done
        assertEquals(1, outcome.dropped)
        assertTrue(t.env.log.recent().any { it.event == AlarmLogEvent.DROPPED })
        assertNull(t.env.pending.read().values.firstOrNull { it.fireAt.isBefore(t.clock.instant().minusSeconds(120)) })
    }

    @Test
    fun everyBookingIsInTheAlarmLog() = runBlocking {
        val t = open()
        seed(t)
        t.env.scheduler.pass()
        assertEquals(4, t.env.log.recent().count { it.event == AlarmLogEvent.SCHEDULED })
    }
}
