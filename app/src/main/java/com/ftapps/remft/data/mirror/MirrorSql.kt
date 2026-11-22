package com.ftapps.remft.data.mirror

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.Closeable
import java.io.File

interface MirrorSql {
    fun <T> query(sql: String, args: Array<out Any?> = emptyArray(), row: (Cursor) -> T): List<T>
    fun exec(sql: String, args: Array<out Any?> = emptyArray())
    fun <T> inTransaction(block: () -> T): T
}

class SupportMirrorSql(private val database: () -> SupportSQLiteDatabase) : MirrorSql {
    override fun <T> query(sql: String, args: Array<out Any?>, row: (Cursor) -> T): List<T> =
        database().query(sql, args).use { cursor ->
            val out = ArrayList<T>()
            while (cursor.moveToNext()) out.add(row(cursor))
            out
        }

    override fun exec(sql: String, args: Array<out Any?>) {
        if (args.isEmpty()) database().execSQL(sql) else database().execSQL(sql, args)
    }

    override fun <T> inTransaction(block: () -> T): T {
        val db = database()
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
}

class FileMirrorSql(file: File) : MirrorSql, Closeable {
    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    override fun <T> query(sql: String, args: Array<out Any?>, row: (Cursor) -> T): List<T> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            val out = ArrayList<T>()
            while (cursor.moveToNext()) out.add(row(cursor))
            out
        }

    override fun exec(sql: String, args: Array<out Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun <T> inTransaction(block: () -> T): T = db.transaction { block() }

    override fun close() {
        db.close()
    }
}

fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
