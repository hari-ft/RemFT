package com.ftapps.remft.data.tasks

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestTasks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TimeTicksTest {
    private val tasks = TestTasks()
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @After
    fun tearDown() {
        tasks.close()
    }

    private class MovableClock(private val scheduler: TestCoroutineScheduler, var base: Instant, var zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MovableClock(scheduler, base, zone)

        override fun instant(): Instant = base.plusMillis(scheduler.currentTime)
    }

    private fun repository(clock: Clock, changes: MutableSharedFlow<Unit>) =
        TasksRepository({ tasks.holder.db }, tasks.identity, tasks.settings, clock, timeChanges = changes)

    private fun local(text: String, zone: ZoneId = kolkata): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun minuteTicksLineUpWithTheWallClockMinute() = runTest {
        val clock = MovableClock(testScheduler, local("2026-11-20T10:00:30"), kolkata)
        val seen = mutableListOf<Instant>()
        val job = launch { repository(clock, MutableSharedFlow()).minuteTicks().collect { seen += it } }
        runCurrent()
        assertEquals(listOf(local("2026-11-20T10:00:30")), seen)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(local("2026-11-20T10:01:00"), seen.last())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(local("2026-11-20T10:02:00"), seen.last())
        job.cancel()
    }

    @Test
    fun settingTheClockForwardMovesTheDayAtOnce() = runTest {
        val clock = MovableClock(testScheduler, local("2026-11-20T22:00:00"), kolkata)
        val changes = MutableSharedFlow<Unit>()
        val seen = mutableListOf<Instant>()
        val ticks = mutableListOf<Instant>()
        val repo = repository(clock, changes)
        val job = launch { repo.dayStarts().collect { seen += it } }
        val tickJob = launch { repo.minuteTicks().collect { ticks += it } }
        runCurrent()
        assertEquals(listOf(local("2026-11-20T00:00:00")), seen)
        clock.base = local("2026-11-21T09:15:20")
        changes.emit(Unit)
        runCurrent()
        assertEquals(listOf(local("2026-11-20T00:00:00"), local("2026-11-21T00:00:00")), seen)
        assertEquals(local("2026-11-21T09:15:20"), ticks.last())
        job.cancel()
        tickJob.cancel()
    }

    @Test
    fun aTimeZoneChangeMovesTheDayStartToTheNewZone() = runTest {
        val clock = MovableClock(testScheduler, local("2026-11-20T02:00:00"), kolkata)
        val changes = MutableSharedFlow<Unit>()
        val seen = mutableListOf<Instant>()
        val job = launch { repository(clock, changes).dayStarts().collect { seen += it } }
        runCurrent()
        assertEquals(listOf(local("2026-11-20T00:00:00")), seen)
        clock.zoneId = ZoneId.of("UTC")
        changes.emit(Unit)
        runCurrent()
        assertEquals(local("2026-11-19T00:00:00", ZoneId.of("UTC")), seen.last())
        job.cancel()
    }

    @Test
    fun systemClockAndZoneBroadcastsReachTheFlow() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var count = 0
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { SystemTimeChanges.flow(context).collect { count++ } }
        SystemTimeChanges.actions.forEach { context.sendBroadcast(Intent(it)) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, count)
        job.cancel()
    }
}
