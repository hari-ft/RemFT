package com.ftapps.remft.data.onedrive

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.mirror.FileMirror
import com.ftapps.remft.data.mirror.MirrorCatchUp
import com.ftapps.remft.data.mirror.MirrorDownloadPhase
import com.ftapps.remft.data.mirror.MirrorDownloads
import com.ftapps.remft.data.mirror.MirrorPhase
import com.ftapps.remft.data.mirror.MirrorProgress
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface SyncNowResult {
    data object UpToDate : SyncNowResult
    data class Synced(
        val uploaded: Int = 0,
        val moved: Int = 0,
        val movedToReview: Int = 0,
        val downloaded: Int = 0,
        val failed: Int = 0,
    ) : SyncNowResult
    data class StoppedEarly(val uploaded: Int, val remaining: Int) : SyncNowResult
    data object Off : SyncNowResult
    data object SignedOut : SyncNowResult
    data object RestoreBusy : SyncNowResult
    data object Busy : SyncNowResult
    data class Failed(val code: String) : SyncNowResult

    companion object {
        private val clearCodes = setOf(OneDriveErrors.OTHER_ACCOUNT, OneDriveErrors.FULL, OneDriveErrors.OFFLINE)

        fun fromCode(code: String): SyncNowResult =
            if (code in OneDriveErrors.reconnectCodes) SignedOut else Failed(code)

        fun of(run: MirrorCatchUp, downloaded: Int = 0, downloadError: String? = null): SyncNowResult {
            run.stoppedBy?.let { return fromCode(it) }
            downloadError?.takeIf { it in OneDriveErrors.reconnectCodes || it in clearCodes }?.let { return fromCode(it) }
            clearCodes.firstOrNull { it in run.errors }?.let { return Failed(it) }
            if (run.remaining > 0) return StoppedEarly(run.uploaded, run.remaining)
            val synced = Synced(run.uploaded, run.moved, run.movedToReview, downloaded, run.failed)
            return if (synced == Synced()) UpToDate else synced
        }
    }
}

interface OneDriveSync {
    val available: Flow<Boolean>
    val running: StateFlow<Boolean>
    val progress: StateFlow<MirrorProgress?>
    val results: Flow<SyncNowResult>
    val lastSynced: Flow<Instant?>
    fun start(): Boolean
}

class MirrorSyncNow(
    private val store: SettingsStore,
    private val auth: OneDriveAuth,
    private val turn: AutoBackupTurn,
    private val mirror: FileMirror,
    private val downloads: MirrorDownloads,
    private val scope: CoroutineScope,
    private val continueLater: () -> Unit = {},
    private val log: BackupLog = AndroidBackupLog,
) : OneDriveSync {
    private val runningState = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = runningState.asStateFlow()

    private val resultFlow = MutableSharedFlow<SyncNowResult>(extraBufferCapacity = 8)
    override val results: Flow<SyncNowResult> = resultFlow.asSharedFlow()

    override val available: Flow<Boolean> =
        combine(store.flow(SettingKeys.OneDriveOn.name), auth.connected) { on, connected -> on == OneDriveAuth.TRUE && connected == true }
            .distinctUntilChanged()

    override val lastSynced: Flow<Instant?> = store.flow(SettingKeys.MirrorLastSync.name).map { UtcText.parse(it) }

    override val progress: StateFlow<MirrorProgress?> = combine(mirror.progress, downloads.state) { upload, down ->
        upload ?: if (down.phase == MirrorDownloadPhase.Downloading) MirrorProgress(MirrorPhase.Downloading, down.done, down.total) else null
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun start(): Boolean {
        if (!runningState.compareAndSet(expect = false, update = true)) return false
        scope.launch {
            try {
                resultFlow.emit(run())
            } finally {
                runningState.value = false
            }
        }
        return true
    }

    suspend fun run(): SyncNowResult {
        val result = try {
            when (val outcome = turn.syncMirrorNow()) {
                is MirrorSyncOutcome.Ran -> afterRun(outcome.result)
                MirrorSyncOutcome.Off -> SyncNowResult.Off
                MirrorSyncOutcome.SignedOut -> SyncNowResult.SignedOut
                MirrorSyncOutcome.RestoreBusy -> SyncNowResult.RestoreBusy
                MirrorSyncOutcome.Busy -> SyncNowResult.Busy
                is MirrorSyncOutcome.Failed -> SyncNowResult.fromCode(outcome.code)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("mirror.sync_now_failed", e)
            SyncNowResult.Failed(OneDriveErrors.UNKNOWN)
        }
        record(result)
        return result
    }

    private suspend fun afterRun(run: MirrorCatchUp): SyncNowResult {
        if (run.stoppedBy != null) return SyncNowResult.of(run)
        if (run.remaining > 0) continueLater()
        val before = runCatching { mirror.pendingDownloads().size }.getOrDefault(0)
        if (before == 0 || run.remaining > 0) return SyncNowResult.of(run)
        downloads.start().join()
        val after = runCatching { mirror.pendingDownloads().size }.getOrDefault(before)
        val error = downloads.state.value.error
        return SyncNowResult.of(run, (before - after).coerceAtLeast(0), error)
    }

    private fun record(result: SyncNowResult) {
        val fields = when (result) {
            SyncNowResult.UpToDate -> mapOf("result" to "up_to_date")
            is SyncNowResult.Synced -> mapOf(
                "result" to "synced",
                "uploaded" to result.uploaded,
                "moved" to result.moved,
                "review" to result.movedToReview,
                "downloaded" to result.downloaded,
                "failed" to result.failed,
            )
            is SyncNowResult.StoppedEarly -> mapOf("result" to "stopped", "uploaded" to result.uploaded, "remaining" to result.remaining)
            SyncNowResult.Off -> mapOf("result" to "off")
            SyncNowResult.SignedOut -> mapOf("result" to "signed_out")
            SyncNowResult.RestoreBusy -> mapOf("result" to "restore_busy")
            SyncNowResult.Busy -> mapOf("result" to "busy")
            is SyncNowResult.Failed -> mapOf("result" to "failed", "code" to result.code)
        }
        log.info("mirror.sync_now", fields)
    }
}
