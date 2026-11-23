package com.ftapps.remft.data.onedrive

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.db.DatabaseRestoring
import com.ftapps.remft.data.mirror.FileMirror
import com.ftapps.remft.data.mirror.MirrorCatchUp
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.lock.WorkLockHold
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.backup.restore.ScratchSweep
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

class AutoBackupTurn(
    private val store: SettingsStore,
    private val auth: OneDriveAuth,
    private val mirror: FileMirror,
    private val runner: (deadline: Instant?) -> AutoBackupRunner,
    private val wake: BackupWake,
    private val locks: () -> WorkLocks,
    private val restoring: () -> Boolean,
    private val clock: () -> Instant = Instant::now,
    private val log: BackupLog = AndroidBackupLog,
    private val lockWait: Duration = LOCK_WAIT,
) {
    private val running = Mutex()
    private val finished = MutableSharedFlow<AutoBackupOutcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<AutoBackupOutcome> = finished.asSharedFlow()

    suspend fun mirrorUploadsOn(): Boolean =
        store.get(SettingKeys.OneDriveOn.name) == OneDriveAuth.TRUE && auth.canWork()

    suspend fun isDue(): Boolean = !restoring() && runner(null).let { it.isReady() && it.isDue() }

    suspend fun run(force: Boolean = false, budget: Duration = BUDGET): AutoBackupOutcome {
        if (restoring()) return AutoBackupOutcome.Busy
        if (!running.tryLock()) return AutoBackupOutcome.Busy
        try {
            val outcome = exclusive(lockWait) {
                val deadline = clock().plus(budget)
                if (!force && mirrorUploadsOn()) catchUpQuietly(deadline)
                runner(deadline).run(force)
            } ?: AutoBackupOutcome.Busy
            if (outcome != AutoBackupOutcome.NotDue && outcome != AutoBackupOutcome.NothingOn) {
                log.info("autobackup.run_finished", mapOf("outcome" to outcome, "force" to force))
            }
            finished.tryEmit(outcome)
            return outcome
        } catch (_: DatabaseRestoring) {
            log.info("autobackup.run_finished", mapOf("outcome" to AutoBackupOutcome.Busy, "force" to force))
            return AutoBackupOutcome.Busy
        } finally {
            running.unlock()
            planWake()
        }
    }

    suspend fun catchUpMirror(deadline: Instant? = null): MirrorCatchUp? {
        if (restoring() || !mirrorUploadsOn()) return null
        return try {
            exclusive(Duration.ZERO) { mirror.catchUp(deadline) }
        } catch (e: Exception) {
            log.warn("mirror.catch_up_failed", e)
            null
        }
    }

    suspend fun planWake() {
        wake.plan(book = true)
    }

    private suspend fun catchUpQuietly(deadline: Instant) {
        try {
            mirror.catchUp(deadline)
        } catch (e: Exception) {
            log.warn("mirror.catch_up_failed", e)
        }
    }

    private suspend fun <T> exclusive(wait: Duration, block: suspend () -> T): T? {
        val set = locks()
        val until = System.nanoTime() + wait.toNanos()
        var acquired: WorkLockHold? = null
        while (acquired == null) {
            if (withContext(Dispatchers.IO) { set.isHeld(WorkLockNames.RESTORE) }) return null
            acquired = withContext(Dispatchers.IO) { set.tryAcquire(WorkLockNames.ONEDRIVE_WORK) }
            if (acquired == null) {
                if (System.nanoTime() - until >= 0) return null
                delay(LOCK_POLL.toMillis())
            }
        }
        val hold: WorkLockHold = acquired
        if (withContext(Dispatchers.IO) { set.isHeld(WorkLockNames.RESTORE) }) {
            withContext(Dispatchers.IO) { hold.release() }
            return null
        }
        try {
            return block()
        } finally {
            withContext(Dispatchers.IO) { hold.release() }
        }
    }

    companion object {
        val BUDGET: Duration = Duration.ofMinutes(4)
        val LOCK_WAIT: Duration = Duration.ofSeconds(30)
        private val LOCK_POLL: Duration = Duration.ofMillis(250)

        fun scratch(cacheDir: File): File = File(cacheDir, ScratchSweep.AUTO_BACKUP_SCRATCH_FOLDER)
    }
}
