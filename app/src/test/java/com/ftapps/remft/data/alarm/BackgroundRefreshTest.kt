package com.ftapps.remft.data.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundRefreshTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val opened = mutableListOf<TestAlarms>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
    }

    private fun alarms(): TestAlarms = TestAlarms().also { opened += it }

    private fun locks(): WorkLocks = WorkLocks(folder.newFolder("locks"), 4242L, log = BackupLog.None)

    private class RecordingTurn(override val name: String, override val beforeAlarms: Boolean, val order: MutableList<String>) : BackgroundTurn {
        override suspend fun run() {
            order += name
        }
    }

    @Test
    fun earlyTurnsRunBeforeLateTurnsAndTheLockIsReleased() = runBlocking {
        val order = mutableListOf<String>()
        val locks = locks()
        val turns = setOf(RecordingTurn("late", false, order), RecordingTurn("early", true, order))
        BackgroundRefresh(alarms().env, turns, locks).run()
        assertEquals(listOf("early", "late"), order)
        assertFalse(locks.isHeld(WorkLockNames.HEADLESS_TURN))
    }

    @Test
    fun aRunningRestoreSkipsThePass() = runBlocking {
        val order = mutableListOf<String>()
        val locks = locks()
        val restore = locks.tryAcquire(WorkLockNames.RESTORE)
        val outcome = BackgroundRefresh(alarms().env, setOf(RecordingTurn("turn", true, order)), locks).run()
        restore?.release()
        assertTrue(outcome is PassOutcome.Skipped)
        assertTrue(order.isEmpty())
    }

    @Test
    fun anotherBackgroundTurnInProgressSkipsThePass() = runBlocking {
        val order = mutableListOf<String>()
        val locks = locks()
        val other = locks.tryAcquire(WorkLockNames.HEADLESS_TURN)
        val outcome = BackgroundRefresh(alarms().env, setOf(RecordingTurn("turn", false, order)), locks).run()
        other?.release()
        assertTrue(outcome is PassOutcome.Skipped)
        assertTrue(order.isEmpty())
    }
}
