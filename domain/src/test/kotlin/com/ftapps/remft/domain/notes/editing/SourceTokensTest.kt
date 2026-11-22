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
}
