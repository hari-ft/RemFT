package com.ftapps.remft.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.files.AttachmentStore
import com.ftapps.remft.data.files.FilesRepository
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.ui.files.AttachSource
import com.ftapps.remft.ui.files.FilesEnvironment
import com.ftapps.remft.ui.files.PickedFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class BrokenDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("unreadable") }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("unwritable")
}

class TestFiles(
    val clock: MutableClock = MutableClock(Instant.parse("2026-11-20T04:30:00Z")),
    maxBytes: Long = AttachmentStore.MAX_BYTES,
    binSettings: SettingsStore? = null,
    fetchMissing: suspend (String) -> File? = { null },
    canFetch: suspend (String) -> Boolean = { false },
    downloaded: Flow<String> = emptyFlow(),
) {
    val tasks = TestTasks(clock = clock)
    val holder get() = tasks.holder
    val settings: SettingsStore get() = tasks.settings
    var failDatabase = false
    val database: () -> RemftDatabase = { if (failDatabase) throw IllegalStateException("database closed") else tasks.holder.db }
    val workspace: String = runBlocking { tasks.identity.resolve().workspaceId }
    val dir: File = tasks.attachments
    val bin = RecycleBin(database, binSettings ?: tasks.settings, dir, { workspace }, clock)
    val attachments = AttachmentStore(database, dir, bin, clock, maxBytes = maxBytes, fetchMissing = fetchMissing, canFetch = canFetch, downloaded = downloaded)
    val files = FilesRepository(database, { workspace }, bin, clock = clock)
    var picks: List<PickedFile> = emptyList()
    val environment = FilesEnvironment(
        attachments = attachments,
        files = files,
        bin = bin,
        settings = tasks.settings,
        authority = "com.ftapps.remft.files",
        clock = clock,
        pickOverride = { _: AttachSource -> picks },
    )
    val notes = NotesRepository(
        holder = tasks.holder,
        workspaceId = { workspace },
        settings = tasks.settings,
        attachmentsDir = dir,
        untitled = { "Untitled" },
        now = { clock.instant() },
    )

    fun bytes(text: String): () -> java.io.InputStream = { text.byteInputStream() }

    fun add(name: String, text: String = "data-$name", targetType: String = "workspace", targetId: String = workspace, folderId: String? = null, mime: String = "text/plain"): String =
        runBlocking { attachments.add(workspace, targetType, targetId, name, mime, null, folderId, bytes(text)) }

    fun query(sql: String) = tasks.query(sql)

    fun exec(sql: String) = tasks.holder.db.openHelper.writableDatabase.execSQL(sql)

    fun close() = tasks.close()
}
