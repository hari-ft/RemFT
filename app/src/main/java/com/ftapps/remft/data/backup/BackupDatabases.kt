package com.ftapps.remft.data.backup

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ftapps.remft.data.db.DatabaseSchema
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.MirrorRef
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.io.IOException
import java.time.Instant

class StagedDatabaseInvalid(reason: String) : IOException(reason)

object BackupDatabases {
    private const val OPEN_FLAGS = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS

    fun <T> withDatabase(file: File, block: (SQLiteDatabase) -> T): T {
        val db = SQLiteDatabase.openDatabase(file.path, null, OPEN_FLAGS)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    fun snapshot(live: SupportSQLiteDatabase, liveFile: File, target: File, preferVacuumInto: Boolean = true) {
        target.parentFile?.let { FileOps.ensureDir(it) }
        FileOps.deleteFileIfExists(target)
        if (preferVacuumInto) {
            try {
                live.execSQL("VACUUM INTO '${target.path.replace("'", "''")}'")
                return
            } catch (_: SQLiteException) {
                FileOps.deleteFileIfExists(target)
            }
        }
        copyUnderWriteLock(live, liveFile, target)
    }

    private fun copyUnderWriteLock(live: SupportSQLiteDatabase, liveFile: File, target: File) {
        runCatching { live.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        val wal = File(liveFile.path + "-wal")
        val targetWal = File(target.path + "-wal")
        live.beginTransaction()
        try {
            liveFile.copyTo(target, overwrite = true)
            if (wal.isFile && wal.length() > 0) wal.copyTo(targetWal, overwrite = true)
        } finally {
            live.endTransaction()
        }
        withDatabase(target) { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        FileOps.deleteFileIfExists(targetWal)
    }

    fun referencedStoredNames(file: File): Set<String> = withDatabase(file) { db ->
        val out = LinkedHashSet<String>()
        db.rawQuery("SELECT local_path FROM file_rows", null).use { cursor ->
            while (cursor.moveToNext()) {
                val stored = if (cursor.isNull(0)) null else cursor.getString(0)
                if (!stored.isNullOrEmpty()) out.add(PathText.basename(stored))
            }
        }
        db.rawQuery("SELECT local_paths FROM trash_items", null).use { cursor ->
            while (cursor.moveToNext()) {
                val raw = if (cursor.isNull(0)) continue else cursor.getString(0)
                val kept = JsonText.parseOrNull(raw) as? JsonArray ?: continue
                for (k in kept) {
                    val name = JsonText.stringOf(k)
                    if (!name.isNullOrEmpty()) out.add(PathText.basename(name))
                }
            }
        }
        out
    }

    fun workspaceCounts(file: File): Map<String, Int> = withDatabase(file) { db ->
        val out = LinkedHashMap<String, Int>()
        db.rawQuery(WORKSPACE_COUNTS, null).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getInt(1)
        }
        out
    }

    fun hasData(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT (SELECT count(*) FROM tasks) + (SELECT count(*) FROM notes)").use { cursor ->
            cursor.moveToFirst() && cursor.getLong(0) > 0
        }

    fun recordMirrorRefs(file: File, refs: List<Pair<String, MirrorRef>>, now: Instant) {
        if (refs.isEmpty()) return
        withDatabase(file) { db ->
            db.beginTransaction()
            try {
                val at = UtcText.format(now)
                for ((storedName, ref) in refs) {
                    val itemId = ref.itemId.ifEmpty { null }
                    val remote = ref.remotePath.ifEmpty { null }
                    val args = arrayOf<Any?>(ref.sha256, ref.size, "mirrored", itemId, remote, at, storedName)
                    db.execSQL(
                        "UPDATE file_mirrors SET sha256 = ?, size_bytes = ?, state = ?, item_id = ?, remote_path = ?, updated_at = ? WHERE local_path = ?",
                        args,
                    )
                    val changed = db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) > 0 }
                    if (!changed) {
                        db.execSQL(
                            "INSERT INTO file_mirrors (sha256, size_bytes, state, item_id, remote_path, updated_at, local_path) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            args,
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun verify(file: File) {
        withDatabase(file) { db ->
            if (db.version != DatabaseSchema.VERSION) throw StagedDatabaseInvalid("user_version ${db.version}")
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { if (it.moveToFirst()) it.getString(0) else null }
            if (integrity != "ok") throw StagedDatabaseInvalid("integrity_check $integrity")
            db.rawQuery("SELECT count(*) FROM tasks", null).use { it.moveToFirst() }
        }
    }

    private const val WORKSPACE_COUNTS = "SELECT ws, count(*) AS n FROM (" +
        "SELECT workspace_id AS ws FROM tasks UNION ALL " +
        "SELECT workspace_id AS ws FROM notes UNION ALL " +
        "SELECT workspace_id AS ws FROM folders UNION ALL " +
        "SELECT workspace_id AS ws FROM file_rows UNION ALL " +
        "SELECT workspace_id AS ws FROM task_series UNION ALL " +
        "SELECT workspace_id AS ws FROM trash_items" +
        ") GROUP BY ws"
}
