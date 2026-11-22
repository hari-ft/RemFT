package com.ftapps.remft.ui.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.notes.editor.NoteEditorScreen
import com.ftapps.remft.ui.notes.editor.NoteEditorViewModel
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteEditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val env = NotesTestEnvironment()

    @After
    fun tearDown() = env.close()

    private fun show(id: String?): NoteEditorViewModel {
        val viewModel = NoteEditorViewModel(SavedStateHandle(mapOf("id" to id, "external" to false)), env.repository, env.external, env.settings, env.tasks.environment)
        val export = env.exportViewModel()
        compose.setContent { RemftTheme { NoteEditorScreen(viewModel, export, onBack = {}) } }
        return viewModel
    }

    @Test
    fun typingAutosavesAndShowsSaved() {
        show(null)
        compose.onNodeWithTag("note-title").performTextInput("Shopping")
        compose.onNodeWithTag("note-source").performTextInput("- milk")
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() }
        val notes = runBlocking { env.repository.observeList().first() }
        assertEquals(1, notes.size)
        assertEquals("Shopping", notes[0].title)
        assertEquals("- milk", notes[0].contentMarkdown)
    }

    @Test
    fun enterContinuesTheList() {
        val viewModel = show(null)
        compose.onNodeWithTag("note-source").performTextInput("1. one")
        compose.onNodeWithTag("note-source").performTextInput("\n")
        compose.waitForIdle()
        assertEquals("1. one\n2. ", viewModel.source.text)
    }

    @Test
    fun toolbarAppliesFormatting() {
        val viewModel = show(null)
        compose.onNodeWithTag("note-source").performTextInput("word")
        compose.onNodeWithTag("action-h1").performScrollTo().performClick()
        assertEquals("# word", viewModel.source.text)
    }

    @Test
    fun longPressOnAToolbarButtonShowsItsNameWithoutFormatting() {
        val viewModel = show(null)
        compose.onNodeWithTag("note-source").performTextInput("word")
        assertEquals(0, compose.onAllNodesWithText("Heading 1").fetchSemanticsNodes().size)
        compose.onNodeWithTag("action-h1").performScrollTo().performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Heading 1").fetchSemanticsNodes().size == 1 }
        assertEquals("word", viewModel.source.text)
    }

    @Test
    fun typingWithTheFindBarOpenKeepsTheCursorWhereItIs() {
        val viewModel = show(null)
        compose.onNodeWithTag("note-source").performTextInput("cat dog cat")
        compose.onNodeWithTag("open-find").performClick()
        compose.onNodeWithTag("find-field").performTextInput("cat")
        compose.waitForIdle()
        assertEquals(TextRange(0, 3), viewModel.source.selection)
        compose.runOnIdle {
            viewModel.select(0, 0)
            viewModel.insertText("A")
        }
        compose.waitForIdle()
        assertEquals("Acat dog cat", viewModel.source.text)
        assertEquals(TextRange(1), viewModel.source.selection)
    }

    @Test
    fun replacingTheLastMatchKeepsTheCounterInRange() {
        val viewModel = show(null)
        compose.onNodeWithTag("note-source").performTextInput("cat cat cat")
        compose.onNodeWithTag("open-find").performClick()
        compose.onNodeWithTag("find-field").performTextInput("cat")
        compose.onNodeWithTag("find-next").performClick()
        compose.onNodeWithTag("find-next").performClick()
        compose.onNodeWithTag("find-count").assertTextEquals("3 of 3")
        compose.onNodeWithTag("replace-field").performTextInput("dog")
        compose.onNodeWithTag("replace-one").performClick()
        compose.waitForIdle()
        assertEquals("cat cat dog", viewModel.source.text)
        compose.onNodeWithTag("find-count").assertTextEquals("1 of 2")
        assertEquals(TextRange(0, 3), viewModel.source.selection)
    }

    @Test
    fun existingNoteOpensAndRendersInReadingMode() {
        val id = runBlocking {
            env.repository.create("Guide", "# Intro\n\n> [!TIP]\n> Use it\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\n```kotlin\nval x = 1\n```\n\n<script>alert(1)</script>\n\n![pic](https://example.com/a.png)")
        }
        show(id)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mode-reading").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reading-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reading-title").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Intro").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("alert-tip").assertExists()
        compose.onNodeWithTag("table").assertExists()
        compose.onNodeWithText("Tip").assertExists()
    }

    @Test
    fun missingNoteShowsNotFound() {
        show("01MISSING00000000000000000")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-not-found").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun pinAndArchiveWriteThrough() {
        val id = runBlocking { env.repository.create("Flags", "x") }
        show(id)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note-pin").performClick()
        compose.onNodeWithTag("note-archive").performClick()
        compose.waitUntil(5_000) { runBlocking { env.repository.get(id)?.let { it.isPinned && it.isArchived } == true } }
        assertTrue(runBlocking { env.repository.get(id)!!.isPinned })
    }
}
