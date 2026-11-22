package com.ftapps.remft.ui.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import android.os.Looper
import org.junit.After
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class HomeTasksScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun show(dark: Boolean = false) {
        compose.setContent {
            RemftTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) {
                    HomeTasksScreen(bottomPadding = androidx.compose.ui.unit.Dp(0f), onOpenSettings = {}, onOpenTask = {})
                }
            }
        }
    }

    private fun create(draft: TaskDraft): String = runBlocking { tasks.repository.create(draft) }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun groupsTasksByDayWithCounts() {
        create(TaskDraft(title = "Late report", dueAt = Instant.parse("2026-11-18T04:30:00Z")))
        create(TaskDraft(title = "Someday"))
        create(TaskDraft(title = "Lunch", dueAt = Instant.parse("2026-11-20T08:00:00Z")))
        create(TaskDraft(title = "Dentist", dueAt = Instant.parse("2026-11-21T05:00:00Z")))
        create(TaskDraft(title = "Captured", status = "inbox"))
        show()
        waitFor { compose.onAllNodesWithTextCount("Overdue · 1") == 1 }
        compose.onNodeWithText("No date · 1").assertIsDisplayed()
        compose.onNodeWithText("Today · 1").assertIsDisplayed()
        compose.onNodeWithText("Tomorrow · 1").assertIsDisplayed()
        compose.onNodeWithText("Captured").assertDoesNotExist()
        compose.onNodeWithText("Overdue — 18 Nov, 10:00 am", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun emptyHomeSaysAllCaughtUp() {
        show(dark = true)
        waitFor { compose.onAllNodesWithTextCount("All caught up") == 1 }
    }

    @Test
    fun checkboxCompletesTheTask() {
        val id = create(TaskDraft(title = "Water plants"))
        show()
        waitFor { compose.onAllNodesWithTextCount("Water plants") == 1 }
        compose.onNodeWithTag("task-check-$id").performClick()
        waitFor { tasks.query("SELECT status FROM tasks").single()["status"] == "completed" }
    }

    @Test
    fun swipeRevealsDeleteThenUndoBringsTheRowBackAndTheNextDeleteCommits() {
        val id = create(TaskDraft(title = "Old errand"))
        show()
        waitFor { compose.onAllNodesWithTextCount("Old errand") == 1 }
        compose.onNodeWithTag("swipe-$id").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, tasks.query("SELECT id FROM tasks").size)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("swipe-delete-$id").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("task-row-$id").assertDoesNotExist()
        compose.onNodeWithText("“Old errand” deleted").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        waitFor { compose.onAllNodesWithTextCount("Old errand") == 1 }
        assertEquals(0, tasks.query("SELECT id FROM trash_items").size)

        compose.onNodeWithTag("swipe-$id").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("swipe-delete-$id").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("“Old errand” deleted").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_500)
        compose.mainClock.autoAdvance = true
        waitFor { tasks.query("SELECT id FROM trash_items").size == 1 }
        assertEquals(0, tasks.query("SELECT id FROM tasks").size)
    }

    @Test
    fun sortMenuPersistsTheChoiceAndReverse() {
        create(TaskDraft(title = "A"))
        show()
        compose.onNodeWithTag("home-sort").performClick()
        compose.onNodeWithTag("sort-priority").performClick()
        waitFor { runBlocking { tasks.settings.get("remft_tasks_sort") } == "priority:desc" }
        compose.onNodeWithTag("home-sort").performClick()
        compose.onNodeWithTag("sort-reverse").performClick()
        waitFor { runBlocking { tasks.settings.get("remft_tasks_sort") } == "priority:asc" }
    }

    @Test
    fun calendarSelectsADayAndHidingItPersists() {
        create(TaskDraft(title = "Dentist", dueAt = Instant.parse("2026-11-25T05:00:00Z")))
        show()
        waitFor { compose.onAllNodes(hasContentDescription("25, has tasks")).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("calendar-day-2026-11-25").performClick()
        waitFor { compose.onAllNodesWithTextCount("Selected day · 1") == 1 }
        compose.onNodeWithTag("calendar-day-2026-11-25").performClick()
        waitFor { compose.onAllNodesWithTextCount("Selected day · 1") == 0 }
        compose.onNodeWithTag("toggle-calendar").performClick()
        waitFor { runBlocking { tasks.settings.get("remft_home_calendar_visible") } == "false" }
        compose.onNodeWithTag("month-calendar").assertDoesNotExist()
    }

    @Test
    fun quickAddCreatesAMediumUrgentTask() {
        show()
        compose.onNodeWithTag("home-quick-add").performTextInput("Buy milk")
        compose.onNodeWithTag("home-quick-add").performImeAction()
        waitFor { compose.onAllNodesWithTagCount("quick-add-deadline-skip") == 1 }
        compose.onNodeWithTag("quick-add-deadline-skip").performClick()
        waitFor { tasks.query("SELECT title FROM tasks").size == 1 }
        val row = tasks.query("SELECT * FROM tasks").single()
        assertEquals("Buy milk", row["title"])
        assertEquals("medium", row["priority"])
        assertEquals("1", row["is_urgent"])
    }

    @Test
    fun addLinkRequestOpensTheCreateSheet() {
        tasks.environment.homeRequests.post(HomeRequest.Create)
        show()
        waitFor { compose.onAllNodesWithTagCount("task-sheet-title") == 1 }
    }
}
