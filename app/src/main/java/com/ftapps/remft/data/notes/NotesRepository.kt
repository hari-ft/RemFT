package com.ftapps.remft.data.notes

import androidx.room.withTransaction
import com.ftapps.remft.data.bin.BinMapping
import com.ftapps.remft.data.bin.BinMapping.toBin
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.notes.NoteText
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

data class NotePatch(
    val title: String? = null,
    val markdown: String? = null,
    val replaceMarkdown: Boolean = false,
    val isPinned: Boolean? = null,
    val isArchived: Boolean? = null,
)

class NotesRepository(
    private val holder: DatabaseHolder,
    private val workspaceId: suspend () -> String,
    private val settings: SettingsStore,
    private val attachmentsDir: File,
    private val untitled: () -> String,
    private val now: () -> Instant = Instant::now,
) {
    private val dao: NotesDao get() = holder.db.notesDao()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeList(): Flow<List<NoteEntity>> =
        flow { emit(workspaceId()) }.flatMapLatest { dao.observeAll(it) }.flowOn(Dispatchers.IO)

    fun observe(id: String): Flow<NoteEntity?> = dao.observe(id).flowOn(Dispatchers.IO)

    suspend fun get(id: String): NoteEntity? = withContext(Dispatchers.IO) { dao.get(id) }

    private fun stamp(): String = UtcText.format(now())

    fun titleFor(raw: String): String = NoteText.savedTitle(raw, untitled())

    suspend fun create(title: String, markdown: String?, isPinned: Boolean = false): String = withContext(Dispatchers.IO) {
        val id = Ulid.next(now().toEpochMilli())
        val saved = titleFor(title)
        val plain = NoteText.plainTextFromMarkdown(markdown)
        val at = stamp()
        val note = NoteEntity(
            id = id,
            workspaceId = workspaceId(),
            title = saved,
            contentMarkdown = markdown,
            plainText = plain,
            isPinned = isPinned,
            isArchived = false,
            titleFold = Fold.searchText(saved),
            bodyFold = Fold.searchText(plain),
            createdAt = at,
            updatedAt = at,
        )
        holder.db.withTransaction { dao.insert(note) }
        id
    }

    suspend fun update(id: String, patch: NotePatch): Boolean = withContext(Dispatchers.IO) {
        holder.db.withTransaction {
            val current = dao.get(id) ?: return@withTransaction false
            var next = current.copy(updatedAt = stamp())
            if (patch.title != null) {
                val saved = titleFor(patch.title)
                next = next.copy(title = saved, titleFold = Fold.searchText(saved))
            }
            if (patch.replaceMarkdown || patch.markdown != null) {
                val plain = NoteText.plainTextFromMarkdown(patch.markdown)
                next = next.copy(contentMarkdown = patch.markdown, plainText = plain, bodyFold = Fold.searchText(plain))
            }
            if (patch.isPinned != null) next = next.copy(isPinned = patch.isPinned)
            if (patch.isArchived != null) next = next.copy(isArchived = patch.isArchived)
            dao.update(next)
            true
        }
    }

    suspend fun save(id: String, title: String, markdown: String): Boolean =
        update(id, NotePatch(title = title, markdown = markdown, replaceMarkdown = true))

    suspend fun setPinned(id: String, pinned: Boolean) = update(id, NotePatch(isPinned = pinned))

    suspend fun setArchived(id: String, archived: Boolean) = update(id, NotePatch(isArchived = archived))

    suspend fun binEnabled(): Boolean = settings.binEnabled()

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val toBin = binEnabled()
        var removed = emptyList<FileRowEntity>()
        val deleted = holder.db.withTransaction {
            val note = dao.get(id) ?: return@withTransaction false
            val files = dao.files(id)
            if (toBin) {
                val payload = BinPayload(
                    notes = listOf(note.toBin()),
                    noteLinks = dao.links(id).map { it.toBin() },
                    files = files.map { it.toBin() },
                )
                dao.insertTrash(BinMapping.entry(Ulid.next(now().toEpochMilli()), note.workspaceId, "note", note.id, note.title, payload, now()))
            }
            dao.deleteNote(id)
            dao.deleteLinks(id)
            dao.deleteFiles(id)
            removed = files
            true
        }
        if (deleted && !toBin) removed.mapNotNull { it.localPath }.forEach { safeChild(it)?.delete() }
        deleted
    }

    suspend fun <T> outliving(block: suspend () -> T): T = background.async { block() }.await()

    fun detached(block: suspend () -> Unit) {
        background.launch { runCatching { block() } }
    }

    fun deleteInBackground(id: String, then: suspend () -> Unit = {}) {
        background.launch {
            runCatching { delete(id) }
            then()
        }
    }

    suspend fun attachmentFile(fileId: String): File? = withContext(Dispatchers.IO) {
        val row = dao.file(fileId) ?: return@withContext null
        val path = row.localPath ?: return@withContext null
        safeChild(path)?.takeIf { it.isFile }
    }

    private fun safeChild(name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") return null
        return File(attachmentsDir, name)
    }
}
