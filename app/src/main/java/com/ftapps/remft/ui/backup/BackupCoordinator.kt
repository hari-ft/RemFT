package com.ftapps.remft.ui.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.ftapps.remft.BuildConfig
import com.ftapps.remft.R
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.app.StartupState
import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.backup.BackupService
import com.ftapps.remft.data.backup.RestoreDatabaseInUse
import com.ftapps.remft.data.backup.RestoreStage
import com.ftapps.remft.data.backup.StartupRestoreRecovery
import com.ftapps.remft.data.onedrive.CloudBackups
import com.ftapps.remft.data.onedrive.RestoreDownloads
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.backup.BackupErrorCode
import com.ftapps.remft.domain.backup.BackupException
import com.ftapps.remft.domain.backup.BuiltBackup
import com.ftapps.remft.domain.backup.ValidatedBackup
import com.ftapps.remft.domain.backup.lock.BackgroundWorkBusy
import com.ftapps.remft.domain.text.fill
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RestoreUiState {
    data object Idle : RestoreUiState
    data object ChoosingSource : RestoreUiState
    data object Checking : RestoreUiState
    data class Confirm(val createdAt: Instant) : RestoreUiState
    data class Running(val stage: RestoreStage, val done: Int = 0, val total: Int = 0) : RestoreUiState
    data class CloudList(val loading: Boolean = true, val failed: Boolean = false, val backups: List<StoredBackup> = emptyList()) : RestoreUiState
    data class CloudDownloading(val received: Long = 0, val total: Long? = null) : RestoreUiState
}

data class BackupUiState(
    val backingUp: Boolean = false,
    val restore: RestoreUiState = RestoreUiState.Idle,
    val oneDriveConnected: Boolean = false,
)

sealed interface BackupEvent {
    data class Message(val text: String) : BackupEvent
    data class Share(val uri: Uri) : BackupEvent
}

