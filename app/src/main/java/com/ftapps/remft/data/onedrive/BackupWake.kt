package com.ftapps.remft.data.onedrive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.app.StartupState
import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.auto.BackupSchedule
import com.ftapps.remft.domain.settings.SettingKeys
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

fun interface WakeBooker {
    fun book(delay: Duration?)
}

class WorkManagerWakeBooker(private val context: Context) : WakeBooker {
    override fun book(delay: Duration?) {
        val work = WorkManager.getInstance(context)
        if (delay == null) {
            work.cancelUniqueWork(BackupWake.WORK_NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<BackupWakeWorker>()
            .setInitialDelay(delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(BackupWake.WORK_NAME)
            .build()
        work.enqueueUniqueWork(BackupWake.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

class BackupWake(
    private val store: SettingsStore,
    private val booker: WakeBooker,
    private val restoring: () -> Boolean = { false },
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: BackupLog = AndroidBackupLog,
) {
    suspend fun upcoming(): List<Instant> {
        val on = !restoring() && store.get(SettingKeys.OneDriveOn.name) == OneDriveAuth.TRUE
        if (!on) return emptyList()
        val schedule = BackupSchedule.parse(store.get(SettingKeys.AutoBackupSchedule.name))
        val last = UtcText.parse(store.get(SettingKeys.AutoBackupLastOk.name))
        return schedule.upcoming(clock(), last, SLOTS, zone())
    }

    suspend fun plan(book: Boolean = true) {
        try {
            val slots = upcoming()
            store.set(SettingKeys.AutoBackupWakeSlots.name, slots.joinToString(",") { it.toEpochMilli().toString() })
            if (!book) return
            val first = slots.firstOrNull()
            booker.book(first?.let { Duration.between(clock(), it).coerceAtLeast(Duration.ZERO) })
        } catch (e: Exception) {
            log.warn("autobackup.wake_failed", e)
        }
    }

    companion object {
        const val WORK_NAME = "remft-backup-wake"
        const val SLOTS = 8
    }
}

@HiltWorker
class BackupWakeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val startup: AppStartup,
    private val turn: AutoBackupTurn,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        startup.start()
        val state = withTimeoutOrNull(STARTUP_WAIT.toMillis()) { startup.state.first { it !is StartupState.Starting } }
        if (state is StartupState.Ready) {
            try {
                turn.run(force = false)
            } catch (e: Exception) {
                AndroidBackupLog.warn("autobackup.wake_run_failed", e)
                turn.planWake()
            }
        }
        return Result.success()
    }

    companion object {
        private val STARTUP_WAIT: Duration = Duration.ofSeconds(60)
    }
}
