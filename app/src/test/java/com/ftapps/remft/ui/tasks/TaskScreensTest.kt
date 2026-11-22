package com.ftapps.remft.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
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
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class TaskScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            RemftTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { content() }
            }
        }
    }

    private fun create(draft: TaskDraft): String = runBlocking { tasks.repository.create(draft) }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun column(id: String, name: String): String? = tasks.query("SELECT $name FROM tasks WHERE id = '$id'").singleOrNull()?.get(name)

    @Test
    fun detailEditsTitleWithAutosaveAndChangesPriority() {
        val id = create(TaskDraft(title = "Draft title"))
        show { TaskDetailScreen(taskId = id, onBack = {}) }
        waitFor { compose.onAllNodesWithTagCount("task-title") == 1 }
        compose.onNodeWithTag("task-title").performTextReplacement("Final title")
        compose.mainClock.advanceTimeBy(2_000)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        waitFor { column(id, "title") == "Final title" }
        compose.onNodeWithTag("priority-dropdown").performClick()
        compose.onNodeWithTag("priority-dropdown-high").performClick()
        waitFor { column(id, "priority") == "high" }
        compose.onNodeWithTag("urgent-switch").performClick()
        waitFor { column(id, "is_urgent") == "1" && column(id, "requires_acknowledgement") == "1" }
    }

    @Test
    fun detailChecklistAddTickAndRemove() {
        val id = create(TaskDraft(title = "Trip"))
        show(dark = true) { TaskDetailScreen(taskId = id, onBack = {}) }
        waitFor { compose.onAllNodesWithTagCount("checklist-add") == 1 }
        compose.onNodeWithTag("checklist-add").performTextInput("Tickets")
        compose.onNodeWithTag("checklist-add").performImeAction()
        waitFor { tasks.query("SELECT id FROM checklist_items").size == 1 }
        val itemId = tasks.query("SELECT id FROM checklist_items").single()["id"]!!
        waitFor { compose.onAllNodesWithTagCount("checklist-check-$itemId") == 1 }
        compose.onNodeWithTag("checklist-check-$itemId").performClick()
        waitFor { tasks.query("SELECT is_done FROM checklist_items").single()["is_done"] == "1" }
        compose.onNodeWithTag("checklist-menu-$itemId").performClick()
        compose.onNodeWithTag("checklist-remove-$itemId").performClick()
        waitFor { tasks.query("SELECT id FROM checklist_items").isEmpty() }
    }

    @Test
    fun detailCompleteFromTheAppBar() {
        val id = create(TaskDraft(title = "Finish"))
        show { TaskDetailScreen(taskId = id, onBack = {}) }
        waitFor { compose.onAllNodesWithTagCount("task-complete") == 1 }
        compose.onNodeWithTag("task-complete").performClick()
        waitFor { column(id, "status") == "completed" }
    }

    @Test
    fun detailOfAMissingTaskSaysSo() {
        show { TaskDetailScreen(taskId = "01JZZZZZZZZZZZZZZZZZZZZZZZ", onBack = {}) }
        waitFor { compose.onAllNodesWithTextCount("This task no longer exists") == 1 }
    }

    @Test
    fun createSheetStartsMediumUrgentWithAnAutoReminder() {
        var closed = false
        show {
            TaskCreateSheet(onDismiss = { closed = true }, initialDue = LocalDateTime.of(2026, 11, 21, 9, 0))
        }
        waitFor { compose.onAllNodesWithTagCount("task-sheet-title") == 1 }
        compose.onNodeWithText("New task").assertIsDisplayed()
        compose.onNodeWithText("Medium").assertIsDisplayed()
        compose.onNodeWithTag("task-sheet-urgent").assertIsOn()
        compose.onNodeWithTag("task-sheet-create").performClick()
        compose.onNodeWithText("Give the task a title").assertIsDisplayed()
        compose.onNodeWithTag("task-sheet-title").performTextInput("Renew licence")
        compose.onNodeWithTag("task-sheet-create").performClick()
        waitFor { tasks.query("SELECT id FROM tasks").size == 1 }
        val row = tasks.query("SELECT * FROM tasks").single()
        assertEquals("Renew licence", row["title"])
        assertEquals("medium", row["priority"])
        assertEquals("1", row["is_urgent"])
        assertEquals("2026-11-21T03:30:00.000Z", row["due_at"])
        assertEquals("2026-11-21T02:30:00.000Z", row["remind_at"])
        waitFor { closed }
    }

    @Test
    fun inboxCapturesAndPlanPromotesWithADate() {
        show { InboxTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}) }
        compose.onNodeWithTag("inbox-capture").performTextInput("Call the plumber")
        compose.onNodeWithTag("inbox-capture").performImeAction()
        waitFor { tasks.query("SELECT id FROM tasks").size == 1 }
        val row = tasks.query("SELECT * FROM tasks").single()
        val id = row["id"]!!
        assertEquals("inbox", row["status"])
        waitFor { compose.onAllNodesWithTagCount("capture-plan-$id") == 1 }
        compose.onNodeWithTag("capture-plan-$id").performClick()
        waitFor { compose.onAllNodesWithTextCount("Plan task") == 1 }
        compose.onNodeWithTag("task-sheet-due-date").performClick()
        compose.onNodeWithTag("date-picker-ok").performClick()
        compose.onNodeWithTag("task-sheet-create").performClick()
        waitFor { column(id, "status") == "open" }
        assertEquals("2026-11-20T05:30:00.000Z", column(id, "due_at"))
    }

    @Test
    fun inboxSearchFiltersAndConvertMovesToNotes() {
        create(TaskDraft(title = "Garden idea", description = "Plant tulips", status = "inbox"))
        val other = create(TaskDraft(title = "Book club", status = "inbox"))
        show { InboxTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}) }
        waitFor { compose.onAllNodesWithTextCount("Book club") == 1 }
        compose.onNodeWithTag("inbox-search-toggle").performClick()
        compose.onNodeWithTag("inbox-search").performTextInput("tulip")
        waitFor { compose.onAllNodesWithTextCount("Book club") == 0 }
        compose.onNodeWithText("Garden idea").assertIsDisplayed()
        compose.onNodeWithTag("inbox-search-toggle").performClick()
        waitFor { compose.onAllNodesWithTagCount("capture-to-note-$other") == 1 }
        compose.onNodeWithTag("capture-to-note-$other").performClick()
        compose.onNodeWithTag("convert-confirm").performClick()
        waitFor { tasks.query("SELECT title FROM notes").size == 1 }
        assertEquals("Book club", tasks.query("SELECT title FROM notes").single()["title"])
        waitFor { column(other, "id") == null }
    }
}
