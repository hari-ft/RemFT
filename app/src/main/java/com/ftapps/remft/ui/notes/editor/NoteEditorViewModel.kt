package com.ftapps.remft.ui.notes.editor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.db.entity.NoteEntity
import com.ftapps.remft.data.notes.ExternalDocumentStore
import com.ftapps.remft.data.notes.ExternalOpenResult
import com.ftapps.remft.data.notes.ExternalSaveOutcome
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.notes.editing.MdActionId
import com.ftapps.remft.domain.notes.editing.MdActions
import com.ftapps.remft.domain.notes.editing.MdEdit
import com.ftapps.remft.domain.notes.editing.MdEditing
import com.ftapps.remft.domain.settings.NoteSourceStyling
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.tasks.TasksEnvironment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

enum class EditorMode { Source, Reading }

enum class SaveState { Idle, Saving, Saved, Failed }

enum class LoadState { Loading, Ready, NotFound }

enum class ExternalMessage { Saved, SavedAsNote, SaveFailed, LostAccess, CopySaved }

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: NotesRepository,
    private val external: ExternalDocumentStore,
    settings: SettingsStore,
    private val tasks: TasksEnvironment,
) : ViewModel() {
    private val initialId: String? = savedState.get<String>(KEY_ID)
    val isExternal: Boolean = savedState.get<Boolean>("external") == true

    var noteId: String? by mutableStateOf(initialId)
        private set
    var load by mutableStateOf(if (initialId == null) LoadState.Ready else LoadState.Loading)
        private set
    var title by mutableStateOf(TextFieldValue(""))
        private set
    var source by mutableStateOf(TextFieldValue(""))
        private set
    var mode by mutableStateOf(EditorMode.Source)
    var saveState by mutableStateOf(SaveState.Idle)
        private set
    var isPinned by mutableStateOf(false)
        private set
    var isArchived by mutableStateOf(false)
        private set
    var updatedAt by mutableStateOf<String?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var conflict by mutableStateOf(false)
        private set
    var externalMessage by mutableStateOf<ExternalMessage?>(null)
    var closed by mutableStateOf(false)
        private set

    val externalSession get() = external.session

    val styling: StateFlow<NoteSourceStyling> = settings.flow(SettingKeys.NoteSourceStyling)
        .map { NoteSourceStyling.parse(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NoteSourceStyling.MarkersOnly)

    private var debounce: Job? = null
    private var saving = false
    private var saveAgain = false
    private var closeWhenSaved = false

    @Volatile private var deleted = false

    @Volatile private var storedId: String? = initialId

    @Volatile private var persisted: Draft? = if (initialId == null) Draft("", "") else null
    private val writeLock = Mutex()

    init {
        if (isExternal) {
            val session = external.session.value
            if (session == null) {
                load = LoadState.NotFound
            } else {
                title = TextFieldValue(session.handle.displayName)
                source = TextFieldValue(session.markdown)
                load = LoadState.Ready
            }
        } else if (initialId != null) {
            viewModelScope.launch {
                repository.observe(initialId).collect { note -> onStored(note) }
            }
        }
    }

    private fun onStored(note: NoteEntity?) {
        if (deleted) return
        if (note == null) {
            if (load == LoadState.Loading || !dirty) load = LoadState.NotFound
            return
        }
        val first = load == LoadState.Loading
        isPinned = note.isPinned
        isArchived = note.isArchived
        if (first || (!dirty && !saving && note.updatedAt != updatedAt)) {
            if (first) {
                title = TextFieldValue(note.title)
            } else if (repository.titleFor(title.text) != note.title) {
                title = TextFieldValue(note.title, title.selection.clampTo(note.title.length))
            }
            val content = note.contentMarkdown.orEmpty()
            if (first || source.text != content) source = TextFieldValue(content, TextRange(if (first) 0 else source.selection.start.coerceAtMost(content.length)))
            persisted = Draft(title.text, source.text)
        }
        updatedAt = note.updatedAt
        load = LoadState.Ready
    }

    fun onTitleChange(value: TextFieldValue) {
        val changed = value.text != title.text
        title = value
        if (changed) markDirty()
    }

    fun onSourceChange(value: TextFieldValue) {
        val previous = source
        if (value.text == previous.text) {
            source = value
            return
        }
        val continued = MdEditing.newlineTyped(previous.text, value.text, value.selection.start)
            .takeIf { value.selection.collapsed }
        source = if (continued != null) TextFieldValue(continued.text, TextRange(continued.selection)) else value
        markDirty()
    }

    fun applyEdit(edit: MdEdit) {
        source = TextFieldValue(edit.text, TextRange(edit.selection.coerceIn(0, edit.text.length)))
        markDirty()
    }

    fun insertText(insert: String) {
        val selection = source.selection
        val text = source.text
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(start, text.length)
        applyEdit(MdEdit(text.replaceRange(start, end, insert), start + insert.length))
    }

    suspend fun ensureNoteId(): String? = if (isExternal) null else ensureSaved()

    fun applyAction(id: MdActionId, tableHeader: String) {
        val selection = source.selection
        applyEdit(MdActions.apply(id, source.text, selection.min, selection.max, tableHeader))
    }

    fun applySlash(id: MdActionId, tableHeader: String) {
        applyEdit(MdActions.applySlash(id, source.text, source.selection.start, tableHeader))
    }

    fun indent(outdent: Boolean): Boolean {
        val edit = MdEditing.indentListItem(source.text, source.selection.start, outdent) ?: return false
        applyEdit(edit)
        return true
    }

    fun replaceSource(text: String, caret: Int) {
        applyEdit(MdEdit(text, caret))
    }

    fun select(start: Int, end: Int) {
        val length = source.text.length
        source = source.copy(selection = TextRange(start.coerceIn(0, length), end.coerceIn(0, length)))
    }

    private fun markDirty() {
        dirty = true
        if (isExternal) return
        debounce?.cancel()
        debounce = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            save()
        }
    }

    fun flush() {
        if (isExternal || !dirty || deleted) return
        debounce?.cancel()
        viewModelScope.launch { save() }
    }

    suspend fun save() {
        if (isExternal || deleted) return
        if (saving) {
            if (dirty) saveAgain = true
            return
        }
        if (!dirty) return
        saving = true
        try {
            do {
                saveAgain = false
                saveOnce()
            } while (saveAgain && dirty)
        } finally {
            saving = false
        }
    }

    private suspend fun saveOnce() {
        val draft = Draft(title.text, source.text)
        dirty = false
        saveState = SaveState.Saving
        try {
            val id = repository.outliving { write(draft, createWhenUnchanged = true) }
            if (id != null && id != noteId) {
                noteId = id
                savedState[KEY_ID] = id
            }
            saveState = SaveState.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogs.warn("notes.save_failed", e)
            dirty = true
            saveState = SaveState.Failed
        }
    }

    private suspend fun write(draft: Draft, createWhenUnchanged: Boolean, whenDeleted: Boolean = false): String? = writeLock.withLock {
        val id = storedId
        if (deleted && !whenDeleted) return@withLock id
        if (persisted == null) return@withLock id
        if (draft == persisted && (id != null || !createWhenUnchanged)) return@withLock id
        if (id == null) {
            storedId = repository.create(draft.title, draft.markdown)
        } else {
            repository.save(id, draft.title, draft.markdown)
        }
        persisted = draft
        storedId
    }

    private suspend fun ensureSaved(): String? {
        if (noteId == null) {
            dirty = true
            save()
        }
        return noteId
    }

    fun togglePinned() {
        viewModelScope.launch {
            val id = ensureSaved() ?: return@launch
            val next = !isPinned
            isPinned = next
            repository.setPinned(id, next)
        }
    }

    fun toggleArchived() {
        viewModelScope.launch {
            val id = ensureSaved() ?: return@launch
            val next = !isArchived
            isArchived = next
            repository.setArchived(id, next)
        }
    }

    suspend fun binEnabled(): Boolean = repository.binEnabled()

    fun delete(message: String) {
        debounce?.cancel()
        val draft = Draft(title.text, source.text)
        deleted = true
        dirty = false
        viewModelScope.launch {
            val id = runCatching { repository.outliving { write(draft, createWhenUnchanged = false, whenDeleted = true) } }.getOrElse { storedId }
            if (id != null) tasks.undo.post(id, message) { repository.delete(id) }
            closed = true
        }
    }

    fun titleForExport(): String = repository.titleFor(title.text)

    fun saveExternal(force: Boolean) {
        viewModelScope.launch {
            when (val outcome = external.save(source.text, force)) {
                is ExternalSaveOutcome.Saved -> {
                    dirty = false
                    conflict = false
                    externalMessage = ExternalMessage.Saved
                    if (closeWhenSaved) closed = true
                }
                ExternalSaveOutcome.Conflict -> conflict = true
                ExternalSaveOutcome.LostAccess -> {
                    closeWhenSaved = false
                    externalMessage = ExternalMessage.LostAccess
                }
                ExternalSaveOutcome.Failed -> {
                    closeWhenSaved = false
                    externalMessage = ExternalMessage.SaveFailed
                }
            }
        }
    }

    fun saveExternalAndClose(asNote: Boolean) {
        closeWhenSaved = true
        if (asNote) saveExternalAsNote() else saveExternal(force = false)
    }

    fun reloadExternal() {
        closeWhenSaved = false
        viewModelScope.launch {
            conflict = false
            when (val result = external.reload()) {
                is ExternalOpenResult.Opened -> {
                    source = TextFieldValue(result.session.markdown)
                    dirty = false
                }
                is ExternalOpenResult.Refused -> externalMessage = ExternalMessage.LostAccess
                null -> Unit
            }
        }
    }

    fun saveExternalCopy(target: Uri) {
        closeWhenSaved = false
        viewModelScope.launch {
            conflict = false
            externalMessage = if (external.saveCopy(target, source.text)) ExternalMessage.CopySaved else ExternalMessage.SaveFailed
        }
    }

    fun dismissConflict() {
        closeWhenSaved = false
        conflict = false
    }

    fun saveExternalAsNote() {
        viewModelScope.launch {
            repository.create(title.text, source.text)
            dirty = false
            externalMessage = ExternalMessage.SavedAsNote
            if (closeWhenSaved) closed = true
        }
    }

    override fun onCleared() {
        if (isExternal || deleted || persisted == null) return
        val draft = Draft(title.text, source.text)
        repository.detached { write(draft, createWhenUnchanged = false) }
    }

    private data class Draft(val title: String, val markdown: String)

    companion object {
        const val AUTOSAVE_MS = 1_500L
        const val KEY_ID = "id"
    }
}

private fun TextRange.clampTo(length: Int): TextRange = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))
