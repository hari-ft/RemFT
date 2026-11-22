package com.ftapps.remft.testing

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.ftapps.remft.data.db.DatabaseFactory
import java.io.File

object TestDatabases {
    val fixturesDir: File get() = File(System.getProperty("fixtures.dir") ?: error("fixtures.dir not set"))

    val userTables: List<String> = listOf(
        "tasks", "checklist_items", "notes", "note_link_rows", "reminders", "file_rows", "folders",
        "task_series", "series_skips", "trash_items", "alarm_events", "file_mirrors",
    )

    fun create(file: File, seed: (SQLiteDatabase) -> Unit = {}) {
        file.parentFile?.mkdirs()
        file.delete()
        val room = DatabaseFactory.open(ApplicationProvider.getApplicationContext(), file)
        try {
            room.openHelper.writableDatabase
        } finally {
            room.close()
        }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            seed(db)
        } finally {
            db.close()
        }
    }
}
