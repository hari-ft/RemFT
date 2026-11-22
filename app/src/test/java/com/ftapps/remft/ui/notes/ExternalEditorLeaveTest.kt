package com.ftapps.remft.ui.notes

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.notes.ExternalOpenResult
import com.ftapps.remft.ui.notes.editor.NoteEditorScreen
import com.ftapps.remft.ui.notes.editor.NoteEditorViewModel
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExternalEditorLeaveTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val env = NotesTestEnvironment()
    private val file = File(env.dir, "plan.md").apply { writeText("start") }
    private var backs = 0

    @After
    fun tearDown() = env.close()

    private fun show(): NoteEditorViewModel {
        val opened = runBlocking { env.external.open(Uri.fromFile(file), fresh = true) }
        assertTrue(opened is ExternalOpenResult.Opened)
        val viewModel = NoteEditorViewModel(SavedStateHandle(mapOf("id" to null, "external" to true)), env.repository, env.external, env.settings)
        compose.setContent { RemftTheme { NoteEditorScreen(viewModel, env.exportViewModel(), onBack = { backs++ }) } }
        return viewModel
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun systemBackWithUnsavedChangesAsksAndCancelKeepsEditing() {
        val viewModel = show()
        compose.onNodeWithTag("note-source").performTextInput("more ")
        pressBack()
        assertTrue(exists("leave-dialog"))
        assertEquals(0, backs)
        compose.onNodeWithTag("leave-cancel").performClick()
        compose.waitForIdle()
        assertTrue(!exists("leave-dialog"))
        assertEquals(0, backs)
        assertTrue(viewModel.dirty)
        assertEquals("start", file.readText())
    }

    @Test
    fun theCloseButtonAsksAndDiscardLeavesTheFileAlone() {
        show()
        compose.onNodeWithTag("note-source").performTextInput("more ")
        compose.onNodeWithTag("back").performClick()
        compose.waitForIdle()
        assertTrue(exists("leave-dialog"))
        assertEquals(0, backs)
        compose.onNodeWithTag("leave-discard").performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
        assertEquals("start", file.readText())
    }

    @Test
    fun saveFromTheDialogStoresTheEditsAndThenLeaves() {
        val viewModel = show()
        compose.onNodeWithTag("note-source").performTextInput("more ")
        pressBack()
        compose.onNodeWithTag("leave-save").performClick()
        val end = System.currentTimeMillis() + 10_000
        while (backs == 0 && System.currentTimeMillis() < end) {
            Thread.sleep(20)
            compose.waitForIdle()
        }
        assertEquals(1, backs)
        assertTrue(env.external.session.value?.writable == true)
        assertEquals(viewModel.source.text, file.readText())
        assertTrue(file.readText().contains("more "))
    }

    @Test
    fun aCleanDocumentClosesWithoutAsking() {
        show()
        compose.onNodeWithTag("back").performClick()
        compose.waitForIdle()
        assertTrue(!exists("leave-dialog"))
        assertEquals(1, backs)
    }
}
