package com.ftapps.remft.ui.tasks

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.repeat.RepeatFreq
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.SeriesTooDenseException
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class TaskSheetStateTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()
    private val due: Instant = Instant.parse("2026-11-21T04:30:00Z")

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun restorable(content: @Composable () -> Unit): StateRestorationTester {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { content() }
            }
        }
        return tester
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun settle(vm: TaskSheetViewModel) {
        val end = System.currentTimeMillis() + 10_000
        while (vm.state.value == SheetSave.Saving && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun theCreateSheetKeepsItsFieldsAndRepeatAcrossRecreation() {
        var closed = false
        val tester = restorable { TaskCreateSheet(onDismiss = { closed = true }, initialDue = LocalDateTime.of(2026, 11, 21, 10, 0)) }
        waitFor { compose.onAllNodesWithTagCount("task-sheet-title") == 1 }
        compose.onNodeWithTag("task-sheet-title").performTextReplacement("Pay rent")
        compose.onNodeWithTag("task-sheet-description").performTextReplacement("Before the 25th")
        compose.onNodeWithTag("task-sheet-urgent").performScrollTo().performClick()
        compose.onNodeWithTag("task-sheet-repeat").performScrollTo().performClick()
        waitFor { compose.onAllNodesWithTagCount("repeat-preset-monthly") == 1 }
        compose.onNodeWithTag("repeat-preset-monthly").performClick()
        compose.onNodeWithTag("repeat-save").performClick()
        waitFor { compose.onAllNodesWithTagCount("task-sheet-repeat-summary") == 1 }

        tester.emulateSavedInstanceStateRestore()

        waitFor { compose.onAllNodesWithTagCount("task-sheet-title") == 1 }
        compose.onNodeWithTag("task-sheet-title").assertTextContains("Pay rent")
        compose.onNodeWithTag("task-sheet-repeat-summary", useUnmergedTree = true).performScrollTo().assertTextEquals("Every month on the 21st · no end")
        compose.onNodeWithTag("task-sheet-create").performClick()
        waitFor { closed && tasks.query("SELECT id FROM task_series").size == 1 }
        val first = tasks.query("SELECT * FROM tasks ORDER BY occurrence_date").first()
        assertEquals("Pay rent", first["title"])
        assertEquals("Before the 25th", first["description"])
        assertEquals("0", first["is_urgent"])
        assertEquals("2026-11-21T04:30:00.000Z", first["due_at"])
    }

    @Test
    fun theInboxPlanSheetComesBackAfterRecreation() {
        val id = runBlocking { tasks.repository.create(TaskDraft(title = "Call the plumber", status = "inbox")) }
        val tester = restorable { InboxTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}) }
        waitFor { compose.onAllNodesWithTagCount("capture-plan-$id") == 1 }
        compose.onNodeWithTag("capture-plan-$id").performClick()
        waitFor { compose.onAllNodesWithTagCount("task-sheet-title") == 1 }
        compose.onNodeWithTag("task-sheet-title").performTextReplacement("Call the plumber today")

        tester.emulateSavedInstanceStateRestore()

        waitFor { compose.onAllNodesWithTagCount("task-sheet-title") == 1 }
        compose.onNodeWithTag("task-sheet-title").assertTextContains("Call the plumber today")
    }

    @Test
    fun aRepeatThatCannotBeBuiltSavesNothingAndSaysWhy() {
        val vm = TaskSheetViewModel(tasks.environment) { _, _, _ -> throw SeriesTooDenseException() }
        vm.create(TaskDraft(title = "Stretch", dueAt = due), RepeatRule(RepeatFreq.Daily), due)
        settle(vm)
        assertEquals(SheetSave.TooDense, vm.state.value)
        assertTrue(tasks.query("SELECT id FROM tasks").isEmpty())
        assertTrue(tasks.query("SELECT id FROM task_series").isEmpty())
    }

    @Test
    fun aRepeatingTaskIsCreatedTogetherWithItsSeries() {
        val vm = TaskSheetViewModel(tasks.environment)
        vm.create(TaskDraft(title = "Stretch", dueAt = due), RepeatRule(RepeatFreq.Weekly), due)
        settle(vm)
        assertEquals(SheetSave.Saved, vm.state.value)
        assertEquals(1, tasks.query("SELECT id FROM task_series").size)
        assertTrue(tasks.query("SELECT id FROM tasks WHERE series_id IS NOT NULL").size > 1)
    }
}
