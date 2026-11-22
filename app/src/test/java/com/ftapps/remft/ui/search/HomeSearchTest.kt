package com.ftapps.remft.ui.search

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.HomeTasksScreen
import com.ftapps.remft.ui.tasks.InboxTasksScreen
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.onAllNodesWithTagCount
import com.ftapps.remft.ui.tasks.onAllNodesWithTextCount
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class HomeSearchTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun create(draft: TaskDraft): String = runBlocking { tasks.repository.create(draft) }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun showHome() {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) {
                    HomeTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}, onOpenTask = {})
                }
            }
        }
    }

    @Test
    fun searchReplacesTheCalendarWithRankedTasksAndCaptures() {
        val title = create(TaskDraft(title = "Pay rent", dueAt = Instant.parse("2026-11-22T04:30:00Z")))
        val body = create(TaskDraft(title = "Call bank", description = "Ask for the rent receipt for October"))
        val capture = create(TaskDraft(title = "Rent ideas", status = "inbox"))
        create(TaskDraft(title = "Groceries"))
        showHome()
        waitFor { compose.onAllNodesWithTextCount("Groceries") == 1 }
        compose.onNodeWithTag("home-search-toggle").performClick()
        compose.onNodeWithTag("home-search").performTextInput("rent")
        waitFor { compose.onAllNodesWithTagCount("home-search-result-$body") == 1 }
        assertEquals(1, compose.onAllNodesWithTagCount("home-search-result-$title"))
        assertEquals(1, compose.onAllNodesWithTagCount("home-search-result-$capture"))
        assertEquals(0, compose.onAllNodesWithTextCount("Groceries"))
        assertEquals(1, compose.onAllNodesWithTextCount("Ask for the rent receipt for October"))
        assertEquals(0, compose.onAllNodesWithTagCount("home-sort"))

        compose.onNodeWithTag("home-search-toggle").performClick()
        waitFor { compose.onAllNodesWithTextCount("Groceries") == 1 }
        assertEquals(0, compose.onAllNodesWithTagCount("home-search-result-$title"))
    }

    @Test
    fun searchWithNoMatchSaysSo() {
        create(TaskDraft(title = "Groceries"))
        showHome()
        compose.onNodeWithTag("home-search-toggle").performClick()
        compose.onNodeWithTag("home-search").performTextInput("zebra")
        waitFor { compose.onAllNodesWithTagCount("home-search-empty") == 1 }
        assertEquals(1, compose.onAllNodesWithTextCount("Nothing matches “zebra”."))
    }

    @Test
    fun inboxSearchFoldsAccents() {
        create(TaskDraft(title = "Café receipts", status = "inbox"))
        create(TaskDraft(title = "Shoes", status = "inbox"))
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) {
                    InboxTasksScreen(bottomPadding = 0.dp, onOpenSettings = {})
                }
            }
        }
        waitFor { compose.onAllNodesWithTextCount("Shoes") == 1 }
        compose.onNodeWithTag("inbox-search-toggle").performClick()
        compose.onNodeWithTag("inbox-search").performTextInput("CAFE")
        waitFor { compose.onAllNodesWithTextCount("Shoes") == 0 }
        assertEquals(1, compose.onAllNodesWithTextCount("Café receipts"))
    }
}
