package com.ftapps.remft.ui.notes

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.notes.ExternalDocumentStore
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.testing.TestDatabases
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.notes.export.NoteExportViewModel
import com.ftapps.remft.ui.notes.export.NoteExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

class NotesTestEnvironment {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dir: File = File(context.filesDir, "notes-test-${System.nanoTime()}").apply { mkdirs() }
    val attachments: File = File(dir, "files").apply { mkdirs() }
    val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
    val holder: DatabaseHolder

    init {
        val file = File(dir, "remft.sqlite")
        TestDatabases.create(file)
        holder = DatabaseHolder(context, file)
    }

    val repository = NotesRepository(
        holder = holder,
        workspaceId = { WORKSPACE },
        settings = settings,
        attachmentsDir = attachments,
        untitled = { "Untitled" },
    )

    val external = ExternalDocumentStore(context, settings)

    val tasks = TestTasks()

    fun exportViewModel() = NoteExportViewModel(NoteExporter(context, repository), repository, settings)

    fun close() {
        tasks.close()
        holder.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    companion object {
        const val WORKSPACE = "01WS0000000000000000000000"
    }
}
