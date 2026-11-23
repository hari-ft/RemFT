package com.ftapps.remft.data.onedrive

import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase

class MirrorChangeWatch(private val onChange: () -> Unit) {
    private var watched: RoomDatabase? = null

    private val observer = object : InvalidationTracker.Observer(TABLES) {
        override fun onInvalidated(tables: Set<String>) {
            onChange()
        }
    }

    fun watch(db: RoomDatabase): Boolean {
        synchronized(this) {
            if (watched === db) return false
            watched = db
        }
        db.invalidationTracker.addObserver(observer)
        return true
    }

    companion object {
        val TABLES: Array<String> = arrayOf("file_rows", "folders", "trash_items", "tasks", "notes")
    }
}
