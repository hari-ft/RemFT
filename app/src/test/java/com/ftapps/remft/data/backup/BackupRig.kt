package com.ftapps.remft.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.lock.WorkLocks
import com.ftapps.remft.testing.TestDatabases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import java.time.Instant

class BackupRig {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val root: File = Files.createTempDirectory("rb").toFile()
    val paths = BackupPaths(
        dbFile = File(root, "data/remft.sqlite"),
        filesDir = File(root, "data/files"),
        cacheDir = File(root, "cache"),
        locksDir = File(root, "data/locks"),
    )
    val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") })
    val holder = DatabaseHolder(context, paths.dbFile)
    val now: Instant = Instant.parse("2026-11-02T10:00:00Z")

    fun service(preferVacuumInto: Boolean = true): BackupService = BackupService(
        paths = paths,
        holder = holder,
        store = store,
        locks = { WorkLocks(paths.locksDir, 4242L) },
        appVersion = "3.1.1",
        clock = { now },
        preferVacuumInto = preferVacuumInto,
    )

    fun seed(extra: (SQLiteDatabase) -> Unit = {}) {
        TestDatabases.create(paths.dbFile) { db ->
            seedRows(db)
            extra(db)
        }
        writeFile("01JC0000000000000000000001.png", byteArrayOf(1, 2, 3, 4))
        writeFile("01JC0000000000000000000002.txt", "binned".toByteArray())
        writeFile("01JC0000000000000000000003.bin", ByteArray(4096) { (it % 251).toByte() })
    }

    fun writeFile(name: String, bytes: ByteArray): File {
        paths.filesDir.mkdirs()
        return File(paths.filesDir, name).apply { writeBytes(bytes) }
    }

    fun dump(): Map<String, List<String>> {
        val db = holder.db.openHelper.readableDatabase
        val out = LinkedHashMap<String, List<String>>()
        for (table in userTables) {
            val rows = ArrayList<String>()
            db.query("SELECT * FROM \"$table\"").use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add((0 until cursor.columnCount).joinToString("|") { i -> if (cursor.isNull(i)) "∅" else cursor.getString(i) })
                }
            }
            out[table] = rows.sorted()
        }
        return out
    }

    fun filesSnapshot(): Map<String, List<Byte>> =
        paths.filesDir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(paths.filesDir).path.replace('\\', '/') to it.readBytes().toList() }

    fun close() {
        runCatching { holder.close() }
        scope.cancel()
        root.deleteRecursively()
    }

    companion object {
        const val WORKSPACE = "01JC00000000000000000000WS"

        val userTables: List<String> = TestDatabases.userTables

        fun seedRows(db: SQLiteDatabase, workspace: String = WORKSPACE) {
            db.execSQL(
                "INSERT INTO tasks (id, workspace_id, title, description, due_at, created_at, updated_at) VALUES " +
                    "('01JC00000000000000000000T1', '$workspace', 'Pay rent', 'See [note](remft://notes/1)', '2026-11-03T09:00:00.000Z', '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.000Z')",
            )
            db.execSQL(
                "INSERT INTO tasks (id, workspace_id, title, status, completed_at, created_at, updated_at) VALUES " +
                    "('01JC00000000000000000000T2', '$workspace', 'Water plants', 'completed', '2026-11-01T09:00:00.000Z', '2026-10-30T08:00:00.000Z', '2026-11-01T09:00:00.000Z')",
            )
            db.execSQL(
                "INSERT INTO checklist_items (id, task_id, title, is_done, created_at, updated_at) VALUES " +
                    "('01JC00000000000000000000C1', '01JC00000000000000000000T1', 'Transfer', 1, '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.000Z')",
            )
            db.execSQL(
                "INSERT INTO notes (id, workspace_id, title, content_markdown, plain_text, title_fold, body_fold, created_at, updated_at) VALUES " +
                    "('01JC00000000000000000000N1', '$workspace', 'Groceries', '# List', 'List', 'groceries', 'list', '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.000Z')",
            )
            db.execSQL(
                "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, local_path, created_at, updated_at) VALUES " +
                    "('01JC00000000000000000000F1', '$workspace', 'task', '01JC00000000000000000000T1', 'receipt.png', 'image/png', 4, '01JC0000000000000000000001.png', '2026-11-01T08:00:00.000Z', '2026-11-01T08:00:00.000Z')",
            )
            db.execSQL(
                "INSERT INTO trash_items (id, workspace_id, kind, entity_id, title, payload, local_paths, size_bytes, deleted_at) VALUES " +
                    "('01JC00000000000000000000B1', '$workspace', 'file', '01JC00000000000000000000F2', 'old.txt', '{}', '[\"01JC0000000000000000000002.txt\"]', 6, '2026-11-01T10:00:00.000Z')",
            )
        }
    }
}