@Singleton
class BackupCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val service: BackupService,
    private val startup: AppStartup,
    private val recovery: StartupRestoreRecovery,
    private val cloud: CloudBackups,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<BackupEvent>(Channel.BUFFERED)
    val events: Flow<BackupEvent> = eventChannel.receiveAsFlow()

    @Volatile
    private var built: BuiltBackup? = null

    @Volatile
    private var validated: ValidatedBackup? = null

    init {
        scope.launch {
            startup.state.first { it is StartupState.Ready }
            if (runCatching { recovery.takeRolledBackNotice() }.getOrDefault(false)) {
                message(context.getString(R.string.backup_restoreRolledBack))
            }
        }
    }

    fun backUpNow() {
        if (mutableState.value.backingUp || mutableState.value.restore != RestoreUiState.Idle) return
        mutableState.update { it.copy(backingUp = true) }
        scope.launch {
            message(context.getString(R.string.backup_working))
            try {
                val zip = service.build()
                built = zip
                val uri = FileProvider.getUriForFile(context, BuildConfig.FILES_AUTHORITY, zip.zip)
                eventChannel.send(BackupEvent.Share(uri))
            } catch (e: Exception) {
                AndroidBackupLog.error("backup.export_failed", e)
                built = null
                mutableState.update { it.copy(backingUp = false) }
                message(context.getString(R.string.backup_failed).fill("error" to errorText(e)))
            }
        }
    }

    fun onShareFinished() {
        val zip = built ?: return
        built = null
        scope.launch {
            val shared = BackupShareChoice.awaitChosen()
            if (shared) service.recordShared()
            mutableState.update { it.copy(backingUp = false) }
            message(if (shared) doneMessage(context, zip) else context.getString(R.string.backup_cancelled))
        }
    }

    fun onShareUnavailable() {
        built = null
        mutableState.update { it.copy(backingUp = false) }
        message(context.getString(R.string.backup_cancelled))
    }

    fun requestRestore() {
        if (mutableState.value.backingUp || mutableState.value.restore != RestoreUiState.Idle) return
        mutableState.update { it.copy(restore = RestoreUiState.ChoosingSource) }
        scope.launch {
            val connected = runCatching { cloud.connected() }.getOrDefault(false)
            mutableState.update { it.copy(oneDriveConnected = connected) }
        }
    }

    fun chooseOneDrive() {
        if (mutableState.value.restore != RestoreUiState.ChoosingSource) return
        loadCloud()
    }

    fun retryCloud() {
        if (mutableState.value.restore is RestoreUiState.CloudList) loadCloud()
    }

    fun dismissCloud() {
        if (mutableState.value.restore is RestoreUiState.CloudList) setRestore(RestoreUiState.Idle)
    }

    private fun loadCloud() {
        setRestore(RestoreUiState.CloudList())
        scope.launch {
            val next = try {
                RestoreUiState.CloudList(loading = false, backups = cloud.list())
            } catch (e: Exception) {
                AndroidBackupLog.warn("backup.onedrive_list_failed", e)
                RestoreUiState.CloudList(loading = false, failed = true)
            }
            if (mutableState.value.restore is RestoreUiState.CloudList) setRestore(next)
        }
    }

    fun pickCloud(backup: StoredBackup) {
        if (mutableState.value.restore !is RestoreUiState.CloudList) return
        setRestore(RestoreUiState.CloudDownloading(0, backup.size))
        scope.launch {
            val temp = RestoreDownloads.tempZip(context.cacheDir, Instant.now())
            try {
                val ok = try {
                    cloud.download(backup, temp) { received ->
                        setRestore(RestoreUiState.CloudDownloading(received, backup.size))
                    }
                } catch (e: Exception) {
                    AndroidBackupLog.warn("backup.onedrive_download_failed", e)
                    false
                }
                if (!ok) {
                    setRestore(RestoreUiState.Idle)
                    message(context.getString(R.string.backup_oneDriveDownloadFailed))
                    return@launch
                }
                AndroidBackupLog.info("backup.onedrive_downloaded", mapOf("bytes" to temp.length()))
                validateNow { temp.inputStream() }
            } finally {
                withContext(Dispatchers.IO) { runCatching { FileOps.deleteFileIfExists(temp) } }
            }
        }
    }

    fun dismissSource() {
        if (mutableState.value.restore == RestoreUiState.ChoosingSource) setRestore(RestoreUiState.Idle)
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) {
            setRestore(RestoreUiState.Idle)
            return
        }
        onPicked { context.contentResolver.openInputStream(uri) ?: throw BackupException(BackupErrorCode.NOT_A_BACKUP) }
    }

    fun onPicked(open: () -> InputStream) {
        setRestore(RestoreUiState.Checking)
        scope.launch { validateNow(open) }
    }

    private suspend fun validateNow(open: () -> InputStream) {
        setRestore(RestoreUiState.Checking)
        try {
            val backup = withContext(Dispatchers.IO) { service.validate(open()) }
            validated = backup
            setRestore(RestoreUiState.Confirm(backup.manifest.createdAt))
        } catch (e: Exception) {
            AndroidBackupLog.warn("backup.refused", e)
            setRestore(RestoreUiState.Idle)
            message(errorText(e))
        }
    }

    fun confirmRestore(go: Boolean) {
        val backup = validated ?: return
        validated = null
        if (!go) {
            scope.launch(Dispatchers.IO) { runCatching { backup.discard() } }
            setRestore(RestoreUiState.Idle)
            return
        }
        setRestore(RestoreUiState.Running(RestoreStage.Tasks))
        scope.launch {
            var failure: String? = null
            try {
                service.restore(backup, onFiles = { done, total -> setRestore(RestoreUiState.Running(RestoreStage.Files, done, total)) }) { stage ->
                    setRestore(RestoreUiState.Running(stage))
                }
            } catch (e: Exception) {
                AndroidBackupLog.error("backup.restore_failed", e)
                failure = errorText(e)
            }
            withContext(NonCancellable) {
                startup.restartAfterRestore()
                startup.state.first { it !is StartupState.Starting }
                setRestore(RestoreUiState.Idle)
                if (failure != null) message(context.getString(R.string.backup_restoreFailed).fill("error" to failure))
            }
        }
    }

    private fun setRestore(next: RestoreUiState) {
        mutableState.update { it.copy(restore = next) }
    }

    private fun message(text: String) {
        eventChannel.trySend(BackupEvent.Message(text))
    }

    private fun errorText(error: Throwable): String {
        if (error is BackupException) {
            val id = when (error.code) {
                BackupErrorCode.NOT_A_BACKUP -> R.string.backup_error_not_a_backup
                BackupErrorCode.NEWER_VERSION -> R.string.backup_error_newer_version
                BackupErrorCode.DAMAGED -> R.string.backup_error_damaged
                BackupErrorCode.NOT_A_DATABASE -> R.string.backup_error_not_a_database
                else -> null
            }
            if (id != null) return context.getString(id)
        }
        if (error is BackgroundWorkBusy) return context.getString(R.string.backup_error_busy)
        if (error is RestoreDatabaseInUse) return context.getString(R.string.dfx_restore_db_in_use)
        return context.getString(R.string.error_unknown)
    }
}

fun doneMessage(context: Context, zip: BuiltBackup): String {
    val remote = zip.manifest.listedOnly.size
    return when {
        zip.unavailable.isNotEmpty() -> context.getString(R.string.backup_doneSomeMissing).fill("n" to zip.unavailable.size)
        remote > 0 -> context.getString(R.string.backup_doneNotAllHere).fill("n" to remote)
        else -> context.getString(R.string.backup_done)
    }
}

object BackupShareChoice {
    @Volatile
    var chosen: Boolean = false

    fun reset() {
        chosen = false
    }

    suspend fun awaitChosen(waitMillis: Long = 1_000, stepMillis: Long = 50): Boolean {
        var waited = 0L
        while (!chosen && waited < waitMillis) {
            delay(stepMillis)
            waited += stepMillis
        }
        return chosen
    }
}
