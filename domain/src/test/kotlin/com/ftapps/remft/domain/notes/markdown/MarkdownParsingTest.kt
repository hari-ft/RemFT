package com.ftapps.remft.domain.notes.markdown

import com.ftapps.remft.domain.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParsingTest {
    private fun all(node: Node): List<Node> {
        val out = ArrayList<Node>()
        fun walk(n: Node) {
            out.add(n)
            n.children().forEach(::walk)
        }
        walk(node)
        return out
    }

    private inline fun <reified T> nodes(source: String): List<T> = all(MarkdownParsing.parse(source).document).filterIsInstance<T>()

    @Test
    fun parsesGfmExtensions() {
        val source = "| a | b |\n| --- | --- |\n| 1 | 2 |\n\n- [x] done\n\n~~gone~~ https://example.com\n\nnote[^1]\n\n[^1]: foot"
        assertEquals(1, nodes<TableBlock>(source).size)
        assertEquals(1, nodes<TaskListItemMarker>(source).size)
        assertEquals(1, nodes<Strikethrough>(source).size)
        assertEquals("https://example.com", nodes<Link>(source).single().destination)
        assertEquals(1, nodes<FootnoteDefinition>(source).size)
    }

    @Test
    fun parsesHighlight() {
        val highlight = nodes<Highlight>("a ==marked **bold**== b").single()
        assertEquals("marked bold", highlight.textContent())
    }

    @Test
    fun parsesMath() {
        assertEquals("x^2", nodes<MathInline>("so \$x^2\$ here").single().literal)
        assertTrue(nodes<MathInline>("costs \$5 and \$10").isEmpty())
        assertEquals("a+b\nc", nodes<MathBlock>("\$\$\na+b\nc\n\$\$\n\nafter").single().literal)
    }

    @Test
    fun parsesAlerts() {
        val alert = nodes<Alert>("> [!WARNING]\n> Careful now").single()
        assertEquals(AlertKind.Warning, alert.kind)
        assertEquals("Careful now", alert.textContent())
        assertTrue(nodes<Alert>("> [!NOTE] inline text").isEmpty())
    }

    @Test
    fun frontMatterOnlyAtTheTopKeepsLineNumbers() {
        val parsed = MarkdownParsing.parse("---\ntitle: x\n---\n# Head\n")
        val front = parsed.document.firstChild as FrontMatter
        assertEquals("title: x", front.literal)
        val heading = parsed.document.children().filterIsInstance<Heading>().single()
        assertEquals(3, heading.startLine())
        assertTrue(nodes<FrontMatter>("text\n---\na\n---\n").isEmpty())
        assertTrue(nodes<FrontMatter>("---\nno close").isEmpty())
    }

    @Test
    fun emojiShortcodesExpandOutsideCode() {
        val emoji = nodes<Emoji>("hi :smile: and `:smile:`")
        assertEquals(1, emoji.size)
        assertEquals("smile", emoji.single().shortcode)
        assertTrue(nodes<Emoji>("time 10:30:00 :notanemoji:").isEmpty())
    }

    @Test
    fun linkAllowlist() {
        assertTrue(MarkdownParsing.isSafeLink("https://example.com"))
        assertTrue(MarkdownParsing.isSafeLink("mailto:a@example.com"))
        assertTrue(MarkdownParsing.isSafeLink("remft://task/01HZZZZZZZZZZZZZZZZZZZZZZZ"))
        assertTrue(MarkdownParsing.isSafeLink("#heading"))
        assertFalse(MarkdownParsing.isSafeLink("javascript:alert(1)"))
        assertFalse(MarkdownParsing.isSafeLink(" JavaScript:alert(1)"))
        assertFalse(MarkdownParsing.isSafeLink("data:text/html,x"))
        assertFalse(MarkdownParsing.isSafeLink("intent://x"))
        assertFalse(MarkdownParsing.isSafeLink(""))
        assertEquals("heading", MarkdownParsing.anchorOf("#heading"))
        assertNull(MarkdownParsing.anchorOf("#"))
    }

    @Test
    fun onlyRemftFileImagesResolve() {
        assertEquals("01HZZZZZZZZZZZZZZZZZZZZZZZ", MarkdownParsing.remftFileId("remft://file/01hzzzzzzzzzzzzzzzzzzzzzzz"))
        assertNull(MarkdownParsing.remftFileId("https://example.com/a.png"))
        assertNull(MarkdownParsing.remftFileId("remft://file/../etc"))
    }

    @Test
    fun rawHtmlStaysAnInertBlock() {
        val html = nodes<HtmlBlock>("<script>alert(1)</script>\n\ntext")
        assertEquals(1, html.size)
        assertTrue(html.single().literal.contains("<script>"))
    }

    @Test
    fun mermaidAndMathFencesAreRecognised() {
        val fences = nodes<FencedCodeBlock>("```mermaid\ngraph TD\nA-->B\n```\n\n```math\nx\n```")
        assertNotNull(MarkdownParsing.mermaidSource(fences[0]))
        assertNotNull(MarkdownParsing.mathFenceSource(fences[1]))
    }

    @Test
    fun conformanceDocumentParses() {
        val parsed = MarkdownParsing.parse(Fixtures.text("markdown_conformance.md"))
        val blocks = parsed.document.children()
        assertTrue(blocks.size > 50)
        assertTrue(blocks.any { it is Heading })
        assertTrue(blocks.any { it is CustomBlock })
        assertTrue(Outline.headings(parsed).isNotEmpty())
    }

    @Test
    fun attackCorpusParsesAndUnsafeLinksAreFlagged() {
        val corpus = Json.parseToJsonElement(Fixtures.text("markdown_attack_corpus.json")).jsonObject
        val cases = corpus["cases"]!!.jsonArray
        assertTrue(cases.size > 5)
        cases.forEach { entry ->
            val text = entry.jsonObject["text"]!!.jsonPrimitive.content
            val parsed = MarkdownParsing.parse("# Title\n\n$text\n\n> $text\n\n- $text")
            parsed.document.accept(object : AbstractVisitor() {
                override fun visit(link: Link) {
                    val href = link.destination
                    if (MarkdownParsing.isSafeLink(href)) {
                        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(href.trim())?.groupValues?.get(1)?.lowercase()
                        assertTrue(href, scheme == null || scheme in MarkdownParsing.linkSchemes)
                    }
                    visitChildren(link)
                }

                override fun visit(customNode: CustomNode) {
                    visitChildren(customNode)
                }
            })
            assertTrue(parsed.document.firstChild is Heading || parsed.document.firstChild is Paragraph || parsed.document.firstChild != null)
        }
    }
}
