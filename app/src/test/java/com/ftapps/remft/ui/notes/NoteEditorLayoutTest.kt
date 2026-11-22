package com.ftapps.remft.ui.notes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.notes.editor.NoteEditorScreen
import com.ftapps.remft.ui.notes.editor.NoteEditorViewModel
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteEditorLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val env = NotesTestEnvironment()

    @After
    fun tearDown() = env.close()

    private var density = 1f

    private fun show(id: String?, fontScale: Float) {
        val viewModel = NoteEditorViewModel(SavedStateHandle(mapOf("id" to id, "external" to false)), env.repository, env.external, env.settings)
        val export = env.exportViewModel()
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                RemftTheme { NoteEditorScreen(viewModel, export, onBack = {}) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-toolbar").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private fun assertLabelInFull(text: String) {
        val layout = layoutOf(text)
        assertEquals(text, 1, layout.lineCount)
        assertFalse(text, layout.didOverflowHeight)
        assertFalse(text, layout.isLineEllipsized(0))
        assertTrue(text, layout.getLineRight(0) <= layout.size.width + 0.5f)
        val bounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(text, bounds.width >= layout.size.width - 1f)
    }

    @Test
    fun modeLabelsShowInFullAtNormalFontScale() {
        show(null, 1f)
        assertLabelInFull("Source")
        assertLabelInFull("Reading")
    }

    @Test
    fun modeLabelsStayReadableAtLargerFontScale() {
        show(null, 1.3f)
        assertLabelInFull("Source")
        assertLabelInFull("Reading")
    }

    @Test
    fun theWordCountDoesNotPushTheFormatButtonsOffAtDoubleFontScale() {
        val id = runBlocking { env.repository.create("Long", (1..1000).joinToString(" ") { "w" }) }
        show(id, 2f)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1000 words", substring = true).fetchSemanticsNodes().isNotEmpty() }
        listOf("action-bold", "action-italic").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag ${bounds.width}", bounds.width >= ICON_BUTTON_DP * density - 0.5f)
        }
    }

    private companion object {
        const val ICON_BUTTON_DP = 40f
    }
}
