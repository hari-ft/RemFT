package com.ftapps.remft.ui.tasks

import android.os.Looper
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeFontLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            RemftTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalTasksEnvironment provides tasks.environment,
                ) { content() }
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val results = mutableListOf<TextLayoutResult>()
        action(results)
        return results.firstOrNull()
    }

    private fun splitWords(): List<String> {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        val split = mutableListOf<String>()
        for (node in nodes) {
            val layout = layoutOf(node) ?: continue
            val text = layout.layoutInput.text.text
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) split += text
            }
        }
        return split
    }

    private fun ellipsized(text: String): Boolean {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.mapNotNull(::layoutOf).filter { it.layoutInput.text.text == text }.any { it.isLineEllipsized(0) || it.lineCount > 1 }
    }

    private fun detail(fontScale: Float): String {
        val id = runBlocking {
            tasks.repository.create(
                TaskDraft(title = "Renew passport", priority = "medium", dueAt = Instant.parse("2026-11-22T04:30:00Z"), isUrgent = true),
            )
        }
        show(fontScale) { TaskDetailScreen(taskId = id, onBack = {}) }
        waitFor { compose.onAllNodesWithTagCount("priority-dropdown") == 1 }
        return id
    }

    @Test
    fun atNormalSizeStatusAndPrioritySitSideBySide() {
        detail(1f)
        val status = compose.onNodeWithTag("status-dropdown").getBoundsInRoot()
        val priority = compose.onNodeWithTag("priority-dropdown").getBoundsInRoot()
        assertEquals(status.top, priority.top)
        assertTrue(priority.left > status.right)
    }

    @Test
    fun atDoubleFontTheDetailStacksFieldsAndSplitsNoWord() {
        detail(2f)
        val status = compose.onNodeWithTag("status-dropdown").getBoundsInRoot()
        val priority = compose.onNodeWithTag("priority-dropdown").getBoundsInRoot()
        val medium = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull(::layoutOf).first { it.layoutInput.text.text == "Medium" }
        assertEquals(2f, medium.layoutInput.density.fontScale)
        assertTrue(priority.top >= status.bottom)
        assertFalse(ellipsized("Medium"))
        assertFalse(ellipsized("22/11/2026"))
        assertFalse(ellipsized("Add date"))
        assertEquals(emptyList<String>(), splitWords())
    }

    @Test
    fun atDoubleFontTheMonthCalendarKeepsLabelsAndNumbersInTheirCells() {
        show(2f) {
            MonthCalendar(
                today = LocalDate.of(2026, 11, 20),
                markedDays = setOf(LocalDate.of(2026, 11, 22)),
                selectedDay = null,
                onDaySelected = {},
                modifier = Modifier.width(320.dp),
            )
        }
        waitFor { compose.onAllNodesWithTagCount("month-calendar") == 1 }
        val cell = compose.onNodeWithTag("calendar-day-2026-11-20").getBoundsInRoot()
        for (day in 1..7) {
            val node = compose.onNodeWithTag("calendar-weekday-$day", useUnmergedTree = true)
            val layout = layoutOf(node.fetchSemanticsNode())!!
            assertEquals(1, layout.lineCount)
            assertTrue("${layout.layoutInput.text}", layout.multiParagraph.intrinsics.maxIntrinsicWidth <= layout.layoutInput.constraints.maxWidth)
            val bounds = node.getBoundsInRoot()
            assertTrue("weekday $day ${bounds.right - bounds.left} vs ${cell.right - cell.left}", bounds.right - bounds.left <= cell.right - cell.left)
        }
        assertEquals(emptyList<String>(), splitWords())
        val visual = calendarDayNumberSize(2f).value * 2f
        assertTrue(visual <= 13f * CalendarMaxFontScale + 0.01f)
        assertEquals(13f, calendarDayNumberSize(1f).value)
    }
}
