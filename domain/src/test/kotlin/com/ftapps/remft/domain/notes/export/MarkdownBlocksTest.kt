package com.ftapps.remft.domain.notes.export

import com.ftapps.remft.domain.notes.markdown.AlertKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {
    private val fileId = "01HZX3K5V7Q8R9S0T1V2W3X4Y5"

    @Test
    fun headingsParagraphsAndInlineStyles() {
        val blocks = MarkdownBlocks.parse("# One\n\n## Two\n\n#### Four\n\nPlain **bold** *it* ~~gone~~ `code` ==mark== [x](https://example.com)")
        assertEquals(NoteBlock.Heading(1, listOf(NoteSpan("One"))), blocks[0])
        assertEquals(2, (blocks[1] as NoteBlock.Heading).level)
        assertEquals(4, (blocks[2] as NoteBlock.Heading).level)
        val spans = (blocks[3] as NoteBlock.Paragraph).spans
        assertTrue(spans.any { it.text == "bold" && it.bold })
        assertTrue(spans.any { it.text == "it" && it.italic })
        assertTrue(spans.any { it.text == "gone" && it.strike })
        assertTrue(spans.any { it.text == "code" && it.code })
        assertTrue(spans.any { it.text == "mark" && it.mark })
        assertTrue(spans.any { it.text == "x" && it.link == "https://example.com" })
    }

    @Test
    fun unsafeLinksLoseTheirTarget() {
        val spans = (MarkdownBlocks.parse("[bad](javascript:alert(1))").single() as NoteBlock.Paragraph).spans
        assertEquals("bad", spans.plainText())
        assertTrue(spans.all { it.link == null })
    }

    @Test
    fun nestedListsCarryIndentAndOrdinals() {
        val blocks = MarkdownBlocks.parse("- a\n  1. one\n  2. two\n- b\n\n3. three\n4. four")
        val items = blocks.filterIsInstance<NoteBlock.ListItem>()
        assertEquals(listOf("a", "one", "two", "b", "three", "four"), items.map { it.text })
        assertEquals(listOf(0, 1, 1, 0, 0, 0), items.map { it.indent })
        assertEquals(NoteListKind.Bullet, items[0].kind)
        assertEquals(listOf(1, 2), items.subList(1, 3).map { it.ordinal })
        assertEquals(listOf(3, 4), items.subList(4, 6).map { it.ordinal })
    }

    @Test
    fun taskItemsBecomeCheckedAndUnchecked() {
        val items = MarkdownBlocks.parse("- [ ] open\n- [x] done").filterIsInstance<NoteBlock.ListItem>()
        assertEquals(NoteListKind.Unchecked, items[0].kind)
        assertEquals("open", items[0].text)
        assertEquals(NoteListKind.Checked, items[1].kind)
        assertEquals("done", items[1].text)
        assertNull(items[1].ordinal)
    }

    @Test
    fun tablesKeepRowsAndAlignment() {
        val table = MarkdownBlocks.parse("| a | b | c |\n|:--|:-:|--:|\n| 1 | **2** | 3 |").single() as NoteBlock.Table
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), table.rows)
        assertEquals(
            listOf(NoteTableAlignment.Left, NoteTableAlignment.Center, NoteTableAlignment.Right),
            table.alignments,
        )
    }

    @Test
    fun remftImagesSplitParagraphsOtherImagesBecomeAltText() {
        val blocks = MarkdownBlocks.parse("before ![pic](remft://file/$fileId) after\n\nsee ![remote](https://example.com/a.png) here")
        assertEquals(4, blocks.size)
        assertEquals("before", blocks[0].text)
        assertEquals(NoteBlock.Image(fileId, "pic"), blocks[1])
        assertEquals("after", blocks[2].text)
        assertEquals("see remote here", blocks[3].text)
        assertTrue(blocks[3] is NoteBlock.Paragraph)
    }

    @Test
    fun alertsKeepKindAndBody() {
        val callout = MarkdownBlocks.parse("> [!WARNING]\n> Careful **now**").single() as NoteBlock.Callout
        assertEquals(AlertKind.Warning, callout.kind)
        assertEquals("Careful now", callout.text)
        val quote = MarkdownBlocks.parse("> just a quote").single()
        assertEquals(NoteBlock.Quote(listOf(NoteSpan("just a quote"))), quote)
    }

    @Test
    fun footnotesBecomeASectionAtTheEnd() {
        val blocks = MarkdownBlocks.parse("Text[^b] and[^a].\n\n[^a]: Alpha note.\n[^b]: Beta note.\n\nAfter.")
        val first = blocks.first() as NoteBlock.Paragraph
        val refs = first.spans.filter { it.superscript }
        assertEquals(listOf("1", "2"), refs.map { it.text })
        assertEquals("After.", blocks[1].text)
        assertEquals(NoteBlock.Divider, blocks[2])
        val notes = blocks.drop(3).map { it as NoteBlock.ListItem }
        assertEquals(listOf("Beta note.", "Alpha note."), notes.map { it.text })
        assertEquals(listOf(1, 2), notes.map { it.ordinal })
    }

    @Test
    fun frontMatterCodeMermaidMathAndDivider() {
        val md = "---\ntitle: T\n---\n\n```kotlin\nval x = 1\n```\n\n```mermaid\ngraph TD\n```\n\n$$\nx^2\n$$\n\n---\n\n<div>raw</div>"
        val blocks = MarkdownBlocks.parse(md)
        assertEquals(NoteBlock.FrontMatter("title: T"), blocks[0])
        assertEquals(NoteBlock.Code("val x = 1", "kotlin"), blocks[1])
        assertEquals(NoteBlock.Figure(NoteFigureKind.Mermaid, "graph TD", 0), blocks[2])
        assertEquals(NoteBlock.Figure(NoteFigureKind.Math, "x^2", 0), blocks[3])
        assertEquals(NoteBlock.Divider, blocks[4])
        assertEquals("<div>raw</div>", blocks[5].text)
    }

    @Test
    fun inlineMathIsCodeAndEmojiIsGlyph() {
        val spans = (MarkdownBlocks.parse("Area \$a^2\$ :rocket:").single() as NoteBlock.Paragraph).spans
        assertTrue(spans.any { it.text == "a^2" && it.code })
        assertFalse(spans.plainText().contains(":rocket:"))
    }
}
