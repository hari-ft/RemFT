package com.ftapps.remft.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.ftapps.remft.domain.backup.oldapp.SqlOpener
import com.ftapps.remft.domain.backup.oldapp.SqlPort
import java.io.File

class AndroidSqlPort(file: File) : SqlPort {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
    }

    override fun query(sql: String, args: List<Any?>, each: (Map<String, Any?>) -> Unit) {
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, Any?>()
                for (i in 0 until cursor.columnCount) row[cursor.getColumnName(i)] = valueAt(cursor, i)
                each(row)
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val out = block()
            db.setTransactionSuccessful()
            return out
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        db.close()
    }

    private fun valueAt(cursor: Cursor, i: Int): Any? = when (cursor.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
        else -> cursor.getString(i)
    }

    companion object {
        val opener: SqlOpener = SqlOpener { AndroidSqlPort(it) }
    }
}
