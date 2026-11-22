package com.ftapps.remft.data.onedrive

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.InvalidationTracker
import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.mirror.MirrorDownloads
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.auto.BackupSchedule
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.backup.restore.ScratchSweep
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

class OneDriveTriggers(
    private val store: SettingsStore,
    private val auth: OneDriveAuth,
    private val turn: AutoBackupTurn,
    private val downloads: MirrorDownloads,
    private val holder: DatabaseHolder,
    private val locks: () -> WorkLocks,
    private val cacheDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observing = false
    private var watched: RemftDatabase? = null
    private var pending: Job? = null
    private var mirrorPending: Job? = null
    private var slotTimer: Job? = null

    private val fileRowsObserver = object : InvalidationTracker.Observer(arrayOf("file_rows")) {
        override fun onInvalidated(tables: Set<String>) {
            mirrorSoon()
        }
    }

    fun onReady() {
        scope.launch {
            runCatching { auth.isConnected() }
            withContext(Dispatchers.IO) {
                runCatching { ScratchSweep.autoBackupScratch(cacheDir, locks(), AndroidBackupLog) }
            }
            watchFileRows()
            check()
            mirrorSoon()
            downloads.startIfPending()
        }
        if (!observing) {
            observing = true
            scope.launch(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            scope.launch {
                                check()
                                mirrorSoon()
                                downloads.startIfPending()
                            }
                        }
                    },
                )
            }
        }
    }

    private fun watchFileRows() {
        val db = runCatching { holder.db }.getOrNull() ?: return
        synchronized(this) {
            if (watched === db) return
            watched = db
        }
        runCatching { db.invalidationTracker.addObserver(fileRowsObserver) }
    }

    fun afterSettingsChanged() {
        scope.launch {
            turn.planWake()
            armSlotTimer()
        }
    }

    private suspend fun check() {
        turn.planWake()
        armSlotTimer()
        synchronized(this) { if (pending?.isActive == true) return }
        if (!runCatching { turn.isDue() }.getOrDefault(false)) return
        synchronized(this) {
            if (pending?.isActive == true) return
            pending = scope.launch {
                delay(DUE_DELAY.toMillis())
                runCatching { turn.run(force = false) }
                armSlotTimer()
            }
        }
    }

    private suspend fun armSlotTimer() {
        synchronized(this) { slotTimer?.cancel() }
        if (store.get(SettingKeys.OneDriveOn.name) != OneDriveAuth.TRUE) return
        val schedule = BackupSchedule.parse(store.get(SettingKeys.AutoBackupSchedule.name))
        val last = UtcText.parse(store.get(SettingKeys.AutoBackupLastOk.name)) ?: return
        val now = Instant.now()
        if (schedule.isDue(now, last)) return
        var wait = Duration.between(now, schedule.nextRunAfter(last)).plusSeconds(5)
        if (wait > MAX_SLOT_WAIT) wait = MAX_SLOT_WAIT
        synchronized(this) {
            slotTimer = scope.launch {
                delay(wait.toMillis())
                check()
            }
        }
    }

    fun mirrorSoon() {
        synchronized(this) {
            mirrorPending?.cancel()
            mirrorPending = scope.launch {
                delay(MIRROR_DELAY.toMillis())
                if (holder.isRestoring) return@launch
                runCatching { turn.catchUpMirror() }
            }
        }
    }

    companion object {
        val DUE_DELAY: Duration = Duration.ofSeconds(20)
        val MIRROR_DELAY: Duration = Duration.ofSeconds(15)
        val MAX_SLOT_WAIT: Duration = Duration.ofHours(6)
    }
}
