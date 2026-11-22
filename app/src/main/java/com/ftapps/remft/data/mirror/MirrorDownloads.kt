package com.ftapps.remft.data.mirror

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.backup.RestoreSettings
import com.ftapps.remft.data.onedrive.OneDriveAuth
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration

enum class MirrorDownloadPhase { Idle, Downloading, NeedsOneDrive, Waiting }

data class MirrorDownloadState(
    val phase: MirrorDownloadPhase = MirrorDownloadPhase.Idle,
    val total: Int = 0,
    val done: Int = 0,
    val error: String? = null,
) {
    val remaining: Int get() = total - done
}

class MirrorDownloads(
    private val mirror: FileMirror,
    private val auth: OneDriveAuth,
    private val store: SettingsStore,
    private val locks: () -> WorkLocks?,
    private val scope: CoroutineScope,
    private val startDelay: Duration = START_DELAY,
    private val backoff: List<Duration> = BACKOFF,
    private val log: BackupLog = AndroidBackupLog,
) {
    private val mutableState = MutableStateFlow(MirrorDownloadState())
    val state: StateFlow<MirrorDownloadState> = mutableState.asStateFlow()

    private val landedFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val landed: SharedFlow<String> = landedFlow.asSharedFlow()

    private var retry: Job? = null
    private var running: Job? = null
    private var failures = 0

    fun startIfPending() {
        scope.launch {
            if (store.get(SettingKeys.MirrorDownloadsPending.name) != OneDriveAuth.TRUE) return@launch
            schedule(startDelay)
        }
    }

    @Synchronized
    private fun schedule(wait: Duration) {
        if (running?.isActive == true) return
        retry?.cancel()
        retry = scope.launch {
            delay(wait.toMillis())
            start()
        }
    }

    @Synchronized
    fun start(): Job {
        running?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch { lockedRound() }
        running = job
        return job
    }

    private suspend fun lockedRound() {
        val set = runCatching { locks() }.getOrNull()
        if (set == null) {
            round()
            return
        }
        if (withContext(Dispatchers.IO) { set.isHeld(WorkLockNames.RESTORE) }) {
            scheduleAfterRound(backoff.first())
            return
        }
        val hold = withContext(Dispatchers.IO) { set.tryAcquire(WorkLockNames.ONEDRIVE_WORK) }
        if (hold == null) {
            scheduleAfterRound(backoff.first())
            return
        }
        try {
            round()
        } finally {
            withContext(Dispatchers.IO) { hold.release() }
        }
    }

    private suspend fun settingsAfterFiles() {
        try {
            RestoreSettings.applyPending(store)
        } catch (e: Exception) {
            log.warn("backup.settings_apply_failed", e)
        }
    }

    private suspend fun round() {
        val pending = try {
            mirror.pendingDownloads()
        } catch (e: Exception) {
            log.warn("mirror.download_list_failed", e)
            return
        }
        if (pending.isEmpty()) {
            failures = 0
            store.remove(SettingKeys.MirrorDownloadsPending.name)
            settingsAfterFiles()
            mutableState.value = MirrorDownloadState()
            return
        }
        if (!auth.canWork()) {
            mutableState.value = MirrorDownloadState(MirrorDownloadPhase.NeedsOneDrive, pending.size)
            return
        }
        var done = 0
        var failed = 0
        var notFound = 0
        var error: String? = null
        mutableState.value = MirrorDownloadState(MirrorDownloadPhase.Downloading, pending.size)
        for (d in pending) {
            try {
                mirror.download(d)
                done++
                landedFlow.tryEmit(d.storedName)
                mutableState.value = MirrorDownloadState(MirrorDownloadPhase.Downloading, pending.size, done)
            } catch (e: OneDriveException) {
                failed++
                error = e.code
                if (e.code == OneDriveErrors.MIRROR_MISSING) notFound++
                if (e.code in STOP_CODES) break
            } catch (e: Exception) {
                failed++
                error = OneDriveErrors.UNKNOWN
                log.warn("mirror.download_failed", e)
            }
        }
        log.info("mirror.downloads", mapOf("done" to done, "failed" to failed, "notFound" to notFound))
        if (done == 0 && notFound >= 2 && notFound == failed) error = OneDriveErrors.OTHER_ACCOUNT
        if (failed == 0 && done == pending.size) {
            failures = 0
            store.remove(SettingKeys.MirrorDownloadsPending.name)
            settingsAfterFiles()
            mutableState.value = MirrorDownloadState()
            return
        }
        val needsSignIn = error in OneDriveErrors.reconnectCodes
        mutableState.value = MirrorDownloadState(
            if (needsSignIn) MirrorDownloadPhase.NeedsOneDrive else MirrorDownloadPhase.Waiting,
            pending.size,
            done,
            error,
        )
        if (needsSignIn) return
        val wait = backoff[failures.coerceIn(0, backoff.size - 1)]
        failures++
        scheduleAfterRound(wait)
    }

    @Synchronized
    private fun scheduleAfterRound(wait: Duration) {
        retry?.cancel()
        retry = scope.launch {
            delay(wait.toMillis())
            start()
        }
    }

    suspend fun fetchNow(fileId: String): File? {
        val d = mirror.pendingFor(fileId) ?: return null
        if (!auth.canWork()) throw OneDriveException(OneDriveErrors.MIRROR_NEEDS_ONEDRIVE)
        val file = mirror.download(d)
        landedFlow.tryEmit(d.storedName)
        return file
    }

    suspend fun isMissingButFetchable(fileId: String): Boolean = mirror.pendingFor(fileId) != null

    companion object {
        val START_DELAY: Duration = Duration.ofSeconds(3)
        val BACKOFF: List<Duration> = listOf(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
        )
        val STOP_CODES: Set<String> = setOf(
            OneDriveErrors.OFFLINE,
            OneDriveErrors.RECONNECT,
            OneDriveErrors.SIGNED_OUT,
            OneDriveErrors.ERROR,
            OneDriveErrors.DENIED,
        )
    }
}
