package com.ftapps.remft.ui.search

import android.os.Looper
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.search.GlobalSearchKind
import com.ftapps.remft.domain.search.SearchRecent
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestSearch
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.shell.AppShell
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.onAllNodesWithTagCount
import com.ftapps.remft.ui.tasks.onAllNodesWithTextCount
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class GlobalSearchPaletteTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()
    private val search = TestSearch(tasks)

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun create(draft: TaskDraft): String = runBlocking { tasks.repository.create(draft) }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun showShell() {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(
                    LocalTasksEnvironment provides tasks.environment,
                    LocalSearchEnvironment provides search.environment,
                ) { AppShell() }
            }
        }
    }

    private fun recents(): List<SearchRecent> = runBlocking { search.recents.recents().first() }

    @Test
    fun phoneButtonOpensFullScreenPaletteWithGroupedResultsAndOpensATask() {
        val id = create(TaskDraft(title = "Renew passport"))
        search.note("n1", "Trip plan", "Pack the passport and the tickets")
        search.file("f1", "passport-scan.pdf")
        showShell()
        compose.onNodeWithTag("global-search-button").performClick()
        waitFor { compose.onAllNodesWithTagCount("global-search-fullscreen") == 1 }
        assertEquals(1, compose.onAllNodesWithTagCount("global-search-intro"))
        compose.onNodeWithTag("global-search-field").performTextInput("passport")
        waitFor { compose.onAllNodesWithTagCount("global-search-task-$id") == 1 }
        assertEquals(1, compose.onAllNodesWithTagCount("global-search-note-n1"))
        assertEquals(1, compose.onAllNodesWithTagCount("global-search-file-f1"))
        assertEquals(1, compose.onAllNodesWithTextCount("Tasks"))
        assertEquals(1, compose.onAllNodesWithTextCount("Pack the passport and the tickets"))
        assertEquals(1, compose.onAllNodesWithTextCount("3 results"))
        compose.onNodeWithTag("global-search-results").performScrollToNode(hasTestTag("global-search-create"))
        compose.onNodeWithTag("global-search-create").assertTextContains("Create task “passport”")
        compose.onNodeWithTag("global-search-results").performScrollToNode(hasTestTag("global-search-task-$id"))

        compose.onNodeWithTag("global-search-task-$id").performClick()
        waitFor { compose.onAllNodesWithTagCount("screen-task") == 1 }
        assertEquals(0, compose.onAllNodesWithTagCount("global-search-fullscreen"))
        waitFor { recents() == listOf(SearchRecent(GlobalSearchKind.Task, id)) }
    }

    @Test
    fun recentsShowWhenTheFieldIsEmpty() {
        val id = create(TaskDraft(title = "Renew passport"))
        search.note("n1", "Trip plan")
        runBlocking {
            search.recents.record(SearchRecent(GlobalSearchKind.Task, id))
            search.recents.record(SearchRecent(GlobalSearchKind.Note, "n1"))
        }
        showShell()
        search.environment.palette.open()
        waitFor { compose.onAllNodesWithTagCount("global-search-note-n1") == 1 }
        assertEquals(1, compose.onAllNodesWithTextCount("Recent"))
        assertEquals(1, compose.onAllNodesWithTagCount("global-search-task-$id"))
        assertEquals(0, compose.onAllNodesWithTagCount("global-search-intro"))

        compose.onNodeWithTag("global-search-note-n1").performClick()
        waitFor { compose.onAllNodesWithTagCount("screen-note-editor") == 1 }
        assertEquals(SearchRecent(GlobalSearchKind.Note, "n1"), recents().first())
    }

    @Test
    fun createRowOpensTheSheetWithTheTitle() {
        showShell()
        search.environment.palette.open()
        waitFor { compose.onAllNodesWithTagCount("global-search-field") == 1 }
        compose.onNodeWithTag("global-search-field").performTextInput("Buy stamps")
        waitFor { compose.onAllNodesWithTagCount("global-search-empty") == 1 }
        compose.onNodeWithTag("global-search-create").performClick()
        waitFor { compose.onAllNodesWithTagCount("task-sheet-title") == 1 }
        compose.onNodeWithTag("task-sheet-title").assertTextContains("Buy stamps")
        assertFalse(search.environment.palette.isOpen.value)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun railEntryOpensTheDialogOnWideScreens() {
        showShell()
        assertEquals(0, compose.onAllNodesWithTagCount("global-search-button"))
        compose.onNodeWithTag("global-search-rail").performClick()
        waitFor { compose.onAllNodesWithTagCount("global-search-dialog") == 1 }
        assertEquals(0, compose.onAllNodesWithTagCount("global-search-fullscreen"))
        assertEquals(1, compose.onAllNodesWithTextCount("Tip: Ctrl+K opens this from anywhere."))
    }

    @Test
    fun ctrlKTogglesThePalette() {
        val requests = GlobalSearchRequests()
        assertTrue(GlobalSearchShortcut.handle(KeyEvent.KEYCODE_K, KeyEvent.ACTION_DOWN, true, 0, requests))
        assertTrue(requests.isOpen.value)
        assertTrue(GlobalSearchShortcut.handle(KeyEvent.KEYCODE_K, KeyEvent.ACTION_DOWN, true, 1, requests))
        assertTrue(requests.isOpen.value)
        assertTrue(GlobalSearchShortcut.handle(KeyEvent.KEYCODE_K, KeyEvent.ACTION_UP, true, 0, requests))
        assertTrue(GlobalSearchShortcut.handle(KeyEvent.KEYCODE_K, KeyEvent.ACTION_DOWN, true, 0, requests))
        assertFalse(requests.isOpen.value)
        assertFalse(GlobalSearchShortcut.handle(KeyEvent.KEYCODE_K, KeyEvent.ACTION_DOWN, false, 0, requests))
        assertFalse(GlobalSearchShortcut.handle(KeyEvent.KEYCODE_J, KeyEvent.ACTION_DOWN, true, 0, requests))
        assertFalse(requests.isOpen.value)
    }
}
