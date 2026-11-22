package com.ftapps.remft.data.onedrive

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.mirror.FakeRemote
import com.ftapps.remft.data.mirror.FileMirror
import com.ftapps.remft.data.mirror.SupportMirrorSql
import com.ftapps.remft.domain.backup.BackupFormat
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupManifest
import com.ftapps.remft.domain.backup.BuiltBackup
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class AutoBackupTurnTest {
    private lateinit var rig: OneDriveRig
    private lateinit var locks: WorkLocks
    private val destination = FakeDestination()

    @Before
    fun setUp() {
        rig = OneDriveRig()
        locks = WorkLocks(File(rig.root, "locks"), 4242L)
        runBlocking { rig.store.set(SettingKeys.OneDriveOn.name, "true") }
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private fun turn(lockWait: Duration = Duration.ofSeconds(10)) = AutoBackupTurn(
        store = rig.store,
        auth = rig.auth(),
        mirror = FileMirror(SupportMirrorSql { throw IllegalStateException("no database here") }, FakeRemote(), File(rig.root, "files"), rig.store),
        runner = {
            AutoBackupRunner(
                store = rig.store,
                destination = destination,
                builder = AutoBackupBuilder { out, now, _ ->
                    out.parentFile?.mkdirs()
                    out.writeBytes(ByteArray(16))
                    BuiltBackup(out, BackupManifest(BackupFormat.CURRENT, "1.1.1", 3, now, "00", 0, 0))
                },
                scratch = File(rig.root, "auto-backup"),
                clock = { rig.now },
                zone = { ZoneOffset.UTC },
                log = BackupLog.None,
            )
        },
        wake = BackupWake(rig.store, WakeBooker { }, log = BackupLog.None),
        locks = { locks },
        restoring = { false },
        clock = { rig.now },
        log = BackupLog.None,
        lockWait = lockWait,
    )

    @Test
    fun aShortStartupSweepDoesNotTurnTheWakeAway() = runBlocking {
        val sweep = locks.tryAcquire(WorkLockNames.ONEDRIVE_WORK)!!
        val releaser = Thread {
            Thread.sleep(400)
            sweep.release()
        }
        releaser.start()
        val outcome = turn().run(force = true)
        releaser.join()
        assertEquals(AutoBackupOutcome.Done, outcome)
        assertEquals(1, destination.uploads.size)
    }

    @Test
    fun aRestoreInProgressTurnsTheRunAwayWithoutWaiting() = runBlocking {
        val restore = locks.tryAcquire(WorkLockNames.RESTORE)!!
        try {
            val started = System.nanoTime()
            assertEquals(AutoBackupOutcome.Busy, turn().run(force = true))
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
            assertTrue(destination.uploads.isEmpty())
        } finally {
            restore.release()
        }
    }

    @Test
    fun aRestoreThatTakesTheLockWhileTheRunWaitsStopsIt() = runBlocking {
        val sweep = locks.tryAcquire(WorkLockNames.ONEDRIVE_WORK)!!
        val restore = Thread {
            Thread.sleep(300)
            locks.tryAcquire(WorkLockNames.RESTORE)
            sweep.release()
        }
        restore.start()
        assertEquals(AutoBackupOutcome.Busy, turn().run(force = true))
        restore.join()
        assertTrue(destination.uploads.isEmpty())
    }
}
