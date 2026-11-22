package com.ftapps.remft.ui.notes

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.ui.notes.editor.NoteEditorScreen
import com.ftapps.remft.ui.notes.editor.NoteEditorViewModel
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.UndoSnackbarHost
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class NoteEditorDeleteUndoTest {
    @get:Rule
    val compose = createComposeRule()

    private val env = NotesTestEnvironment()

    @After
    fun tearDown() = env.close()

    private var closed by mutableStateOf(false)

    private fun show(id: String?): NoteEditorViewModel {
        val viewModel = NoteEditorViewModel(SavedStateHandle(mapOf("id" to id, "external" to false)), env.repository, env.external, env.settings, env.tasks.environment)
        val export = env.exportViewModel()
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides env.tasks.environment) {
                    Box(Modifier.fillMaxSize()) {
                        if (!closed) NoteEditorScreen(viewModel, export, onBack = { closed = true })
                        UndoSnackbarHost()
                    }
                }
            }
        }
        return viewModel
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun deleteFromTheEditor() {
        compose.onNodeWithTag("note-delete").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-delete-confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note-delete-confirm").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            closed
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun deletingFromTheEditorOffersUndoAndUndoKeepsTheNote() {
        val id = runBlocking { env.repository.create("Groceries", "milk") }
        show(id)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("milk", substring = true).fetchSemanticsNodes().isNotEmpty() }
        deleteFromTheEditor()
        compose.onNodeWithText("“Groceries” deleted").assertExists()
        assertTrue(id in env.tasks.pendingDeletes.ids.value)
        assertNotNull(runBlocking { env.repository.get(id) })
        compose.onNodeWithText("Undo").performClick()
        waitFor { env.tasks.pendingDeletes.ids.value.isEmpty() }
        Thread.sleep(PendingDeletes.UNDO_WINDOW_MS + 500)
        assertNotNull(runBlocking { env.repository.get(id) })
    }

    @Test
    fun deletingFromTheEditorCommitsAfterTheWindow() {
        val id = runBlocking { env.repository.create("Old list", "eggs") }
        show(id)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("eggs", substring = true).fetchSemanticsNodes().isNotEmpty() }
        deleteFromTheEditor()
        waitFor { runBlocking { env.repository.get(id) == null } }
    }

    @Test
    fun unsavedEditsSurviveAnUndoneDelete() {
        show(null)
        compose.onNodeWithTag("note-title").performTextInput("Draft")
        compose.onNodeWithTag("note-source").performTextInput("fresh words")
        deleteFromTheEditor()
        val request = env.tasks.environment.undo.current.value!!
        compose.onNodeWithText("Undo").performClick()
        waitFor { env.tasks.pendingDeletes.ids.value.isEmpty() }
        val stored = runBlocking { env.repository.get(request.id) }!!
        assertEquals("Draft", stored.title)
        assertEquals("fresh words", stored.contentMarkdown)
    }
}
