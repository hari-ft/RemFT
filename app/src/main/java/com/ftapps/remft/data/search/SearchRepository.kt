package com.ftapps.remft.data.search

import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.search.GlobalSearch
import com.ftapps.remft.domain.search.GlobalSearchGroup
import com.ftapps.remft.domain.search.GlobalSearchItem
import com.ftapps.remft.domain.search.GlobalSearchKind
import com.ftapps.remft.domain.search.SearchHit
import com.ftapps.remft.domain.search.SearchMatch
import com.ftapps.remft.domain.search.SearchRecent
import com.ftapps.remft.domain.search.SearchRecents
import com.ftapps.remft.domain.search.SearchTier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SearchRepository(
    private val dao: SearchDao,
    private val workspaceId: suspend () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun tasks(query: String, statuses: List<String>): List<SearchHit> {
        val workspace = workspaceId()
        return withContext(io) { dao.tasks(workspace, query, statuses) }
    }

    suspend fun global(query: String): List<GlobalSearchGroup> {
        if (query.trim().isEmpty()) return emptyList()
        val workspace = workspaceId()
        return withContext(io) {
            val words = SearchMatch.queryWords(query)
            val hits = mapOf(
                GlobalSearchKind.Task to dao.tasks(workspace, query, GlobalSearch.taskStatuses),
                GlobalSearchKind.Note to dao.notes(workspace, query),
                GlobalSearchKind.File to dao.files(workspace, query),
            )
            GlobalSearch.groups(query, hits) { kind, shown -> dao.items(kind, shown, words) }
        }
    }

    suspend fun recentItems(recents: List<SearchRecent>): List<GlobalSearchItem> {
        if (recents.isEmpty()) return emptyList()
        return withContext(io) {
            val resolved = mutableMapOf<SearchRecent, GlobalSearchItem>()
            for (kind in GlobalSearchKind.entries) {
                val hits = recents.filter { it.kind == kind }.map { SearchHit(it.id, SearchTier.Title) }
                dao.items(kind, hits).forEach { resolved[SearchRecent(kind, it.id)] = it }
            }
            GlobalSearch.resolveRecents(recents) { resolved[it] }
        }
    }
}

class SearchRecentsStore(private val settings: SettingsStore) {
    private val mutex = Mutex()

    fun recents(): Flow<List<SearchRecent>> = settings.flow(SearchRecents.KEY).map { SearchRecents.parse(it) }

    suspend fun record(recent: SearchRecent) = mutex.withLock {
        val current = SearchRecents.parse(settings.get(SearchRecents.KEY))
        val next = SearchRecents.record(current, recent)
        if (next != current) settings.set(SearchRecents.KEY, SearchRecents.encode(next))
    }
}
