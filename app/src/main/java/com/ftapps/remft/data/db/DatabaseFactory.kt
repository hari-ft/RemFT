package com.ftapps.remft.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

object DatabaseFactory {
    fun databaseFile(context: Context): File = File(context.filesDir, RemftDatabase.FILE_NAME)

    fun open(context: Context, file: File = databaseFile(context)): RemftDatabase {
        file.parentFile?.mkdirs()
        return Room.databaseBuilder(context.applicationContext, RemftDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        runCatching { db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() } }
                    }
                },
            )
            .build()
    }
}
