package com.ftapps.remft.ui.scheduled

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.shell.AppShell
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.onAllNodesWithTagCount
import com.ftapps.remft.ui.tasks.onAllNodesWithTextCount
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
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class ScheduledScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()
    private val zone = ZoneId.of("Asia/Kolkata")

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun insert(id: String, title: String, due: String, status: String = "open", urgent: Boolean = false, priority: String = "none") {
        val ws = runBlocking { tasks.repository.workspaceId() }
        val at = UtcText.format(LocalDateTime.parse(due).atZone(zone).toInstant())
        tasks.holder.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tasks (id, workspace_id, title, status, due_at, is_urgent, priority) VALUES " +
                "('$id', '$ws', '$title', '$status', '$at', ${if (urgent) 1 else 0}, '$priority')",
        )
    }

    private fun show(dark: Boolean = false, fontScale: Float = 1f, onOpenTask: (String) -> Unit = {}) {
        compose.setContent {
            RemftTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalTasksEnvironment provides tasks.environment,
                ) {
                    ScheduledScreen(onBack = {}, onOpenTask = onOpenTask)
                }
            }
        }
    }

    private fun textLayouts(): List<TextLayoutResult> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                results.firstOrNull()
            }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().top.value

    private fun column(id: String, name: String): String? = tasks.query("SELECT $name FROM tasks WHERE id = '$id'").singleOrNull()?.get(name)

    @Test
    fun settingsRowOpensTheScreenAndBackReturnsToSettings() {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { AppShell() }
            }
        }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("settings-scheduled"))
        compose.onNodeWithText("Every open task with a time, soonest first").assertExists()
        assertTrue(top("settings-scheduled") > top("settings-completed"))
        compose.onNodeWithTag("settings-scheduled").performClick()
        waitFor { compose.onAllNodesWithTagCount("sched-empty") == 1 }
        compose.onNodeWithTag("screen-scheduled").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        waitFor { compose.onAllNodesWithTagCount("settings-scheduled") == 1 }
        compose.onNodeWithTag("settings-general").assertExists()
    }

    @Test
    fun rowsAppearInTimeOrderUnderTheirDayHeaders() {
        insert("01JA0000000000000000000001", "Pay the electricity bill", "2026-11-19T18:00", urgent = true)
        insert("01JA0000000000000000000002", "Water the plants", "2026-11-20T18:30", priority = "high")
        insert("01JA0000000000000000000003", "Call the plumber", "2026-11-20T12:00")
        insert("01JA0000000000000000000004", "Pack for the trip", "2026-11-21T07:00")
        insert("01JA0000000000000000000005", "Book train tickets", "2026-11-24T09:00")
        insert("01JA0000000000000000000006", "Already finished", "2026-11-20T13:00", status = "completed")
        show()
        waitFor { compose.onAllNodesWithTagCount("sched-row-01JA0000000000000000000005") == 1 }
        val order = listOf(
            "sched-header-overdue",
            "sched-row-01JA0000000000000000000001",
            "sched-header-2026-11-20",
            "sched-row-01JA0000000000000000000003",
            "sched-row-01JA0000000000000000000002",
            "sched-header-2026-11-21",
            "sched-row-01JA0000000000000000000004",
            "sched-header-2026-11-24",
            "sched-row-01JA0000000000000000000005",
        )
        val tops = order.map(::top)
        assertEquals(tops.sorted(), tops)
        assertEquals(0, compose.onAllNodesWithTagCount("sched-row-01JA0000000000000000000006"))
        assertEquals(1, compose.onAllNodesWithTextCount("Overdue"))
        assertEquals(1, compose.onAllNodesWithTextCount("Today"))
        assertEquals(1, compose.onAllNodesWithTextCount("Tomorrow"))
        assertEquals(1, compose.onAllNodesWithTextCount("Tue, 24 Nov"))
        assertEquals(1, compose.onAllNodesWithTextCount("12:00 PM"))
        assertEquals(1, compose.onAllNodesWithTextCount("19 Nov, 6:00 PM"))
        assertEquals(1, compose.onAllNodesWithTextCount("Urgent"))
    }

    @Test
    fun theDateFormatSettingShapesHeadersAndTimes() {
        runBlocking { tasks.settings.set("remft_date_format", "iso") }
        insert("01JA0000000000000000000001", "Book train tickets", "2026-11-24T09:00")
        show(dark = true)
        waitFor { compose.onAllNodesWithTextCount("09:00") == 1 }
        assertEquals(1, compose.onAllNodesWithTextCount("Tue, 24-11"))
    }

    @Test
    fun tappingARowOpensTheTask() {
        insert("01JA0000000000000000000001", "Call the plumber", "2026-11-20T12:00")
        var opened: String? = null
        show(onOpenTask = { opened = it })
        waitFor { compose.onAllNodesWithTagCount("sched-row-01JA0000000000000000000001") == 1 }
        compose.onNodeWithText("Call the plumber").performClick()
        assertEquals("01JA0000000000000000000001", opened)
    }

    @Test
    fun checkingCompletesTheTaskAndItLeavesTheList() {
        insert("01JA0000000000000000000001", "Call the plumber", "2026-11-20T12:00")
        insert("01JA0000000000000000000002", "Water the plants", "2026-11-20T18:00")
        show()
        waitFor { compose.onAllNodesWithTagCount("sched-check-01JA0000000000000000000001") == 1 }
        compose.onNodeWithTag("sched-check-01JA0000000000000000000001").performClick()
        waitFor { column("01JA0000000000000000000001", "status") == "completed" }
        waitFor { compose.onAllNodesWithTagCount("sched-row-01JA0000000000000000000001") == 0 }
        waitFor { compose.onAllNodesWithTextCount("“Call the plumber” completed") == 1 }
        compose.onNodeWithTag("sched-row-01JA0000000000000000000002").assertExists()
        compose.onNodeWithText("Undo").performClick()
        waitFor { column("01JA0000000000000000000001", "status") == "open" }
        waitFor { compose.onAllNodesWithTagCount("sched-row-01JA0000000000000000000001") == 1 }
    }

    @Test
    fun atDoubleFontNoRowTextIsCutOff() {
        insert("01JA0000000000000000000001", "Pay the electricity bill before the office closes", "2026-11-19T18:00", urgent = true, priority = "high")
        insert("01JA0000000000000000000002", "Book train tickets", "2026-11-24T09:00", priority = "low")
        show(fontScale = 2f)
        waitFor { compose.onAllNodesWithTagCount("sched-row-01JA0000000000000000000002") == 1 }
        val layouts = textLayouts()
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all { it.layoutInput.density.fontScale == 2f })
        val clipped = layouts.filter { layout -> (0 until layout.lineCount).any(layout::isLineEllipsized) }
        assertEquals(emptyList<String>(), clipped.map { it.layoutInput.text.text })
    }

    @Test
    fun anEmptyListShowsTheIllustratedEmptyState() {
        insert("01JA0000000000000000000001", "Already finished", "2026-11-20T13:00", status = "completed")
        show(dark = true)
        waitFor { compose.onAllNodesWithTagCount("sched-empty") == 1 }
        compose.onNodeWithText("Nothing scheduled").assertIsDisplayed()
    }
}
