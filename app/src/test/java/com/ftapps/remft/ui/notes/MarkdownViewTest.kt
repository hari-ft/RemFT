package com.ftapps.remft.ui.notes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestDatabases
import com.ftapps.remft.ui.notes.markdown.MarkdownView
import com.ftapps.remft.ui.notes.markdown.markdownDocument
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState

@RunWith(AndroidJUnit4::class)
class MarkdownViewTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(source: String, dark: Boolean = false, collapsed: Set<Int> = emptySet()) {
        val document = markdownDocument(source)
        compose.setContent {
            RemftTheme(darkTheme = dark) {
                MarkdownView(document = document, modifier = Modifier.fillMaxSize(), collapsed = collapsed, onToggleFold = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun conformanceDocumentRendersInBothThemes() {
        val source = TestDatabases.fixturesDir.resolve("markdown_conformance.md").readText()
        render(source)
        compose.onNodeWithTag("markdown-view").assertExists()
    }

    @Test
    fun readingModeChecklistMarksReportTheirStateAndAreNotButtons() {
        render("- [x] Jacket\n- [ ] Tent")
        val marks = compose.onAllNodesWithTag("markdown-check", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, marks.size)
        assertEquals(listOf(ToggleableState.On, ToggleableState.Off), marks.map { it.config.getOrNull(SemanticsProperties.ToggleableState) })
        assertEquals(listOf(null, null), marks.map { it.config.getOrNull(SemanticsActions.OnClick) })
    }

    @Test
    fun conformanceDocumentRendersDark() {
        val source = TestDatabases.fixturesDir.resolve("markdown_conformance.md").readText()
        render(source, dark = true)
        compose.onNodeWithTag("markdown-view").assertExists()
    }

    @Test
    fun attackCorpusStaysInertAndNeverStartsAnything() {
        val corpus = Json.parseToJsonElement(TestDatabases.fixturesDir.resolve("markdown_attack_corpus.json").readText()).jsonObject
        val body = corpus["cases"]!!.jsonArray.joinToString("\n\n") { it.jsonObject["text"]!!.jsonPrimitive.content }
        render("# Corpus\n\n$body")
        compose.onNodeWithTag("markdown-view").assertExists()
        val app = RuntimeEnvironment.getApplication() as Application
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test
    fun rawHtmlIsShownAsSource() {
        render("<div onclick=\"x()\">hi</div>\n\ntext")
        compose.onNodeWithTag("raw-html").assertExists()
        compose.onNodeWithText("HTML is shown as source, never run.").assertExists()
    }

    @Test
    fun remoteImagesAreNeverFetchedAndShowAChip() {
        render("![a remote picture](https://example.com/x.png)")
        compose.onNodeWithTag("image-chip").assertExists()
        compose.onNodeWithText("a remote picture").assertExists()
    }

    @Test
    fun unsafeLinksAreNotClickable() {
        render("[bad](javascript:alert(1))")
        compose.onNodeWithText("bad").performClick()
        val app = RuntimeEnvironment.getApplication() as Application
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test
    fun frontMatterIsCollapsible() {
        render("---\ntitle: Plan\n---\n\n# Body")
        compose.onNodeWithTag("front-matter").assertExists()
        compose.onNodeWithText("Document properties").performClick()
        compose.onNodeWithText("Plan").assertExists()
    }

    @Test
    fun foldedHeadingsHideTheirSection() {
        render("# A\n\nhidden text\n\n# B\n\nshown text", collapsed = setOf(0))
        compose.onNodeWithText("shown text").assertExists()
        compose.onNodeWithText("hidden text").assertDoesNotExist()
    }

    @Test
    fun mermaidAndCodeBlocksRender() {
        render("```mermaid\ngraph TD\nA[Start] --> B[End]\n```\n\n```python\nprint('hi')\n```\n\n\$\$\nE = mc^2\n\$\$")
        compose.onNodeWithTag("markdown-view").performScrollToNode(hasTestTag("math-block"))
        compose.onNodeWithTag("math-block").assertExists()
    }

    @Test
    fun theFoldButtonNamesWhatItDoes() {
        render("# A\n\nbody\n\n# B\n\nmore", collapsed = setOf(2))
        compose.onNodeWithTag("fold-0").assert(hasContentDescription("Collapse section"))
        compose.onNodeWithTag("fold-2").assert(hasContentDescription("Expand section"))
        compose.onAllNodesWithContentDescription("Outline").assertCountEquals(0)
    }

    @Test
    fun copiedStateResetsAfterAMoment() {
        render("```kotlin\nval x = 1\n```")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Copied").assertExists()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithContentDescription("Copy code").assertExists()
        compose.onNodeWithContentDescription("Copied").assertDoesNotExist()
    }
}
