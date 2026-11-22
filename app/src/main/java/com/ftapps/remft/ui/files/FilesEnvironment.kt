package com.ftapps.remft.ui.files

import androidx.compose.runtime.staticCompositionLocalOf
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.files.AttachmentStore
import com.ftapps.remft.data.files.AttachmentTooLarge
import com.ftapps.remft.data.files.FilesRepository
import com.ftapps.remft.data.files.MimeTypes
import com.ftapps.remft.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

data class PickedFile(
    val name: String,
    val sizeBytes: Long?,
    val mime: String?,
    val open: () -> InputStream,
    val cleanup: () -> Unit = {},
) {
    val resolvedMime: String get() = mime?.takeIf { it.isNotBlank() && it != MimeTypes.FALLBACK } ?: MimeTypes.forName(name)
}

data class UploadTarget(val workspaceId: String, val targetType: String, val targetId: String, val folderId: String? = null)

enum class UploadPhase { Running, Failed }

data class UploadJob(
    val localId: Long,
    val target: UploadTarget,
    val name: String,
    val phase: UploadPhase = UploadPhase.Running,
    val errorCode: String? = null,
)

class UploadCenter(
    private val attachments: AttachmentStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val state = MutableStateFlow<List<UploadJob>>(emptyList())
    private val sources = mutableMapOf<Long, PickedFile>()
    private val ids = AtomicLong()
    val jobs: StateFlow<List<UploadJob>> = state.asStateFlow()

    suspend fun upload(target: UploadTarget, pick: PickedFile): String? {
        val job = UploadJob(ids.incrementAndGet(), target, pick.name)
        synchronized(sources) { sources[job.localId] = pick }
        state.update { it + job }
        return run(job.localId)
    }

    suspend fun uploadAll(target: UploadTarget, picks: List<PickedFile>): List<String> = picks.mapNotNull { upload(target, it) }

    suspend fun retry(localId: Long): String? {
        state.update { jobs -> jobs.map { if (it.localId == localId) it.copy(phase = UploadPhase.Running, errorCode = null) else it } }
        return run(localId)
    }

    fun dismiss(localId: Long) {
        val pick = synchronized(sources) { sources.remove(localId) }
        state.update { jobs -> jobs.filterNot { it.localId == localId } }
        pick?.let { runCatching(it.cleanup) }
    }

    private suspend fun run(localId: Long): String? {
        val job = state.value.firstOrNull { it.localId == localId } ?: return null
        val pick = synchronized(sources) { sources[localId] } ?: return null
        val outcome = scope.async {
            runCatching {
                attachments.add(
                    workspaceId = job.target.workspaceId,
                    targetType = job.target.targetType,
                    targetId = job.target.targetId,
                    name = pick.name,
                    mime = pick.resolvedMime,
                    declaredSize = pick.sizeBytes,
                    folderId = job.target.folderId,
                    open = pick.open,
                )
            }
        }.await()
        val id = outcome.getOrNull()
        if (id != null) {
            dismiss(localId)
            return id
        }
        val code = if (outcome.exceptionOrNull() is AttachmentTooLarge) "FILE_TOO_LARGE" else "UPLOAD_FAILED"
        state.update { jobs -> jobs.map { if (it.localId == localId) it.copy(phase = UploadPhase.Failed, errorCode = code) else it } }
        return null
    }
}

class FileRequests {
    private val pending = MutableStateFlow<String?>(null)
    val requests: StateFlow<String?> = pending.asStateFlow()

    fun open(fileId: String?) {
        pending.value = fileId
    }

    fun take(fileId: String): Boolean = pending.compareAndSet(fileId, null)
}

class FilesEnvironment(
    val attachments: AttachmentStore,
    val files: FilesRepository,
    val bin: RecycleBin,
    val settings: SettingsStore,
    val authority: String,
    val uploads: UploadCenter = UploadCenter(attachments),
    val requests: FileRequests = FileRequests(),
    val clock: Clock = Clock.systemDefaultZone(),
    val pickOverride: (suspend (AttachSource) -> List<PickedFile>)? = null,
)

val LocalFilesEnvironment = staticCompositionLocalOf<FilesEnvironment?> { null }
