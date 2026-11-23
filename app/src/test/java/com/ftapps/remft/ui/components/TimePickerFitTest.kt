package com.ftapps.remft.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.tasks.TaskTimePickerDialog
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TimePickerFitTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(is24Hour: Boolean) {
        compose.setContent {
            RemftTheme(darkTheme = false) {
                TaskTimePickerDialog(initial = LocalTime.of(21, 41), is24Hour = is24Hour, onDismiss = {}, onPicked = {})
            }
        }
        compose.waitForIdle()
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val results = mutableListOf<TextLayoutResult>()
        action(results)
        return results.firstOrNull()
    }

    private fun clipped(layout: TextLayoutResult): Boolean {
        if (layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines) return true
        val text = layout.layoutInput.text.text
        if (text.isNotEmpty() && text.none { it.isWhitespace() } && layout.lineCount > 1) return true
        return (0 until layout.lineCount).any { line ->
            layout.isLineEllipsized(line) || layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 0.5f
        }
    }

    private fun label(node: SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.TestTag)
            ?: "node ${node.id}")

    private fun assertFits(is24Hour: Boolean) {
        show(is24Hour)
        val metrics = RuntimeEnvironment.getApplication().resources.displayMetrics
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode()
        val frame = dialog.boundsInRoot
        assertTrue("dialog $frame wider than the screen ${metrics.widthPixels}", frame.width <= metrics.widthPixels + 0.5f)
        assertTrue("dialog $frame taller than the screen ${metrics.heightPixels}", frame.height <= metrics.heightPixels + 0.5f)
        val nodes = compose.onAllNodes(hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        val inside = Rect(frame.left - 0.5f, frame.top - 0.5f, frame.right + 0.5f, frame.bottom + 0.5f)
        for (node in nodes) {
            val bounds = node.boundsInRoot
            if (bounds.isEmpty) continue
            assertTrue("${label(node)} at $bounds is cut off by the dialog $frame", inside.contains(bounds.topLeft) && inside.contains(bounds.bottomRight))
            val layout = layoutOf(node) ?: continue
            assertFalse("${label(node)} is clipped: ${layout.size} in $bounds", clipped(layout))
        }
        val shown = nodes.any { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
            text == "41"
        }
        assertTrue("the selected minute is not shown", shown)
    }

    @Test
    fun twelveHourAtNormalSize() = assertFits(is24Hour = false)

    @Test
    fun twentyFourHourAtNormalSize() = assertFits(is24Hour = true)

    @Test
    @Config(fontScale = 1.3f)
    fun twelveHourAtLargeText() = assertFits(is24Hour = false)

    @Test
    @Config(fontScale = 1.3f)
    fun twentyFourHourAtLargeText() = assertFits(is24Hour = true)

    @Test
    @Config(fontScale = 2.0f)
    fun twelveHourAtLargestText() = assertFits(is24Hour = false)

    @Test
    @Config(fontScale = 2.0f)
    fun twentyFourHourAtLargestText() = assertFits(is24Hour = true)
}

@Config(qualifiers = "w360dp-h640dp-port")
class TimePickerFitNarrowPortraitTest : TimePickerFitTest()

@Config(qualifiers = "w411dp-h891dp-port")
class TimePickerFitPhonePortraitTest : TimePickerFitTest()

@Config(qualifiers = "w640dp-h360dp-land")
class TimePickerFitNarrowLandscapeTest : TimePickerFitTest()

@Config(qualifiers = "w891dp-h411dp-land")
class TimePickerFitPhoneLandscapeTest : TimePickerFitTest()

@Config(qualifiers = "w1280dp-h520dp-land")
class TimePickerFitWideShortTest : TimePickerFitTest()
