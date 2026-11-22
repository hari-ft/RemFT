package com.ftapps.remft.domain.backup.lock

import com.ftapps.remft.domain.backup.restore.ScratchSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

class WorkLocksTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dir: File get() = File(temp.root, "locks")

    private fun locks(pid: Long = 42, clock: () -> Instant = Instant::now, heartbeat: Duration = Duration.ofSeconds(20)) =
        WorkLocks(dir, pid, clock, heartbeatEvery = heartbeat)

    @Test
    fun aLockHeldInOneIsolateIsRefusedInTheOther() {
        val app = locks()
        val background = locks()
        val hold = app.tryAcquire("x")
        assertNotNull(hold)
        assertNull(background.tryAcquire("x"))
        assertTrue(background.isHeld("x"))
        hold!!.release()
        assertFalse(background.isHeld("x"))
        val again = background.tryAcquire("x")
        assertNotNull(again)
        again!!.release()
    }

    @Test
    fun namesAreIndependent() {
        val a = locks().tryAcquire(WorkLockNames.ONEDRIVE_WORK)!!
        val b = locks().tryAcquire(WorkLockNames.RESTORE)
        assertNotNull(b)
        a.release()
        b!!.release()
    }

    @Test
    fun theLockFileHoldsTokenPidAndBeat() {
        val hold = locks().tryAcquire("x")!!
        val owner = LockOwner.parse(File(dir, "x.lock").readText())!!
        assertEquals(42L, owner.pid)
        assertTrue(owner.token.startsWith("42_"))
        val raw = File(dir, "x.lock").readText()
        assertTrue(raw.startsWith("{\"token\":\"42_"))
        assertTrue(Regex(",\"pid\":42,\"beat\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3,6}Z\"}$").containsMatchIn(raw))
        hold.release()
        assertFalse(File(dir, "x.lock").exists())
    }

    @Test
    fun aLockLeftByADeadProcessIsTakenOver() {
        locks(pid = 1).tryAcquire("x")!!
        val hold = locks(pid = 2).tryAcquire("x")
        assertNotNull(hold)
        hold!!.release()
    }

    @Test
    fun aLockWhoseHeartbeatStoppedIsTakenOver() {
        var now = Instant.parse("2026-11-05T10:00:00Z")
        val clock = { now }
        locks(clock = clock).tryAcquire("x")!!
        now = now.plus(Duration.ofMinutes(1))
        assertNull(locks(clock = clock).tryAcquire("x"))
        now = now.plus(Duration.ofMinutes(2))
        val hold = locks(clock = clock).tryAcquire("x")
        assertNotNull(hold)
        hold!!.release()
    }

    @Test
    fun aRunningHolderKeepsItsLockAliveWithHeartbeats() {
        val hold = locks(heartbeat = Duration.ofMillis(30)).tryAcquire("x")!!
        val first = LockOwner.parse(File(dir, "x.lock").readText())!!.beat
        var later = first
        val deadline = System.currentTimeMillis() + 5000
        while (later == first && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            later = runCatching { LockOwner.parse(File(dir, "x.lock").readText())?.beat }.getOrNull() ?: first
        }
        assertNotEquals(first, later)
        hold.release()
    }

    @Test
    fun anUnreadableLockFileAgesOutByItsFileTime() {
        dir.mkdirs()
        val file = File(dir, "x.lock").apply { writeText("{torn") }
        assertNull(locks().tryAcquire("x"))
        file.setLastModified(System.currentTimeMillis() - Duration.ofMinutes(3).toMillis())
        val hold = locks().tryAcquire("x")
        assertNotNull(hold)
        hold!!.release()
    }

    @Test
    fun releaseOnlyDeletesTheOwnersFile() {
        var now = Instant.parse("2026-11-05T10:00:00Z")
        val clock = { now }
        val stale = locks(clock = clock).tryAcquire("x")!!
        now = now.plus(Duration.ofMinutes(5))
        val fresh = locks(clock = clock).tryAcquire("x")!!
        stale.release()
        assertTrue(File(dir, "x.lock").exists())
        fresh.release()
        assertFalse(File(dir, "x.lock").exists())
    }

    @Test
    fun theStalenessRule() {
        val now = Instant.parse("2026-11-05T10:00:00Z")
        val owner = LockOwner("t", 7, now.minus(Duration.ofMinutes(1)))
        assertFalse(LockOwner.isStale(owner, null, now, 7))
        assertTrue(LockOwner.isStale(owner, null, now, 8))
        assertTrue(LockOwner.isStale(owner.copy(beat = now.minus(Duration.ofSeconds(121))), null, now, 7))
        assertFalse(LockOwner.isStale(owner.copy(beat = now.minus(Duration.ofSeconds(120))), null, now, 7))
        assertTrue(LockOwner.isStale(null, null, now, 7))
        assertFalse(LockOwner.isStale(null, now.minusSeconds(30), now, 7))
        assertTrue(LockOwner.isStale(null, now.minusSeconds(180), now, 7))
        assertNull(LockOwner.parse("{\"token\":\"t\",\"pid\":\"7\",\"beat\":\"2026-11-05T10:00:00Z\"}"))
        assertEquals(owner, LockOwner.parse(owner.encode()))
    }

    @Test
    fun withLockRunsAndReleases() {
        val l = locks()
        assertEquals(5, l.withLock("x") { 5 })
        assertFalse(l.isHeld("x"))
    }

    @Test
    fun withLockDoesNotRunWhileTheOtherIsolateHoldsIt() {
        val hold = locks().tryAcquire("x")!!
        var ran = false
        assertNull(locks().withLock("x") { ran = true })
        assertFalse(ran)
        hold.release()
    }

    @Test
    fun withLockReleasesWhenTheWorkThrows() {
        val l = locks()
        try {
            l.withLock("x") { throw IllegalStateException("boom") }
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }
        assertFalse(l.isHeld("x"))
    }

    @Test
    fun withLockWaitsForAHolderThatLetsGo() {
        val hold = locks().tryAcquire("x")!!
        val releaser = Thread {
            Thread.sleep(150)
            hold.release()
        }
        releaser.start()
        assertEquals("ran", locks().withLock("x", Duration.ofSeconds(5)) { "ran" })
        releaser.join()
    }

    @Test
    fun theRestoreLockWaitsForABackgroundTurnThenHolds() {
        val turn = locks().tryAcquire(WorkLockNames.HEADLESS_TURN)!!
        val releaser = Thread {
            Thread.sleep(150)
            turn.release()
        }
        releaser.start()
        val l = locks()
        val hold = l.beginRestore(Duration.ofSeconds(5))
        assertTrue(l.isHeld(WorkLockNames.RESTORE))
        hold.release()
        releaser.join()
    }

    @Test
    fun theRestoreWaitsForOneDriveWorkAndKeepsItUntilTheRestoreEnds() {
        val work = locks().tryAcquire(WorkLockNames.ONEDRIVE_WORK)!!
        val releaser = Thread {
            Thread.sleep(150)
            work.release()
        }
        releaser.start()
        val l = locks()
        val hold = l.beginRestore(Duration.ofSeconds(5))
        releaser.join()
        assertTrue(l.isHeld(WorkLockNames.RESTORE))
        assertTrue(l.isHeld(WorkLockNames.ONEDRIVE_WORK))
        assertNull(locks().tryAcquire(WorkLockNames.ONEDRIVE_WORK))
        hold.release()
        assertFalse(l.isHeld(WorkLockNames.RESTORE))
        assertFalse(l.isHeld(WorkLockNames.ONEDRIVE_WORK))
    }

    @Test
    fun theRestoreGivesUpHoldingNothingWhenOneDriveWorkDoesNotLetGo() {
        val work = locks().tryAcquire(WorkLockNames.ONEDRIVE_WORK)!!
        val l = locks()
        try {
            l.beginRestore(Duration.ofMillis(200))
            fail("expected busy")
        } catch (_: BackgroundWorkBusy) {
        }
        assertFalse(l.isHeld(WorkLockNames.RESTORE))
        assertTrue(l.isHeld(WorkLockNames.ONEDRIVE_WORK))
        work.release()
    }

    @Test
    fun theRestoreLockGivesUpHoldingNothingWhenTheTurnDoesNotLetGo() {
        val turn = locks().tryAcquire(WorkLockNames.HEADLESS_TURN)!!
        val l = locks()
        try {
            l.beginRestore(Duration.ofMillis(200))
            fail("expected busy")
        } catch (_: BackgroundWorkBusy) {
        }
        assertFalse(l.isHeld(WorkLockNames.RESTORE))
        turn.release()
    }

    @Test
    fun theStartSweepsWhatEarlierBackupsLeftInTheTempFolder() {
        val tmp = File(temp.root, "os-temp")
        val stale = File(tmp, "backup-scratch/restore-1/remft.sqlite").apply { parentFile.mkdirs(); writeText("x") }
        val zip = File(tmp, "remft-backup-2026-11-01.zip").apply { writeText("zip") }
        val other = File(tmp, "auto-backup/keep.zip").apply { parentFile.mkdirs(); writeText("k") }
        assertEquals(2, ScratchSweep.backupScratch(tmp))
        assertFalse(stale.parentFile.exists())
        assertFalse(zip.exists())
        assertTrue(other.exists())
    }

    @Test
    fun theAutomaticBackupFolderIsSweptOnlyWhileNoBackupHoldsTheLock() {
        val tmp = File(temp.root, "os-temp-auto")
        val auto = File(tmp, "auto-backup")
        val stale = File(auto, "remft-auto-2026-11-01-020000Z.zip").apply { parentFile.mkdirs(); writeText("z") }
        val scratch = File(auto, "scratch/db.sqlite").apply { parentFile.mkdirs(); writeText("d") }
        val other = File(auto, "keep.txt").apply { writeText("k") }
        val l = locks()
        val hold = l.tryAcquire(WorkLockNames.ONEDRIVE_WORK)!!
        assertEquals(0, ScratchSweep.autoBackupScratch(tmp, locks()))
        assertTrue(stale.exists())
        assertTrue(scratch.exists())
        hold.release()
        assertEquals(2, ScratchSweep.autoBackupScratch(tmp, locks()))
        assertFalse(stale.exists())
        assertFalse(scratch.parentFile.exists())
        assertTrue(other.exists())
        assertFalse(l.isHeld(WorkLockNames.ONEDRIVE_WORK))
    }
}
