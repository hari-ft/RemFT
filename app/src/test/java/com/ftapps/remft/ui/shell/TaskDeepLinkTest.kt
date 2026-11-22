package com.ftapps.remft.ui.shell

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class TaskDeepLinkTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun show(link: String) {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { AppShell(deepLink = link) }
            }
        }
    }

    private fun waitForTag(tag: String) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun taskLinkOpensTheTaskView() {
        val id = runBlocking { tasks.repository.create(TaskDraft(title = "Linked task")) }
        show("remft://task/$id")
        waitForTag("screen-task")
        waitForTag("task-title")
    }

    @Test
    fun dayLinkSelectsThatDayOnHome() {
        runBlocking { tasks.repository.create(TaskDraft(title = "Dentist", dueAt = Instant.parse("2026-11-25T05:00:00Z"))) }
        show("remft://day/2026-11-25")
        waitForText("Selected day · 1")
    }

    @Test
    fun addLinkOpensTheCreateSheet() {
        show("remft://add")
        waitForTag("task-sheet-title")
    }

    @Test
    fun unknownLinksOpenHome() {
        show("remft://task/not-an-id")
        waitForTag("screen-home")
    }

    @Test
    @Config(qualifiers = "w1000dp-h900dp")
    fun wideHomeShowsTheSideCalendar() {
        show("remft://open")
        waitForTag("month-calendar")
        waitForTag("nav-rail")
    }
}
