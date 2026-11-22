package com.ftapps.remft.ui.quickadd

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.HomeRequest
import com.ftapps.remft.ui.tasks.HomeTasksScreen
import com.ftapps.remft.ui.tasks.InboxTasksScreen
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.onAllNodesWithTagCount
import com.ftapps.remft.ui.tasks.onAllNodesWithTextCount
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class QuickAddScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun show(content: @Composable () -> Unit = { HomeTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}, onOpenTask = {}) }) {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { content() }
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun onlyTask(): Map<String, String?> {
        waitFor { tasks.query("SELECT id FROM tasks").size == 1 }
        return tasks.query("SELECT * FROM tasks").single()
    }

    @Test
    fun typedScheduleShowsChipsAndCreatesTheTaskWithoutAsking() {
        show()
        compose.onNodeWithTag("home-quick-add").performTextInput("tomorrow 9am pay rent p1")
        waitFor { compose.onAllNodesWithTagCount("home-quick-add-token-due") == 1 }
        compose.onNodeWithTag("home-quick-add-token-due").assertTextContains("21 Nov", substring = true)
        compose.onNodeWithTag("home-quick-add-token-priority").assertTextContains("Priority: High")
        compose.onNodeWithTag("home-quick-add").performImeAction()
        val row = onlyTask()
        assertEquals("pay rent", row["title"])
        assertEquals("high", row["priority"])
        assertEquals("1", row["is_urgent"])
        assertEquals("open", row["status"])
        assertEquals("2026-11-21T03:30:00.000Z", row["due_at"])
        assertEquals("2026-11-21T02:30:00.000Z", row["remind_at"])
        assertEquals(0, compose.onAllNodesWithTagCount("quick-add-deadline-dialog"))
        assertEquals(0, compose.onAllNodesWithTagCount("home-quick-add-token-due"))
    }

    @Test
    fun repeatShowsARepeatChip() {
        show()
        compose.onNodeWithTag("home-quick-add").performTextInput("water plants every monday")
        waitFor { compose.onAllNodesWithTagCount("home-quick-add-token-repeat") == 1 }
        compose.onNodeWithTag("home-quick-add-token-repeat").assertTextContains("Monday", substring = true)
    }

    @Test
    fun dismissingAChipKeepsItsWordsInTheTitleAndSkipLeavesNoDate() {
        show()
        compose.onNodeWithTag("home-quick-add").performTextInput("call mom p1")
        waitFor { compose.onAllNodesWithTagCount("home-quick-add-token-priority") == 1 }
        compose.onNodeWithTag("home-quick-add-token-priority").performClick()
        waitFor { compose.onAllNodesWithTagCount("home-quick-add-token-priority") == 0 }
        compose.onNodeWithTag("home-quick-add-submit").performClick()
        waitFor { compose.onAllNodesWithTagCount("quick-add-deadline-dialog") == 1 }
        compose.onNodeWithText("Deadline").assertIsDisplayed()
        compose.onNodeWithTag("quick-add-deadline-skip").performClick()
        val row = onlyTask()
        assertEquals("call mom p1", row["title"])
        assertEquals("medium", row["priority"])
        assertNull(row["due_at"])
        assertNull(row["remind_at"])
    }

    @Test
    fun deadlineSetUsesThePickedDate() {
        show()
        compose.onNodeWithTag("home-quick-add").performTextInput("Buy milk")
        compose.onNodeWithTag("home-quick-add").performImeAction()
        waitFor { compose.onAllNodesWithTagCount("quick-add-deadline-dialog") == 1 }
        compose.onNodeWithTag("quick-add-deadline-date").performClick()
        compose.onNodeWithTag("date-picker-ok").performClick()
        compose.onNodeWithTag("quick-add-deadline-set").performClick()
        val row = onlyTask()
        assertEquals("Buy milk", row["title"])
        assertEquals("2026-11-20T05:30:00.000Z", row["due_at"])
        assertNull(row["remind_at"])
    }

    @Test
    fun selectedDayPrefillsTheDeadline() {
        tasks.environment.homeRequests.post(HomeRequest.SelectDay(LocalDate.of(2026, 11, 24)))
        show()
        waitFor { compose.onAllNodesWithTextCount("Add for 24/11/2026") == 1 }
        compose.onNodeWithTag("home-quick-add").performTextInput("Buy bread")
        compose.onNodeWithTag("home-quick-add").performImeAction()
        waitFor { compose.onAllNodesWithTagCount("quick-add-deadline-dialog") == 1 }
        compose.onNodeWithTag("quick-add-deadline-set").performClick()
        val row = onlyTask()
        assertEquals("2026-11-24T05:30:00.000Z", row["due_at"])
        assertEquals("2026-11-24T04:30:00.000Z", row["remind_at"])
    }

    @Test
    fun helpSheetListsTheGrammar() {
        show()
        compose.onNodeWithTag("home-quick-add-help").performClick()
        waitFor { compose.onAllNodesWithTagCount("quick-add-help-sheet") == 1 }
        compose.onNodeWithText("Not what you meant?").assertExists()
        compose.onNodeWithText("p1 high · p2 medium · p3 low · p4 none").assertExists()
    }

    @Test
    fun inboxCaptureWithADateWordStaysACaptureInTheInbox() {
        show { InboxTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}) }
        compose.onNodeWithTag("inbox-capture").performTextInput("Sample idea for weekend")
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTagCount("inbox-capture-token-due"))
        compose.onNodeWithTag("inbox-capture").performImeAction()
        val row = onlyTask()
        assertEquals("Sample idea for weekend", row["title"])
        assertEquals("inbox", row["status"])
        assertEquals("none", row["priority"])
        assertEquals("0", row["is_urgent"])
        assertNull(row["due_at"])
        assertNull(row["remind_at"])
        waitFor { compose.onAllNodesWithTextCount("Sample idea for weekend") == 1 }
    }

    @Test
    fun inboxCaptureWithoutADateStaysACapture() {
        show { InboxTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}) }
        compose.onNodeWithTag("inbox-capture").performTextInput("Call the plumber !high")
        compose.onNodeWithTag("inbox-capture").performImeAction()
        val row = onlyTask()
        assertEquals("Call the plumber", row["title"])
        assertEquals("inbox", row["status"])
        assertEquals("high", row["priority"])
        assertEquals("0", row["is_urgent"])
        assertNull(row["due_at"])
    }

    @Test
    fun aDismissedChipStaysDismissedAfterRecreation() {
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        tester.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) {
                    HomeTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}, onOpenTask = {})
                }
            }
        }
        compose.onNodeWithTag("home-quick-add").performTextInput("read the paper tomorrow")
        waitFor { compose.onAllNodesWithTagCount("home-quick-add-token-due") == 1 }
        compose.onNodeWithTag("home-quick-add-token-due").performClick()
        waitFor { compose.onAllNodesWithTagCount("home-quick-add-token-due") == 0 }
        tester.emulateSavedInstanceStateRestore()
        waitFor { compose.onAllNodesWithTextCount("read the paper tomorrow") >= 1 }
        assertEquals(0, compose.onAllNodesWithTagCount("home-quick-add-token-due"))
        compose.onNodeWithTag("home-quick-add").performImeAction()
        waitFor { compose.onAllNodesWithTagCount("quick-add-deadline-skip") == 1 }
        compose.onNodeWithTag("quick-add-deadline-skip").performClick()
        val row = onlyTask()
        assertEquals("read the paper tomorrow", row["title"])
        assertNull(row["due_at"])
    }
}
