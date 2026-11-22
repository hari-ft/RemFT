package com.ftapps.remft.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.ui.tasks.TasksEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class TestTasks(
    val clock: Clock = Clock.fixed(Instant.parse("2026-11-20T04:30:00Z"), ZoneId.of("Asia/Kolkata")),
    seed: (android.database.sqlite.SQLiteDatabase) -> Unit = {},
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val dir: File = File(context.filesDir, "tasks-test-${System.nanoTime()}")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
    val holder: DatabaseHolder
    val identity: LocalIdentityRepository
    val attachments = File(dir, "files")
    val series: SeriesRepository
    val repository: TasksRepository
    val pendingDeletes = PendingDeletes(scope)
    val environment: TasksEnvironment

    init {
        val file = File(dir, "remft.sqlite")
        TestDatabases.create(file, seed)
        holder = DatabaseHolder(context, file)
        identity = LocalIdentityRepository({ holder.db.identityDao() }, settings)
        series = SeriesRepository({ holder.db }, clock, scope)
        repository = TasksRepository({ holder.db }, identity, settings, clock, attachments, series)
        environment = TasksEnvironment(repository, settings, pendingDeletes, series = series)
    }

    fun close() {
        holder.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    fun query(sql: String): List<Map<String, String?>> {
        val db = holder.db.openHelper.readableDatabase
        val rows = mutableListOf<Map<String, String?>>()
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                rows += (0 until cursor.columnCount).associate { cursor.getColumnName(it) to cursor.getString(it) }
            }
        }
        return rows
    }
}
