package com.ftapps.remft.ui.shell

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.repeat.RepeatFreq
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
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
class AppUndoHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun textCount(text: String): Int = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun deletingFromTheTaskViewKeepsAWorkingUndoOnHome() {
        val id = runBlocking { tasks.repository.create(TaskDraft(title = "Linked task")) }
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { AppShell(deepLink = "remft://task/$id") }
            }
        }
        waitForTag("task-delete")
        compose.onNodeWithTag("task-delete").performClick()
        waitForTag("screen-home")
        waitFor { textCount("Undo") == 1 }
        compose.onNodeWithText("“Linked task” deleted").assertExists()
        assertEquals(1, tasks.query("SELECT id FROM tasks").size)
        compose.onNodeWithText("Undo").performClick()
        waitFor { textCount("Linked task") == 1 }
        assertEquals(1, tasks.query("SELECT id FROM tasks").size)
        assertEquals(0, tasks.query("SELECT id FROM trash_items").size)
    }

    @Test
    fun recreatingTheScreenDuringTheUndoWindowKeepsTheUndo() {
        val id = runBlocking { tasks.repository.create(TaskDraft(title = "Old errand")) }
        var alive by mutableStateOf(true)
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { if (alive) AppShell() }
            }
        }
        waitFor { textCount("Old errand") == 1 }
        compose.onNodeWithTag("swipe-$id").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-delete-$id").performClick()
        waitFor { textCount("Undo") == 1 }
        alive = false
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, tasks.query("SELECT id FROM tasks").size)
        alive = true
        waitFor { textCount("Undo") == 1 }
        assertEquals(1, tasks.query("SELECT id FROM tasks").size)
        compose.onNodeWithText("Undo").performClick()
        waitFor { textCount("Old errand") == 1 }
        assertEquals(0, tasks.query("SELECT id FROM trash_items").size)
    }

    @Test
    fun deletingThisAndFutureOnesFromTheTaskViewCanBeUndoneOnHome() {
        val due = Instant.parse("2026-11-21T04:30:00Z")
        val id = runBlocking { tasks.repository.create(TaskDraft(title = "Water the plants", dueAt = due)) }
        val seriesId = runBlocking { tasks.series.start(id, RepeatRule(freq = RepeatFreq.Weekly), due) }!!
        val total = tasks.query("SELECT id FROM tasks").size
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { AppShell(deepLink = "remft://task/$id") }
            }
        }
        waitForTag("task-delete")
        compose.onNodeWithTag("task-delete").performClick()
        waitForTag("delete-scope-future")
        compose.onNodeWithTag("delete-scope-future").performClick()
        waitForTag("screen-home")
        waitFor { textCount("Undo") == 1 }
        assertEquals(total, tasks.pendingDeletes.ids.value.size)
        assertEquals(total, tasks.query("SELECT id FROM tasks").size)
        compose.onNodeWithText("Undo").performClick()
        waitFor { tasks.pendingDeletes.ids.value.isEmpty() }
        assertEquals(total, tasks.query("SELECT id FROM tasks").size)
        assertEquals(1, tasks.query("SELECT id FROM task_series WHERE id = '$seriesId'").size)
        assertEquals(0, tasks.query("SELECT id FROM trash_items").size)
    }
}
