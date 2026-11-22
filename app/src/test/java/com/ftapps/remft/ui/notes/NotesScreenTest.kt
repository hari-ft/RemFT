package com.ftapps.remft.ui.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.notes.ExternalDocHandle
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val env = NotesTestEnvironment()
    private var opened: String? = "none"

    @After
    fun tearDown() = env.close()

    private fun show(dark: Boolean = false): NotesViewModel {
        val viewModel = NotesViewModel(env.repository, env.settings)
        compose.setContent {
            RemftTheme(darkTheme = dark) {
                NotesScreen(
                    viewModel = viewModel,
                    bottomPadding = 0.dp,
                    onOpenSettings = {},
                    onOpenNote = { opened = it },
                    onExport = { _, _ -> },
                    onEditExternal = {},
                    onImport = {},
                    onOpenRecent = {},
                    recents = emptyList<ExternalDocHandle>(),
                )
            }
        }
        return viewModel
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun emptyStateShowsWithoutNotes() {
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("notes-empty").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No notes here").assertIsDisplayed()
    }

    @Test
    fun listShowsPinnedFirstAndOpensNotes() {
        val (first, second) = runBlocking {
            val a = env.repository.create("Alpha", "first body")
            val b = env.repository.create("Beta", "second body")
            env.repository.setPinned(a, true)
            a to b
        }
        show()
        waitForText("Alpha")
        val nodes = compose.onAllNodesWithTag("notes-list").fetchSemanticsNodes()
        assertEquals(1, nodes.size)
        val alphaTop = compose.onNodeWithTag("note-$first").fetchSemanticsNode().boundsInRoot.top
        val betaTop = compose.onNodeWithTag("note-$second").fetchSemanticsNode().boundsInRoot.top
        assert(alphaTop < betaTop)
        compose.onNodeWithText("Beta").performClick()
        assertEquals(second, opened)
        compose.onNodeWithTag("new-note").performClick()
        assertNull(opened)
    }

    @Test
    fun archivedChipShowsOnlyArchivedNotes() {
        runBlocking {
            env.repository.create("Live note", "x")
            val old = env.repository.create("Old note", "y")
            env.repository.setArchived(old, true)
        }
        show()
        waitForText("Live note")
        compose.onNodeWithTag("filter-archived").performClick()
        waitForText("Old note")
        compose.onAllNodesWithText("Live note").assertCountEquals(0)
    }

    @Test
    fun deleteFromMenuHidesTheRowAndUndoBringsItBack() {
        val id = runBlocking { env.repository.create("Doomed", "x") }
        show()
        waitForText("Doomed")
        compose.onNodeWithTag("note-menu-$id").performClick()
        compose.onNodeWithTag("note-delete-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("note-$id")).assertCountEquals(0)
        compose.onNodeWithText("Undo").performClick()
        waitForText("Doomed")
        runBlocking { assert(env.repository.get(id) != null) }
    }

    @Test
    fun deleteCommitsAfterTheUndoWindow() {
        val id = runBlocking { env.repository.create("Gone soon", "x") }
        show()
        waitForText("Gone soon")
        compose.onNodeWithTag("note-menu-$id").performClick()
        compose.onNodeWithTag("note-delete-$id").performClick()
        compose.mainClock.advanceTimeBy(4_000)
        compose.waitUntil(10_000) { runBlocking { env.repository.get(id) == null } }
    }

    @Test
    fun aNoteRestoredFromTheBinAfterTheUndoWindowShowsAgain() {
        val id = runBlocking { env.repository.create("Comes back", "x") }
        val stored = runBlocking { env.repository.get(id)!! }
        show()
        waitForText("Comes back")
        compose.onNodeWithTag("note-menu-$id").performClick()
        compose.onNodeWithTag("note-delete-$id").performClick()
        compose.mainClock.advanceTimeBy(4_000)
        compose.waitUntil(10_000) { runBlocking { env.repository.get(id) == null } }
        runBlocking { env.holder.db.notesDao().insert(stored) }
        waitForText("Comes back")
    }

    @Test
    fun clearingTheListDoesNotDeleteARestoredNoteAgain() {
        val id = runBlocking { env.repository.create("Kept", "x") }
        val stored = runBlocking { env.repository.get(id)!! }
        val store = ViewModelStore()
        val viewModel = ViewModelProvider.create(store, viewModelFactory { initializer { NotesViewModel(env.repository, env.settings) } })[NotesViewModel::class]
        viewModel.hide(id)
        viewModel.commitDelete(id)
        waitUntil { runBlocking { env.repository.get(id) == null } }
        runBlocking { env.holder.db.notesDao().insert(stored) }
        store.clear()
        Thread.sleep(1_000)
        assertNotNull(runBlocking { env.repository.get(id) })
    }

    @Test
    fun clearingTheListCommitsADeleteStillAwaitingUndo() {
        val id = runBlocking { env.repository.create("Pending", "x") }
        val store = ViewModelStore()
        val viewModel = ViewModelProvider.create(store, viewModelFactory { initializer { NotesViewModel(env.repository, env.settings) } })[NotesViewModel::class]
        viewModel.hide(id)
        store.clear()
        waitUntil { runBlocking { env.repository.get(id) == null } }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
    }

    @Test
    fun gridModeRendersCardsInDarkTheme() {
        runBlocking {
            env.repository.create("Card", "body")
            env.settings.set("remft_notes_view_mode", "grid")
        }
        show(dark = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("notes-grid").fetchSemanticsNodes().isNotEmpty() }
        waitForText("Card")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(count: Int) {
    assertEquals(count, fetchSemanticsNodes().size)
}
