package com.ftapps.remft.ui.repeat

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.repeat.EditScope
import com.ftapps.remft.domain.repeat.RepeatBasis
import com.ftapps.remft.domain.repeat.RepeatEnd
import com.ftapps.remft.domain.repeat.RepeatFreq
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.WeekdayPick
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.TaskCreateSheet
import com.ftapps.remft.ui.tasks.TaskDetailScreen
import com.ftapps.remft.ui.tasks.onAllNodesWithTagCount
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class RepeatEditorTest {
    @get:org.junit.Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()
    private val anchor: LocalDate = LocalDate.of(2026, 11, 21)
    private val due: Instant = Instant.parse("2026-11-21T04:30:00Z")

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

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun editor(initial: RepeatRule? = null, onSave: (RepeatRule) -> Unit = {}) = show {
        RepeatEditorContent(
            anchor = anchor,
            initial = initial,
            format = DateFormatId.System,
            today = LocalDate.of(2026, 11, 20),
            onCancel = {},
            onSave = onSave,
        )
    }

    private fun sentence(text: String) = compose.onNodeWithTag("repeat-sentence").assertTextEquals(text)

    @Test
    fun presetsChangeTheSentence() {
        var saved: RepeatRule? = null
        editor { saved = it }
        sentence("Every month on the 21st · no end")
        compose.onNodeWithTag("repeat-preset-weekly").performClick()
        sentence("Every week on Saturday · no end")
        compose.onNodeWithTag("repeat-preset-daily").performClick()
        sentence("Every day · no end")
        compose.onNodeWithTag("repeat-preset-yearly").performClick()
        sentence("Every year on 21 November · no end")
        compose.onNodeWithTag("repeat-preset-weekly").performClick()
        compose.onNodeWithTag("repeat-save").assertIsEnabled().performClick()
        assertEquals(RepeatRule(RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("SA"))), saved)
    }

    @Test
    fun quarterlyNamesItsMonthsAndPreviewsFiveDates() {
        var saved: RepeatRule? = null
        editor { saved = it }
        compose.onNodeWithTag("repeat-preset-quarterly").performClick()
        compose.onNodeWithTag("repeat-preset-quarterly").assertIsSelected()
        sentence("Quarterly on the 21st (Feb, May, Aug, Nov) · no end")
        listOf("2026-11-21", "2027-02-21", "2027-05-21", "2027-08-21", "2027-11-21").forEach {
            compose.onNodeWithTag("repeat-preview-$it", useUnmergedTree = true).assertTextEquals(
                LocalDate.parse(it).let { d -> "%02d/%02d/%d".format(d.dayOfMonth, d.monthValue, d.year) },
            )
        }
        compose.onNodeWithTag("repeat-save").performClick()
        assertEquals(listOf(2, 5, 8, 11), saved?.byMonth)
        assertEquals(listOf(21), saved?.byMonthDay)
    }

    @Test
    fun afterCompletionAndAdvancedEnd() {
        var saved: RepeatRule? = null
        editor { saved = it }
        compose.onNodeWithTag("repeat-basis-completion").performClick()
        sentence("1 day after completion · no end")
        compose.onNodeWithTag("repeat-after-interval").performTextReplacement("3")
        sentence("3 days after completion · no end")
        compose.onNodeWithTag("repeat-after-unit-weeks").performClick()
        sentence("3 weeks after completion · no end")
        compose.onNodeWithTag("repeat-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-end-count").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-count").performScrollTo().performTextReplacement("4")
        compose.onNodeWithTag("repeat-save").performClick()
        assertEquals(RepeatRule(RepeatFreq.Weekly, interval = 3, end = RepeatEnd.Count(4), basis = RepeatBasis.Completion), saved)
    }

    @Test
    fun advancedWeekdayChipsAndInterval() {
        var saved: RepeatRule? = null
        editor(RepeatRule(RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("SA")))) { saved = it }
        compose.onNodeWithTag("repeat-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-interval").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("repeat-weekday-MO").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-save").performClick()
        assertEquals(RepeatRule(RepeatFreq.Weekly, interval = 2, byWeekday = listOf(WeekdayPick("MO"), WeekdayPick("SA"))), saved)
    }

    @Test
    fun scopeDialogPreselectsByEditKind() {
        var picked: EditScope? = null
        show(dark = true) { SeriesScopeDialog(isDateEdit = true, onDismiss = {}, onApply = { picked = it }) }
        compose.onNodeWithTag("scope-this").assertIsSelected()
        compose.onNodeWithTag("scope-apply").performClick()
        assertEquals(EditScope.This, picked)
    }

    private fun startSeries(): Pair<String, String> = runBlocking {
        val id = tasks.repository.create(TaskDraft(title = "Water the plants", dueAt = due, priority = "high"))
        id to tasks.series.start(id, RepeatRule(RepeatFreq.Weekly), due)!!
    }

    private fun occurrenceIds(seriesId: String): List<String> =
        tasks.query("SELECT id FROM tasks WHERE series_id = '$seriesId' ORDER BY occurrence_date").map { it["id"]!! }

    @Test
    fun detailEditOnAnOccurrenceAsksForTheScope() {
        val (_, seriesId) = startSeries()
        val target = occurrenceIds(seriesId)[2]
        show { TaskDetailScreen(taskId = target, onBack = {}) }
        waitFor { compose.onAllNodesWithTagCount("repeat-summary") == 1 }
        compose.onNodeWithTag("repeat-summary", useUnmergedTree = true).assertTextEquals("Every week on Saturday · no end")
        compose.onNodeWithTag("priority-dropdown").performClick()
        compose.onNodeWithTag("priority-dropdown-low").performClick()
        waitFor { compose.onAllNodesWithTagCount("series-scope-dialog") == 1 }
        compose.onNodeWithTag("scope-future").assertIsSelected()
        compose.onNodeWithTag("scope-all").performClick()
        compose.onNodeWithTag("scope-apply").performClick()
        waitFor { tasks.query("SELECT id FROM tasks WHERE priority = 'low'").size == occurrenceIds(seriesId).size }
        assertEquals(1, tasks.query("SELECT id FROM task_series").size)
    }

    @Test
    fun deletingAnOccurrenceAsksAndThisAndFutureStopsTheSeries() {
        val (_, seriesId) = startSeries()
        val ids = occurrenceIds(seriesId)
        var back = false
        show { TaskDetailScreen(taskId = ids[3], onBack = { back = true }) }
        waitFor { compose.onAllNodesWithTagCount("task-delete") == 1 }
        compose.onNodeWithTag("task-delete").performClick()
        waitFor { compose.onAllNodesWithTagCount("occurrence-delete-scope") == 1 }
        compose.onNodeWithTag("delete-scope-future").performClick()
        waitFor { back && tasks.query("SELECT id FROM task_series").isEmpty() }
        assertEquals(3, tasks.query("SELECT id FROM tasks").size)
    }

    @Test
    fun turningRepeatOnInDetailCreatesTheSeries() {
        val id = runBlocking { tasks.repository.create(TaskDraft(title = "Stretch", dueAt = due)) }
        show { TaskDetailScreen(taskId = id, onBack = {}) }
        waitFor { compose.onAllNodesWithTagCount("repeat-switch") == 1 }
        compose.onNodeWithTag("repeat-switch").performScrollTo().performClick()
        waitFor { compose.onAllNodesWithTagCount("repeat-preset-daily") == 1 }
        compose.onNodeWithTag("repeat-preset-daily").performClick()
        compose.onNodeWithTag("repeat-save").performClick()
        waitFor { tasks.query("SELECT id FROM task_series").size == 1 }
        waitFor { tasks.query("SELECT id FROM tasks").size == 365 }
        waitFor { compose.onAllNodesWithTagCount("repeat-summary") == 1 }
    }

    @Test
    fun createSheetRepeatCreatesASeriesForTheNewTask() {
        var closed = false
        show { TaskCreateSheet(onDismiss = { closed = true }, initialDue = LocalDateTime.of(2026, 11, 21, 10, 0)) }
        waitFor { compose.onAllNodesWithTagCount("task-sheet-title") == 1 }
        compose.onNodeWithTag("task-sheet-title").performTextReplacement("Pay rent")
        compose.onNodeWithTag("task-sheet-repeat").performScrollTo().performClick()
        waitFor { compose.onAllNodesWithTagCount("repeat-preset-monthly") == 1 }
        compose.onNodeWithTag("repeat-preset-monthly").performClick()
        compose.onNodeWithTag("repeat-save").performClick()
        waitFor { compose.onAllNodesWithTagCount("task-sheet-repeat-summary") == 1 }
        compose.onNodeWithTag("task-sheet-repeat-summary", useUnmergedTree = true).assertTextEquals("Every month on the 21st · no end")
        compose.onNodeWithTag("task-sheet-create").performClick()
        waitFor { closed && tasks.query("SELECT id FROM task_series").size == 1 }
        val rows = tasks.query("SELECT * FROM tasks ORDER BY occurrence_date")
        assertTrue(rows.size in 12..13)
        assertTrue(rows.all { it["title"] == "Pay rent" && it["occurrence_date"]!!.endsWith("-21") })
    }

    @Test
    fun typingARepeatingTitleWaitsForFocusLossAndCancelKeepsTheEdit() {
        val (_, seriesId) = startSeries()
        val target = occurrenceIds(seriesId)[1]
        show { TaskDetailScreen(taskId = target, onBack = {}) }
        waitFor { compose.onAllNodesWithTagCount("task-title") == 1 }
        compose.onNodeWithTag("task-title").performClick()
        compose.onNodeWithTag("task-title").performTextReplacement("Water the ferns")
        compose.mainClock.advanceTimeBy(3_000)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTagCount("series-scope-dialog"))
        assertEquals(0, tasks.query("SELECT id FROM tasks WHERE title = 'Water the ferns'").size)

        compose.onNodeWithTag("task-add-description").performScrollTo().performClick()
        waitFor { compose.onAllNodesWithTagCount("series-scope-dialog") == 1 }
        compose.onNodeWithTag("scope-cancel").performClick()
        waitFor { compose.onAllNodesWithTagCount("series-scope-dialog") == 0 }
        compose.onNodeWithTag("task-title").assertTextEquals("Water the ferns")
        assertEquals(0, tasks.query("SELECT id FROM tasks WHERE title = 'Water the ferns'").size)

        compose.onNodeWithTag("task-title").performClick()
        compose.onNodeWithTag("task-description").performClick()
        waitFor { compose.onAllNodesWithTagCount("series-scope-dialog") == 1 }
        compose.onNodeWithTag("scope-all").performClick()
        compose.onNodeWithTag("scope-apply").performClick()
        waitFor { tasks.query("SELECT id FROM tasks WHERE title = 'Water the ferns'").size == occurrenceIds(seriesId).size }
    }

    @Test
    fun anInvalidIntervalOrCountDisablesSaveInsteadOfSavingTheOldNumber() {
        editor(RepeatRule(RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("SA"))))
        compose.onNodeWithTag("repeat-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-interval").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("repeat-save").assertIsNotEnabled()
        compose.onNodeWithTag("repeat-interval").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("repeat-save").assertIsNotEnabled()
        compose.onNodeWithTag("repeat-interval").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("repeat-save").assertIsEnabled()
        compose.onNodeWithTag("repeat-end-count").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-count").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("repeat-save").assertIsNotEnabled()
        compose.onNodeWithTag("repeat-end-never").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-save").assertIsEnabled()
    }
}
