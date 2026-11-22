package com.ftapps.remft.testing

import com.ftapps.remft.data.search.SearchDao
import com.ftapps.remft.data.search.SearchRecentsStore
import com.ftapps.remft.data.search.SearchRepository
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.ui.search.SearchEnvironment
import kotlinx.coroutines.runBlocking

class TestSearch(val tasks: TestTasks) {
    val dao = SearchDao { tasks.holder.db }
    val repository = SearchRepository(dao, tasks.repository::workspaceId)
    val recents = SearchRecentsStore(tasks.settings)
    val environment = SearchEnvironment(repository, recents, hidden = tasks.pendingDeletes.ids)

    val workspace: String get() = runBlocking { tasks.repository.workspaceId() }

    fun note(id: String, title: String, body: String? = null, archived: Boolean = false, updatedAt: String = "2026-11-01T00:00:00.000Z") {
        tasks.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO notes (id, workspace_id, title, plain_text, is_archived, title_fold, body_fold, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, workspace, title, body, if (archived) 1 else 0, Fold.searchText(title), body?.let { Fold.searchText(it) }, updatedAt),
        )
    }

    fun file(id: String, name: String, targetType: String = "workspace", createdAt: String = "2026-11-01T00:00:00.000Z") {
        tasks.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO file_rows (id, workspace_id, target_type, target_id, name, mime, size_bytes, name_fold, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, workspace, targetType, workspace, name, "application/pdf", 10, Fold.searchText(name), createdAt),
        )
    }
}
