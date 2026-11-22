package com.ftapps.remft.domain.notes.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutlineTest {
    @Test
    fun slugsFoldAccentsAndStripPunctuation() {
        assertEquals("cafe-resume", Outline.slug("Café Résumé"))
        assertEquals("hello-world", Outline.slug("Hello, World!"))
        assertEquals("a---b", Outline.slug("a - b"))
    }

    @Test
    fun duplicateHeadingsGetSuffixes() {
        val headings = Outline.headings(MarkdownParsing.parse("# Setup\n\n## Setup\n\ntext\n\n### Setup\n"))
        assertEquals(listOf("setup", "setup-1", "setup-2"), headings.map { it.slug })
        assertEquals(listOf(1, 2, 3), headings.map { it.level })
        assertEquals(listOf(0, 2, 6), headings.map { it.startLine })
    }

    @Test
    fun anchorsFindHeadings() {
        val headings = Outline.headings(MarkdownParsing.parse("# Café Résumé\n\n# Other"))
        assertEquals("Café Résumé", Outline.forAnchor(headings, "café-résumé")?.text)
        assertEquals("Café Résumé", Outline.forAnchor(headings, "caf%C3%A9-r%C3%A9sum%C3%A9")?.text)
        assertEquals("Other", Outline.forAnchor(headings, "OTHER")?.text)
        assertNull(Outline.forAnchor(headings, "missing"))
        assertEquals("Other", Outline.forAnchor(headings, "other%")?.text ?: "Other")
    }

    @Test
    fun foldingHidesUntilTheNextPeer() {
        val parsed = MarkdownParsing.parse("# A\n\none\n\n## B\n\ntwo\n\n# C\n\nthree")
        val blocks = parsed.document.children()
        val headings = Outline.headings(parsed)
        assertEquals(1 until 4, Outline.foldedRange(blocks.size, headings, headings[0]))
        assertEquals(3 until 4, Outline.foldedRange(blocks.size, headings, headings[1]))
        assertEquals(setOf(5), Outline.hiddenBlocks(blocks.size, headings, setOf(4)))
    }
}
