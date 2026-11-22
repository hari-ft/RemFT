package com.ftapps.remft.ui.notes

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.domain.notes.ExternalDocHandle
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.UndoSnackbarHost
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun show(dark: Boolean = false, viewModel: NotesViewModel = NotesViewModel(env.repository, env.settings, env.tasks.environment)): NotesViewModel {
        compose.setContent {
            RemftTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalTasksEnvironment provides env.tasks.environment) {
                    Box {
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
                        UndoSnackbarHost()
                    }
                }
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
    fun deleteFromMenuShowsTheAppUndoAndUndoBringsItBack() {
        val id = runBlocking { env.repository.create("Doomed", "x") }
        show()
        waitForText("Doomed")
        compose.onNodeWithTag("note-menu-$id").performClick()
        compose.onNodeWithTag("note-delete-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("“Doomed” deleted").assertExists()
        assertEquals(PendingDeletes.UNDO_WINDOW_MS, env.tasks.pendingDeletes.window)
        assertTrue(id in env.tasks.pendingDeletes.ids.value)
        compose.onAllNodes(hasTestTag("note-$id")).assertCountEquals(0)
        compose.onNodeWithText("Undo").performClick()
        waitForText("Doomed")
        assertTrue(env.tasks.pendingDeletes.ids.value.isEmpty())
        runBlocking { assert(env.repository.get(id) != null) }
    }

    @Test
    fun deleteCommitsAfterTheUndoWindow() {
        val id = runBlocking { env.repository.create("Gone soon", "x") }
        show()
        waitForText("Gone soon")
        compose.onNodeWithTag("note-menu-$id").performClick()
        compose.onNodeWithTag("note-delete-$id").performClick()
        assertNotNull(runBlocking { env.repository.get(id) })
        waitUntil { runBlocking { env.repository.get(id) == null } }
        assertEquals(1, env.holder.db.openHelper.readableDatabase.query("SELECT id FROM trash_items").use { it.count })
    }

    @Test
    fun aNoteRestoredFromTheBinAfterTheUndoWindowShowsAgain() {
        val id = runBlocking { env.repository.create("Comes back", "x") }
        val stored = runBlocking { env.repository.get(id)!! }
        show()
        waitForText("Comes back")
        compose.onNodeWithTag("note-menu-$id").performClick()
        compose.onNodeWithTag("note-delete-$id").performClick()
        waitUntil { runBlocking { env.repository.get(id) == null } }
        waitUntil { env.tasks.pendingDeletes.ids.value.isEmpty() }
        runBlocking { env.holder.db.notesDao().insert(stored) }
        waitForText("Comes back")
    }

    @Test
    fun clearingTheListDuringTheUndoWindowKeepsTheUndo() {
        val id = runBlocking { env.repository.create("Pending", "x") }
        val store = ViewModelStore()
        val viewModel = ViewModelProvider.create(store, viewModelFactory { initializer { NotesViewModel(env.repository, env.settings, env.tasks.environment) } })[NotesViewModel::class]
        viewModel.delete(NoteRow(id, "Pending", "", false, false, null, null), "“Pending” deleted")
        store.clear()
        val request = env.tasks.environment.undo.current.value!!
        assertEquals(id, request.id)
        assertTrue(env.tasks.environment.undo.resolve(request, undo = true))
        Thread.sleep(PendingDeletes.UNDO_WINDOW_MS + 500)
        assertNotNull(runBlocking { env.repository.get(id) })
    }

    @Test
    fun retryReloadsTheListAfterALoadFailure() {
        var failing = true
        val flaky = NotesRepository(
            holder = env.holder,
            workspaceId = { if (failing) throw IllegalStateException("not ready") else NotesTestEnvironment.WORKSPACE },
            settings = env.settings,
            attachmentsDir = env.attachments,
            untitled = { "Untitled" },
        )
        runBlocking { env.repository.create("Back again", "x") }
        show(viewModel = NotesViewModel(flaky, env.settings, env.tasks.environment))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        failing = false
        compose.onNodeWithText("Retry").performClick()
        waitForText("Back again")
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

    @Test
    fun systemBackClosesTheOpenSearchBeforeLeavingTheTab() {
        val viewModel = NotesViewModel(env.repository, env.settings, env.tasks.environment)
        var leftTab = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            BackHandler { leftTab++ }
            RemftTheme {
                NotesScreen(
                    viewModel = viewModel,
                    bottomPadding = 0.dp,
                    onOpenSettings = {},
                    onOpenNote = {},
                    onExport = { _, _ -> },
                    onEditExternal = {},
                    onImport = {},
                    onOpenRecent = {},
                    recents = emptyList<ExternalDocHandle>(),
                )
            }
        }
        compose.onNodeWithTag("notes-search-open").performClick()
        compose.onNodeWithTag("notes-search").performTextInput("alp")
        compose.waitForIdle()
        assertEquals("alp", viewModel.query.value.search)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(0, leftTab)
        assertEquals("", viewModel.query.value.search)
        assertEquals(0, compose.onAllNodesWithTag("notes-search").fetchSemanticsNodes().size)
        compose.onNodeWithTag("notes-search-open").assertIsDisplayed()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, leftTab)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(count: Int) {
    assertEquals(count, fetchSemanticsNodes().size)
}
