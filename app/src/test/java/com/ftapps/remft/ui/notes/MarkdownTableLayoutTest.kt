package com.ftapps.remft.ui.notes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.notes.markdown.MarkdownView
import com.ftapps.remft.ui.notes.markdown.markdownDocument
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownTableLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tableCellsInARowShareTheTallestHeight() {
        val long = List(60) { "word" }.joinToString(" ")
        val document = markdownDocument("| a | b |\n| --- | --- |\n| short | $long |")
        compose.setContent { RemftTheme { MarkdownView(document = document, modifier = Modifier.fillMaxSize()) } }
        compose.waitForIdle()
        val cells = compose.onAllNodesWithTag("table-cell").fetchSemanticsNodes()
        assertEquals(4, cells.size)
        val header = cells[0].boundsInRoot
        val short = cells[2].boundsInRoot
        val tall = cells[3].boundsInRoot
        assertTrue("$tall vs $header", tall.height > header.height * 2)
        assertEquals("$tall vs $short", tall.height, short.height, 0.5f)
        assertEquals(tall.top, short.top, 0.5f)
    }
}
