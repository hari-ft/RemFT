package com.ftapps.remft.ui.files

import androidx.compose.runtime.staticCompositionLocalOf
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.files.AttachmentStore
import com.ftapps.remft.data.files.AttachmentTooLarge
import com.ftapps.remft.data.files.FilesRepository
import com.ftapps.remft.data.files.MimeTypes
import com.ftapps.remft.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val wantsLink: Boolean = false,
)

data class UploadDone(val localId: Long, val target: UploadTarget, val fileId: String, val name: String, val mime: String)

private data class LinkBook(val done: Map<Long, UploadDone> = emptyMap(), val waiting: Set<Long> = emptySet()) {
    val unclaimed: List<UploadDone> get() = done.values.filter { it.localId !in waiting }.sortedBy { it.localId }
}

class UploadCenter(
    private val attachments: AttachmentStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val state = MutableStateFlow<List<UploadJob>>(emptyList())
    private val sources = mutableMapOf<Long, PickedFile>()
    private val running = mutableMapOf<Long, Deferred<String?>>()
    private val links = MutableStateFlow(LinkBook())
    private val ids = AtomicLong()
    val jobs: StateFlow<List<UploadJob>> = state.asStateFlow()

    suspend fun upload(target: UploadTarget, pick: PickedFile, wantsLink: Boolean = false): String? =
        await(startAll(target, listOf(pick), wantsLink).single(), claimLink = wantsLink)

    suspend fun uploadAll(target: UploadTarget, picks: List<PickedFile>): List<String> =
        startAll(target, picks, wantsLink = false).mapNotNull { await(it) }

    suspend fun retry(localId: Long): String? {
        if (state.value.none { it.localId == localId }) return null
        state.update { jobs -> jobs.map { if (it.localId == localId) it.copy(phase = UploadPhase.Running, errorCode = null) else it } }
        launch(listOf(localId))
        return await(localId)
    }

    fun dismiss(localId: Long) {
        val pick = synchronized(sources) { sources.remove(localId) }
        state.update { jobs -> jobs.filterNot { it.localId == localId } }
        pick?.let { runCatching(it.cleanup).onFailure { e -> AppLogs.warn("files.pick_cleanup_failed", e) } }
    }

    fun unclaimedLinks(targetType: String, targetId: String): Flow<List<UploadDone>> =
        links.map { book -> book.unclaimed.filter { it.target.targetType == targetType && it.target.targetId == targetId } }.distinctUntilChanged()

    fun claim(localId: Long): UploadDone? {
        var taken: UploadDone? = null
        links.update { book ->
            taken = book.done[localId]
            if (taken == null) book else book.copy(done = book.done - localId)
        }
        return taken
    }

    private fun startAll(target: UploadTarget, picks: List<PickedFile>, wantsLink: Boolean): List<Long> {
        val created = picks.map { pick ->
            val job = UploadJob(ids.incrementAndGet(), target, pick.name, wantsLink = wantsLink)
            synchronized(sources) { sources[job.localId] = pick }
            job
        }
        if (wantsLink) links.update { it.copy(waiting = it.waiting + created.map { job -> job.localId }) }
        state.update { it + created }
        launch(created.map { it.localId })
        return created.map { it.localId }
    }

    private fun launch(localIds: List<Long>) {
        val gates = localIds.associateWith { CompletableDeferred<String?>() }
        synchronized(running) { running.putAll(gates) }
        scope.launch {
            localIds.forEach { localId ->
                val gate = gates.getValue(localId)
                gate.complete(runCatching { perform(localId) }.getOrNull())
            }
        }
    }

    private suspend fun await(localId: Long, claimLink: Boolean = false): String? {
        val gate = synchronized(running) { running[localId] } ?: return null
        try {
            val id = gate.await() ?: return null
            return if (claimLink) claim(localId)?.fileId else id
        } finally {
            synchronized(running) { if (running[localId] === gate && gate.isCompleted) running.remove(localId) }
            if (claimLink) links.update { it.copy(waiting = it.waiting - localId) }
        }
    }

    private suspend fun perform(localId: Long): String? {
        val job = state.value.firstOrNull { it.localId == localId } ?: return null
        val pick = synchronized(sources) { sources[localId] } ?: return null
        val outcome = runCatching {
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
        val id = outcome.getOrNull()
        if (id != null) {
            if (job.wantsLink) {
                val done = UploadDone(localId, job.target, id, pick.name, pick.resolvedMime)
                links.update { it.copy(done = it.done + (localId to done)) }
            }
            dismiss(localId)
            return id
        }
        val code = if (outcome.exceptionOrNull() is AttachmentTooLarge) "FILE_TOO_LARGE" else "UPLOAD_FAILED"
        AppLogs.warn("files.attach_failed", outcome.exceptionOrNull(), mapOf("code" to code))
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
