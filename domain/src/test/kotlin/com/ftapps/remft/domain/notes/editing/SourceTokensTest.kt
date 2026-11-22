package com.ftapps.remft.domain.notes.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SourceTokensTest {
    private fun covers(text: String) {
        val tokens = SourceTokens.scan(text)
        var cursor = 0
        tokens.forEach {
            assertEquals(cursor, it.start)
            assertTrue(it.end > it.start)
            cursor = it.end
        }
        assertEquals(text.length, cursor)
    }

    @Test
    fun tokensCoverEveryCharacterExactlyOnce() {
        covers("# Title **b**\n> quote *i*\n- [ ] task `code` [l](u)\n```\nfence\n```\n---\n==m== ~~s~~\n")
        val random = Random(7)
        val alphabet = "#*_`~=[]()!>-+ 1.\nabc"
        repeat(300) {
            covers(String(CharArray(random.nextInt(0, 60)) { alphabet[random.nextInt(alphabet.length)] }))
        }
    }

    @Test
    fun classifiesHeadingsAndInlines() {
        val tokens = SourceTokens.scan("## Hi **b**")
        assertEquals(MdTokenKind.Marker, tokens[0].kind)
        assertEquals(MdTokenKind.Heading2, tokens[1].kind)
        assertTrue(tokens.any { it.kind == MdTokenKind.Bold })
    }

    @Test
    fun fencedLinesAreCodeBlock() {
        val tokens = SourceTokens.scan("```\nx = 1\n```")
        assertTrue(tokens.any { it.kind == MdTokenKind.CodeBlock && it.line == 1 })
    }

    @Test
    fun linksSplitTextAndTarget() {
        val text = "[label](https://e.com)"
        val link = SourceTokens.scan(text).first { it.kind == MdTokenKind.Link }
        assertEquals("label", text.substring(link.start, link.end))
    }

    @Test
    fun lineOfCountsNewlines() {
        assertEquals(2, SourceTokens.lineOf("a\nb\nc", 4))
    }

    @Test
    fun smallNotesAreStyledWhole() {
        assertEquals(null, SourceTokens.window(SourceTokens.LARGE_TEXT, 10))
    }

    @Test
    fun largeNotesAreStyledOnlyAroundTheCaretWithTheSameTokens() {
        val block = "# Heading\n\nSome **bold** and `code` text.\n\n```\nfenced line\n```\n\n- item [link](https://e.com)\n\n"
        val text = block.repeat(SourceTokens.LARGE_TEXT / block.length + 400)
        val caret = text.length / 2
        val window = SourceTokens.window(text.length, caret)!!
        assertTrue(caret in window)
        assertTrue(window.last - window.first <= 2 * SourceTokens.WINDOW_RADIUS)
        val full = SourceTokens.scan(text)
        val partial = SourceTokens.scan(text, window)
        assertTrue(partial.isNotEmpty())
        assertTrue(partial.size < full.size / 2)
        val firstLine = partial.first().line
        val lastLine = partial.last().line
        assertEquals(full.filter { it.line in firstLine..lastLine }, partial)
        assertTrue(partial.all { it.end >= window.first && it.start <= window.last + block.length })
    }

    @Test
    fun aFenceOpenedBeforeTheWindowStillMarksCodeInsideIt() {
        val filler = "plain words here\n".repeat(SourceTokens.LARGE_TEXT / 17 + 10)
        val text = "~~~\n" + filler + "still code\n~~~\n" + filler
        val caret = text.indexOf("still code")
        val tokens = SourceTokens.scan(text, SourceTokens.window(text.length, caret))
        val inside = tokens.first { it.start == caret }
        assertEquals(MdTokenKind.CodeBlock, inside.kind)
    }
}
