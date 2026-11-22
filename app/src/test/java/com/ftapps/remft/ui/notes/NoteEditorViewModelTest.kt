package com.ftapps.remft.ui.notes

import android.os.Looper
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.ui.notes.editor.LoadState
import com.ftapps.remft.ui.notes.editor.NoteEditorViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class NoteEditorViewModelTest {
    private val env = NotesTestEnvironment()

    @After
    fun tearDown() = env.close()

    private fun handle(id: String?) = SavedStateHandle(mapOf("id" to id, "external" to false))

    private fun open(id: String?, repository: NotesRepository = env.repository, saved: SavedStateHandle = handle(id)) =
        NoteEditorViewModel(saved, repository, env.external, env.settings, env.tasks.environment)

    private fun idleUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    private fun notes() = runBlocking { env.repository.observeList().first() }

    private fun opened(id: String): NoteEditorViewModel {
        val viewModel = open(id)
        idleUntil { viewModel.load == LoadState.Ready }
        Thread.sleep(5)
        return viewModel
    }

    @Test
    fun theTitleAutosaveEchoKeepsATrailingSpaceAndTheCursor() {
        val id = runBlocking { env.repository.create("Plan", "body") }
        val viewModel = opened(id)
        viewModel.onTitleChange(TextFieldValue("Meeting ", TextRange(8)))
        runBlocking { viewModel.save() }
        val stored = runBlocking { env.repository.get(id)!! }
        assertEquals("Meeting", stored.title)
        idleUntil { viewModel.updatedAt == stored.updatedAt }
        assertEquals("Meeting ", viewModel.title.text)
        assertEquals(TextRange(8), viewModel.title.selection)
    }

    @Test
    fun anEmptyTitleStaysEmptyAfterItIsSavedAsUntitled() {
        val id = runBlocking { env.repository.create("Plan", "body") }
        val viewModel = opened(id)
        viewModel.onTitleChange(TextFieldValue("", TextRange(0)))
        runBlocking { viewModel.save() }
        val stored = runBlocking { env.repository.get(id)!! }
        assertEquals("Untitled", stored.title)
        idleUntil { viewModel.updatedAt == stored.updatedAt }
        assertEquals("", viewModel.title.text)
    }

    @Test
    fun aTitleChangedElsewhereStillReachesTheField() {
        val id = runBlocking { env.repository.create("Plan", "body") }
        val viewModel = opened(id)
        runBlocking { env.repository.save(id, "Renamed", "body") }
        idleUntil { viewModel.title.text == "Renamed" }
    }

    @Test
    fun leavingWhileTheLastSaveIsRunningStillStoresTheEdits() {
        val gate = CompletableDeferred<String>()
        val slow = NotesRepository(env.holder, { gate.await() }, env.settings, env.attachments, { "Untitled" })
        val store = ViewModelStore()
        val viewModel = ViewModelProvider.create(store, viewModelFactory { initializer { open(null, slow) } })[NoteEditorViewModel::class]
        viewModel.onTitleChange(TextFieldValue("Trip"))
        viewModel.onSourceChange(TextFieldValue("- tickets"))
        viewModel.flush()
        shadowOf(Looper.getMainLooper()).idle()
        store.clear()
        gate.complete(NotesTestEnvironment.WORKSPACE)
        idleUntil { notes().isNotEmpty() }
        Thread.sleep(300)
        val all = notes()
        assertEquals(1, all.size)
        assertEquals("Trip", all[0].title)
        assertEquals("- tickets", all[0].contentMarkdown)
    }

    @Test
    fun editsMadeAfterAnInFlightSaveAreStoredWhenLeaving() {
        val id = runBlocking { env.repository.create("Plan", "one") }
        val store = ViewModelStore()
        val viewModel = ViewModelProvider.create(store, viewModelFactory { initializer { open(id) } })[NoteEditorViewModel::class]
        idleUntil { viewModel.load == LoadState.Ready }
        viewModel.onSourceChange(TextFieldValue("two"))
        viewModel.flush()
        viewModel.onSourceChange(TextFieldValue("three"))
        store.clear()
        idleUntil { runBlocking { env.repository.get(id)?.contentMarkdown } == "three" }
        Thread.sleep(300)
        assertEquals("three", runBlocking { env.repository.get(id)?.contentMarkdown })
    }

    @Test
    fun theCreatedIdIsKeptSoARestoredEditorDoesNotCreateASecondNote() {
        val saved = handle(null)
        val viewModel = open(null, saved = saved)
        viewModel.onSourceChange(TextFieldValue("first"))
        runBlocking { viewModel.save() }
        val created = saved.get<String>("id")
        assertNotNull(created)
        assertEquals(viewModel.noteId, created)
        val restored = open(created, saved = handle(created))
        idleUntil { restored.load == LoadState.Ready }
        restored.onSourceChange(TextFieldValue("second"))
        runBlocking { restored.save() }
        val all = notes()
        assertEquals(1, all.size)
        assertEquals("second", all[0].contentMarkdown)
    }
}
