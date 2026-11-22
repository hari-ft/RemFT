package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.FakeHealth
import com.ftapps.remft.testing.TestAlarms
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import android.os.Looper
import org.junit.After
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assert

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
                    Box {
                        HomeTasksScreen(bottomPadding = androidx.compose.ui.unit.Dp(0f), onOpenSettings = {}, onOpenTask = {})
                        UndoSnackbarHost(Modifier.align(Alignment.BottomCenter))
                    }
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
        compose.mainClock.autoAdvance = true
        waitFor { tasks.query("SELECT id FROM trash_items").size == 1 }
        assertEquals(0, tasks.query("SELECT id FROM tasks").size)
        waitFor { compose.onAllNodesWithTextCount("Undo") == 0 }
    }

    @Test
    fun aTaskRestoredFromTheBinAfterTheUndoWindowShowsOnHomeAgain() {
        val id = create(TaskDraft(title = "Old errand"))
        val bin = RecycleBin({ tasks.holder.db }, tasks.settings, tasks.attachments, { runBlocking { tasks.identity.resolve().workspaceId } })
        show()
        waitFor { compose.onAllNodesWithTextCount("Old errand") == 1 }
        compose.onNodeWithTag("swipe-$id").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-delete-$id").performClick()
        waitFor { tasks.query("SELECT id FROM trash_items").size == 1 }
        waitFor { !tasks.pendingDeletes.isPending(id) }
        compose.onNodeWithTag("task-row-$id").assertDoesNotExist()
        val trashId = tasks.query("SELECT id FROM trash_items").single()["id"]!!
        assertTrue(runBlocking { bin.restore(trashId) })
        waitFor { compose.onAllNodesWithTextCount("Old errand") == 1 }
    }

    @Test
    fun headerShowsTheDateAndTodaysCount() {
        create(TaskDraft(title = "Late report", dueAt = Instant.parse("2026-11-18T04:30:00Z")))
        create(TaskDraft(title = "Lunch", dueAt = Instant.parse("2026-11-20T08:00:00Z")))
        create(TaskDraft(title = "Dentist", dueAt = Instant.parse("2026-11-21T05:00:00Z")))
        show()
        waitFor { compose.onAllNodesWithTextCount("2 things today") == 1 }
        compose.onNodeWithTag("home-date").assertTextContains("Friday", substring = true)
        compose.onNodeWithTag("name-mark").assertIsDisplayed()
    }

    @Test
    fun headlineCountsOnlyOpenDueTasksAndIgnoresTheSelectedDay() {
        create(TaskDraft(title = "Late report", dueAt = Instant.parse("2026-11-18T04:30:00Z")))
        create(TaskDraft(title = "Lunch", dueAt = Instant.parse("2026-11-20T08:00:00Z")))
        val done = create(TaskDraft(title = "Gym", dueAt = Instant.parse("2026-11-20T02:00:00Z")))
        create(TaskDraft(title = "Dentist", dueAt = Instant.parse("2026-11-21T05:00:00Z")))
        runBlocking { tasks.repository.setCompleted(done, true) }
        show()
        waitFor { compose.onAllNodesWithTextCount("Gym") == 1 }
        waitFor { compose.onAllNodesWithTextCount("2 things today") == 1 }
        compose.onNodeWithTag("calendar-day-2026-11-20").performClick()
        waitFor { compose.onAllNodes(hasText("Selected day", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2 things today").assertIsDisplayed()
        compose.onNodeWithTag("calendar-day-2026-11-21").performClick()
        waitFor { compose.onAllNodesWithTextCount("Selected day · 1") == 1 }
        compose.onNodeWithText("2 things today").assertIsDisplayed()
    }

    @Test
    fun emptyDayHeadlineAndWeekStripExpandsToTheMonth() {
        show()
        waitFor { compose.onAllNodesWithTextCount("Nothing due today") == 1 }
        compose.onNodeWithTag("week-strip").assertIsDisplayed()
        compose.onNodeWithTag("month-calendar").assertDoesNotExist()
        compose.onNodeWithTag("calendar-expand").performClick()
        compose.onNodeWithTag("month-calendar").assertIsDisplayed()
        compose.onNodeWithTag("week-strip").assertDoesNotExist()
        compose.onNodeWithTag("calendar-expand").performClick()
        compose.onNodeWithTag("week-strip").assertIsDisplayed()
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

    @Test
    fun quickAddComesFirstThenWarningsThenTheUrgentCardAndTasks() = runBlocking {
        val alarms = TestAlarms(health = FakeHealth(FakeHealth.AllAllowed.copy(notifications = false)))
        try {
            val now = alarms.clock.instant()
            alarms.tasks.repository.create(TaskDraft(title = "Call the plumber", remindAt = now.minus(Duration.ofHours(2)), isUrgent = true))
            alarms.tasks.repository.create(TaskDraft(title = "Pay the rent", remindAt = now.plus(Duration.ofHours(1)), isUrgent = true))
            alarms.env.ring.reload()
            compose.setContent {
                RemftTheme {
                    CompositionLocalProvider(
                        LocalTasksEnvironment provides alarms.tasks.environment,
                        LocalAlarmEnvironment provides alarms.env,
                    ) {
                        HomeTasksScreen(bottomPadding = androidx.compose.ui.unit.Dp(0f), onOpenSettings = {}, onOpenTask = {})
                    }
                }
            }
            waitFor {
                listOf("alarm-banner", "missed-alarm-card", "urgent-alarm-card", "group-header-NoDate-null")
                    .all { compose.onAllNodesWithTagCount(it) == 1 }
            }
            val order = listOf(
                "home-header",
                "home-quick-add",
                "home-fab",
                "alarm-banner",
                "missed-alarm-card",
                "urgent-alarm-card",
                "week-strip",
                "group-header-NoDate-null",
            ).map { it to compose.onNodeWithTag(it).getBoundsInRoot() }
            order.zipWithNext().forEach { (above, below) ->
                if (above.first == "home-quick-add") {
                    assertTrue("home-fab sits beside the quick add", below.second.top < above.second.bottom)
                } else {
                    assertTrue("${above.first} should sit above ${below.first}", above.second.bottom <= below.second.top)
                }
            }
            val fab = compose.onNodeWithTag("home-fab").getBoundsInRoot()
            val quickAdd = compose.onNodeWithTag("home-quick-add").getBoundsInRoot()
            assertTrue(fab.left >= quickAdd.right)
            assertTrue(quickAdd.bottom <= compose.onNodeWithTag("alarm-banner").getBoundsInRoot().top)
        } finally {
            alarms.close()
        }
    }

    @Test
    fun whileSearchingTheBannersMakeWayForTheResults() = runBlocking {
        val alarms = TestAlarms(health = FakeHealth(FakeHealth.AllAllowed.copy(notifications = false)))
        try {
            val now = alarms.clock.instant()
            alarms.tasks.repository.create(TaskDraft(title = "Call the plumber", remindAt = now.minus(Duration.ofHours(2)), isUrgent = true))
            val rent = alarms.tasks.repository.create(TaskDraft(title = "Pay the rent", remindAt = now.plus(Duration.ofHours(1)), isUrgent = true))
            alarms.env.ring.reload()
            compose.setContent {
                RemftTheme {
                    CompositionLocalProvider(
                        LocalTasksEnvironment provides alarms.tasks.environment,
                        LocalAlarmEnvironment provides alarms.env,
                    ) {
                        HomeTasksScreen(bottomPadding = androidx.compose.ui.unit.Dp(0f), onOpenSettings = {}, onOpenTask = {})
                    }
                }
            }
            waitFor { listOf("alarm-banner", "missed-alarm-card", "urgent-alarm-card").all { compose.onAllNodesWithTagCount(it) == 1 } }
            compose.onNodeWithTag("home-search-toggle").performClick()
            compose.onNodeWithTag("home-search").performTextInput("rent")
            waitFor { compose.onAllNodesWithTagCount("home-search-result-$rent") == 1 }
            listOf("alarm-banner", "missed-alarm-card", "urgent-alarm-card").forEach { assertEquals(it, 0, compose.onAllNodesWithTagCount(it)) }
            val field = compose.onNodeWithTag("home-search").getBoundsInRoot()
            val result = compose.onNodeWithTag("home-search-result-$rent").getBoundsInRoot()
            assertTrue(result.top - field.bottom < androidx.compose.ui.unit.Dp(200f))
            compose.onNodeWithTag("home-search-toggle").performClick()
            waitFor { compose.onAllNodesWithTagCount("urgent-alarm-card") == 1 }
        } finally {
            alarms.close()
        }
    }

    @Test
    fun theFunnelIsCalledFilterNotSearch() {
        show()
        compose.onNodeWithTag("home-search-toggle").assert(hasContentDescription("Filter tasks"))
        compose.onAllNodes(hasContentDescription("Search")).assertCountEquals(0)
    }

    @Test
    fun theSortMenuReportsWhichOptionIsSelected() {
        show()
        compose.onNodeWithTag("home-sort").performClick()
        waitFor { compose.onAllNodesWithTagCount("sort-date") == 1 }
        compose.onNodeWithTag("sort-date").assertIsSelected()
        compose.onNodeWithTag("sort-priority").assertIsNotSelected()
        compose.onNodeWithTag("sort-title").assertIsNotSelected()
        compose.onNodeWithTag("sort-reverse").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose.onNodeWithTag("sort-title").performClick()
        compose.onNodeWithTag("home-sort").performClick()
        waitFor { compose.onAllNodesWithTagCount("sort-title") == 1 }
        compose.onNodeWithTag("sort-title").assertIsSelected()
        compose.onNodeWithTag("sort-date").assertIsNotSelected()
    }

    @Test
    fun aPendingDeleteHidesItsUrgentAndMissedAlarmCardsUntilUndo() = runBlocking {
        val alarms = TestAlarms()
        try {
            val now = alarms.clock.instant()
            val missed = alarms.tasks.repository.create(TaskDraft(title = "Call the plumber", remindAt = now.minus(Duration.ofHours(2)), isUrgent = true))
            val gym = alarms.tasks.repository.create(TaskDraft(title = "Gym", remindAt = now.plus(Duration.ofHours(18)), isUrgent = true))
            alarms.env.ring.reload()
            compose.setContent {
                RemftTheme {
                    CompositionLocalProvider(
                        LocalTasksEnvironment provides alarms.tasks.environment,
                        LocalAlarmEnvironment provides alarms.env,
                    ) {
                        HomeTasksScreen(bottomPadding = androidx.compose.ui.unit.Dp(0f), onOpenSettings = {}, onOpenTask = {})
                    }
                }
            }
            waitFor { listOf("missed-alarm-card", "urgent-alarm-card").all { compose.onAllNodesWithTagCount(it) == 1 } }
            alarms.tasks.environment.undo.post(gym, "“Gym” deleted") {}
            alarms.tasks.pendingDeletes.schedule(missed) {}
            waitFor { listOf("missed-alarm-card", "urgent-alarm-card").all { compose.onAllNodesWithTagCount(it) == 0 } }
            alarms.tasks.environment.undo.resolve(alarms.tasks.environment.undo.current.value!!, undo = true)
            alarms.tasks.pendingDeletes.undo(missed)
            waitFor { listOf("missed-alarm-card", "urgent-alarm-card").all { compose.onAllNodesWithTagCount(it) == 1 } }
        } finally {
            alarms.close()
        }
    }

    @Test
    fun aSelectedDayOutsideTheWeekShowsAChipThatClearsIt() {
        create(TaskDraft(title = "Far errand", dueAt = Instant.parse("2026-12-15T04:30:00Z")))
        create(TaskDraft(title = "Near errand", dueAt = Instant.parse("2026-11-21T04:30:00Z")))
        tasks.environment.homeRequests.post(HomeRequest.SelectDay(java.time.LocalDate.of(2026, 12, 15)))
        show()
        waitFor { compose.onAllNodesWithTagCount("week-strip-selected") == 1 }
        compose.onNodeWithTag("week-strip-selected").assertTextContains("Showing", substring = true)
        compose.onNodeWithTag("week-strip-selected").performClick()
        waitFor { compose.onAllNodesWithTagCount("week-strip-selected") == 0 }
        waitFor { compose.onAllNodesWithTextCount("Near errand") == 1 }
    }

    @Test
    fun aSelectedDayInsideTheWeekNeedsNoChip() {
        tasks.environment.homeRequests.post(HomeRequest.SelectDay(java.time.LocalDate.of(2026, 11, 22)))
        show()
        waitFor { compose.onAllNodesWithTagCount("week-strip") == 1 }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTagCount("week-strip-selected"))
    }
}
