package com.ftapps.remft.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.notes.NoteListing
import com.ftapps.remft.domain.notes.NoteSearchRow
import com.ftapps.remft.domain.notes.NoteSortField
import com.ftapps.remft.domain.notes.NoteSortKey
import com.ftapps.remft.domain.notes.NoteSortState
import com.ftapps.remft.domain.notes.NoteText
import com.ftapps.remft.domain.notes.NotesFilter
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class NoteRow(
    val id: String,
    val title: String,
    val snippet: String,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
)

sealed interface NotesListState {
    data object Loading : NotesListState
    data object Failed : NotesListState
    data class Ready(val notes: List<NoteRow>) : NotesListState
}

data class NotesQuery(val filter: NotesFilter = NotesFilter.All, val search: String = "")

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    private val mutableQuery = MutableStateFlow(NotesQuery())
    val query: StateFlow<NotesQuery> = mutableQuery

    private val pendingDeletes = MutableStateFlow<Set<String>>(emptySet())
    private val awaitingUndo = mutableSetOf<String>()
    private val reload = MutableStateFlow(0)

    val sort: StateFlow<NoteSortState> = settings.flow(SettingKeys.NotesSort)
        .map { NoteSortState.parse(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NoteSortState())

    val viewMode: StateFlow<String> = settings.flow(SettingKeys.NotesViewMode)
        .map { if (it == "grid") "grid" else "list" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "list")

    val dateFormat: StateFlow<DateFormatId> = settings.flow(SettingKeys.DateFormat)
        .map { DateFormatId.parse(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DateFormatId.System)

    private val notes = repository.observeList()
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val state: StateFlow<NotesListState> = reload
        .let { trigger ->
            combine(trigger, mutableQuery, sort, pendingDeletes) { _, q, s, p -> Triple(q, s, p) }
        }
        .let { inputs ->
            combine(notes, inputs) { loaded, (q, s, pending) ->
                val visible = loaded.getOrThrow().filter { it.id !in pending }
                val arranged = NoteListing.arrange(visible, q.filter, q.search, s, ::searchRow)
                NotesListState.Ready(arranged.map(::row)) as NotesListState
            }
        }
        .catch { emit(NotesListState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesListState.Loading)

    private fun searchRow(note: NoteEntity) = NoteSearchRow(
        NoteSortKey(note.id, note.title, note.isPinned, note.createdAt, note.updatedAt),
        note.isArchived,
        note.titleFold,
        note.bodyFold,
        note.plainText,
    )

    private fun row(note: NoteEntity) = NoteRow(
        note.id, note.title, NoteText.snippet(note.plainText), note.isPinned, note.isArchived, note.createdAt, note.updatedAt,
    )

    fun setFilter(filter: NotesFilter) {
        mutableQuery.value = mutableQuery.value.copy(filter = filter)
    }

    fun setSearch(search: String) {
        mutableQuery.value = mutableQuery.value.copy(search = search)
    }

    fun selectSort(field: NoteSortField) {
        viewModelScope.launch { settings.set(SettingKeys.NotesSort.name, sort.value.select(field).encode()) }
    }

    fun reverseSort() {
        viewModelScope.launch { settings.set(SettingKeys.NotesSort.name, sort.value.reversed().encode()) }
    }

    fun setViewMode(mode: String) {
        viewModelScope.launch { settings.set(SettingKeys.NotesViewMode.name, mode) }
    }

    fun retry() {
        reload.value += 1
    }

    fun togglePinned(note: NoteRow) {
        viewModelScope.launch { repository.setPinned(note.id, !note.isPinned) }
    }

    fun setArchived(note: NoteRow, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(note.id, archived) }
    }

    fun hide(id: String) {
        awaitingUndo += id
        pendingDeletes.update { it + id }
    }

    fun undoDelete(id: String) {
        awaitingUndo -= id
        pendingDeletes.update { it - id }
    }

    fun commitDelete(id: String) {
        if (!awaitingUndo.remove(id)) return
        repository.deleteInBackground(id) {
            withTimeoutOrNull(SETTLE_MS) { notes.first { loaded -> loaded.getOrNull()?.none { it.id == id } ?: true } }
            pendingDeletes.update { it - id }
        }
    }

    override fun onCleared() {
        val ids = awaitingUndo.toList()
        awaitingUndo.clear()
        ids.forEach { id -> repository.deleteInBackground(id) }
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
