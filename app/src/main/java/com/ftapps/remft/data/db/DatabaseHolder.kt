package com.ftapps.remft.data.db

import android.content.Context
import com.ftapps.remft.domain.backup.restore.BackupRestore
import java.io.File

class DatabaseRestoring : IllegalStateException("The database is being replaced by a restore")

class DatabaseRecoveryPending : IllegalStateException("An interrupted restore must be finished before the database opens")

class DatabaseHolder(private val context: Context, private val fileOverride: File? = null) {
    @Volatile
    private var current: RemftDatabase? = null

    @Volatile
    private var restoring = false

    val file: File get() = fileOverride ?: DatabaseFactory.databaseFile(context)

    val db: RemftDatabase
        get() = current ?: synchronized(this) {
            if (restoring) throw DatabaseRestoring()
            current ?: run {
                if (BackupRestore.openBlocked(file)) throw DatabaseRecoveryPending()
                DatabaseFactory.open(context, file).also { current = it }
            }
        }

    val isOpen: Boolean get() = current != null

    val isRestoring: Boolean get() = restoring

    fun close() {
        synchronized(this) {
            current?.close()
            current = null
        }
    }

    fun detachForRestore(): RemftDatabase? = synchronized(this) {
        restoring = true
        current.also { current = null }
    }

    fun reopenAfterRestore() {
        synchronized(this) {
            restoring = false
        }
    }
}
